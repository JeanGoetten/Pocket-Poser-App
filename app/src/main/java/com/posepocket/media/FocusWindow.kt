package com.posepocket.media

import kotlin.math.ceil
import kotlin.math.floor

/** Normalized visible region of the source video; null means the full frame. */
data class FocusWindow(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init {
        require(listOf(left,top,right,bottom).all { it.isFinite() })
        require(left>=0f&&top>=0f&&right<=1f&&bottom<=1f)
        require(right-left>=MIN_SIZE-EPSILON&&bottom-top>=MIN_SIZE-EPSILON)
    }
    /** Shared gesture operation; Float roundoff at the minimum is accepted by the model. */
    fun drag(anchor:Int,dx:Float,dy:Float):FocusWindow {
        require(dx.isFinite()&&dy.isFinite())
        val width=right-left;val height=bottom-top
        if(anchor==4) {
            val x=(left+dx).coerceIn(0f,(1f-width).coerceAtLeast(0f))
            val y=(top+dy).coerceIn(0f,(1f-height).coerceAtLeast(0f))
            return FocusWindow(x,y,(x+width).coerceAtMost(1f),(y+height).coerceAtMost(1f))
        }
        return FocusWindow(
            if(anchor==0||anchor==2)(left+dx).coerceIn(0f,(right-MIN_SIZE).coerceAtLeast(0f))else left,
            if(anchor==0||anchor==1)(top+dy).coerceIn(0f,(bottom-MIN_SIZE).coerceAtLeast(0f))else top,
            if(anchor==1||anchor==3)(right+dx).coerceIn((left+MIN_SIZE).coerceAtMost(1f),1f)else right,
            if(anchor==2||anchor==3)(bottom+dy).coerceIn((top+MIN_SIZE).coerceAtMost(1f),1f)else bottom)
    }
    companion object {
        const val MIN_SIZE=.2f
        private const val EPSILON=.000001f
    }
    fun pixelBounds(width:Int,height:Int):IntArray {
        require(width>=2&&height>=2)
        val x=floor(left*width).toInt().coerceIn(0,width-2)
        val y=floor(top*height).toInt().coerceIn(0,height-2)
        val rightPixel=ceil(right*width).toInt().coerceIn(x+2,width)
        val bottomPixel=ceil(bottom*height).toInt().coerceIn(y+2,height)
        return intArrayOf(x,y,rightPixel-x,bottomPixel-y)
    }
}
