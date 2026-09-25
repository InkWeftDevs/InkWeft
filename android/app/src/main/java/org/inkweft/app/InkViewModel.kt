// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.InkRepository

data class InkUi(val strokes:List<InkStroke> = emptyList(),val loading:Boolean=true,val readFailed:Boolean=false,
    val blocked:InkCommitResult?=null,val queued:Int=0,val canStart:Boolean=false,val canUndo:Boolean=false,
    val canRedo:Boolean=false,val revision:Long=0,val processing:Boolean=false)
class InkViewModel(private val noteId:String,private val repository:InkRepository):ViewModel(){
    private val mutable=MutableStateFlow(InkUi());val ui=mutable.asStateFlow()
    private var session:InkSession?=null
    private var writing=false;private var reading=false;private var erasing=false
    init{load()}
    fun load(){if(session!=null||reading)return;reading=true;mutable.value=InkUi();viewModelScope.launch{try{val p=withContext(Dispatchers.IO){repository.read(noteId)};session=InkSession(p);publish()}catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=InkUi(loading=false,readFailed=true)}finally{reading=false}}}
    fun discardRejectedDraft(){val captured=session?:return;if(reading||erasing||captured.blocked !in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))return;reading=true;viewModelScope.launch{try{val p=withContext(Dispatchers.IO){repository.read(noteId)};if(session===captured)session=InkSession(p);publish()}catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=mutable.value.copy(readFailed=true)}finally{reading=false}}}
    private fun publish(){val s=session?:return;mutable.value=InkUi(s.visibleDraft(),false,false,s.blocked,s.queued,s.canStart&&!erasing,s.canUndo&&!erasing,s.canRedo&&!erasing,s.page.revision,erasing)}
    fun accept(stroke:InkStroke){val s=session?:return;s.enqueue(InkMutation.Add(stroke),finishInFlight=true);publish();pump()}
    fun erase(ids:List<String>){if(ids.isEmpty())return;val s=session?:return;s.enqueue(InkMutation.Visibility(ids,false),finishInFlight=true);publish();pump()}
    fun erasePath(path:List<InkSample>){
        val s=session?:return;if(erasing)return
        val snapshot=s.visibleDraft();erasing=true;publish()
        viewModelScope.launch{try{val ids=withContext(Dispatchers.Default){snapshot.filter{ensureActive();InkHitTest.hits(it,path)}.map{it.id}};if(session===s && ids.isNotEmpty())erase(ids)}finally{erasing=false;publish()}}
    }
    fun undo(){val s=session?:return;if(!s.canUndo||erasing)return;s.requestUndo();publish();pump()}
    fun redo(){val s=session?:return;if(!s.canRedo||erasing)return;s.requestRedo();publish();pump()}
    fun retry(){session?.retry();publish();pump()}
    private fun pump(){if(writing)return;val s=session?:return;writing=true;viewModelScope.launch{try{while(true){val c=s.nextCommand()?:break;publish();val result=try{withContext(Dispatchers.IO){repository.save(c)}}catch(_:CancellationException){s.complete(c,InkCommitResult.Unknown);publish();return@launch}catch(_:Exception){InkCommitResult.Unknown};s.complete(c,result);publish();if(result !is InkCommitResult.Committed)break}}finally{writing=false;publish()}}}
    class Factory(private val id:String,private val repo:InkRepository):ViewModelProvider.Factory{override fun <T:ViewModel> create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(InkViewModel::class.java));@Suppress("UNCHECKED_CAST")return InkViewModel(id,repo) as T}}
}
