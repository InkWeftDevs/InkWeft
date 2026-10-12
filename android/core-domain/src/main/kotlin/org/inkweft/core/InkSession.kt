// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core
import java.util.UUID

/** One page actor. Masks are committed once and shared by affected strokes. */
class InkSession(initial:InkPage) {
    var page=initial;private set
    var blocked:InkCommitResult?=null;private set
    var pending:CommitInk?=null;private set
    private val queue=ArrayDeque<InkMutation>()
    private val scopes=java.util.IdentityHashMap<InkMutation,LayerWriteScope?>()
    private val resolved=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InkMutation,Boolean>())
    private val undo=ArrayDeque<InkMutation>()
    private val redo=ArrayDeque<InkMutation>()
    private var historyMove:Boolean?=null
    private var visibleCache:List<InkStroke>?=null
    private var storedById=initial.strokes.associateByTo(LinkedHashMap()){it.stroke.id}
    val queued get()=queue.size+if(pending==null)0 else 1
    private fun additions()=(listOfNotNull(pending?.mutation)+queue).flatMap{when(it){is InkMutation.Add->listOf(it.stroke);is InkMutation.Replace->it.added;else->emptyList()}}
    val canStart get()=blocked==null&&queued<16
    val canUndo get()=queued==0&&blocked==null&&undo.isNotEmpty()
    val canRedo get()=queued==0&&blocked==null&&redo.isNotEmpty()
    val undoIdentity:InkMutation? get()=undo.lastOrNull()
    val redoIdentity:InkMutation? get()=redo.lastOrNull()
    fun hasHistoryIdentity(value:InkMutation)=undo.any{it===value}||redo.any{it===value}
    class History internal constructor(val noteId:String,val revision:Long,internal val undo:List<InkMutation>,internal val redo:List<InkMutation>) {
        val strokeIds:Set<String> by lazy { java.util.Collections.unmodifiableSet((undo+redo).flatMap{change->when(change){
            is InkMutation.Visibility->change.ids
            is InkMutation.Swap->change.hide+change.show
            else->emptyList()
        }}.toSet()) }
        val cutIds:Set<String> by lazy { java.util.Collections.unmodifiableSet((undo+redo).mapNotNull{(it as? InkMutation.CutVisibility)?.cutId}.toSet()) }
    }
    /** Committed inverses hold identities, never stroke geometry or point arrays. */
    fun suspendHistory():History {
        check(queued==0&&blocked==null&&historyMove==null)
        val entries=undo+redo
        check(entries.none{it is InkMutation.Add||it is InkMutation.Replace||it is InkMutation.Cut})
        return History(page.noteId,page.revision,undo.toList(),redo.toList())
    }
    fun restoreHistory(value:History) {
        require(value.noteId==page.noteId&&value.revision==page.revision){"INK_HISTORY_REVISION_CHANGED"}
        check(queued==0&&blocked==null&&undo.isEmpty()&&redo.isEmpty())
        undo.addAll(value.undo);redo.addAll(value.redo)
    }
    /** Change the loaded window at the same saved revision without changing undo identities. */
    fun refreshWindow(value:InkPage) {
        check(queued==0&&blocked==null&&historyMove==null)
        require(value.noteId==page.noteId&&value.revision==page.revision)
        page=value;storedById=value.strokes.associateByTo(LinkedHashMap()){it.stroke.id};visibleCache=null
    }
    // History commands are validated against retained rows by the repository. Applying their
    // local preview changes only loaded geometry; a committed history move reloads the window.
    private fun historyChange(change:InkMutation)=historyMove!=null&&(change===undo.lastOrNull()||change===redo.lastOrNull())
    private fun unloadedAllowed(change:InkMutation)=historyChange(change)||change in resolved
    private fun apply(change:InkMutation,revision:Long,rows:LinkedHashMap<String,StoredInk>,cuts:LinkedHashMap<String,StoredCut>):InkMutation=when(change){
        is InkMutation.Replace->{
            require(change.hidden.all{rows[it]?.visible==true||(rows[it]==null&&unloadedAllowed(change))});require(change.added.none{rows.containsKey(it.id)})
            change.hidden.forEach{id->rows[id]?.let{rows[id]=it.copy(visible=false)}}
            change.added.forEach{rows[it.id]=StoredInk(it,true,revision)}
            InkMutation.Swap(change.added.map{it.id},change.hidden)
        }
        is InkMutation.Swap->{
            require(change.hide.all{rows[it]?.visible==true||(rows[it]==null&&historyChange(change))}&&change.show.all{rows[it]?.visible==false||(rows[it]==null&&historyChange(change))})
            change.hide.forEach{id->rows[id]?.let{rows[id]=it.copy(visible=false)}}
            change.show.forEach{id->rows[id]?.let{rows[id]=it.copy(visible=true)}}
            InkMutation.Swap(change.show,change.hide)
        }
        is InkMutation.Add->{rows[change.stroke.id]=StoredInk(change.stroke,true,revision);InkMutation.Visibility(listOf(change.stroke.id),false)}
        is InkMutation.Visibility->{require(change.ids.all{rows[it]?.visible==!change.visible||(rows[it]==null&&unloadedAllowed(change))});change.ids.forEach{id->rows[id]?.let{rows[id]=it.copy(visible=change.visible)}};InkMutation.Visibility(change.ids,!change.visible)}
        is InkMutation.Cut->{require(change.selection.strokeIds.all{rows[it]?.visible==true||(rows[it]==null&&unloadedAllowed(change))});cuts[change.selection.cut.id]=StoredCut(change.selection,true,revision);InkMutation.CutVisibility(change.selection.cut.id,false)}
        is InkMutation.CutVisibility->{val old=cuts[change.cutId];require(old?.visible==!change.visible||(old==null&&historyChange(change)));if(old!=null)cuts[change.cutId]=old.copy(visible=change.visible);InkMutation.CutVisibility(change.cutId,!change.visible)}
    }
    fun visibleDraft():List<InkStroke> {
        visibleCache?.let{return it}
        val rows=LinkedHashMap(storedById)
        val cuts=page.cuts.associateByTo(LinkedHashMap()){it.selection.cut.id}
        (listOfNotNull(pending?.mutation)+queue).forEach{apply(it,page.revision+1,rows,cuts)}
        val byStroke=mutableMapOf<String,MutableList<InkCut>>()
        cuts.values.filter{it.visible}.forEach{c->c.selection.strokeIds.forEach{id->if(rows[id]?.visible==true)byStroke.getOrPut(id){mutableListOf()}.add(c.selection.cut)}}
        return java.util.Collections.unmodifiableList(rows.values.filter{it.visible}.map{it.stroke.withCuts(byStroke[it.stroke.id].orEmpty())}).also{
            visibleCache=it
        }
    }
    fun enqueue(change:InkMutation,finishInFlight:Boolean=false,layerScope:LayerWriteScope?=null){
        check(canStart||(finishInFlight&&queued<17&&blocked==null)||(change !is InkMutation.Add&&queued==0&&blocked==null)){"Resolve pending storage before more input"}
        when(change){
            is InkMutation.Replace->{
                require(queued==0)
                require(change.hidden.all{storedById[it]?.visible==true||(storedById[it]==null&&change in resolved)})
                require(change.added.none{it.id in storedById||it.id in page.unloaded.strokePoints})
            }
            is InkMutation.Add->{val added=additions()
                require(change.stroke.id !in storedById&&change.stroke.id !in page.unloaded.strokePoints&&added.none{it.id==change.stroke.id})}
            is InkMutation.Cut->{val current=visibleDraft().associateBy{it.id};change.selection.strokeIds.forEach{id->val s=current[id];if(s==null)check(change in resolved)else require(s.cuts.size<InkLimits.MAX_CUTS&&s.cuts.sumOf{it.points.size}+change.selection.cut.points.size<=InkLimits.MAX_CUT_POINTS)}}
            else->Unit
        };queue.add(change);scopes[change]=layerScope;visibleCache=null
    }
    /** The caller resolved these targets from storage at this exact revision. The repository
     * revalidates every identity, mask budget and layer before any write is committed. */
    fun enqueueResolved(change:InkMutation,revision:Long,layerScope:LayerWriteScope?=null){
        check(queued==0&&blocked==null&&revision==page.revision)
        require(change is InkMutation.Replace||change is InkMutation.Cut||change is InkMutation.Visibility&&!change.visible)
        resolved.add(change)
        try{enqueue(change,layerScope=layerScope)}catch(t:Throwable){resolved.remove(change);throw t}
    }
    fun nextCommand(id:()->String={UUID.randomUUID().toString()}):CommitInk? {if(blocked!=null)return null;if(pending==null&&queue.isNotEmpty())pending=queue.removeFirst().let{CommitInk(id(),page.noteId,page.revision,it,scopes.remove(it))};return pending}
    fun retry():CommitInk?{if(blocked!=InkCommitResult.Unknown)return null;blocked=null;return pending}
    fun complete(command:CommitInk,result:InkCommitResult){
        check(command===pending);if(result !is InkCommitResult.Committed){blocked=result;return};require(result.revision==command.expectedRevision+1)
        val rows=LinkedHashMap(storedById);val cuts=page.cuts.associateByTo(LinkedHashMap()){it.selection.cut.id}
        val inverse=apply(command.mutation,result.revision,rows,cuts)
        page=InkPage(page.noteId,result.revision,rows.values.toList(),cuts.values.toList(),page.unloaded,page.windowComplete)
        storedById=rows
        when(historyMove){true->{undo.removeLast();redo.add(inverse)};false->{redo.removeLast();undo.add(inverse)};null->{undo.add(inverse);redo.clear()}}
        while(undo.size>50)undo.removeFirst();resolved.remove(command.mutation);historyMove=null;pending=null;blocked=null
    }
    fun requestUndo(layerScope:LayerWriteScope?=null){check(canUndo);historyMove=true;queue.add(undo.last());scopes[undo.last()]=layerScope;visibleCache=null}
    /** Reconstruct only the just-recovered logical stroke, not an invented full history. */
    fun rememberRecoveredAddition(ids:List<String>){
        check(queued==0&&blocked==null);require(ids.isNotEmpty()&&ids.distinct()==ids&&ids.all{id->page.strokes.any{it.stroke.id==id&&it.visible}})
        undo.add(InkMutation.Visibility(ids,false));redo.clear()
    }
    fun requestRedo(layerScope:LayerWriteScope?=null){check(canRedo);historyMove=false;queue.add(redo.last());scopes[redo.last()]=layerScope;visibleCache=null}
}
