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
    private var erasing=false
    private data class Queued(val model:InkViewModel,val strokes:List<InkStroke>,val scope:LayerWriteScope?)
    private val waiting=ArrayDeque<List<Queued>>()
    private val accepting=MutableStateFlow(true);val canWrite=accepting.asStateFlow()
    private val provisional=MutableStateFlow<Map<InkViewModel,List<InkStroke>>>(emptyMap());val drafts=provisional.asStateFlow()
    private fun refresh(){
        mutable.value=!empty()||waiting.isNotEmpty()||running
        accepting.value=!failure.value&&!erasing&&direction==0&&pendingObjects.isEmpty()&&waiting.size<16
        provisional.value=waiting.flatMap{it}.groupBy({it.model},{it.strokes}).mapValues{it.value.flatten()}
    }
    var onNotice:(String)->Unit={}
    suspend fun awaitSettled(){blocked.first{!it}}
    fun adoptRecovered(parts:List<Pair<InkViewModel,CommitInk>>,results:List<InkCommitResult.Committed>,repo:InkRepository){
        check(!running&&empty()&&waiting.isEmpty())
        parts.zip(results).forEach{(entry,result)->entry.first.adoptRecovered(entry.second,result)}
        linkHistory(parts,emptyList(),true,repo)
    }
    private fun empty()=pending.isEmpty()&&pendingObjects.isEmpty()
    fun accept(parts:List<Pair<InkViewModel,List<InkStroke>>>,repo:InkRepository){
        check(accepting.value&&waiting.size<16){"Writing queue is full"}
        parts.forEach{(model,strokes)->model.validateQueued(strokes+waiting.flatMap{it}.filter{it.model===model}.flatMap{it.strokes})}
        waiting.addLast(parts.map{Queued(it.first,it.second,it.first.capturedLayerScope)});refresh();drain(repo)
    }
    private fun drain(repo:InkRepository){
        if(running||!empty()||failure.value||waiting.isEmpty()){refresh();return}
        val next=waiting.first()
        prepare(next.map{it.model to InkMutation.Replace(emptyList(),it.strokes)},emptyList(),repo,next.associate{it.model to it.scope})
        waiting.removeFirst();refresh()
    }
    fun edit(ink:InkViewModel,objects:PageObjectViewModel,revision:Long,before:List<PageObject>,change:InkMutation?,after:List<PageObject>,repo:InkRepository):Boolean{
        if(running||!empty()||failure.value||waiting.isNotEmpty()||ink.ui.value.revision!=revision||ink.ui.value.queued!=0||objects.ui.value.objects!=before){onNotice("内容已变化，请重新选择");return false}
        return try{prepare(if(change==null)emptyList()else listOf(ink to change),if(after==before)emptyList()else listOf(objects to after),repo);true}
        catch(_:Exception){onNotice("超出页面或容量限制，原内容保留");false}
    }
    private fun prepare(ink:List<Pair<InkViewModel,InkMutation>>,objects:List<Pair<PageObjectViewModel,List<PageObject>>>,repo:InkRepository,scopes:Map<InkViewModel,LayerWriteScope?> = emptyMap()){
        check(empty())
        ink.forEach{it.first.validateChange(it.second)};objects.forEach{it.first.validateExternal(it.second)}
        pending=ink.map{it.first to if(scopes.containsKey(it.first))it.first.prepareChange(it.second,scopes[it.first])else it.first.prepareChange(it.second)}
        pendingObjects=objects.map{it.first to it.first.prepareExternal(it.second)}
        direction=0;if(empty())erasing=false;refresh();if(!empty())retry(repo)
    }
    fun erase(targets:List<PageEraseTarget>,radius:Float,whole:Boolean,onlyHighlighter:Boolean,repo:InkRepository,onlyTape:Boolean=false){
        if(running||!empty()||waiting.isNotEmpty())return
        erasing=true
        val snapshots=targets.map{it.ink.ui.value};val originals=targets.map{it.objects.ui.value.objects}
        running=true;refresh()
        viewModelScope.launch {
            try {
                val ink=if(onlyTape)emptyList()else withContext(Dispatchers.Default){targets.mapIndexedNotNull{i,t->ensureActive();t.ink.eraseChange(snapshots[i].strokes,t.path,radius,whole,onlyHighlighter)?.let{t.ink to it}}}
                check(targets.indices.all{i->targets[i].ink.ui.value.revision==snapshots[i].revision&&targets[i].ink.ui.value.queued==0&&targets[i].objects.ui.value.objects==originals[i]})
                val objects=if(onlyHighlighter)emptyList()else targets.mapNotNull{t->(if(onlyTape)t.objects.erasedTapes(t.path,radius)else t.objects.erasedBeauty(t.path,radius,whole)).takeIf{it!=t.objects.ui.value.objects}?.let{t.objects to it}}
                running=false;mutable.value=false;prepare(ink,objects,repo)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){running=false;erasing=false;refresh();onNotice("页面已变化，本次擦除未应用，请重试。")}
        }
    }
    private data class Member(val history:EditorHistory,val identity:(Boolean)->Any?,val ready:(Boolean)->Boolean,
        val prepare:(Boolean)->Unit,val register:(Boolean,()->Unit)->Unit)
    private fun linkHistory(captured:List<Pair<InkViewModel,CommitInk>>,capturedObjects:List<Pair<PageObjectViewModel,ObjectWrite>>,back:Boolean,repo:InkRepository){
        val members=captured.map{(m,_)->Member(m.history,m::historyIdentity,{undo->m.ui.value.let{if(undo)it.canUndo else it.canRedo}},
            {undo->pending=pending+(m to m.prepareHistoryGroup(undo))},m::registerGroupHistory)}+
            capturedObjects.map{(m,_)->Member(checkNotNull(m.history),m::historyIdentity,m::historyReady,
                {undo->pendingObjects=pendingObjects+(m to m.prepareHistoryGroup(undo))},m::registerGroupHistory)}
        if(members.size<=1)return
        val identities=members.map{it.identity(back)};val heads=members.associate{it.history to it.history.state.value}
        val action:()->Unit={
            if(empty()&&members.zip(identities).all{(m,key)->m.identity(back)===key&&m.ready(back)&&(if(back)m.history.state.value.undo==heads[m.history]?.undo else m.history.state.value.redo==heads[m.history]?.redo)}){
                members.forEach{it.prepare(back)};direction=if(back)-1 else 1;mutable.value=true;retry(repo)
            }else onNotice("请先撤销相关页上较新的编辑，再撤销这次跨页操作。")
        }
        members.forEach{it.register(back,action)}
    }
    fun retry(repo:InkRepository){
        if(running||empty())return
        running=true;failure.value=false;refresh()
        viewModelScope.launch {
            val captured=pending;val capturedObjects=pendingObjects
            val result=try{withContext(Dispatchers.IO){repo.saveCanvasBatch(captured.map{it.second},capturedObjects.map{it.second})}}
            catch(_:Exception){CanvasBatchResult(failure=InkCommitResult.Unknown)}
            if(result.failure==null){
                fun finishInk(){captured.zip(result.ink).forEach{(pair,r)->pair.first.completeGroup(pair.second,r)}}
                fun finishObjects(){capturedObjects.zip(result.objects).forEach{(pair,r)->pair.first.completeExternal(r)}}
                // The inverse unwinds the domain history in the opposite order.
                if(direction==-1){finishObjects();finishInk()}else{finishInk();finishObjects()}
                linkHistory(captured,capturedObjects,direction!=-1,repo)
                pending=emptyList();pendingObjects=emptyList();mutable.value=false
            }else failure.value=true
            running=false;erasing=false;direction=0;refresh();if(result.failure==null)drain(repo)
        }
    }
    /** Called only after the user confirms reading the durable pages instead of retaining this draft. */
    fun readSaved(){
        if(running||!failure.value)return
        pending.forEach{(m,c)->m.completeGroup(c,InkCommitResult.Conflict);m.discardRejectedDraft()}
        pendingObjects.forEach{it.first.discardExternal()}
        pending=emptyList();pendingObjects=emptyList();waiting.clear();failure.value=false;erasing=false;direction=0;refresh()
    }
}
