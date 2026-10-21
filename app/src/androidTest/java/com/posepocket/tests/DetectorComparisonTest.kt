package com.posepocket.tests

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posepocket.nvidia.*
import com.posepocket.media.*
import com.posepocket.privacy.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in authorized video test; no NVIDIA upload and no frames added to Git. */
@RunWith(AndroidJUnit4::class)
class DetectorComparisonTest {
    private fun localGate():ConsentGate {
        val store=object:ConsentStore {
            private var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }
        return ConsentGate(Policy("local-detector-lab","video","Only local tracking, no upload"),store)
            .apply { accept(true,true) }
    }

    @Test fun blankImageDoesNotProducePersonBox()=runBlocking {
        val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        try {
            MlKitPosePersonDetector().use { detector ->
                assertNull(detector.detect(bitmap,0))
                assertNull(detector.detect(bitmap,1))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun compareOnAuthorizedVideoIfProvided()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"authorized-beach-test.mp4")
        assumeTrue("No authorized video staged in sandbox; comparison skipped",source.isFile)
        val gate=localGate()
        val capture=CaptureStore(context).create()
        source.copyTo(capture.file,true)
        try {
            val prepared=MediaPreparer(context,gate).prepare(capture.uri,"camera"){}
            try {
                val normalized=DeviceVideoPipeline(gate,DetectorBackend.HOG).normalizeVideo(prepared){}
                try {
                    val report=JSONObject().put("video_frames",normalized.frames)
                        .put("video_width",normalized.width).put("video_height",normalized.height)
                    for(backend in DetectorBackend.values()) {
                        val started=android.os.SystemClock.elapsedRealtime()
                        val item=JSONObject()
                        try {
                            val result=DeviceVideoPipeline(gate,backend).trackVideo(normalized){}
                            val summary=TrackingSummary.from(result)
                            item.put("coverage_percent",summary.percent)
                                .put("detector_frames",result.detectorFrames)
                                .put("boxes",result.boxes.size)
                                .put("longest_gap_frames",summary.longestGap)
                        } catch(error:Exception) {
                            item.put("error_type",error.javaClass.simpleName)
                        }
                        item.put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
                        report.put(backend.name,item)
                    }
                    File(context.cacheDir,"detector-comparison.json").writeText(report.toString(2))
                    android.util.Log.i("PocketPoseDetector","comparison="+report.toString())
                    assertTrue(report.has("HOG")&&report.has("ML_KIT"))
                }finally { normalized.file.delete() }
            }finally { prepared.file.delete() }
        }finally { capture.file.delete() }
    }
}
