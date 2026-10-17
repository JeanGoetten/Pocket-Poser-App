package com.posepocket.media

import kotlin.math.ceil
import kotlin.math.floor

/** Normalized visible region of the source video; null means the full frame. */
data class FocusWindow(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init {
        require(listOf(left,top,right,bottom).all { it.isFinite() })
        require(left>=0f&&top>=0f&&right<=1f&&bottom<=1f)
        require(right-left>=.2f&&bottom-top>=.2f)
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
