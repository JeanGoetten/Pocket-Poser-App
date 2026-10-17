package com.posepocket.tests

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posepocket.PocketViewModel
import com.posepocket.media.PreparedVideo
import com.posepocket.nvidia.*
import com.posepocket.pose.fileHash
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated private folders; never touches the user's key, receipt or projects. */
@RunWith(AndroidJUnit4::class)
class RecoveryStateTest {
    private class IsolatedApplication(base:Context,private val root:File):Application() {
        init{attachBaseContext(base)}
        override fun getNoBackupFilesDir()=File(root,"private").apply{mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
    }
    @Test fun reviewDraftSurvivesANewViewModelWithoutStartingWork()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val root=File(instrumentation.targetContext.cacheDir,"review-state-${UUID.randomUUID()}").apply{mkdirs()}
        val app=IsolatedApplication(instrumentation.targetContext,root)
        val first=ViewModelStore();val second=ViewModelStore()
        try {
            lateinit var model:PocketViewModel
            instrumentation.runOnMainSync{model=PocketViewModel(app);first.put("model",model)}
            withTimeout(10000){while(!model.initialized.value)delay(20)}
            val uri=android.net.Uri.parse("content://local-test/video")
            instrumentation.runOnMainSync{model.gate.accept(true,true);model.review(uri,"attachment");model.updateReviewRange(100,400);first.clear()}
            lateinit var restored:PocketViewModel
            instrumentation.runOnMainSync{restored=PocketViewModel(app);second.put("model",restored)}
            withTimeout(10000){while(!restored.initialized.value)delay(20)}
            assertEquals(uri,restored.state.value.review?.uri)
            assertEquals(100L,restored.state.value.review?.startMs)
            assertEquals(400L,restored.state.value.review?.endMs)
            assertFalse(restored.state.value.busy);assertNull(restored.state.value.clip)
            assertFalse(File(app.noBackupFilesDir,"processing/pending.json").exists())
            instrumentation.runOnMainSync{restored.cancelReview()}
            assertNull(com.posepocket.media.ReviewDraftStore(app.noBackupFilesDir).load())
        }finally{instrumentation.runOnMainSync{first.clear();second.clear()};root.deleteRecursively()}
    }
    @Test fun restoresWithoutUploadPausesAndRevokesOnlyPendingWork()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.cacheDir,"recovery-state-${UUID.randomUUID()}").apply{mkdirs()}
        val application=IsolatedApplication(instrumentation.targetContext,folder)
        val holder=ViewModelStore()
        try {
            val input=File(folder,"input-fixture.mp4").apply{writeBytes(byteArrayOf(1,2,3))}
            val video=File(folder,"video-fixture.mp4").apply{writeBytes(byteArrayOf(4,5,6))}
            val store=PendingProcessingStore(File(application.noBackupFilesDir,"processing"))
            store.prepared(PreparedVideo(input,fileHash(input),"attachment"))
            store.normalized(DeviceVideo(video,fileHash(video),2,640,480,listOf(PersonBox(0,1f,2f,100f,200f))))
            val sentinel=File(application.noBackupFilesDir,"projects/preserved.txt").apply{parentFile!!.mkdirs();writeText("keep")}
            lateinit var model:PocketViewModel
            instrumentation.runOnMainSync { model=PocketViewModel(application);holder.put("model",model) }
            withTimeout(10000){while(!model.initialized.value)delay(20)}
            assertEquals(RecoveryStage.NVIDIA,model.state.value.recovery)
            assertFalse(model.state.value.busy)
            assertFalse(File(application.cacheDir,"results").exists())
            instrumentation.runOnMainSync {model.gate.accept(true,true);model.cancel()}
            withTimeout(10000){while(model.state.value.busy)delay(20)}
            assertEquals(RecoveryStage.NVIDIA,store.load()!!.stage)
            instrumentation.runOnMainSync {model.revokeRemote();model.gate.revoke()}
            withTimeout(10000){while(model.state.value.busy)delay(20)}
            assertNull(store.load());assertNull(model.state.value.recovery)
            assertFalse(model.gate.isGranted());assertEquals("keep",sentinel.readText())
        }finally{
            instrumentation.runOnMainSync{holder.clear()}
            folder.deleteRecursively()
        }
    }
}
