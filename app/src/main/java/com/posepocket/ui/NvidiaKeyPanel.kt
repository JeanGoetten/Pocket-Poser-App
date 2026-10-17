package com.posepocket.ui

import androidx.compose.ui.res.stringResource
import com.posepocket.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.posepocket.gateway.NvidiaKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NvidiaKeyPanel(onDismiss:()->Unit) {
    val context=LocalContext.current
    val store=remember { NvidiaKeyStore(context.noBackupFilesDir) }
    val scope=rememberCoroutineScope()
    // Never restore the key through saved instance state or prefill a saved credential.
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var terms by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        saved=withContext(Dispatchers.IO){store.read()!=null}
        busy=false
    }
    DisposableEffect(Unit) { onDispose { input="" } }
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn)) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.key_intro))
                Text(if(saved)stringResource(R.string.key_status_saved) else stringResource(R.string.key_status_none))
                TextButton(onClick={details=!details}){Text(stringResource(R.string.key_connection_details))}
                if(details){
                    Text(stringResource(R.string.key_model,NvidiaKeyStore.MODEL))
                    Text(stringResource(R.string.key_endpoint,NvidiaKeyStore.ENDPOINT))
                }
                OutlinedTextField(value=input,onValueChange={input=it;message=null},
                    label={Text(if(saved)stringResource(R.string.key_field_new) else stringResource(R.string.key_field))},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                    modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.key_storage_notice))
                Text(stringResource(R.string.key_direct_notice))
                Row {
                    Checkbox(checked=terms,onCheckedChange={terms=it},enabled=!busy)
                    Text(stringResource(R.string.key_terms_check))
                }
                message?.let { Text(it) }
                Button(enabled=!busy&&input.isNotBlank()&&terms,onClick={
                    try { NvidiaKeyStore.validate(input) }
                    catch(_:IllegalArgumentException) { message=context.getString(R.string.key_invalid);return@Button }
                    val value=input
                    busy=true
                    scope.launch {
                        try {
                            withContext(NonCancellable+Dispatchers.IO){store.write(value)}
                            saved=true;input="";terms=false;message=context.getString(R.string.key_saved_msg)
                        }catch(_:Exception){message=context.getString(R.string.key_save_failed)}
                        finally{busy=false}
                    }
                }){Text(if(saved)stringResource(R.string.key_replace) else stringResource(R.string.key_save))}
                if(saved)OutlinedButton(enabled=!busy,onClick={deleting=true}){Text(stringResource(R.string.key_delete))}
                TextButton(enabled=!busy,onClick={input="";onDismiss()}){Text(stringResource(R.string.close))}
            }
        }
    }
    if(deleting)AlertDialog(onDismissRequest={if(!busy)deleting=false},title={Text(stringResource(R.string.key_delete_title))},
        text={Text(stringResource(R.string.key_delete_body))},
        confirmButton={TextButton(enabled=!busy,onClick={
            busy=true
            scope.launch {
                try {
                    withContext(NonCancellable+Dispatchers.IO){store.clear()}
                    saved=false;input="";deleting=false;message=context.getString(R.string.key_deleted_msg)
                }catch(_:Exception){message=context.getString(R.string.key_delete_failed);deleting=false}
                finally{busy=false}
            }
        }){Text(stringResource(R.string.key_delete))}},
        dismissButton={TextButton(enabled=!busy,onClick={deleting=false}){Text(stringResource(R.string.cancel))}})
}
