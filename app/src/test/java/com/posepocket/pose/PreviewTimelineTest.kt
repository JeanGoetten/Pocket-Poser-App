package com.posepocket.pose

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreviewTimelineTest {
    private fun clip(frames: List<PoseFrame>, count: Int) = PoseClip(
        File("not-read.jsonl"), frames, 30, 1, count, false, "attachment", null
    )

    @Test fun shortMissingGapCanBeInterpolatedForPreviewOnly() {
        val zero = List(77) { Vec3(0f, 0f, 0f) }
        val two = List(77) { Vec3(2f, 2f, 2f) }
        val original = clip(listOf(PoseFrame(0, zero), PoseFrame(2, two)), 3)
        val timeline = buildPreviewTimeline(original)
        assertEquals(setOf(1), timeline.missingFrameIds)
        assertEquals(setOf(1), timeline.interpolatedFrameIds)
        assertNull(timeline.jointsAt(1, false))
        assertEquals(1f, timeline.jointsAt(1, true)!![0].x, 0.0001f)
        assertEquals(listOf(0, 2), original.frames.map { it.id })
    }

    @Test fun explicitNoDetectionIsNeverInterpolated() {
        val pose = List(77) { Vec3(1f, 0f, 0f) }
        val timeline = buildPreviewTimeline(clip(listOf(PoseFrame(0, pose), PoseFrame(1, null), PoseFrame(2, pose)), 3))
        assertTrue(timeline.interpolatedFrameIds.isEmpty())
        assertEquals(setOf(1), timeline.undetectedFrameIds)
        assertNull(timeline.jointsAt(1, true))
    }

    @Test fun longGapsStayVisibleAndNotInferred() {
        val pose = List(77) { Vec3(1f, 0f, 0f) }
        val timeline = buildPreviewTimeline(clip(listOf(PoseFrame(0, pose), PoseFrame(12, pose)), 13))
        assertEquals(11, timeline.missingFrameIds.size)
        assertTrue(timeline.interpolatedFrameIds.isEmpty())
        assertNull(timeline.jointsAt(4, true))
    }
}
