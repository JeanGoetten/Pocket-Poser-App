package com.posepocket.pose

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

data class Quaternion(val x:Float,val y:Float,val z:Float,val w:Float) {
    fun values()=listOf(x,y,z,w)
    fun normalized():Quaternion {
        val n=sqrt(x*x+y*y+z*z+w*w)
        require(n.isFinite()&&abs(n-1)<.025f){"Quaternion inválido."}
        return Quaternion(x/n,y/n,z/n,w/n)
    }
    fun inverse()=Quaternion(-x,-y,-z,w)
    operator fun times(q:Quaternion)=Quaternion(w*q.x+x*q.w+y*q.z-z*q.y,w*q.y-x*q.z+y*q.w+z*q.x,
        w*q.z+x*q.y-y*q.x+z*q.w,w*q.w-x*q.x-y*q.y-z*q.z)
    fun rotate(v:Vec3):Vec3 {
        val q=this*Quaternion(v.x,v.y,v.z,0f)*inverse()
        return Vec3(q.x,q.y,q.z)
    }
    companion object {
        val IDENTITY=Quaternion(0f,0f,0f,1f)
        fun zDegrees(value:Float):Quaternion { require(value.isFinite());val r=value*PI/360;return Quaternion(0f,0f,sin(r).toFloat(),cos(r).toFloat()) }
        fun fromTo(a:Vec3,b:Vec3):Quaternion {
            val u=a.unit();val v=b.unit();val dot=u.dot(v)
            if(dot<-.999999f) { val axis=u.cross(if(abs(u.x)<.9f)Vec3(1f,0f,0f)else Vec3(0f,1f,0f)).unit();return Quaternion(axis.x,axis.y,axis.z,0f) }
            val cross=u.cross(v);val raw=Quaternion(cross.x,cross.y,cross.z,1+dot)
            val n=sqrt(raw.values().sumOf { (it*it).toDouble() }).toFloat()
            return Quaternion(raw.x/n,raw.y/n,raw.z/n,raw.w/n)
        }
        fun slerp(a:Quaternion,b:Quaternion,t:Float):Quaternion {
            var q=b;var d=a.values().zip(b.values()).sumOf { (x,y)->(x*y).toDouble() }.toFloat()
            if(d<0) {q=Quaternion(-b.x,-b.y,-b.z,-b.w);d=-d}
            val aa:Float;val bb:Float
            if(d>.9995f){aa=1-t;bb=t}else{val angle=acos(d.coerceIn(-1f,1f));aa=sin((1-t)*angle)/sin(angle);bb=sin(t*angle)/sin(angle)}
            return Quaternion(a.x*aa+q.x*bb,a.y*aa+q.y*bb,a.z*aa+q.z*bb,a.w*aa+q.w*bb).normalized()
        }
    }
}
private fun Vec3.dot(v:Vec3)=x*v.x+y*v.y+z*v.z
private fun Vec3.cross(v:Vec3)=Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
private fun Vec3.unit():Vec3 { val n=norm();require(n>1e-7f);return this*(1/n) }
private fun JSONArray.vec()=Vec3(getDouble(0).toFloat(),getDouble(1).toFloat(),getDouble(2).toFloat())
private fun JSONArray.quat()=Quaternion(getDouble(0).toFloat(),getDouble(1).toFloat(),getDouble(2).toFloat(),getDouble(3).toFloat()).normalized()

private data class Body(val rest:List<Vec3>,val rotations:List<Quaternion>,val translation:Vec3,val root:Quaternion)
private data class RotationEvent(val joint:Int,val start:Int,val end:Int,val q:Quaternion,val endQ:Quaternion?=null,val group:String?=null)

/** Separate event layer. Editing and bone export require verified FK; original JSONL never changes. */
class PoseEditor(private val original:PoseClip) {
    private val bodies:List<Body>
    private val rawFrames:List<PoseFrame>
    private val baseHash=fileHash(original.raw)
    private var events=mutableListOf<RotationEvent>()
    var cursor=0;private set
    val canUndo get()=cursor>0
    val canRedo get()=cursor<events.size
    init {
        val records=original.raw.useLines { lines->lines.map { line->
            val frame=JSONObject(line);val frameId=frame.getInt("frame_id")
            val detections=frame.getJSONArray("detections");require(detections.length()==1)
            val body=detections.getJSONObject(0);val rest=body.getJSONArray("rest_pose");val rotations=body.getJSONArray("joint_rotations")
            require(rest.length()==77&&rotations.length()==77)
            val root=body.getJSONObject("root_pose")
            val data=Body((0..76).map{rest.getJSONArray(it).vec()},(0..76).map{rotations.getJSONArray(it).quat()},
                root.getJSONArray("translation").vec(),root.getJSONArray("rotation").quat())
            val inferred=body.getJSONArray("keypoints_3d")
            val predicted=forward(data).first
            require((0..76).all { (predicted[it]-inferred.getJSONArray(it).vec()).norm()<.03f }) {
                "FK e keypoints não reconciliam. Edição avançada bloqueada; o JSON original permanece disponível."
            }
            Triple(frameId,data,(0..76).map { inferred.getJSONArray(it).vec() })
        }.toList() }.sortedBy { it.first }
        require(records.indices.all { records[it].first==it }){"Edição exige quadros contínuos, sem lacunas."}
        bodies=records.map { it.second }
        rawFrames=records.map { PoseFrame(it.first,it.third) }
        require(bodies.size==original.frameCount)
        require(bodies.all { body->body.rest.zip(bodies.first().rest).all { (a,b)->(a-b).norm()<.0001f } }) {"Rest pose variável; é necessário calibrar o rig antes de editar."}
    }
    private fun forward(body:Body):Pair<List<Vec3>,List<Quaternion>> {
        val points=MutableList(77){Vec3(0f,0f,0f)};val world=MutableList(77){Quaternion.IDENTITY}
        world[0]=body.root*body.rotations[0];points[0]=body.translation+body.root.rotate(body.rest[0])
        for(joint in 1..76) {val p=Nova77.parents[joint];world[joint]=world[p]*body.rotations[joint];points[joint]=points[p]+world[p].rotate(body.rest[joint]-body.rest[p])}
        return points to world
    }
    private fun editedBodies():List<Body> {
        val result=bodies.map { it.copy(rotations=it.rotations.toMutableList()) }.toMutableList()
        for(event in events.take(cursor))for(frame in event.start..event.end) {
            val values=result[frame].rotations.toMutableList()
            val t=(frame-event.start).toFloat()/max(1,event.end-event.start)
            values[event.joint]=Quaternion.slerp(event.q,event.endQ?:event.q,t)
            result[frame]=result[frame].copy(rotations=values)
        }
        return result
    }
    @Synchronized fun rotate(joint:Int,start:Int,end:Int,q:Quaternion,endQ:Quaternion?=null) {
        require(joint in 0..76&&start in bodies.indices&&end in start until bodies.size){"Índice da junta ou intervalo de quadros inválido."}
        require(cursor<1000){"Limite de 1000 correções atingido; salve uma versão antes de continuar."}
        events=events.take(cursor).toMutableList()
        events.add(RotationEvent(joint,start,end,q.normalized(),endQ?.normalized()));cursor++
    }
    @Synchronized fun undo() {
        if(cursor==0)return
        val group=events[cursor-1].group
        cursor--;while(group!=null&&cursor>0&&events[cursor-1].group==group)cursor--
    }
    @Synchronized fun redo() {
        if(cursor==events.size)return
        val group=events[cursor].group
        cursor++;while(group!=null&&cursor<events.size&&events[cursor].group==group)cursor++
    }
    @Synchronized fun ik(frame:Int,start:Int,middle:Int,end:Int,target:Vec3,pole:Vec3) {
        require(frame in bodies.indices&&start in 0..76&&middle in 1..76&&end in 1..76){"Confira os índices de quadro e juntas da cadeia IK."}
        require(Nova77.parents[middle]==start&&Nova77.parents[end]==middle){"IK exige dois ossos consecutivos."}
        require(cursor<=998){"IK exige espaço para duas correções na camada."}
        val body=editedBodies()[frame];val(points,world)=forward(body)
        val a=points[start];val b=points[middle];val c=points[end]
        val l1=(b-a).norm();val l2=(c-b).norm();val axis=(target-a).unit()
        val distance=(target-a).norm().coerceIn(abs(l1-l2)+.00001f,l1+l2-.00001f)
        val bend=((pole-a)-axis*((pole-a).dot(axis))).unit()
        val angleCos=((l1*l1+distance*distance-l2*l2)/(2*l1*distance)).coerceIn(-1f,1f)
        val mid=a+axis*(l1*angleCos)+bend*(l1*sqrt(1-angleCos*angleCos));val tip=a+axis*distance
        val delta=Quaternion.fromTo(b-a,mid-a);val desiredA=delta*world[start]
        val parent=if(start==0)body.root else world[Nova77.parents[start]]
        val desiredB=Quaternion.fromTo(delta.rotate(c-b),tip-mid)*delta*world[middle]
        val qa=parent.inverse()*desiredA;val qb=desiredA.inverse()*desiredB
        val group=UUID.randomUUID().toString()
        rotate(start,frame,frame,qa);events[cursor-1]=events[cursor-1].copy(group=group)
        rotate(middle,frame,frame,qb);events[cursor-1]=events[cursor-1].copy(group=group)
    }
    @Synchronized fun layer():String=JSONObject().put("schema_version",1).put("base_capture_sha256",baseHash).put("cursor",cursor)
        .put("events",JSONArray(events.map { event->JSONObject().put("kind","joint_local_rotation").put("joint",event.joint)
            .put("start",event.start).put("end",event.end).put("rotation",JSONArray(event.q.values()))
            .also { if(event.endQ!=null)it.put("end_rotation",JSONArray(event.endQ.values()));if(event.group!=null)it.put("group",event.group) } })).toString()
    @Synchronized fun load(layer:String) {
        val json=JSONObject(layer)
        require(json.getInt("schema_version")==1&&json.getString("base_capture_sha256")==baseHash)
        val values=json.getJSONArray("events");require(values.length()<=1000)
        events.clear();cursor=0
        for(i in 0 until values.length()) {
            val e=values.getJSONObject(i);require(e.getString("kind")=="joint_local_rotation")
            rotate(e.getInt("joint"),e.getInt("start"),e.getInt("end"),e.getJSONArray("rotation").quat(),
                if(e.has("end_rotation"))e.getJSONArray("end_rotation").quat()else null)
            if(e.has("group"))events[cursor-1]=events[cursor-1].copy(group=e.getString("group"))
        }
        val position=json.getInt("cursor");require(position in 0..events.size);cursor=position
    }
    @Synchronized fun clip():PoseClip {
        check(fileHash(original.raw)==baseHash){"O original foi modificado externamente."}
        val changed=events.take(cursor).flatMap { (it.start..it.end).toList() }.toSet()
        val frames=editedBodies().mapIndexed { index,body->if(index in changed)PoseFrame(index,forward(body).first)else rawFrames[index] }
        return original.copy(frames=frames,correctionLayer=layer())
    }
}
