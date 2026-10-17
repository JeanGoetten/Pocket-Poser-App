package com.posepocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.posepocket.ProcessingStage
import com.posepocket.ProcessingProgress
import com.posepocket.R
import com.posepocket.pose.SavedProject
import com.posepocket.ui.theme.PocketTheme

/** Entry: attaching a video is the one primary action; recording with the camera app is secondary (D-15). */
@Composable
fun EntryScreen(
    enabled: Boolean,
    cameraAvailable: Boolean,
    keyConfigured: Boolean,
    projectCount: Int,
    onAttach: () -> Unit,
    onRecord: () -> Unit,
    onKey: () -> Unit,
    onDemo: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(36.dp))
        ActionCard(
            PocketIcons.VideoLibrary, stringResource(R.string.attach_video), stringResource(R.string.entry_attach_subtitle),
            onAttach, Modifier.fillMaxWidth().testTag("entry_attach"), primary = true, enabled = enabled,
        )
        ActionCard(
            PocketIcons.Camera, stringResource(R.string.record_with_camera), stringResource(R.string.entry_record_subtitle),
            onRecord, Modifier.fillMaxWidth().testTag("entry_record"), enabled = enabled && cameraAvailable,
        )
        if (!cameraAvailable) {
            Text(stringResource(R.string.no_camera_found), style = MaterialTheme.typography.bodySmall, color = scheme.onSurface.copy(alpha = .7f))
        }
        // Never color alone: the chip always carries text, and the missing state is the tappable one.
        StatusChip(
            stringResource(if (keyConfigured) R.string.key_chip_ready else R.string.key_chip_missing),
            if (keyConfigured) scheme.primary else PocketTheme.extras.warning,
            Modifier.align(Alignment.CenterHorizontally).testTag("entry_key"), PocketIcons.Key, onClick = onKey,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDemo, enabled = enabled, modifier = Modifier.testTag("entry_demo")) { Text(stringResource(R.string.open_demo)) }
        }
        TextButton(onClick = onLibrary, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("entry_library")) {
            Glyph(PocketIcons.Folder, null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_open, projectCount))
        }
    }
}

/** Scrollable substeps with measured progress; cancellation stays fixed at the bottom. */
@Composable
fun ProcessingScreen(
    phase: String,
    stage: ProcessingStage?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    progress:ProcessingProgress?=null,
) {
    BackHandler(enabled=canCancel){onCancel()}
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        val privacy=LocalOpenPrivacy.current
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconAction(PocketIcons.Lock,stringResource(R.string.privacy_and_auth),privacy)}
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=8.dp,vertical=16.dp),verticalArrangement=Arrangement.Center) {
            if(stage!=null)StepList(stage,null,Modifier.testTag("processing_steps"),progress)
            if(stage==null){CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally));if(phase.isNotBlank())Text(phase,style=MaterialTheme.typography.bodyMedium,color=scheme.onSurface.copy(alpha=.8f))}
        }
        if (canCancel) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("processing_cancel")) {
                Text(stringResource(R.string.pause_processing_short))
            }
        }
    }
}

/** Compose only visible tiles; wider text uses fewer columns without shrinking touch targets. */
@Composable
fun LibraryScreen(
    projects: List<SavedProject>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    BackHandler{onBack()}
    val scheme = MaterialTheme.colorScheme
    val minTileWidth=if(LocalDensity.current.fontScale>1.3f)240.dp else 156.dp
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(PocketIcons.Back, stringResource(R.string.back), onBack, Modifier.testTag("library_back"))
            Text(stringResource(R.string.library_title), Modifier.weight(1f).semantics{heading()}, style = MaterialTheme.typography.titleLarge)
        }
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                Glyph(PocketIcons.Folder,null,size=64.dp,tint=scheme.primary)
                Text(stringResource(R.string.library_empty),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("library_empty"),textAlign=TextAlign.Center)
                Text(stringResource(R.string.library_empty_hint),style=MaterialTheme.typography.bodyMedium,color=scheme.onSurface.copy(alpha=.7f),textAlign=TextAlign.Center)
                Button(onBack,Modifier.heightIn(min=48.dp).testTag("library_new")){Text(stringResource(R.string.new_project))}
            }
        } else {
            LazyVerticalGrid(
                columns=GridCells.Adaptive(minTileWidth),
                state=gridState,
                modifier=Modifier.fillMaxWidth().weight(1f).testTag("library_grid"),
                contentPadding=PaddingValues(bottom=16.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),
            ) {
                items(projects,key={it.id}) { project ->
                    ProjectCard(project,{onOpen(project.id)},Modifier.testTag("project_${project.id}"),onDelete={onDelete(project.id)})
                }
                item(key="delete-all",span={GridItemSpan(maxLineSpan)}) {
                    TextButton(onClick=onDeleteAll,modifier=Modifier.testTag("library_delete_all")){Text(stringResource(R.string.delete_all))}
                }
            }
        }
    }
}
