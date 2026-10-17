package com.posepocket.nvidia

import org.junit.Assert.*
import org.junit.Test

class ConfirmedTrackGapsTest {
    private fun box(frame:Int,x:Float=30f)=PersonBox(frame,x,20f,80f,180f)
    @Test fun publishesPredictionsOnlyAfterMatchingDetectorAnchor() {
        val track=ConfirmedTrackGaps()
        assertEquals(listOf(0),track.accept(box(0),null).map{it.frame})
        assertTrue(track.accept(null,box(1,31f)).isEmpty())
        assertTrue(track.accept(null,box(2,32f)).isEmpty())
        assertEquals(listOf(1,2,3),track.accept(box(3,33f),box(3,33f)).map{it.frame})
    }
    @Test fun neverPublishesUnconfirmedTrailingFramesOrInitialGaps() {
        val track=ConfirmedTrackGaps()
        assertTrue(track.accept(null,null).isEmpty())
        track.accept(box(1),null)
        assertTrue(track.accept(null,box(2)).isEmpty())
    }
    @Test fun rejectsAppearanceFailureAndMismatchingAnchor() {
        val track=ConfirmedTrackGaps()
        track.accept(box(0),null);track.accept(null,box(1))
        assertEquals(listOf(2),track.accept(box(2,300f),box(2)).map{it.frame})
        track.accept(null,box(3,300f));track.accept(null,null)
        assertEquals(listOf(5),track.accept(box(5,300f),null).map{it.frame})
    }
    @Test fun capsTrackingAtTwelveFrames() {
        val track=ConfirmedTrackGaps()
        track.accept(box(0),null)
        for(i in 1..13)assertTrue(track.accept(null,box(i)).isEmpty())
        assertNull(track.last)
        assertEquals(listOf(14),track.accept(box(14),null).map{it.frame})
    }
}
