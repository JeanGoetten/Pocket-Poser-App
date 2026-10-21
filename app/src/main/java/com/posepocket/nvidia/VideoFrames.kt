package com.posepocket.nvidia

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build

/** Decode the validated CFR file in small batches, preserving exact frame indices.
 * At 720x720, eight ARGB frames occupy at most about 16 MB. Never retain a batch
 * after its consumer finishes, including on cancellation or a detection error. */
object VideoFrames {
    suspend fun forEach(
        retriever:MediaMetadataRetriever,
        count:Int,
        checkActive:suspend ()->Unit,
        maxBatch:Int=8,
        consume:suspend (Int,Bitmap)->Unit,
    ) {
        require(count in 2..18000)
        require(maxBatch in 1..8)
        // Match this device's time-based decoder, rather than assuming the Samsung's RGB_565.
        val params=if(Build.VERSION.SDK_INT>=28) {
            checkActive()
            val probe=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST)
            val config=if(probe?.config==Bitmap.Config.RGB_565)Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            probe?.recycle()
            MediaMetadataRetriever.BitmapParams().apply { setPreferredConfig(config) }
        }else null
        var first=0
        while(first<count) {
            checkActive()
            val size=minOf(maxBatch,count-first)
            if(Build.VERSION.SDK_INT>=28) {
                val batch=retriever.getFramesAtIndex(first,size,requireNotNull(params))
                try {
                    require(batch.size==size) { "Decodificação incompleta do vídeo normalizado." }
                    batch.forEachIndexed { offset,bitmap ->
                        checkActive()
                        consume(first+offset,bitmap)
                    }
                }finally{batch.forEach { it.recycle() }}
            }else {
                // Android 8.0/8.1 do not expose indexed batch decoding.
                repeat(size) { offset ->
                    checkActive()
                    val index=first+offset
                    val bitmap=retriever.getFrameAtTime(index*1_000_000L/30,MediaMetadataRetriever.OPTION_CLOSEST)
                        ?:error("Falha ao ler o vídeo normalizado.")
                    try { consume(index,bitmap) }finally{bitmap.recycle()}
                }
            }
            first+=size
        }
    }
}
