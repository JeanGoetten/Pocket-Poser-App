package com.posepocket.ui

import androidx.compose.runtime.saveable.listSaver
import com.posepocket.pose.ViewCamera

/** View-only state; never changes the inferred poses. */
internal val ViewCameraSaver = listSaver<ViewCamera, Float>(
    save = { listOf(it.yaw, it.pitch, it.zoom, it.panX, it.panY) },
    restore = { ViewCamera(it[0], it[1], it[2], it[3], it[4]) },
)
