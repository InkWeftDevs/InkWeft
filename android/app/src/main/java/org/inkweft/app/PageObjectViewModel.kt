// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class ObjectsUi(val objects:List<PageObject> = emptyList(),val loading:Boolean=true,
    val busy:Boolean=false,val error:String?=null,val pending:Boolean=false,val undo:Boolean=false,val redo:Boolean=false)
internal class PageObjectViewModel(private val pageId:String,private val repo:PageObjectRepository):ViewModel() {
    private val state=MutableStateFlow(ObjectsUi());val ui=state.asStateFlow()
    private var snapshot=ObjectSnapshot()
    private val undo=ArrayDeque<List<PageObject>>()
    private val redo=ArrayDeque<List<PageObject>>()
    private data class Pending(val id:String,val before:ObjectSnapshot,val after:List<PageObject>,val direction:Int,val expectedInk:Long?=null)
    private var pending:Pending?=null
    private var reading=false
    init{reload()}
    fun reload(){if(state.value.busy||reading)return;reading=true;viewModelScope.launch{
        state.value=state.value.copy(loading=true)
        try{snapshot=withContext(Dispatchers.IO){repo.read(pageId)};pending=null;undo.clear();redo.clear();publish()}
        catch(c:CancellationException){throw c}catch(_:Exception){state.value=state.value.copy(loading=false,error="对象读取失败，请重试。",pending=true)}finally{reading=false}
    }}
    private fun publish(error:String?=null){state.value=ObjectsUi(pending?.after?:snapshot.objects,false,false,error,pending!=null,undo.isNotEmpty(),redo.isNotEmpty())}
    fun change(objects:List<PageObject>,direction:Int=0,expectedInk:Long?=null){
        if(state.value.loading||state.value.busy||state.value.pending||objects==snapshot.objects)return
        try{PageObjectCodec.encode(objects)}catch(_:Exception){publish("对象超过容量：每页最多 32 项、图片与对象总计约 1.6 MB。请减少图片后重试。");return}
        pending=Pending(UUID.randomUUID().toString(),snapshot,objects.toList(),direction,expectedInk);retry()
    }
    fun retry(){val p=pending?:return;if(state.value.busy)return
        state.value=state.value.copy(objects=p.after,busy=true,pending=true,error=null)
        viewModelScope.launch{
            try{
                val revision=withContext(Dispatchers.IO){repo.save(pageId,p.before.revision,p.id,p.after,p.expectedInk)}
                when(p.direction){-1->{undo.removeLast();redo.addLast(p.before.objects)};1->{redo.removeLast();undo.addLast(p.before.objects)};else->{undo.addLast(p.before.objects);redo.clear()}}
                while(undo.size>10)undo.removeFirst()
                snapshot=ObjectSnapshot(revision,p.after);pending=null;publish()
            }catch(c:CancellationException){throw c}catch(_:Exception){publish("对象保存尚未确认。请核对重试；如有版本冲突，可重新读取已保存对象。")}
        }
    }
    fun undo(){if(undo.isNotEmpty())change(undo.last(),-1)}
    fun redo(){if(redo.isNotEmpty())change(redo.last(),1)}
    fun put(value:PageObject){val old=snapshot.objects;change(if(old.any{it.id==value.id})old.map{if(it.id==value.id)value else it}else old+value)}
    fun delete(id:String)=change(snapshot.objects.filterNot{it.id==id})
    /** The ViewModel owns decoding so rotation cannot cancel an accepted camera result. */
    fun importImage(context:android.content.Context,uri:android.net.Uri,world:Boolean,viewport:CanvasViewport,
        cleanup:java.io.File?=null,onSelected:(String)->Unit={}) {
        viewModelScope.launch {
            try {
                ui.first{!it.loading}
                check(!state.value.busy&&!state.value.pending)
                state.value=state.value.copy(busy=true,error=null)
                val bytes=withContext(Dispatchers.IO){CoverImages.read(context.applicationContext,uri,PageObjectCodec.MAX_IMAGE)}
                val size=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,size)
                val width=minOf(500f,500f*size.outWidth/size.outHeight);val height=width*size.outHeight/size.outWidth
                val x=(viewport.centerX-width/2).toFloat();val y=(viewport.centerY-height/2).toFloat()
                val o=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,
                    if(world)x else x.coerceIn(0f,1000f-width),if(world)y else y.coerceIn(0f,1414f-height),width,height,
                    image=java.util.Base64.getEncoder().encodeToString(bytes))
                publish();put(o);onSelected(o.id)
            }catch(c:CancellationException){throw c}catch(_:Exception){publish("图片未能加入。支持静态 JPG、PNG、WebP、HEIF，原图最多 20 MB；可先缩小后重试。")}
            finally{cleanup?.delete()}
        }
    }
    class Factory(private val id:String,private val repo:PageObjectRepository):ViewModelProvider.Factory {
        override fun <T:ViewModel> create(modelClass:Class<T>):T {require(modelClass.isAssignableFrom(PageObjectViewModel::class.java));@Suppress("UNCHECKED_CAST")return PageObjectViewModel(id,repo) as T}
    }
}
