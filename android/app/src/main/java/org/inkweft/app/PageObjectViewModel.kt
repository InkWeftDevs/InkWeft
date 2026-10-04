// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class ObjectsUi(val objects:List<PageObject> = emptyList(),val loading:Boolean=true,
    val busy:Boolean=false,val error:String?=null,val pending:Boolean=false,val undo:Boolean=false,val redo:Boolean=false,val automaticPending:Boolean=false)
internal class PageObjectViewModel(private val pageId:String,private val repo:PageObjectRepository,
    private val recognition:(suspend (List<InkStroke>,BeautyLanguage,Boolean)->RecognizedWriting)?=null):ViewModel() {
    private val state=MutableStateFlow(ObjectsUi());val ui=state.asStateFlow()
    private var snapshot=ObjectSnapshot()
    internal val revision get()=snapshot.revision
    private val undo=ArrayDeque<List<PageObject>>()
    private val redo=ArrayDeque<List<PageObject>>()
    private data class Pending(val id:String,val before:ObjectSnapshot,val after:List<PageObject>,val direction:Int,val expectedInk:Long?=null,val accepted:(()->Unit)?=null,val automatic:Boolean=false,val originals:List<ImageSource> = emptyList())
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
    private var latestWriting=false
    private var latestBeautyOptions=BeautyOptions()
    private val beautyAttempts=mutableMapOf<Set<String>,String>()
    private val beautyWaiting=linkedMapOf<Set<String>,BeautyReview>()
    private var diagnostics:AppDiagnostics?=null
    private var lastAutomatic:String?=null
    private val beautyState=MutableStateFlow<String?>(null)
    val beautyStatus=beautyState.asStateFlow()
    private val reviewState=MutableStateFlow<BeautyReview?>(null)
    val beautyReview=reviewState.asStateFlow()
    private fun current(ink:Long,objects:Long)=latestInk.revision==ink&&snapshot.revision==objects&&!latestWriting&&!latestInk.loading&&!latestInk.readFailed&&latestInk.blocked==null&&!state.value.loading&&!state.value.busy&&!state.value.pending&&latestInk.queued==0&&!latestInk.processing
    fun dismissBeauty(){reviewState.value?.let{beautyWaiting.remove(it.strokes.map{it.id}.toSet());runCatching{it.trace?.finish("KEPT_ORIGINAL")}};reviewState.value=null;beautyState.value=null;showWaitingBeauty()}
    fun openBeauty(){beautyJob?.cancel();beautyJob=null;beautyKey=null;reviewState.value=reviewState.value?.copy(open=true,preview=false)}
    fun previewBeauty(show:Boolean){reviewState.value=reviewState.value?.copy(preview=show)}
    fun reviseBeauty(text:String,options:BeautyOptions){
        val r=reviewState.value?:return
        if(!current(r.inkRevision,r.objectRevision)){dismissBeauty();return}
        reviewState.value=prepareBeautyReview(r.strokes,r.result.corrected(text),options,r.world,r.inkRevision,r.objectRevision,r.previous,r.affected,r.automatic,latestInk.strokes,snapshot.objects).copy(open=true,preview=r.preview,trace=r.trace)
    }
    fun acceptBeauty(){
        val r=reviewState.value?:return;val o=r.candidate?:return
        if(!current(r.inkRevision,r.objectRevision)){dismissBeauty();return}
        commitBeauty(r,o,false)
    }
    private fun commitBeauty(r:BeautyReview,o:PageObject,automaticCommit:Boolean=r.automatic){
        val replaced=r.previous?.id?.takeIf{it==o.id}
        runCatching{r.trace?.let{trace->
            trace.record("automatic_commit",automaticCommit)
            trace.record("submitted_candidate",org.json.JSONObject().put("object_id",o.id).put("source_ids",org.json.JSONArray(o.sourceStrokeIds))
                .put("characters",o.text.length).put("text",if(trace.privateAttachments)o.text else org.json.JSONObject.NULL))
            trace.finish("SAVE_PENDING")
        }}
        change(snapshot.objects.filterNot{it.id==replaced}+o,expectedInk=r.inkRevision,automatic=automaticCommit,accepted={
            diagnostics?.event(DiagnosticCode.BEAUTY_COMMIT,DiagnosticResult.SAVED,r.strokes.size.toLong(),o.text.length.toLong())
            runCatching{r.trace?.record("committed_object_revision",snapshot.revision);r.trace?.finish("SAVED")}
            beautyKnown=beautyKnown.orEmpty()+r.strokes.map{it.id};if(r.automatic)lastAutomatic=o.id
            val ids=r.strokes.map{it.id}.toSet();beautyWaiting.keys.removeAll{key->key.any{it in ids}}
            reviewState.value=null;beautyState.value=null;showWaitingBeauty()
        })
        if(pending==null){
            val review=r.copy(reason=state.value.error?:"结果尚未保存，已保留原迹",open=!automaticCommit)
            if(r.automatic)beautyWaiting[r.strokes.map{it.id}.toSet()]=review
            reviewState.value=review;beautyState.value="美化待校对"
            runCatching{r.trace?.finish("NOT_ENQUEUED")}
        }
    }
    private fun showWaitingBeauty(){
        if(reviewState.value!=null||!current(latestInk.revision,snapshot.revision))return
        val byId=latestInk.strokes.associateBy{it.id}
        // An unchanged ID does not mean unchanged ink: cuts and recovered samples are
        // immutable new values. Never rebase their old candidate onto a newer page CAS.
        beautyWaiting.entries.removeAll{(_,r)->r.options!=latestBeautyOptions||r.strokes.any{s->
            val now=byId[s.id];now==null||(now!==s&&!InkStrokeCodec.encode(now).contentEquals(InkStrokeCodec.encode(s)))
        }}
        val r=beautyWaiting.values.firstOrNull()?:return
        val previous=r.previous?.let{old->snapshot.objects.find{it.id==old.id}}
        reviewState.value=prepareBeautyReview(r.strokes,r.result,r.options,r.world,latestInk.revision,snapshot.revision,previous,r.affected,true,latestInk.strokes,snapshot.objects).let{it.copy(reason=it.reason?:r.reason,trace=r.trace)}
        beautyState.value="美化待校对"
    }
    fun observeBeauty(ink:InkUi,writing:Boolean,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
        latestInk=ink;latestWriting=writing;latestBeautyOptions=options;diagnostics=app.diagnostics
        reviewState.value?.let{if(it.inkRevision!=ink.revision||it.objectRevision!=snapshot.revision||it.automatic&&!it.open&&it.options!=options)reviewState.value=null}
        if(!options.enabled||options.keepInk){beautyJob?.cancel();beautyJob=null;beautyKnown=null;beautyKey=null;beautyAttempts.clear();beautyWaiting.clear();beautyState.value=null;return}
        if(ink.loading)return
        if(reviewState.value?.open==true)return
        if(beautyKnown==null){beautyKnown=ink.strokes.map{it.id}.toSet();return}
        if(writing||ink.queued>0||ink.processing){beautyJob?.cancel();beautyJob=null;beautyKey=null;beautyState.value=null;return}
        if(ink.blocked!=null||ink.readFailed||state.value.loading||state.value.busy||state.value.pending)return
        val suppressed=snapshot.objects.flatMap{it.sourceStrokeIds}.toSet()
        val fresh=ink.strokes.filter{it.id !in beautyKnown!!&&it.id !in suppressed&&it.pen!=InkPen.HIGHLIGHTER}
        if(fresh.isEmpty()){showWaitingBeauty();return}
        val key="${ink.revision}:$options"
        if(key==beautyKey){showWaitingBeauty();return}
        beautyKey=key;beautyJob?.cancel()
        beautyJob=viewModelScope.launch {
            delay(750)
            // Retry only a spatial unit whose immutable source or options changed. A rejection
            // remains author ink; it is never promoted to beautyKnown to hide unfinished work.
            val units=try{withContext(Dispatchers.Default){HandwritingLines.split(fresh)}}catch(c:CancellationException){throw c}
                catch(_:Exception){beautyState.value="这段字较长，可分段框选美化";return@launch}
            val byId=ink.strokes.associateBy{it.id}
            val active=ink.strokes.map{it.id}.toSet()
            beautyAttempts.keys.removeAll{!active.containsAll(it)}
            beautyWaiting.keys.removeAll{!active.containsAll(it)}
            for(unit in units){
            ensureActive()
            if(!current(ink.revision,snapshot.revision))return@launch
            val freshBounds=unit.bounds
            // A late dot/radical can touch an older fragment, not only the last converted word.
            val touched=snapshot.objects.filter{!it.hidden&&it.textRuns.isNotEmpty()}.firstOrNull{o->o.textRuns.any{r->r.sourceIds.mapNotNull{byId[it]}.any{it.bounds().padded(5.0).intersects(freshBounds)}}}
            val previous=touched?:snapshot.objects.find{it.id==lastAutomatic&&!it.hidden}
            val merge=previous?.takeIf{it.bounds().padded(options.size*2.0).intersects(freshBounds)}
            val affected=touched?.textRuns?.filter{r->r.sourceIds.mapNotNull{byId[it]}.any{it.bounds().padded(5.0).intersects(freshBounds)}}?.map{it.id}?.toSet().orEmpty()
            val context=touched?.textRuns?.filter{it.id in affected}?.flatMap{it.sourceIds}?.toSet().orEmpty()
            // Every borrowed source belongs to an affected run being replaced. Unchanged
            // neighbouring runs are retained by append/fragment replacement, never suppressed.
            val sources=(ink.strokes.filter{it.id in context}+unit.strokes).distinctBy{it.id}
            val ids=sources.map{it.id}.toSet();val prior=touched?:merge
            val fingerprint=withContext(Dispatchers.Default){
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                sources.sortedBy{it.id}.forEach{digest.update(InkStrokeCodec.encode(it))}
                digest.update(options.toString().toByteArray())
                prior?.let{digest.update(PageObjectCodec.encode(listOf(it)))}
                DiagnosticLog.hash(digest.digest())
            }
            if(beautyAttempts[ids]==fingerprint)continue
            beautyAttempts.keys.removeAll{key->key!=ids&&key.any{it in ids}}
            beautyWaiting.keys.removeAll{key->key.any{it in ids}}
            if(sources.size>256){beautyAttempts[ids]=fingerprint;beautyState.value="这段字较长，可分段框选美化";continue}
            if(convertBeauty(sources,ink.revision,options,world,app,prior,true,affected))beautyAttempts[ids]=fingerprint
            // Each accepted object uses the existing receipt/CAS transaction. Do not start
            // the next unit against the old object revision or a write of unknown outcome.
            if(state.value.busy)state.first{!it.busy}
            if(state.value.pending)return@launch
            }
            showWaitingBeauty()
        }
    }
    fun beautify(selection:SelectedInk,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
        diagnostics=app.diagnostics
        beautyJob?.cancel()
        beautyJob=viewModelScope.launch{convertBeauty(selection.strokes.filter{it.pen!=InkPen.HIGHLIGHTER},selection.revision,options,world,app,null,false)}
    }
    private suspend fun convertBeauty(strokes:List<InkStroke>,revision:Long,options:BeautyOptions,world:Boolean,app:InkWeftApplication,previous:PageObject?,automatic:Boolean,affected:Set<String> = emptySet()):Boolean{
        if(strokes.isEmpty()||strokes.size>256)return false
        val objectRevision=snapshot.revision
        if(!current(revision,objectRevision))return false
        val trace=runCatching{app.beautyDiagnostics.begin(pageId,latestInk,beautyKnown.orEmpty(),snapshot.objects,strokes,options,automatic,previous,affected)}.getOrNull()
        diagnostics?.event(DiagnosticCode.BEAUTY_INPUT,DiagnosticResult.OBSERVED,strokes.size.toLong(),if(automatic)1 else 0)
        beautyState.value="正在美化…"
        try{
            val result=recognition?.invoke(strokes,options.language,false)?:app.handwriting.recognize(strokes,language=options.language,trace=trace)
            if(!current(revision,objectRevision)){trace?.finish("STALE");beautyState.value=null;return false}
            // Quiet time is only scheduling. Compare a second raster margin after checking author versions.
            delay(250)
            val variant=recognition?.invoke(strokes,options.language,true)?:app.handwriting.recognize(strokes,language=options.language,padded=true,trace=trace)
            if(!current(revision,objectRevision)){trace?.finish("STALE");beautyState.value=null;return false}
            val quality=BeautyQuality.decide(strokes,result,variant,true)
            val r=prepareBeautyReview(strokes,result,options,world,revision,objectRevision,previous,affected,automatic,latestInk.strokes,snapshot.objects).copy(trace=trace)
            runCatching{trace?.decision(result,variant,quality,r)}
            if(automatic&&quality.automatic&&r.candidate!=null&&r.reason==null)commitBeauty(r,r.candidate)
            else {
                val review=r.copy(reason=quality.reason?:r.reason,open=!automatic)
                if(automatic)beautyWaiting[strokes.map{it.id}.toSet()]=review
                reviewState.value=review;beautyState.value="美化待校对"
                diagnostics?.event(DiagnosticCode.BEAUTY_REVIEW,DiagnosticResult.REJECTED,strokes.size.toLong(),result.text.length.toLong())
                trace?.finish("REVIEW_UNAPPLIED")
            }
            return true
        }catch(c:CancellationException){runCatching{trace?.finish("CANCELLED")};beautyState.value=null;throw c}
        catch(_:Exception){runCatching{trace?.finish("UNAVAILABLE")};beautyState.value="这段字暂未美化，已保留原迹";return true}
    }
    init{reload()}
    fun reload(){if(state.value.busy||reading)return;reading=true;viewModelScope.launch{
        state.value=state.value.copy(loading=true)
        try{snapshot=withContext(Dispatchers.IO){repo.read(pageId)};pending=null;beautyKey=null;undo.clear();redo.clear();publish()}
        catch(c:CancellationException){throw c}catch(_:Exception){state.value=state.value.copy(loading=false,error="对象读取失败，请重试。",pending=true)}finally{reading=false}
    }}
    private fun publish(error:String?=null){state.value=ObjectsUi(if(pending?.automatic==true)snapshot.objects else pending?.after?:snapshot.objects,false,false,error,pending!=null,undo.isNotEmpty(),redo.isNotEmpty(),pending?.automatic==true)}
    internal var authorAllowed:()->Boolean={true}
    internal val authorOperationActive:Boolean get()=beautyJob?.isActive==true||state.value.busy||state.value.pending
    fun change(objects:List<PageObject>,direction:Int=0,expectedInk:Long?=null,accepted:(()->Unit)?=null,automatic:Boolean=false,originals:List<ImageSource> = emptyList()){
        if(!authorAllowed())return
        if(state.value.loading||state.value.busy||state.value.pending||objects==snapshot.objects)return
        try{PageObjectCodec.encode(objects)}catch(_:Exception){publish("对象超过容量：每页最多 32 项、图片与对象总计约 1.6 MB。请减少图片后重试。");return}
        pending=Pending(UUID.randomUUID().toString(),snapshot,objects.toList(),direction,expectedInk,accepted,automatic,originals);retry()
    }
    fun retry(){val p=pending?:return;if(state.value.busy||external)return
        state.value=state.value.copy(objects=if(p.automatic)snapshot.objects else p.after,busy=true,pending=true,error=null,automaticPending=p.automatic)
        viewModelScope.launch{
            try{
                val revision=withContext(Dispatchers.IO){repo.save(pageId,p.before.revision,p.id,p.after,p.expectedInk,p.originals)}
                completeExternal(revision)
            }catch(c:CancellationException){throw c}catch(_:ImageOriginalCapacity){
                // This rejection is raised inside the transaction before its receipt commits.
                pending=null;external=false;publish("图片未保存：原件总量已达 32 MB 上限。原文件和已有内容均未更改；可另存较小副本后导入。")
            }catch(_:Exception){publish("对象保存尚未确认。请核对重试；如有版本冲突，可重新读取已保存对象。")}
        }
    }
    internal fun validateExternal(objects:List<PageObject>){check(authorAllowed());check(!state.value.loading&&!state.value.busy&&!state.value.pending);PageObjectCodec.encode(objects)}
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
        p.accepted?.invoke()
    }
    internal fun discardExternal(){external=false;state.value=state.value.copy(busy=false);reload()}
    internal fun historyIdentity(back:Boolean)=if(back)undo.lastOrNull()else redo.lastOrNull()
    internal fun historyReady(back:Boolean)=!state.value.loading&&!state.value.busy&&!state.value.pending&&if(back)undo.isNotEmpty()else redo.isNotEmpty()
    internal fun prepareHistoryGroup(back:Boolean)=prepareExternal(checkNotNull(historyIdentity(back)),if(back)-1 else 1)
    internal fun registerGroupHistory(back:Boolean,action:()->Unit){historyIdentity(back)?.let{(if(back)groupUndo else groupRedo)[it]=action}}
    fun undo(){if(!authorAllowed())return;if(undo.isNotEmpty()){groupUndo[undo.last()]?.let{it();return};change(undo.last(),-1)}}
    fun redo(){if(!authorAllowed())return;if(redo.isNotEmpty()){groupRedo[redo.last()]?.let{it();return};change(redo.last(),1)}}
    fun put(value:PageObject,originals:List<ImageSource> = emptyList(),accepted:(()->Unit)?=null){val old=snapshot.objects;change(if(old.any{it.id==value.id})old.map{if(it.id==value.id)value else it}else old+value,originals=originals,accepted=accepted)}
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
                    val cutObject=o.copy(glyphs=before,erasures=o.erasures+cut)
                    if(o.textRuns.isEmpty())cutObject else {
                        // Keep partially erased text searchable; remove a cluster only when no outline remains.
                        val outline=NaturalText.path(cutObject)
                        val visible=before.map{g->if(g.hidden)g else {
                            val part=android.graphics.Path(outline)
                            part.op(android.graphics.Path().apply{addRect(o.x+g.x,o.y+g.y,o.x+g.x+g.width,o.y+g.y+g.height,android.graphics.Path.Direction.CW)},android.graphics.Path.Op.INTERSECT)
                            if(part.isEmpty)g.copy(hidden=true)else g
                        }}
                        cutObject.copy(glyphs=visible,hidden=visible.all{it.hidden}&&o.sourceStrokeIds.isNotEmpty())
                    }
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
                check(authorAllowed())
                check(!state.value.busy&&!state.value.pending)
                state.value=state.value.copy(busy=true,error=null)
                val (original,bytes)=withContext(Dispatchers.IO){
                    val raw=CoverImages.readBytes(context.applicationContext,uri)
                    ImageSource(raw) to CoverImages.normalize(raw,PageObjectCodec.MAX_IMAGE)
                }
                val size=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,size)
                val width=minOf(500f,500f*size.outWidth/size.outHeight);val height=width*size.outHeight/size.outWidth
                val x=(viewport.centerX-width/2).toFloat();val y=(viewport.centerY-height/2).toFloat()
                val o=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,
                    if(world)x else x.coerceIn(0f,1000f-width),if(world)y else y.coerceIn(0f,1414f-height),width,height,
                    image=java.util.Base64.getEncoder().encodeToString(bytes),imageSource=original.sha256)
                publish();put(o,listOf(original),accepted={cleanup?.delete();onSelected(o.id)})
            }catch(c:CancellationException){throw c}catch(_:Exception){publish("图片未能加入。支持静态 JPG、PNG、WebP、HEIF，原图最多 20 MB；原件整库上限 32 MB，原件与预览会一同保存。")}
        }
    }
    class Factory(private val id:String,private val repo:PageObjectRepository):ViewModelProvider.Factory {
        override fun <T:ViewModel> create(modelClass:Class<T>):T {require(modelClass.isAssignableFrom(PageObjectViewModel::class.java));@Suppress("UNCHECKED_CAST")return PageObjectViewModel(id,repo) as T}
    }
}
