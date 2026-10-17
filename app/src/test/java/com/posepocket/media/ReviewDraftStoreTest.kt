package com.posepocket.media

import android.net.Uri
import com.posepocket.ReviewVideo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReviewDraftStoreTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun rangeAndOriginSurviveStoreRecreationAndDiscard() {
        val root=folder.newFolder()
        val draft=ReviewVideo(Uri.parse("content://provider/video/1"),"attachment",1500,3400)
        ReviewDraftStore(root).save(draft)
        assertEquals(draft,ReviewDraftStore(root).load())
        ReviewDraftStore(root).clear();assertNull(ReviewDraftStore(root).load())
    }
    @Test fun corruptManifestCannotRestoreAJob() {
        val root=folder.newFolder();java.io.File(root,"review-draft.json").writeText("broken")
        assertNull(ReviewDraftStore(root).load())
    }
}
