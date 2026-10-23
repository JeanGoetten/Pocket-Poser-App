package com.posepocket

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.posepocket.gateway.*
import com.posepocket.nvidia.*
import com.posepocket.media.CaptureStore
import com.posepocket.media.MediaPreparer
import com.posepocket.media.TrimRange
import com.posepocket.media.ReviewDraftStore
import com.posepocket.pose.*
import com.posepocket.privacy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class PocketState(
    val busy:Boolean=false,val phase:String="",val clip:PoseClip?=null,val error:String?=null,
    val savedId:String?=null,val projects:List<SavedProject> = emptyList(),val message:String?=null,
    val editing:Boolean=false,
    val recovery:RecoveryStage?=null,
    val stage:ProcessingStage?=null,
    val progress:ProcessingProgress?=null,
    val library:Boolean=false,
    val review:ReviewVideo?=null,
    val tracking:TrackingSummary?=null,
    val previewIdentity:String?=null,
)

class PocketViewModel @JvmOverloads constructor(application:Application,
    processingFactory:((ConsentGate,PendingProcessingStore)->ResumableProcessing)?=null):AndroidViewModel(application) {
    private val context=application
    private val captures=CaptureStore(application)
    val gate=ConsentGate(CurrentPolicy.create(application),AtomicConsentStore(application.noBackupFilesDir))
    private val keyStore=NvidiaKeyStore(application.noBackupFilesDir)
    private val repository=ProjectRepository(File(application.noBackupFilesDir,"projects"))
    private val client=DirectNvidiaClient(gate,File(application.cacheDir,"results"))
    private val completed=CompletedResultStore(File(application.noBackupFilesDir,"unsaved-result"))
    private val pending=PendingProcessingStore(File(application.noBackupFilesDir,"processing"))
    private val reviewDraft=ReviewDraftStore(application.noBackupFilesDir)
    private val processing=processingFactory?.invoke(gate,pending)?:ResumableProcessing(gate,pending,
        { uri,source -> val current=generation;try { MediaPreparer(context,gate).prepare(uri,source,trimRange,focusWindow){updatePhase(it,current)} } finally { trimRange=null;focusWindow=null } },
        { input,phase -> DeviceVideoPipeline(gate).prepare(input,phase) },
        { video,input,phase ->
            val key=keyStore.read()?:error(context.getString(R.string.key_required_prepared))
            client.infer(key,video,input,phase)
        },
        encode={input,phase->DeviceVideoPipeline(gate).normalizeVideo(input,phase)},
        locate={video,phase->DeviceVideoPipeline(gate).trackVideo(video,phase)},
        reviewTracking={video->if(TrackingSummary.from(video).percent<80)throw TrackingReviewRequired()})
    private val _keyConfigured=MutableStateFlow(false)
    val keyConfigured=_keyConfigured.asStateFlow()
    private val _initialized=MutableStateFlow(false)
    val initialized=_initialized.asStateFlow()
    private val _state=MutableStateFlow(PocketState())
    val state=_state.asStateFlow()
    private var work:Job?=null
    @Volatile private var trimRange:TrimRange?=null
    @Volatile private var focusWindow:com.posepocket.media.FocusWindow?=null
    @Volatile private var generation=0
    private var editor:PoseEditor?=null
    private val frameFilterWriteLock=Mutex()
    val canUndo get()=editor?.canUndo==true
    val canRedo get()=editor?.canRedo==true

    init {
        // A new ViewModel represents a new process/session; rotations retain the existing instance.
        listOf("media","results","shared").forEach { File(application.cacheDir,it).deleteRecursively() }
        // Recordings from the system camera may still be pending after process death: purge by age, not on every start.
        captures.purgeStale(protectedUri=reviewDraft.load()?.uri)
        viewModelScope.launch {
            _keyConfigured.value=withContext(Dispatchers.IO){keyStore.read()!=null}
            withContext(Dispatchers.IO){
                if(gate.isRevoked()){pending.clear();completed.clear();discardReviewDraft()}
                else try { completed.load()?.let { clip ->
                    pending.clear()
                    _state.update { it.copy(clip=clip) }
                } }catch(_:Exception){report(context.getString(R.string.project_corrupt))}
            }
            restorePending()
            if(gate.isGranted()&&_state.value.recovery==null&&_state.value.clip==null) {
                val draft=withContext(Dispatchers.IO){reviewDraft.load()}
                _state.update { it.copy(review=draft) }
            }else if(_state.value.recovery!=null)reviewDraft.clear()
            refreshProjects()
            _initialized.value=true
        }
    }

    fun submit(uri:Uri,source:String,trim:TrimRange?=null,focus:com.posepocket.media.FocusWindow?=null) {
        if(_state.value.busy) {
            if(source=="camera") captures.discard(uri)
            return
        }
        if(_state.value.recovery!=null) {
            if(source=="camera") captures.discard(uri)
            report(context.getString(R.string.processing_pending))
            return
        }
        beginProcessing(uri,source,trim,focus)
    }

    fun retryProcessing() {
        if(_state.value.busy || _state.value.recovery==null)return
        beginProcessing(null,null,null,null)
    }

    private fun beginProcessing(uri:Uri?,source:String?,trim:TrimRange?,focus:com.posepocket.media.FocusWindow?) {
        try { gate.withConsent { } } catch(_:Exception) {
            if(source=="camera"&&uri!=null) captures.discard(uri)
            report(context.getString(R.string.consent_review))
            return
        }
        // Every real-video inference uses NVIDIA. Remote consent is mandatory
        // Remote permission cannot be bypassed by an earlier installation.
        if(!gate.isRemoteAllowed()) {
            report(context.getString(R.string.engine_remote_consent_required))
            return
        }
        // Set only once processing really starts, so a refused submit or a retry can never reuse a stale range.
        trimRange=trim
        focusWindow=focus
        val current=++generation
        _state.update { it.copy(busy=true,phase=context.getString(R.string.phase_preparing),error=null,message=null,stage=ProcessingStage.PREPARE,progress=ProcessingProgress(ProcessingSubstep.IMPORT)) }
        work=viewModelScope.launch {
            var checkpointCommitted=uri==null
            try {
                val phase:(String)->Unit={ message ->
                    updatePhase(message,current)
                }
                val clip=withContext(Dispatchers.IO) {
                    val result=processing.run(uri,source,phase) { stage ->
                        checkpointCommitted=true
                        releaseReviewGrant()
                        reviewDraft.clear()
                        if(current==generation)_state.update { it.copy(recovery=stage) }
                    }
                    // Commit before crossing the cancellable IO→UI boundary.
                    val saved=withContext(NonCancellable) { completed.save(result).also { pending.clear() } }
                    checkpointCommitted=true
                    releaseReviewGrant()
                    reviewDraft.clear()
                    saved
                }
                ensureActive()
                if(current==generation) {
                    _state.update { it.copy(busy=false,clip=clip,phase=context.getString(R.string.phase_ready),error=null,recovery=null,stage=null) }
                }
            } catch(error:TrackingReviewRequired) {
                if(current==generation) {
                    restorePending()
                    _state.update { it.copy(busy=false,error=null,phase="",stage=null) }
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                if(current==generation) {
                    restorePending()
                    _state.update { it.copy(busy=false,error=localizeUiMessage(context,error.message?:context.getString(R.string.processing_failed)),phase="",stage=null) }
                }
            } finally {
                // Prepared/normalized files are owned by ResumableProcessing and PendingProcessingStore.
                // Do not delete them here; retries and process-death recovery depend on them.
                if(checkpointCommitted) {
                    if(source=="camera"&&uri!=null)captures.discard(uri)
                    reviewDraft.clear()
                }else if(current==generation) {
                    _state.update { it.copy(review=reviewDraft.load()) }
                }
            }
        }
    }
    private fun updatePhase(message:String,current:Int) {
        if(current!=generation)return
        val progress=ProcessingProgress.fromMessage(message)
        val next=progress?.step?.stage?:ProcessingStage.fromMessage(message)
        _state.update { state ->
            if(current!=generation||(next!=null&&state.stage!=null&&next.ordinal<state.stage.ordinal))state else
                state.copy(phase=localizeUiMessage(context,message),stage=next?:state.stage,progress=progress?:state.progress)
        }
    }
    private suspend fun restorePending() {
        val (stage,summary)=withContext(Dispatchers.IO) {
            try { val job=pending.load();job?.stage to job?.video?.let(TrackingSummary::from) }
            catch(_:Exception){RecoveryStage.INVALID to null}
        }
        _state.update { it.copy(recovery=stage,tracking=summary) }
    }

    fun demo() {
        if(_state.value.busy||_state.value.recovery!=null)return
        try { gate.withConsent { } }catch(_:Exception){return}
        val current=++generation
        resetPreview()
        _state.update { it.copy(busy=true,phase=context.getString(R.string.phase_demo),error=null) }
        work=viewModelScope.launch {
            try {
                val clip=withContext(Dispatchers.IO) {
                    val folder=File(context.cacheDir,"results").apply { mkdirs() }
                    syntheticDemo(File(folder,"${UUID.randomUUID()}.jsonl"))
                }
                ensureActive();gate.withConsent { }
                if(current==generation)_state.update { it.copy(busy=false,clip=clip,phase=context.getString(R.string.phase_ready)) }
            }catch(error:CancellationException){throw error}
            catch(error:Exception){if(current==generation)failWork(error.message?:context.getString(R.string.demo_failed))}
        }
    }

    fun cancel()=stopProcessing(false)
    fun discardProcessing()=stopProcessing(true)
    private fun stopProcessing(discard:Boolean) {
        val current=++generation;client.interrupt()
        val previous=work;previous?.cancel()
        _state.update { it.copy(busy=true,phase=context.getString(R.string.phase_stopping),error=null,stage=null) }
        work=viewModelScope.launch {
            try {
                // A second pause/revocation must wait for the original IO writer,
                // even if it cancels this cleanup job in the meantime.
                withContext(NonCancellable) {
                    previous?.join()
                    if(discard)withContext(Dispatchers.IO){pending.clear();completed.clear();discardReviewDraft()}
                }
                if(current==generation) {
                    val ready=if(discard)null else withContext(Dispatchers.IO){completed.load()}
                    if(ready!=null) {
                        _state.update { it.copy(busy=false,clip=ready,recovery=null,tracking=null,stage=null,
                            phase=context.getString(R.string.phase_ready),error=null) }
                    }else {
                        resetPreview();restorePending()
                        if(!discard&&_state.value.recovery==null)_state.update { it.copy(review=reviewDraft.load()) }
                        _state.update { it.copy(busy=false,phase="",message=if(discard)null else context.getString(R.string.processing_stopped)) }
                    }
                }
            }catch(_:CancellationException){}catch(_:Exception){if(current==generation)failWork(context.getString(R.string.discard_processing_failed))}
        }
    }

    fun revokeRemote() {
        cancelReview()
        File(context.cacheDir,"shared").deleteRecursively()
        File(context.noBackupFilesDir,"exports").deleteRecursively()
        stopProcessing(true)

    }

    fun resetPreview(toEntry:Boolean=false) {
        editor=null
        completed.clear()
        _state.value.clip?.raw?.let { raw ->
            if(raw.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator)) {
                val pending=work
                if(pending!=null&&!pending.isCompleted)pending.invokeOnCompletion { raw.delete() } else raw.delete()
            }
        }
        _state.update { it.copy(clip=null,savedId=null,previewIdentity=null,error=null,message=null,editing=false,library=if(toEntry)false else _state.value.library) }
    }
    fun setFrameFilters(filters:FrameFilters) {
        val current=_state.value
        val clip=current.clip?:return
        if(current.busy||clip.frameFilters==filters)return
        _state.value=current.copy(clip=clip.copy(frameFilters=filters))
        val id=current.savedId?:return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                frameFilterWriteLock.withLock {
                    val newest=_state.value
                    val selected=if(newest.savedId==id)newest.clip?.frameFilters?:filters else filters
                    repository.updateFrameFilters(id,selected)
                }
            }catch(_:Exception){report(context.getString(R.string.project_save_failed))}
        }
    }
    fun save() {
        if(_state.value.busy)return
        val clip=_state.value.clip?:return
        if(_state.value.savedId!=null)return
        val current=generation
        _state.update { it.copy(busy=true,phase=context.getString(R.string.phase_saving)) }
        work=viewModelScope.launch {
            try {
                val (project,savedClip)=withContext(Dispatchers.IO){
                    val project=repository.save(clip)
                    val savedClip=repository.open(project.id)
                    completed.clear()
                    project to savedClip
                }
                ensureActive()
                if(current==generation)_state.update { it.copy(busy=false,clip=savedClip,previewIdentity=it.previewIdentity?:clip.raw.absolutePath,savedId=project.id,message=context.getString(R.string.project_saved)) }
                refreshProjects()
            }catch(error:CancellationException){throw error}
            catch(_:Exception){if(current==generation)failWork(context.getString(R.string.project_save_failed))}
        }
    }
    fun open(id:String) {
        if(_state.value.busy)return
        val current=++generation
        _state.update { it.copy(busy=true,phase=context.getString(R.string.phase_opening),error=null) }
        work=viewModelScope.launch {
            try {
                val clip=withContext(Dispatchers.IO){repository.open(id)}
                ensureActive()
                if(current==generation) {
                    resetPreview();_state.update { it.copy(busy=false,clip=clip,savedId=id) }
                }
            }catch(error:CancellationException){throw error}
            catch(_:Exception){if(current==generation)failWork(context.getString(R.string.project_corrupt))}
        }
    }
    fun deleteProject(id:String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO){repository.delete(id)};refreshProjects() }
            catch(_:Exception){report(context.getString(R.string.project_delete_failed))}
        }
    }
    fun clearProjects() {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO){repository.deleteAll()};resetPreview();refreshProjects() }
            catch(_:Exception){report(context.getString(R.string.project_delete_all_failed))}
        }
    }
    private suspend fun refreshProjects() {
        val projects=withContext(Dispatchers.IO){repository.list()}
        _state.update { it.copy(projects=projects) }
    }
    fun refreshKeyStatus() {
        viewModelScope.launch { _keyConfigured.value=withContext(Dispatchers.IO){keyStore.read()!=null} }
    }
    fun startEditing() {
        edit(context.getString(R.string.phase_validate_fk),false){}
    }
    fun rotateJoint(joint:Int,start:Int,end:Int,degrees:Float,endDegrees:Float?)=edit(context.getString(R.string.phase_apply_keys),true) {
        it.rotate(joint,start,end,Quaternion.zDegrees(degrees),endDegrees?.let { angle->Quaternion.zDegrees(angle) })
    }
    fun solveIk(frame:Int,start:Int,middle:Int,end:Int,target:Vec3,pole:Vec3)=edit(context.getString(R.string.phase_solve_ik),true){it.ik(frame,start,middle,end,target,pole)}
    fun undoEdit()=edit(context.getString(R.string.phase_undo),true){it.undo()}
    fun redoEdit()=edit(context.getString(R.string.phase_redo),true){it.redo()}
    private fun edit(phase:String,dirty:Boolean,action:(PoseEditor)->Unit) {
        val clip=_state.value.clip?:return
        if(_state.value.busy)return
        val current=generation
        _state.update { it.copy(busy=true,phase=phase,error=null) }
        work=viewModelScope.launch {
            try {
                val (engine,updated)=withContext(Dispatchers.IO) {
                    val engine=editor?:PoseEditor(clip).apply { clip.correctionLayer?.let(::load) }
                    action(engine)
                    engine to engine.clip().copy(frameFilters=clip.frameFilters)
                }
                if(current==generation) {
                    editor=engine
                    _state.update { it.copy(busy=false,clip=updated,editing=true,savedId=if(dirty)null else _state.value.savedId) }
                }
            }catch(error:CancellationException){throw error}
            catch(error:Exception){if(current==generation)failWork(error.message?:context.getString(R.string.edit_failed_original))}
        }
    }
    /** Pick/record lands here first: the person reviews and optionally trims before anything is prepared or sent. */
    fun review(uri:Uri,source:String) {
        val current=_state.value
        if(current.busy||current.recovery!=null||current.review!=null) {
            if(source=="camera") captures.discard(uri)
            if(current.recovery!=null) report(context.getString(R.string.processing_pending))
            return
        }
        try { gate.withConsent { } } catch(_:Exception) {
            if(source=="camera") captures.discard(uri)
            report(context.getString(R.string.consent_review))
            return
        }
        val draft=ReviewVideo(uri,source)
        try{reviewDraft.save(draft)}catch(_:Exception){report(context.getString(R.string.review_unreadable));return}
        _state.value=current.copy(review=draft,error=null,message=null)
    }
    fun updateReviewRange(start:Long,end:Long) {
        val draft=_state.value.review?:return
        if(start<0||end<=start)return
        val updated=draft.copy(startMs=start,endMs=end)
        try{reviewDraft.save(updated);_state.update { it.copy(review=updated) }}catch(_:Exception){report(context.getString(R.string.accept_save_failed))}
    }
    fun updateReviewFocus(focus:com.posepocket.media.FocusWindow?) {
        val draft=_state.value.review?:return
        val updated=draft.copy(focus=focus)
        try{reviewDraft.save(updated);_state.update { it.copy(review=updated) }}
        catch(_:Exception){report(context.getString(R.string.accept_save_failed))}
    }
    fun cancelReview() {
        val pending=_state.value.review?:return
        if(pending.source=="camera") captures.discard(pending.uri)
        releaseReviewGrant()
        reviewDraft.clear()
        _state.update { it.copy(review=null) }
    }
    fun confirmReview(trim:TrimRange?,focus:com.posepocket.media.FocusWindow?=null) {
        val pending=_state.value.review?:return
        _state.update { it.copy(review=null) }
        submit(pending.uri,pending.source,trim,focus)
    }
    fun showLibrary(show:Boolean) { _state.update { it.copy(library=show) } }
    fun dismissNotice() { _state.update { it.copy(error=null,message=null) } }
    private fun releaseReviewGrant() {
        reviewDraft.load()?.takeIf { it.source=="attachment" }?.let {
            try { context.contentResolver.releasePersistableUriPermission(it.uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch(_:Exception){}
        }
    }
    private fun discardReviewDraft() {
        releaseReviewGrant()
        reviewDraft.load()?.let { if(it.source=="camera")captures.discard(it.uri) }
        reviewDraft.clear()
    }
    /** Auxiliary errors must never unlock an unrelated processing operation. */
    fun report(message:String) { _state.update { it.copy(error=localizeUiMessage(context,message)) } }
    private fun failWork(message:String) { _state.update { it.copy(busy=false,error=localizeUiMessage(context,message)) } }
    override fun onCleared() { work?.cancel();client.interrupt();super.onCleared() }
}
