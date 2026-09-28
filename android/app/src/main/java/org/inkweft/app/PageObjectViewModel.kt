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
    private var external=false
    private val groupUndo=java.util.IdentityHashMap<List<PageObject>,()->Unit>()
    private val groupRedo=java.util.IdentityHashMap<List<PageObject>,()->Unit>()
    private var reading=false
    var history:EditorHistory?=null
    private var beautyJob:Job?=null
    private var beautyKnown:Set<String>?=null
    private var beautyKey:String?=null
    private var latestInk=InkUi()
    private var lastAutomatic:String?=null
    private val beautyState=MutableStateFlow<String?>(null)
    val beautyStatus=beautyState.asStateFlow()
    fun observeBeauty(ink:InkUi,writing:Boolean,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
        latestInk=ink
        if(!options.enabled||options.keepInk){beautyJob?.cancel();beautyJob=null;beautyKnown=null;beautyKey=null;beautyState.value=null;return}
        if(ink.loading)return
        if(beautyKnown==null){beautyKnown=ink.strokes.map{it.id}.toSet();return}
        if(writing||ink.queued>0||ink.processing){beautyJob?.cancel();beautyJob=null;beautyKey=null;beautyState.value=null;return}
        if(ink.blocked!=null||ink.readFailed||state.value.loading||state.value.busy||state.value.pending)return
        val suppressed=snapshot.objects.flatMap{it.sourceStrokeIds}.toSet()
        val fresh=ink.strokes.filter{it.id !in beautyKnown!!&&it.id !in suppressed&&it.pen!=InkPen.HIGHLIGHTER}
        if(fresh.isEmpty())return
        val key="${ink.revision}:$options"
        if(key==beautyKey)return
        beautyKey=key;beautyJob?.cancel()
        beautyJob=viewModelScope.launch {
            delay(750)
            val previous=snapshot.objects.find{it.id==lastAutomatic&&!it.hidden}
            val freshBounds=fresh.map{it.bounds()}.reduce{a,b->a.union(b)}
            val merge=previous?.takeIf{it.bounds().padded(options.size*2.0).intersects(freshBounds)}
            val sources=fresh
            if(sources.size>256){beautyState.value="这段字较长，可分段框选美化";return@launch}
            convertBeauty(sources,ink.revision,options,world,app,merge?.id,true)
        }
    }
    fun beautify(selection:SelectedInk,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
        beautyJob?.cancel()
        beautyJob=viewModelScope.launch{convertBeauty(selection.strokes.filter{it.pen!=InkPen.HIGHLIGHTER},selection.revision,options,world,app,null,false)}
    }
    private suspend fun convertBeauty(strokes:List<InkStroke>,revision:Long,options:BeautyOptions,world:Boolean,app:InkWeftApplication,replace:String?,automatic:Boolean){
        if(strokes.isEmpty()||strokes.size>256)return
        val objectRevision=snapshot.revision
        beautyState.value="正在美化…"
        try{
            val result=app.handwriting.recognize(strokes,language=options.language){_,_->}
            if(snapshot.revision!=objectRevision||state.value.busy||state.value.pending||latestInk.queued>0||latestInk.revision!=revision){beautyState.value=null;return}
            if(result.text.isBlank()){beautyState.value="未识别到文字，已保留原迹";return}
            val fresh=beautyObject(strokes,result,options,world,appId())
            val previous=snapshot.objects.find{it.id==replace&&!it.hidden}
            val combined=previous?.let{appendBeauty(it,fresh)}
            val o=combined?:fresh
            change(snapshot.objects.filterNot{combined!=null&&it.id==replace}+o,expectedInk=revision)
            if(automatic){lastAutomatic=o.id;beautyKnown=beautyKnown.orEmpty()+strokes.map{it.id}}
            beautyState.value=null
        }catch(c:CancellationException){beautyState.value=null;throw c}
        catch(_:Exception){beautyState.value="这段字暂未美化，已保留原迹"}
    }
    private fun appId()=UUID.randomUUID().toString()
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
    fun retry(){val p=pending?:return;if(state.value.busy||external)return
        state.value=state.value.copy(objects=p.after,busy=true,pending=true,error=null)
        viewModelScope.launch{
            try{
                val revision=withContext(Dispatchers.IO){repo.save(pageId,p.before.revision,p.id,p.after,p.expectedInk)}
                completeExternal(revision)
            }catch(c:CancellationException){throw c}catch(_:Exception){publish("对象保存尚未确认。请核对重试；如有版本冲突，可重新读取已保存对象。")}
        }
    }
    internal fun validateExternal(objects:List<PageObject>){check(!state.value.loading&&!state.value.busy&&!state.value.pending);PageObjectCodec.encode(objects)}
    internal fun prepareExternal(objects:List<PageObject>,direction:Int=0):ObjectWrite {
        validateExternal(objects);val p=Pending(UUID.randomUUID().toString(),snapshot,objects.toList(),direction)
        pending=p;external=true;state.value=state.value.copy(objects=p.after,busy=true,pending=true,error=null)
        return ObjectWrite(pageId,p.before.revision,p.id,p.after)
    }
    internal fun completeExternal(revision:Long){
        val p=checkNotNull(pending)
        when(p.direction){-1->{undo.removeLast();redo.addLast(p.before.objects)};1->{redo.removeLast();undo.addLast(p.before.objects)};else->{undo.addLast(p.before.objects);redo.clear()}}
        while(undo.size>10)undo.removeFirst()
        snapshot=ObjectSnapshot(revision,p.after);pending=null;external=false;history?.committed(EditDomain.OBJECT,p.direction);publish()
    }
    internal fun discardExternal(){external=false;state.value=state.value.copy(busy=false);reload()}
    internal fun historyIdentity(back:Boolean)=if(back)undo.lastOrNull()else redo.lastOrNull()
    internal fun historyReady(back:Boolean)=!state.value.loading&&!state.value.busy&&!state.value.pending&&if(back)undo.isNotEmpty()else redo.isNotEmpty()
    internal fun prepareHistoryGroup(back:Boolean)=prepareExternal(checkNotNull(historyIdentity(back)),if(back)-1 else 1)
    internal fun registerGroupHistory(back:Boolean,action:()->Unit){historyIdentity(back)?.let{(if(back)groupUndo else groupRedo)[it]=action}}
    fun undo(){if(undo.isNotEmpty()){groupUndo[undo.last()]?.let{it();return};change(undo.last(),-1)}}
    fun redo(){if(redo.isNotEmpty()){groupRedo[redo.last()]?.let{it();return};change(redo.last(),1)}}
    fun put(value:PageObject){val old=snapshot.objects;change(if(old.any{it.id==value.id})old.map{if(it.id==value.id)value else it}else old+value)}
    fun delete(id:String)=deleteObjects(setOf(id))
    fun deleteObjects(ids:Set<String>){change(snapshot.objects.mapNotNull{if(it.id !in ids)it else if(it.sourceStrokeIds.isNotEmpty())it.copy(hidden=true)else null})}
    fun restoreOriginal(id:String)=change(snapshot.objects.filterNot{it.id==id})
    fun eraseTapes(path:List<InkSample>,radius:Float){change(erasedTapes(path,radius))}
    internal fun erasedTapes(path:List<InkSample>,radius:Float)=snapshot.objects.filterNot{ObjectGeometry.intersectsTape(it,path,radius)}
    fun eraseBeauty(path:List<InkSample>,radius:Float,whole:Boolean){
        if(path.isNotEmpty())change(erasedBeauty(path,radius,whole))
    }
    internal fun erasedBeauty(path:List<InkSample>,radius:Float,whole:Boolean):List<PageObject>{
        if(path.isEmpty())return snapshot.objects
        fun hits(bounds:CanvasBounds)=path.zipWithNext().ifEmpty{listOf(path[0] to path[0])}.any{(a,b)->
            // Segment/rectangle intersection, not the bounding box of an entire winding gesture.
            val box=bounds.padded(radius.toDouble())
            var lo=0.0;var hi=1.0
            fun slab(start:Double,delta:Double,min:Double,max:Double):Boolean{if(delta==0.0)return start in min..max;val a=(min-start)/delta;val b=(max-start)/delta;lo=maxOf(lo,minOf(a,b));hi=minOf(hi,maxOf(a,b));return lo<=hi}
            slab(a.x.toDouble(),(b.x-a.x).toDouble(),box.left,box.right)&&slab(a.y.toDouble(),(b.y-a.y).toDouble(),box.top,box.bottom)
        }
        if(!whole&&snapshot.objects.any{!it.hidden&&(it.sourceStrokeIds.isNotEmpty()||it.glyphs.isNotEmpty())&&hits(it.bounds())&&(it.erasures.size>=InkLimits.MAX_CUTS||it.erasures.sumOf{c->c.points.size}+path.size>InkLimits.MAX_CUT_POINTS)}){
            publish("这段文字的局部擦除次数已达上限，可撤销一次擦除或使用整字擦除。");return snapshot.objects
        }
        return snapshot.objects.mapNotNull{o->
            if(o.hidden||(o.sourceStrokeIds.isEmpty()&&o.glyphs.isEmpty())||!hits(o.bounds()))o else {
                val before=TextStyles.positioned(o)
                val after=before.map{g->if(g.hidden||!hits(CanvasBounds((o.x+g.x).toDouble(),(o.y+g.y).toDouble(),(o.x+g.x+g.width).toDouble(),(o.y+g.y+g.height).toDouble())))g else g.copy(hidden=true)}
                if(after==before)o else if(whole){if(after.all{it.hidden}&&o.sourceStrokeIds.isEmpty())null else o.copy(glyphs=after,hidden=after.all{it.hidden})} else {
                    // Keep author text and geometry; only subtract the swept area from existing glyphs.
                    val cut=TextErasePath(0,o.text.length,radius,path.map{TextErasePoint(it.x-o.x,it.y-o.y)})
                    o.copy(glyphs=before,erasures=o.erasures+cut)
                }
            }
        }
    }
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
