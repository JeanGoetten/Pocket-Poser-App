package com.posepocket.ui

import androidx.compose.ui.res.stringResource
import com.posepocket.R
import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.posepocket.PocketViewModel
import com.posepocket.media.CaptureStore
import com.posepocket.media.RecordVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.File

@Composable
fun Workspace(model:PocketViewModel) {
    val state by model.state.collectAsState()
    val keyConfigured by model.keyConfigured.collectAsState()
    val initialized by model.initialized.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val libraryScroll=rememberLazyGridState()
    val captures=remember { CaptureStore(context) }
    val cameraAppAvailable=remember { captures.cameraAppAvailable(context.packageManager) }
    // Name of the file the system camera is writing; survives rotation and process death.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var nvidiaSettings by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var redoAfterDiscard by remember { mutableStateOf(false) }
    var discardProcessing by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<String?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var exportTemporary by remember { mutableStateOf(false) }
    val recorder=rememberLauncherForActivityResult(RecordVideo()) { recorded ->
        val file=pendingCapture?.let(captures::fileNamed);pendingCapture=null
        when {
            file==null -> model.report(context.getString(R.string.record_receive_failed))
            !recorded -> captures.discard(file)
            !file.exists()||file.length()==0L -> {captures.discard(file);model.report(context.getString(R.string.record_no_video))}
            model.gate.isGranted() -> model.review(captures.uriFor(file),"camera")
            else -> {captures.discard(file);model.report(context.getString(R.string.consent_invalid_no_video))}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            if(model.gate.isGranted()) {
                try{context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){}
                model.review(uri,"attachment")
            }
            else model.report(context.getString(R.string.consent_invalid_no_video))
        }
    }
    val exportResult:(android.net.Uri?)->Unit={ uri ->
        val original=exportFile;exportFile=null;val remove=exportTemporary;exportTemporary=false
        if(uri!=null&&original!=null)scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    model.gate.withConsent { }
                    context.contentResolver.openOutputStream(uri,"wt")?.use { out ->
                        original.inputStream().use { input ->
                            val buffer=ByteArray(65536)
                            while(true) { model.gate.withConsent { };val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n) }
                        }
                    }?:error(context.getString(R.string.export_provider_unavailable))
                }
            }catch(_:Exception) {
                withContext(NonCancellable+Dispatchers.IO){try{DocumentsContract.deleteDocument(context.contentResolver,uri)}catch(_:Exception){}}
                model.report(context.getString(R.string.export_failed))
            }finally{if(remove)original.delete()}
        }
        else if(remove)original?.delete()
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson"),exportResult)
    val layerExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),exportResult)
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        state.error?.let { InlineNotice(it,NoticeTone.ERROR,model::dismissNotice) }
        state.message?.let { InlineNotice(it,NoticeTone.INFO,model::dismissNotice) }
        when {
            state.review!=null -> ReviewHost(model,requireNotNull(state.review))
            state.busy&&state.clip==null -> ProcessingScreen(state.phase,state.stage,canCancel=state.stage!=null,onCancel={model.cancel()},progress=state.progress)
            state.clip!=null -> {
                val clip=requireNotNull(state.clip)
                if(state.busy){Text(state.phase);LinearProgressIndicator(Modifier.fillMaxWidth())}
                val reset={redoAfterDiscard=false;if(state.savedId==null)discard=true else model.resetPreview()}
                val redo={redoAfterDiscard=true;if(state.savedId==null)discard=true else model.resetPreview(toEntry=true)}
                PosePreview(clip,state.savedId!=null,onSave={model.save()},onDiscard=reset,onRedo=redo,onExport={
                    try { model.gate.withConsent { }
                        scope.launch {
                            val shared=File(context.cacheDir,"shared/${java.util.UUID.randomUUID()}/pocket-poser-original.jsonl")
                            var launched=false
                            try {
                                withContext(Dispatchers.IO){
                                    shared.parentFile!!.mkdirs()
                                    if(clip.frameFilters==com.posepocket.pose.FrameFilters()) {
                                        clip.raw.inputStream().use{input->shared.outputStream().use{out->
                                            val buffer=ByteArray(65536)
                                            while(true){currentCoroutineContext().ensureActive();model.gate.withConsent{};val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}
                                        }}
                                    }else {
                                        val exportJob=currentCoroutineContext()[kotlinx.coroutines.Job]
                                        com.posepocket.pose.exportFrameSelection(clip,shared) {
                                            exportJob?.ensureActive()
                                            model.gate.withConsent{}
                                        }
                                    }
                                }
                                model.gate.withConsent {
                                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.captures",shared)
                                    val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/x-ndjson")
                                        .putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    intent.clipData=android.content.ClipData.newRawUri("Pocket Poser",uri)
                                    context.startActivity(android.content.Intent.createChooser(intent,context.getString(R.string.share)))
                                    launched=true
                                }
                            }catch(error:kotlinx.coroutines.CancellationException){throw error}
                            catch(_:Exception){model.report(context.getString(R.string.export_failed))}
                            finally{if(!launched)shared.parentFile?.deleteRecursively()}
                        }
                    }
                    catch(_:Exception){model.report(context.getString(R.string.consent_invalid_review))}
                },enabled=!state.busy,extras={
                    if(state.editing)EditControls(model,!state.busy)
                    else TextButton(enabled=!state.busy,onClick=model::startEditing){Text(stringResource(R.string.enable_advanced_edit))}
                    clip.correctionLayer?.let { layer -> TextButton(enabled=!state.busy,onClick={scope.launch {
                    try {
                        model.gate.withConsent { }
                        val file=withContext(Dispatchers.IO) {
                            File(context.cacheDir,"results").apply { mkdirs() }.let { File(it,"layer-${java.util.UUID.randomUUID()}.json") }.apply { writeText(layer) }
                        }
                        exportFile=file;exportTemporary=true;layerExporter.launch("pocket-poser-corrections.json")
                    }catch(_:Exception){model.report(context.getString(R.string.layer_export_failed))}
                }}){Text(stringResource(R.string.export_layer))} }
                },onFiltersChange=model::setFrameFilters)
            }
            state.recovery!=null&&!state.library -> ProcessingRecovery(requireNotNull(state.recovery),model::retryProcessing,
                {discardProcessing=true},{nvidiaSettings=true},{model.showLibrary(true)})
            state.library -> LibraryScreen(state.projects,onBack={model.showLibrary(false)},onOpen=model::open,onDelete={delete=it},onDeleteAll={deleteAll=true},gridState=libraryScroll)
            else -> EntryScreen(
                enabled=initialized,cameraAvailable=cameraAppAvailable,keyConfigured=keyConfigured,projectCount=state.projects.size,
                onAttach={
                    try { model.gate.withConsent { picker.launch(arrayOf("video/*")) } }
                    catch(_:Exception){model.report(context.getString(R.string.consent_invalid_picker))}
                },
                onRecord={
                    val allowed=try { model.gate.withConsent { }; true }
                    catch(_:Exception){model.report(context.getString(R.string.consent_invalid_camera));false}
                    if(allowed) {
                        captures.purgeStale()
                        val capture=captures.create();pendingCapture=capture.name
                        try { recorder.launch(capture.uri) }
                        catch(_:ActivityNotFoundException){pendingCapture=null;captures.discard(capture.file);model.report(context.getString(R.string.no_camera_launch))}
                    }
                },
                onKey={nvidiaSettings=true},onDemo={model.demo()},onLibrary={model.showLibrary(true)},
            )
        }
    }
    if(nvidiaSettings)NvidiaKeyPanel(onDismiss={nvidiaSettings=false;model.refreshKeyStatus()})
    if(discardProcessing)AlertDialog(onDismissRequest={discardProcessing=false},title={Text(stringResource(R.string.discard_title))},
        text={Text(stringResource(R.string.discard_pending_body))},
        confirmButton={TextButton(onClick={discardProcessing=false;model.discardProcessing()}){Text(stringResource(R.string.discard_confirm))}},
        dismissButton={TextButton(onClick={discardProcessing=false}){Text(stringResource(R.string.cancel))}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text(stringResource(R.string.discard_title))},
        text={Text(stringResource(R.string.discard_body))},
        confirmButton={TextButton(onClick={discard=false;model.resetPreview(toEntry=redoAfterDiscard)}){Text(stringResource(R.string.discard_confirm))}},
        dismissButton={TextButton(onClick={discard=false}){Text(stringResource(R.string.keep_preview))}})
    if(delete!=null||deleteAll)AlertDialog(onDismissRequest={delete=null;deleteAll=false},title={Text(stringResource(R.string.delete_title))},
        text={Text(stringResource(if(deleteAll)R.string.delete_body_all else R.string.delete_body_one))},
        confirmButton={TextButton(onClick={if(deleteAll)model.clearProjects()else delete?.let(model::deleteProject);delete=null;deleteAll=false}){Text(stringResource(R.string.delete))}},
        dismissButton={TextButton(onClick={delete=null;deleteAll=false}){Text(stringResource(R.string.cancel))}})
}
