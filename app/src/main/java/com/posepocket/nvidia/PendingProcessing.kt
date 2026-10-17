package com.posepocket.nvidia

import android.net.Uri
import android.util.AtomicFile
import com.posepocket.media.PreparedVideo
import com.posepocket.pose.PoseClip
import com.posepocket.pose.fileHash
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class RecoveryStage { NORMALIZE, NVIDIA, INVALID }
data class PendingProcessing(val input:PreparedVideo,val video:DeviceVideo?=null) {
    val stage get()=if(video==null)RecoveryStage.NORMALIZE else RecoveryStage.NVIDIA
}

/** One explicit pending job, private and excluded from backup/cache eviction.
 * Commit immutable files before publishing the atomic manifest. Never store keys. */
class PendingProcessingStore(private val root:File) {
    private val manifest get()=AtomicFile(File(root,"pending.json"))
    @Synchronized fun clear() { check(!root.exists()||root.deleteRecursively()) { "Não foi possível descartar o processamento pendente." } }
    @Synchronized fun prepared(input:PreparedVideo):PendingProcessing {
        check(load()==null) { "Descarte ou retome o processamento anterior primeiro." }
        check(root.isDirectory||root.mkdirs())
        copy(input.file,File(root,"input.mp4"),250_000_000)
        val value=JSONObject().put("schema",1).put("source",input.source).put("input_hash",input.hash)
            .put("original_hash",input.originalHash?:JSONObject.NULL)
            .put("focus",input.focus?.let{ JSONArray(listOf(it.left,it.top,it.right,it.bottom)) }?:JSONObject.NULL)
        write(value)
        return requireNotNull(load())
    }
    @Synchronized fun normalized(video:DeviceVideo):PendingProcessing {
        requireNotNull(load())
        copy(video.file,File(root,"normalized.mp4"),50_000_000)
        val value=JSONObject(String(manifest.readFully(),Charsets.UTF_8))
        value.put("video",JSONObject().put("hash",video.hash).put("frames",video.frames).put("detector_frames",video.detectorFrames)
            .put("width",video.width).put("height",video.height)
            .put("boxes",JSONArray(video.boxes.map { box -> JSONArray(listOf(box.frame,box.x,box.y,box.width,box.height)) })))
        write(value)
        return requireNotNull(load())
    }
    @Synchronized fun load():PendingProcessing? {
        if(!File(root,"pending.json").exists()&&!File(root,"pending.json.bak").exists()) {
            // Files left before the first committed checkpoint are not resumable.
            clear();return null
        }
        val bytes=manifest.readFully()
        require(bytes.size<=8_000_000)
        val value=JSONObject(String(bytes,Charsets.UTF_8))
        require(value.getInt("schema")==1)
        val source=value.getString("source");require(source in listOf("camera","attachment"))
        val input=File(root,"input.mp4")
        checkFile(input,value.getString("input_hash"),250_000_000)
        val focusData=value.optJSONArray("focus")
        val focus=focusData?.let{
            require(it.length()==4)
            com.posepocket.media.FocusWindow(it.getDouble(0).toFloat(),it.getDouble(1).toFloat(),it.getDouble(2).toFloat(),it.getDouble(3).toFloat())
        }
        val prepared=PreparedVideo(input,value.getString("input_hash"),source,
            if(value.isNull("original_hash"))null else value.getString("original_hash"),focus)
        val data=value.optJSONObject("video")?:return PendingProcessing(prepared)
        val file=File(root,"normalized.mp4");checkFile(file,data.getString("hash"),50_000_000)
        val frames=data.getInt("frames");require(frames in 2..18000)
        val width=data.getInt("width");val height=data.getInt("height")
        require(width in 64..720&&height in 128..720)
        val array=data.getJSONArray("boxes");require(array.length() in 1..frames)
        val boxes=(0 until array.length()).map { index ->
            val row=array.getJSONArray(index)
            require(row.length()==5)
            PersonBox(row.getInt(0),row.getDouble(1).toFloat(),row.getDouble(2).toFloat(),row.getDouble(3).toFloat(),row.getDouble(4).toFloat()).also {
                require(it.frame in 0 until frames&&listOf(it.x,it.y,it.width,it.height).all(Float::isFinite))
                require(it.width>0&&it.height>0&&it.x<width&&it.y<height&&it.x+it.width>0&&it.y+it.height>0)
            }
        }
        require(boxes.map{it.frame}.distinct().size==boxes.size)
        val detected=data.optInt("detector_frames",boxes.size);require(detected in 1..boxes.size)
        return PendingProcessing(prepared,DeviceVideo(file,data.getString("hash"),frames,width,height,boxes,detected))
    }
    private fun checkFile(file:File,hash:String,maxBytes:Long) {
        require(file.length() in 1..maxBytes&&hash.matches(Regex("[a-f0-9]{64}"))&&fileHash(file)==hash) {
            "O processamento guardado está incompleto ou corrompido. Descarte-o e selecione o vídeo novamente."
        }
    }
    private fun copy(source:File,target:File,maxBytes:Long) {
        val partial=File(root,"${target.name}.partial")
        try {
            source.inputStream().use { input -> FileOutputStream(partial).use { out -> input.copyTo(out);out.fd.sync() } }
            check(partial.length() in 1..maxBytes)
            check(partial.renameTo(target))
        }finally{partial.delete()}
    }
    private fun write(value:JSONObject) {
        val stream=manifest.startWrite()
        try { stream.write(value.toString().toByteArray());stream.fd.sync();manifest.finishWrite(stream) }
        catch(error:Exception){manifest.failWrite(stream);throw error}
    }
}

/** The orchestration is shared by initial processing and an explicit retry. */
class ResumableProcessing(
    private val gate:ConsentGate,
    private val store:PendingProcessingStore,
    private val prepare:suspend (Uri,String)->PreparedVideo,
    private val normalize:suspend (PreparedVideo,(String)->Unit)->DeviceVideo,
    private val infer:suspend (DeviceVideo,PreparedVideo,(String)->Unit)->PoseClip,
) {
    suspend fun run(uri:Uri?,source:String?,phase:(String)->Unit,checkpoint:(RecoveryStage)->Unit):PoseClip {
        currentCoroutineContext().ensureActive();gate.withConsent { }
        var job=if(uri==null)requireNotNull(store.load()) { "Nenhum processamento pendente." } else {
            phase("Etapa 1/3 — Preparando vídeo no dispositivo")
            val input=prepare(uri,requireNotNull(source))
            try { store.prepared(input) }finally{input.file.delete()}
        }
        checkpoint(job.stage)
        currentCoroutineContext().ensureActive();gate.withConsent { }
        if(job.video==null) {
            val normalized=normalize(job.input) { phase("Etapa 2/3 — $it") }
            try { job=store.normalized(normalized) }finally{normalized.file.delete()}
            checkpoint(job.stage)
        }
        currentCoroutineContext().ensureActive();gate.withConsent { }
        phase("Etapa 3/3 — Enviando vídeo já preparado à NVIDIA")
        return infer(requireNotNull(job.video),job.input) { phase("Etapa 3/3 — $it") }
    }
}
