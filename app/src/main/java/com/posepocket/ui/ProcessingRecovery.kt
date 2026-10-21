package com.posepocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.posepocket.R
import com.posepocket.nvidia.RecoveryStage

@Composable
fun ProcessingRecovery(stage:RecoveryStage,onRetry:()->Unit,onDiscard:()->Unit,onKey:()->Unit,onLibrary:()->Unit={},tracking:com.posepocket.nvidia.TrackingSummary?=null) {
    val privacy=LocalOpenPrivacy.current
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconAction(PocketIcons.Lock,stringResource(R.string.privacy_and_auth),privacy)}
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recovery_title),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(when(stage){RecoveryStage.INVALID->R.string.recovery_invalid;RecoveryStage.NVIDIA->R.string.recovery_remote;else->R.string.recovery_local}))
            if(stage==RecoveryStage.TRACK)Text(stringResource(R.string.recovery_tracking))
            tracking?.let {
                Text(stringResource(R.string.tracking_summary,it.percent,it.covered,it.frames,it.longestGap))
                if(it.percent<80)Text(stringResource(R.string.tracking_low_coverage))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val retry: @Composable (Modifier)->Unit = { button ->
                Button(onRetry,button.heightIn(min=52.dp).testTag("recovery_retry")){Text(stringResource(if(stage==RecoveryStage.NVIDIA)R.string.recovery_resend else R.string.recovery_continue))}
            }
            val discard: @Composable (Modifier)->Unit = { button ->
                OutlinedButton(onDiscard,button.heightIn(min=52.dp).testTag("recovery_discard")){Text(stringResource(R.string.recovery_discard))}
            }
            if(maxWidth>=600.dp)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(stage!=RecoveryStage.INVALID)retry(Modifier.weight(1f))
                discard(Modifier.weight(1f))
            }else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(stage!=RecoveryStage.INVALID)retry(Modifier.fillMaxWidth())
                discard(Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            if(stage!=RecoveryStage.INVALID)TextButton(onKey){Text(stringResource(R.string.recovery_key))}
            TextButton(onLibrary){Text(stringResource(R.string.library_title))}
        }
    }
}
