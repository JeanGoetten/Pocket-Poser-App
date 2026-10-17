package com.posepocket.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Keep the current phone orientation only while recording; restore user policy
 * on finalization, cancellation or leaving the camera. Large screens stay free. */
class RecordingOrientationLease(private val activity:Activity):AutoCloseable {
    private val previous=activity.requestedOrientation
    init{activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LOCKED}
    override fun close() {
        if(activity.requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_LOCKED)activity.requestedOrientation=previous
    }
}

@Composable
fun RecordingOrientation(active:Boolean) {
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    val activity=remember(context) {
        generateSequence(context){current -> (current as? ContextWrapper)?.baseContext?.takeIf{it!==current}}
            .filterIsInstance<Activity>().firstOrNull()
    }
    val compact=configuration.smallestScreenWidthDp<600
    DisposableEffect(activity,active,compact) {
        val lease=if(active&&compact&&activity!=null)RecordingOrientationLease(activity)else null
        onDispose{lease?.close()}
    }
}
