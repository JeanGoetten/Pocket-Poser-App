package com.posepocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.posepocket.media.TrimRange
import com.posepocket.media.FocusWindow
import androidx.compose.ui.geometry.Offset
import com.posepocket.ui.theme.PocketPoserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReviewScreenTest {
    @get:Rule val compose=createComposeRule()

    private fun info(duration:Long=8000L)=ReviewInfo(duration,null,List(5){ Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).asImageBitmap() },16f/9f)

    private fun show(load:ReviewLoad,onNext:(Long,Long)->Unit={_,_->},onChoose:()->Unit={}) {
        compose.setContent { PocketPoserTheme { Column(Modifier.fillMaxSize()) { ReviewScreen(load,onNext,onChoose) } } }
    }

    @Test fun nextSendsTheWholeVideoUntilTheRangeIsChanged() {
        var sent:Pair<Long,Long>?=null
        show(ReviewLoad.Ready(info()),onNext={ s,e -> sent=s to e })
        compose.onNodeWithTag("review_next").performClick()
        assertEquals(0L to 8000L,sent)
    }

    @Test fun chooseAnotherAndBackBothLeaveWithoutSending() {
        var left=0;var sent=0
        show(ReviewLoad.Ready(info()),onNext={_,_->sent++},onChoose={left++})
        compose.onNodeWithTag("review_back").performClick()
        compose.onNodeWithTag("review_choose").performClick()
        assertEquals(2,left);assertEquals(0,sent)
    }

    @Test fun loadingShowsProgressAndFailureOffersTheWayOut() {
        show(ReviewLoad.Loading)
        compose.onNodeWithTag("review_loading").assertExists()
        compose.onNodeWithTag("review_next").assertDoesNotExist()
    }

    @Test fun anUnreadableVideoCannotBeSent() {
        var left=0
        show(ReviewLoad.Failed,onChoose={left++})
        compose.onNodeWithTag("review_next").assertDoesNotExist()
        compose.onNodeWithTag("review_choose").performClick()
        assertEquals(1,left)
    }

    @Test fun focusCanBeEnabledMovedAndCommittedBeforeNext() {
        var last:FocusWindow?=null
        var next=0
        compose.setContent { PocketPoserTheme {
            ReviewScreen(ReviewLoad.Ready(info()),{_,_->next++},{},onFocus={last=it})
        } }
        compose.onNodeWithTag("review_playhead").assertExists()
        compose.onNodeWithTag("review_focus_toggle").performClick()
        assertEquals(FocusWindow(.2f,.05f,.8f,.95f),last)
        compose.onNodeWithTag("review_focus_window").assertExists().performTouchInput {
            swipe(center,center+Offset(24f,0f),durationMillis=350)
        }
        assertTrue(requireNotNull(last).left>.2f)
        compose.onNodeWithTag("review_next").performClick()
        assertEquals(1,next)
    }

    @Test fun trimRangeRejectsEmptyOrNegativeRanges() {
        assertThrows(IllegalArgumentException::class.java) { TrimRange(-1L,10L) }
        assertThrows(IllegalArgumentException::class.java) { TrimRange(10L,10L) }
        assertEquals(500L,TrimRange(0L,500L).endUs)
    }
    @Test fun subsecondSelectionHasDistinctReadableTimestamps() {
        compose.setContent{PocketPoserTheme{ReviewScreen(ReviewLoad.Ready(info(1000)),{_,_->},{},200,900)}}
        compose.onNodeWithText("0:00.2").assertExists()
        compose.onNodeWithText("0:00.9").assertExists()
    }

    @Test @Config(sdk=[35],qualifiers="w820dp-h400dp-land")
    fun landscapeReviewKeepsVideoAndTrimPanelSideBySide() {
        show(ReviewLoad.Ready(info()))
        compose.onNodeWithTag("review_next").assertIsDisplayed()
        compose.onNodeWithTag("review_choose").performScrollTo().assertIsDisplayed()
        val video=compose.onNodeWithTag("review_poster").fetchSemanticsNode().boundsInRoot
        val panel=compose.onNodeWithTag("review_side_panel").fetchSemanticsNode().boundsInRoot
        assertTrue(video.width>panel.width);assertTrue(video.right<=panel.left)
    }

    @Test @Config(sdk=[35],qualifiers="w320dp-h568dp-port")
    fun largeFontKeepsVideoAndNextVisibleAndTrimControlsReachable() {
        compose.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                PocketPoserTheme{ReviewScreen(ReviewLoad.Ready(info()),{_,_->},{},1000,6000)}
            }
        }
        compose.onNodeWithTag("review_next").assertIsDisplayed()
        compose.onNodeWithTag("review_poster").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("review_poster").fetchSemanticsNode().boundsInRoot.height>100)
        compose.onNodeWithTag("review_choose").performScrollTo().assertIsDisplayed()
    }
}
