package com.posepocket.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.storage.StorageManager
import com.posepocket.pose.fileHash
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The Build API limit applies to the final normalized H.264 video, NOT the input
 * chosen from a document provider. We allow a larger, bounded local source.
 * Callers must still reject an encoded output above MAX_NVIDIA_BYTES.
 */
internal object VideoSizeLimits {
    const val MAX_SOURCE_BYTES = 250_000_000L
    const val MAX_NVIDIA_BYTES = 50_000_000L

    fun checkSource(bytes:Long) {
        require(bytes in 1..MAX_SOURCE_BYTES) { "O vídeo de origem excede 250 MB." }
    }
    fun checkForNvidia(bytes:Long) {
        require(bytes in 1..MAX_NVIDIA_BYTES) { "O vídeo convertido excede 50 MB para a NVIDIA." }
    }
}
data class PreparedVideo(val file:File,val hash:String,val source:String,val originalHash:String?=null,val focus:FocusWindow?=null,val trim:TrimRange?=null)

/** Half-open interval in microseconds. Preparation retains decode preroll; normalization excludes it. */
data class TrimRange(val startUs:Long,val endUs:Long) {
    init { require(startUs>=0L&&endUs>startUs) { "Invalid trim range." } }
}

/**
 * Review duration comes from MediaMetadataRetriever (milliseconds), while the
 * processing track duration comes from MediaExtractor (microseconds). They may
 * differ slightly because of container rounding, edit lists and time bases.
 * Accept only a small end discrepancy (up to 50 ms); NEVER extend a selection
 * beyond the actual track duration or change its selected start.
 */
internal fun validatedTrimAgainstTrackDuration(range:TrimRange, trackDurationUs:Long):TrimRange {
    require(trackDurationUs>0L)
    val endSlackUs=50_000L
    require(range.startUs<trackDurationUs &&
        range.endUs<=trackDurationUs+endSlackUs) { "Intervalo de vídeo inválido." }
    val end=minOf(range.endUs,trackDurationUs)
    require(end>range.startUs) { "Intervalo de vídeo inválido." }
    return TrimRange(range.startUs,end)
}

class MediaPreparer(private val context:Context,private val gate:ConsentGate) {
    suspend fun prepare(uri:Uri,source:String,trim:TrimRange?=null,focus:FocusWindow?=null,onPhase:(String)->Unit={}):PreparedVideo {
        require(source in listOf("camera","attachment"))
        gate.withConsent { require(uri.scheme=="content") }
        val directory=File(context.cacheDir,"media").apply { mkdirs() }
        val original=File(directory,"${UUID.randomUUID()}.input")
        val output=File(directory,"${UUID.randomUUID()}.mp4")
        val coroutine=currentCoroutineContext()
        var decodeRange:TrimRange?=null
        try {
            val storage=context.getSystemService(StorageManager::class.java)
            val allocatable=storage.getAllocatableBytes(storage.getUuidForPath(directory))
            val expected=try { gate.withConsent { context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use {
                if(it.moveToFirst()&&!it.isNull(0))it.getLong(0).takeIf{n->n>0} else null
            } } }catch(_:Exception){null}
            onPhase("Importando vídeo")
            val input=gate.withConsent { context.contentResolver.openInputStream(uri) }
                ?: error("Não foi possível ler o vídeo selecionado.")
            input.use { stream -> original.outputStream().use { out ->
                val buffer=ByteArray(65536); var total=0L
                while(true) {
                    coroutine.ensureActive(); gate.withConsent { }
                    val n=stream.read(buffer); if(n<0)break
                    total+=n; VideoSizeLimits.checkSource(total)
                    require(total*2+2_000_000<allocatable) { "Espaço insuficiente para preparar vídeo." }
                    out.write(buffer,0,n)
                    if(expected!=null)onPhase("Importando vídeo: ${(total*100/expected).coerceAtMost(99)}%")
                }
                require(total>0) { "O arquivo selecionado está vazio." }
            } }
            onPhase("Importando vídeo: 100%")
            onPhase("Validando vídeo de origem")
            val originalHash=fileHash(original)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(original.absolutePath)
                val videoTrack=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                    ?: error("O arquivo não contém vídeo. Imagens não são aceitas.")
                val format=extractor.getTrackFormat(videoTrack)
                if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))require(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_ST2084,MediaFormat.COLOR_TRANSFER_HLG)) { "Vídeo HDR não é suportado. Grave ou exporte em SDR." }
                val duration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION)else 0
                require(duration in 1..600_000_000) { "O vídeo deve ter duração válida de até 10 minutos." }
                val effectiveTrim=trim?.let { validatedTrimAgainstTrackDuration(it,duration) }
                val width=format.getInteger(MediaFormat.KEY_WIDTH); val height=format.getInteger(MediaFormat.KEY_HEIGHT)
                require(width in 2..4096&&height in 2..4096) { "Resolução não suportada." }
                onPhase("Removendo áudio")
                val muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var started=false
                try {
                    val metadata=MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(original.absolutePath)
                        val rotation=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
                        if(rotation in listOf(0,90,180,270))muxer.setOrientationHint(rotation)
                    } finally { metadata.release() }
                    val target=muxer.addTrack(format)
                    extractor.selectTrack(videoTrack)
                    if(effectiveTrim!=null)extractor.seekTo(effectiveTrim.startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    muxer.start(); started=true
                    val buffer=ByteBuffer.allocate(8*1024*1024)
                    val info=MediaCodec.BufferInfo()
                    var frames=0
                    val first=extractor.sampleTime
                    if(effectiveTrim!=null) {
                        require(first>=0 && first<=effectiveTrim.startUs) { "Intervalo de vídeo inválido." }
                        decodeRange=TrimRange(effectiveTrim.startUs-first,effectiveTrim.endUs-first)
                    }
                    while(true) {
                        coroutine.ensureActive(); gate.withConsent { }
                        buffer.clear()
                        val count=extractor.readSampleData(buffer,0)
                        if(count<0)break
                        require(count<buffer.capacity()) { "Quadro comprimido excede o limite." }
                        info.set(0,count,(extractor.sampleTime-first).coerceAtLeast(0),
                            if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(target,buffer,info)
                        val selectedEnd=duration
                        val progress=((extractor.sampleTime-first)*100/(selectedEnd-first).coerceAtLeast(1)).coerceIn(0,99)
                        if(frames%15==0)onPhase("Removendo áudio: $progress%")
                        frames++; require(frames<=144000) { "Limite de quadros de origem excedido (240 FPS por 10 minutos)." }
                        extractor.advance()
                    }
                    require(frames>=2) { "Selecione um vídeo, não uma imagem estática." }
                } finally {
                    try { if(started)muxer.stop() } finally { muxer.release() }
                }
            } finally { extractor.release() }
            onPhase("Removendo áudio: 100%")
            VideoSizeLimits.checkSource(output.length())
            gate.withConsent { }
            return PreparedVideo(output,fileHash(output),source,originalHash,focus,decodeRange)
        } catch(error:Throwable) {
            output.delete(); throw error
        } finally { original.delete() }
    }
}
