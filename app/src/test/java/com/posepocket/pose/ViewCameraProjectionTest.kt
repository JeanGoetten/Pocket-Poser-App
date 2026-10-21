package com.posepocket.pose

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewCameraProjectionTest {
    private fun originalFormula(camera: ViewCamera, point: Vec3): Vec3 {
        val x = point.x * cos(camera.yaw) + point.z * sin(camera.yaw)
        val z = -point.x * sin(camera.yaw) + point.z * cos(camera.yaw)
        val y = point.y * cos(camera.pitch) - z * sin(camera.pitch)
        val depth = point.y * sin(camera.pitch) + z * cos(camera.pitch)
        val scale = 3f / (3f + depth).coerceAtLeast(.2f)
        return Vec3(x * scale * camera.zoom + camera.panX, y * scale * camera.zoom + camera.panY, depth)
    }

    @Test fun cachedTrigonometryPreservesProjectionForAllCameraGestures() {
        val cameras = listOf(
            ViewCamera(),
            ViewCamera(yaw=1.2f,pitch=-.8f,zoom=.35f,panX=1.5f,panY=-.7f),
            ViewCamera(yaw=-3.2f,pitch=1.2f,zoom=4f,panX=-3f,panY=3f),
            ViewCamera(yaw=.4f,pitch=.12f,zoom=.86f),
        )
        val points = Nova77.tPose() + listOf(Vec3(-1f,0f,2f),Vec3(2f,-.5f,-2f))
        for (camera in cameras) for (point in points) {
            val expected = originalFormula(camera,point)
            val actual = camera.project(point)
            assertEquals(expected.x,actual.x,1e-6f)
            assertEquals(expected.y,actual.y,1e-6f)
            assertEquals(expected.z,actual.z,1e-6f)
        }
    }

    @Test fun cameraCopyRebuildsCoefficientsWithoutMutatingPriorCamera() {
        val point=Vec3(.5f,.4f,-.2f)
        val before=ViewCamera(yaw=.1f,pitch=.3f)
        val original=before.project(point)
        val after=before.copy(yaw=-.9f,pitch=-.2f).zoomBy(1.2f)
        assertNotEquals(original,after.project(point))
        assertEquals(original,before.project(point))
        assertEquals(originalFormula(after,point),after.project(point))
    }
}
