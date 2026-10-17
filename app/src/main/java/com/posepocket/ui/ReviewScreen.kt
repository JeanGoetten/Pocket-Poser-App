package com.posepocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.posepocket.PocketViewModel
import com.posepocket.R
import com.posepocket.ReviewVideo
import com.posepocket.media.TrimRange
import com.posepocket.media.FocusWindow
import com.posepocket.privacy.ConsentGate
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.*

class ReviewInfo(val durationMs:Long,val poster:ImageBitmap?,val frames:List<ImageBitmap>,val aspect:Float,val uri:Uri?=null)
sealed interface ReviewLoad {object Loading:ReviewLoad;class Ready(val info:ReviewInfo):ReviewLoad;object Failed:ReviewLoad}

suspend fun loadReview(context:Context,gate:ConsentGate,uri:Uri):ReviewLoad=withContext(Dispatchers.IO) {
    val retriever=MediaMetadataRetriever()
    try {
        gate.withConsent{retriever.setDataSource(context,uri)}
        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
        if(duration<=0L)return@withContext ReviewLoad.Failed
        val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
        require(width in 2..4096&&height in 2..4096)
        val aspect=if(rotation==90||rotation==270)height.toFloat()/width else width.toFloat()/height
        suspend fun frameAt(ms:Long,side:Int):ImageBitmap? {
            currentCoroutineContext().ensureActive();gate.withConsent{}
            val ratio=side.toFloat()/max(width,height)
            val source=if(Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width*ratio).roundToInt().coerceAtLeast(1),(height*ratio).roundToInt().coerceAtLeast(1))
                else retriever.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            source?:return null
            val scale=side.toFloat()/max(source.width,source.height)
            val scaled=if(scale<1f)Bitmap.createScaledBitmap(source,(source.width*scale).roundToInt().coerceAtLeast(1),(source.height*scale).roundToInt().coerceAtLeast(1),true) else source
            if(scaled!==source)source.recycle()
            return scaled.asImageBitmap()
        }
        ReviewLoad.Ready(ReviewInfo(duration,frameAt(0,720),(0..6).mapNotNull{frameAt(duration*it/7,160)},aspect,uri))
    }catch(error:CancellationException){throw error}catch(_:Exception){ReviewLoad.Failed}finally{retriever.release()}
}
private fun clock(ms:Long):String {val total=ms/1000;return String.format(Locale.ROOT,"%d:%02d.%d",total/60,total%60,(ms%1000)/100)}

@Composable
fun ReviewHost(model:PocketViewModel,review:ReviewVideo) {
    val context=LocalContext.current
    var load by remember(review.uri){mutableStateOf<ReviewLoad>(ReviewLoad.Loading)}
    LaunchedEffect(review.uri){load=loadReview(context,model.gate,review.uri)}
    BackHandler{model.cancelReview()}
    ReviewScreen(load,{start,end->
        val duration=(load as? ReviewLoad.Ready)?.info?.durationMs?:0L
        model.confirmReview(if(start==0L&&end>=duration-50L)null else TrimRange(start*1000,end*1000), model.state.value.review?.focus)
    },model::cancelReview,review.startMs,review.endMs,model::updateReviewRange,review.focus,model::updateReviewFocus)
}

@Composable
fun ReviewScreen(load:ReviewLoad,onNext:(Long,Long)->Unit,onChoose:()->Unit,initialStart:Long=0,initialEnd:Long?=null,onRange:(Long,Long)->Unit={_,_->},
    initialFocus:FocusWindow?=null,onFocus:(FocusWindow?)->Unit={}) {
    val info=(load as? ReviewLoad.Ready)?.info
    var start by rememberSaveable(info?.uri?.toString(),info?.durationMs){mutableLongStateOf(initialStart)}
    var end by rememberSaveable(info?.uri?.toString(),info?.durationMs){mutableLongStateOf(initialEnd?:info?.durationMs?:0L)}
    var focus by remember(info?.uri?.toString()){mutableStateOf(initialFocus)}
    Column(Modifier.fillMaxSize().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconAction(PocketIcons.Back,stringResource(R.string.back),onChoose,Modifier.testTag("review_back"))
            Spacer(Modifier.weight(1f))
            if(info!=null)Button({onFocus(focus);onNext(start,end)},Modifier.heightIn(min=48.dp).testTag("review_next")){Text(stringResource(R.string.next))}
        }
        when(load) {
            ReviewLoad.Loading -> Box(Modifier.fillMaxWidth().weight(1f).testTag("review_loading"),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            ReviewLoad.Failed -> Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.Center){Text(stringResource(R.string.review_unreadable))}
            is ReviewLoad.Ready -> {
                val total=load.info.durationMs.toFloat()
                val safeStart=start.coerceIn(0,load.info.durationMs-1)
                val safeEnd=end.coerceIn(safeStart+1,load.info.durationMs)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val controls: @Composable ColumnScope.()->Unit = {
                        val description=stringResource(R.string.review_range)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(stringResource(R.string.review_focus),Modifier.weight(1f))
                            Switch(focus!=null,{ checked->
                                focus=if(checked)FocusWindow(.2f,.05f,.8f,.95f) else null
                                onFocus(focus)
                            },Modifier.testTag("review_focus_toggle"))
                        }
                        if(focus!=null)Text(stringResource(R.string.review_focus_hint),style=MaterialTheme.typography.bodySmall)
                        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))) {
                            Row(Modifier.matchParentSize()){load.info.frames.forEach{Image(it,null,Modifier.weight(1f).fillMaxHeight(),contentScale=ContentScale.Crop)}}
                            RangeSlider(safeStart.toFloat()..safeEnd.toFloat(),{ next->
                                if(next.endInclusive-next.start>=min(500f,total)){
                                    start=next.start.toLong();end=next.endInclusive.toLong()
                                }
                            },Modifier.fillMaxWidth().align(Alignment.Center).testTag("review_range").semantics{contentDescription=description},
                                valueRange=0f..total,onValueChangeFinished={onRange(start,end)})
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(clock(safeStart),style=MaterialTheme.typography.labelMedium);Text(clock(safeEnd),style=MaterialTheme.typography.labelMedium)}
                        if(safeStart>0||safeEnd<load.info.durationMs)Text(stringResource(R.string.trim_short_note),style=MaterialTheme.typography.bodySmall)
                        TextButton(onChoose,Modifier.align(Alignment.CenterHorizontally).testTag("review_choose")){Text(stringResource(R.string.choose_another))}
                    }
                    val panelLimit=maxHeight*.45f
                    if(maxWidth>=600.dp&&maxWidth>maxHeight) {
                        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            VideoFocusReview(load.info,safeStart,safeEnd,focus,{focus=it},{onFocus(focus)},Modifier.weight(1f).fillMaxHeight().testTag("review_poster"))
                            Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("review_side_panel"),verticalArrangement=Arrangement.spacedBy(12.dp)){controls()}
                        }
                    }else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        VideoFocusReview(load.info,safeStart,safeEnd,focus,{focus=it},{onFocus(focus)},Modifier.fillMaxWidth().weight(1f).testTag("review_poster"))
                        Column(Modifier.fillMaxWidth().heightIn(max=panelLimit).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){controls()}
                    }
                }
            }
        }
        if(info==null)TextButton(onChoose,Modifier.align(Alignment.CenterHorizontally).testTag("review_choose")){Text(stringResource(R.string.choose_another))}
    }
}


/** Corners resize the normalized video crop; the actual crop runs before tracking. */
@Composable
private fun VideoFocusReview(info:ReviewInfo,start:Long,end:Long,focus:FocusWindow?,
    onChange:(FocusWindow)->Unit,onCommit:()->Unit,modifier:Modifier) {
    val current by rememberUpdatedState(focus)
    val changeCrop by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    var anchor by remember{mutableIntStateOf(-1)}
    BoxWithConstraints(modifier) {
        VideoReview(info,start,end,Modifier.fillMaxSize())
        if(focus!=null) {
            val videoWidth=minOf(maxWidth,maxHeight*info.aspect)
            val videoHeight=videoWidth/info.aspect
            Canvas(Modifier.align(Alignment.Center).size(videoWidth,videoHeight)
                .testTag("review_focus_window").pointerInput(Unit) {
                    detectDragGestures(onDragStart={ pos->
                        val region=current?:return@detectDragGestures
                        val x=pos.x/size.width;val y=pos.y/size.height
                        val corners=listOf(region.left to region.top,region.right to region.top,
                            region.left to region.bottom,region.right to region.bottom)
                        anchor=corners.indices.minByOrNull { index->
                            val(cx,cy)=corners[index];(x-cx)*(x-cx)+(y-cy)*(y-cy)
                        }?:-1
                    },onDragEnd={anchor=-1;commit()},onDragCancel={anchor=-1}){pointer,drag->
                        pointer.consume()
                        val a=current?:return@detectDragGestures
                        val dx=drag.x/size.width;val dy=drag.y/size.height
                        val l=if(anchor==0||anchor==2)(a.left+dx).coerceIn(0f,a.right-.2f)else a.left
                        val rr=if(anchor==1||anchor==3)(a.right+dx).coerceIn(a.left+.2f,1f)else a.right
                        val t=if(anchor==0||anchor==1)(a.top+dy).coerceIn(0f,a.bottom-.2f)else a.top
                        val b=if(anchor==2||anchor==3)(a.bottom+dy).coerceIn(a.top+.2f,1f)else a.bottom
                        changeCrop(FocusWindow(l,t,rr,b))
                    }
                }) {
                val w=size.width;val h=size.height
                val l=focus.left*w;val t=focus.top*h;val rr=focus.right*w;val b=focus.bottom*h
                val dim=Color.Black.copy(alpha=.4f)
                drawRect(dim,topLeft=Offset.Zero,size=Size(w,t))
                drawRect(dim,topLeft=Offset(0f,b),size=Size(w,h-b))
                drawRect(dim,topLeft=Offset(0f,t),size=Size(l,b-t))
                drawRect(dim,topLeft=Offset(rr,t),size=Size(w-rr,b-t))
                drawRect(Color.White,topLeft=Offset(l,t),size=Size(rr-l,b-t),style=Stroke(width=2.dp.toPx()))
                listOf(Offset(l,t),Offset(rr,t),Offset(l,b),Offset(rr,b)).forEach {
                    drawCircle(Color.White,8.dp.toPx(),it)
                    drawCircle(Color(0xFF2563EB),5.dp.toPx(),it)
                }
            }
        }
    }
}
