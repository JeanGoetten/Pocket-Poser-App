package com.posepocket.pose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PoseDataTest {
    @get:Rule val temp=TemporaryFolder()
    private fun demo()=syntheticDemo(temp.newFile("${java.util.UUID.randomUUID()}.jsonl"))

    @Test fun officialHierarchyIsTreeAndCanonicalPoseHasHorizontalArms() {
        assertEquals(77,Nova77.parents.size)
        assertEquals(-1,Nova77.parents[0])
        Nova77.parents.forEachIndexed { index,parent -> if(index>0)assertTrue(parent in 0 until index) }
        val t=Nova77.tPose()
        assertEquals(t[11].y,t[14].y,.0001f)
        assertEquals(t[39].y,t[42].y,.0001f)
        assertTrue(t[14].x<-.6f&&t[42].x>.6f)
        assertTrue((t[68]-t[67]).norm()>.2f)
    }

    @Test fun saveReopenAndIntegrityProtectRawAndSavedProjects() {
        val clip=demo();val originalHash=fileHash(clip.raw)
        val repository=ProjectRepository(temp.newFolder("projects"))
        val saved=repository.save(clip)
        assertEquals(1,repository.list().size)
        val reopened=repository.open(saved.id)
        assertEquals(originalHash,fileHash(reopened.raw))
        assertTrue(reopened.demo)
        assertEquals(120,reopened.frameCount)
        assertEquals(clip.frames,reopened.frames)
        // Removing a new unsaved capture must not remove the saved raw data.
        clip.raw.delete()
        assertEquals(originalHash,fileHash(repository.open(saved.id).raw))
        reopened.raw.appendText("corrupt")
        assertThrows(IllegalArgumentException::class.java){repository.open(saved.id)}
        repository.delete(saved.id)
        assertTrue(repository.list().isEmpty())
    }

    @Test fun invalidResultCannotBeOpenedAsSuccessfulCapture() {
        val clip=demo()
        val first=JSONObject(clip.raw.useLines { it.first() })
        first.getJSONArray("detections").getJSONObject(0).getJSONArray("keypoints_3d").remove(0)
        clip.raw.writeText(first.toString()+"\n")
        assertThrows(IllegalArgumentException::class.java){readClip(clip.raw,30,1,120,false,"camera",null)}
    }

    @Test fun libraryMetadataPreservesRationalFrameRateAndRawData() {
        val clip=demo().copy(fpsNumerator=30000,fpsDenominator=1001)
        val hash=fileHash(clip.raw)
        val repository=ProjectRepository(temp.newFolder("rational-fps"))
        val saved=repository.save(clip)
        val listed=repository.list().single()
        assertEquals(saved,listed)
        assertEquals(120,listed.frameCount)
        assertEquals(4.004,listed.durationSeconds!!,.000001)
        assertEquals(hash,fileHash(repository.open(saved.id).raw))
    }

    @Test fun duplicateFramesAndPathTraversalAreRejected() {
        val clip=demo();val line=clip.raw.useLines { it.first() }
        clip.raw.writeText("$line\n$line\n")
        assertThrows(IllegalArgumentException::class.java){readClip(clip.raw,30,1,120,true,"synthetic",null)}
        val repository=ProjectRepository(temp.newFolder("projects"))
        assertThrows(IllegalArgumentException::class.java){repository.open("../outside")}
    }

    @Test fun missingFrameIsRejectedButExplicitEmptyDetectionIsAccepted() {
        val clip=demo()
        val lines=clip.raw.readLines().toMutableList()
        val frame=JSONObject(lines[42]).put("detections",org.json.JSONArray())
        lines[42]=frame.toString()
        clip.raw.writeText(lines.joinToString("\n",postfix="\n"))
        assertNull(readClip(clip.raw,30,1,120,true,"synthetic",null).frames[42].joints)
        lines.removeAt(42)
        clip.raw.writeText(lines.joinToString("\n",postfix="\n"))
        assertThrows(IllegalArgumentException::class.java){readClip(clip.raw,30,1,120,true,"synthetic",null)}
    }

    @Test fun playbackAndViewCameraHaveIndependentState() {
        val raw=demo();val hash=fileHash(raw.raw)
        val paused=Playback(frame=42).pause()
        assertEquals(42,paused.advance(120).frame)
        assertEquals(42,paused.resume().frame)
        val t=paused.resetPose()
        assertTrue(t.tPose);assertFalse(t.playing)
        assertEquals(0,t.resume().frame)
        var view=ViewCamera(yaw=.5f,panX=1f)
        repeat(100){view=view.zoomBy(2f)}
        assertEquals(4f,view.zoom,.001f)
        repeat(100){view=view.zoomBy(.5f)}
        assertEquals(.35f,view.zoom,.001f)
        view=ViewCamera()
        assertEquals(t,paused.resetPose())
        assertEquals(Vec3(0f,0f,0f),view.project(Vec3(0f,0f,0f)))
        assertEquals(hash,fileHash(raw.raw))
    }

    @Test fun editingLayerIsReversibleAndSurvivesSaveWithoutChangingRaw() {
        val clip=demo();val hash=fileHash(clip.raw);val editor=PoseEditor(clip)
        editor.rotate(13,0,119,Quaternion.zDegrees(0f),Quaternion.zDegrees(90f))
        val edited=editor.clip()
        assertTrue((requireNotNull(edited.frames.last().joints)[14]-requireNotNull(clip.frames.last().joints)[14]).norm()>.1f)
        editor.undo()
        assertTrue((requireNotNull(editor.clip().frames.last().joints)[14]-requireNotNull(clip.frames.last().joints)[14]).norm()<.00001f)
        editor.redo()
        val repository=ProjectRepository(temp.newFolder("edited-projects"))
        val saved=repository.save(editor.clip());val reopened=repository.open(saved.id)
        assertEquals(hash,fileHash(reopened.raw))
        assertEquals(editor.clip().frames,reopened.frames)
        assertNotNull(reopened.correctionLayer)
        assertEquals(hash,fileHash(clip.raw))
    }

    @Test fun nativeIkKeepsBoneLengthsAndUndoesWholeOperation() {
        val clip=demo();val editor=PoseEditor(clip)
        val before=requireNotNull(clip.frames[0].joints)
        editor.ik(0,11,12,13,Vec3(-.3f,.55f,.05f),Vec3(0f,1f,1f))
        val after=requireNotNull(editor.clip().frames[0].joints)
        assertEquals((before[12]-before[11]).norm(),(after[12]-after[11]).norm(),.00001f)
        assertEquals((before[13]-before[12]).norm(),(after[13]-after[12]).norm(),.00001f)
        assertTrue((after[13]-Vec3(-.3f,.55f,.05f)).norm()<.0001f)
        editor.undo();assertEquals(0,editor.cursor)
        assertTrue((requireNotNull(editor.clip().frames[0].joints)[13]-before[13]).norm()<.00001f)
    }

    @Test fun outOfOrderFramesAreIndexedAndSyntheticCannotBeMislabelled() {
        val clip=demo();val reversed=clip.raw.readLines().reversed().joinToString("\n",postfix="\n")
        clip.raw.writeText(reversed)
        val sorted=readClip(clip.raw,30,1,120,true,"synthetic",null)
        assertEquals(0,sorted.frames.first().id)
        assertEquals(120,PoseEditor(sorted).clip().frames.size)
        assertThrows(IllegalArgumentException::class.java){readClip(clip.raw,30,1,120,false,"camera",null)}
    }

    @Test fun enablingEditorAndUndoKeepExactRawPointsWithinCalibrationTolerance() {
        val source=demo();val lines=source.raw.readLines().toMutableList()
        val frame=JSONObject(lines[0]);val point=frame.getJSONArray("detections").getJSONObject(0).getJSONArray("keypoints_3d").getJSONArray(14)
        point.put(0,point.getDouble(0)+.01);lines[0]=frame.toString();source.raw.writeText(lines.joinToString("\n",postfix="\n"))
        val clip=readClip(source.raw,30,1,120,true,"synthetic",null)
        val editor=PoseEditor(clip)
        assertEquals(clip.frames,editor.clip().frames)
        editor.rotate(13,0,0,Quaternion.zDegrees(60f));editor.undo()
        assertEquals(clip.frames,editor.clip().frames)
    }
}
