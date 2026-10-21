package com.posepocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.posepocket.privacy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "pt-rBR")
@LooperMode(LooperMode.Mode.PAUSED)
class PrivacyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store : ConsentStore {
        var value: ConsentReceipt? = null
        var failWrite = false
        override fun read() = value
        override fun write(receipt: ConsentReceipt) {
            if (failWrite) throw java.io.IOException("Storage unavailable")
            value = receipt
        }
        override fun clear() { value = null }
    }
    private val store = Store()
    private val policy = Policy("dev-v1", "video-v1", "Política integral de teste — sem envio real.")
    private val gate = ConsentGate(policy, store)
    private var exits = 0

    private fun launch() { compose.setContent { PocketPoserApp(gate,onExit={exits++}) } }
    private fun accept() {
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Aceitar e continuar").performScrollTo().performClick()
    }

    @Test fun firstLaunchHasTwoUncheckedAffirmationsAndNoInputScreen() {
        launch()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
        compose.onNodeWithText("Aceitar e continuar").assertIsNotEnabled()
        compose.onNodeWithText("Entrada de vídeo").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Aceitar e continuar").assertIsEnabled()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
    }

    @Test fun localOnlyConsentCanBeUpgradedToNvidiaExplicitly() {
        launch()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Aceitar e continuar").performScrollTo().performClick()
        assertTrue(gate.isGranted())
        assertFalse(gate.isRemoteAllowed())
        compose.onNodeWithContentDescription("Privacidade e autorização").performClick()
        compose.onNodeWithText("Autorizar processamento remoto NVIDIA").assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Autorizar processamento remoto NVIDIA").performScrollTo().performClick()
        assertTrue(gate.isRemoteAllowed())
    }

    @Test fun fullPolicyIsReadableWithoutAcceptanceAndChecksReset() {
        launch()
        compose.onNodeWithText("Ler Política de Privacidade").performScrollTo().performClick()
        compose.onNodeWithText("Pocket Poser — política de desenvolvimento",substring=true).assertExists()
        assertFalse(gate.isGranted())
        compose.onNodeWithText("Voltar").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onNodeWithText("Aceitar e continuar").assertIsNotEnabled()
    }

    @Test fun refusalLeavesInputsBlockedAndAllowsReviewOrExit() {
        launch()
        compose.onNodeWithText("Não concordo").performScrollTo().performClick()
        compose.onNodeWithText("Entrada de vídeo").assertDoesNotExist()
        assertFalse(gate.isGranted())
        compose.onNodeWithText("Sair").performClick()
        assertEquals(1, exits)
        compose.onNodeWithText("Rever minha decisão").performClick()
        compose.onNodeWithText("Aceitar e continuar").assertIsNotEnabled()
    }

    @Test fun acceptanceShowsInputAndRevocationImmediatelyClosesIt() {
        launch()
        accept()
        assertTrue(gate.isGranted())
        compose.onNodeWithText("Entrada de vídeo").assertExists()
        compose.onNodeWithContentDescription("Privacidade e autorização").performClick()
        compose.onNodeWithText("Revogar autorização").performScrollTo().performClick()
        assertFalse(gate.isGranted())
        compose.onNodeWithText("Entrada de vídeo").assertDoesNotExist()
        assertNotNull(store.value?.revokedAt)
    }

    @Test fun validStoredConsentSkipsNotice() {
        gate.accept(true, true)
        launch()
        compose.onNodeWithText("Entrada de vídeo").assertExists()
        compose.onNodeWithText("Aceitar e continuar").assertDoesNotExist()
    }

    @Test fun failedSaveKeepsInputsBlockedAndReportsError() {
        store.failWrite = true
        launch()
        accept()
        assertFalse(gate.isGranted())
        compose.onNodeWithText("Entrada de vídeo").assertDoesNotExist()
        compose.onAllNodesWithText("Não foi possível salvar o aceite.", substring = true)[0].assertExists()
    }

    @Test fun changedDocumentRequiresFreshUncheckedConsent() {
        ConsentGate(policy.copy(document = "Documento anterior"), store).accept(true, true)
        launch()
        compose.onNodeWithText("Entrada de vídeo").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
    }

    @Suppress("DEPRECATION")
    @Test fun backMeansRefusalRatherThanAcceptance() {
        launch()
        // Android dispatches Back to the focused Dialog, not the underlying Activity.
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithText("Rever minha decisão").assertExists()
        assertFalse(gate.isGranted())
    }
}
