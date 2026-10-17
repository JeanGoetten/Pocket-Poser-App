package com.posepocket.nvidia

import com.posepocket.media.PreparedVideo
import com.posepocket.pose.Nova77
import com.posepocket.privacy.*
import io.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import nvidia.ai4m.body_pose.v1.BodyPose.*
import nvidia.ai4m.body_pose.v1.BodyPoseServiceGrpc
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DirectNvidiaClientTest {
    private val folder=Files.createTempDirectory("direct-nvidia").toFile()
    private val output=File(folder,"results")
    private class Store:ConsentStore {
        var value:ConsentReceipt?=null
        override fun read()=value
        override fun write(receipt:ConsentReceipt){value=receipt}
        override fun clear(){value=null}
    }
    private val gate=ConsentGate(Policy("test","video","synthetic transport test"),Store()).apply{accept(true,true)}
    private var server:Server?=null
    private val key="test-only-not-a-real-nvidia-key-1234567890"
    private fun video():Pair<DeviceVideo,PreparedVideo> {
        val f=File(folder,"transport-only-not-real-video.mp4").apply{writeBytes(ByteArray(700000){(it%251).toByte()})}
        return DeviceVideo(f,"normalized-hash",2,640,480,listOf(PersonBox(0,1f,2f,100f,200f),PersonBox(1,2f,2f,100f,200f))) to PreparedVideo(f,"hash","attachment","original-hash")
    }
    private fun response(id:Int=0):BodyPoseResponse {
        val identity=Quaternion.newBuilder().setW(1f).build()
        val body=Body.newBuilder().setBbox(BoundingBox.newBuilder().setTrackingId(1))
        Nova77.tPose().forEach { p ->
            val point=Point3f.newBuilder().setX(p.x).setY(p.y).setZ(p.z).build()
            body.addKeypoints3D(point).addRestPose(point).addKeypoints2D(Point2f.getDefaultInstance()).addJointRotations(identity).addKeypointConfidence(1f)
        }
        body.setRootPose(Transform3f.newBuilder().setTranslation(Point3f.getDefaultInstance()).setRotation(identity))
        return BodyPoseResponse.newBuilder().setReady(true).setFrameId(id).addBodies(body).build()
    }
    private fun client(respond:(StreamObserver<BodyPoseResponse>,List<BodyPoseRequest>)->Unit,headers:(Metadata)->Unit={}):DirectNvidiaClient {
        val name=UUID.randomUUID().toString()
        val service=object:BodyPoseServiceGrpc.BodyPoseServiceImplBase() {
            override fun estimateBodyPose(out:StreamObserver<BodyPoseResponse>):StreamObserver<BodyPoseRequest> {
                val requests=mutableListOf<BodyPoseRequest>()
                return object:StreamObserver<BodyPoseRequest> {
                    override fun onNext(value:BodyPoseRequest){requests+=value}
                    override fun onError(error:Throwable){}
                    override fun onCompleted(){respond(out,requests)}
                }
            }
        }
        val interceptor=object:ServerInterceptor {
            override fun <ReqT:Any?,RespT:Any?> interceptCall(call:ServerCall<ReqT,RespT>,metadata:Metadata,next:ServerCallHandler<ReqT,RespT>):ServerCall.Listener<ReqT> {
                headers(metadata);return next.startCall(call,metadata)
            }
        }
        server=InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(service,interceptor)).build().start()
        return DirectNvidiaClient(gate,output){InProcessChannelBuilder.forName(name).directExecutor().build()}
    }
    @After fun cleanup(){server?.shutdownNow();folder.deleteRecursively()}

    @Test fun sendsPersonalAuthenticationAndAllBoxesBeforeChunkedVideoAndParsesResult()=runBlocking {
        val (video,input)=video()
        var received=0
        val client=client({ out,requests ->
            assertTrue(requests.first().hasConfig())
            val firstVideo=requests.indexOfFirst{!it.videoData.isEmpty}
            assertEquals(2,requests.take(firstVideo).sumOf{it.trackedBboxesCount})
            assertTrue(requests.drop(firstVideo).all{it.trackedBboxesCount==0&&!it.hasConfig()&&it.videoData.size()<=256*1024})
            received=requests.sumOf{it.videoData.size()}
            out.onNext(response(1));out.onNext(response(0))
            out.onNext(BodyPoseResponse.newBuilder().setStreamFlushed(true).build());out.onCompleted()
        },{ metadata ->
            assertEquals("Bearer $key",metadata.get(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER)))
            assertEquals(DirectNvidiaClient.FUNCTION_ID,metadata.get(Metadata.Key.of("function-id",Metadata.ASCII_STRING_MARSHALLER)))
        })
        val clip=client.infer(key,video,input){}
        assertEquals(700000,received);assertFalse(clip.demo);assertEquals(listOf(0,1),clip.frames.map{it.id})
        assertEquals("original-hash",clip.sourceHash);assertEquals(30.0,clip.fps,0.0)
        assertTrue(clip.raw.readText().contains("provider_protobuf_base64"))
        assertFalse(clip.raw.readText().contains(key))
    }

    @Test fun incompleteResponseDoesNotPublishPartial()=runBlocking {
        val (v,i)=video();val client=client({out,_->out.onNext(response());out.onCompleted()})
        try{client.infer(key,v,i){};fail("Expected incomplete error")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("completo"))}
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun flushedStreamMissingAFrameCannotBePublishedAsComplete()=runBlocking {
        val (v,i)=video()
        val client=client({out,_ ->
            out.onNext(response(0))
            out.onNext(BodyPoseResponse.newBuilder().setStreamFlushed(true).build())
            out.onCompleted()
        })
        try {
            client.infer(key,v,i){}
            fail("A flushed stream with one of two expected frames must fail.")
        } catch(e:IllegalStateException) {
            assertTrue(e.message!!.contains("quadros esperados"))
        }
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun duplicateResponseIsRejectedAndTemporaryResultDeleted()=runBlocking {
        val (v,i)=video();val client=client({out,_->out.onNext(response());out.onNext(response());out.onCompleted()})
        try{client.infer(key,v,i){};fail("Expected duplicate error")}catch(_:IllegalStateException){}
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun flushedStreamWithMissingFramesDoesNotPublishResult()=runBlocking {
        val (v,i)=video()
        val client=client({out,_->
            out.onNext(response(0))
            out.onNext(BodyPoseResponse.newBuilder().setStreamFlushed(true).build())
            out.onCompleted()
        })
        try{client.infer(key,v,i){};fail("Expected missing frame error")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("resultado completo"))}
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun credentialFailureDoesNotEchoServerDescriptionOrRetry()=runBlocking {
        val (v,i)=video();var calls=0
        val client=client({out,_->calls++;out.onError(Status.UNAUTHENTICATED.withDescription(key).asRuntimeException())})
        try{client.infer(key,v,i){};fail("Expected auth error")}catch(e:IllegalStateException){assertFalse(e.message!!.contains(key));assertTrue(e.message!!.contains("Chave NVIDIA"))}
        assertEquals(1,calls);assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun cancellationStopsWaitingAndRemovesTemporaryFiles()=runBlocking {
        val (v,i)=video();val client=client({_,_->})
        try{withTimeout(500){client.infer(key,v,i){}};fail("Expected cancellation")}catch(_:TimeoutCancellationException){}
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }
    @Test fun revokedConsentBlocksConnection()=runBlocking {
        val (v,i)=video();var contacted=false
        val client=DirectNvidiaClient(gate,output){contacted=true;error("Must not connect")}
        gate.revoke()
        try{client.infer(key,v,i){};fail("Expected revoked consent")}catch(_:IllegalStateException){}
        assertFalse(contacted)
    }
}
