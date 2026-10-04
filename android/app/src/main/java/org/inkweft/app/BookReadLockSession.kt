// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device/session state for one book. No author command or backup object is created. */
internal class BookReadLockViewModel(private val saved:SavedStateHandle):ViewModel(){
    private val mode=MutableStateFlow(saved.get<Boolean>("readLock.enabled")?:false)
    val readOnly=mode.asStateFlow()
    val canWrite:Boolean get()=!mode.value
    private val guards=mutableMapOf<String,Boolean>()
    // These writers share the Activity lifetime. Keep visited pages across view
    // recreation so an offscreen recognition job also blocks a mode transition.
    private val objectWriters=mutableMapOf<String,PageObjectViewModel>()
    private val drafts=MutableStateFlow(false)
    val hasDraft=drafts.asStateFlow()
    var reason=""
        private set

    fun observeObjects(page:String,writer:PageObjectViewModel){objectWriters[page]=writer}

    fun guard(key:String,blocked:Boolean,draft:Boolean=false){
        if(blocked)guards[key]=draft else guards.remove(key)
        drafts.value=guards.values.any{it}
    }
    fun request(value:Boolean,ready:Boolean=true):Boolean{
        if(value==mode.value)return true
        if(!canChangeMode(ready))return false
        saved["readLock.enabled"]=value
        mode.value=value
        return true
    }
    /** Recall also needs the guard when the read/write lock itself would not change. */
    fun canChangeMode(ready:Boolean=true):Boolean{
        if(!ready||guards.isNotEmpty()||objectWriters.values.any{it.authorOperationActive}){
            reason="请先抬笔、完成或取消草稿，并核对当前操作，再切换读／写／忆。"
            return false
        }
        reason="";return true
    }
    class Factory:ViewModelProvider.Factory{
        override fun<T:ViewModel>create(modelClass:Class<T>,extras:CreationExtras):T{
            require(modelClass.isAssignableFrom(BookReadLockViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BookReadLockViewModel(extras.createSavedStateHandle()) as T
        }
    }
}

@Composable internal fun rememberBookReadLock(book:String):BookReadLockViewModel=
    viewModel(key="read-lock-$book",factory=BookReadLockViewModel.Factory())

/** A mounted writer participates in the same transition gate; read inspection is not a draft. */
@Composable internal fun ReadLockGuard(lock:BookReadLockViewModel,key:String,blocked:Boolean,draft:Boolean=false){
    SideEffect{lock.guard(key,blocked,draft)}
    DisposableEffect(lock,key){onDispose{lock.guard(key,false)}}
}
