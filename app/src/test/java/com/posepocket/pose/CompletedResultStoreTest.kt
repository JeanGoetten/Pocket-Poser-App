package com.posepocket.pose

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CompletedResultStoreTest {
    @get:Rule val dir=TemporaryFolder()
    @Test fun completedResultRestoresAfterCacheAndProcessingAreGone() {
        val source=syntheticDemo(dir.newFile("network.jsonl"))
            .copy(frameFilters=FrameFilters(true,true))
        val root=dir.newFolder("unsaved")
        val stored=CompletedResultStore(root).save(source)
        source.raw.delete()
        val restored=requireNotNull(CompletedResultStore(root).load())
        assertEquals(fileHash(stored.raw),fileHash(restored.raw))
        assertEquals(FrameFilters(true,true),restored.frameFilters)
        val projects=ProjectRepository(dir.newFolder("projects"))
        val saved=projects.save(restored)
        CompletedResultStore(root).clear()
        assertEquals(120,projects.open(saved.id).frameCount)
        assertNull(CompletedResultStore(root).load())
    }
    @Test fun failedReplacementKeepsPreviousCompletedResult() {
        val source=syntheticDemo(dir.newFile("source.jsonl"))
        val store=CompletedResultStore(dir.newFolder("unsaved"))
        val original=store.save(source)
        assertThrows(Exception::class.java) { store.save(source.copy(raw=File(dir.root,"missing"))) }
        assertEquals(fileHash(original.raw),fileHash(store.load()!!.raw))
    }
}
