package com.posepocket.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.posepocket.ProcessingStage
import com.posepocket.ProcessingProgress
import com.posepocket.ProcessingSubstep
import com.posepocket.pose.SavedProject
import com.posepocket.ui.theme.PocketPoserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "pt-rBR")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun stateOf(text: String): String? =
        compose.onNodeWithText(text, useUnmergedTree = false).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun attachIsPrimaryAndRecordIsSecondary() {
        var attach = 0; var record = 0
        compose.setContent { PocketPoserTheme { EntryScreen(true, true, true, 0, { attach++ }, { record++ }, {}, {}, {}) } }
        compose.onNodeWithTag("entry_attach").performClick()
        compose.onNodeWithTag("entry_record").performClick()
        assertEquals(1, attach); assertEquals(1, record)
        compose.onNodeWithText("Chave NVIDIA salva").assertExists()
    }
    @Test @Config(sdk=[35],qualifiers="pt-rBR-w820dp-h400dp-land")
    fun landscapeEntryKeepsSourcesAndLibraryVisibleInShortSpace() {
        compose.setContent{PocketPoserTheme{Box(Modifier.height(280.dp)) {
            EntryScreen(true,true,true,3,{},{},{},{},{})
        }}}
        compose.onNodeWithTag("entry_attach").assertIsDisplayed()
        compose.onNodeWithTag("entry_record").assertIsDisplayed()
        compose.onNodeWithTag("entry_library").assertIsDisplayed()
        compose.onNodeWithTag("entry_demo").assertIsDisplayed()
        assertEquals(compose.onNodeWithTag("entry_attach").fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithTag("entry_record").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun entryOffersOnlyNvidiaAndKeepsKeyAccess() {
        compose.setContent { PocketPoserTheme {
            EntryScreen(true,true,true,0,{},{},{},{},{})
        } }
        compose.onNodeWithTag("entry_key").assertExists()
        compose.onNodeWithTag("entry_engine_picker").assertDoesNotExist()
        compose.onNodeWithTag("engine_mediapipe").assertDoesNotExist()
        compose.onNodeWithTag("engine_ml_kit").assertDoesNotExist()
    }

    @Test fun recordIsDisabledWithoutCameraAppButAttachStillWorks() {
        compose.setContent { PocketPoserTheme { EntryScreen(true, false, false, 2, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("entry_record").assertIsNotEnabled()
        compose.onNodeWithTag("entry_attach").assertIsEnabled()
        compose.onNodeWithText("Nenhum app de câmera foi encontrado neste aparelho. Use Anexar vídeo.").assertExists()
        compose.onNodeWithText("Adicione sua chave NVIDIA").assertExists()
        compose.onNodeWithText("Projetos salvos (2)").assertExists()
    }

    @Test fun nothingIsClickableUntilConsentIsLoaded() {
        compose.setContent { PocketPoserTheme { EntryScreen(false, true, true, 0, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("entry_attach").assertIsNotEnabled()
        compose.onNodeWithTag("entry_record").assertIsNotEnabled()
        compose.onNodeWithTag("entry_demo").assertIsNotEnabled()
    }

    @Test fun processingShowsRealStepsWithTextStatesAndNoPercentage() {
        compose.setContent { PocketPoserTheme { ProcessingScreen("Localizando uma pessoa no vídeo", ProcessingStage.LOCATE, true, {}) } }
        assertEquals("Concluída", stateOf("Preparando vídeo"))
        assertEquals("Em andamento", stateOf("Analisando movimento"))
        compose.onNodeWithText("Enviando para a NVIDIA").assertDoesNotExist()
        assertEquals("Pendente", stateOf("Gerando animação"))
        assertTrue(compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun unknownStageKeepsAllStepsPendingAndShowsTheMessage() {
        compose.setContent { PocketPoserTheme { ProcessingScreen("Mensagem nova", null, true, {}) } }
        compose.onNodeWithText("Mensagem nova").assertExists()
        compose.onNodeWithTag("processing_steps").assertDoesNotExist()
    }

    @Test fun cancelIsOfferedOnlyWhenAllowed() {
        var cancelled = 0
        compose.setContent { PocketPoserTheme { ProcessingScreen("x", ProcessingStage.SEND, true, { cancelled++ }) } }
        compose.onNodeWithTag("processing_cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun cancelIsHiddenWhileSaving() {
        compose.setContent { PocketPoserTheme { ProcessingScreen("Salvando projeto", null, false, {}) } }
        compose.onNodeWithTag("processing_cancel").assertDoesNotExist()
    }

    @Test fun substepsShowMeasuredPercentButNeverAFakeRemoteEstimate() {
        val progress=mutableStateOf(ProcessingProgress(ProcessingSubstep.NORMALIZE,42))
        compose.setContent{PocketPoserTheme{ProcessingScreen("",progress.value.step.stage,true,{},progress=progress.value)}}
        compose.onNodeWithText("Normalizar quadros").assertExists()
        compose.onNodeWithText("42%").assertExists()
        compose.onNodeWithTag("processing_percent",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("processing_percent_bar").assertDoesNotExist()
        compose.runOnIdle{progress.value=ProcessingProgress(ProcessingSubstep.WAIT)}
        compose.onNodeWithText("Aguardando a NVIDIA").assertExists()
        compose.onNodeWithTag("processing_percent",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("processing_percent_bar").assertDoesNotExist()
    }

    @Test fun changingStagesDoesNotMoveSubstepRows() {
        val progress=mutableStateOf(ProcessingProgress(ProcessingSubstep.NORMALIZE,20))
        compose.setContent { PocketPoserTheme { ProcessingScreen("",progress.value.step.stage,true,{},progress=progress.value) } }
        val before=compose.onNodeWithText("Normalizar quadros").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { progress.value=ProcessingProgress(ProcessingSubstep.WAIT) }
        val after=compose.onNodeWithText("Normalizar quadros").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before,after)
        compose.onNodeWithTag("processing_percent_bar").assertDoesNotExist()
    }

    @Test fun emptyLibraryExplainsItself() {
        var back = 0
        compose.setContent { PocketPoserTheme { LibraryScreen(emptyList(), { back++ }, {}, {}, {}) } }
        compose.onNodeWithTag("library_empty").assertExists()
        compose.onNodeWithTag("library_delete_all").assertDoesNotExist()
        compose.onNodeWithTag("library_new").performClick()
        assertEquals(1, back)
        compose.onNodeWithTag("library_back").performClick()
        assertEquals(2, back)
    }

    @Test fun libraryShowsProjectsWithOpenAndDelete() {
        val opened = mutableListOf<String>(); val deleted = mutableListOf<String>()
        val projects = listOf(
            SavedProject("a", "2026-10-15T14:32:00Z", false),
            SavedProject("b", "2026-10-14T09:10:00Z", true),
            SavedProject("c", "2026-10-13T08:00:00Z", false),
        )
        compose.setContent { PocketPoserTheme { LibraryScreen(projects, {}, { opened += it }, { deleted += it }, {}) } }
        compose.onNodeWithTag("library_grid").performScrollToKey("b")
        compose.onNodeWithTag("project_b").performClick()
        assertEquals(listOf("b"), opened)
        compose.onNodeWithText("Demonstração sintética").assertExists()
        compose.onNodeWithTag("library_grid").performScrollToKey("a")
        compose.onNodeWithText("Resultado Nova-77").assertExists()
        compose.onAllNodesWithContentDescription("Excluir")[0].performClick()
        assertEquals(listOf("a"), deleted)
    }

    @Test fun longLibraryReachesLastProjectAndDeleteAllWithoutLosingBack() {
        var opened:String?=null;var cleared=false
        val showing=mutableStateOf(true)
        val projects=(0 until 1000).map{SavedProject("p$it","2026-10-15T14:32:00Z",false,97,97.0/30)}
        compose.setContent{PocketPoserTheme{
            val grid=rememberLazyGridState()
            if(showing.value)LibraryScreen(projects,{},{opened=it;showing.value=false},{},{cleared=true},gridState=grid)
            else Button({showing.value=true}){Text("Voltar aos projetos")}
        }}
        compose.onNodeWithTag("project_p999").assertDoesNotExist()
        compose.onNodeWithTag("library_grid").performScrollToKey("p999")
        compose.onNodeWithTag("project_p999").performClick()
        assertEquals("p999",opened)
        compose.onNodeWithText("Voltar aos projetos").performClick()
        compose.onNodeWithTag("project_p999").assertIsDisplayed()
        compose.onNodeWithTag("library_back").assertIsDisplayed()
        compose.onNodeWithTag("library_grid").performScrollToKey("delete-all")
        compose.onNodeWithTag("library_delete_all").performClick()
        assertTrue(cleared)
        compose.onNodeWithTag("library_back").assertIsDisplayed()
    }

    @Test fun projectSummaryUsesSavedFramesAndDuration() {
        val projects=listOf(SavedProject("a","2026-10-15T14:32:00Z",false,97,97.0/30))
        compose.setContent{PocketPoserTheme{LibraryScreen(projects,{},{},{},{})}}
        compose.onNodeWithText("0:03 · 97 quadros").assertExists()
    }
}
