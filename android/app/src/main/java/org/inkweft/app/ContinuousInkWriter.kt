// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*

internal data class PageEraseTarget(val ink:InkViewModel,val objects:PageObjectViewModel,val path:List<InkSample>)

/** One captured gesture and its inverses commit across every affected sheet and content kind. */
internal class ContinuousInkWriter:ViewModel() {
    private val mutable=MutableStateFlow(false);val blocked=mutable.asStateFlow()
    private val failure=MutableStateFlow(false);val needsRetry=failure.asStateFlow()
    private var pending:List<Pair<InkViewModel,CommitInk>> = emptyList()
    private var pendingObjects:List<Pair<PageObjectViewModel,ObjectWrite>> = emptyList()
    private var running=false
    private var direction=0
    var onNotice:(String)->Unit={}
    private fun empty()=pending.isEmpty()&&pendingObjects.isEmpty()
    fun accept(parts:List<Pair<InkViewModel,List<InkStroke>>>,repo:InkRepository)=prepare(parts.map{it.first to InkMutation.Replace(emptyList(),it.second)},emptyList(),repo)
    private fun prepare(ink:List<Pair<InkViewModel,InkMutation>>,objects:List<Pair<PageObjectViewModel,List<PageObject>>>,repo:InkRepository){
        check(empty())
        ink.forEach{it.first.validateChange(it.second)};objects.forEach{it.first.validateExternal(it.second)}
        pending=ink.map{it.first to it.first.prepareChange(it.second)}
        pendingObjects=objects.map{it.first to it.first.prepareExternal(it.second)}
        direction=0;mutable.value=!empty();if(!empty())retry(repo)
    }
    fun erase(targets:List<PageEraseTarget>,radius:Float,whole:Boolean,onlyHighlighter:Boolean,repo:InkRepository){
        if(running||!empty())return
        val snapshots=targets.map{it.ink.ui.value};val originals=targets.map{it.objects.ui.value.objects}
        running=true;mutable.value=true
        viewModelScope.launch {
            try {
                val ink=withContext(Dispatchers.Default){targets.mapIndexedNotNull{i,t->ensureActive();t.ink.eraseChange(snapshots[i].strokes,t.path,radius,whole,onlyHighlighter)?.let{t.ink to it}}}
                check(targets.indices.all{i->targets[i].ink.ui.value.revision==snapshots[i].revision&&targets[i].ink.ui.value.queued==0&&targets[i].objects.ui.value.objects==originals[i]})
                val objects=if(onlyHighlighter)emptyList()else targets.mapNotNull{t->t.objects.erasedBeauty(t.path,radius,whole).takeIf{it!=t.objects.ui.value.objects}?.let{t.objects to it}}
                running=false;mutable.value=false;prepare(ink,objects,repo)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){running=false;mutable.value=false;onNotice("页面已变化，本次擦除未应用，请重试。")}
        }
    }
    private data class Member(val history:EditorHistory,val identity:(Boolean)->Any?,val ready:(Boolean)->Boolean,
        val prepare:(Boolean)->Unit,val register:(Boolean,()->Unit)->Unit)
    fun retry(repo:InkRepository){
        if(running||empty())return
        running=true;failure.value=false
        viewModelScope.launch {
            val captured=pending;val capturedObjects=pendingObjects
            val result=try{withContext(Dispatchers.IO){repo.saveCanvasBatch(captured.map{it.second},capturedObjects.map{it.second})}}
            catch(_:Exception){CanvasBatchResult(failure=InkCommitResult.Unknown)}
            if(result.failure==null){
                fun finishInk(){captured.zip(result.ink).forEach{(pair,r)->pair.first.completeGroup(pair.second,r)}}
                fun finishObjects(){capturedObjects.zip(result.objects).forEach{(pair,r)->pair.first.completeExternal(r)}}
                // The inverse unwinds the domain history in the opposite order.
                if(direction==-1){finishObjects();finishInk()}else{finishInk();finishObjects()}
                val members=captured.map{(m,_)->Member(m.history,m::historyIdentity,{back->m.ui.value.let{if(back)it.canUndo else it.canRedo}},
                    {back->pending=pending+(m to m.prepareHistoryGroup(back))},m::registerGroupHistory)}+
                    capturedObjects.map{(m,_)->Member(checkNotNull(m.history),m::historyIdentity,m::historyReady,
                        {back->pendingObjects=pendingObjects+(m to m.prepareHistoryGroup(back))},m::registerGroupHistory)}
                if(members.size>1){
                    val back=direction!=-1;val identities=members.map{it.identity(back)}
                    val heads=members.associate{it.history to it.history.state.value}
                    val action:()->Unit={
                        if(empty()&&members.zip(identities).all{(m,key)->m.identity(back)===key&&m.ready(back)&&(if(back)m.history.state.value.undo==heads[m.history]?.undo else m.history.state.value.redo==heads[m.history]?.redo)}){
                            members.forEach{it.prepare(back)};direction=if(back)-1 else 1;mutable.value=true;retry(repo)
                        }else onNotice("请先撤销相关页上较新的编辑，再撤销这次跨页操作。")
                    }
                    members.forEach{it.register(back,action)}
                }
                pending=emptyList();pendingObjects=emptyList();mutable.value=false
            }else failure.value=true
            running=false
        }
    }
    /** Called only after the user confirms reading the durable pages instead of retaining this draft. */
    fun readSaved(){
        if(running||!failure.value)return
        pending.forEach{(m,c)->m.completeGroup(c,InkCommitResult.Conflict);m.discardRejectedDraft()}
        pendingObjects.forEach{it.first.discardExternal()}
        pending=emptyList();pendingObjects=emptyList();failure.value=false;mutable.value=false
    }
}
