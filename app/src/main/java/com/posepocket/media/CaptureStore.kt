package com.posepocket.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Private cache area that receives videos recorded by the system camera app (D-15, #39/#40).
 *
 * The camera app writes to a `content://` URI served by [FileProvider] restricted to `cache/captures`.
 * Files never reach the gallery. A capture is deleted after use, on cancel, or when it becomes stale;
 * it is deliberately NOT wiped on process start, because the process can die while the camera is open.
 */
class CaptureStore(
    private val context: Context,
    /** Overridable so JVM tests do not depend on FileProvider root resolution (host-OS specific). */
    private val uriOf: (File) -> Uri = { FileProvider.getUriForFile(context, "${context.packageName}.captures", it) },
) {
    data class Pending(val file: File, val uri: Uri) { val name: String get() = file.name }

    val authority: String get() = "${context.packageName}.captures"
    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    fun create(): Pending {
        val folder = directory.apply { mkdirs() }
        val file = File(folder, "${UUID.randomUUID()}.mp4")
        return Pending(file, uriOf(file))
    }

    /** Resolves a name previously returned by [Pending.name]; rejects anything that is not one of ours. */
    fun fileNamed(name: String): File? =
        if (NAME.matches(name)) File(directory, name) else null

    fun uriFor(file: File): Uri = uriOf(file)

    fun owns(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == authority

    fun discard(file: File?) { if (file != null && file.parentFile?.canonicalPath == directory.canonicalPath) file.delete() }

    /** Deletes the file behind [uri] only when the URI belongs to this store. Safe for foreign URIs. */
    fun discard(uri: Uri) {
        if (!owns(uri)) return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
        discard(fileNamed(name))
    }

    /** Removes captures older than [maxAgeMillis] (interrupted or abandoned recordings). */
    fun purgeStale(maxAgeMillis: Long = STALE_AFTER_MILLIS, now: Long = System.currentTimeMillis()) {
        directory.listFiles().orEmpty().forEach { if (now - it.lastModified() > maxAgeMillis) it.delete() }
    }

    fun cameraAppAvailable(packageManager: PackageManager): Boolean =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE).resolveActivity(packageManager) != null

    companion object {
        const val DIRECTORY = "captures"
        const val MAX_BYTES = 50L * 1024 * 1024
        const val MAX_SECONDS = 600
        const val STALE_AFTER_MILLIS = 6L * 60 * 60 * 1000
        private val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4")
    }
}
