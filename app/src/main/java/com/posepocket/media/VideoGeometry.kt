package com.posepocket.media

import kotlin.math.*

/** Fit without stretching; padding keeps HOG's 64x128 window valid even after its 640px resize. */
data class VideoGeometry(val width:Int,val height:Int,val contentWidth:Int,val contentHeight:Int) {
    val left get()=(width-contentWidth)/2
    val top get()=(height-contentHeight)/2
    companion object {
        fun fit(sourceWidth:Int,sourceHeight:Int):VideoGeometry {
            require(sourceWidth>=2&&sourceHeight>=2)
            val scale=min(1.0,640.0/max(sourceWidth,sourceHeight))
            val w=(sourceWidth*scale).roundToInt().coerceIn(2,640)
            val h=(sourceHeight*scale).roundToInt().coerceIn(2,640)
            fun aligned(value:Int,minimum:Int)=max(minimum,((value+15)/16)*16)
            return VideoGeometry(aligned(w,64),aligned(h,128),w,h)
        }
        fun detectorCompatible(width:Int,height:Int):Boolean {
            val scale=min(1.0,640.0/max(width,height))
            return (width*scale).roundToInt()>=64&&(height*scale).roundToInt()>=128
        }
    }
}

/** Select only actual presentation timestamps inside the confirmed half-open interval. */
class TrimmedTimeline(timestamps:List<Long>,val range:TrimRange) {
    private val samples=timestamps.filter { it>=range.startUs&&it<range.endUs }.sorted()
    val frames=ceil((range.endUs-range.startUs)*30.0/1_000_000).toInt()
    init { require(samples.isNotEmpty()&&frames in 2..18000) { "O intervalo precisa conter pelo menos dois quadros de saída." } }
    fun timestamp(frame:Int):Long {
        require(frame in 0 until frames)
        val time=range.startUs+frame*1_000_000L/30
        val found=samples.binarySearch(time)
        if(found>=0)return samples[found]
        val after=-found-1
        if(after==0)return samples.first()
        if(after==samples.size)return samples.last()
        return if(time-samples[after-1]<=samples[after]-time)samples[after-1] else samples[after]
    }
}
