package com.posepocket.ui

import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.posepocket.privacy.ConsentGate
import com.posepocket.R
import com.posepocket.ui.theme.PocketPoserTheme

val LocalOpenPrivacy = staticCompositionLocalOf<()->Unit> { {} }

@Composable
fun PocketPoserApp(gate: ConsentGate, onExit: () -> Unit, onRevoke:()->Unit={}, ready:@Composable ()->Unit={ Text(stringResource(R.string.input_title)) }, showChrome:Boolean=true) {
    var granted by remember { mutableStateOf(gate.isGranted()) }
    var declined by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    var remoteOptIn by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current

    PocketPoserTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal=16.dp,vertical=8.dp),
            ) {
                if (showChrome||!granted) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        IconAction(PocketIcons.Lock,stringResource(R.string.privacy_and_auth),{privacy=true})
                    }
                }
                if (granted) {
                    CompositionLocalProvider(LocalOpenPrivacy provides { privacy=true }) { ready() }
                } else {
                    Text(stringResource(R.string.privacy_first), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.locked_notice))
                    if (declined) {
                        Text(stringResource(R.string.declined_notice))
                        Button(onClick = { declined = false; error = null }) { Text(stringResource(R.string.review_decision)) }
                        TextButton(onClick = { reading = true }) { Text(stringResource(R.string.read_policy)) }
                        OutlinedButton(onClick = onExit) { Text(stringResource(R.string.exit)) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        if (!granted && !declined && !reading) {
            ConsentDialog(
                onRead = { reading = true },
                onDecline = { declined = true },
                onAccept = { notice, remote ->
                    try {
                        gate.accept(notice, remote)
                        granted = gate.isGranted()
                        error = null
                    } catch (_: Exception) {
                        granted = false
                        error = context.getString(R.string.accept_save_failed)
                    }
                },
                error = error,
            )
        }

        if (reading || privacy) {
            Dialog(
                onDismissRequest = { reading = false; privacy = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.titleLarge)
                        val configuration = LocalConfiguration.current
                        val localizedPolicy = remember(configuration) {
                            context.resources.openRawResource(R.raw.privacy_policy_display).bufferedReader().use { it.readText() }
                        }
                        Text(localizedPolicy)
                        gate.receipt()?.let {
                            Text(stringResource(R.string.receipt_info,it.policyVersion,it.acceptedAt))
                        }
                        if (privacy && granted) {
                            if (!gate.isRemoteAllowed()) {
                                Text(stringResource(R.string.engine_remote_permission_hint))
                                ConsentCheck(stringResource(R.string.consent_check_remote),remoteOptIn) {remoteOptIn=it}
                                Button(enabled=remoteOptIn,onClick={
                                    try {
                                        gate.accept(true,true)
                                        remoteOptIn=false
                                        privacy=false
                                        error=null
                                    }catch(_:Exception) {
                                        error=context.getString(R.string.accept_save_failed)
                                    }
                                }) {Text(stringResource(R.string.engine_remote_permission_enable))}
                            }
                            Text(stringResource(R.string.revoke_notice))
                            Button(onClick = {
                                // Block UI immediately, including when durable storage fails.
                                granted = false
                                privacy = false
                                declined = true
                                onRevoke()
                                try {
                                    gate.revoke()
                                    error = null
                                } catch (_: Exception) {
                                    error = context.getString(R.string.revoke_failed)
                                }
                            }) { Text(stringResource(R.string.revoke_button)) }
                        }
                        TextButton(onClick = { reading = false; privacy = false }) { Text(stringResource(R.string.back)) }
                        TextButton(onClick={licenses=true}){Text(stringResource(R.string.licenses_title))}
                    }
                }
            }
        }
        if(licenses)Dialog(onDismissRequest={licenses=false}) {
            Surface(shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.licenses_title),style=MaterialTheme.typography.titleLarge)
                    val text=remember { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
                    Text(text)
                    TextButton(onClick={licenses=false}){Text(stringResource(R.string.back))}
                }
            }
        }
    }
}

@Composable
private fun ConsentDialog(
    onRead: () -> Unit,
    onDecline: () -> Unit,
    onAccept: (Boolean, Boolean) -> Unit,
    error: String?,
) {
    // Deliberately not saveable: after recreation, never restore checked boxes without a durable receipt.
    var notice by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = onDecline)
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.consent_p1))
                Text(stringResource(R.string.consent_p2))
                Text(stringResource(R.string.consent_p3))
                TextButton(onClick = onRead) { Text(stringResource(R.string.read_policy)) }
                ConsentCheck(stringResource(R.string.consent_check_notice), notice) { notice = it }
                ConsentCheck(stringResource(R.string.consent_check_remote), remote) { remote = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onAccept(notice, remote) }, enabled = notice,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.accept_continue)) }
                OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.decline)) }
            }
        }
    }
}

@Composable
private fun ConsentCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}
