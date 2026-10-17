package com.posepocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.posepocket.ProcessingStage
import com.posepocket.ui.theme.PocketPoserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ScreensRoutingTest {
    @get:Rule val compose=createComposeRule()

    private fun show(content:@androidx.compose.runtime.Composable ()->Unit) {
        compose.setContent { PocketPoserTheme { Column(Modifier.fillMaxSize()) { content() } } }
    }

    @Test fun entryRoutesEveryCallbackFromItsOwnControl() {
        val calls=mutableListOf<String>()
        show { EntryScreen(true,true,false,2,{calls+="attach"},{calls+="record"},{calls+="key"},{calls+="demo"},{calls+="library"}) }
        for(tag in listOf("entry_attach","entry_record","entry_key","entry_demo","entry_library")) compose.onNodeWithTag(tag).performScrollTo().performClick()
        assertEquals(listOf("attach","record","key","demo","library"),calls)
    }

    @Test fun recordingIsDisabledWhenNoCameraAppExistsButAttachingStillWorks() {
        show { EntryScreen(true,false,true,0,{},{},{},{},{}) }
        compose.onNodeWithTag("entry_record").assertIsNotEnabled()
        compose.onNodeWithTag("entry_attach").assertIsEnabled()
    }

    @Test fun entryIsFullyDisabledUntilTheConsentGateIsReady() {
        show { EntryScreen(false,true,true,0,{},{},{},{},{}) }
        compose.onNodeWithTag("entry_attach").assertIsNotEnabled()
        compose.onNodeWithTag("entry_record").assertIsNotEnabled()
        compose.onNodeWithTag("entry_demo").assertIsNotEnabled()
    }

    @Test fun processingOffersCancelOnlyWhileItCanBeCancelled() {
        var cancelled=0
        val cancellable=androidx.compose.runtime.mutableStateOf(true)
        show { ProcessingScreen("detail",ProcessingStage.LOCATE,cancellable.value,{cancelled++}) }
        compose.onNodeWithTag("processing_steps").assertExists()
        compose.onNodeWithTag("processing_cancel").performClick()
        assertEquals(1,cancelled)
        compose.runOnIdle { cancellable.value=false }
        compose.onNodeWithTag("processing_cancel").assertDoesNotExist()
    }

    @Test fun libraryShowsAnEmptyStateAndGoesBack() {
        var back=0
        show { LibraryScreen(emptyList(),{back++},{},{},{}) }
        compose.onNodeWithTag("library_empty").assertExists()
        compose.onNodeWithTag("library_back").performClick()
        assertEquals(1,back)
    }

    @Test fun pipelineMessagesMapToStepsAndUnknownMessagesDoNotMoveThem() {
        assertEquals(ProcessingStage.LOCATE,ProcessingStage.fromMessage("Localizando uma pessoa no vídeo"))
        assertEquals(ProcessingStage.GENERATE,ProcessingStage.fromMessage("NVIDIA gerando poses"))
        assertEquals(ProcessingStage.SEND,ProcessingStage.fromMessage("Enviando 12%"))
        assertEquals(ProcessingStage.PREPARE,ProcessingStage.fromMessage("Preparando o vídeo"))
        assertNull(ProcessingStage.fromMessage("mensagem desconhecida"))
    }

    @Test fun everyIconPathParsesAtMaterialSize() {
        assertTrue(PocketIcons.all.isNotEmpty())
        PocketIcons.all.forEach { assertEquals(24.dp,it.defaultWidth);assertEquals(24.dp,it.defaultHeight) }
    }
}