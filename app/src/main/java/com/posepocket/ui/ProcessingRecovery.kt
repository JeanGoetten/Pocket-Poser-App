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
fun ProcessingRecovery(stage:RecoveryStage,onRetry:()->Unit,onDiscard:()->Unit,onKey:()->Unit,onLibrary:()->Unit={}) {
    val privacy=LocalOpenPrivacy.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconAction(PocketIcons.Lock,stringResource(R.string.privacy_and_auth),privacy)}
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.recovery_title),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(when(stage){RecoveryStage.INVALID->R.string.recovery_invalid;RecoveryStage.NVIDIA->R.string.recovery_remote;else->R.string.recovery_local}))
        if(stage!=RecoveryStage.INVALID){
            Button(onRetry,Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("recovery_retry")){Text(stringResource(if(stage==RecoveryStage.NVIDIA)R.string.recovery_resend else R.string.recovery_continue))}
            TextButton(onKey){Text(stringResource(R.string.recovery_key))}
        }
        OutlinedButton(onDiscard,Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("recovery_discard")){Text(stringResource(R.string.recovery_discard))}
        TextButton(onLibrary){Text(stringResource(R.string.library_title))}
    }
}
