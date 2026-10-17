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
)

class PocketViewModel(application:Application):AndroidViewModel(application) {
    private val context=application
    private val captures=CaptureStore(application)
    val gate=ConsentGate(Policy("dev-2026-10-17-v6-explicit-review-focus","nvidia-body-pose-video-v1",
        application.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }),AtomicConsentStore(application.noBackupFilesDir))
    private val keyStore=NvidiaKeyStore(application.noBackupFilesDir)
    private val repository=ProjectRepository(File(application.noBackupFilesDir,"projects"))
    private val client=DirectNvidiaClient(gate,File(application.cacheDir,"results"))
    private val pending=PendingProcessingStore(File(application.noBackupFilesDir,"processing"))
    private val reviewDraft=ReviewDraftStore(application.noBackupFilesDir)
    private val processing=ResumableProcessing(gate,pending,
        { uri,source -> val current=generation;try { MediaPreparer(context,gate).prepare(uri,source,trimRange,focusWindow){updatePhase(it,current)} } finally { trimRange=null;focusWindow=null } },
        { input,phase -> DeviceVideoPipeline(gate).prepare(input,phase) },
        { video,input,phase ->
            val key=keyStore.read()?:error(context.getString(R.string.key_required_prepared))
            client.infer(key,video,input,phase)
        })
    private val _keyConfigured=MutableStateFlow(false)
    val keyConfigured=_keyConfigured.asStateFlow()
    private val _initialized=MutableStateFlow(false)
    val initialized=_initialized.asStateFlow()
    private val _state=MutableStateFlow(PocketState())
    val state=_state.asStateFlow()
    private var work:Job?=null
    @Volatile private var trimRange:TrimRange?=null
    @Volatile private var focusWindow:com.posepocket.media.FocusWindow?=null
    private var generation=0
    private var editor:PoseEditor?=null
    private val frameFilterWriteLock=Mutex()
    val canUndo get()=editor?.canUndo==true
    val canRedo get()=editor?.canRedo==true

    init {
        // A new ViewModel represents a new process/session; rotations retain the existing instance.
        listOf("media","results","shared").forEach { File(application.cacheDir,it).deleteRecursively() }
        // Recordings from the system camera may still be pending after process death: purge by age, not on every start.
        captures.purgeStale()
        viewModelScope.launch {
            _keyConfigured.value=withContext(Dispatchers.IO){keyStore.read()!=null}
            withContext(Dispatchers.IO){if(gate.receipt()?.revokedAt!=null)pending.clear()}
            restorePending()
            if(gate.isGranted()&&_state.value.recovery==null) {
                val draft=withContext(Dispatchers.IO){reviewDraft.load()}
                _state.value=_state.value.copy(review=draft)
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
        // Set only once processing really starts, so a refused submit or a retry can never reuse a stale range.
        trimRange=trim
        focusWindow=focus
        val current=++generation
        _state.value=_state.value.copy(busy=true,phase=context.getString(R.string.phase_preparing),error=null,message=null,stage=ProcessingStage.PREPARE,progress=ProcessingProgress(ProcessingSubstep.IMPORT))
        work=viewModelScope.launch {
            try {
                val phase:(String)->Unit={ message ->
                    updatePhase(message,current)
                }
                val clip=withContext(Dispatchers.IO) {
                    processing.run(uri,source,phase) { stage ->
                        reviewDraft.clear()
                        if(current==generation)_state.value=_state.value.copy(recovery=stage)
                    }
                }
                ensureActive()
                if(current==generation) {
                    withContext(NonCancellable+Dispatchers.IO){pending.clear()}
                    _state.value=_state.value.copy(busy=false,clip=clip,phase=context.getString(R.string.phase_ready),error=null,recovery=null,stage=null)
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                if(current==generation) {
                    restorePending()
                    _state.value=_state.value.copy(busy=false,error=localizeUiMessage(context,error.message?:context.getString(R.string.processing_failed)),phase="",stage=null)
                }
            } finally {
                // Prepared/normalized files are owned by ResumableProcessing and PendingProcessingStore.
                // Do not delete them here; retries and process-death recovery depend on them.
                if(source=="camera"&&uri!=null){captures.discard(uri);reviewDraft.clear()}
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
        val stage=withContext(Dispatchers.IO) {
            try { pending.load()?.stage }catch(_:Exception){RecoveryStage.INVALID}
        }
        _state.value=_state.value.copy(recovery=stage)
    }

    fun demo() {
        if(_state.value.busy||_state.value.recovery!=null)return
        try { gate.withConsent { } }catch(_:Exception){return}
        val current=++generation
        resetPreview()
        _state.value=_state.value.copy(busy=true,phase=context.getString(R.string.phase_demo),error=null)
        work=viewModelScope.launch {
            try {
                val clip=withContext(Dispatchers.IO) {
                    val folder=File(context.cacheDir,"results").apply { mkdirs() }
                    syntheticDemo(File(folder,"${UUID.randomUUID()}.jsonl"))
                }
                ensureActive();gate.withConsent { }
                if(current==generation)_state.value=_state.value.copy(busy=false,clip=clip,phase=context.getString(R.string.phase_ready))
            }catch(error:CancellationException){throw error}
            catch(error:Exception){if(current==generation)report(error.message?:context.getString(R.string.demo_failed))}
        }
    }

    fun cancel()=stopProcessing(false)
    fun discardProcessing()=stopProcessing(true)
    private fun stopProcessing(discard:Boolean) {
        val current=++generation;client.interrupt()
        val previous=work;previous?.cancel()
        _state.value=_state.value.copy(busy=true,phase=context.getString(R.string.phase_stopping),error=null,stage=null)
        work=viewModelScope.launch {
            try {
                // A second pause/revocation must wait for the original IO writer,
                // even if it cancels this cleanup job in the meantime.
                withContext(NonCancellable) {
                    previous?.join()
                    if(discard)withContext(Dispatchers.IO){pending.clear()}
                }
                if(current==generation) {
                    resetPreview();restorePending()
                    _state.value=_state.value.copy(busy=false,phase="",message=if(discard)null else context.getString(R.string.processing_stopped))
                }
            }catch(_:CancellationException){}catch(_:Exception){if(current==generation)report(context.getString(R.string.discard_processing_failed))}
        }
    }

    fun revokeRemote() {
        cancelReview()
        File(context.cacheDir,"shared").deleteRecursively()
        stopProcessing(true)

    }

    fun resetPreview(toEntry:Boolean=false) {
        editor=null
        _state.value.clip?.raw?.let { raw ->
            if(raw.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator)) {
                val pending=work
                if(pending!=null&&!pending.isCompleted)pending.invokeOnCompletion { raw.delete() } else raw.delete()
            }
        }
        _state.value=_state.value.copy(clip=null,savedId=null,error=null,message=null,editing=false,library=if(toEntry)false else _state.value.library)
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
        _state.value=_state.value.copy(busy=true,phase=context.getString(R.string.phase_saving))
        work=viewModelScope.launch {
            try {
                val project=withContext(Dispatchers.IO){repository.save(clip)}
                ensureActive()
                if(current==generation)_state.value=_state.value.copy(busy=false,savedId=project.id,message=context.getString(R.string.project_saved))
                refreshProjects()
            }catch(error:CancellationException){throw error}
            catch(_:Exception){if(current==generation)report(context.getString(R.string.project_save_failed))}
        }
    }
    fun open(id:String) {
        if(_state.value.busy)return
        val current=++generation
        _state.value=_state.value.copy(busy=true,phase=context.getString(R.string.phase_opening),error=null)
        work=viewModelScope.launch {
            try {
                val clip=withContext(Dispatchers.IO){repository.open(id)}
                ensureActive()
                if(current==generation) {
                    resetPreview();_state.value=_state.value.copy(busy=false,clip=clip,savedId=id)
                }
            }catch(error:CancellationException){throw error}
            catch(_:Exception){if(current==generation)report(context.getString(R.string.project_corrupt))}
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
        _state.value=_state.value.copy(projects=projects)
    }
    fun refreshKeyStatus() {
        viewModelScope.launch { _keyConfigured.value=withContext(Dispatchers.IO){keyStore.read()!=null} }
    }
    fun startEditing()=edit(context.getString(R.string.phase_validate_fk),false){}
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
        _state.value=_state.value.copy(busy=true,phase=phase,error=null)
        work=viewModelScope.launch {
            try {
                val (engine,updated)=withContext(Dispatchers.IO) {
                    val engine=editor?:PoseEditor(clip).apply { clip.correctionLayer?.let(::load) }
                    action(engine)
                    engine to engine.clip().copy(frameFilters=clip.frameFilters)
                }
                if(current==generation) {
                    editor=engine
                    _state.value=_state.value.copy(busy=false,clip=updated,editing=true,savedId=if(dirty)null else _state.value.savedId)
                }
            }catch(error:CancellationException){throw error}
            catch(error:Exception){if(current==generation)report(error.message?:context.getString(R.string.edit_failed_original))}
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
        try{reviewDraft.save(updated);_state.value=_state.value.copy(review=updated)}catch(_:Exception){report(context.getString(R.string.accept_save_failed))}
    }
    fun updateReviewFocus(focus:com.posepocket.media.FocusWindow?) {
        val draft=_state.value.review?:return
        val updated=draft.copy(focus=focus)
        try{reviewDraft.save(updated);_state.value=_state.value.copy(review=updated)}
        catch(_:Exception){report(context.getString(R.string.accept_save_failed))}
    }
    fun cancelReview() {
        val pending=_state.value.review?:return
        if(pending.source=="camera") captures.discard(pending.uri)
        reviewDraft.clear()
        _state.value=_state.value.copy(review=null)
    }
    fun confirmReview(trim:TrimRange?,focus:com.posepocket.media.FocusWindow?=null) {
        val pending=_state.value.review?:return
        _state.value=_state.value.copy(review=null)
        submit(pending.uri,pending.source,trim,focus)
    }
    fun showLibrary(show:Boolean) { _state.value=_state.value.copy(library=show) }
    fun dismissNotice() { _state.value=_state.value.copy(error=null,message=null) }
    fun report(message:String) { _state.value=_state.value.copy(busy=false,error=localizeUiMessage(context,message)) }
    override fun onCleared() { work?.cancel();client.interrupt();super.onCleared() }
}
