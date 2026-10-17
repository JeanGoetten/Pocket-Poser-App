package com.posepocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="pt-rBR")
@LooperMode(LooperMode.Mode.PAUSED)
class NvidiaKeyPanelTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultsAreVisibleAndSavingRequiresInput() {
        compose.setContent { MaterialTheme { NvidiaKeyPanel {} } }
        compose.onNodeWithText("Detalhes da conexão").performClick()
        compose.onNodeWithText("Modelo padrão: NVIDIA Body Pose (Nova-77)").assertExists()
        compose.onNodeWithText("Endpoint padrão: grpc.nvcf.nvidia.com:443").assertExists()
        compose.onNodeWithText("Salvar chave").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Apagar chave").assertDoesNotExist()
        compose.onNodeWithText("A conexão é direta com a NVIDIA.",substring=true).assertExists()
    }

    @Test fun invalidKeyDoesNotBecomeSavedAndClosingClearsUnsavedEntry() {
        var closed=false
        compose.setContent { MaterialTheme { NvidiaKeyPanel { closed=true } } }
        compose.onNodeWithText("API key NVIDIA").performTextInput("curta")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Salvar chave").performScrollTo().performClick()
        compose.onNodeWithText("Informe uma chave válida, sem espaços ou quebras de linha.").assertExists()
        compose.onNodeWithText("Chave salva neste aparelho.").assertDoesNotExist()
        compose.onNodeWithText("Fechar").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(closed) }
    }
}
