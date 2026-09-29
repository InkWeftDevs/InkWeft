// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.InkRepository
import java.util.UUID

data class InkUi(val strokes:List<InkStroke> = emptyList(),val loading:Boolean=true,val readFailed:Boolean=false,
    val blocked:InkCommitResult?=null,val queued:Int=0,val canStart:Boolean=false,val canUndo:Boolean=false,
    val canRedo:Boolean=false,val revision:Long=0,val processing:Boolean=false,val message:String?=null)
class InkViewModel(private val noteId:String,private val repository:InkRepository):ViewModel(){
    private val mutable=MutableStateFlow(InkUi());val ui=mutable.asStateFlow()
    private var session:InkSession?=null
    internal val history=EditorHistory()
    private var historyDirection=0
    private val groupUndo=java.util.IdentityHashMap<InkMutation,()->Unit>()
    private val groupRedo=java.util.IdentityHashMap<InkMutation,()->Unit>()
    internal fun historyIdentity(undo:Boolean)=if(undo)session?.undoIdentity else session?.redoIdentity
    internal fun registerGroupHistory(undo:Boolean,action:()->Unit){
        val key=historyIdentity(undo)?:return
        (if(undo)groupUndo else groupRedo)[key]=action
    }
    internal fun prepareHistoryGroup(undo:Boolean):CommitInk {
        val s=checkNotNull(session);check(!writing&&!erasing&&if(undo)s.canUndo else s.canRedo)
        historyDirection=if(undo)-1 else 1
        if(undo)s.requestUndo()else s.requestRedo()
        writing=true;val command=checkNotNull(s.nextCommand());publish();return command
    }
    var suppressedIds:Set<String> = emptySet()
    private var writing=false;private var reading=false;private var erasing=false
    init{load()}
    fun load(){if(session!=null||reading)return;reading=true;mutable.value=InkUi();viewModelScope.launch{try{val p=withContext(Dispatchers.IO){repository.read(noteId)};session=InkSession(p)
        val recovered=try{withContext(Dispatchers.IO){repository.recoverCheckpoints(noteId)}}catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=mutable.value.copy(message="长笔检查点待核对；已保存笔迹保留");emptyList()}
        recovered.forEach{session!!.enqueue(InkMutation.Add(it))};publish();if(recovered.isNotEmpty()){mutable.value=mutable.value.copy(message="已恢复长笔的已确认采样，可一次撤销整笔");pump()}
        }catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=InkUi(loading=false,readFailed=true)}finally{reading=false}}}
    fun discardRejectedDraft(){val captured=session?:return;if(reading||erasing||captured.blocked !in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))return;reading=true;viewModelScope.launch{try{val p=withContext(Dispatchers.IO){repository.read(noteId)};if(session===captured){session=InkSession(p);historyDirection=0;groupUndo.clear();groupRedo.clear()};publish()}catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=mutable.value.copy(readFailed=true)}finally{reading=false}}}
    private fun publish(){val s=session?:return;mutable.value=InkUi(s.visibleDraft(),false,false,s.blocked,s.queued,s.canStart&&!erasing,s.canUndo&&!erasing,s.canRedo&&!erasing,s.page.revision,erasing,mutable.value.message)}
    fun clearMessage(){mutable.value=mutable.value.copy(message=null)}
    internal fun validateGroup(strokes:List<InkStroke>){
        validateChange(InkMutation.Replace(emptyList(),strokes))
    }
    internal fun validateChange(change:InkMutation){
        val s=checkNotNull(session);check(!writing&&!erasing&&s.queued==0&&s.blocked==null)
        InkSession(s.page).enqueue(change)
    }
    internal fun prepareGroup(strokes:List<InkStroke>):CommitInk {
        return prepareChange(InkMutation.Replace(emptyList(),strokes))
    }
    internal fun prepareChange(change:InkMutation):CommitInk {
        val s=checkNotNull(session);check(!writing&&!erasing&&s.queued==0&&s.blocked==null)
        s.enqueue(change);val command=checkNotNull(s.nextCommand())
        writing=true;publish();return command
    }
    internal fun eraseChange(snapshot:List<InkStroke>,path:List<InkSample>,radius:Float,whole:Boolean,onlyHighlighter:Boolean):InkMutation?{
        val candidates=snapshot.filter{it.id !in suppressedIds&&(!onlyHighlighter||it.pen==InkPen.HIGHLIGHTER)}
        val ids=if(whole)candidates.filter{VisibleInkGeometry().hits(it,path,radius)}.map{it.id}else {
            val box=CanvasBounds(path.minOf{it.x}.toDouble(),path.minOf{it.y}.toDouble(),path.maxOf{it.x}.toDouble(),path.maxOf{it.y}.toDouble()).padded(radius.toDouble())
            candidates.filter{it.bounds().intersects(box)}.map{it.id}
        }
        if(ids.isEmpty())return null
        return if(whole)InkMutation.Visibility(ids,false)else InkMutation.Cut(EraseSelection(InkCut(UUID.randomUUID().toString(),radius,path.map{EraserPoint(it.x,it.y)}),ids))
    }
    internal fun completeGroup(command:CommitInk,result:InkCommitResult){
        checkNotNull(session).complete(command,result);if(result is InkCommitResult.Committed){history.committed(EditDomain.INK,historyDirection);historyDirection=0};writing=false;publish()
    }
    internal fun validateQueued(strokes:List<InkStroke>){
        val s=checkNotNull(session);check(s.blocked==null&&!erasing)
        val existing=(s.page.strokes.map{it.stroke}+ui.value.strokes).associateBy{it.id}
        require(existing.size+strokes.size<=InkLimits.MAX_STROKES)
        require(existing.values.sumOf{it.samples.size}+strokes.sumOf{it.samples.size}<=InkLimits.MAX_PAGE_POINTS)
        require(strokes.map{it.id}.distinct().size==strokes.size&&strokes.none{it.id in existing})
    }
    private var checkpointJob:Job?=null
    private var checkpointLatest:InkStroke?=null
    fun cancelCheckpoint(id:String){
        if(checkpointLatest?.id==id)checkpointLatest=null
        val previous=checkpointJob
        previous?.cancel()
        viewModelScope.launch{
            previous?.join()
            withContext(Dispatchers.IO){repository.discardCheckpoint(noteId,id)}
        }
    }
    fun checkpoint(stroke:InkStroke){
        checkpointLatest=stroke
        if(checkpointJob?.isActive==true)return
        checkpointJob=viewModelScope.launch{
            while(checkpointLatest!=null){val latest=checkpointLatest!!;checkpointLatest=null
                try{withContext(Dispatchers.IO){repository.checkpoint(noteId,latest)}}
                catch(c:CancellationException){throw c}
                catch(_:Exception){mutable.value=mutable.value.copy(message="长笔检查点未确认；请尽快抬笔保存")}
            }
        }
    }
    fun accept(stroke:InkStroke){val s=session?:return;s.enqueue(InkMutation.Add(stroke),finishInFlight=true);publish();pump()}
    fun erase(ids:List<String>){if(ids.isEmpty())return;val s=session?:return;s.enqueue(InkMutation.Visibility(ids,false),finishInFlight=true);publish();pump()}
    fun erasePath(path:List<InkSample>,radius:Float=12f,whole:Boolean=true,onlyHighlighter:Boolean=false){
        val s=session?:return;if(erasing||path.isEmpty()||s.blocked!=null)return
        val snapshot=s.visibleDraft().filter{it.id !in suppressedIds&&(!onlyHighlighter||it.pen==InkPen.HIGHLIGHTER)}
        if(!whole){
            // Only bounded AABB filtering is synchronous; the exact subtraction
            // is the immutable mask used by rendering and export, not a raster.
            val box=CanvasBounds(path.minOf{it.x}.toDouble(),path.minOf{it.y}.toDouble(),path.maxOf{it.x}.toDouble(),path.maxOf{it.y}.toDouble()).padded(radius.toDouble())
            val ids=snapshot.filter{it.bounds().intersects(box)}.map{it.id}
            if(ids.isEmpty())return
            val cut=InkCut(UUID.randomUUID().toString(),radius,path.map{EraserPoint(it.x,it.y)})
            s.enqueue(InkMutation.Cut(EraseSelection(cut,ids)),finishInFlight=true);publish();pump();return
        }
        erasing=true;publish()
        viewModelScope.launch{try{
            val ids=withContext(Dispatchers.Default){snapshot.filter{ensureActive();VisibleInkGeometry().hits(it,path,radius)}.map{it.id}}
            if(session===s&&ids.isNotEmpty())erase(ids)
        }catch(c:CancellationException){throw c}catch(_:Exception){mutable.value=mutable.value.copy(message="擦除未被接收，已保存笔迹不变；请检查容量。")}
        finally{erasing=false;publish()}}
    }
    /** All selected edits are a single undo unit at the revision actually shown. */
    fun selectedEdit(expectedRevision:Long,change:InkMutation):Boolean {
        val s=session?:return false
        if(reading||erasing||s.queued!=0||s.blocked!=null||s.page.revision!=expectedRevision){
            mutable.value=mutable.value.copy(message="页面已更新，请重新框选；未修改原笔迹。");return false
        }
        return try{s.enqueue(change);publish();pump();true}
        catch(_:IllegalArgumentException){mutable.value=mutable.value.copy(message="本次选择超出笔迹或空间预算，原件保留。可减少选区后重试。");false}
        catch(_:IllegalStateException){false}
    }
    fun undo(){val s=session?:return;if(!s.canUndo||erasing)return;groupUndo[s.undoIdentity]?.let{it();return};historyDirection=-1;s.requestUndo();publish();pump()}
    fun redo(){val s=session?:return;if(!s.canRedo||erasing)return;groupRedo[s.redoIdentity]?.let{it();return};historyDirection=1;s.requestRedo();publish();pump()}
    internal fun historyBlocked(){mutable.value=mutable.value.copy(message="请先撤销相邻页上较新的编辑，再撤销这笔跨页书写。")}
    fun retry(){session?.retry();publish();pump()}
    private fun pump(){if(writing)return;val s=session?:return;writing=true;viewModelScope.launch{try{while(true){val c=s.nextCommand()?:break;publish();val result=try{withContext(Dispatchers.IO){repository.save(c)}}catch(_:CancellationException){s.complete(c,InkCommitResult.Unknown);publish();return@launch}catch(_:Exception){InkCommitResult.Unknown};s.complete(c,result);if(result is InkCommitResult.Committed){history.committed(EditDomain.INK,historyDirection);historyDirection=0};publish();if(result !is InkCommitResult.Committed)break}}finally{writing=false;publish()}}}
    class Factory(private val id:String,private val repo:InkRepository):ViewModelProvider.Factory{override fun <T:ViewModel> create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(InkViewModel::class.java));@Suppress("UNCHECKED_CAST")return InkViewModel(id,repo) as T}}
}
