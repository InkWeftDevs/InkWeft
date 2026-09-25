// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import java.util.UUID

data class NotebookUi(val notes:List<Note> = emptyList(),val drafts:Map<String,NoteDraft> = emptyMap(),
    val selectedId:String?=null,val loading:Boolean=true,val readFailed:Boolean=false) {
    val current:NoteDraft? get()=drafts[selectedId]
}

data class RenameUi(val base:Note,val titleAtRequest:String,val value:String,
    val pending:RenameNote?=null,val phase:SavePhase=SavePhase.EDITING,val message:String?=null)

class NotebookViewModel(application:Application):AndroidViewModel(application) {
    private val repository=(application as InkWeftApplication).repository
    private val mutableUi=MutableStateFlow(NotebookUi());val ui:StateFlow<NotebookUi> = mutableUi.asStateFlow()
    private val renameState=MutableStateFlow<RenameUi?>(null);val renaming=renameState.asStateFlow()
    private var observation:Job?=null
    init{retryRead()}
    fun retryRead(){
        observation?.cancel();mutableUi.update{it.copy(loading=true,readFailed=false)}
        observation=viewModelScope.launch{try{repository.observeNotes().collect{notes->mutableUi.update{it.copy(notes=notes,loading=false,readFailed=false)}}}
        catch(c:CancellationException){throw c}catch(_:Exception){mutableUi.update{it.copy(loading=false,readFailed=true)}}}
    }
    fun create(title:String){
        if(title.isBlank()||title.length>120)return
        val n=Note(UUID.randomUUID().toString(),0,title.trim(),"")
        mutableUi.update{it.copy(drafts=it.drafts+(n.id to NoteDraft(n)),selectedId=n.id)}
    }
    fun select(note:Note){mutableUi.update{it.copy(selectedId=note.id,drafts=if(note.id in it.drafts)it.drafts else it.drafts+(note.id to NoteDraft(note)))}}
    fun back(){mutableUi.update{it.copy(selectedId=null)}}
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
        if(renameState.value!=null)return
        val d=ui.value.drafts[note.id]
        val base=d?.base?:ui.value.notes.firstOrNull{it.id==note.id}?:note
        if(base.revision==0L)return
        val blocked=d!=null && d.phase!=SavePhase.EDITING
        renameState.value=RenameUi(base,d?.title?:base.title,d?.title?:base.title,
            phase=if(blocked)SavePhase.REJECTED else SavePhase.EDITING,
            message=if(blocked)"这份笔记还有待核对的文字保存，请先处理后再重命名。"else null)
    }
    fun editRename(value:String){val r=renameState.value?:return;if(r.phase!=SavePhase.EDITING||value.length>120)return;renameState.value=r.copy(value=value,message=null)}
    fun closeRename(){val r=renameState.value?:return;if(r.phase !in listOf(SavePhase.SAVING,SavePhase.UNKNOWN))renameState.value=null}
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
                        state.copy(notes=state.notes.map{if(it.id==result.note.id && it.revision<=result.note.revision)result.note else it},
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
