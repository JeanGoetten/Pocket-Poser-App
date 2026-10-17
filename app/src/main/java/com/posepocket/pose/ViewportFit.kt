package com.posepocket.pose

import kotlin.math.*

/** Stable framing across the whole clip, including derived display poses.
 * User zoom/pan remain independent; clipping handles intentional close-ups. */
data class ViewportFit(val center:Vec3,val extent:Float) {
    companion object {
        fun from(points:Sequence<Vec3>):ViewportFit {
            var low=Vec3(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY)
            var high=Vec3(Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY)
            var found=false
            points.forEach { p ->
                if(p.x.isFinite()&&p.y.isFinite()&&p.z.isFinite()) {
                    found=true
                    low=Vec3(min(low.x,p.x),min(low.y,p.y),min(low.z,p.z))
                    high=Vec3(max(high.x,p.x),max(high.y,p.y),max(high.z,p.z))
                }
            }
            if(!found)return ViewportFit(Vec3(0f,0f,0f),1f)
            val center=Vec3(((low.x.toDouble()+high.x)/2).toFloat(),((low.y.toDouble()+high.y)/2).toFloat(),((low.z.toDouble()+high.z)/2).toFloat())
            val camera=ViewCamera()
            var extent=.1f
            points.forEach {
                val p=camera.project(it-center)
                if(p.x.isFinite()&&p.y.isFinite())extent=max(extent,max(abs(p.x),abs(p.y)))
            }
            return ViewportFit(center,extent)
        }
    }
}
