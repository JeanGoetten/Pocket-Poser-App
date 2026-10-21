package com.posepocket.nvidia

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import com.posepocket.pose.Vec3

/**
 * Experimental on-device alternative to HOG. 33 pose landmarks are used ONLY to
 * derive a person box; Nova-77 inference remains NVIDIA's responsibility.
 * No image/frame/landmark is transmitted or logged here.
 */
class MlKitPosePersonDetector : AutoCloseable {
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    suspend fun detectLandmarks(bitmap:Bitmap,frame:Int):LocalLandmarkFrame?=withContext(Dispatchers.IO) {
        val pose=Tasks.await(detector.process(InputImage.fromBitmap(bitmap,0)))
        val points=(0..32).map { index ->
            val point=pose.getPoseLandmark(index)
            if(point==null || point.inFrameLikelihood<.3f ||
                !point.position.x.isFinite() || !point.position.y.isFinite())null
            else {
                val xyz=point.position3D
                val scale=1.7f/bitmap.height
                Vec3((point.position.x-bitmap.width/2f)*scale,
                    (bitmap.height/2f-point.position.y)*scale,
                    if(xyz.z.isFinite())xyz.z*scale else 0f)
            }
        }
        val scores=(0..32).map { index ->
            pose.getPoseLandmark(index)?.inFrameLikelihood?.takeIf { it.isFinite() }
                ?.coerceIn(0f,1f)
        }
        val framePoints=LocalLandmarkFrame(points,metric=false,visibilityScores=scores)
        framePoints.takeIf { it.usable }
    }

    suspend fun detect(bitmap:Bitmap,frame:Int):PersonBox? {
        val landmarks=detectLandmarks(bitmap,frame)?:return null
        // Undo the metric-scale approximation for the 2D bounding box.
        val scale=bitmap.height/1.7f
        val values=landmarks.points.filterNotNull().map {
            Pair(it.x*scale+bitmap.width/2f,bitmap.height/2f-it.y*scale)
        }.filter { (x,y)-> x in 0f..bitmap.width.toFloat() && y in 0f..bitmap.height.toFloat() }
        if(values.size<6)return null
        val minX=values.minOf { it.first };val maxX=values.maxOf { it.first }
        val minY=values.minOf { it.second };val maxY=values.maxOf { it.second }
        val padX=max(6f,(maxX-minX)*.16f);val padY=max(6f,(maxY-minY)*.12f)
        val x1=max(0f,minX-padX);val y1=max(0f,minY-padY)
        val x2=min(bitmap.width.toFloat(),maxX+padX)
        val y2=min(bitmap.height.toFloat(),maxY+padY)
        return if(x2-x1<16f || y2-y1<32f)null
            else PersonBox(frame,x1,y1,x2-x1,y2-y1)
    }

    override fun close() {
        detector.close()
    }
}
