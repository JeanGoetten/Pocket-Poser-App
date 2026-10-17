package com.posepocket.nvidia

import org.junit.Test
import org.junit.Assert.*

class SinglePersonTrackTest {
    private fun box(frame:Int,x:Float=10f)=PersonBox(frame,x,10f,80f,200f)
    @Test fun followsOverlappingBodyAndAllowsShortGap() {
        val t=SinglePersonTrack();assertNotNull(t.accept(listOf(box(0))))
        repeat(3){assertNull(t.accept(emptyList()))}
        assertEquals(4,t.accept(listOf(box(4,20f)))!!.frame)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMultipleBodies(){SinglePersonTrack().accept(listOf(box(0),box(0,200f)))}
    @Test(expected=IllegalArgumentException::class) fun rejectsIdentityJump(){val t=SinglePersonTrack();t.accept(listOf(box(0)));t.accept(listOf(box(1,400f)))}
    @Test(expected=IllegalArgumentException::class) fun rejectsLongOcclusion(){val t=SinglePersonTrack();t.accept(listOf(box(0)));repeat(16){t.accept(emptyList())};t.accept(listOf(box(17)))}
    @Test fun acceptsMotionConfirmedByCurrentFrameTemplateButNotAStalePrediction() {
        val tracker=SinglePersonTrack();tracker.accept(listOf(box(0)))
        repeat(8){tracker.accept(emptyList())}
        assertNotNull(tracker.accept(listOf(box(9,140f)),box(9,138f)))
        assertThrows(IllegalArgumentException::class.java){tracker.accept(listOf(box(10,400f)),box(9,400f))}
    }
    @Test fun detectorBoundsScaleOnceAndRespectRoundedResizeDimensions() {
        val edge=PersonBox(7,0f,0f,356f,640f).inVideoCoordinates(356,640,400,720)
        assertEquals(400f,edge.width,.001f);assertEquals(720f,edge.height,.001f)
        val padded=PersonTemplateMotion.padded(PersonBox(7,280f,450f,70f,180f),356,640)
            .inVideoCoordinates(356,640,400,720)
        assertTrue(padded.x>=0&&padded.y>=0)
        assertTrue(padded.x+padded.width<=400.001f&&padded.y+padded.height<=720.001f)
        assertTrue(padded.width>0&&padded.height>0)
    }
}
