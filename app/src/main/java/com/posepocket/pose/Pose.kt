package com.posepocket.pose

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(v: Vec3) = Vec3(x+v.x,y+v.y,z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x,y-v.y,z-v.z)
    operator fun times(s: Float) = Vec3(x*s,y*s,z*s)
    fun norm() = sqrt(x*x+y*y+z*z)
}

object Nova77 {
    // NVIDIA nim-clients d02b7d57541b8598ac4c057947b10b2e42ccb1ee, MIT; see THIRD_PARTY_NOTICES.
    val parents = listOf(-1,0,1,2,3,4,5,6,6,6,6,3,11,12,13,14,15,16,17,
        14,19,20,21,22,14,24,25,26,27,14,29,30,31,32,14,34,35,36,37,
        3,39,40,41,42,43,44,45,42,47,48,49,50,42,52,53,54,55,42,57,
        58,59,60,42,62,63,64,65,0,67,68,69,70,0,72,73,74,75)

    /** Authored canonical T-pose for visualization, not the NVIDIA rest_pose. Metres, Y up. */
    fun tPose(): List<Vec3> {
        val p = MutableList(77) { Vec3(0f,0f,0f) }
        listOf(0f,.14f,.28f,.42f,.54f,.63f,.75f).forEachIndexed { i,y -> p[i]=Vec3(0f,y,0f) }
        for (i in 7..10) p[i]=Vec3((i-8.5f)*.025f,.76f,.06f)
        for ((base,sign) in listOf(11 to -1f,39 to 1f)) {
            listOf(.12f,.23f,.48f,.72f).forEachIndexed { i,x -> p[base+i]=Vec3(sign*x,.42f,0f) }
            for (finger in 0..4) {
                val start=base+listOf(4,8,13,18,23)[finger]
                for (part in 0 until if(finger==0)4 else 5) {
                    val index=start+part
                    if (index<base+28) p[index]=Vec3(sign*(.75f+part*.02f),.42f+(finger-2)*.018f,0f)
                }
            }
        }
        for ((base,sign) in listOf(67 to -1f,72 to 1f)) {
            p[base]=Vec3(sign*.1f,-.1f,0f)
            p[base+1]=Vec3(sign*.1f,-.48f,0f)
            p[base+2]=Vec3(sign*.1f,-.88f,0f)
            p[base+3]=Vec3(sign*.1f,-.94f,.08f)
            p[base+4]=Vec3(sign*.1f,-.94f,.18f)
        }
        return p
    }
}

data class PoseFrame(val id: Int, val joints: List<Vec3>?)
data class PoseClip(
    val raw: File, val frames: List<PoseFrame>, val fpsNumerator: Int, val fpsDenominator: Int,
    val frameCount: Int, val demo: Boolean, val inputSource: String, val inputHash: String?,
    val correctionLayer:String?=null,
    val sourceHash:String?=null,
    val frameFilters:FrameFilters=FrameFilters(),
) {
    val fps: Double get()=fpsNumerator.toDouble()/fpsDenominator
}

fun fileHash(file: File): String {
    val hash=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer=ByteArray(65536)
        while (true) { val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

fun readClip(raw: File, fpsNum: Int, fpsDen: Int, frameCount: Int, demo: Boolean, source: String, hash: String?): PoseClip {
    require(raw.length() in 1..64_000_000 && fpsNum>0 && fpsDen>0 && fpsNum.toDouble()/fpsDen in 1.0..60.0 && frameCount in 2..18000)
    val ids=HashSet<Int>()
    val frames=raw.useLines { lines -> lines.map { line ->
        require(line.length<=256_000) { "Quadro de resultado muito grande." }
        val json=JSONObject(line)
        require(json.get("frame_id") is Number&&json.getDouble("frame_id")==json.getInt("frame_id").toDouble())
        val id=json.getInt("frame_id")
        require(demo||!json.optString("provenance").startsWith("synthetic")) { "Demonstração sintética não pode ser apresentada como inferência real." }
        require(id in 0 until frameCount && ids.add(id)) { "Quadro inválido ou duplicado." }
        val detections=json.getJSONArray("detections")
        require(detections.length()<=1) { "Resultado deve conter uma pessoa." }
        val joints=if(detections.length()==0) null else {
            val body=detections.getJSONObject(0)
            require(body.getInt("tracking_id")==1)
            for ((field,size) in listOf("keypoints_3d" to 3,"rest_pose" to 3,"joint_rotations" to 4,"keypoints_2d" to 2)) {
                val data=body.getJSONArray(field); require(data.length()==77)
                for(i in 0..76) {
                    val v=data.getJSONArray(i); require(v.length()==size)
                    for(j in 0 until size) require(v.getDouble(j).isFinite())
                }
            }
            val confidence=body.getJSONArray("keypoints_confidence"); require(confidence.length()==77)
            for(i in 0..76) require(confidence.getDouble(i).isFinite())
            val root=body.getJSONObject("root_pose")
            val translation=root.getJSONArray("translation");require(translation.length()==3)
            for(i in 0..2)require(translation.getDouble(i).isFinite())
            val quaternions=(0..76).map { body.getJSONArray("joint_rotations").getJSONArray(it) }+root.getJSONArray("rotation")
            quaternions.forEach { q->
                require(q.length()==4)
                var sum=0.0;for(i in 0..3){val value=q.getDouble(i);require(value.isFinite());sum+=value*value}
                require(kotlin.math.abs(sum-1)<.05)
            }
            val values=body.getJSONArray("keypoints_3d")
            (0..76).map { i -> values.getJSONArray(i).let { v ->
                Vec3(v.getDouble(0).toFloat(),v.getDouble(1).toFloat(),v.getDouble(2).toFloat()).also {
                    require(it.x.isFinite()&&it.y.isFinite()&&it.z.isFinite()&&it.norm()<10000)
                }
            } }
        }
        PoseFrame(id,joints)
    }.toList() }.sortedBy { it.id }
    require(frames.size==frameCount) { "Resultado incompleto: faltam quadros da animação." }
    require(frames.any { it.joints!=null }) { "Resultado sem poses." }
    return PoseClip(raw,frames,fpsNum,fpsDen,frameCount,demo,source,hash)
}

fun syntheticDemo(file: File): PoseClip {
    val rest=Nova77.tPose()
    file.bufferedWriter().use { out ->
        repeat(120) { frame ->
            val angle=sin(frame/30.0)*.55
            val c=cos(angle).toFloat(); val s=sin(angle).toFloat()
            val joints=rest.mapIndexed { i,p ->
                if(i in 12..38) {
                    val d=p-rest[11]
                    Vec3(d.x*c-d.y*s,d.x*s+d.y*c,d.z)+rest[11]
                } else p
            }
            val vectors: (List<Vec3>)->JSONArray = { list -> JSONArray(list.map { listOf(it.x,it.y,it.z) }) }
            val rotations=JSONArray((0..76).map { i ->
                if(i==11) listOf(0.0,0.0,sin(angle/2),cos(angle/2)) else listOf(0.0,0.0,0.0,1.0)
            })
            val body=JSONObject().put("tracking_id",1).put("bbox",JSONArray(listOf(0,0,100,200)))
                .put("keypoints_3d",vectors(joints)).put("rest_pose",vectors(rest)).put("joint_rotations",rotations)
                .put("keypoints_2d",JSONArray(joints.map { listOf(it.x,it.y) }))
                .put("keypoints_confidence",JSONArray(List(77){1.0}))
                .put("root_pose",JSONObject().put("translation",JSONArray(listOf(0,0,0))).put("rotation",JSONArray(listOf(0,0,0,1))))
            out.write(JSONObject().put("frame_id",frame).put("detections",JSONArray().put(body)).put("provenance","synthetic-demo-not-nvidia").toString())
            out.newLine()
        }
    }
    return readClip(file,30,1,120,true,"synthetic",null)
}

data class ViewCamera(val yaw: Float=0f,val pitch:Float=0f,val zoom:Float=1f,val panX:Float=0f,val panY:Float=0f) {
    fun zoomBy(factor:Float)=copy(zoom=(zoom*factor).coerceIn(.35f,4f))
    fun project(p:Vec3):Vec3 {
        val x=p.x*cos(yaw)+p.z*sin(yaw)
        val z=-p.x*sin(yaw)+p.z*cos(yaw)
        val y=p.y*cos(pitch)-z*sin(pitch)
        val depth=p.y*sin(pitch)+z*cos(pitch)
        val scale=3f/(3f+depth).coerceAtLeast(.2f)
        return Vec3((x*scale*zoom)+panX,(y*scale*zoom)+panY,depth)
    }
}

data class Playback(val frame:Int=0,val playing:Boolean=true,val tPose:Boolean=false) {
    fun pause()=copy(playing=false)
    fun resume()=copy(frame=if(tPose)0 else frame,playing=true,tPose=false)
    fun resetPose()=copy(playing=false,tPose=true)
    fun advance(count:Int)=if(playing)copy(frame=(frame+1)%count)else this
}
