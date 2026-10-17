package com.posepocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.posepocket.nvidia.RecoveryStage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="pt-rBR")
class ProcessingRecoveryTest {
    @get:Rule val compose=createComposeRule()
    @Test fun readyCheckpointClearlyRetriesOnlyNvidiaAfterExplicitTap() {
        var retries=0;var discards=0
        compose.setContent { MaterialTheme {
            ProcessingRecovery(RecoveryStage.NVIDIA,{retries++},{discards++},{})
        } }
        assertEquals(0,retries)
        compose.onNodeWithText("Vídeo e rastreamento prontos.",substring=true).assertExists()
        compose.onNodeWithText("Reenviar à NVIDIA").performClick()
        assertEquals(1,retries);assertEquals(0,discards)
        compose.onNodeWithTag("recovery_discard").performClick()
        assertEquals(1,discards)
    }
}
