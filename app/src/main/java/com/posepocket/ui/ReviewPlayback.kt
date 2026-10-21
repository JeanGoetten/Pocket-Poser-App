package com.posepocket.ui

import android.net.Uri
import android.content.Context
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.posepocket.R

/** The media source enforces both boundaries, including decoding from a preceding keyframe. */
internal fun reviewMediaItem(uri:Uri,start:Long,end:Long):MediaItem {
    require(uri.scheme=="content" && start>=0 && end>start)
    return MediaItem.Builder().setUri(uri).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
    ).build()
}

/** One player per URI; recomposition never releases an active decoder. No audio or auto-play. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoReview(info:ReviewInfo,start:Long,end:Long,modifier:Modifier,onPlaybackProgress:(Float)->Unit={},playerFactory:(Context)->ExoPlayer={ExoPlayer.Builder(it).build()}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val player=remember(context,info.uri) {
        info.uri?.let { playerFactory(context).apply {
            volume=0f
            trackSelectionParameters=trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build()
        } }
    }
    var ready by remember(player){mutableStateOf(false)}
    var playing by remember(player){mutableStateOf(false)}
    var rendered by remember(player){mutableStateOf(false)}
    var failed by remember(player){mutableStateOf(false)}
    val reportProgress by rememberUpdatedState(onPlaybackProgress)
    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int) {
                if(state==Player.STATE_READY)ready=true
                if(state==Player.STATE_ENDED)playing=false
            }
            override fun onIsPlayingChanged(value:Boolean){playing=value}
            override fun onRenderedFirstFrame(){rendered=true}
            override fun onPlayerError(error:PlaybackException){failed=true;ready=false;playing=false}
        }
        player?.addListener(listener)
        onDispose{player?.removeListener(listener);player?.release()}
    }
    DisposableEffect(owner,player) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player?.pause()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(player,start,end) {
        player?.let {
            it.pause();ready=false;rendered=false;failed=false;playing=false
            reportProgress(0f)
            it.setMediaItem(reviewMediaItem(requireNotNull(info.uri),start,end));it.prepare()
        }
    }
    // Progress is based on the clipped player window, not elapsed wall time.
    LaunchedEffect(player,start,end,ready,playing) {
        if(player!=null&&ready) {
            do {
                val duration=player.duration
                val position=player.currentPosition
                val fraction=if(duration>0L&&duration!=C.TIME_UNSET)
                    (position.toFloat()/duration.toFloat()).coerceIn(0f,1f)
                    else 0f
                reportProgress(fraction)
                if(!playing)break
                delay(80L)
            }while(isActive)
        }
    }
    val playbackState=stringResource(if(playing)R.string.pause else R.string.play)
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFE5E8ED)),contentAlignment=Alignment.Center) {
        if(player!=null)key(player){AndroidView(
            factory={ TextureView(it).apply{player.setVideoTextureView(this)} },
            modifier=Modifier.aspectRatio(info.aspect).testTag("review_video_surface"),
            onRelease={},
        )}
        // TextureView composes below this poster until an actual frame has been rendered.
        if(!rendered||failed)info.poster?.let{Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
        if(failed)Text(stringResource(R.string.review_unreadable),Modifier.align(Alignment.TopCenter).padding(16.dp))
        if(player!=null&&!failed)FilledIconButton({
            if(player.isPlaying)player.pause() else {
                if(player.playbackState==Player.STATE_ENDED)player.seekTo(0)
                player.play()
            }
        },Modifier.align(Alignment.BottomStart).padding(16.dp).size(52.dp).testTag("review_play").semantics{stateDescription=playbackState},enabled=ready){
            Glyph(if(playing)PocketIcons.Pause else PocketIcons.Play,playbackState)
        }
    }
}
