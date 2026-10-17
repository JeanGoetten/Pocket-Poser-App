package com.posepocket

import com.posepocket.ui.PocketIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStageTest {
    @Test fun mapsRealPipelineMessagesToSteps() {
        assertEquals(ProcessingStage.PREPARE, ProcessingStage.fromMessage("Etapa 1/3 — Normalizando vídeo"))
        assertEquals(ProcessingStage.LOCATE, ProcessingStage.fromMessage("Etapa 2/3 — Localizando uma pessoa"))
        assertEquals(ProcessingStage.SEND, ProcessingStage.fromMessage("Enviando vídeo à NVIDIA"))
        assertEquals(ProcessingStage.GENERATE, ProcessingStage.fromMessage("NVIDIA gerando poses"))
    }

    @Test fun unknownMessagesDoNotMoveTheSteps() {
        assertNull(ProcessingStage.fromMessage("Mensagem inesperada"))
        assertNull(ProcessingStage.fromMessage(""))
    }

    @Test fun stepsAreOrderedForTheStepList() {
        assertEquals(listOf("PREPARE", "LOCATE", "SEND", "GENERATE"), ProcessingStage.entries.map { it.name })
    }
}

class PocketIconsTest {
    @Test fun everyIconPathParses() {
        assertTrue(PocketIcons.all.isNotEmpty())
        PocketIcons.all.forEach { assertTrue(it.name, it.root.size > 0) }
    }
}
