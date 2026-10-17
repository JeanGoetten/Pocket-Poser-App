package com.posepocket.pose

import org.json.JSONArray
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
class FrameFilteringTest {
    @get:Rule val dir=TemporaryFolder()

    @Test fun filtersComposeWithoutConflatingEmptyAndDuplicateFrames() {
        val same=List(77){Vec3(0f,0f,0f)}
        val moved=same.toMutableList().apply{this[0]=Vec3(.002f,0f,0f)}
        val frames=listOf(PoseFrame(0,same),PoseFrame(1,null),PoseFrame(2,same),
            PoseFrame(3,same),PoseFrame(4,moved),PoseFrame(5,moved))
        assertEquals(listOf(0,1,2,3,4,5),filteredFrameIds(frames,FrameFilters()))
        assertEquals(listOf(0,2,3,4,5),filteredFrameIds(frames,FrameFilters(hideEmpty=true)))
        assertEquals(listOf(0,1,2,4),filteredFrameIds(frames,FrameFilters(removeDuplicates=true)))
        assertEquals(listOf(0,4),filteredFrameIds(frames,FrameFilters(true,true)))
    }

    @Test fun exportCompactsIdsRetainsSourceIdsAndNeverModifiesRaw() {
        val initial=syntheticDemo(dir.newFile("source.jsonl"))
        val originals=initial.raw.readLines()
        val pose0=JSONObject(originals.first()).getJSONArray("detections").toString()
        initial.raw.writeText(originals.map { line ->
            JSONObject(line).also { json ->
                when(json.getInt("frame_id")) {
                    1 -> json.put("detections",JSONArray())
                    2 -> json.put("detections",JSONArray(pose0))
                }
            }.toString()
        }.joinToString("\n",postfix="\n"))
        val clip=readClip(initial.raw,30,1,120,true,"synthetic",null)
            .copy(frameFilters=FrameFilters(true,true))
        val before=fileHash(clip.raw)
        val output=dir.newFile("compact.jsonl")
        exportFrameSelection(clip,output)
        val lines=output.readLines()
        assertEquals(118,lines.size)
        assertEquals(0,JSONObject(lines[0]).getInt("source_frame_id"))
        assertEquals(3,JSONObject(lines[1]).getInt("source_frame_id"))
        assertTrue(lines.indices.all { JSONObject(lines[it]).getInt("frame_id")==it })
        assertEquals(before,fileHash(clip.raw))
        assertEquals(118,readClip(output,30,1,118,true,"synthetic",null).frameCount)
        assertThrows(IllegalArgumentException::class.java) {exportFrameSelection(clip,clip.raw)}
    }

    @Test fun optionsPersistWhenProjectIsSavedAndUpdated() {
        val clip=syntheticDemo(dir.newFile("project.jsonl")).copy(frameFilters=FrameFilters(hideEmpty=true))
        val repository=ProjectRepository(dir.newFolder("library"))
        val saved=repository.save(clip)
        assertEquals(FrameFilters(true,false),repository.open(saved.id).frameFilters)
        repository.updateFrameFilters(saved.id,FrameFilters(true,true))
        val reopened=repository.open(saved.id)
        assertEquals(FrameFilters(true,true),reopened.frameFilters)
        assertEquals(fileHash(clip.raw),fileHash(reopened.raw))
    }
}
