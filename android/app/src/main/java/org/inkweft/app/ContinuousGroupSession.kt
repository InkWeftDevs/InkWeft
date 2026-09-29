// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*

/** Connect native gesture journals to author transactions; UI history is attached only after commit. */
internal class ContinuousGroupSession(private val book:String,private val repo:InkRepository):ViewModel(){
    private data class Capture(val prepared:Deferred<InkGroupPrefix>,var latest:InkStroke,var job:Job?=null,var cancelled:Boolean=false)
    private val captures=mutableMapOf<String,Capture>()
    private val active=MutableStateFlow(false);val busy=active.asStateFlow()
    private var operationActive=false
    private val cancellations=MutableStateFlow(0)
    private fun refreshBusy(){active.value=operationActive||cancellations.value>0}
    private fun operation(value:Boolean){operationActive=value;refreshBusy()}
    internal suspend fun awaitCancellationSettled(){cancellations.first{it==0}}
    private val error=MutableStateFlow<String?>(null);val problem=error.asStateFlow()
    private var frozenPages=emptyList<String>();private var origin=0;private var opened=false
    private var tail:Job?=null
    lateinit var writer:ContinuousInkWriter
    var modelFor:(String)->InkViewModel={kotlin.error("Writer unavailable")}
    var notice:(String)->Unit={}
    fun begin(pages:List<String>,page:String){frozenPages=pages.toList();origin=pages.indexOf(page);require(origin>=0)}
    private fun capture(stroke:InkStroke):Capture=captures.getOrPut(stroke.id){
        val pages=frozenPages;val start=origin;val previous=tail
        Capture(viewModelScope.async{previous?.join();writer.awaitSettled();withContext(Dispatchers.IO){repo.captureGroup(book,pages,start,stroke)}},stroke)
    }
    fun checkpoint(stroke:InkStroke){
        val c=capture(stroke);if(c.cancelled)return;c.latest=stroke
        if(c.job?.isActive==true)return
        c.job=viewModelScope.launch{
            try{val base=c.prepared.await();var count=0
                while(!c.cancelled&&count<c.latest.samples.size){val latest=c.latest
                    withContext(Dispatchers.IO){repo.checkpointGroup(base.copy(stroke=latest))};count=latest.samples.size
                }
            }catch(c:CancellationException){throw c}catch(_:Exception){notice("长笔检查点尚未确认，请抬笔保存；已保存内容保留。")}
        }
    }
    fun cancel(id:String){
        val capture=captures[id]?:return
        capture.cancelled=true;capture.job?.cancel()
        cancellations.value++;refreshBusy()
        viewModelScope.launch{
            try{withContext(Dispatchers.IO){repo.cancelGroup(id)};captures.remove(id)}
            catch(_:Exception){error.value="取消结果待核对，恢复材料仍保留"}
            finally{cancellations.value--;refreshBusy()}
        }
    }
    private suspend fun apply(group:InkGroupPrefix,finished:InkStroke?=null){
        writer.awaitSettled()
        val committed=withContext(Dispatchers.IO){repo.sealGroup(group,finished)}
        val parts=committed.group.commands().map{modelFor(it.noteId) to it}
        for((model,_)in parts){model.ui.first{!it.loading}.also{check(!it.readFailed&&!it.processing&&it.queued==0){"GROUP_PAGE_BUSY"}}}
        writer.adoptRecovered(parts,committed.results,repo)
        withContext(Dispatchers.IO){repo.acknowledgeGroup(group.stroke.id)}
    }
    private fun describe(t:Throwable)=when(t.message){
        "GROUP_PAGE_UNAVAILABLE","GROUP_BOOK_UNAVAILABLE"->"跨页原目标已回收或不可用，未复活页面；恢复材料保留。"
        "GROUP_REVISION_CONFLICT","GROUP_PAGE_BUSY"->"跨页目标已有新修改，未覆盖原内容；请核对恢复材料。"
        "GROUP_CANCELLED"->"这笔已取消，迟到的保存未应用。"
        else->"跨页保存结果待核对，原页面与恢复材料保留。"
    }
    fun finish(raw:InkStroke,finished:InkStroke){
        val c=capture(raw);c.latest=raw;operation(true)
        val previous=tail
        tail=viewModelScope.launch{
            try{previous?.join();c.job?.join();if(c.cancelled)return@launch
                val base=c.prepared.await();apply(base.copy(stroke=raw),finished);captures.remove(raw.id)
            }catch(c:CancellationException){throw c}catch(t:Exception){error.value=describe(t);notice(error.value!!)}
            finally{if(tail===currentCoroutineContext()[Job])operation(false)}
        }
    }
    fun open(){if(opened)return;opened=true;retry()}
    fun retry(){
        if(active.value)return;operation(true);error.value=null
        tail=viewModelScope.launch{
            try{
                // A failed cancellation must be retried before an OPEN prefix can be recovered.
                for(id in captures.filterValues{it.cancelled}.keys.toList()){
                    withContext(Dispatchers.IO){repo.cancelGroup(id)};captures.remove(id)
                }
                val pending=withContext(Dispatchers.IO){repo.pendingGroups(book)}
                for(group in pending){apply(group);notice("已恢复跨页整笔的已确认部分，可一次撤销。")}
            }catch(c:CancellationException){throw c}catch(t:Exception){error.value=describe(t);notice(error.value!!)}
            finally{operation(false)}
        }
    }
    fun discard(){
        if(active.value)return;operation(true)
        viewModelScope.launch{try{
            withContext(Dispatchers.IO){for(group in repo.pendingGroups(book)){if(!repo.cancelGroup(group.stroke.id))repo.acknowledgeGroup(group.stroke.id)}}
            error.value=null;captures.clear()
        }catch(t:Exception){error.value=describe(t)}finally{operation(false)}}
    }
    class Factory(private val book:String,private val repo:InkRepository):ViewModelProvider.Factory{
        override fun<T:ViewModel>create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(ContinuousGroupSession::class.java));@Suppress("UNCHECKED_CAST")return ContinuousGroupSession(book,repo) as T}
    }
}
