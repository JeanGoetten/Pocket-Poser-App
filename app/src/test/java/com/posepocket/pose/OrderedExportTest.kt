package com.posepocket.pose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OrderedExportTest {
    @get:Rule val folder=TemporaryFolder()

    @Test fun unsortedSourceExportsInTimelineOrderAndPreservesUnicodeAndRootId() {
        val clip=syntheticDemo(folder.newFile("source.jsonl"))
            .copy(frameFilters=FrameFilters(hideEmpty=true))
        val lines=clip.raw.readLines()
        val original0=JSONObject(lines[0])
            .put("note", "câmera em ação 🎬")
            .put("source_frame_id", 400).toString()
        clip.raw.writeText((listOf(lines[1],original0)+lines.drop(2))
            .joinToString("\n",postfix="\n"))
        val hash=fileHash(clip.raw)
        val output=folder.newFile("selected.jsonl")
        exportFrameSelection(clip,output)
        val selected=output.readLines().map(::JSONObject)
        assertEquals(120,selected.size)
        assertTrue(selected.indices.all { selected[it].getInt("frame_id")==it })
        assertEquals(400,selected[0].getInt("source_frame_id"))
        assertEquals("câmera em ação 🎬",selected[0].getString("note"))
        assertEquals(1,selected[1].getInt("source_frame_id"))
        assertEquals(hash,fileHash(clip.raw))
    }

    @Test fun revokedAccessLeavesExistingOutputUntouchedAndNoTemporaryFiles() {
        val clip=syntheticDemo(folder.newFile("original.jsonl"))
            .copy(frameFilters=FrameFilters(hideEmpty=true))
        val output=folder.newFile("export.jsonl").apply{writeText("existing export")}
        var calls=0
        assertThrows(SecurityException::class.java) {
            exportFrameSelection(clip,output) {
                if(++calls==125)throw SecurityException("Authorization revoked")
            }
        }
        assertEquals("existing export",output.readText())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith(".pose-export-") })
    }

    @Test fun missingSourceFrameFailsWithoutReplacingOutput() {
        val clip=syntheticDemo(folder.newFile("original.jsonl"))
            .copy(frameFilters=FrameFilters(hideEmpty=true))
        val all=clip.raw.readLines()
        clip.raw.writeText(all.drop(1).joinToString("\n",postfix="\n"))
        val output=folder.newFile("export.jsonl").apply{writeText("valid previous export")}
        assertThrows(IllegalStateException::class.java) {
            exportFrameSelection(clip,output)
        }
        assertEquals("valid previous export",output.readText())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith(".pose-export-") })
    }
}
