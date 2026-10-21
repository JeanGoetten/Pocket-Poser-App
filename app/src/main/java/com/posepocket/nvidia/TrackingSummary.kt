package com.posepocket.nvidia

data class TrackingSummary(val frames:Int,val covered:Int,val longestGap:Int) {
    val percent get()=covered*100/frames
    companion object {
        fun from(video:DeviceVideo):TrackingSummary {
            val present=video.boxes.map { it.frame }.toHashSet()
            var run=0;var longest=0
            repeat(video.frames) { if(it in present)run=0 else {run++;longest=maxOf(longest,run)} }
            return TrackingSummary(video.frames,present.size,longest)
        }
    }
}

/** Local tracking finished; request an explicit decision before sending a discontinuous result. */
class TrackingReviewRequired:IllegalStateException()
