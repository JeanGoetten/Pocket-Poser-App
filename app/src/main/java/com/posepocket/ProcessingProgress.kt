package com.posepocket

enum class ProcessingSubstep(val stage:ProcessingStage,val label:Int) {
    IMPORT(ProcessingStage.PREPARE,R.string.progress_import),
    INSPECT(ProcessingStage.PREPARE,R.string.progress_inspect),
    MUTE(ProcessingStage.PREPARE,R.string.progress_mute),
    NORMALIZE(ProcessingStage.PREPARE,R.string.progress_normalize),
    CHECK_VIDEO(ProcessingStage.PREPARE,R.string.progress_check_video),
    DETECT(ProcessingStage.LOCATE,R.string.progress_detect),
    CHECK_TRACK(ProcessingStage.LOCATE,R.string.progress_check_track),
    CONFIGURE(ProcessingStage.SEND,R.string.progress_configure),
    UPLOAD(ProcessingStage.SEND,R.string.progress_upload),
    WAIT(ProcessingStage.GENERATE,R.string.progress_wait),
    RECEIVE(ProcessingStage.GENERATE,R.string.progress_receive),
    VALIDATE(ProcessingStage.GENERATE,R.string.progress_validate),
}

/** Percent is scoped to this substep, never an estimate of remote processing time. */
data class ProcessingProgress(val step:ProcessingSubstep,val percent:Int?=null) {
    companion object {
        fun fromMessage(message:String):ProcessingProgress? {
            val text=message.replace(Regex("^Etapa [123]/3 — "),"")
            val step=when {
                text.startsWith("Importando vídeo")->ProcessingSubstep.IMPORT
                text.startsWith("Validando vídeo de origem")->ProcessingSubstep.INSPECT
                text.startsWith("Removendo áudio")->ProcessingSubstep.MUTE
                text.startsWith("Normalizando")||text.startsWith("Vídeo H.264")->ProcessingSubstep.NORMALIZE
                text.startsWith("Verificando vídeo normalizado")->ProcessingSubstep.CHECK_VIDEO
                text.startsWith("Localizando uma pessoa")->ProcessingSubstep.DETECT
                text.startsWith("Verificando rastreamento")->ProcessingSubstep.CHECK_TRACK
                text.startsWith("Enviando configuração")->ProcessingSubstep.CONFIGURE
                text.startsWith("Enviando")->ProcessingSubstep.UPLOAD
                text.startsWith("NVIDIA gerando")->ProcessingSubstep.WAIT
                text.startsWith("Recebendo poses Nova-77")->ProcessingSubstep.RECEIVE
                text.startsWith("Validando poses Nova-77")->ProcessingSubstep.VALIDATE
                text.startsWith("Preparando")->ProcessingSubstep.IMPORT
                else->return null
            }
            val percent=Regex(": (\\d{1,3})%$").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf{it in 0..100}
            return ProcessingProgress(step,percent)
        }
    }
}
