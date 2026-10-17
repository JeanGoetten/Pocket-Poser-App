package com.posepocket

import android.content.Context

/**
 * Converts legacy pipeline messages at the UI boundary.
 * The underlying processor keeps stable diagnostic messages for current tests.
 * New pipeline errors should eventually use typed codes rather than text matching.
 */
internal fun localizeUiMessage(context: Context, message: String): String {
    if (context.resources.configuration.locales[0].language == "pt") return message
    val direct = when (message) {
        "Mais de uma pessoa detectada. Use um vídeo com apenas uma pessoa." -> R.string.backend_multiple_people
        "Rastreamento ambíguo após movimento ou oclusão. Tente outro vídeo." -> R.string.backend_ambiguous_track
        "Não foi possível iniciar o processamento de vídeo neste aparelho." -> R.string.backend_video_init_failed
        "Libere pelo menos 100 MB para preparar o vídeo." -> R.string.backend_free_space
        "O aparelho não produziu vídeo CFR compatível." -> R.string.backend_cfr_failed
        "Contagem de quadros do vídeo normalizado inconsistente." -> R.string.backend_frame_inconsistent
        "Vídeo H.264 SDR 30 CFR compatível; preservando quadros" -> R.string.backend_video_compatible
        "Duração de vídeo inválida; limite de 10 minutos." -> R.string.backend_duration
        "Vídeo não decodificável neste aparelho." -> R.string.backend_decode_unsupported
        "Vídeo normalizado excede 50 MB. Use um vídeo mais curto." -> R.string.backend_final_too_large
        "O codificador do aparelho parou de responder." -> R.string.backend_encoder_stalled
        "Falha ao decodificar quadro do vídeo." -> R.string.backend_decode_frame_failed
        "Codificador sem entrada YUV compatível." -> R.string.backend_yuv_missing
        "Normalizando vídeo no celular: leitura em lotes" -> R.string.backend_read_batches
        "Vídeo pequeno demais para localizar o corpo inteiro." -> R.string.backend_video_too_small
        "Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado." -> R.string.backend_no_person
        "Já há uma inferência em andamento." -> R.string.backend_infer_busy
        "Resposta NVIDIA contém quadro inválido ou duplicado." -> R.string.backend_bad_frame
        "Resposta contém mais de uma pessoa." -> R.string.backend_response_people
        "Resposta NVIDIA inválida ou autorização revogada." -> R.string.backend_response_invalid
        "A NVIDIA encerrou sem resultado completo: faltam quadros esperados ou poses válidas. Nenhum parcial foi salvo." -> R.string.backend_response_incomplete
        "A NVIDIA encerrou o envio antes do fim do vídeo." -> R.string.backend_send_early_end
        "Enviando configuração e rastreamento à NVIDIA" -> R.string.backend_send_config
        "NVIDIA gerando poses 3D" -> R.string.backend_generating
        "Não foi possível concluir o resultado local." -> R.string.backend_result_failed
        "Sessão encerrada" -> R.string.backend_session_closed
        "Chave NVIDIA inválida ou sem acesso ao modelo. Confira sua conta e substitua a chave em Minha chave NVIDIA." -> R.string.backend_invalid_key
        "A NVIDIA recusou o vídeo ou o rastreamento. Tente outro vídeo curto, com uma pessoa e corpo inteiro visível." -> R.string.backend_refused_video
        "Tempo limite da NVIDIA. O processamento remoto pode ter começado; não houve nova tentativa automática." -> R.string.backend_timeout
        "Serviço NVIDIA indisponível ou conexão com o serviço interrompida. Tente reenviar em instantes." -> R.string.backend_unavailable
        "Não foi possível localizar o servidor NVIDIA (DNS). Tente reenviar em instantes ou por outra rede." -> R.string.backend_dns
        "Não foi possível estabelecer uma conexão segura com a NVIDIA (TLS). Verifique data/hora do aparelho ou tente outra rede." -> R.string.backend_tls
        "A conexão com o servidor NVIDIA foi recusada ou interrompida. Tente reenviar em instantes." -> R.string.backend_conn_refused
        "A conexão com o servidor NVIDIA excedeu o tempo de espera. Tente reenviar em instantes." -> R.string.backend_conn_timed
        "Não foi possível descartar o processamento pendente." -> R.string.backend_processing_discard
        "Descarte ou retome o processamento anterior primeiro." -> R.string.backend_processing_before
        "O processamento guardado está incompleto ou corrompido. Descarte-o e selecione o vídeo novamente." -> R.string.backend_processing_corrupted
        "Etapa 1/3 — Preparando vídeo no dispositivo" -> R.string.backend_stage_1
        "Etapa 3/3 — Enviando vídeo já preparado à NVIDIA" -> R.string.backend_stage_3
        "Decodificação incompleta do vídeo normalizado." -> R.string.backend_incomplete_decode
        "Falha ao ler o vídeo normalizado." -> R.string.backend_read_normalized
        "O vídeo de origem excede 250 MB." -> R.string.backend_source_too_large
        "O vídeo convertido excede 50 MB para a NVIDIA." -> R.string.backend_converted_too_large
        "Não foi possível ler o vídeo selecionado." -> R.string.backend_selected_unreadable
        "Espaço insuficiente para preparar vídeo." -> R.string.backend_not_enough_space
        "O arquivo selecionado está vazio." -> R.string.backend_empty_file
        "O arquivo não contém vídeo. Imagens não são aceitas." -> R.string.backend_no_video
        "Vídeo HDR não é suportado. Grave ou exporte em SDR." -> R.string.backend_hdr
        "O vídeo deve ter duração válida de até 10 minutos." -> R.string.backend_long_duration
        "Resolução não suportada." -> R.string.backend_bad_resolution
        "Quadro comprimido excede o limite." -> R.string.backend_large_frame
        "Selecione um vídeo, não uma imagem estática." -> R.string.backend_image_not_video
        else -> null
    }
    if (direct != null) return context.getString(direct)
    for ((prefix, resource) in arrayOf(
        "Normalizando vídeo no celular:" to R.string.backend_normalizing,
        "Localizando uma pessoa no celular:" to R.string.backend_tracking,
        "Enviando diretamente à NVIDIA:" to R.string.backend_uploading,
    )) {
        if (message.startsWith(prefix)) return context.getString(resource) + message.removePrefix(prefix)
    }
    if (message.startsWith("Etapa 2/3 — ")) return "Step 2/3 — " + localizeUiMessage(context, message.removePrefix("Etapa 2/3 — "))
    if (message.startsWith("Etapa 3/3 — ")) return "Step 3/3 — " + localizeUiMessage(context, message.removePrefix("Etapa 3/3 — "))
    // Unknown diagnostic messages remain unchanged instead of inventing an error cause.
    return message
}
