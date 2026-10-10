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
    private val undo=ArrayDeque<InkMutation>()
    private val redo=ArrayDeque<InkMutation>()
    private var historyMove:Boolean?=null
    private var visibleCache:List<InkStroke>?=null
    private var draftPoints=0
    private var storedById=initial.strokes.associateByTo(LinkedHashMap()){it.stroke.id}
    private var visibleCount=initial.strokes.count{it.visible}
    private var visiblePoints=initial.strokes.sumOf{if(it.visible)it.stroke.samples.size else 0}
    private var retainedPoints=initial.strokes.sumOf{it.stroke.samples.size}
    val queued get()=queue.size+if(pending==null)0 else 1
    private fun additions()=(listOfNotNull(pending?.mutation)+queue).flatMap{when(it){is InkMutation.Add->listOf(it.stroke);is InkMutation.Replace->it.added;else->emptyList()}}
    val canStart get():Boolean {
        if(blocked!=null||queued>=16)return false
        val added=additions();val points=added.sumOf{it.samples.size}
        return visibleCount+added.size<InkLimits.MAX_STROKES&&visiblePoints+points+InkLimits.MAX_POINTS<=InkLimits.MAX_PAGE_POINTS&&
            page.strokes.size+added.size<InkLimits.MAX_RETAINED_STROKES&&retainedPoints+points+InkLimits.MAX_POINTS<=InkLimits.MAX_RETAINED_POINTS
    }
    val canUndo get()=queued==0&&blocked==null&&undo.isNotEmpty()
    val canRedo get()=queued==0&&blocked==null&&redo.isNotEmpty()
    val undoIdentity:InkMutation? get()=undo.lastOrNull()
    val redoIdentity:InkMutation? get()=redo.lastOrNull()
    private fun apply(change:InkMutation,revision:Long,rows:LinkedHashMap<String,StoredInk>,cuts:LinkedHashMap<String,StoredCut>):InkMutation=when(change){
        is InkMutation.Replace->{
            require(change.hidden.all{rows[it]?.visible==true});require(change.added.none{rows.containsKey(it.id)})
            change.hidden.forEach{rows[it]=checkNotNull(rows[it]).copy(visible=false)}
            change.added.forEach{rows[it.id]=StoredInk(it,true,revision)}
            InkMutation.Swap(change.added.map{it.id},change.hidden)
        }
        is InkMutation.Swap->{
            require(change.hide.all{rows[it]?.visible==true}&&change.show.all{rows[it]?.visible==false})
            change.hide.forEach{rows[it]=checkNotNull(rows[it]).copy(visible=false)}
            change.show.forEach{rows[it]=checkNotNull(rows[it]).copy(visible=true)}
            InkMutation.Swap(change.show,change.hide)
        }
        is InkMutation.Add->{rows[change.stroke.id]=StoredInk(change.stroke,true,revision);InkMutation.Visibility(listOf(change.stroke.id),false)}
        is InkMutation.Visibility->{require(change.ids.all{rows[it]?.visible==!change.visible});change.ids.forEach{rows[it]=checkNotNull(rows[it]).copy(visible=change.visible)};InkMutation.Visibility(change.ids,!change.visible)}
        is InkMutation.Cut->{require(change.selection.strokeIds.all{rows[it]?.visible==true});cuts[change.selection.cut.id]=StoredCut(change.selection,true,revision);InkMutation.CutVisibility(change.selection.cut.id,false)}
        is InkMutation.CutVisibility->{val old=checkNotNull(cuts[change.cutId]);require(old.visible!=change.visible);cuts[change.cutId]=old.copy(visible=change.visible);InkMutation.CutVisibility(change.cutId,!change.visible)}
    }
    fun visibleDraft():List<InkStroke> {
        visibleCache?.let{return it}
        val rows=LinkedHashMap(storedById)
        val cuts=page.cuts.associateByTo(LinkedHashMap()){it.selection.cut.id}
        (listOfNotNull(pending?.mutation)+queue).forEach{apply(it,page.revision+1,rows,cuts)}
        val byStroke=mutableMapOf<String,MutableList<InkCut>>()
        cuts.values.filter{it.visible}.forEach{c->c.selection.strokeIds.forEach{id->byStroke.getOrPut(id){mutableListOf()}.add(c.selection.cut)}}
        return java.util.Collections.unmodifiableList(rows.values.filter{it.visible}.map{it.stroke.withCuts(byStroke[it.stroke.id].orEmpty())}).also{
            draftPoints=it.sumOf{stroke->stroke.samples.size};visibleCache=it
        }
    }
    fun enqueue(change:InkMutation,finishInFlight:Boolean=false,layerScope:LayerWriteScope?=null){
        check(canStart||(finishInFlight&&queued<17&&blocked==null)||(change !is InkMutation.Add&&queued==0&&blocked==null)){"Resolve pending storage before more input"}
        when(change){
            is InkMutation.Replace->{
                require(queued==0)
                require(change.hidden.all{storedById[it]?.visible==true})
                require(change.added.none{it.id in storedById})
                val hiddenPoints=change.hidden.sumOf{checkNotNull(storedById[it]).stroke.samples.size}
                val addedPoints=change.added.sumOf{it.samples.size}
                require(visibleCount-change.hidden.size+change.added.size<=InkLimits.MAX_STROKES)
                require(visiblePoints-hiddenPoints+addedPoints<=InkLimits.MAX_PAGE_POINTS)
                require(page.strokes.size+change.added.size<=InkLimits.MAX_RETAINED_STROKES)
                require(retainedPoints+addedPoints<=InkLimits.MAX_RETAINED_POINTS)
            }
            is InkMutation.Add->{val current=visibleDraft();val added=additions()
                require(current.size<InkLimits.MAX_STROKES);require(draftPoints+change.stroke.samples.size<=InkLimits.MAX_PAGE_POINTS)
                require(page.strokes.size+added.size<InkLimits.MAX_RETAINED_STROKES)
                require(retainedPoints+added.sumOf{it.samples.size}+change.stroke.samples.size<=InkLimits.MAX_RETAINED_POINTS)
                require(change.stroke.id !in storedById&&added.none{it.id==change.stroke.id})}
            is InkMutation.Cut->{val current=visibleDraft().associateBy{it.id};require(page.cuts.size+(listOfNotNull(pending?.mutation)+queue).count{it is InkMutation.Cut}<InkLimits.MAX_RETAINED_CUTS);change.selection.strokeIds.forEach{id->val s=checkNotNull(current[id]);require(s.cuts.size<InkLimits.MAX_CUTS&&s.cuts.sumOf{it.points.size}+change.selection.cut.points.size<=InkLimits.MAX_CUT_POINTS)}}
            else->Unit
        };queue.add(change);scopes[change]=layerScope;visibleCache=null
    }
    fun nextCommand(id:()->String={UUID.randomUUID().toString()}):CommitInk? {if(blocked!=null)return null;if(pending==null&&queue.isNotEmpty())pending=queue.removeFirst().let{CommitInk(id(),page.noteId,page.revision,it,scopes.remove(it))};return pending}
    fun retry():CommitInk?{if(blocked!=InkCommitResult.Unknown)return null;blocked=null;return pending}
    fun complete(command:CommitInk,result:InkCommitResult){
        check(command===pending);if(result !is InkCommitResult.Committed){blocked=result;return};require(result.revision==command.expectedRevision+1)
        val rows=LinkedHashMap(storedById);val cuts=page.cuts.associateByTo(LinkedHashMap()){it.selection.cut.id}
        val inverse=apply(command.mutation,result.revision,rows,cuts)
        page=InkPage(page.noteId,result.revision,rows.values.toList(),cuts.values.toList())
        storedById=rows
        visibleCount=page.strokes.count{it.visible};visiblePoints=page.strokes.sumOf{if(it.visible)it.stroke.samples.size else 0};retainedPoints=page.strokes.sumOf{it.stroke.samples.size}
        when(historyMove){true->{undo.removeLast();redo.add(inverse)};false->{redo.removeLast();undo.add(inverse)};null->{undo.add(inverse);redo.clear()}}
        while(undo.size>50)undo.removeFirst();historyMove=null;pending=null;blocked=null
    }
    fun requestUndo(layerScope:LayerWriteScope?=null){check(canUndo);historyMove=true;queue.add(undo.last());scopes[undo.last()]=layerScope;visibleCache=null}
    /** Reconstruct only the just-recovered logical stroke, not an invented full history. */
    fun rememberRecoveredAddition(ids:List<String>){
        check(queued==0&&blocked==null);require(ids.isNotEmpty()&&ids.distinct()==ids&&ids.all{id->page.strokes.any{it.stroke.id==id&&it.visible}})
        undo.add(InkMutation.Visibility(ids,false));redo.clear()
    }
    fun requestRedo(layerScope:LayerWriteScope?=null){check(canRedo);historyMove=false;queue.add(redo.last());scopes[redo.last()]=layerScope;visibleCache=null}
}
