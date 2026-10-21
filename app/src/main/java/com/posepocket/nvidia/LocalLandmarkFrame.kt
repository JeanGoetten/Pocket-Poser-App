package com.posepocket.nvidia

import com.posepocket.pose.Vec3

/** 33 landmark slots, null where the pose model lacks sufficient evidence. */
data class LocalLandmarkFrame(
    val points:List<Vec3?>,
    val metric:Boolean,
    /** Probability of being visible, NOT presence or validity; hidden limbs may be estimated. */
    val visibilityScores:List<Float?>?=null,
) {
    init {
        require(points.size==33)
        require(visibilityScores==null || (visibilityScores.size==33 &&
            visibilityScores.all { it==null || (it.isFinite() && it in 0f..1f) }))
    }
    val usable get() = points[11]!=null && points[12]!=null &&
        points[23]!=null && points[24]!=null &&
        listOf(25,26,27,28).any { points[it]!=null }
}
