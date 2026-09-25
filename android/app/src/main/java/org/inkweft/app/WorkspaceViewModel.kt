// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.*

/** Shelf and presentation state, never replaces a NoteDraft or ink author head. */
class WorkspaceViewModel(app:Application):AndroidViewModel(app){
    private val repo=(app as InkWeftApplication).workspaceRepository
    private val mutable=MutableStateFlow<Map<String,WorkspaceRow>>(emptyMap())
    val entries=mutable.asStateFlow()
    private val counts=MutableStateFlow<Map<String,LibraryInkCount>>(emptyMap())
    val inkCounts=counts.asStateFlow()
    private val failure=MutableStateFlow<String?>(null)
    val error=failure.asStateFlow()
    private val working=MutableStateFlow(false);val busy=working.asStateFlow()
    private val viewWrites=Mutex()
    private val viewGeneration=mutableMapOf<String,Long>()
    init {
        viewModelScope.launch{try{repo.observe().collect{rows->mutable.value=rows.associateBy{it.noteId}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="无法读取资料分类，原数据未删除。"}}
        viewModelScope.launch{try{repo.observeInkCounts().collect{rows->counts.value=rows.associateBy{it.noteId}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="缩略统计读取失败，可继续尝试打开笔记。"}}
    }
    private val liveViews=mutableMapOf<String,CanvasViewport>()
    fun cachedViewport(id:String)=liveViews[id]
    fun ensure(id:String){viewModelScope.launch{try{val row=withContext(Dispatchers.IO){repo.get(id)};mutable.update{it+(id to row)}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="无法读取页面设置"}}}
    fun clearError(){failure.value=null}
    fun create(title:String,world:Boolean,paper:PaperStyle,onCreated:(Note)->Unit){
        if(working.value)return;working.value=true
        viewModelScope.launch{try{onCreated(withContext(Dispatchers.IO){repo.create(title,world,paper)})}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="新建结果待核对。请先检查资料库，勿重复点击；原笔记未改动。"}finally{working.value=false}}
    }
    fun organize(row:WorkspaceRow,folder:String=row.folder,tags:String=row.tags,favorite:Boolean=row.favorite,trash:Boolean=row.trashedAt!=null){
        viewModelScope.launch{try{if(!withContext(Dispatchers.IO){repo.organize(row.noteId,row.revision,folder,tags,favorite,trash)})failure.value="分类已被其他操作修改，请检查最新状态后重试。"}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="未能确认分类操作，请检查当前状态。笔记内容没有被删除。"}}
    }
    fun paper(id:String,style:PaperStyle){viewModelScope.launch{try{withContext(Dispatchers.IO){repo.changePaper(id,style)}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="未能保存纸张设置"}}}
    fun viewport(id:String,v:CanvasViewport){
        liveViews[id]=v
        val token=(viewGeneration[id]?:0)+1;viewGeneration[id]=token
        viewModelScope.launch{try{viewWrites.withLock{if(viewGeneration[id]==token)withContext(Dispatchers.IO){repo.saveViewport(id,v)}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="视图位置未保存；笔迹保存状态不受影响。"}}
    }
}
