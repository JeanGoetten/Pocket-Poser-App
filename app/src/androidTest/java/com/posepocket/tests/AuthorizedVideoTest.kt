package com.posepocket.tests

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posepocket.media.MediaPreparer
import com.posepocket.nvidia.DeviceVideoPipeline
import com.posepocket.nvidia.DirectNvidiaClient
import com.posepocket.nvidia.PendingProcessingStore
import com.posepocket.nvidia.ResumableProcessing
import com.posepocket.nvidia.NvidiaFailure
import com.posepocket.nvidia.TransportIssue
import com.posepocket.nvidia.RecoveryStage
import com.posepocket.gateway.NvidiaKeyStore
import com.posepocket.pose.fileHash
import com.posepocket.R
import com.posepocket.privacy.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in check with footage supplied outside the repository. Network
 * inference additionally requires inferNvidia=true and the real stored consent. */
@RunWith(AndroidJUnit4::class)
class AuthorizedVideoTest {
    @Test fun stageAuthorizedVideoForRecoveryUi()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stageRecovery")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"authorized-beach-test.mp4")
        require(source.isFile)
        val gate=ConsentGate(Policy("dev-2026-10-15-v5-i18n-video-limits","nvidia-body-pose-video-v1",
            context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use{it.readText()}),AtomicConsentStore(context.noBackupFilesDir))
        check(gate.isGranted())
        val store=PendingProcessingStore(File(context.noBackupFilesDir,"processing"))
        check(store.load()==null){"Preserve the existing pending job"}
        val runner=ResumableProcessing(gate,store,
            {uri,_->MediaPreparer(context,gate).prepare(uri,"camera")},
            {input,phase->DeviceVideoPipeline(gate).prepare(input,phase)},
            {_,_,_->throw NvidiaFailure(io.grpc.Status.Code.UNAVAILABLE,TransportIssue.SERVICE_OR_CONNECTION)})
        try{runner.run(Uri.fromFile(source),"camera",{},{}) ;fail("Expected simulated service failure")}
        catch(_:NvidiaFailure){}
        val restored=requireNotNull(PendingProcessingStore(File(context.noBackupFilesDir,"processing")).load())
        assertEquals(RecoveryStage.NVIDIA,restored.stage)
        File(context.cacheDir,"recovery-staged.json").writeText(JSONObject()
            .put("input_sha256",restored.input.hash).put("normalized_sha256",restored.video!!.hash)
            .put("frames",restored.video!!.frames).put("boxes",restored.video!!.boxes.size)
            .put("failure","simulated UNAVAILABLE, no upload; pending job intentionally left for explicit UI retry").toString())
    }

    @Test fun prepareAuthorizedLocalVideo()=runBlocking {
        val path=InstrumentationRegistry.getArguments().getString("authorizedVideoPath")
        assumeTrue("No authorized local video supplied",path!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val inferNvidia=InstrumentationRegistry.getArguments().getString("inferNvidia")=="true"
        val source=File(requireNotNull(path))
        require(source.canonicalFile.parentFile==context.cacheDir.canonicalFile)
        val store=object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }
        val gate=if(inferNvidia) ConsentGate(Policy("dev-2026-10-15-v5-i18n-video-limits","nvidia-body-pose-video-v1",
            context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use{it.readText()}),AtomicConsentStore(context.noBackupFilesDir))
        else ConsentGate(Policy("test","video","Explicitly authorized local test; no upload"),store).apply{accept(true,true)}
        check(gate.isGranted()){"The app's current consent must already be granted"}
        val started=SystemClock.elapsedRealtime()
        // A file URI exercises the same private-file preparation used by capture.
        val prepared=MediaPreparer(context,gate).prepare(Uri.fromFile(source),"camera")
        var trackingStarted=0L
        var batched=false
        try {
            val video=DeviceVideoPipeline(gate).prepare(prepared) { phase ->
                if(phase.contains("leitura em lotes"))batched=true
                if(phase=="Localizando uma pessoa no celular")trackingStarted=SystemClock.elapsedRealtime()
            }
            try {
                assertTrue(batched)
                assertTrue(video.boxes.isNotEmpty())
                assertTrue(trackingStarted>started)
                val localFinished=SystemClock.elapsedRealtime()
                val summary=JSONObject()
                    .put("frames",video.frames).put("frames_with_boxes",video.boxes.size)
                    .put("detector_frames",video.detectorFrames).put("template_tracked_frames",video.boxes.size-video.detectorFrames)
                    .put("width",video.width).put("height",video.height)
                    .put("prepare_normalize_ms",trackingStarted-started)
                    .put("tracking_ms",localFinished-trackingStarted)
                    .put("total_ms",localFinished-started)
                    .put("normalized_bytes",video.file.length()).put("indexed_normalization",batched)
                    .put("scope","Authorized local video, Android preparation; NVIDIA only with explicit argument")
                if(inferNvidia) {
                    val key=requireNotNull(NvidiaKeyStore(context.noBackupFilesDir).read()){"Configure the test key in the app first"}
                    val resultDirectory=File(context.cacheDir,"authorized-inference-test").apply{mkdirs()}
                    val client=DirectNvidiaClient(gate,resultDirectory)
                    try {
                        val clip=client.infer(key,video,prepared){}
                        try {
                            assertEquals(video.frames,clip.frames.size)
                            val withPose=clip.frames.count{it.joints!=null}
                            assertTrue(withPose>0)
                            summary.put("nvidia_frames",clip.frames.size).put("nvidia_frames_with_pose",withPose)
                                .put("nvidia_ms",SystemClock.elapsedRealtime()-localFinished)
                                .put("raw_sha256",fileHash(clip.raw))
                        }finally{clip.raw.delete()}
                    }finally{client.interrupt();resultDirectory.delete()}
                }
                File(context.cacheDir,"authorized-video-timing.json").writeText(summary.toString())
            }finally{video.file.delete()}
        }finally{prepared.file.delete()}
    }
}
