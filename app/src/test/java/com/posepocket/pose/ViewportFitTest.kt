package com.posepocket.pose

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ViewportFitTest {
    @Test fun everyProjectedJointFitsWithMarginAcrossMovingPoses() {
        val first=Nova77.tPose()
        val moving=first.map{it+Vec3(2f,-1f,-1.5f)}
        val points=first+moving
        val fit=ViewportFit.from(points.asSequence())
        val camera=ViewCamera()
        points.forEach {
            val projected=camera.project(it-fit.center)
            assertTrue(abs(projected.x)/fit.extent<=1.0001f)
            assertTrue(abs(projected.y)/fit.extent<=1.0001f)
        }
        assertEquals(Nova77.tPose(),first)
    }
    @Test fun emptyFramesHaveFiniteFallback() {
        val fit=ViewportFit.from(emptySequence())
        assertTrue(fit.extent.isFinite()&&fit.extent>0)
        assertEquals(Vec3(0f,0f,0f),fit.center)
    }
}
