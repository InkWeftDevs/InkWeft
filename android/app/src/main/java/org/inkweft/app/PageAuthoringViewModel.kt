// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class AuthoringUi(val state:PageAuthoring=PageAuthoring(),val revision:Long=0,val loading:Boolean=true,
    val busy:Boolean=false,val pending:Boolean=false,val message:String?=null,val undo:Boolean=false,val redo:Boolean=false) {
    val ready get()=!loading&&!busy&&!pending
    val canWrite get()=ready&&state.layers.currentId!=null
}
/** The same author gate and chronological history as page ink/objects; unknown writes retain their command. */
internal class PageAuthoringViewModel(val scope:AuthoringScope,private val repo:PageAuthoringRepository):ViewModel() {
    private val mutable=MutableStateFlow(AuthoringUi());val ui=mutable.asStateFlow()
    var authorAllowed:()->Boolean={true}
    var writing by androidx.compose.runtime.mutableStateOf(false)
    var history:EditorHistory?=null
    private val undo=ArrayDeque<PageAuthoring>();private val redo=ArrayDeque<PageAuthoring>()
    private var pending:AuthoringPending?=null
    private var snapshot:AuthoringSnapshot?=null
    private var unreadablePending=false
    init { viewModelScope.launch {
        try{
            pending=withContext(Dispatchers.IO){repo.pending(scope)}
            pending?.let{p->snapshot=p.before;mutable.value=AuthoringUi(p.after,p.before.revision,false,pending=true,message="发现未确认作者操作，正在核对原回执");retry()}
        }catch(c:CancellationException){throw c}catch(_:Exception){unreadablePending=true;mutable.value=mutable.value.copy(loading=false,message="图层或恢复材料读取失败；原文件保留，请核对或明确放弃未确认草稿",pending=true)}
        // A corrupt recovery file must not permanently stop observation after explicit recovery.
        repo.observe(scope).catch{e->if(e is CancellationException)throw e;mutable.value=mutable.value.copy(loading=false,pending=true,message="作者状态读取失败，请核对已保存版本")}
            .collect{saved->if(pending==null&&!unreadablePending&&!mutable.value.busy){
                val before=snapshot
                if(before!=null&&(before.revision!=saved.revision||before.inkRevision!=saved.inkRevision||before.objectRevision!=saved.objectRevision||before.graphFingerprint!=saved.graphFingerprint)){
                    undo.clear();redo.clear();history?.forget(EditDomain.AUTHORING)
                }
                publish(saved)
            }}
    } }
    private fun publish(snapshot:AuthoringSnapshot,message:String?=null){this.snapshot=snapshot;mutable.value=AuthoringUi(snapshot.state,snapshot.revision,false,message=message,undo=undo.isNotEmpty(),redo=redo.isNotEmpty())}
    fun pageHeadsMatch(ink:Long,objects:Long)=snapshot?.let{it.inkRevision==ink&&it.objectRevision==objects}==true
    fun scopeForWrite():LayerWriteScope {check(ui.value.canWrite){"请先选择可写图层"};return ui.value.state.layers.writeScope(ui.value.revision)}
    fun change(transform:(PageAuthoring)->PageAuthoring,direction:Int=0,expectedRevision:Long?=null):Boolean{
        if(!authorAllowed()||!ui.value.ready)return false
        return try {
            val before=checkNotNull(snapshot)
            require(before.revision==(expectedRevision?:ui.value.revision)){"图层已变化，请重新操作"}
            val after=transform(before.state);PageAuthoringCodec.encode(after)
            val accepted=AuthoringPending(scope,UUID.randomUUID().toString(),before,after,direction)
            // Keep the visible draft blocked until the IO-thread journal and author transaction are confirmed.
            // Process death before the first journal fsync remains an explicitly unconfirmed recovery window.
            pending=accepted;mutable.value=ui.value.copy(state=after,pending=true,message="批注尚未保存，正在写入恢复材料");retry();true
        }catch(e:Exception){mutable.value=ui.value.copy(busy=false,message=explain(e));false}
    }
    fun retry(){
        val p=pending?:return;if(ui.value.busy)return
        mutable.value=ui.value.copy(busy=true,pending=true,message=null)
        viewModelScope.launch {
            try {
                val next=withContext(Dispatchers.IO){repo.stagePending(p);repo.save(scope,p.before,p.commandId,p.after,p.afterPayload())}
                val saved=withContext(Dispatchers.IO){repo.read(scope)}
                withContext(Dispatchers.IO){repo.clearPending(scope,p.commandId)}
                val exact=saved.revision==next&&saved.inkRevision==p.before.inkRevision&&saved.objectRevision==p.before.objectRevision&&saved.graphFingerprint==p.before.graphFingerprint
                val direction=if(p.direction==-1&&undo.isEmpty()||p.direction==1&&redo.isEmpty())0 else p.direction
                if(exact){
                    when(direction){-1->{undo.removeLast();redo.addLast(p.before.state)};1->{redo.removeLast();undo.addLast(p.before.state)};else->{undo.addLast(p.before.state);redo.clear()}}
                    while(undo.size>10)undo.removeFirst();history?.committed(EditDomain.AUTHORING,direction)
                }else{undo.clear();redo.clear();history?.forget(EditDomain.AUTHORING)}
                pending=null
                publish(saved,if(!exact)"原操作已确认，图层随后已有新修改，现显示最新保存版本"else if(saved.state.layers.currentId==null)"当前层已不可写，请明确选择另一可写层后再落笔"else null)
            }catch(c:CancellationException){throw c}catch(e:Exception){mutable.value=ui.value.copy(busy=false,pending=true,message="保存结果待核对，原命令已保留。${explain(e)}")}
        }
    }
    fun reload(){if(ui.value.busy)return;mutable.value=ui.value.copy(busy=true);viewModelScope.launch{
        try{
            val p=pending?:withContext(Dispatchers.IO){repo.pending(scope)}
            if(p!=null&&withContext(Dispatchers.IO){repo.confirmed(p)}==null){pending=p;mutable.value=ui.value.copy(busy=false,pending=true,message="没有找到已提交回执，恢复材料保留。可重试原操作，或明确放弃未确认草稿");return@launch}
            val saved=withContext(Dispatchers.IO){repo.read(scope)}
            if(p!=null)withContext(Dispatchers.IO){repo.clearPending(scope,p.commandId)}
            pending=null;unreadablePending=false;undo.clear();redo.clear();history?.forget(EditDomain.AUTHORING);publish(saved)
        }catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=ui.value.copy(busy=false,pending=true,message="仍无法核对已保存状态，恢复材料保留")}
    }}
    /** Called only by the explicit discard confirmation; a committed receipt is kept in the database. */
    fun discardPending(){if(ui.value.busy)return;mutable.value=ui.value.copy(busy=true);viewModelScope.launch{
        try{val saved=withContext(Dispatchers.IO){val current=repo.read(scope);repo.clearPending(scope);current};pending=null;unreadablePending=false;undo.clear();redo.clear();history?.forget(EditDomain.AUTHORING);publish(saved,"未确认草稿已放弃，所有已提交内容保留")}
        catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=ui.value.copy(busy=false,pending=true,message="未能放弃草稿，恢复材料仍保留")}
    }}
    fun undo(){if(undo.isNotEmpty())change({undo.last()},-1)}
    fun redo(){if(redo.isNotEmpty())change({redo.last()},1)}
    fun clearMessage(){mutable.value=ui.value.copy(message=null)}
    fun change(transform:(PageAuthoring)->PageAuthoring)=change(transform,0,null)
    fun layers(transform:(UserLayers)->UserLayers)=change{it.withLayers(transform(it.layers))}
    companion object {
        fun explain(e:Throwable)=when(e.message){
            "LAYER_BEAUTY_MOVE_TOGETHER"->"美化文字和保留的原迹需一起转层；请重新选择整组"
            "LAYER_LAST_WRITABLE"->"至少保留一个可见、未锁定的可写层"
            "LAYER_CHOOSE_WRITABLE"->"请先明确选择一个可写层"
            "LAYER_SELECTION_LOCKED"->"选择包含隐藏或锁定内容，本次整组未修改"
            "LAYER_UNLOCK_BEFORE_DELETE"->"请先解锁该层，再选择删除或转移其内容"
            "LAYER_DELETE_CHOOSE_CONTENT"->"该层非空，请选择删除内容或转移至指定可写层"
            "LAYER_CAPACITY"->"当前页或画布最多 32 层，已有内容未改动"
            "ANNOTATION_CAPACITY"->"批注已达当前画布容量（512 笔、6 万点或 1.9 MB），已有内容未改动"
            "AUTHORING_CONFLICT","LAYER_WRITE_SCOPE_CHANGED"->"内容或图层已变化，请读取已保存内容后重新操作"
            "ANNOTATION_DETACH_BEFORE_DELETE"->"请先解绑对象旁的批注，再删除对象；原笔迹保留"
            else->e.message?.take(120)?:"操作未确认，请核对重试"
        }
    }
    class Factory(private val scope:AuthoringScope,private val repo:PageAuthoringRepository):ViewModelProvider.Factory {
        override fun<T:ViewModel>create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(PageAuthoringViewModel::class.java));@Suppress("UNCHECKED_CAST")return PageAuthoringViewModel(scope,repo) as T}
    }
}
