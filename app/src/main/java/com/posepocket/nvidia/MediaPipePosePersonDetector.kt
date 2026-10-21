package com.posepocket.nvidia

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.posepocket.pose.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CPU VIDEO mode: strictly increasing timestamps and model asset shipped in APK.
 * World points are metric estimates, not calibrated ground-truth world positions.
 */
class MediaPipePosePersonDetector(context: Context) : AutoCloseable {
    private val detector=PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(.35f)
            .setMinPosePresenceConfidence(.35f)
            .setMinTrackingConfidence(.35f)
            .build())
    suspend fun detect(bitmap:Bitmap,frame:Int):LocalLandmarkFrame?=withContext(Dispatchers.Default) {
        val argb=if(bitmap.config==Bitmap.Config.ARGB_8888)bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888,false)
        try {
            val result=detector.detectForVideo(BitmapImageBuilder(argb).build(),frame*1000L/30)
            val image=result.landmarks().firstOrNull()
            val world=result.worldLandmarks().firstOrNull()
            if(image==null||world==null||image.size!=33||world.size!=33)return@withContext null
            // MediaPipe predicts world coordinates even for occluded limbs.
            // 'visibility' measures whether the point is visible in the VIDEO,
            // not whether a 3D estimate exists. Filtering at 0.3 erased
            // the entire far arm on side-view footage.
            val joints=(0..32).map { i ->
                val p=world[i]
                if (!p.x().isFinite()||!p.y().isFinite()||!p.z().isFinite())null
                else Vec3(p.x(),-p.y(),-p.z())
            }
            val scores=(0..32).map { i ->
                image[i].visibility().orElse(0f).takeIf { it.isFinite() }
                    ?.coerceIn(0f,1f)
            }
            LocalLandmarkFrame(joints,metric=true,visibilityScores=scores)
        }finally{if(argb!==bitmap)argb.recycle()}
    }
    override fun close(){detector.close()}
}
