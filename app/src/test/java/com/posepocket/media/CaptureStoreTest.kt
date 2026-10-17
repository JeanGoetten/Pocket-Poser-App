package com.posepocket.media

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CaptureStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store get()=CaptureStore(context) { Uri.parse("content://${context.packageName}.captures/captures/${it.name}") }

    @Test fun captureUriIsOurContentUriAndNameRoundTrips() {
        val pending=store.create()
        assertEquals("content",pending.uri.scheme)
        assertTrue(store.owns(pending.uri))
        assertEquals(pending.file,store.fileNamed(pending.name))
    }

    @Test fun discardDeletesOwnFileOnly() {
        val pending=store.create().also { it.file.writeText("x") }
        val foreign=java.io.File(context.cacheDir,"foreign.mp4").apply { writeText("x") }
        store.discard(Uri.parse("content://other.authority/captures/${pending.name}"))
        assertTrue(pending.file.exists())
        store.discard(Uri.fromFile(foreign))
        assertTrue(foreign.exists())
        store.discard(pending.uri)
        assertFalse(pending.file.exists())
        foreign.delete()
    }

    @Test fun fileNamedRejectsTraversalAndForeignNames() {
        assertNull(store.fileNamed("../x.mp4"))
        assertNull(store.fileNamed("video.mp4"))
        assertNull(store.fileNamed(""))
    }

    @Test fun purgeRemovesOnlyStaleCapturesAndKeepsPendingOnes() {
        val old=store.create().also { it.file.writeText("x");it.file.setLastModified(1_000L) }
        val fresh=store.create().also { it.file.writeText("x") }
        store.purgeStale(maxAgeMillis=60_000,now=System.currentTimeMillis())
        assertFalse(old.file.exists())
        assertTrue(fresh.file.exists())
        fresh.file.delete()
    }

    @Test fun recordVideoIntentTargetsCaptureUriWithLimitsAndGrants() {
        val uri=store.create().uri
        val intent=RecordVideo().createIntent(context,uri)
        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE,intent.action)
        assertEquals(uri,intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals(CaptureStore.MAX_SECONDS,intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT,0))
        assertEquals(CaptureStore.MAX_BYTES,intent.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT,0))
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0)
    }

    @Test fun onlyResultOkIsARecording() {
        val contract=RecordVideo()
        assertTrue(contract.parseResult(Activity.RESULT_OK,null))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED,null))
    }
}
