package com.posepocket.pose

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Debug
import android.os.SystemClock
import com.posepocket.nvidia.*
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Offline inference preserves the actual 33 landmarks, including missing markers.
 * No Nova-77 synthetic joints, neutral quaternions, or invented rig hierarchy.
 */
class OfflinePoseGenerator(private val context:Context,private val gate:ConsentGate) {
    /** Retains every frame ID even when no person is detected; never interpolates silent losses. */
    suspend fun infer(video:NormalizedVideo,engine:ProcessingEngine,source:String,
        originalHash:String?,onPhase:(String)->Unit):PoseClip {
        require(engine.offline)
        val output=File(context.cacheDir,"results/"+UUID.randomUUID()+".jsonl")
        output.parentFile?.mkdirs()
        val started=SystemClock.elapsedRealtime()
        val initStart=SystemClock.elapsedRealtime()
        val mlkit=if(engine==ProcessingEngine.ML_KIT)MlKitPosePersonDetector()else null
        val mediapipe=if(engine==ProcessingEngine.MEDIAPIPE)MediaPipePosePersonDetector(context)else null
        val initializationMs=SystemClock.elapsedRealtime()-initStart
        val reader=MediaMetadataRetriever()
        var poseFrames=0
        var inferNs=0L
        var peakNative=Debug.getNativeHeapAllocatedSize()
        try {
            reader.setDataSource(video.file.absolutePath)
            output.bufferedWriter().use { out ->
                VideoFrames.forEach(reader,video.frames,{
                    currentCoroutineContext().ensureActive();gate.withConsent{}
                }) { frame,bitmap ->
                    val begin=SystemClock.elapsedRealtimeNanos()
                    val detected=when(engine) {
                        ProcessingEngine.ML_KIT -> mlkit!!.detectLandmarks(bitmap,frame)
                        ProcessingEngine.MEDIAPIPE -> mediapipe!!.detect(bitmap,frame)
                        ProcessingEngine.NVIDIA -> error("Remote processing must use NVIDIA.")
                    }
                    inferNs+=SystemClock.elapsedRealtimeNanos()-begin
                    val usable=detected?.usable==true
                    if(usable)poseFrames++
                    // A null entry means the detector had insufficient evidence.
                    // Preserve partial observations even when the frame isn't usable.
                    val original33=JSONArray((detected?.points?:List(33){null}).map { point ->
                        if(point==null)JSONObject.NULL else listOf(point.x,point.y,point.z)
                    })
                    out.write(JSONObject().put("schema_version",2)
                        .put("frame_id",frame)
                        .put("skeleton","pose-landmarks-33")
                        .put("landmarks_33",original33)
                        .put("landmark_visibility_33",
                            JSONArray((detected?.visibilityScores?:List(33){null}).map {
                                score->score?:JSONObject.NULL
                            }))
                        .put("has_pose",usable)
                        .put("source_coordinate_space",
                            if(detected?.metric==true)"estimated_body_meters" else "normalized_image_height_approx")
                        .put("provenance","local-"+engine.name.lowercase()+"-landmarks-33")
                        .put("rig_rotations_calibrated",false).toString())
                    out.newLine()
                    peakNative=max(peakNative,Debug.getNativeHeapAllocatedSize())
                    if(frame%5==0||frame==video.frames-1)
                        onPhase("Estimando pose local: "+((frame+1)*100/video.frames)+"%")
                }
            }
            require(poseFrames>0) { "Nenhuma pose corporal foi encontrada no vídeo." }
            val clip=readClip(output,30,1,video.frames,false,source,video.hash)
                .copy(sourceHash=originalHash, generator=engine.name)
            val elapsedMs=SystemClock.elapsedRealtime()-started
            val metrics=JSONObject().put("engine",engine.name).put("frame_count",video.frames)
                .put("frames_with_pose",poseFrames)
                .put("coverage_percent",poseFrames*100/video.frames)
                .put("model_initialization_ms",initializationMs)
                .put("total_ms",elapsedMs)
                .put("inference_ms",inferNs/1_000_000L)
                .put("peak_native_heap_bytes",peakNative)
                .put("normalization_included",false)
                .put("3d_rotation_status","uncalibrated")
            val target=File(context.noBackupFilesDir,"local-pose-performance.json")
            target.writeText(metrics.toString(2))
            android.util.Log.i("PocketPosePerf",
                "engine="+engine+" frames="+video.frames+" withPose="+poseFrames+
                " inferenceMs="+(inferNs/1_000_000L)+" totalMs="+elapsedMs)
            return clip
        }catch(error:Throwable){output.delete();throw error}
        finally{reader.release();mlkit?.close();mediapipe?.close()}
    }

}
