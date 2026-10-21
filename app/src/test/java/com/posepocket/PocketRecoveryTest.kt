package com.posepocket

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.posepocket.media.*
import com.posepocket.nvidia.*
import com.posepocket.pose.*
import com.posepocket.privacy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PocketRecoveryTest {
    private lateinit var app:Application
    private val models=mutableListOf<PocketViewModel>()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app=ApplicationProvider.getApplicationContext()
        app.noBackupFilesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        app.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }
    @After fun cleanup() {
        models.forEach { ViewModelStore().apply { put("test",it);clear() } }
        Dispatchers.resetMain()
    }
    private fun awaitCondition(predicate:()->Boolean) {
        val deadline=System.nanoTime()+10_000_000_000L
        while(!predicate()) { check(System.nanoTime()<deadline){"Timed out waiting for ViewModel"};Thread.sleep(10) }
    }
    private fun model(factory:((ConsentGate,PendingProcessingStore)->ResumableProcessing)?=null):PocketViewModel {
        val model=PocketViewModel(app,factory);models+=model
        awaitCondition { model.initialized.value };return model
    }
    @Test fun cameraImportFailurePreservesRecordingAndRestoresReview() {
        val model=model { gate,store->ResumableProcessing(gate,store,
            {_,_->throw IllegalArgumentException("Synthetic import failure")},
            {_,_->error("No encoding")},{_,_,_->error("No upload")}) }
        model.gate.accept(true,true)
        val capture=CaptureStore(app){Uri.parse("content://${app.packageName}.captures/captures/${it.name}")}.create().also { it.file.writeText("synthetic recording") }
        model.review(capture.uri,"camera")
        model.confirmReview(TrimRange(100_000,900_000))
        awaitCondition { !model.state.value.busy&&model.state.value.review!=null }
        assertTrue(capture.file.exists())
        assertNull(model.state.value.recovery)
        assertEquals(capture.uri,model.state.value.review!!.uri)
        val restarted=model()
        assertEquals(capture.uri,restarted.state.value.review!!.uri)
        assertTrue(capture.file.exists())
    }
    @Test fun completedResultSurvivesRestartAndSavingDoesNotDeleteDisplayedRaw() {
        val model=model { gate,store->ResumableProcessing(gate,store,{_,_->
            val f=File(app.cacheDir,"synthetic.mp4").apply { writeText("synthetic") }
            PreparedVideo(f,fileHash(f),"attachment")
        },{_,_->
            val f=File(app.cacheDir,"encoded.mp4").apply { writeText("encoded") }
            DeviceVideo(f,fileHash(f),2,640,480,listOf(PersonBox(0,0f,0f,100f,200f)))
        },{_,_,_->syntheticDemo(File(app.cacheDir,"network.jsonl"))}) }
        model.gate.accept(true,true)
        model.review(Uri.parse("content://fixture/video"),"attachment")
        model.confirmReview(null)
        awaitCondition { model.state.value.clip!=null&&!model.state.value.busy }
        assertTrue(model.state.value.clip!!.raw.isFile)
        val restarted=model()
        assertEquals(120,restarted.state.value.clip!!.frameCount)
        assertNull(restarted.state.value.recovery)
        val previewIdentity=restarted.state.value.clip!!.raw.absolutePath
        restarted.save()
        awaitCondition { restarted.state.value.savedId!=null&&!restarted.state.value.busy }
        assertTrue(restarted.state.value.clip!!.raw.isFile)
        assertEquals(previewIdentity,restarted.state.value.previewIdentity)
        assertNull(CompletedResultStore(File(app.noBackupFilesDir,"unsaved-result")).load())
    }
    @Test fun restartCleansRevokedProcessingWithoutGrantingAccess() {
        val first=model()
        first.gate.accept(true,true)
        val file=File(app.cacheDir,"synthetic.mp4").apply { writeText("synthetic") }
        PendingProcessingStore(File(app.noBackupFilesDir,"processing"))
            .prepared(PreparedVideo(file,fileHash(file),"attachment"))
        first.gate.revoke()
        assertTrue(first.gate.isRevoked());assertNull(first.gate.receipt())
        val restarted=model()
        assertFalse(restarted.gate.isGranted())
        assertNull(restarted.state.value.recovery)
        assertFalse(File(app.noBackupFilesDir,"processing").exists())
    }
    @Test fun auxiliaryErrorDoesNotUnlockProcessing() {
        val release=CompletableDeferred<Unit>()
        val model=model { gate,store->ResumableProcessing(gate,store,{_,_->
            release.await();error("Synthetic stopped import")
        },{_,_->error("No encoding")},{_,_,_->error("No upload")}) }
        model.gate.accept(true,true)
        model.submit(Uri.parse("content://fixture/video"),"attachment")
        assertTrue(model.state.value.busy)
        model.report("Synthetic auxiliary error")
        assertTrue(model.state.value.busy)
        release.complete(Unit)
        awaitCondition { !model.state.value.busy }
    }
    @Test fun pauseAtInferenceCompletionKeepsTheFinishedResultInsteadOfResending() {
        lateinit var running:PocketViewModel
        running=model { gate,store->ResumableProcessing(gate,store,{_,_->
            val f=File(app.cacheDir,"input.mp4").apply { writeText("synthetic") }
            PreparedVideo(f,fileHash(f),"attachment")
        },{_,_->
            val f=File(app.cacheDir,"encoded.mp4").apply { writeText("encoded") }
            DeviceVideo(f,fileHash(f),2,640,480,listOf(PersonBox(0,0f,0f,100f,200f)))
        },{_,_,_->withContext(NonCancellable) {
            val result=syntheticDemo(File(app.cacheDir,"finished.jsonl"))
            running.cancel()
            result
        }}) }
        running.gate.accept(true,true)
        running.submit(Uri.parse("content://fixture/video"),"attachment")
        awaitCondition { running.state.value.clip!=null&&!running.state.value.busy }
        assertEquals(120,running.state.value.clip!!.frameCount)
        assertNull(running.state.value.recovery)
        assertNotNull(CompletedResultStore(File(app.noBackupFilesDir,"unsaved-result")).load())
    }
}
