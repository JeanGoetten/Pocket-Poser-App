package com.posepocket.pose

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PreviewContinuityTest {
    private val a=Nova77.tPose()
    private val b=a.map{Vec3((it.x-it.y)/sqrt(2f)+.2f,(it.x+it.y)/sqrt(2f),it.z)}
    @Test fun interpolatesOnlyWhenEnabledWithoutChangingOriginalsOrBoneLengths() {
        val source=listOf(PoseFrame(0,a),PoseFrame(1,null),PoseFrame(2,b))
        val preview=PreviewContinuity(source,30.0)
        assertNull(preview.at(1,false));assertEquals(1,preview.count)
        val middle=preview.at(1,true)!!
        assertTrue(middle.interpolated);assertSame(a,preview.at(0,true)!!.joints)
        assertFalse(preview.at(0,true)!!.interpolated);assertNull(source[1].joints)
        for(i in 1..76) {
            val parent=Nova77.parents[i]
            assertEquals((a[i]-a[parent]).norm(),(middle.joints[i]-middle.joints[parent]).norm(),1e-5f)
        }
    }
    @Test fun doesNotExtrapolateEdgesOrFillLongGaps() {
        val preview=PreviewContinuity(listOf(PoseFrame(1,a),PoseFrame(9,b)),30.0)
        assertEquals(0,preview.count);assertNull(preview.at(0,true));assertNull(preview.at(10,true))
    }
    @Test fun rejectsPositionJumpsAndOpposingBones() {
        assertEquals(0,PreviewContinuity(listOf(PoseFrame(0,a),PoseFrame(2,a.map{it+Vec3(2f,0f,0f)})),30.0).count)
        assertEquals(0,PreviewContinuity(listOf(PoseFrame(0,a),PoseFrame(2,a.map{Vec3(-it.x,-it.y,it.z)})),30.0).count)
    }
}
