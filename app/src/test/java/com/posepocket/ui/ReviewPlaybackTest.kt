package com.posepocket.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="pt-rBR")
class ReviewPlaybackTest {
    @get:Rule val compose=createComposeRule()

    @Test fun clipUsesBothSelectedBoundariesAndRejectsRemoteOrEmptyMedia() {
        val media=reviewMediaItem(Uri.parse("content://test/video"),1250,2750)
        assertEquals(1250L,media.clippingConfiguration.startPositionMs)
        assertEquals(2750L,media.clippingConfiguration.endPositionMs)
        assertFalse(media.clippingConfiguration.startsAtKeyFrame)
        assertThrows(IllegalArgumentException::class.java){reviewMediaItem(Uri.parse("https://example.test/video"),0,1000)}
        assertThrows(IllegalArgumentException::class.java){reviewMediaItem(Uri.parse("content://test/video"),1000,1000)}
    }

    @Test fun firstPrepareAndRangeChangesDoNotReleaseThePlayerAndPlayCanRestart() {
        val start=mutableStateOf(0L);val visible=mutableStateOf(true)
        val listeners=mutableListOf<Player.Listener>();val items=mutableListOf<MediaItem>()
        var releases=0;var plays=0;var playbackState=Player.STATE_READY;var lastSeek=-1L
        val info=ReviewInfo(8000,null,emptyList(),1f,Uri.parse("content://test/video"))
        val fake=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)){proxy,method,args->
            when(method.name) {
                "addListener"->{listeners+=args!![0] as Player.Listener;null}
                "removeListener"->{listeners-=args!![0] as Player.Listener;null}
                "release"->{releases++;null}
                "setMediaItem"->{items+=args!![0] as MediaItem;null}
                "getTrackSelectionParameters"->TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
                "prepare"->{listeners.toList().forEach{it.onPlaybackStateChanged(Player.STATE_READY);it.onRenderedFirstFrame()};null}
                "play"->{plays++;null}
                "seekTo"->{lastSeek=args!!.last() as Long;null}
                "getPlaybackState"->playbackState
                "isPlaying"->false
                "equals"->proxy===args!![0]
                "hashCode"->System.identityHashCode(proxy)
                else->when(method.returnType){java.lang.Boolean.TYPE->false;java.lang.Integer.TYPE->0;java.lang.Long.TYPE->0L;java.lang.Float.TYPE->0f;else->null}
            }
        } as ExoPlayer
        compose.setContent{if(visible.value)VideoReview(info,start.value,6000,Modifier.fillMaxSize()){fake}}
        compose.onNodeWithTag("review_play").assertIsEnabled().performClick()
        assertEquals(1,plays);assertEquals(0,releases)
        compose.runOnIdle{start.value=1500}
        compose.waitForIdle()
        assertEquals(1500L,items.last().clippingConfiguration.startPositionMs)
        assertEquals(6000L,items.last().clippingConfiguration.endPositionMs)
        assertEquals(0,releases)
        compose.runOnIdle{playbackState=Player.STATE_ENDED}
        compose.onNodeWithTag("review_play").performClick()
        assertEquals(0L,lastSeek);assertEquals(2,plays)
        compose.runOnIdle{visible.value=false}
        compose.waitForIdle()
        assertEquals(1,releases)
    }
}
