package com.posepocket.pose

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class SavedProject(val id:String,val createdAt:String,val demo:Boolean,val frameCount:Int?=null,val durationSeconds:Double?=null)

class ProjectRepository(private val root:File) {
    init { check(root.isDirectory||root.mkdirs()) }

    @Synchronized
    fun save(clip:PoseClip):SavedProject {
        val id=UUID.randomUUID().toString()
        val temp=File(root,".$id.tmp")
        check(temp.mkdir())
        try {
            val raw=File(temp,"original.jsonl")
            clip.raw.inputStream().use { input -> FileOutputStream(raw).use { out -> input.copyTo(out); out.fd.sync() } }
            val created=Instant.now().toString()
            val manifest=JSONObject().put("schema_version",1).put("project_id",id).put("created_at",created)
                .put("demo",clip.demo).put("input_source",clip.inputSource).put("input_sha256",clip.inputHash?:JSONObject.NULL)
                .put("source_sha256",clip.sourceHash?:JSONObject.NULL)
                .put("raw_sha256",fileHash(raw)).put("fps_numerator",clip.fpsNumerator).put("fps_denominator",clip.fpsDenominator)
                .put("frame_count",clip.frameCount).put("skeleton",if(clip.generator=="NVIDIA")"Nova-77" else "pose-landmarks-33")
                .put("generator",clip.generator)
                .put("hide_empty_frames",clip.frameFilters.hideEmpty).put("remove_duplicate_frames",clip.frameFilters.removeDuplicates)
            clip.correctionLayer?.let { layer->
                val corrections=File(temp,"corrections.json")
                FileOutputStream(corrections).use { it.write(layer.toByteArray());it.fd.sync() }
                manifest.put("correction_sha256",fileHash(corrections))
            }
            FileOutputStream(File(temp,"manifest.json")).use { it.write(manifest.toString().toByteArray()); it.fd.sync() }
            check(fileHash(raw)==fileHash(clip.raw))
            check(temp.renameTo(File(root,id))) { "Falha ao finalizar salvamento." }
            return SavedProject(id,created,clip.demo,clip.frameCount,clip.frameCount.toDouble()*clip.fpsDenominator/clip.fpsNumerator)
        } finally { temp.deleteRecursively() }
    }

    @Synchronized
    fun list():List<SavedProject> = root.listFiles().orEmpty().filter { it.isDirectory&&!it.name.startsWith('.') }.mapNotNull {
        try { val m=JSONObject(File(it,"manifest.json").readText());
            require(m.getString("project_id")==it.name)
            val frames=m.optInt("frame_count",0).takeIf { count->count>0 }
            val numerator=m.optInt("fps_numerator",0)
            val denominator=m.optInt("fps_denominator",0)
            val duration=if(frames!=null&&numerator>0&&denominator>0) frames.toDouble()*denominator/numerator else null
            SavedProject(it.name,m.getString("created_at"),m.getBoolean("demo"),frames,duration)
        } catch(_:Exception){null}
    }.sortedByDescending { it.createdAt }

    @Synchronized
    fun open(id:String):PoseClip {
        val folder=folder(id)
        val m=JSONObject(File(folder,"manifest.json").readText())
        require(m.getInt("schema_version")==1&&m.getString("project_id")==id)
        val raw=File(folder,"original.jsonl")
        require(fileHash(raw)==m.getString("raw_sha256")) { "Projeto corrompido; hash inválido." }
        val clip=readClip(raw,m.getInt("fps_numerator"),m.getInt("fps_denominator"),m.getInt("frame_count"),m.getBoolean("demo"),
            m.getString("input_source"),if(m.isNull("input_sha256"))null else m.getString("input_sha256"))
            .copy(sourceHash=if(m.isNull("source_sha256"))null else m.getString("source_sha256"),
                frameFilters=FrameFilters(m.optBoolean("hide_empty_frames",false),m.optBoolean("remove_duplicate_frames",false)))
        return if(m.has("correction_sha256")) {
            val corrections=File(folder,"corrections.json")
            require(fileHash(corrections)==m.getString("correction_sha256"))
            PoseEditor(clip).apply { load(corrections.readText()) }.clip()
        }else clip
    }

    @Synchronized fun updateFrameFilters(id:String,filters:FrameFilters) {
        val dir=folder(id)
        val manifest=File(dir,"manifest.json")
        val data=JSONObject(manifest.readText())
        require(data.getString("project_id")==id)
        data.put("hide_empty_frames",filters.hideEmpty).put("remove_duplicate_frames",filters.removeDuplicates)
        val temp=File(dir,".manifest-"+UUID.randomUUID()+".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(data.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(),manifest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{temp.delete()}
    }
    @Synchronized fun delete(id:String) { check(folder(id).deleteRecursively()) }
    @Synchronized fun deleteAll() { root.listFiles().orEmpty().forEach { check(it.deleteRecursively()) } }
    private fun folder(id:String):File {
        require(UUID.fromString(id).toString()==id)
        return File(root,id).also { require(it.canonicalFile.parentFile==root.canonicalFile) }
    }
}
