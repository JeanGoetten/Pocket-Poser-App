package com.posepocket.pose

/**
 * Visual-only timeline. Missing frame IDs in the provider response must never be
 * fabricated in the raw JSONL or exported as NVIDIA measurements.
 */
data class PreviewTimeline(
    val frames: List<List<Vec3>?>,
    val missingFrameIds: Set<Int>,
    val undetectedFrameIds: Set<Int>,
    val interpolatedFrameIds: Set<Int>,
) {
    fun jointsAt(frame: Int, interpolate: Boolean): List<Vec3>? {
        if (frame !in frames.indices || (!interpolate && frame in interpolatedFrameIds)) return null
        return frames[frame]
    }
}

/**
 * Interpolate only short gaps between two valid provider poses and only if the
 * provider supplied no record for those IDs. Explicit no-detection records
 * remain empty. No changes to the source clip or raw JSONL.
 */
fun buildPreviewTimeline(clip: PoseClip, maxGap: Int = 4): PreviewTimeline {
    require(maxGap in 0..30)
    val byId = clip.frames.associateBy { it.id }
    val missing = (0 until clip.frameCount).filterTo(mutableSetOf()) { it !in byId }
    val undetected = clip.frames.filter { it.joints == null }.mapTo(mutableSetOf()) { it.id }
    val display = MutableList<List<Vec3>?>(clip.frameCount) { byId[it]?.joints }
    val generated = mutableSetOf<Int>()
    val detected = clip.frames.filter { it.joints != null }.sortedBy { it.id }
    for (index in 0 until detected.lastIndex) {
        val before = detected[index]
        val after = detected[index + 1]
        val gap = after.id - before.id - 1
        if (gap !in 1..maxGap) continue
        if(clip.generator!="NVIDIA")continue
        val first = requireNotNull(before.joints)
        val last = requireNotNull(after.joints)
        if (first.size != last.size) continue
        for (frame in before.id + 1 until after.id) {
            if (frame !in missing) continue
            val t = (frame - before.id).toFloat() / (after.id - before.id)
            display[frame] = first.zip(last).map { (a, b) -> a * (1f - t) + b * t }
            generated += frame
        }
    }
    return PreviewTimeline(display, missing, undetected, generated)
}
