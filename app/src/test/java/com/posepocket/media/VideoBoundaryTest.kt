package com.posepocket.media

import org.junit.Assert.*
import org.junit.Test

class VideoBoundaryTest {
    @Test fun focusSurvivesMinimumAtEveryCornerAndRepeatedMove() {
        for(anchor in 0..3) {
            var focus=FocusWindow(.2f,.05f,.8f,.95f)
            repeat(1000) {
                focus=focus.drag(anchor,if(anchor%2==0)1f else -1f,if(anchor<2)1f else -1f)
                focus=focus.drag(4,1f,-1f).drag(4,-1f,1f)
                assertTrue(focus.right-focus.left>=.2f-.000001f)
                assertTrue(focus.bottom-focus.top>=.2f-.000001f)
            }
        }
        assertNotNull(FocusWindow(.8f-.2f,.05f,.8f,.95f))
        assertThrows(IllegalArgumentException::class.java){FocusWindow(.601f,0f,.8f,1f)}
    }
    @Test fun narrowAndWideCropsFitDetectorWithoutStretching() {
        for((w,h) in listOf(720 to 128,64 to 720,4096 to 32,32 to 4096,1920 to 1080)) {
            val geometry=VideoGeometry.fit(w,h)
            assertTrue(VideoGeometry.detectorCompatible(geometry.width,geometry.height))
            assertEquals(0,geometry.width%16);assertEquals(0,geometry.height%16)
            assertTrue(geometry.width<=640&&geometry.height<=640)
            assertEquals(w.toDouble()/h,geometry.contentWidth.toDouble()/geometry.contentHeight,.04)
        }
        assertFalse(VideoGeometry.detectorCompatible(720,128))
        assertFalse(VideoGeometry.detectorCompatible(64,720))
    }
    @Test fun reviewDurationRoundingAtEndIsClampedWithinOneFrame() {
        val actualDurationUs=3_000_000L
        val selection=TrimRange(1_500_000L,3_008_000L)
        assertEquals(TrimRange(1_500_000L,actualDurationUs),
            validatedTrimAgainstTrackDuration(selection,actualDurationUs))
        assertEquals(TrimRange(200_000L,1_400_000L),
            validatedTrimAgainstTrackDuration(TrimRange(200_000L,1_400_000L),actualDurationUs))
    }

    @Test fun outOfBoundsRangeRemainsInvalidEvenWithEndRoundingTolerance() {
        val actualDurationUs=3_000_000L
        assertThrows(IllegalArgumentException::class.java) {
            validatedTrimAgainstTrackDuration(TrimRange(1_000_000L,3_100_000L),actualDurationUs)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedTrimAgainstTrackDuration(TrimRange(3_000_000L,3_010_000L),actualDurationUs)
        }
    }

    @Test fun confirmedIntervalNeverSelectsPrerollOrExclusiveEnd() {
        val timestamps=(0L..120L).map { it*1_000_000/60 }.reversed()
        val range=TrimRange(515_000,1_255_000)
        val timeline=TrimmedTimeline(timestamps,range)
        assertEquals(23,timeline.frames)
        val selected=(0 until timeline.frames).map(timeline::timestamp)
        assertTrue(selected.all { it>=range.startUs&&it<range.endUs&&it in timestamps })
        assertEquals(selected.sorted(),selected)
        assertEquals(516_666L,selected.first())
    }
    @Test fun emptyIntervalFailsInsteadOfSendingAnotherFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimmedTimeline(listOf(0,1_000_000),TrimRange(100_000,900_000))
        }
    }
}
