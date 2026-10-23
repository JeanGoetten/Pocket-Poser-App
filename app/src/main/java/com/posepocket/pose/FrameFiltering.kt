package com.posepocket.pose

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
            pose.size == previous.size &&
            pose.indices.all { i ->
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

/**
 * Writes the compacted timeline in frame_id order, even if the original JSONL is unsorted.
 * source_frame_id identifies the original inference frame (and is preserved on a second export).
 * Keep the immutable original capture untouched. If validation, consent, or writing fails, neither
 * an existing destination nor a partially generated replacement is exposed to the share sheet.
 *
 * Index only byte offsets, never all pose records, to bound peak memory on a long clip.
 */
fun exportFrameSelection(clip: PoseClip, destination: File, checkAccess: () -> Unit = {}) {
    require(destination.canonicalFile != clip.raw.canonicalFile) {
        "Original pose data cannot be overwritten."
    }
    val selected = filteredFrameIds(clip.frames, clip.frameFilters)
    check(selected.isNotEmpty()) { "No frames available to export." }
    val wanted = selected.toHashSet()
    val positions = HashMap<Int, Long>(selected.size)
    val parent = requireNotNull(destination.absoluteFile.parentFile)
    require(parent.isDirectory) { "Export directory is unavailable." }

    val staged = File.createTempFile(".pose-export-", ".tmp", parent)
    try {
        RandomAccessFile(clip.raw, "r").use { input ->
            while (input.filePointer < input.length()) {
                checkAccess()
                val position = input.filePointer
                val source = JSONObject(requireNotNull(input.readUtf8Line())).getInt("frame_id")
                if (source in wanted) {
                    check(positions.put(source, position) == null) { "Duplicate source frame ID." }
                }
            }
            check(positions.size == selected.size) { "Source capture is missing selected frames." }
            FileOutputStream(staged).use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered()
                selected.forEachIndexed { index, source ->
                    checkAccess()
                    input.seek(requireNotNull(positions[source]))
                    val item = JSONObject(requireNotNull(input.readUtf8Line()))
                    check(item.getInt("frame_id") == source) { "Source frame changed during export." }
                    val originalId = if (item.has("source_frame_id")) item.getInt("source_frame_id") else source
                    item.put("source_frame_id", originalId)
                    item.put("frame_id", index)
                    writer.write(item.toString())
                    writer.newLine()
                }
                writer.flush()
                output.fd.sync()
            }
        }
        checkAccess()
        // If atomic replacement is unavailable, fail without touching the existing destination.
        Files.move(staged.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staged.delete()
    }
}

/** RandomAccessFile.readLine decodes bytes as ISO-8859-1. Reassemble the UTF-8 bytes so accents
 * and other non-ASCII JSON metadata survive an ordered export without loading the entire file.
 */
private fun RandomAccessFile.readUtf8Line(): String? =
    readLine()?.toByteArray(Charsets.ISO_8859_1)?.toString(Charsets.UTF_8)
