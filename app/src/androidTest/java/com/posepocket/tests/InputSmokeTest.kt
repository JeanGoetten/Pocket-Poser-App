package com.posepocket.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posepocket.MainActivity
import com.posepocket.PocketViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** No camera acquisition and no network. Uses only the generated test-provider video. */
@RunWith(AndroidJUnit4::class)
class InputSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val model get()=ViewModelProvider(compose.activity)[PocketViewModel::class.java]
    private fun syntheticUri(path:String)=Uri.Builder().scheme("content").authority(InstrumentationRegistry.getInstrumentation().context.packageName + ".synthetic").appendPath(path).build()
    private var previousReceipt:ByteArray?=null
    private var backupTaken=false
    @Before fun start() {
        Intents.init()
        compose.waitUntil(10000){model.initialized.value}
        Assume.assumeTrue("Apague a chave NVIDIA antes de executar a suíte sintética.",!model.keyConfigured.value)
        Assume.assumeTrue("Não sobrescrever processamento pendente do usuário.",!File(compose.activity.noBackupFilesDir,"processing/pending.json").exists())
        previousReceipt=File(compose.activity.noBackupFilesDir,"consent.json").takeIf{it.exists()}?.readBytes()
        backupTaken=true
        compose.waitForIdle()
        if(compose.onAllNodesWithText("Aceitar e continuar").fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().indices.forEach { i->
                compose.onAllNodes(isToggleable())[i].performScrollTo().performClick()
            }
            compose.onNodeWithText("Aceitar e continuar").performScrollTo().performClick()
        }
        compose.waitForIdle()
    }
    @After fun stop(){
        if(backupTaken) {
            model.discardProcessing()
            compose.waitUntil(10000){!model.state.value.busy&&model.state.value.recovery==null}
            val file=AtomicFile(File(compose.activity.noBackupFilesDir,"consent.json"))
            val bytes=previousReceipt
            if(bytes==null)file.delete()else{
                val stream=file.startWrite()
                try{stream.write(bytes);stream.fd.sync();file.finishWrite(stream)}catch(error:Exception){file.failWrite(stream);throw error}
            }
        }
        Intents.release()
    }

    @Test fun cancelingPickerDoesNotCreateJobOrOpenCamera() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null))
        compose.onNodeWithText("Anexar vídeo").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.clip)
        compose.onNodeWithText("Gravar vídeo").assertExists()
    }

    @Test fun cancelingNativeCameraKeepsEntryAndCreatesNoJob() {
        intending(hasAction(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null))
        compose.onNodeWithTag("entry_record").performClick()
        compose.waitForIdle()
        assertFalse(model.state.value.busy);assertNull(model.state.value.review)
        compose.onNodeWithTag("entry_attach").assertIsDisplayed()
    }

    @Test fun studioGesturesChangeOnlyTheViewAndKeepSaveVisible() {
        compose.runOnIdle{model.demo()}
        compose.waitUntil(10000){model.state.value.clip!=null}
        val clip=requireNotNull(model.state.value.clip)
        val rawHash=com.posepocket.pose.fileHash(clip.raw)
        val stage=compose.onNodeWithTag("preview_stage")
        fun imageHash()=stage.captureToImage().toPixelMap().buffer.contentHashCode()
        val initial=imageHash()
        stage.performTouchInput{swipe(center,center+Offset(180f,30f),600)}
        val orbited=imageHash();assertNotEquals(initial,orbited)
        stage.performTouchInput{pinch(center-Offset(70f,0f),center+Offset(70f,0f),center-Offset(140f,0f),center+Offset(140f,0f),600)}
        val zoomed=imageHash();assertNotEquals(orbited,zoomed)
        stage.performTouchInput{
            down(0,center-Offset(70f,0f));down(1,center+Offset(70f,0f))
            moveBy(0,Offset(80f,40f));moveBy(1,Offset(80f,40f));up(0);up(1)
        }
        assertNotEquals(zoomed,imageHash())
        stage.performTouchInput{doubleClick(center)}
        assertEquals(initial,imageHash())
        fun snapshot(name:String){
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        snapshot("ux-studio.png")
        compose.onNodeWithTag("preview_save").assertIsDisplayed()
        compose.onNodeWithTag("view_multi").performClick()
        snapshot("ux-multiview.png")
        compose.onNodeWithTag("view_skeleton").performClick()
        snapshot("ux-skeleton.png")
        compose.onNodeWithTag("preview_save").assertIsDisplayed()
        assertEquals(rawHash,com.posepocket.pose.fileHash(clip.raw))
    }

    @Test fun landscapePreservesCameraAndKeepsSaveReachable() {
        val previousOrientation=compose.activity.requestedOrientation
        try {
            compose.runOnIdle{model.demo()}
            compose.waitUntil(10000){model.state.value.clip!=null}
            val raw=requireNotNull(model.state.value.clip).raw
            val hash=com.posepocket.pose.fileHash(raw)
            val stage=compose.onNodeWithContentDescription("Vista 3D.",substring=true)
            val zoom=stage.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                .single{it.label=="Zoom in"}
            compose.runOnIdle{assertTrue(zoom.action())}
            compose.runOnIdle{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(15000){compose.onAllNodesWithTag("preview_side_dock").fetchSemanticsNodes().isNotEmpty()}
            stage.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Zoom 103%"))
            compose.onNodeWithTag("preview_save").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("preview_export").assertIsDisplayed()
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir,"ux-landscape.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000){compose.onAllNodesWithTag("preview_side_dock").fetchSemanticsNodes().isNotEmpty()}
            stage.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Zoom 103%"))
            assertEquals(hash,com.posepocket.pose.fileHash(raw))
        } finally {
            compose.runOnIdle{compose.activity.requestedOrientation=previousOrientation}
        }
    }

    @Test fun sharingOpensAndroidChooserWithReadOnlyOriginalData() {
        compose.runOnIdle{model.demo()}
        compose.waitUntil(10000){model.state.value.clip!=null}
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null))
        compose.onNodeWithTag("preview_export").performClick()
        compose.waitUntil(10000){Intents.getIntents().any{it.action==Intent.ACTION_CHOOSER}}
        val chooser=Intents.getIntents().first{it.action==Intent.ACTION_CHOOSER}
        @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND,send.action)
        assertEquals("application/x-ndjson",send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        assertEquals(0,send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION") val uri=send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val bytes=compose.activity.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
        assertArrayEquals(requireNotNull(model.state.value.clip).raw.readBytes(),bytes)
    }

    @Test fun redoFromSavedProjectReturnsToBothInputsAndPreservesTheProject() {
        compose.runOnIdle{model.demo()}
        compose.waitUntil(10000){model.state.value.clip!=null}
        compose.onNodeWithTag("preview_save").performClick()
        compose.waitUntil(10000){model.state.value.savedId!=null&&!model.state.value.busy}
        val id=requireNotNull(model.state.value.savedId)
        compose.onNodeWithTag("preview_back").performClick()
        compose.onNodeWithTag("entry_library").performClick()
        compose.onNodeWithTag("project_$id").performClick()
        compose.waitUntil(10000){model.state.value.savedId==id&&model.state.value.clip!=null&&!model.state.value.busy}
        compose.onNodeWithTag("preview_more").performClick()
        compose.onNodeWithText("Refazer").performClick()
        compose.onNodeWithTag("entry_attach").assertIsDisplayed()
        compose.onNodeWithTag("entry_record").assertIsDisplayed()
        assertTrue(model.state.value.projects.any{it.id==id})
    }

    @Test fun reviewCanPlayAndRestoreTheSelectedRangeAfterRecreation() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(syntheticUri("video"))))
        compose.onNodeWithTag("entry_attach").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("review_play").fetchSemanticsNodes().isNotEmpty()}
        assertFalse(model.state.value.busy)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(100f)}
        compose.waitUntil(5000){model.state.value.review?.startMs==100L}
        compose.onNodeWithTag("review_play").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithTag("review_play").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(100L,model.state.value.review?.startMs)
        assertFalse(model.state.value.busy)
        val range=compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))[0]
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(100f,range.current,.01f)
        compose.onNodeWithTag("review_back").performClick()
        compose.onNodeWithTag("entry_attach").assertIsDisplayed()
    }

    @Test fun firstReviewPlaysOnlyTheSelectionAndCanReplayWithoutRecordingAgain() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(syntheticUri("video"))))
        compose.onNodeWithTag("entry_attach").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("review_play").fetchSemanticsNodes().isNotEmpty()}
        val sliders=compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        sliders[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(200f)}
        sliders[1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(900f)}
        compose.waitUntil(10000){compose.onAllNodesWithTag("review_play").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        repeat(2) {
            compose.onNodeWithTag("review_play").performClick()
            compose.waitUntil(5000){compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(5000){compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("review_play").assertIsEnabled()
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun contentUriStartsPreparationAutomaticallyAndCleansTemporaryVideo() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setData(syntheticUri("video"))))
        compose.onNodeWithText("Anexar vídeo").performScrollTo().performClick()
        compose.waitUntil(15000){model.state.value.review!=null}
        compose.waitUntil(15000){compose.onAllNodesWithTag("review_next").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("review_next").assertExists()
        assertFalse("Review must not upload or prepare automatically",model.state.value.busy)
        compose.onNodeWithTag("review_next").performClick()
        compose.waitUntil(15000){model.state.value.error!=null&&!model.state.value.busy}
        assertTrue("A falha da etapa deve ser informada.",model.state.value.error?.isNotBlank()==true)
        assertNotNull("Etapas já concluídas devem permanecer recuperáveis.",model.state.value.recovery)
        assertNull(model.state.value.clip)
        compose.waitUntil(5000){File(compose.activity.cacheDir,"media").listFiles().orEmpty().isEmpty()}
        compose.onNodeWithTag("recovery_discard").performClick()
        compose.onNodeWithText("Descartar e voltar às entradas").performClick()
        compose.waitUntil(10000){model.state.value.recovery==null&&!model.state.value.busy}
        compose.onNodeWithTag("entry_attach").assertIsDisplayed()
    }

    @Test fun revokedUriIsRecoverableAndDoesNotCreateResult() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setData(syntheticUri("revoked"))))
        compose.onNodeWithText("Anexar vídeo").performScrollTo().performClick()
        compose.waitUntil(15000){model.state.value.review!=null}
        compose.waitUntil(15000){compose.onAllNodesWithText("Não foi possível ler este vídeo.",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Não foi possível ler este vídeo.",substring=true).assertExists()
        compose.onNodeWithTag("review_choose").performClick()
        assertNull(model.state.value.clip)
        compose.onNodeWithText("Anexar vídeo").assertIsEnabled()
    }
}
