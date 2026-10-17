package com.posepocket.nvidia

import android.net.Uri
import com.posepocket.media.PreparedVideo
import com.posepocket.pose.*
import com.posepocket.privacy.*
import io.grpc.Status
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ResumableProcessingTest {
    private val root=Files.createTempDirectory("recovery-test").toFile()
    private val directory=File(root,"pending")
    private val store=PendingProcessingStore(directory)
    private val gate=ConsentGate(Policy("test","video","Synthetic recovery; fake network"),object:ConsentStore {
        var value:ConsentReceipt?=null
        override fun read()=value
        override fun write(receipt:ConsentReceipt){value=receipt}
        override fun clear(){value=null}
    }).apply{accept(true,true)}
    private var preparations=0
    private var normalizations=0
    private var sends=0
    private fun input():PreparedVideo {
        preparations++
        val file=File(root,"synthetic-input.mp4").apply{writeBytes(ByteArray(100){it.toByte()})}
        return PreparedVideo(file,fileHash(file),"attachment","a".repeat(64))
    }
    private fun video():DeviceVideo {
        normalizations++
        val file=File(root,"synthetic-normalized.mp4").apply{writeBytes(ByteArray(120){it.toByte()})}
        return DeviceVideo(file,fileHash(file),2,640,480,listOf(PersonBox(0,1f,2f,100f,200f)))
    }
    @After fun cleanup(){root.deleteRecursively()}

    @Test fun unavailableThenRestartResendsIdenticalPreparedBytesWithoutPreparingAgain()=runBlocking {
        val first=ResumableProcessing(gate,store,{_,_->input()},{_,_->video()},{_,_,_->
            sends++;throw NvidiaFailure(Status.Code.UNAVAILABLE,TransportIssue.SERVICE_OR_CONNECTION)
        })
        try { first.run(Uri.parse("content://fixture/video"),"attachment",{},{}) ;fail("Expected unavailable") }
        catch(_:NvidiaFailure){}
        val checkpoint=requireNotNull(PendingProcessingStore(directory).load())
        assertEquals(RecoveryStage.NVIDIA,checkpoint.stage)
        val hash=checkpoint.video!!.hash
        val retry=ResumableProcessing(gate,PendingProcessingStore(directory),
            {_,_->error("Must not attach again")},{_,_->error("Must not normalize/track again")},
            {video,input,_->sends++;assertEquals(hash,fileHash(video.file));assertEquals("a".repeat(64),input.originalHash);syntheticDemo(File(root,"result.jsonl"))})
        retry.run(null,null,{},{})
        assertEquals(1,preparations);assertEquals(1,normalizations);assertEquals(2,sends)
        assertTrue(directory.listFiles().orEmpty().none{it.name.endsWith(".partial")})
        store.clear();assertNull(store.load())
    }
    @Test fun normalizationFailureKeepsInputAndRetriesOnlyUnfinishedStages()=runBlocking {
        val first=ResumableProcessing(gate,store,{_,_->input()},{_,_->throw IllegalArgumentException("Synthetic codec failure")},{_,_,_->error("No upload")})
        try {first.run(Uri.parse("content://fixture/video"),"attachment",{},{}) ;fail("Expected failure")}
        catch(_:IllegalArgumentException){}
        assertEquals(RecoveryStage.NORMALIZE,store.load()!!.stage)
        ResumableProcessing(gate,store,{_,_->error("No attachment")},{_,_->video()},
            {_,_,_->syntheticDemo(File(root,"result.jsonl"))}).run(null,null,{},{})
        assertEquals(1,preparations);assertEquals(1,normalizations)
    }
    @Test fun cancelledInferenceKeepsCheckpointButRevocationStillBlocksRetry()=runBlocking {
        val runner=ResumableProcessing(gate,store,{_,_->input()},{_,_->video()},
            {_,_,_->throw CancellationException("Synthetic pause")})
        try {runner.run(Uri.parse("content://fixture/video"),"attachment",{},{}) ;fail("Expected cancellation")}
        catch(_:CancellationException){}
        assertEquals(RecoveryStage.NVIDIA,store.load()!!.stage)
        gate.revoke()
        try {runner.run(null,null,{},{}) ;fail("Expected consent gate")}
        catch(_:IllegalStateException){}
    }
    @Test fun corruptedVideoNeverReachesUploadAndDiscardRemovesBothFiles()=runBlocking {
        val first=ResumableProcessing(gate,store,{_,_->input()},{_,_->video()},
            {_,_,_->throw NvidiaFailure(Status.Code.UNAVAILABLE,TransportIssue.SERVICE_OR_CONNECTION)})
        try{first.run(Uri.parse("content://fixture/video"),"attachment",{},{})}catch(_:NvidiaFailure){}
        File(directory,"normalized.mp4").appendText("corrupt")
        try{store.load();fail("Expected integrity rejection")}catch(_:IllegalArgumentException){}
        store.clear();assertFalse(directory.exists())
    }
    @Test fun diagnosticsKeepSafeClassificationWithoutServerDescriptionOrCause() {
        val secret="sensitive-server-description"
        val failure=NvidiaFailure.from(Status.UNAVAILABLE.withDescription(secret).withCause(UnknownHostException(secret)).asRuntimeException())
        assertEquals(Status.Code.UNAVAILABLE,failure.code)
        assertEquals(TransportIssue.DNS,failure.issue)
        assertTrue(failure.message!!.contains("DNS"));assertFalse(failure.toString().contains(secret));assertNull(failure.cause)
        assertTrue(NvidiaFailure(Status.Code.UNAVAILABLE,TransportIssue.SERVICE_OR_CONNECTION).message!!.contains("internet funcionando"))
    }
}
