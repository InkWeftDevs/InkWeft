// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.data.*

internal class CardReuseViewModel(private val repo:CardReuseRepository,private val saved:SavedStateHandle):ViewModel(){
    val busy=MutableStateFlow(false)
    val pending=saved.getStateFlow<ArrayList<String>?>("reuse.pending",null)
    val message=MutableStateFlow<String?>(null)
    var allowed:()->Boolean={false}
    fun start(card:StudyCardRow,destination:String,kind:CardReuseKind){
        if(busy.value||pending.value!=null||!allowed())return
        busy.value=true
        viewModelScope.launch{try{
            val r=withContext(Dispatchers.IO){repo.prepare(card.id,card.revision,destination,kind)}
            saved["reuse.pending"]=arrayListOf(r.operationId,r.cardId,r.cardRevision.toString(),r.destination,r.kind.name,r.presentationId.orEmpty(),r.presentationRevision.toString())
        }catch(c:CancellationException){throw c}catch(_:Exception){message.value="原卡已变化或读取失败，请返回重新打开；没有创建引用或副本。"}finally{busy.value=false}
            if(pending.value!=null)retry()
        }
    }
    fun retry(){
        val f=pending.value?:return
        if(busy.value)return
        // A frozen unknown operation must still be reconciled after a mode change.
        busy.value=true
        viewModelScope.launch{try{
            val r=CardReuseRequest(f[0],f[1],f[2].toLong(),f[3],CardReuseKind.valueOf(f[4]),f[5].ifEmpty{null},f[6].toLong())
            when(val result=withContext(Dispatchers.IO){repo.outcome(r)}){
                is KnowledgeOutcome.Success->{saved.set<ArrayList<String>?>("reuse.pending",null);message.value=if(r.kind==CardReuseKind.REFERENCE)"同步引用已加入目标笔记的关联页，仍是同一张卡。"else"独立副本已加入目标笔记主图；题目与进度未复制。"}
                is KnowledgeOutcome.Rejected->{saved.set<ArrayList<String>?>("reuse.pending",null);message.value=when(result.reason){KnowledgeRejection.DUPLICATE->"目标笔记已引用此卡；没有重复创建。";KnowledgeRejection.CONFLICT->"原内容或注释已变化，请返回重新打开。";KnowledgeRejection.UNAVAILABLE->"原卡或目标笔记不可用，未创建副本。";else->"未创建：目标容量或内容条件不满足，原卡保留。"}}
                KnowledgeOutcome.Unknown->message.value="结果待核对，请核对原操作，不要另建一次。"
            }
        }catch(c:CancellationException){throw c}catch(_:Exception){message.value="结果待核对，原请求已保留。"}finally{busy.value=false}}
    }
    class Factory(private val repo:CardReuseRepository):ViewModelProvider.Factory{
        override fun<T:ViewModel>create(modelClass:Class<T>,extras:CreationExtras):T{@Suppress("UNCHECKED_CAST")return CardReuseViewModel(repo,extras.createSavedStateHandle()) as T}
    }
}

@Composable internal fun CardReuseDialog(card:StudyCardRow,dismiss:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val vm:CardReuseViewModel=viewModel(key="card-reuse-${card.id}",factory=CardReuseViewModel.Factory(app.study.reuse))
    val notes by remember(app){app.knowledge.notes()}.collectAsStateWithLifecycle(initialValue=emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle();val pending by vm.pending.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle()
    var destination by rememberSaveable(card.id){mutableStateOf(card.notebookId)}
    var kind by rememberSaveable(card.id){mutableStateOf(CardReuseKind.REFERENCE)}
    val target=pending?.get(3)?:destination
    val lock=rememberBookReadLock(target);val readOnly by lock.readOnly.collectAsStateWithLifecycle()
    val editable=!busy&&pending==null&&!readOnly&&notes.any{it.id==target}
    SideEffect{vm.allowed={lock.canWrite}}
    ReadLockGuard(lock,"card-reuse-${card.id}",blocked=true,draft=pending!=null)
    EditorPanel("复用知识卡","",{if(!busy&&pending==null)dismiss()},"card-reuse",footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            TextButton(dismiss,enabled=!busy&&pending==null){Text("返回原卡")}
            TextButton({if(pending!=null)vm.retry()else vm.start(card,destination,kind)},enabled=!busy&&(pending!=null||editable),modifier=Modifier.testTag("card-reuse-save")){Text(if(pending!=null)"核对原操作"else"确认复用")}
        }
    }){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(card.title,style=MaterialTheme.typography.titleMedium)
            Text("同步引用保留同一内容身份，更新与回收会反映到各处；删除引用仅移除该处链接。独立副本另起内容身份，不同步正文、注释或复习进度；原迹来源仍可回溯。")
            Text("目标笔记")
            notes.forEach{note->FilterChip(destination==note.id,{destination=note.id},enabled=!busy&&pending==null,label={Text(note.title)},modifier=Modifier.testTag("reuse-book-${note.id}"))}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(kind==CardReuseKind.REFERENCE,{kind=CardReuseKind.REFERENCE},enabled=editable,label={Text("同步引用")})
                FilterChip(kind==CardReuseKind.INDEPENDENT_COPY,{kind=CardReuseKind.INDEPENDENT_COPY},enabled=editable,label={Text("独立副本")})
            }
            if(readOnly)Text("目标笔记处于阅读锁定，请先在该笔记切换为书写。")
            message?.let{Text(it,modifier=Modifier.testTag("card-reuse-message"))}
        }
    }
}
