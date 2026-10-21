package com.posepocket.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posepocket.nvidia.DeviceVideoPipeline
import com.posepocket.media.*
import com.posepocket.pose.*
import com.posepocket.privacy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Offline-only A/B; video is staged with explicit authorization into app-private cache. */
@RunWith(AndroidJUnit4::class)
class OfflineEngineTest {
    private fun gate():ConsentGate {
        val store=object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }
        return ConsentGate(Policy("offline-ab","video","Local processing only"),store)
            .apply{accept(true,false)}
    }

    @Test fun twoOfflineEnginesGenerateSourceLabeledPoseClips():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"authorized-offline-test.mp4")
        assumeTrue("No authorized local test video staged",source.isFile)
        val gate=gate()
        assertFalse(gate.isRemoteAllowed())
        val capture=CaptureStore(context).create()
        source.copyTo(capture.file,true)
        try {
            val prepared=MediaPreparer(context,gate).prepare(capture.uri,"camera")
            try {
                val normalized=DeviceVideoPipeline(gate).normalizeVideo(prepared){}
                try {
                    val report=JSONObject().put("frames",normalized.frames)
                        .put("width",normalized.width).put("height",normalized.height)
                    for(engine in listOf(ProcessingEngine.ML_KIT,ProcessingEngine.MEDIAPIPE)) {
                        val clip=OfflinePoseGenerator(context,gate).infer(normalized,engine,
                            "camera",prepared.originalHash){}
                        try {
                            assertEquals(engine.name,clip.generator)
                            assertEquals(normalized.frames,clip.frameCount)
                            val poses=clip.frames.mapNotNull { it.joints }
                            val valid=poses.size
                            assertTrue("Insufficient local coverage: "+valid,valid>=normalized.frames/2)
                            poses.forEach { points ->
                                assertEquals("Offline is native Pose33, never Nova77",33,points.size)
                            }
                            clip.frames.filter { it.joints!=null }.forEach { frame ->
                                val valid=requireNotNull(frame.visibility)
                                val joints=requireNotNull(frame.joints)
                                assertEquals(33,valid.size)
                                assertTrue("A local frame needs core body landmarks",Pose33.hasBody(valid))
                                Pose33.edges.forEach { (a,b) ->
                                    if(valid[a]&&valid[b]) {
                                        val length=(joints[a]-joints[b]).norm()
                                        assertTrue("Invalid edge "+Pose33.names[a]+"-"+Pose33.names[b],
                                            length.isFinite()&&length<2.0f)
                                    }
                                }
                                assertTrue("Shoulders must be above the pelvis in Y-up convention",
                                    (joints[11].y+joints[12].y)/2f>(joints[23].y+joints[24].y)/2f)
                            }
                            val firstRaw=JSONObject(clip.raw.readLines().first())
                            assertEquals("pose-landmarks-33",firstRaw.getString("skeleton"))
                            assertEquals(33,firstRaw.getJSONArray("landmarks_33").length())
                            assertFalse(firstRaw.has("joint_rotations"))
                            assertFalse(firstRaw.has("keypoints_3d"))
                            val frameVisibility=firstRaw.getJSONArray("landmark_visibility_33")
                            assertEquals(33,frameVisibility.length())
                            if(engine==ProcessingEngine.MEDIAPIPE) {
                                // Real side-view videos can have very low visibility
                                // for a limb while still returning its world coordinates.
                                val confidentArms=clip.frames.count { frame ->
                                    val mask=frame.visibility
                                    mask!=null && listOf(11,13,15,12,14,16).all{mask[it]}
                                }
                                assertTrue("Both arms should have world estimates in sample",confidentArms>0)
                                report.put("mediapipe_frames_with_both_arm_positions",confidentArms)
                                val lowVisibilityRight=clip.raw.useLines { lines ->
                                    lines.count { line ->
                                        val data=JSONObject(line)
                                        val pts=data.getJSONArray("landmarks_33")
                                        val vis=data.getJSONArray("landmark_visibility_33")
                                        listOf(12,14,16).all{!pts.isNull(it)} &&
                                            listOf(12,14,16).any{
                                                !vis.isNull(it)&&vis.getDouble(it)<.30
                                            }
                                    }
                                }
                                report.put("mediapipe_right_arm_low_visibility_frames",lowVisibilityRight)
                            }
                            val perf=JSONObject(File(context.noBackupFilesDir,"local-pose-performance.json").readText())
                            report.put(engine.name,perf)
                        }finally{clip.raw.delete()}
                    }
                    File(context.cacheDir,"offline-engine-benchmark.json").writeText(report.toString(2))
                    android.util.Log.i("PocketPosePerf","offline_ab="+report.toString())
                }finally{normalized.file.delete()}
            }finally{prepared.file.delete()}
        }finally{capture.file.delete()}
    }
}
