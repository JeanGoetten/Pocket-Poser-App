package com.posepocket.media

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * Opens the system camera app to record a video into [input] (a [CaptureStore] URI).
 * Returns true only for RESULT_OK; the caller must still validate the file, because camera apps
 * may ignore the duration/size limits below.
 */
class RecordVideo : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, CaptureStore.MAX_SECONDS)
            .putExtra(MediaStore.EXTRA_SIZE_LIMIT, CaptureStore.MAX_BYTES)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("capture", input) }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}
