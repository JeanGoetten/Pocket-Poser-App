package com.posepocket

import org.junit.Assert.*
import org.junit.Test

class ProcessingProgressTest {
    @Test fun measuresOnlyActualSubstepProgressAndStripsLegacyStagePrefixes() {
        val upload=ProcessingProgress.fromMessage("Etapa 3/3 — Enviando diretamente à NVIDIA: 48%")!!
        assertEquals(ProcessingSubstep.UPLOAD,upload.step);assertEquals(48,upload.percent)
        assertEquals(ProcessingStage.GENERATE,ProcessingStage.fromMessage("Etapa 3/3 — Recebendo poses Nova-77: 63%"))
        assertEquals(ProcessingStage.GENERATE,ProcessingStage.fromMessage("Etapa 3/3 — Validando poses Nova-77"))
        assertEquals(ProcessingStage.PREPARE,ProcessingStage.fromMessage("Etapa 2/3 — Normalizando vídeo no celular: 12%"))
    }
    @Test fun doesNotInventRemotePercentOrAcceptInvalidCounters() {
        assertNull(ProcessingProgress.fromMessage("NVIDIA gerando poses 3D")!!.percent)
        assertNull(ProcessingProgress.fromMessage("Enviando diretamente à NVIDIA: 101%")!!.percent)
        assertNull(ProcessingProgress.fromMessage("Normalizando vídeo no celular: leitura em lotes")!!.percent)
        assertNull(ProcessingProgress.fromMessage("Mensagem inesperada"))
    }
}
