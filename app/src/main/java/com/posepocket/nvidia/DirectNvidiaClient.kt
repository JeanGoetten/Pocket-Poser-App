package com.posepocket.nvidia

import android.util.Base64
import com.google.protobuf.ByteString
import com.posepocket.gateway.NvidiaKeyStore
import com.posepocket.media.PreparedVideo
import com.posepocket.pose.*
import com.posepocket.privacy.ConsentGate
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.*
import nvidia.ai4m.body_pose.v1.BodyPose.*
import nvidia.ai4m.body_pose.v1.BodyPoseServiceGrpc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed TLS destination; user credentials never go through a Pocket Poser server. */
class DirectNvidiaClient(
    private val gate:ConsentGate,
    private val directory:File,
    private val channelFactory:()->ManagedChannel={ OkHttpChannelBuilder.forAddress("grpc.nvcf.nvidia.com",443).useTransportSecurity().maxInboundMessageSize(2*1024*1024).build() },
) {
    private val active=AtomicReference<ManagedChannel?>()
    fun interrupt() { active.getAndSet(null)?.shutdownNow() }

    suspend fun infer(key:String,video:DeviceVideo,input:PreparedVideo,onPhase:(String)->Unit):PoseClip=withContext(Dispatchers.IO) {
        NvidiaKeyStore.validate(key);gate.withConsent { }
        require(video.boxes.isNotEmpty()&&video.frames in 2..18000&&video.file.length() in 1..50_000_000)
        require(video.boxes.map { it.frame }.distinct().size==video.boxes.size)
        require(video.boxes.all { it.frame in 0 until video.frames && listOf(it.x,it.y,it.width,it.height).all(Float::isFinite)&&it.width>0&&it.height>0&&it.x<video.width&&it.y<video.height&&it.x+it.width>0&&it.y+it.height>0 })
        directory.mkdirs()
        val output=File(directory,"${UUID.randomUUID()}.jsonl")
        val partial=File(directory,"${output.name}.partial")
        val channel=channelFactory()
        check(active.compareAndSet(null,channel)) { "Já há uma inferência em andamento." }
        val headers=Metadata().apply {
            put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer $key")
            put(Metadata.Key.of("function-id",Metadata.ASCII_STRING_MARSHALLER),FUNCTION_ID)
        }
        val completed=CompletableDeferred<Unit>()
        val request=AtomicReference<ClientCallStreamObserver<BodyPoseRequest>?>()
        val lock=Any()
        val writer=partial.bufferedWriter()
        var bytes=0L;var flushed=false;var bodies=false
        var header:String?=null
        val seen=HashSet<Int>()
        val uploadDone=AtomicBoolean(false)
        try {
            val observer=object:ClientResponseObserver<BodyPoseRequest,BodyPoseResponse> {
                override fun beforeStart(stream:ClientCallStreamObserver<BodyPoseRequest>) { request.set(stream) }
                override fun onNext(value:BodyPoseResponse) {
                    try {
                        gate.withConsent { }
                        synchronized(lock) {
                            check(!completed.isCompleted)
                            if(value.hasServiceInfo())header=Base64.encodeToString(value.toByteArray(),Base64.NO_WRAP)
                            if(value.ready) {
                                require(!flushed&&value.frameId in 0 until video.frames&&seen.add(value.frameId)) { "Resposta NVIDIA contém quadro inválido ou duplicado." }
                                require(value.bodiesCount<=1) { "Resposta contém mais de uma pessoa." }
                                val row=frameJson(value)
                                header?.let { row.put("provider_header_protobuf_base64",it);header=null }
                                val text=row.toString()
                                require(text.length<=256_000)
                                bytes+=text.toByteArray(Charsets.UTF_8).size+1
                                require(bytes<=64_000_000) { "Resultado excede 64 MB." }
                                writer.write(text);writer.newLine()
                                bodies=bodies||value.bodiesCount>0
                                if(uploadDone.get())onPhase("Recebendo poses Nova-77: ${seen.size*100/video.frames}%")
                            }
                            if(value.streamFlushed)flushed=true
                        }
                    }catch(error:Exception) {
                        completed.completeExceptionally(IllegalStateException("Resposta NVIDIA inválida ou autorização revogada."))
                        request.get()?.cancel("Resposta rejeitada",null)
                    }
                }
                override fun onError(error:Throwable) { completed.completeExceptionally(NvidiaFailure.from(error)) }
                override fun onCompleted() {
                    if(flushed&&bodies&&seen.size==video.frames)completed.complete(Unit)
                    else completed.completeExceptionally(IllegalStateException("A NVIDIA encerrou sem resultado completo: faltam quadros esperados ou poses válidas. Nenhum parcial foi salvo."))
                }
            }
            val stream=BodyPoseServiceGrpc.newStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(240,TimeUnit.SECONDS).estimateBodyPose(observer)
            suspend fun send(message:BodyPoseRequest) {
                currentCoroutineContext().ensureActive();gate.withConsent { }
                while(request.get()?.isReady!=true) {
                    if(completed.isCompleted){completed.await();error("A NVIDIA encerrou o envio antes do fim do vídeo.")}
                    delay(10);currentCoroutineContext().ensureActive();gate.withConsent { }
                }
                if(completed.isCompleted){completed.await();error("Envio encerrado prematuramente.")}
                gate.withConsent { stream.onNext(message) }
            }
            onPhase("Enviando configuração e rastreamento à NVIDIA")
            send(BodyPoseRequest.newBuilder().setConfig(BodyPoseConfig.getDefaultInstance()).build())
            video.boxes.chunked(500).forEach { batch ->
                send(BodyPoseRequest.newBuilder().addAllTrackedBboxes(batch.map { box ->
                    FrameBoxes.newBuilder().setFrameId(box.frame).addBoxes(BoundingBox.newBuilder()
                        .setTrackingId(1).setX(box.x).setY(box.y).setWidth(box.width).setHeight(box.height)).build()
                }).build())
            }
            video.file.inputStream().use { source ->
                val buffer=ByteArray(256*1024);var sent=0L
                while(true) {
                    currentCoroutineContext().ensureActive();gate.withConsent { }
                    val count=source.read(buffer);if(count<0)break
                    send(BodyPoseRequest.newBuilder().setVideoData(ByteString.copyFrom(buffer,0,count)).build())
                    sent+=count;onPhase("Enviando diretamente à NVIDIA: ${sent*100/video.file.length()}%")
                }
            }
            stream.onCompleted();uploadDone.set(true)
            synchronized(lock){onPhase(if(seen.isEmpty())"NVIDIA gerando poses 3D" else "Recebendo poses Nova-77: ${seen.size*100/video.frames}%")}
            completed.await();currentCoroutineContext().ensureActive();gate.withConsent { }
            synchronized(lock){writer.flush();writer.close()} // close before rename: some hosts (Windows/JVM tests) cannot rename an open file
            onPhase("Validando poses Nova-77")
            // Validate the full result before making it available to preview/persistence.
            readClip(partial,30,1,video.frames,false,input.source,video.hash)
            check(partial.renameTo(output)) { "Não foi possível concluir o resultado local." }
            readClip(output,30,1,video.frames,false,input.source,video.hash).copy(sourceHash=input.originalHash)
        }catch(error:CancellationException){output.delete();throw error}
        catch(error:Exception){output.delete();throw error}
        finally {
            request.get()?.cancel("Sessão encerrada",null)
            channel.shutdownNow();active.compareAndSet(channel,null)
            synchronized(lock){writer.close()};partial.delete()
        }
    }

    companion object {
        const val FUNCTION_ID="254b3621-bb66-4b7b-9c89-621c352b4d91"
        fun statusMessage(code:Status.Code):String=when(code) {
            Status.Code.UNAUTHENTICATED,Status.Code.PERMISSION_DENIED -> "Chave NVIDIA inválida ou sem acesso ao modelo. Confira sua conta e substitua a chave em Minha chave NVIDIA."
            Status.Code.RESOURCE_EXHAUSTED -> "Limite ou cota NVIDIA atingido. Confira os limites gratuitos da sua conta."
            Status.Code.INVALID_ARGUMENT -> "A NVIDIA recusou o vídeo ou o rastreamento. Tente outro vídeo curto, com uma pessoa e corpo inteiro visível."
            Status.Code.DEADLINE_EXCEEDED -> "Tempo limite da NVIDIA. O processamento remoto pode ter começado; não houve nova tentativa automática."
            Status.Code.UNAVAILABLE -> "Serviço NVIDIA indisponível ou conexão com o serviço interrompida. Tente reenviar em instantes."
            else -> "Falha na conexão NVIDIA (${code.name}). Nenhum resultado completo foi salvo."
        }
        fun frameJson(response:BodyPoseResponse):JSONObject {
            fun p3(p:Point3f)=listOf(p.x,p.y,p.z)
            fun q(q:nvidia.ai4m.body_pose.v1.BodyPose.Quaternion)=listOf(q.x,q.y,q.z,q.w)
            val detections=JSONArray()
            response.bodiesList.forEach { body ->
                require(body.bbox.trackingId==1)
                detections.put(JSONObject().put("tracking_id",body.bbox.trackingId)
                    .put("bbox",JSONArray(listOf(body.bbox.x,body.bbox.y,body.bbox.width,body.bbox.height)))
                    .put("keypoints_2d",JSONArray(body.keypoints2DList.map { listOf(it.x,it.y) }))
                    .put("keypoints_3d",JSONArray(body.keypoints3DList.map(::p3)))
                    .put("keypoints_confidence",JSONArray(body.keypointConfidenceList))
                    .put("joint_rotations",JSONArray(body.jointRotationsList.map(::q)))
                    .put("rest_pose",JSONArray(body.restPoseList.map(::p3)))
                    .put("root_pose",JSONObject().put("translation",JSONArray(p3(body.rootPose.translation)))
                        .put("rotation",JSONArray(q(body.rootPose.rotation)))))
            }
            return JSONObject().put("frame_id",response.frameId).put("detections",detections)
                .put("provider_protobuf_base64",Base64.encodeToString(response.toByteArray(),Base64.NO_WRAP))
                .put("provider_focal_length",response.focalLength)
        }
    }
}
