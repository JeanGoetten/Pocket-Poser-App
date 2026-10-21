package com.posepocket.pose

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class Pose33TopologyTest {
    private fun writeLocalClip(dir:File,model:String="mediapipe",n:Int=33, hasPose:Boolean=true):File {
        val file=File(dir,"33-landmarks.jsonl")
        val points=JSONArray()
        repeat(n) { index ->
            if(index==16)points.put(JSONObject.NULL) else
                points.put(JSONArray(listOf(index*.005,when(index) {
                    11,12->.5
                    23,24->0.0
                    25,26->-.5
                    27,28->-.9
                    else->.6
                },.0)))
        }
        file.bufferedWriter().use { writer ->
            repeat(2) { frame ->
                val json=JSONObject().put("frame_id",frame)
                    .put("schema_version",2)
                    .put("skeleton","pose-landmarks-33")
                    .put("provenance","local-"+model+"-landmarks-33")
                    .put("landmarks_33",points)
                    .put("has_pose",hasPose)
                    .put("rig_rotations_calibrated",false)
                writer.write(json.toString());writer.newLine()
            }
        }
        return file
    }

    @Test fun exactly33NamedNodesWithOnlyNativeEdges() {
        assertEquals(33,Pose33.names.size)
        assertEquals(33,Pose33.names.toSet().size)
        assertEquals("left_shoulder",Pose33.names[11])
        assertEquals("right_shoulder",Pose33.names[12])
        assertEquals("left_wrist",Pose33.names[15])
        assertEquals("right_wrist",Pose33.names[16])
        assertEquals("left_hip",Pose33.names[23])
        assertEquals("right_hip",Pose33.names[24])
        assertTrue(Pose33.edges.contains(11 to 13))
        assertTrue(Pose33.edges.contains(13 to 15))
        assertFalse(Pose33.edges.contains(11 to 15))
        Pose33.edges.forEach { (start,end) ->
            assertTrue(start in 0..32&&end in 0..32)
        }
        assertFalse(Pose33.edges.any {(a,b)->a==11&&b==14})
    }

    @Test fun native33ReadKeepsMissingLandmarkWithoutSyntheticBones() {
        val dir=createTempDir(prefix="pose33-")
        try {
            val file=writeLocalClip(dir)
            val clip=readClip(file,30,1,2,false,"camera",null)
            assertEquals("MEDIAPIPE",clip.generator)
            val first=requireNotNull(clip.frames[0].joints)
            assertEquals(33,first.size)
            val mask=requireNotNull(clip.frames[0].visibility)
            assertEquals(33,mask.size)
            assertFalse(mask[16])
            assertTrue(mask[15])
            assertEquals(0f,first[16].norm())
            assertFalse(JSONObject(file.readLines()[0]).has("keypoints_3d"))
            assertFalse(JSONObject(file.readLines()[0]).has("joint_rotations"))
            val continuity=PreviewContinuity(clip.frames,clip.fps)
            assertEquals(0,continuity.count)
            assertEquals(64,continuity.positions().count())
        }finally{dir.deleteRecursively()}
    }

    @Test fun nativeClipRejectsUnsupportedLandmarkCount() {
        val dir=createTempDir(prefix="pose33-")
        try {
            val file=writeLocalClip(dir,n=32)
            assertThrows(IllegalArgumentException::class.java) {
                readClip(file,30,1,2,false,"camera",null)
            }
        }finally{dir.deleteRecursively()}
    }

    @Test fun aFrameWithNoPoseRetainsSourceButNotRenderableNodes() {
        val dir=createTempDir(prefix="pose33-")
        try {
            val file=writeLocalClip(dir,hasPose=false)
            assertThrows(IllegalArgumentException::class.java) {
                readClip(file,30,1,2,false,"camera",null)
            }
            // Missing data cannot masquerade as a valid skeleton.
        }finally{dir.deleteRecursively()}
    }

    @Test fun nativeMaskMustHaveBothShouldersAndHipsForUsablePose() {
        val mask=MutableList(33){false}
        listOf(11,12,23,24,27).forEach{mask[it]=true}
        assertTrue(Pose33.hasBody(mask))
        mask[11]=false
        assertFalse(Pose33.hasBody(mask))
    }

    @Test fun localCoordinateConversionKeepsUpAxisIndependentFromNova77() {
        val p=Vec3(.15f,.6f,.2f)
        assertEquals(p,previewSourceCoordinates(p,"MEDIAPIPE"))
        assertEquals(p,previewSourceCoordinates(p,"ML_KIT"))
        assertEquals(Vec3(.15f,-.6f,-.2f),previewSourceCoordinates(p,"NVIDIA"))
    }
    @Test fun lowVisibilityIsNotMissingDataAndKeepsBothArms() {
        val dir=java.nio.file.Files.createTempDirectory("pose33-scores-").toFile()
        try {
            val file=writeLocalClip(dir)
            val visibility=JSONArray((0..32).map { index ->
                if(index in listOf(12,14,16,18,20,22)) .06
                else .95
            })
            file.writeText(file.readLines().joinToString("\n",postfix="\n") {
                JSONObject(it).put("landmark_visibility_33",visibility).toString()
            })
            val clip=readClip(file,30,1,2,false,"camera",null)
            val frame=clip.frames.first()
            assertEquals(33,requireNotNull(frame.joints).size)
            val mask=requireNotNull(frame.visibility)
            assertTrue(mask[12]);assertTrue(mask[14])
            assertFalse("Only deliberately missing point is not available",mask[16])
            val confidence=requireNotNull(frame.confidence)
            assertTrue(Pose33.isEstimated(12,14,confidence))
            assertFalse(Pose33.isEstimated(11,13,confidence))
            assertTrue(Pose33.isEstimated(16,confidence))
            assertEquals(2,clip.frames.count { it.joints!=null })
            // Framing ignores estimates but the renderable frame retains them.
            val fitPoints=PreviewContinuity(clip.frames,clip.fps).positions().toList()
            assertFalse(fitPoints.contains(frame.joints!![12]))
        }finally{dir.deleteRecursively()}
    }

    @Test fun confidenceValuesHaveExplicitRangeAndShape() {
        val dir=java.nio.file.Files.createTempDirectory("pose33-invalid-").toFile()
        try {
            val file=writeLocalClip(dir)
            val bad=JSONArray(List(33){if(it==12)1.5 else .8})
            file.writeText(file.readLines().joinToString("\n",postfix="\n") {
                JSONObject(it).put("landmark_visibility_33",bad).toString()
            })
            assertThrows(IllegalArgumentException::class.java) {
                readClip(file,30,1,2,false,"camera",null)
            }
        }finally{dir.deleteRecursively()}
    }

}
