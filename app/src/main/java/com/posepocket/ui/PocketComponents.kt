package com.posepocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.posepocket.ProcessingStage
import com.posepocket.ProcessingProgress
import com.posepocket.ProcessingSubstep
import com.posepocket.R
import com.posepocket.pose.SavedProject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun Glyph(icon: ImageVector, description: String?, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Icon(icon, description, modifier.size(size), tint)
}

/** Icon button with a guaranteed 48dp touch target. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = LocalContentColor.current) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Icon(icon, label, Modifier.size(24.dp), tint)
    }
}

/** Small pill for status. Never relies on color alone: it always carries text. */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val clickable = if (onClick != null) Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.Button, onClick = onClick) else Modifier
    Surface(modifier.then(clickable), shape = CircleShape, color = color.copy(alpha = .14f), contentColor = color) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Glyph(icon, null, size = 16.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Large tappable card. `primary` is the single filled action of a screen. */
@Composable
fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val container = if (primary) scheme.primary.copy(alpha=.04f) else scheme.surface
    val content = scheme.onSurface
    Surface(
        modifier.clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = shape, color = container, contentColor = content.copy(alpha = if (enabled) 1f else .5f),
        border = BorderStroke(1.dp,if(primary)scheme.primary.copy(alpha=.45f) else scheme.onSurface.copy(alpha=.12f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp).heightIn(min=84.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Glyph(icon,null,size=36.dp)
            Text(title,style=MaterialTheme.typography.titleMedium)
        }
    }
}

/** Fixed-height status rows. Only directly measured substeps display a percentage. */
@Composable
fun StepList(current: ProcessingStage?, detail: String?, modifier: Modifier = Modifier, progress:ProcessingProgress?=null) {
    val labels=listOf(R.string.step_prepare,R.string.step_locate,R.string.step_generate)
    val currentIndex=when(current){ProcessingStage.PREPARE->0;ProcessingStage.LOCATE->1;ProcessingStage.SEND,ProcessingStage.GENERATE->2;null->-1}
    val done=stringResource(R.string.step_done)
    val active=stringResource(R.string.step_active)
    val pending=stringResource(R.string.step_pending)
    val scheme=MaterialTheme.colorScheme
    Column(modifier) {
        labels.forEachIndexed { index, label ->
            val state=when {
                currentIndex<0->2
                index<currentIndex->0
                index==currentIndex->1
                else->2
            }
            val steps=ProcessingSubstep.entries.filter { step ->
                when(index) {
                    0->step.stage==ProcessingStage.PREPARE
                    1->step.stage==ProcessingStage.LOCATE
                    else->step.stage==ProcessingStage.SEND||step.stage==ProcessingStage.GENERATE
                }
            }
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {
                stateDescription=when(state){0->done;1->active;else->pending}
            },horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.size(28.dp),contentAlignment=Alignment.Center) {
                        when(state) {
                            0->Box(Modifier.fillMaxSize().clip(CircleShape).background(scheme.primary),contentAlignment=Alignment.Center) {
                                Glyph(PocketIcons.Check,null,size=18.dp,tint=scheme.onPrimary)
                            }
                            1->CircularProgressIndicator(Modifier.fillMaxSize(),strokeWidth=3.dp)
                            else->Box(Modifier.fillMaxSize().border(2.dp,scheme.onSurface.copy(alpha=.3f),CircleShape))
                        }
                    }
                    if(index<labels.lastIndex) {
                        Box(Modifier.width(2.dp).height((steps.size*32+14).dp)
                            .background(if(state==0)scheme.primary else scheme.onSurface.copy(alpha=.2f)))
                    }
                }
                Column(Modifier.weight(1f).padding(top=3.dp)) {
                    Text(stringResource(label),style=MaterialTheme.typography.bodyLarge,
                        fontWeight=if(state==1)FontWeight.SemiBold else FontWeight.Normal,
                        color=if(state==2)scheme.onSurface.copy(alpha=.6f) else scheme.onSurface)
                    Column(Modifier.padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        steps.forEach { step ->
                            val isCurrent=step==progress?.step
                            val isDone=step.ordinal<(progress?.step?.ordinal?:-1)
                            Row(Modifier.fillMaxWidth().heightIn(min=28.dp).semantics(mergeDescendants=true) {
                                stateDescription=if(isCurrent)active else if(isDone)done else pending
                            },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(isDone)Glyph(PocketIcons.Check,null,size=14.dp,tint=scheme.primary)
                                else Box(Modifier.size(6.dp).background(if(isCurrent)scheme.primary else scheme.outline,CircleShape))
                                Text(stringResource(step.label),modifier=Modifier.weight(1f),
                                    fontWeight=if(isCurrent)FontWeight.SemiBold else FontWeight.Normal,
                                    style=MaterialTheme.typography.bodySmall,
                                    color=if(isCurrent)scheme.onSurface else scheme.onSurfaceVariant)
                                val percent=if(isCurrent)progress?.percent else null
                                if(percent!=null)Text(stringResource(R.string.progress_percent,percent),
                                    Modifier.width(48.dp).testTag("processing_percent"),
                                    textAlign=TextAlign.End,style=MaterialTheme.typography.labelMedium)
                                else Spacer(Modifier.width(48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class NoticeTone { ERROR, INFO }

/** Dismissible notice announced to TalkBack. Replaces the bare red text at the top of the screen. */
@Composable
fun InlineNotice(text: String, tone: NoticeTone, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (tone == NoticeTone.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(modifier.semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .12f)) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (tone == NoticeTone.ERROR) PocketIcons.Warning else PocketIcons.Info, null, size = 20.dp, tint = color)
            Text(text, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            IconAction(PocketIcons.Close, stringResource(R.string.dismiss), onDismiss, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Project tile. Shows only what the project really has: origin, date and saved frames are not invented. */
@Composable
fun ProjectCard(project: SavedProject, onOpen: () -> Unit, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val synthetic = project.demo
    val tone = if (synthetic) com.posepocket.ui.theme.PocketTheme.extras.synthetic else scheme.primary
    Surface(
        modifier.clip(shape).clickable(role = Role.Button, onClick = onOpen),
        shape = shape, color = scheme.onSurface.copy(alpha = .06f), border = BorderStroke(1.dp, scheme.onSurface.copy(alpha = .12f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(com.posepocket.ui.theme.PocketTheme.extras.stage), contentAlignment = Alignment.Center) {
                Glyph(PocketIcons.Skeleton, null, size = 36.dp, tint = com.posepocket.ui.theme.PocketTheme.extras.stageBone)
            }
            StatusChip(stringResource(if (synthetic) R.string.badge_synthetic else R.string.badge_real), tone)
            val duration=project.durationSeconds
            val frames=project.frameCount
            if(duration!=null && duration.isFinite() && duration>0 && frames!=null && frames>0) {
                val seconds=duration.toLong()
                val clock="${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
                Text(pluralStringResource(R.plurals.project_summary,frames,clock,frames),style=MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatProjectDate(project.createdAt), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (onDelete != null) IconAction(PocketIcons.Delete, stringResource(R.string.delete), onDelete)
            }
        }
    }
}

/** Human date such as "Oct 15, 2026, 2:32 PM"; falls back to the stored text if it cannot be parsed. */
fun formatProjectDate(createdAt: String): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(createdAt))
} catch (_: Exception) { createdAt }
