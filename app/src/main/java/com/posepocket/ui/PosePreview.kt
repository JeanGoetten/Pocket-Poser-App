package com.posepocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.posepocket.R
import com.posepocket.pose.*
import com.posepocket.ui.theme.PocketTheme
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.roundToInt

/** Source-frame continuity when a filter removes the currently selected position. */
internal fun nearestSelectedIndex(ids:List<Int>,sourceId:Int):Int {
    if(ids.isEmpty())return 0
    val exact=ids.binarySearch(sourceId)
    if(exact>=0)return exact
    val next=-exact-1
    if(next==0)return 0
    if(next>=ids.size)return ids.lastIndex
    return if(sourceId-ids[next-1]<ids[next]-sourceId)next-1 else next
}

/** Fixed stage/transport. One finger orbits, two fingers pan/pinch, double tap resets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosePreview(clip:PoseClip,saved:Boolean,onSave:()->Unit,onDiscard:()->Unit,onRedo:()->Unit,onExport:()->Unit,enabled:Boolean=true,extras:@Composable ColumnScope.()->Unit={},onFiltersChange:(FrameFilters)->Unit={},identity:String=clip.raw.absolutePath) {
    val id=identity
    var frame by rememberSaveable(id){mutableIntStateOf(0)}
    var playing by rememberSaveable(id){mutableStateOf(false)}
    var tPose by rememberSaveable(id){mutableStateOf(false)}
    var expanded by rememberSaveable(id){mutableStateOf(false)}
    var body by rememberSaveable(id){mutableStateOf(true)}
    var multi by rememberSaveable(id){mutableStateOf(false)}
    var smooth by rememberSaveable(id){mutableStateOf(false)}
    var details by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)}
    var camera by rememberSaveable(id,stateSaver=ViewCameraSaver){mutableStateOf(ViewCamera(pitch=.12f,zoom=.86f))}
    val continuity=remember(clip.frames,clip.fps){PreviewContinuity(clip.frames,clip.fps)}
    val selectedIds=remember(clip.frames,clip.frameFilters){filteredFrameIds(clip.frames,clip.frameFilters)}
    // The slider stores a compacted index; retain its original frame ID across filter changes.
    var previousIds by remember(id){mutableStateOf(selectedIds)}
    val viewFrame=frame.coerceIn(0,(selectedIds.size-1).coerceAtLeast(0))
    val sourceFrame=selectedIds.getOrNull(viewFrame)
    LaunchedEffect(id,selectedIds) {
        val previousSource=previousIds.getOrNull(frame)
        frame=if(previousSource==null)frame.coerceIn(0,(selectedIds.size-1).coerceAtLeast(0))
            else nearestSelectedIndex(selectedIds,previousSource)
        previousIds=selectedIds
        if(selectedIds.isEmpty())playing=false
    }
    val fit=remember(clip.frames,clip.fps){ViewportFit.from(continuity.positions())}
    val neutralPose=remember { Nova77.tPose() }
    val neutral=remember(neutralPose) { ViewportFit.from(neutralPose.asSequence()) }
    val actual=if(tPose||sourceFrame==null)null else continuity.at(sourceFrame,smooth)
    val pose=if(tPose)neutralPose else actual?.joints
    val bounds=if(tPose)neutral else fit
    // Camera orbit/zoom redraws the same pose; transform joints only when pose/bounds change.
    val points=remember(pose,bounds,clip.demo,tPose,clip.generator) {
        pose?.map {
            val p=(it-bounds.center)*(1f/bounds.extent.coerceAtLeast(.1f))
            if(clip.demo||tPose)p else previewSourceCoordinates(p,clip.generator)
        }
    }
    val privacy=LocalOpenPrivacy.current
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)playing=false}
        lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(id,playing,clip.frameFilters) {
        if(playing){
            var last=withFrameNanos{it};val interval=(1_000_000_000.0/clip.fps).toLong().coerceAtLeast(1)
            while(isActive){val now=withFrameNanos{it};val elapsed=(now-last)/interval
                if(elapsed>0&&playing){frame=((frame+elapsed)%selectedIds.size.coerceAtLeast(1)).toInt();last+=elapsed*interval}}
        }
    }
    BackHandler{if(details)details=false else if(expanded)expanded=false else onDiscard()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconAction(PocketIcons.Back,stringResource(R.string.back),{if(expanded)expanded=false else onDiscard()},Modifier.testTag("preview_back"),enabled)
            if(clip.demo)Text(stringResource(R.string.badge_synthetic),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium) else Spacer(Modifier.weight(1f))
            IconAction(if(expanded)PocketIcons.FullscreenExit else PocketIcons.Fullscreen,stringResource(if(expanded)R.string.collapse_stage else R.string.expand_stage),{expanded=!expanded},Modifier.testTag("preview_expand"))
            Box {
                IconAction(PocketIcons.More,stringResource(R.string.more_actions),{menu=true},Modifier.testTag("preview_more"),enabled)
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem({Text(stringResource(R.string.hide_empty_frames))},
                        {menu=false;onFiltersChange(clip.frameFilters.copy(hideEmpty=!clip.frameFilters.hideEmpty))},
                        trailingIcon={if(clip.frameFilters.hideEmpty)Glyph(PocketIcons.Check,null)})
                    DropdownMenuItem({Text(stringResource(R.string.remove_duplicate_frames))},
                        {menu=false;onFiltersChange(clip.frameFilters.copy(removeDuplicates=!clip.frameFilters.removeDuplicates))},
                        trailingIcon={if(clip.frameFilters.removeDuplicates)Glyph(PocketIcons.Check,null)})
                    DropdownMenuItem({Text(stringResource(R.string.result_details))},{menu=false;details=true})
                    DropdownMenuItem({Text(stringResource(R.string.tpose))},
                            {menu=false;tPose=!tPose;playing=false})
                    DropdownMenuItem({Text(stringResource(R.string.camera_reset))},{menu=false;camera=ViewCamera(pitch=.12f,zoom=.86f)})
                    DropdownMenuItem({Text(stringResource(R.string.redo))},{menu=false;onRedo()})
                    DropdownMenuItem({Text(stringResource(R.string.privacy_and_auth))},{menu=false;privacy()})
                    if(!saved)DropdownMenuItem({Text(stringResource(R.string.discard))},{menu=false;onDiscard()})
                }
            }
        }
        val stage: @Composable (Modifier)->Unit = { modifier ->
            Box(modifier.clip(RoundedCornerShape(16.dp)).testTag("preview_stage")) {
                Column(Modifier.fillMaxSize()) {
                    val description=stringResource(R.string.stage_gestures)
                    val reset=stringResource(R.string.camera_reset)
                    val zoomIn=stringResource(R.string.zoom_in);val zoomOut=stringResource(R.string.zoom_out)
                    val left=stringResource(R.string.rotate_minus);val right=stringResource(R.string.rotate_plus)
                    val panLeft=stringResource(R.string.pan_left);val panRight=stringResource(R.string.pan_right)
                    val panUp=stringResource(R.string.pan_up);val panDown=stringResource(R.string.pan_down)
                    val tiltUp=stringResource(R.string.tilt_up);val tiltDown=stringResource(R.string.tilt_down)
                    val zoomState=stringResource(R.string.camera_zoom_state,(camera.zoom*100).roundToInt())
                    StageCanvas(points,camera,body,Modifier.fillMaxWidth().weight(if(multi)2f else 1f)
                        .pointerInput(id){detectTapGestures(onDoubleTap={camera=ViewCamera(pitch=.12f,zoom=.86f)})}
                        .pointerInput(id){awaitEachGesture {
                            awaitFirstDown(requireUnconsumed=false)
                            do {
                                val event=awaitPointerEvent();val delta=event.calculatePan();val zoom=event.calculateZoom()
                                val dx=delta.x/size.width.coerceAtLeast(1);val dy=delta.y/size.height.coerceAtLeast(1)
                                camera=if(event.changes.count{it.pressed}>=2)camera.copy(panX=(camera.panX+dx*2.5f).coerceIn(-3f,3f),panY=(camera.panY-dy*2.5f).coerceIn(-3f,3f)).zoomBy(zoom)
                                    else camera.copy(yaw=camera.yaw+dx*4f,pitch=(camera.pitch-dy*3f).coerceIn(-1.2f,1.2f))
                                if(delta.getDistance()>0f||zoom!=1f)event.changes.forEach{it.consume()}
                            }while(event.changes.any{it.pressed})
                        }}.semantics {
                            contentDescription=description
                            stateDescription=zoomState
                            customActions=listOf(
                                CustomAccessibilityAction(reset){camera=ViewCamera(pitch=.12f,zoom=.86f);true},
                                CustomAccessibilityAction(zoomIn){camera=camera.zoomBy(1.2f);true},
                                CustomAccessibilityAction(zoomOut){camera=camera.zoomBy(1/1.2f);true},
                                CustomAccessibilityAction(left){camera=camera.copy(yaw=camera.yaw-.3f);true},
                                CustomAccessibilityAction(right){camera=camera.copy(yaw=camera.yaw+.3f);true},
                                CustomAccessibilityAction(tiltUp){camera=camera.copy(pitch=(camera.pitch+.2f).coerceIn(-1.2f,1.2f));true},
                                CustomAccessibilityAction(tiltDown){camera=camera.copy(pitch=(camera.pitch-.2f).coerceIn(-1.2f,1.2f));true},
                                CustomAccessibilityAction(panLeft){camera=camera.copy(panX=(camera.panX-.2f).coerceIn(-3f,3f));true},
                                CustomAccessibilityAction(panRight){camera=camera.copy(panX=(camera.panX+.2f).coerceIn(-3f,3f));true},
                                CustomAccessibilityAction(panUp){camera=camera.copy(panY=(camera.panY+.2f).coerceIn(-3f,3f));true},
                                CustomAccessibilityAction(panDown){camera=camera.copy(panY=(camera.panY-.2f).coerceIn(-3f,3f));true})
                        })
                    if(multi)Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        StageCanvas(points,ViewCamera(pitch=.08f,zoom=.8f),body,Modifier.weight(1f).fillMaxHeight())
                        StageCanvas(points,ViewCamera(yaw=(PI/2).toFloat(),pitch=.08f,zoom=.8f),body,Modifier.weight(1f).fillMaxHeight())
                    }
                }
                val badge=when{tPose->stringResource(R.string.status_tpose);actual?.interpolated==true->stringResource(R.string.interpolated_short);pose==null->stringResource(R.string.no_pose_short);else->null}
                badge?.let{Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface.copy(alpha=.92f)){Text(it,Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}}
            }
        }
        val controls: @Composable ()->Unit = {
            if(!expanded)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(body,{body=true},{Text(stringResource(R.string.view_body))},Modifier.testTag("view_body"))
                FilterChip(!body,{body=false},{Text(stringResource(R.string.view_skeleton))},Modifier.testTag("view_skeleton"))
                FilterChip(multi,{multi=!multi},{Text(stringResource(R.string.view_multi))},Modifier.testTag("view_multi"))
            }
            if(!expanded)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(clip.frameFilters.hideEmpty,
                    {onFiltersChange(clip.frameFilters.copy(hideEmpty=!clip.frameFilters.hideEmpty))},
                    {Text(stringResource(R.string.hide_empty_frames))},Modifier.testTag("preview_hide_empty"),enabled=enabled)
                FilterChip(clip.frameFilters.removeDuplicates,
                    {onFiltersChange(clip.frameFilters.copy(removeDuplicates=!clip.frameFilters.removeDuplicates))},
                    {Text(stringResource(R.string.remove_duplicate_frames))},Modifier.testTag("preview_remove_duplicates"),enabled=enabled)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconAction(if(playing)PocketIcons.Pause else PocketIcons.Play,stringResource(if(playing)R.string.pause else R.string.play),{tPose=false;playing=!playing},Modifier.testTag("preview_play"),enabled&&selectedIds.isNotEmpty())
                val label=stringResource(R.string.frame_slider)
                val frameState=if(sourceFrame==null)stringResource(R.string.no_frames_after_filters)
                    else if(clip.frameFilters==FrameFilters())stringResource(R.string.frame_counter,viewFrame+1,selectedIds.size)
                    else stringResource(R.string.frame_filtered_counter,viewFrame+1,selectedIds.size,sourceFrame+1)
                Slider(viewFrame.toFloat(),{frame=it.roundToInt().coerceIn(0,(selectedIds.size-1).coerceAtLeast(0));playing=false;tPose=false},Modifier.weight(1f).testTag("preview_timeline").semantics{contentDescription=label;stateDescription=frameState},enabled=enabled&&selectedIds.isNotEmpty(),valueRange=0f..(selectedIds.size-1).coerceAtLeast(1).toFloat())
                Text(frameState,Modifier.padding(start=8.dp),style=MaterialTheme.typography.labelLarge)
            }
            if(!expanded)Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onSave,Modifier.weight(1f).heightIn(min=52.dp).testTag("preview_save"),enabled=enabled&&!saved,contentPadding=PaddingValues(10.dp)){Glyph(PocketIcons.Download,null);Spacer(Modifier.width(6.dp));Text(stringResource(if(saved)R.string.saved else R.string.save))}
                OutlinedButton({playing=false;onExport()},Modifier.weight(1f).heightIn(min=52.dp).testTag("preview_export"),enabled=enabled&&selectedIds.isNotEmpty(),contentPadding=PaddingValues(10.dp)){Glyph(PocketIcons.Share,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.share))}
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val dockMaxHeight=maxHeight*.55f
            if(!expanded && maxWidth>=600.dp && maxWidth>maxHeight) {
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    stage(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("preview_side_dock"),verticalArrangement=Arrangement.spacedBy(8.dp)){controls()}
                }
            } else {
                Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    stage(Modifier.fillMaxWidth().weight(1f))
                    Column(Modifier.fillMaxWidth().heightIn(max=dockMaxHeight).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){controls()}
                }
            }
        }
    }
    if(details)ModalBottomSheet(onDismissRequest={details=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.result_details),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.poses_available,clip.frames.count{it.joints!=null},clip.frameCount))
            Text(stringResource(R.string.compact_duration,clip.frameCount/clip.fps,selectedIds.size/clip.fps,clip.fps))
            if(clip.frameFilters!=FrameFilters())Text(stringResource(R.string.compact_duration_hint))
            Text(if(smooth)stringResource(R.string.preview_smoothed,continuity.count) else stringResource(R.string.preview_raw_only))
            if(continuity.count>0)Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.interpolate_gaps),Modifier.weight(1f));Switch(smooth,{smooth=it},Modifier.testTag("preview_smooth"))}
            Text(stringResource(R.string.body_caption),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.share_hint),style=MaterialTheme.typography.bodySmall)
            extras()
            TextButton({details=false},Modifier.testTag("details_close")){Text(stringResource(R.string.back))}
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StageCanvas(joints:List<Vec3>?,camera:ViewCamera,body:Boolean,
    modifier:Modifier) {
    val colors=PocketTheme.extras
    Canvas(modifier.clip(RoundedCornerShape(12.dp))){drawStage(camera,joints,if(body)StageMode.MANNEQUIN else StageMode.SKELETON,colors)}
}
