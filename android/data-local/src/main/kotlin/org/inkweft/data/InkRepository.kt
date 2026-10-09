// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*
import java.util.UUID
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Entity(tableName="ink_pages",foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkPageRow(@PrimaryKey val noteId:String,val revision:Long)
@Entity(tableName="ink_strokes",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkStrokeRow(@PrimaryKey val id:String,val noteId:String,val payload:ByteArray,val pointCount:Int,val visible:Boolean,val createdRevision:Long)
@Entity(tableName="ink_receipts",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkReceiptRow(@PrimaryKey val commandId:String,val noteId:String,val digest:String,val committedRevision:Long,val strokeIds:String,val visible:Boolean)
@Entity(tableName="ink_cuts",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkCutRow(@PrimaryKey val id:String,val noteId:String,val payload:ByteArray,val strokeIds:String,val visible:Boolean,val createdRevision:Long)
@Dao
interface InkDao {
    @Query("SELECT * FROM ink_pages WHERE noteId=:id") suspend fun page(id:String):InkPageRow?
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id ORDER BY createdRevision,id") suspend fun strokes(id:String):List<InkStrokeRow>
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id AND visible=1 ORDER BY createdRevision,id") suspend fun visibleStrokes(id:String):List<InkStrokeRow>
    @Query("SELECT revision FROM ink_pages WHERE noteId=:id") fun observeRevision(id:String):kotlinx.coroutines.flow.Flow<Long?>
    @Query("SELECT * FROM ink_strokes WHERE id=:id") suspend fun stroke(id:String):InkStrokeRow?
    @Query("SELECT * FROM ink_receipts WHERE commandId=:id") suspend fun receipt(id:String):InkReceiptRow?
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id ORDER BY createdRevision,id") suspend fun cuts(id:String):List<InkCutRow>
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id AND visible=1 ORDER BY createdRevision,id") suspend fun visibleCuts(id:String):List<InkCutRow>
    @Query("SELECT * FROM ink_cuts WHERE id=:id") suspend fun cut(id:String):InkCutRow?
    @Query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=:id") suspend fun count(id:String):Int
    @Query("SELECT COALESCE(SUM(pointCount),0) FROM ink_strokes WHERE noteId=:id") suspend fun pointCount(id:String):Int
    @Query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=:id AND visible=1") suspend fun visibleCount(id:String):Int
    @Query("SELECT COALESCE(SUM(pointCount),0) FROM ink_strokes WHERE noteId=:id AND visible=1") suspend fun visiblePointCount(id:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertPage(row:InkPageRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertStroke(row:InkStrokeRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertReceipt(row:InkReceiptRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertCut(row:InkCutRow)
    @Query("UPDATE ink_cuts SET visible=:visible WHERE id=:id AND noteId=:noteId AND visible!=:visible") suspend fun cutVisibility(id:String,noteId:String,visible:Boolean):Int
    @Query("UPDATE ink_pages SET revision=:next WHERE noteId=:id AND revision=:expected") suspend fun compareAndSet(id:String,expected:Long,next:Long):Int
    @Query("UPDATE ink_strokes SET visible=:visible WHERE noteId=:noteId AND id IN (:ids)") suspend fun setVisibility(noteId:String,ids:List<String>,visible:Boolean):Int
}
enum class InkFaultPoint { BEFORE_RECEIPT,AFTER_TRANSACTION }
data class ObjectWrite(val pageId:String,val expected:Long,val commandId:String,val objects:List<PageObject>,val layerScope:LayerWriteScope?=null)
data class CanvasBatchResult(val ink:List<InkCommitResult> = emptyList(),val objects:List<Long> = emptyList(),val failure:InkCommitResult?=null)
data class InkGroupCommit(val group:InkGroupPrefix,val results:List<InkCommitResult.Committed>,val replayed:Boolean)
class InkRepository(private val db:NoteDatabase,private val fault:(InkFaultPoint)->Unit={}) {
    private class GroupAbort(val result:InkCommitResult):RuntimeException()
    /** A seam gesture either commits on every sheet or on none; receipts make a retry idempotent. */
    suspend fun saveGroup(commands:List<CommitInk>):List<InkCommitResult> {
        require(commands.isNotEmpty()&&commands.map{it.noteId}.distinct().size==commands.size)
        return try {db.withTransaction {commands.map { command ->
            val result=save(command)
            if(result !is InkCommitResult.Committed)throw GroupAbort(result)
            result
        }}}catch(e:GroupAbort){commands.map{e.result}}
    }
    suspend fun saveCanvasBatch(commands:List<CommitInk>,objects:List<ObjectWrite>):CanvasBatchResult {
        require(commands.isNotEmpty()||objects.isNotEmpty())
        require(objects.map{it.pageId}.distinct().size==objects.size)
        return try{db.withTransaction {
            val ink=if(commands.isEmpty())emptyList()else saveGroup(commands)
            ink.firstOrNull{it !is InkCommitResult.Committed}?.let{throw GroupAbort(it)}
            val objectRepo=PageObjectRepository(db)
            val revisions=objects.map{objectRepo.save(it.pageId,it.expected,it.commandId,it.objects,layerScope=it.layerScope)}
            CanvasBatchResult(ink,revisions)
        }}catch(e:GroupAbort){CanvasBatchResult(failure=e.result)}
        catch(_:IllegalArgumentException){CanvasBatchResult(failure=InkCommitResult.Conflict)}
    }
    private val dao=db.ink()
    private val checkpoints by lazy{InkCheckpoints(checkNotNull(db.checkpointRoot))}
    var groupFaultForTest:(String)->Unit={}
    private val groups by lazy{InkGroupCheckpoints(java.io.File(checkNotNull(db.checkpointRoot),"groups")){groupFaultForTest("journal-$it")}}
    private fun groupOperation(id:String)=UUID.nameUUIDFromBytes("ink-group:$id".toByteArray()).toString()
    fun hasUnsealedInput():Boolean=db.checkpointRoot?.let{root->root.walkTopDown().any{it.isFile&&(it.name.endsWith(".inkpart")||it.name.endsWith(".inkpart.bak"))}||groups.hasUnsealed()}?:false
    suspend fun captureGroup(book:String,pages:List<String>,origin:Int,stroke:InkStroke,layerScopes:List<LayerWriteScope?> = emptyList()):InkGroupPrefix=db.withTransaction{
        require(db.workspace().get(book)?.trashedAt==null&&db.notes().note(book)!=null){"GROUP_BOOK_UNAVAILABLE"}
        pages.forEach{id->val p=checkNotNull(db.pages().get(id));require(p.notebookId==book&&!p.world&&p.trashedAt==null){"GROUP_PAGE_UNAVAILABLE"}}
        InkGroupPrefix(book,pages,pages.map{dao.page(it)?.revision?:0L},origin,stroke,layerScopes=layerScopes)
    }
    suspend fun checkpointGroup(group:InkGroupPrefix):Boolean=db.inkGroupMutex.withLock{
        if(db.libraryContent().receipt(groupOperation(group.stroke.id))!=null)return@withLock false
        groupFaultForTest("before-prefix");val saved=groups.save(group);groupFaultForTest("after-prefix");saved
    }
    suspend fun pendingGroups(book:String):List<InkGroupPrefix> = db.inkGroupMutex.withLock{groups.pending(book)}
    suspend fun cancelGroup(id:String):Boolean=db.inkGroupMutex.withLock{
        if(db.libraryContent().receipt(groupOperation(id))!=null)return@withLock false
        groups.cancel(id);groupFaultForTest("after-cancel");groups.discard(id);true
    }
    suspend fun acknowledgeGroup(id:String)=db.inkGroupMutex.withLock{
        check(db.libraryContent().receipt(groupOperation(id))?.kind=="INK_GROUP")
        groups.discard(id)
    }
    /** Journal intent first; every fragment and the logical receipt share one author transaction. */
    suspend fun sealGroup(value:InkGroupPrefix,finished:InkStroke?=null):InkGroupCommit=db.inkGroupMutex.withLock{
        val stored=groups.read(value.stroke.id)
        val group=if(stored?.state in listOf("SEALING","SEALED"))stored!! else value.copy(state="SEALING",finished=finished?:value.finished)
        require(stored?.state!="CANCELLED"){"GROUP_CANCELLED"}
        val operation=groupOperation(group.stroke.id);val digest=group.digest();val commands=group.commands()
        val replay=db.libraryContent().receipt(operation)
        if(replay==null&&stored?.state!="SEALING")require(groups.save(group)){"GROUP_CANCELLED"}
        groupFaultForTest("before-group-transaction")
        val results=db.withTransaction{
            val receipt=db.libraryContent().receipt(operation)
            if(receipt!=null){
                require(receipt.kind=="INK_GROUP"&&receipt.digest==digest&&receipt.noteId==group.book){"GROUP_RECEIPT_CHANGED"}
                commands.map{c->val r=checkNotNull(dao.receipt(c.commandId));require(r.digest==c.digest()&&r.noteId==c.noteId);InkCommitResult.Committed(r.committedRevision)}
            }else{
                require(db.workspace().get(group.book)?.trashedAt==null){"GROUP_BOOK_UNAVAILABLE"}
                commands.forEach{c->val page=db.pages().get(c.noteId);require(page!=null&&page.notebookId==group.book&&page.trashedAt==null&&!page.world){"GROUP_PAGE_UNAVAILABLE"}}
                groupFaultForTest("before-first-fragment")
                val saved=saveGroup(commands);require(saved.all{it is InkCommitResult.Committed}){"GROUP_REVISION_CONFLICT"}
                groupFaultForTest("after-fragments")
                db.libraryContent().insert(LibraryContentReceipt(operation,"INK_GROUP",digest,group.book))
                groupFaultForTest("before-group-commit")
                saved.map{it as InkCommitResult.Committed}
            }
        }
        groupFaultForTest("after-group-commit")
        if(group.state!="SEALED")groups.save(group.copy(state="SEALED"))
        groupFaultForTest("after-group-marker")
        InkGroupCommit(group.copy(state="SEALED"),results,replay!=null)
    }
    suspend fun checkpoint(page:String,stroke:InkStroke,layerScope:LayerWriteScope?=null){
        require(stroke.world==owner(page).world)
        if(dao.stroke(stroke.id)==null)checkpoints.save(page,stroke.samples.size,stroke,layerScope)
    }
    fun discardCheckpoint(page:String,strokeId:String){if(db.checkpointRoot!=null)checkpoints.remove(page,strokeId)}
    suspend fun recoverCheckpoints(page:String):List<InkStroke> = recoverScopedCheckpoints(page).map{it.stroke}
    suspend fun recoverScopedCheckpoints(page:String):List<InkCheckpoint> {
        if(db.checkpointRoot==null)return emptyList()
        val recovered=mutableListOf<InkCheckpoint>()
        for(checkpoint in checkpoints.readScoped(page)){
            if(dao.stroke(checkpoint.stroke.id)!=null)checkpoints.remove(page,checkpoint.stroke.id) else recovered+=checkpoint
        };return recovered
    }

    private suspend fun owner(id:String)=db.pages().get(id)?:NotebookPages(db).ensureFirst(id)
    private data class ReadRows(val owner:NotebookPageRow,val page:InkPageRow?,val strokes:List<InkStrokeRow>,val cuts:List<InkCutRow>)
    suspend fun read(noteId:String):InkPage {
        // Freeze rows atomically; decoding immutable blobs must not hold the Room
        // transaction executor while another page is trying to save handwriting.
        val frozen=db.withTransaction{ReadRows(owner(noteId),dao.page(noteId),dao.strokes(noteId),dao.cuts(noteId))}
        val owner=frozen.owner;val page=frozen.page;val rows=frozen.strokes
        check(rows.size<=InkLimits.MAX_RETAINED_STROKES&&rows.sumOf{it.pointCount}<=InkLimits.MAX_RETAINED_POINTS)
        check(rows.count{it.visible}<=InkLimits.MAX_STROKES&&rows.sumOf{if(it.visible)it.pointCount else 0}<=InkLimits.MAX_PAGE_POINTS)
        val context=currentCoroutineContext()
        val decoded=rows.map{context.ensureActive();val s=InkStrokeCodec.decode(it.payload);check(s.id==it.id&&s.samples.size==it.pointCount&&s.world==owner.world);StoredInk(s,it.visible,it.createdRevision)}
        val ids=decoded.map{it.stroke.id}.toSet()
        val cuts=frozen.cuts;check(cuts.size<=InkLimits.MAX_RETAINED_CUTS)
        val masks=cuts.map{r->val cut=InkCutCodec.decode(r.payload);check(cut.id==r.id);val target=r.strokeIds.split(',');check(target.all{it in ids});StoredCut(EraseSelection(cut,target),r.visible,r.createdRevision)}
        check(page!=null||(rows.isEmpty()&&masks.isEmpty()))
        return InkPage(noteId,page?.revision?:0,decoded,masks).also{InkSession(it).visibleDraft()}
    }
    /** Preview reads never deserialize hidden move/erase history. Masks remain exact. */
    suspend fun readVisible(noteId:String):List<InkStroke> {
        val frozen=db.withTransaction{ReadRows(owner(noteId),dao.page(noteId),dao.visibleStrokes(noteId),dao.visibleCuts(noteId))}
        val rows=frozen.strokes
        check(rows.size<=InkLimits.MAX_STROKES&&rows.sumOf{it.pointCount}<=InkLimits.MAX_PAGE_POINTS)
        val context=currentCoroutineContext()
        val strokes=rows.map{row->context.ensureActive();InkStrokeCodec.decode(row.payload).also{check(it.id==row.id&&it.samples.size==row.pointCount&&it.world==frozen.owner.world)}}
        val visible=strokes.map{it.id}.toSet();val byStroke=mutableMapOf<String,MutableList<InkCut>>()
        check(frozen.cuts.size<=InkLimits.MAX_RETAINED_CUTS)
        frozen.cuts.forEach{row->val cut=InkCutCodec.decode(row.payload);check(cut.id==row.id)
            val targets=row.strokeIds.split(',');check(targets.distinct().size==targets.size)
            targets.filter{it in visible}.forEach{byStroke.getOrPut(it){mutableListOf()}.add(cut)}
        }
        check(frozen.page!=null||(rows.isEmpty()&&frozen.cuts.isEmpty()))
        return strokes.map{it.withCuts(byStroke[it.id].orEmpty())}
    }
    fun observeVisible(noteId:String)=dao.observeRevision(noteId).distinctUntilChanged().map{readVisible(noteId)}
    suspend fun save(command:CommitInk):InkCommitResult {
        val result=db.withTransaction {
            val digest=command.digest();val old=dao.receipt(command.commandId)
            if(old!=null)return@withTransaction if(old.noteId==command.noteId&&old.digest==digest)InkCommitResult.Committed(old.committedRevision)else InkCommitResult.Rejected
            if(db.pages().get(command.noteId)==null&&db.notes().note(command.noteId)==null)return@withTransaction InkCommitResult.Conflict
            val owner=owner(command.noteId);val current=dao.page(command.noteId)
            if(owner.trashedAt!=null || db.workspace().get(owner.notebookId)?.trashedAt!=null)return@withTransaction InkCommitResult.Conflict
            if((current?.revision?:0)!=command.expectedRevision)return@withTransaction InkCommitResult.Conflict
            val next=command.expectedRevision+1
            val visible=when(val change=command.mutation){
                is InkMutation.Replace->{
                    if(dao.count(command.noteId)+change.added.size>InkLimits.MAX_RETAINED_STROKES ||
                        dao.pointCount(command.noteId)+change.added.sumOf{it.samples.size}>InkLimits.MAX_RETAINED_POINTS)
                        return@withTransaction InkCommitResult.Rejected
                    var replacedPoints=0
                    for(id in change.hidden){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict;replacedPoints+=row.pointCount}
                    if(dao.visibleCount(command.noteId)-change.hidden.size+change.added.size>InkLimits.MAX_STROKES||
                        dao.visiblePointCount(command.noteId)-replacedPoints+change.added.sumOf{it.samples.size}>InkLimits.MAX_PAGE_POINTS)return@withTransaction InkCommitResult.Rejected
                    if(change.added.any{it.world!=owner.world||dao.stroke(it.id)!=null})return@withTransaction InkCommitResult.Rejected
                    true
                }
                is InkMutation.Swap->{
                    var hiddenPoints=0;var shownPoints=0
                    for(id in change.hide){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict;hiddenPoints+=row.pointCount}
                    for(id in change.show){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||row.visible)return@withTransaction InkCommitResult.Conflict;shownPoints+=row.pointCount}
                    if(dao.visibleCount(command.noteId)-change.hide.size+change.show.size>InkLimits.MAX_STROKES||dao.visiblePointCount(command.noteId)-hiddenPoints+shownPoints>InkLimits.MAX_PAGE_POINTS)return@withTransaction InkCommitResult.Rejected
                    true
                }
                is InkMutation.Add->{if(change.stroke.world!=owner.world||dao.stroke(change.stroke.id)!=null||dao.visibleCount(command.noteId)>=InkLimits.MAX_STROKES||dao.visiblePointCount(command.noteId)+change.stroke.samples.size>InkLimits.MAX_PAGE_POINTS||
                    dao.count(command.noteId)>=InkLimits.MAX_RETAINED_STROKES||dao.pointCount(command.noteId)+change.stroke.samples.size>InkLimits.MAX_RETAINED_POINTS)return@withTransaction InkCommitResult.Rejected;true}
                is InkMutation.Visibility->{var points=0;for(id in change.ids){val r=dao.stroke(id);if(r==null||r.noteId!=command.noteId||r.visible==change.visible)return@withTransaction InkCommitResult.Conflict;points+=r.pointCount}
                    if(change.visible&&(dao.visibleCount(command.noteId)+change.ids.size>InkLimits.MAX_STROKES||dao.visiblePointCount(command.noteId)+points>InkLimits.MAX_PAGE_POINTS))return@withTransaction InkCommitResult.Rejected
                    change.visible}
                is InkMutation.Cut->{
                    val c=change.selection;val oldCuts=dao.cuts(command.noteId)
                    if(dao.cut(c.cut.id)!=null||oldCuts.size>=InkLimits.MAX_RETAINED_CUTS)return@withTransaction InkCommitResult.Rejected
                    val effective=InkSession(read(command.noteId)).visibleDraft().associateBy{it.id}
                    for(id in c.strokeIds){val s=effective[id]?:return@withTransaction InkCommitResult.Conflict;if(s.cuts.size>=InkLimits.MAX_CUTS||s.cuts.sumOf{it.points.size}+c.cut.points.size>InkLimits.MAX_CUT_POINTS)return@withTransaction InkCommitResult.Rejected}
                    true
                }
                is InkMutation.CutVisibility->{
                    val oldCut=dao.cut(change.cutId)
                    if(oldCut==null||oldCut.noteId!=command.noteId||oldCut.visible==change.visible)return@withTransaction InkCommitResult.Conflict
                    if(change.visible){
                        val snapshot=read(command.noteId)
                        val proposed=snapshot.copy(cuts=snapshot.cuts.map{if(it.selection.cut.id==change.cutId)it.copy(visible=true)else it})
                        try{InkSession(proposed).visibleDraft()}catch(_:IllegalArgumentException){return@withTransaction InkCommitResult.Rejected}
                    }
                    change.visible
                }
            }
            // Preserve target conflicts before layer validation; both checks still precede every write.
            val authoring=try{PageAuthoringRepository(db).acceptInk(command)}catch(e:IllegalArgumentException){return@withTransaction if(e.message=="LAYER_WRITE_SCOPE_CHANGED")InkCommitResult.Conflict else InkCommitResult.Rejected}
            if(current==null)dao.insertPage(InkPageRow(command.noteId,next))else check(dao.compareAndSet(command.noteId,command.expectedRevision,next)==1)
            when(val change=command.mutation){
                is InkMutation.Replace->{
                    change.hidden.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,false)==it.size)}
                    change.added.forEach{stroke->dao.insertStroke(InkStrokeRow(stroke.id,command.noteId,InkStrokeCodec.encode(stroke),stroke.samples.size,true,next))}
                }
                is InkMutation.Swap->{
                    change.hide.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,false)==it.size)}
                    change.show.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,true)==it.size)}
                }
                is InkMutation.Add->dao.insertStroke(InkStrokeRow(change.stroke.id,command.noteId,InkStrokeCodec.encode(change.stroke),change.stroke.samples.size,true,next))
                is InkMutation.Visibility->change.ids.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,change.visible)==it.size)}
                is InkMutation.Cut->dao.insertCut(InkCutRow(change.selection.cut.id,command.noteId,InkCutCodec.encode(change.selection.cut),change.selection.strokeIds.joinToString(","),true,next))
                is InkMutation.CutVisibility->check(dao.cutVisibility(change.cutId,command.noteId,change.visible)==1)
            }
            authoring?.let{db.authoring().put(it)}
            // Pure annotation changes do not change the transcribed underlying handwriting.
            // Advance only an already-valid index; never resurrect a stale revision.
            suspend fun highlights(ids:List<String>):Boolean{for(id in ids){val r=dao.stroke(id)?:return false;if(InkStrokeCodec.decode(r.payload).pen!=InkPen.HIGHLIGHTER)return false};return ids.isNotEmpty()}
            val annotationOnly=when(val m=command.mutation){
                is InkMutation.Add->m.stroke.pen==InkPen.HIGHLIGHTER
                is InkMutation.Visibility->highlights(m.ids)
                is InkMutation.Cut->highlights(m.selection.strokeIds)
                is InkMutation.CutVisibility->dao.cut(m.cutId)?.strokeIds?.split(',')?.let{highlights(it)}?:false
                is InkMutation.Replace->m.added.all{it.pen==InkPen.HIGHLIGHTER}&&(m.hidden.isEmpty()||highlights(m.hidden))
                is InkMutation.Swap->highlights(m.hide+m.show)
            }
            if(annotationOnly)db.pages().carrySearch(command.noteId,command.expectedRevision,next)
            fault(InkFaultPoint.BEFORE_RECEIPT)
            dao.insertReceipt(InkReceiptRow(command.commandId,command.noteId,digest,next,command.strokeIds.joinToString(","),visible))
            // Search query joins this exact page head, so stale text becomes
            // unsearchable in the same transaction, even before an OCR worker runs.
            db.notes().touch(owner.notebookId,System.currentTimeMillis());InkCommitResult.Committed(next)
        };fault(InkFaultPoint.AFTER_TRANSACTION)
        if(result is InkCommitResult.Committed){
            val sealed=when(val m=command.mutation){is InkMutation.Add->listOf(m.stroke.id);is InkMutation.Replace->m.added.map{it.id};else->emptyList()}
            if(db.checkpointRoot!=null)sealed.forEach{checkpoints.remove(command.noteId,it)}
        };return result
    }
    internal suspend fun populateImportedPage(id:String,file:InkPageFile){
        DocumentRepository(db).attach(id,file.source)
        check(db.ink().page(id)==null);db.pages().paper(id,file.paper.ordinal)
        val strokeIds=file.strokes.associate{it.id to UUID.randomUUID().toString()}
        dao.insertPage(InkPageRow(id,file.strokes.size.toLong()))
        file.strokes.forEachIndexed{index,old->val s=InkStroke(checkNotNull(strokeIds[old.id]),old.pen,old.color,old.width,old.tool,old.samples,old.world,old.cuts,old.appearance);dao.insertStroke(InkStrokeRow(s.id,id,InkStrokeCodec.encode(s),s.samples.size,true,index.toLong()+1))}
        val objects=PageObjectRepository(db).import(id,file.objects,strokeIds,file.imageSources)
        file.authoring?.let{state->val owner=checkNotNull(db.pages().get(id));val copied=state.copied(id,strokeIds,objects);PageAuthoringRepository(db).validate(AuthoringScope.page(owner.notebookId,id),copied);db.authoring().put(PageAuthoringRow(AuthoringScopeKind.PAGE.name,id,owner.notebookId,0,PageAuthoringCodec.encode(copied)))}
    }
    suspend fun importCopy(file:InkPageFile):Note=db.withTransaction {
        val title=(file.title.take(115)+" · 副本").take(120)
        val n=WorkspaceRepository(db).create(title,file.world,file.paper)
        if(file.text.isNotEmpty())check(NoteRepository(db).save(SaveNote(UUID.randomUUID().toString(),n.id,1,n.title,file.text)) is SaveResult.Committed)
        populateImportedPage(n.id,file);checkNotNull(NoteRepository(db).read(n.id))
    }
}
