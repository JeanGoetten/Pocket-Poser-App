package com.posepocket.gateway

import com.posepocket.media.PreparedVideo
import com.posepocket.pose.PoseClip
import com.posepocket.pose.fileHash
import com.posepocket.pose.readClip
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class GatewayClient(private val gate:ConsentGate,private val directory:File) {
    private val active=AtomicReference<HttpURLConnection?>()
    fun interrupt() { active.getAndSet(null)?.disconnect() }
    private fun InputStream.readLimited(limit:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n) }
        return out.toByteArray()
    }

    private fun connection(settings:GatewaySettings,path:String,method:String):HttpURLConnection {
        settings.validate()
        return (URL(settings.url.trimEnd('/')+path).openConnection() as HttpURLConnection).apply {
            requestMethod=method; connectTimeout=5000;readTimeout=15000;instanceFollowRedirects=false
            setRequestProperty("Authorization","Bearer "+settings.token)
        }
    }
    private fun checked(connection:HttpURLConnection):Int {
        val status=connection.responseCode
        if(status !in 200..299) {
            val message=try { connection.errorStream?.bufferedReader()?.use { reader ->
                val buffer=CharArray(4096); val n=reader.read(buffer)
                if(n>0)JSONObject(String(buffer,0,n)).getJSONObject("error").getString("message")else null
            } }catch(_:Exception){null}
            error(message?:"Gateway indisponível (HTTP $status). Nenhum resultado foi concluído.")
        }
        return status
    }
    private fun json(settings:GatewaySettings,path:String,method:String="GET",body:JSONObject?=null):JSONObject {
        val connection=connection(settings,path,method)
        active.set(connection)
        try {
            if(body!=null) {
                val bytes=body.toString().toByteArray()
                connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            checked(connection)
            if(connection.responseCode==204)return JSONObject()
            val bytes=connection.inputStream.use { it.readLimited(64000) }
            require(bytes.size<=64000)
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { active.compareAndSet(connection,null);connection.disconnect() }
    }

    suspend fun verify(settings:GatewaySettings)=withContext(Dispatchers.IO) {
        val policy=json(settings,"/v1/policy")
        require(policy.getString("document_hash")==gate.policy.hash&&policy.getString("policy_version")==gate.policy.version&&policy.getString("scope")==gate.policy.scope) {
            "A política do gateway difere da versão aceita no app. Atualize/revise a política antes de enviar."
        }
        require(json(settings,"/v1/health").getBoolean("nvidia_configured")) { "Gateway sem inferência NVIDIA habilitada. Use a demonstração offline." }
    }

    suspend fun infer(settings:GatewaySettings,video:PreparedVideo,requestId:String,onPhase:(String)->Unit):PoseClip = withContext(Dispatchers.IO) {
        check(settings.enabled) { "Configure e habilite o gateway em Configurações antes de enviar. A demo offline não usa o vídeo." }
        verify(settings)
        val receipt=gate.receipt()?:error("Consentimento revogado ou inválido.")
        val declaration=JSONObject().put("policy_version",receipt.policyVersion).put("document_hash",receipt.documentHash)
            .put("scope",receipt.scope).put("notice_ack",receipt.noticeAcknowledged).put("remote_consent",receipt.remoteProcessingConsented)
            .put("accepted_at",receipt.acceptedAt.toString()).put("revoked_at",JSONObject.NULL)
        gate.withConsent { json(settings,"/v1/consent","POST",declaration) }
        currentCoroutineContext().ensureActive()
        val boundary="pose-"+UUID.randomUUID()
        val metadata=JSONObject().put("schema_version",1).put("video_sha256",video.hash).put("device_request_id",requestId)
            .put("input_source",video.source).put("consent_confirmed",true)
        val prefix=("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$metadata\r\n"+
            "--$boundary\r\nContent-Disposition: form-data; name=\"video\"; filename=\"video.mp4\"\r\nContent-Type: video/mp4\r\n\r\n").toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        val upload=connection(settings,"/v1/jobs/prepare","POST")
        active.set(upload)
        val context=currentCoroutineContext()
        val jobId=try {
            upload.setRequestProperty("Idempotency-Key",requestId)
            upload.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
            upload.doOutput=true;upload.setFixedLengthStreamingMode(prefix.size+video.file.length()+suffix.size)
            upload.outputStream.use { out ->
                gate.withConsent { out.write(prefix) }
                var bytes=0L
                video.file.inputStream().use { input ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        context.ensureActive();gate.withConsent { }
                        val n=input.read(buffer);if(n<0)break
                        out.write(buffer,0,n);bytes+=n
                        onPhase("Enviando ao gateway: ${bytes*100/video.file.length()}%")
                    }
                }
                out.write(suffix)
            }
            checked(upload)
            val bytes=upload.inputStream.use { it.readLimited(64000) };require(bytes.size<=64000)
            JSONObject(String(bytes)).getString("job_id").also { UUID.fromString(it) }
        } finally { active.compareAndSet(upload,null);upload.disconnect() }
        val deadline=System.nanoTime()+240_000_000_000L
        var media:JSONObject?=null
        while(true) {
            context.ensureActive();gate.withConsent { }
            val state=json(settings,"/v1/jobs/$jobId")
            when(state.getString("status")) {
                "completed" -> { media=state.getJSONObject("media");break }
                "failed" -> error("O processamento falhou: ${state.optString("error","erro de mídia ou serviço")}. Tente outra entrada.")
                "canceled","cancel_requested" -> error("Processamento cancelado no servidor.")
            }
            onPhase(when(state.optString("phase")) {
                "preparing"->"Preparando vídeo no gateway"
                "tracking"->"Localizando e rastreando uma pessoa"
                "nvidia_inference"->"Gerando poses 3D na NVIDIA — aguardando resposta"
                else->"Aguardando processamento no gateway"
            })
            check(System.nanoTime()<deadline) { "Tempo limite do processamento. O app solicitará cancelamento ao gateway." }
            delay(500)
        }
        onPhase("Recebendo e validando Nova-77")
        directory.mkdirs()
        val raw=File(directory,"${UUID.randomUUID()}.jsonl")
        val download=connection(settings,"/v1/jobs/$jobId/result","GET")
        active.set(download)
        try {
            checked(download)
            download.inputStream.use { input -> raw.outputStream().use { out ->
                val buffer=ByteArray(65536);var total=0L
                while(true) {
                    context.ensureActive();gate.withConsent { }
                    val n=input.read(buffer);if(n<0)break
                    total+=n;require(total<=64_000_000) { "Resultado excede o limite local." }
                    out.write(buffer,0,n)
                }
            } }
            require(fileHash(raw)==download.getHeaderField("X-Content-SHA256")) { "Hash do resultado diverge; arquivo incompleto." }
            val finalMedia=requireNotNull(media)
            val clip=readClip(raw,finalMedia.getInt("fps_numerator"),finalMedia.getInt("fps_denominator"),finalMedia.getInt("frame_count"),false,video.source,finalMedia.getString("sha256"))
                .copy(sourceHash=video.originalHash?:video.hash)
            gate.withConsent { }
            return@withContext clip
        } catch(error:Throwable) { raw.delete();throw error }
        finally { active.compareAndSet(download,null);download.disconnect() }
    }

    suspend fun cancel(settings:GatewaySettings,requestId:String)=withContext(Dispatchers.IO) {
        UUID.fromString(requestId)
        json(settings,"/v1/requests/$requestId","DELETE")
    }
    suspend fun revoke(settings:GatewaySettings)=withContext(Dispatchers.IO) { json(settings,"/v1/consent","DELETE") }
}
