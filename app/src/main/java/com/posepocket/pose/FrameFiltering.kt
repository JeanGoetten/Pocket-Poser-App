package com.posepocket.pose

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Non-destructive timeline and export options. Frame IDs always refer to source frames. */
data class FrameFilters(
    val hideEmpty: Boolean = false,
    val removeDuplicates: Boolean = false,
)

/** A duplicate is a consecutive rendered pose with all 77 coordinates unchanged (within 0.01 mm).
 * Empty frames separate runs unless hidden. Interpolated preview poses are never treated as raw data.
 */
fun filteredFrameIds(frames: List<PoseFrame>, filters: FrameFilters): List<Int> {
    val selected = ArrayList<Int>(frames.size)
    var previous: List<Vec3>? = null
    for (frame in frames.sortedBy { it.id }) {
        val pose = frame.joints
        if (pose == null) {
            if (!filters.hideEmpty) {
                selected.add(frame.id)
                previous = null
            }
            continue
        }
        val duplicate = filters.removeDuplicates && previous != null &&
            pose.size == previous.size && pose.indices.all { i ->
                val a = pose[i]; val b = previous[i]
                abs(a.x - b.x) <= 0.00001f &&
                abs(a.y - b.y) <= 0.00001f &&
                abs(a.z - b.z) <= 0.00001f
            }
        if (!duplicate) {
            selected.add(frame.id)
            previous = pose
        }
    }
    return selected
}

/** Exports the selected frames in compact, consecutive frame_id order; source_frame_id
 * retains correspondence with the immutable inference. Raw JSONL is never modified.
 * The caller owns the destination file and must delete partial output if an error occurs.
 */
fun exportFrameSelection(clip: PoseClip, destination: File, checkAccess: () -> Unit = {}) {
    require(destination.canonicalFile != clip.raw.canonicalFile) { "Original pose data cannot be overwritten." }
    val selected = filteredFrameIds(clip.frames, clip.frameFilters)
    check(selected.isNotEmpty()) { "No frames available to export." }
    val index = selected.withIndex().associate { it.value to it.index }
    val seen = HashSet<Int>()
    destination.bufferedWriter().use { writer ->
        clip.raw.forEachLine { line ->
            checkAccess()
            val item = JSONObject(line)
            val source = item.getInt("frame_id")
            val next = index[source] ?: return@forEachLine
            check(seen.add(source)) { "Duplicate source frame ID." }
            item.put("frame_id", next)
            item.put("source_frame_id", source)
            writer.write(item.toString())
            writer.newLine()
        }
    }
    check(seen.size == selected.size) { "Source capture is missing selected frames." }
}
