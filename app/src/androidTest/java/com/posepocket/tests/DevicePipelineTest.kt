package com.posepocket.tests

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posepocket.gateway.NvidiaKeyStore
import com.posepocket.media.PreparedVideo
import com.posepocket.nvidia.DeviceVideoPipeline
import com.posepocket.pose.fileHash
import com.posepocket.privacy.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native Android codec/OpenCV/Keystore checks. No NVIDIA/network calls or real footage. */
@RunWith(AndroidJUnit4::class)
class DevicePipelineTest {
    @Test fun indexedBatchesMatchCfrFramesAndRecycleOnCancellation()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.cacheDir,"frames-check-${UUID.randomUUID()}").apply{mkdirs()}
        val file=File(folder,"synthetic.mp4")
        instrumentation.context.assets.open("synthetic-rotated-test.mp4").use{input->file.outputStream().use(input::copyTo)}
        val retriever=android.media.MediaMetadataRetriever()
        val formats=mutableListOf<String>()
        fun signature(bitmap:android.graphics.Bitmap):Int {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.contentHashCode()
        }
        try {
            retriever.setDataSource(file.absolutePath)
            assertEquals("180",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            val seekStarted=android.os.SystemClock.elapsedRealtime()
            val expected=(0 until 15).map { index ->
                val bitmap=requireNotNull(retriever.getFrameAtTime(index*1_000_000L/30,android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                try{if(index==0)formats+="time: ${bitmap.config} ${bitmap.width}x${bitmap.height}";signature(bitmap)}finally{bitmap.recycle()}
            }
            val seekMillis=android.os.SystemClock.elapsedRealtime()-seekStarted
            val actual=mutableListOf<Int>()
            val batchStarted=android.os.SystemClock.elapsedRealtime()
            com.posepocket.nvidia.VideoFrames.forEach(retriever,15,{}) { index,bitmap ->
                assertEquals(actual.size,index)
                if(index==0)formats+="index: ${bitmap.config} ${bitmap.width}x${bitmap.height}"
                actual+=signature(bitmap)
            }
            val batchMillis=android.os.SystemClock.elapsedRealtime()-batchStarted
            assertEquals(formats.joinToString(),expected,actual)
            File(instrumentation.targetContext.cacheDir,"decoder-fixture-timing.json").writeText(
                org.json.JSONObject().put("frames",15).put("time_seek_ms",seekMillis)
                    .put("indexed_batch_ms",batchMillis).put("pixel_identical",true)
                    .put("scope","Synthetic 160x240 fixture, same retriever, not end-to-end inference benchmark").toString())
            val delivered=mutableListOf<android.graphics.Bitmap>()
            try {
                com.posepocket.nvidia.VideoFrames.forEach(retriever,15,{}) { index,bitmap ->
                    delivered+=bitmap
                    if(index==2)throw kotlinx.coroutines.CancellationException("Synthetic cancellation")
                }
                fail("Expected cancellation")
            }catch(_:kotlinx.coroutines.CancellationException){}
            assertEquals(3,delivered.size)
            assertTrue(delivered.all{it.isRecycled})
        }finally{retriever.release();folder.deleteRecursively()}
    }

    @Test fun rotatedCfrVideoUsesBatchesWhileNormalizing()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.cacheDir,"rotation-check-${UUID.randomUUID()}").apply{mkdirs()}
        val file=File(folder,"synthetic.mp4")
        instrumentation.context.assets.open("synthetic-rotated-test.mp4").use{input->file.outputStream().use(input::copyTo)}
        val store=object:ConsentStore {
            var value:ConsentReceipt?=null
            override fun read()=value
            override fun write(receipt:ConsentReceipt){value=receipt}
            override fun clear(){value=null}
        }
        val gate=ConsentGate(Policy("test","video","Synthetic rotated video; no network"),store).apply{accept(true,true)}
        val phases=mutableListOf<String>()
        try {
            try {
                DeviceVideoPipeline(gate).prepare(PreparedVideo(file,fileHash(file),"attachment")){phases+=it}
                fail("Synthetic pattern must not produce a person")
            }catch(error:IllegalArgumentException) {
                assertTrue(error.message!!.startsWith("Nenhuma pessoa detectada"))
            }
            assertTrue(phases.joinToString(),phases.any{it.contains("leitura em lotes")})
            assertTrue(phases.any{it.startsWith("Localizando uma pessoa")})
            assertEquals(listOf("synthetic.mp4"),folder.listFiles().orEmpty().map{it.name})
        }finally{folder.deleteRecursively()}
    }

    @Test fun compatibleCfrVideoSkipsEncodingAndStillRunsPersonDetection()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.cacheDir,"cfr-check-${UUID.randomUUID()}").apply{mkdirs()}
        val file=File(folder,"synthetic.mp4")
        instrumentation.context.assets.open("synthetic-cfr-test.mp4").use{input->file.outputStream().use(input::copyTo)}
        val store=object:ConsentStore {
            var value:ConsentReceipt?=null
            override fun read()=value
            override fun write(receipt:ConsentReceipt){value=receipt}
            override fun clear(){value=null}
        }
        val gate=ConsentGate(Policy("test","video","Synthetic CFR video; no network"),store).apply{accept(true,true)}
        val phases=mutableListOf<String>()
        try {
            try {
                DeviceVideoPipeline(gate).prepare(PreparedVideo(file,fileHash(file),"attachment")){phases+=it}
                fail("Synthetic pattern must not produce a person")
            }catch(error:IllegalArgumentException) {
                assertTrue(error.message!!.startsWith("Nenhuma pessoa detectada"))
            }
            assertTrue(phases.any{it.contains("preservando quadros")})
            assertEquals(listOf("synthetic.mp4"),folder.listFiles().orEmpty().map{it.name})
        }finally{folder.deleteRecursively()}
    }

    @Test fun consentReplacementAndRevocationAreDurableOnAndroid() {
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"consent-check-${UUID.randomUUID()}").apply{mkdirs()}
        val policy=Policy("test","video","Synthetic privacy persistence test")
        try {
            val gate=ConsentGate(policy,AtomicConsentStore(folder))
            gate.accept(true,true)
            assertTrue(ConsentGate(policy,AtomicConsentStore(folder)).isGranted())
            gate.revoke()
            assertFalse(ConsentGate(policy,AtomicConsentStore(folder)).isGranted())
            assertNotNull(AtomicConsentStore(folder).read()?.revokedAt)
        }finally{folder.deleteRecursively()}
    }

    @Test fun normalizesSyntheticVideoAndCleansItWhenNoPersonIsFound()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.cacheDir,"native-check-${UUID.randomUUID()}").apply{mkdirs()}
        val file=File(folder,"synthetic.mp4")
        instrumentation.context.assets.open("synthetic-test.mp4").use{input->file.outputStream().use(input::copyTo)}
        val store=object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }
        val gate=ConsentGate(Policy("native-test","video","Synthetic video only; no network"),store).apply{accept(true,true)}
        val phases=mutableListOf<String>()
        try {
            try {
                DeviceVideoPipeline(gate).prepare(PreparedVideo(file,fileHash(file),"attachment")){phases+=it}
                fail("Synthetic pattern must not produce a person")
            }catch(error:IllegalArgumentException) {
                assertEquals("Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado.",error.message)
            }
            assertTrue(phases.any{it.startsWith("Localizando uma pessoa")})
            assertEquals(listOf("synthetic.mp4"),folder.listFiles().orEmpty().map{it.name})
        }finally{folder.deleteRecursively()}
    }

    @Test fun personalKeyIsEncryptedReopenedAndDeletedWithAndroidKeystore() {
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"key-check-${UUID.randomUUID()}").apply{mkdirs()}
        val key="test-only-not-a-real-nvidia-key-12345678901234567890"
        try {
            val store=NvidiaKeyStore(folder)
            store.write(key)
            assertEquals(key,NvidiaKeyStore(folder).read())
            assertFalse(File(folder,"nvidia-key.enc").readBytes().toString(Charsets.ISO_8859_1).contains(key))
            store.clear()
            assertNull(NvidiaKeyStore(folder).read());assertTrue(folder.listFiles().orEmpty().isEmpty())
        }finally{folder.deleteRecursively()}
    }
}
