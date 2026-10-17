package com.posepocket

import android.net.Uri

/** A video the person picked or recorded and has not sent yet. Nothing leaves the device from this state. */
data class ReviewVideo(val uri:Uri,val source:String,val startMs:Long=0,val endMs:Long?=null,val focus:com.posepocket.media.FocusWindow?=null)
