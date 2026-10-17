package com.posepocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.saveable.SaverScope
import com.posepocket.pose.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="pt-rBR")
class PosePreviewTest {
    @get:Rule val compose=createComposeRule()
    @get:Rule val folder=TemporaryFolder()

    @Test fun smoothingIsExplicitAndDoesNotChangeOriginalOrInflateProviderCount() {
        val demo=syntheticDemo(folder.newFile("gap.jsonl"))
        val first=org.json.JSONObject(demo.raw.readLines().first()).getJSONArray("detections").toString()
        demo.raw.writeText(demo.raw.readLines().map{line->
            org.json.JSONObject(line).also{if(it.getInt("frame_id")==1)it.put("detections",org.json.JSONArray());if(it.getInt("frame_id")==2)it.put("detections",org.json.JSONArray(first))}.toString()
        }.joinToString("\n",postfix="\n"))
        val clip=readClip(demo.raw,30,1,demo.frameCount,true,"camera",null);val hash=fileHash(clip.raw)
        compose.setContent{MaterialTheme{PosePreview(clip,false,{},{},{},{})}}
        compose.onNodeWithTag("preview_more").performClick()
        compose.onNodeWithText("Detalhes").performClick()
        compose.onNodeWithText("Suavização desativada.",substring=true).assertExists()
        compose.onNodeWithTag("preview_smooth").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("preview_smooth").assertIsOn()
        compose.onNodeWithText("Quadros interpolados na visualização: 1.",substring=true).assertExists()
        compose.onNodeWithText("Poses disponíveis: 119/120 quadros.").assertExists()
        compose.onNodeWithTag("details_close").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("preview_timeline").performSemanticsAction(SemanticsActions.SetProgress){assertTrue(it(1f))}
        compose.onNodeWithText("Quadro interpolado").assertExists()
        compose.onNodeWithText("Pose detectada neste quadro.").assertDoesNotExist()
        assertEquals(hash,fileHash(clip.raw))
    }

    @Test fun playbackAndSaveStayVisibleAcrossDetectionGaps() {
        val original=syntheticDemo(folder.newFile("demo.jsonl"))
        val clip=original.copy(frames=original.frames.map{if(it.id==1)it.copy(joints=null)else it})
        compose.setContent{MaterialTheme{PosePreview(clip,false,{},{},{},{})}}
        val before=compose.onNodeWithTag("preview_play").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("preview_save").assertIsDisplayed()
        compose.onNodeWithTag("preview_timeline").performSemanticsAction(SemanticsActions.SetProgress){assertTrue(it(1f))}
        compose.onNodeWithText("2 / 120").assertExists()
        compose.onNodeWithText("Sem pose neste quadro").assertExists()
        assertEquals(before,compose.onNodeWithTag("preview_play").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("preview_save").assertIsDisplayed()
    }

    @Test fun expandedStageKeepsTransportAndBackRestoresActions() {
        val clip=syntheticDemo(folder.newFile("expanded.jsonl"))
        compose.setContent{MaterialTheme{PosePreview(clip,false,{},{},{},{})}}
        val before=compose.onNodeWithTag("preview_stage").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("preview_expand").performClick()
        compose.onNodeWithTag("preview_save").assertDoesNotExist()
        compose.onNodeWithTag("preview_play").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("preview_stage").fetchSemanticsNode().boundsInRoot.height>before)
        compose.onNodeWithTag("preview_back").performClick()
        compose.onNodeWithTag("preview_save").assertIsDisplayed()
    }

    @Test fun cameraRestoresEveryViewParameter() {
        val camera=ViewCamera(.9f,-.4f,2.1f,-.8f,.6f)
        val stored=with(ViewCameraSaver){SaverScope { true }.save(camera)}
        assertNotNull(stored)
        assertEquals(camera,ViewCameraSaver.restore(stored!!))
    }

    @Test fun accessibilityZoomSurvivesStateRestorationAndCanReset() {
        val clip=syntheticDemo(folder.newFile("restore.jsonl"))
        val hash=fileHash(clip.raw)
        val restoration=StateRestorationTester(compose)
        restoration.setContent{MaterialTheme{PosePreview(clip,false,{},{},{},{})}}
        val stage=compose.onNodeWithContentDescription("Vista 3D.",substring=true)
        stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 86%"))
        compose.runOnIdle{
            val actions=stage.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(11,actions.size)
            assertTrue(actions.single{it.label=="Zoom in"}.action())
        }
        stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 103%"))
        restoration.emulateSavedInstanceStateRestore()
        stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 103%"))
        compose.runOnIdle{
            val actions=stage.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertTrue(actions.single{it.label=="Reiniciar câmera"}.action())
        }
        stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 86%"))
        assertEquals(hash,fileHash(clip.raw))
    }

    @Test @Config(sdk=[35],qualifiers="pt-rBR-w820dp-h400dp-land")
    fun landscapeUsesSideDockAndKeepsActionsReachable() {
        val clip=syntheticDemo(folder.newFile("landscape.jsonl"))
        compose.setContent{MaterialTheme{PosePreview(clip,false,{},{},{},{})}}
        compose.onNodeWithTag("preview_side_dock").assertExists()
        compose.onNodeWithTag("preview_save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview_export").assertIsDisplayed()
        val stage=compose.onNodeWithTag("preview_stage").fetchSemanticsNode().boundsInRoot
        val dock=compose.onNodeWithTag("preview_side_dock").fetchSemanticsNode().boundsInRoot
        assertTrue(stage.width>dock.width)
        assertTrue(stage.right<=dock.left)
        compose.onNodeWithTag("preview_expand").performClick()
        compose.onNodeWithTag("preview_side_dock").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("preview_stage").fetchSemanticsNode().boundsInRoot.width>stage.width)
        compose.onNodeWithTag("preview_play").assertIsDisplayed()
    }

    @Test @Config(sdk=[35],qualifiers="pt-rBR-w320dp-h568dp-port")
    fun largeFontKeepsStageVisibleAndActionsReachable() {
        val clip=syntheticDemo(folder.newFile("large-font.jsonl"))
        compose.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                MaterialTheme{PosePreview(clip,false,{},{},{},{})}
            }
        }
        compose.onNodeWithTag("preview_stage").assertIsDisplayed()
        val stage=compose.onNodeWithTag("preview_stage").fetchSemanticsNode().boundsInRoot
        assertTrue(stage.height>100)
        compose.onNodeWithTag("preview_save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview_export").assertIsDisplayed()
        compose.onNodeWithTag("preview_play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview_timeline").assertIsDisplayed()
    }
}
