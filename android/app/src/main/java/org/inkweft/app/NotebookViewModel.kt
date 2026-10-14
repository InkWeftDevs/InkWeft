// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import java.util.UUID

/** notes contains title/revision headers; only drafts contain editable complete bodies. */
data class NotebookUi(val notes:List<Note> = emptyList(),val drafts:Map<String,NoteDraft> = emptyMap(),
    val selectedId:String?=null,val openIds:List<String> = emptyList(),val loading:Boolean=true,val readFailed:Boolean=false) {
    val current:NoteDraft? get()=drafts[selectedId]
}

data class RenameUi(val base:Note,val titleAtRequest:String,val value:String,
    val pending:RenameNote?=null,val phase:SavePhase=SavePhase.EDITING,val message:String?=null)

class NotebookViewModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    private val repository=(application as InkWeftApplication).repository
    private val tabPreferences=application.getSharedPreferences("inkweft-open-tabs",android.content.Context.MODE_PRIVATE)
    private val rememberedTabs=tabPreferences.getString("ids","").orEmpty().split('|').filter{runCatching{UUID.fromString(it)}.isSuccess}.distinct()
    // Restore this task's route after process death; the author's note still comes from Room.
    private val mutableUi=MutableStateFlow(NotebookUi(selectedId=saved["notebook.selected"],openIds=rememberedTabs));val ui:StateFlow<NotebookUi> = mutableUi.asStateFlow()
    private val renameState=MutableStateFlow<RenameUi?>(null);val renaming=renameState.asStateFlow()
    private var observation:Job?=null
    private var selection:Job?=null
    private var selectionGeneration=0L
    private var pendingSelection:Note?=null
    internal var readSelection:suspend(String)->Note?=repository::read
    private var renameLoad:Job?=null
    init{retryRead()}
    fun retryRead(){
        observation?.cancel();mutableUi.update{it.copy(loading=true,readFailed=false)}
        pendingSelection?.takeIf{selection?.isActive!=true}?.let(::select)
        observation=viewModelScope.launch{try{repository.observeNoteHeaders().collect{notes->
                mutableUi.update{old->
                    val ids=notes.mapTo(HashSet()){it.id}
                    old.copy(notes=notes,loading=false,readFailed=old.readFailed&&pendingSelection!=null,
                        selectedId=old.selectedId?.takeIf{it in ids||it in old.drafts},
                        openIds=old.openIds.filter{it in ids||it in old.drafts})
                }
                // A restored route has only directory metadata until its body is read.
                val state=ui.value
                state.selectedId?.takeIf{it !in state.drafts&&selection?.isActive!=true}?.let{id->notes.firstOrNull{it.id==id}?.let(::select)}
                saved["notebook.selected"]=ui.value.selectedId
            }}
        catch(c:CancellationException){throw c}catch(_:Exception){mutableUi.update{it.copy(loading=false,readFailed=true)}}}
    }
    fun create(title:String){
        if(title.isBlank()||title.length>120)return
        selectionGeneration++;selection?.cancel();pendingSelection=null
        val n=Note(UUID.randomUUID().toString(),0,title.trim(),"")
        mutableUi.update{it.copy(drafts=it.drafts+(n.id to NoteDraft(n)),selectedId=n.id)}
        saved["notebook.selected"]=n.id
    }
    private fun persistTabs(){saved["notebook.selected"]=ui.value.selectedId;tabPreferences.edit().putString("ids",ui.value.openIds.joinToString("|")).apply()}
    fun select(note:Note){
        val generation=beginSelection(note)
        if(note.id in ui.value.drafts||note.revision==0L){publishSelection(note);return}
        selection=viewModelScope.launch{loadSelection(note,generation)}
    }
    /** Navigation callers only consume source/return state after the complete body opens. */
    suspend fun selectAwait(note:Note):Boolean {
        currentCoroutineContext().ensureActive()
        val generation=beginSelection(note)
        if(note.id in ui.value.drafts||note.revision==0L){publishSelection(note);return true}
        return try{coroutineScope {
            // Stay in the navigation caller after the read: publication and its success
            // acknowledgement must finish before Compose can dispose the source panel.
            selection=currentCoroutineContext().job
            loadSelection(note,generation)
        }}catch(c:CancellationException){currentCoroutineContext().ensureActive();false}
    }

    private fun beginSelection(note:Note):Long {
        selectionGeneration++;selection?.cancel();pendingSelection=note
        return selectionGeneration
    }
    private fun publishSelection(head:Note){
        pendingSelection=null
        mutableUi.update{state->state.copy(selectedId=head.id,openIds=(state.openIds+head.id).distinct(),readFailed=false,
            drafts=if(head.id in state.drafts)state.drafts else state.drafts+(head.id to NoteDraft(head)))}
        persistTabs()
    }
    private suspend fun loadSelection(note:Note,generation:Long):Boolean=try{
        // Shelf notes contain short previews. Only this complete read may create a draft.
        val head=readSelection(note.id)
        currentCoroutineContext().ensureActive()
        if(generation!=selectionGeneration)false
        else if(head==null){mutableUi.update{it.copy(readFailed=true)};false}
        else{publishSelection(head);true}
    }catch(c:CancellationException){if(generation==selectionGeneration)pendingSelection=null;throw c}catch(_:Exception){
        if(generation==selectionGeneration)mutableUi.update{it.copy(readFailed=true)}
        false
    }
    fun selectTab(id:String){(ui.value.notes.firstOrNull{it.id==id}?:ui.value.drafts[id]?.base)?.let(::select)}
    fun closeTab(id:String):Boolean {
        val d=ui.value.drafts[id]
        if(d!=null&&(d.dirty||d.phase!=SavePhase.EDITING))return false
        selectionGeneration++;selection?.cancel();pendingSelection=null
        mutableUi.update{old->val remaining=old.openIds-id
            val next=if(old.selectedId==id)remaining.getOrNull(old.openIds.indexOf(id).coerceAtMost(remaining.lastIndex).coerceAtLeast(0))else old.selectedId
            old.copy(openIds=remaining,selectedId=next)}
        ui.value.selectedId?.takeIf{it !in ui.value.drafts}?.let(::selectTab)
        persistTabs();return true
    }
    fun back(){selectionGeneration++;selection?.cancel();pendingSelection=null;mutableUi.update{it.copy(selectedId=null)};persistTabs()}
    fun edit(title:String,text:String){val d=ui.value.current?:return;if(!d.canEdit||title.length>120||text.length>100_000)return;replace(d.base.id,d.edit(title,text))}
    fun save(){
        val d=ui.value.current?:return
        if(renameState.value?.let{it.base.id==d.base.id && it.phase in listOf(SavePhase.SAVING,SavePhase.UNKNOWN)}==true)return
        if(d.phase in listOf(SavePhase.SAVING,SavePhase.CONFLICT,SavePhase.REJECTED)||d.title.isBlank())return
        val saving=d.beginSave();val command=checkNotNull(saving.pending);replace(d.base.id,saving)
        viewModelScope.launch{
            val result=try{repository.save(command)}catch(_:CancellationException){replace(d.base.id,saving.finish(command,SaveResult.OutcomeUnknown));return@launch}catch(_:Exception){SaveResult.OutcomeUnknown}
            val active=ui.value.drafts[d.base.id];if(active?.pending==command)replace(d.base.id,active.finish(command,result))
        }
    }
    fun openRename(note:Note){
        if(renameState.value!=null||renameLoad?.isActive==true)return
        fun show(base:Note){
            val d=ui.value.drafts[note.id];val head=d?.base?:base
            if(head.revision==0L)return
            val blocked=d!=null&&d.phase!=SavePhase.EDITING
            renameState.value=RenameUi(head,d?.title?:head.title,d?.title?:head.title,
                phase=if(blocked)SavePhase.REJECTED else SavePhase.EDITING,
                message=if(blocked)"这份笔记还有待核对的文字保存，请先处理后再重命名。"else null)
        }
        ui.value.drafts[note.id]?.let{show(it.base);return}
        // Renaming can finish after the same note is opened. Its requested base must be
        // a complete head so acceptRenamedHead can still prove the body was unchanged.
        renameLoad=viewModelScope.launch{try{repository.read(note.id)?.let(::show)}
            catch(c:CancellationException){throw c}catch(_:Exception){mutableUi.update{it.copy(readFailed=true)}}}
    }
    fun editRename(value:String){val r=renameState.value?:return;if(r.phase!=SavePhase.EDITING||value.length>120)return;renameState.value=r.copy(value=value,message=null)}
    fun closeRename(){renameLoad?.cancel();val r=renameState.value?:return;if(r.phase !in listOf(SavePhase.SAVING,SavePhase.UNKNOWN))renameState.value=null}
    fun saveRename(){
        val requested=renameState.value?:return
        if(requested.phase !in listOf(SavePhase.EDITING,SavePhase.UNKNOWN))return
        val clean=requested.value.trim();if(!RenameNote.validTitle(clean))return
        if(clean==requested.base.title&&requested.pending==null){renameState.value=null;return}
        val command=requested.pending?:RenameNote(UUID.randomUUID().toString(),requested.base.id,requested.base.revision,clean)
        val saving=requested.copy(pending=command,phase=SavePhase.SAVING,message=null);renameState.value=saving
        viewModelScope.launch{
            val result=try{repository.rename(command)}catch(_:CancellationException){if(renameState.value===saving)renameState.value=saving.copy(phase=SavePhase.UNKNOWN,message="结果待核对，保留原重命名命令。请重试，不会再次复制笔记。");return@launch}catch(_:Exception){SaveResult.OutcomeUnknown}
            if(renameState.value!==saving)return@launch
            when(result){
                is SaveResult.Committed->{
                    mutableUi.update { state ->
                        val d=state.drafts[command.noteId]
                        val replacement=d?.acceptRenamedHead(requested.base,requested.titleAtRequest,result.note)
                        state.copy(notes=state.notes.map{if(it.id==result.note.id && it.revision<=result.note.revision)result.note.copy(text="") else it},
                            drafts=if(replacement!=null)state.drafts+(command.noteId to replacement)else state.drafts)
                    }
                    renameState.value=null
                }
                is SaveResult.Conflict->renameState.value=saving.copy(phase=SavePhase.CONFLICT,pending=null,message="名称或文字已被另一操作修改，未覆盖新内容。请关闭后在文字页核对版本，再重新命名。")
                SaveResult.ReusedCommandId->renameState.value=saving.copy(phase=SavePhase.REJECTED,pending=null,message="重命名命令不匹配，未覆盖资料。请关闭后重试。")
                SaveResult.OutcomeUnknown->renameState.value=saving.copy(phase=SavePhase.UNKNOWN,message="保存结果待核对。请用下方重试按钮核对同一命令；输入与原资料都保留。")
            }
        }
    }
    fun discardDraftAndRead(){
        val requested=ui.value.current?:return
        if(requested.phase in listOf(SavePhase.SAVING,SavePhase.UNKNOWN))return
        val id=requested.base.id
        viewModelScope.launch{try{
            val note=repository.read(id)?:return@launch
            mutableUi.update{state->val d=state.drafts[id]?:return@update state;val replacement=d.acceptExplicitReload(requested,note);if(replacement===d)state else state.copy(drafts=state.drafts+(id to replacement))}
        }catch(c:CancellationException){throw c}catch(_:Exception){mutableUi.update{it.copy(readFailed=true)}}}
    }
    private fun replace(id:String,draft:NoteDraft){mutableUi.update{it.copy(drafts=it.drafts+(id to draft))}}
}
