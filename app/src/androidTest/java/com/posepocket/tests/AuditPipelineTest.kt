package com.posepocket.tests

import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.posepocket.media.*
import com.posepocket.nvidia.*
import com.posepocket.pose.fileHash
import com.posepocket.privacy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuditPipelineTest {
    @Test fun wideSixtyFpsVideoIsScaledAndPaddedForTheDetector()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val capture=CaptureStore(context).create()
        instrumentation.context.assets.open("synthetic-wide-test.mp4").use { input->capture.file.outputStream().use(input::copyTo) }
        val gate=ConsentGate(Policy("synthetic-padding","video","Synthetic green frame; no upload"),object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }).apply { accept(true,true) }
        try {
            val video=DeviceVideoPipeline(gate).normalizeVideo(PreparedVideo(capture.file,fileHash(capture.file),"camera"),{})
            try {
                assertEquals(640,video.width);assertEquals(128,video.height);assertEquals(30,video.frames)
                val reader=MediaMetadataRetriever()
                try {
                    reader.setDataSource(video.file.absolutePath)
                    val frame=requireNotNull(reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
                    assertTrue(Color.green(frame.getPixel(frame.width/2,frame.height/2))>80)
                    assertTrue("Padding must be black, not stretched green content",Color.green(frame.getPixel(frame.width/2,0))<30)
                    frame.recycle()
                }finally{reader.release()}
            }finally{video.file.delete()}
        }finally{capture.file.delete()}
    }
    @Test fun focusOnRotatedVideoMatchesTheReviewedRegion()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val capture=CaptureStore(context).create()
        instrumentation.context.assets.open("synthetic-rotated-test.mp4").use { input->capture.file.outputStream().use(input::copyTo) }
        val gate=ConsentGate(Policy("synthetic-rotation","video","Synthetic crop only; no upload"),object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }).apply { accept(true,true) }
        val focus=FocusWindow(0f,0f,.3f,.6f)
        fun mean(bitmap:android.graphics.Bitmap,x:Int,y:Int,w:Int,h:Int):List<Double> {
            val sum=DoubleArray(3)
            for(row in 0..7)for(col in 0..7) {
                val pixel=bitmap.getPixel(x+((col+.5)*w/8).toInt().coerceAtMost(w-1),y+((row+.5)*h/8).toInt().coerceAtMost(h-1))
                sum[0]+=Color.red(pixel);sum[1]+=Color.green(pixel);sum[2]+=Color.blue(pixel)
            }
            return sum.map { it/64 }
        }
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(capture.file.absolutePath)
            val original=requireNotNull(reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
            val region=focus.pixelBounds(original.width,original.height)
            val expected=mean(original,region[0],region[1],region[2],region[3]);original.recycle()
            val geometry=VideoGeometry.fit(region[2],region[3])
            val video=DeviceVideoPipeline(gate).normalizeVideo(PreparedVideo(capture.file,fileHash(capture.file),"camera",focus=focus),{})
            try {
                reader.release()
                val output=MediaMetadataRetriever()
                try {
                    output.setDataSource(video.file.absolutePath)
                    val bitmap=requireNotNull(output.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
                    val actual=mean(bitmap,geometry.left,geometry.top,geometry.contentWidth,geometry.contentHeight);bitmap.recycle()
                    expected.indices.forEach { assertEquals("Rotated focus channel $it",expected[it],actual[it],18.0) }
                }finally{output.release()}
            }finally{video.file.delete()}
        }finally{try{reader.release()}catch(_:Exception){};capture.file.delete()}
    }
    @Test fun sourceAbove18000SamplesIsAcceptedBeforeThirtyFpsConversion()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val capture=CaptureStore(context).create()
        instrumentation.context.assets.open("synthetic-high-fps-test.mp4").use { input->capture.file.outputStream().use(input::copyTo) }
        val gate=ConsentGate(Policy("synthetic-fps","video","Synthetic source; no upload"),object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }).apply { accept(true,true) }
        try {
            val prepared=MediaPreparer(context,gate).prepare(capture.uri,"camera")
            try {
                val extractor=android.media.MediaExtractor()
                try {
                    extractor.setDataSource(prepared.file.absolutePath);extractor.selectTrack(0)
                    var frames=0
                    while(extractor.sampleTime>=0){frames++;extractor.advance()}
                    assertEquals(18060,frames)
                }finally{extractor.release()}
            }finally{prepared.file.delete()}
        }finally{capture.file.delete()}
    }
    @Test fun roundedReviewEndStillPreparesAndNormalizesSelectedHalf()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val capture=CaptureStore(context).create()
        instrumentation.context.assets.open("synthetic-trim-test.mp4").use { input ->
            capture.file.outputStream().use(input::copyTo)
        }
        val gate=ConsentGate(Policy("synthetic-rounded-end","video","Local synthetic trim only"),
            object:ConsentStore {
                private var receipt:ConsentReceipt?=null
                override fun read()=receipt
                override fun write(receipt:ConsentReceipt){this.receipt=receipt}
                override fun clear(){receipt=null}
            }).apply { accept(true,true) }
        val extractor=android.media.MediaExtractor()
        try {
            extractor.setDataSource(capture.file.absolutePath)
            val track=(0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/")==true
            }
            val durationUs=extractor.getTrackFormat(track).getLong(android.media.MediaFormat.KEY_DURATION)
            val startUs=durationUs/2
            // Reproduce the small positive discrepancy possible in the UI duration
            // in milliseconds vs the extractor track duration in microseconds.
            val range=TrimRange(startUs,durationUs+8_000L)
            val prepared=MediaPreparer(context,gate).prepare(capture.uri,"camera",range)
            try {
                assertEquals(durationUs-startUs,requireNotNull(prepared.trim).endUs-prepared.trim.startUs)
                val normalized=DeviceVideoPipeline(gate,DetectorBackend.HOG).normalizeVideo(prepared,{})
                try {
                    val expected=kotlin.math.ceil((durationUs-startUs)*30.0/1_000_000).toInt()
                    assertEquals(expected,normalized.frames)
                }finally{normalized.file.delete()}
            }finally{prepared.file.delete()}
        }finally{extractor.release();capture.file.delete()}
    }

    @Test fun nonKeyframeTrimSendsOnlySelectedGreenFrames()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val capture=CaptureStore(context).create()
        instrumentation.context.assets.open("synthetic-trim-test.mp4").use { input->capture.file.outputStream().use(input::copyTo) }
        val gate=ConsentGate(Policy("synthetic-trim","video","Synthetic colors only; no upload"),object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }).apply { accept(true,true) }
        val hash=fileHash(capture.file)
        val input=MediaPreparer(context,gate).prepare(capture.uri,"camera",TrimRange(1_100_000,1_900_000))
        try {
            assertEquals(TrimRange(1_100_000,1_900_000),input.trim)
            val video=DeviceVideoPipeline(gate).normalizeVideo(input,{})
            try {
                assertEquals(24,video.frames)
                assertEquals(hash,fileHash(capture.file))
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.file.absolutePath)
                    for(frame in 0 until video.frames) {
                        val bitmap=requireNotNull(retriever.getFrameAtTime(frame*1_000_000L/30,MediaMetadataRetriever.OPTION_CLOSEST))
                        val pixel=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                        bitmap.recycle()
                        assertTrue("Frame $frame leaked red/blue preroll or postroll",Color.green(pixel)>Color.red(pixel)*2&&Color.green(pixel)>Color.blue(pixel)*2)
                    }
                }finally{retriever.release()}
            }finally{video.file.delete()}
        }finally{input.file.delete();capture.file.delete()}
    }
}
