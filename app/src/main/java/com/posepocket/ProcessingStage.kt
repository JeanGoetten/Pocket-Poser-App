package com.posepocket

/**
 * User-visible processing steps. They are derived from the pipeline's own progress messages,
 * never from invented percentages (RF-08).
 */
enum class ProcessingStage {
    PREPARE, LOCATE, SEND, GENERATE;

    companion object {
        /**
         * Maps a legacy pipeline message (original Portuguese text, before localization) to a step.
         * Unknown messages return null so the UI keeps the last known step instead of guessing.
         */
        fun fromMessage(message: String): ProcessingStage? {
            ProcessingProgress.fromMessage(message)?.let{return it.step.stage}
            val text = message.replace(Regex("^Etapa [123]/3 — "),"")
            return when {
                text.startsWith("Localizando uma pessoa") -> LOCATE
                text.startsWith("NVIDIA gerando") -> GENERATE
                text.startsWith("Enviando") || text.startsWith("Etapa 3/3") -> SEND
                message.startsWith("Etapa 1/3") || text.startsWith("Normalizando") ||
                    text.startsWith("Preparando") || text.startsWith("Vídeo H.264") -> PREPARE
                else -> null
            }
        }
    }
}
