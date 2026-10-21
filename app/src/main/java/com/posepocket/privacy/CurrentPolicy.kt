package com.posepocket.privacy

import android.content.Context
import com.posepocket.R

object CurrentPolicy {
    const val VERSION="dev-2026-10-20-v8-local-engines-optional-remote"
    const val SCOPE="video-pose-local-and-optional-nvidia-v1"
    fun create(context:Context)=Policy(VERSION,SCOPE,
        context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() })
}
