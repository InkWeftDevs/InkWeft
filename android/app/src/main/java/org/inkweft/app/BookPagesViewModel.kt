// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.data.*
import java.util.UUID

data class BookPagesUi(val pages:List<NotebookPageRow> = emptyList(),val selectedId:String?=null,val loading:Boolean=true,val busy:Boolean=false,val error:String?=null)
class BookPagesViewModel(private val bookId:String,private val repo:NotebookPages,private val workspace:WorkspaceRepository):ViewModel(){
    private val mutable=MutableStateFlow(BookPagesUi());val ui=mutable.asStateFlow()
    private var addId:String?=null;private var addAfter:String?=null
    init{viewModelScope.launch{try{
        val saved=withContext(Dispatchers.IO){repo.ensureFirst(bookId);workspace.get(bookId).selectedPageId}
        repo.observe(bookId).collect{rows->mutable.update{old->old.copy(pages=rows,loading=false,selectedId=old.selectedId?.takeIf{id->rows.any{it.id==id}}?:saved.takeIf{id->rows.any{it.id==id}}?:rows.firstOrNull()?.id)}}
    }catch(c:CancellationException){throw c}catch(_:Exception){mutable.update{it.copy(loading=false,error="无法读取页目录，原页未删除。请返回重试。")}}}}
    fun select(id:String){if(ui.value.busy||ui.value.pages.none{it.id==id})return;mutable.update{it.copy(selectedId=id)};viewModelScope.launch{try{withContext(Dispatchers.IO){repo.select(bookId,id)}}catch(c:CancellationException){throw c}catch(_:Exception){mutable.update{it.copy(error="当前页已打开，但最后阅读页未保存。")}}}}
    fun add(){
        if(ui.value.busy)return
        val selected=ui.value.pages.firstOrNull{it.id==ui.value.selectedId}?:return
        if(selected.world)return
        if(addId==null){addId=UUID.randomUUID().toString();addAfter=selected.id}
        val id=checkNotNull(addId);val after=checkNotNull(addAfter);mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch{try{val p=withContext(Dispatchers.IO){repo.addAfter(bookId,after,id)};addId=null;addAfter=null;mutable.update{it.copy(busy=false,selectedId=p.id)};withContext(Dispatchers.IO){repo.select(bookId,p.id)}}catch(c:CancellationException){throw c}catch(_:Exception){mutable.update{it.copy(busy=false,error="新增页结果待核对；再次点添加将核对原操作，不重复创建。")}}}
    }
    fun clearError(){mutable.update{it.copy(error=null)}}
    class Factory(private val id:String,private val repo:NotebookPages,private val workspace:WorkspaceRepository):ViewModelProvider.Factory{
        override fun <T:ViewModel> create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(BookPagesViewModel::class.java));@Suppress("UNCHECKED_CAST")return BookPagesViewModel(id,repo,workspace) as T}
    }
}
