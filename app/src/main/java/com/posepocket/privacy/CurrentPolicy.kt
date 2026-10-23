package com.posepocket.privacy

import android.content.Context
import com.posepocket.R

object CurrentPolicy {
    const val VERSION="dev-2026-10-23-v9-nvidia-only"
    const val SCOPE="video-pose-nvidia-remote-v2"
    fun create(context:Context)=Policy(VERSION,SCOPE,
        context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() })
}
