package com.posepocket.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecordingOrientationTest {
    @Test fun keepsCurrentOrientationAndRestoresPreviousPolicyAfterRecording() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        try {
            activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            val recording=RecordingOrientationLease(activity)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED,activity.requestedOrientation)
            recording.close();recording.close()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,activity.requestedOrientation)
        }finally{controller.pause().stop().destroy()}
    }
}
