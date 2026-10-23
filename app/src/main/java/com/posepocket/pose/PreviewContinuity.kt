package com.posepocket.pose

import kotlin.math.*

data class PreviewPose(val joints:List<Vec3>,val interpolated:Boolean)

/** Derived display-only data. Never written into a clip, project or export.
 * Bounded interpolation preserves bone lengths and never extrapolates edges. */
class PreviewContinuity(frames:List<PoseFrame>,fps:Double) {
    private val originals=frames.associateBy{it.id}
    private val filled=mutableMapOf<Int,List<Vec3>>()
    val count get()=filled.size
    fun positions():Sequence<Vec3> = sequence {
        originals.values.forEach { frame -> frame.joints?.forEach { yield(it) } }
        filled.values.forEach{yieldAll(it)}
    }
    init {
        val known=frames.filter{it.joints?.size==77}.sortedBy{it.id}
        val maxGap=min(6,floor(fps*.2).toInt())
        known.zipWithNext().forEach { (a,b) ->
            val missing=b.id-a.id-1
            if(missing in 1..maxGap) {
                val start=requireNotNull(a.joints);val end=requireNotNull(b.joints)
                val generated=(1..missing).map { offset ->
                    interpolate(start,end,offset.toFloat()/(missing+1))
                }
                if(generated.all{it!=null})generated.forEachIndexed { i,pose -> filled[a.id+i+1]=requireNotNull(pose) }
            }
        }
    }
    fun at(frame:Int,smooth:Boolean):PreviewPose? {
        originals[frame]?.joints?.let{return PreviewPose(it,false)}
        return if(smooth)filled[frame]?.let{PreviewPose(it,true)}else null
    }
    private fun interpolate(a:List<Vec3>,b:List<Vec3>,t:Float):List<Vec3>? {
        if(a.size!=77||b.size!=77||a.any{!it.x.isFinite()||!it.y.isFinite()||!it.z.isFinite()}
            ||b.any{!it.x.isFinite()||!it.y.isFinite()||!it.z.isFinite()}||(a[0]-b[0]).norm()>.8f)return null
        val result=mutableListOf(a[0]*(1-t)+b[0]*t)
        for(i in 1..76) {
            val parent=Nova77.parents[i]
            val av=a[i]-a[parent];val bv=b[i]-b[parent]
            val al=av.norm();val bl=bv.norm()
            if(al<1e-6f||bl<1e-6f) {
                if(max(al,bl)>1e-4f)return null
                result+=result[parent];continue
            }
            if(min(al,bl)/max(al,bl)<.8f)return null
            val an=av*(1/al);val bn=bv*(1/bl)
            val dot=an.x*bn.x+an.y*bn.y+an.z*bn.z
            if(dot<-.95f)return null
            val direction=an*(1-t)+bn*t
            result+=result[parent]+direction*((al*(1-t)+bl*t)/direction.norm())
        }
        return result
    }
}
