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

private val windowTraceIds=java.util.concurrent.atomic.AtomicInteger()

@Entity(tableName="ink_pages",foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkPageRow(@PrimaryKey val noteId:String,val revision:Long)
@Entity(tableName="ink_strokes",indices=[Index("noteId"),Index(value=["noteId","visible","createdRevision","id"])],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkStrokeRow(@PrimaryKey val id:String,val noteId:String,val payload:ByteArray,val pointCount:Int,val visible:Boolean,val createdRevision:Long)
@Entity(tableName="ink_receipts",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkReceiptRow(@PrimaryKey val commandId:String,val noteId:String,val digest:String,val committedRevision:Long,val strokeIds:String,val visible:Boolean)
@Entity(tableName="ink_cuts",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkCutRow(@PrimaryKey val id:String,val noteId:String,val payload:ByteArray,val strokeIds:String,val visible:Boolean,val createdRevision:Long)
data class InkCutReferenceRow(val id:String,val strokeIds:String)
data class InkStrokeMetadataRow(val id:String,val pointCount:Int,val visible:Boolean,val createdRevision:Long)
data class InkCutMetadataRow(val id:String,val strokeIds:String,val visible:Boolean,val createdRevision:Long)
@Dao
interface InkDao {
    @Query("SELECT * FROM ink_pages WHERE noteId=:id") suspend fun page(id:String):InkPageRow?
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id ORDER BY createdRevision,id") suspend fun strokes(id:String):List<InkStrokeRow>
    @Query("SELECT id,pointCount,visible,createdRevision FROM ink_strokes WHERE noteId=:id ORDER BY createdRevision,id") suspend fun strokeMetadata(id:String):List<InkStrokeMetadataRow>
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id AND id IN (:ids) ORDER BY createdRevision,id") suspend fun strokes(id:String,ids:List<String>):List<InkStrokeRow>
    /** Layer ownership needs every retained identity, including hidden history, but no ink payload. */
    @Query("SELECT id FROM ink_strokes WHERE noteId=:id ORDER BY createdRevision,id") suspend fun strokeIds(id:String):List<String>
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id AND visible=1 ORDER BY createdRevision,id") suspend fun visibleStrokes(id:String):List<InkStrokeRow>
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id AND visible=1 AND id IN (:ids) ORDER BY createdRevision,id") suspend fun visibleStrokes(id:String,ids:List<String>):List<InkStrokeRow>
    @Query("SELECT revision FROM ink_pages WHERE noteId=:id") fun observeRevision(id:String):kotlinx.coroutines.flow.Flow<Long?>
    @Query("SELECT * FROM ink_strokes WHERE id=:id") suspend fun stroke(id:String):InkStrokeRow?
    @Query("SELECT * FROM ink_receipts WHERE commandId=:id") suspend fun receipt(id:String):InkReceiptRow?
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id ORDER BY createdRevision,id") suspend fun cuts(id:String):List<InkCutRow>
    @Query("SELECT id,strokeIds,visible,createdRevision FROM ink_cuts WHERE noteId=:id ORDER BY createdRevision,id") suspend fun cutMetadata(id:String):List<InkCutMetadataRow>
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id AND id IN (:ids) ORDER BY createdRevision,id") suspend fun cuts(id:String,ids:List<String>):List<InkCutRow>
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id AND visible=1 ORDER BY createdRevision,id") suspend fun visibleCuts(id:String):List<InkCutRow>
    @Query("SELECT id,strokeIds FROM ink_cuts WHERE noteId=:id AND visible=1 ORDER BY createdRevision,id") suspend fun visibleCutReferences(id:String):List<InkCutReferenceRow>
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id AND visible=1 AND id IN (:ids) ORDER BY createdRevision,id") suspend fun visibleCuts(id:String,ids:List<String>):List<InkCutRow>
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
/** Saved display/copy content and its revision, without retained undo geometry. */
data class VisibleInkSnapshot(val noteId:String,val revision:Long,val strokes:List<InkStroke>)
class InkRepository(private val db:NoteDatabase,private val fault:(InkFaultPoint)->Unit={}) {
    @Volatile var readObserver:((ReadTiming)->Unit)?=null
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
    private data class EditingRows(val rows:ReadRows,val unloaded:UnloadedInk,val complete:Boolean=true)
    /** Visible author geometry plus only the identities needed to resume this revision's undo. */
    suspend fun readForEditing(noteId:String,history:InkSession.History?=null,bounds:CanvasBounds?=null,pointBudget:Int=Int.MAX_VALUE):InkPage {
        val trace=windowTraceIds.incrementAndGet();android.os.Trace.beginAsyncSection("InkWeft.ink.window",trace)
        try{
        require(pointBudget>=InkLimits.MAX_POINTS)
        val started=System.nanoTime()
        val frozen=db.withTransaction {
            val owner=owner(noteId);val page=dao.page(noteId)
            val metadata=dao.strokeMetadata(noteId);val masks=dao.cutMetadata(noteId)
            check(metadata.all{it.pointCount in 1..InkLimits.MAX_POINTS})
            val ids=metadata.map{it.id}.toSet();ids.forEach{UUID.fromString(it)}
            val resume=history?.takeIf{it.noteId==noteId&&it.revision==(page?.revision?:0)}
            if(resume!=null)check(resume.strokeIds.all{it in ids}&&resume.cutIds.all{id->masks.any{it.id==id}})
            var complete=true
            val visible=if(bounds==null)metadata.filter{it.visible}.map{it.id}.toSet()else{
                val sql=db.openHelper.writableDatabase;SpatialIndex.prepare(sql,noteId)
                val selected=linkedSetOf<String>();var cursor:SpatialIndex.Cursor?=null;var points=0L
                scan@while(true){
                    val batch=SpatialIndex.ink(sql,noteId,bounds,cursor)
                    if(batch.isEmpty())break
                    for(row in batch){
                        check(row.pointCount in 1..InkLimits.MAX_POINTS)
                        if(points+row.pointCount>pointBudget){complete=false;break@scan}
                        selected.add(row.id);points+=row.pointCount;cursor=SpatialIndex.Cursor(row.revision,row.id)
                    }
                };selected
            }
            val needed=visible+resume?.strokeIds.orEmpty()
            val neededMasks=masks.filter{row->
                UUID.fromString(row.id)
                val targets=row.strokeIds.split(',')
                check(targets.size in 1..InkLimits.MAX_MUTATION_STROKES&&targets.distinct().size==targets.size&&targets.all{it in ids})
                (row.visible&&targets.any{it in needed})||row.id in resume?.cutIds.orEmpty()
            }.map{it.id}.toSet()
            val rows=needed.toList().chunked(900).flatMap{dao.strokes(noteId,it)}.sortedWith(compareBy({it.createdRevision},{it.id}))
            val cuts=neededMasks.toList().chunked(900).flatMap{dao.cuts(noteId,it)}.sortedWith(compareBy({it.createdRevision},{it.id}))
            check(rows.map{it.id}.toSet()==needed&&cuts.map{it.id}.toSet()==neededMasks)
            check(page!=null||(metadata.isEmpty()&&masks.isEmpty()))
            EditingRows(ReadRows(owner,page,rows,cuts),UnloadedInk(
                metadata.filter{it.id !in needed}.associate{it.id to it.pointCount},
                masks.filter{it.id !in neededMasks}.map{it.id}.toSet()),complete)
        }
        val frozenAt=System.nanoTime();val context=currentCoroutineContext();val rows=frozen.rows
        val strokes=rows.strokes.map{row->context.ensureActive();val stroke=InkStrokeCodec.decode(row.payload)
            check(stroke.id==row.id&&stroke.samples.size==row.pointCount&&stroke.world==rows.owner.world)
            StoredInk(stroke,row.visible,row.createdRevision)
        }
        val cuts=rows.cuts.map{row->context.ensureActive();val cut=InkCutCodec.decode(row.payload);check(cut.id==row.id)
            StoredCut(EraseSelection(cut,row.strokeIds.split(',')),row.visible,row.createdRevision)
        }
        return InkPage(noteId,rows.page?.revision?:0,strokes,cuts,frozen.unloaded,frozen.complete).also{
            InkSession(it).visibleDraft()
            val decodedAt=System.nanoTime()
            runCatching{readObserver?.invoke(ReadTiming(ReadKind.INK,frozenAt-started,decodedAt-frozenAt,strokes.size+cuts.size))}
        }
        }finally{android.os.Trace.endAsyncSection("InkWeft.ink.window",trace)}
    }
    suspend fun read(noteId:String):InkPage {
        // Freeze rows atomically; decoding immutable blobs must not hold the Room
        // transaction executor while another page is trying to save handwriting.
        val started=System.nanoTime()
        val frozen=db.withTransaction{ReadRows(owner(noteId),dao.page(noteId),dao.strokes(noteId),dao.cuts(noteId))}
        val frozenAt=System.nanoTime()
        val owner=frozen.owner;val page=frozen.page;val rows=frozen.strokes
        val context=currentCoroutineContext()
        val decoded=rows.map{context.ensureActive();val s=InkStrokeCodec.decode(it.payload);check(s.id==it.id&&s.samples.size==it.pointCount&&s.world==owner.world);StoredInk(s,it.visible,it.createdRevision)}
        val ids=decoded.map{it.stroke.id}.toSet()
        val cuts=frozen.cuts
        val masks=cuts.map{r->val cut=InkCutCodec.decode(r.payload);check(cut.id==r.id);val target=r.strokeIds.split(',');check(target.all{it in ids});StoredCut(EraseSelection(cut,target),r.visible,r.createdRevision)}
        check(page!=null||(rows.isEmpty()&&masks.isEmpty()))
        return InkPage(noteId,page?.revision?:0,decoded,masks).also{
            InkSession(it).visibleDraft()
            val decodedAt=System.nanoTime()
            // Diagnostics are observational and may never turn a successful read into failure.
            runCatching{readObserver?.invoke(ReadTiming(ReadKind.INK,frozenAt-started,decodedAt-frozenAt,rows.size+cuts.size))}
        }
    }
    /** IDs, revision, selected geometry and relevant masks are frozen in the same transaction. */
    suspend fun readVisibleSnapshot(noteId:String,strokeIds:List<String>?=null):VisibleInkSnapshot {
        if(strokeIds==null){
            val revision=db.withTransaction{owner(noteId);dao.page(noteId)?.revision?:0}
            val strokes=mutableListOf<InkStroke>()
            visitVisible(noteId,revision){strokes.addAll(it)}
            return VisibleInkSnapshot(noteId,revision,strokes)
        }
        val started=System.nanoTime()
        val selected=strokeIds?.toList()
        selected?.let{require(it.distinct().size==it.size);it.forEach{value->UUID.fromString(value)}}
        val frozen=db.withTransaction{
            val owner=owner(noteId);val page=dao.page(noteId)
            val rows=if(selected==null)dao.visibleStrokes(noteId)else selected.chunked(900)
                .flatMap{dao.visibleStrokes(noteId,it)}.sortedWith(compareBy({it.createdRevision},{it.id}))
            val visible=rows.map{it.id}.toSet()
            val references=if(visible.isEmpty())emptyList()else dao.visibleCutReferences(noteId)
            val owned=if(references.isEmpty())emptySet()else dao.strokeIds(noteId).toSet()
            val relevant=references.filter{row->
                val targets=row.strokeIds.split(',')
                check(targets.size in 1..InkLimits.MAX_MUTATION_STROKES&&targets.distinct().size==targets.size&&targets.all{it in owned})
                targets.any{it in visible}
            }.map{it.id}
            val cuts=relevant.chunked(900).flatMap{dao.visibleCuts(noteId,it)}
            check(cuts.map{it.id}==relevant)
            ReadRows(owner,page,rows,cuts)
        }
        val frozenAt=System.nanoTime()
        val rows=frozen.strokes
        val context=currentCoroutineContext()
        val strokes=rows.map{row->context.ensureActive();InkStrokeCodec.decode(row.payload).also{check(it.id==row.id&&it.samples.size==row.pointCount&&it.world==frozen.owner.world)}}
        val visible=strokes.map{it.id}.toSet();val byStroke=mutableMapOf<String,MutableList<InkCut>>()
        frozen.cuts.forEach{row->val cut=InkCutCodec.decode(row.payload);check(cut.id==row.id)
            val targets=row.strokeIds.split(',');check(targets.distinct().size==targets.size)
            targets.filter{it in visible}.forEach{byStroke.getOrPut(it){mutableListOf()}.add(cut)}
        }
        check(frozen.page!=null||(rows.isEmpty()&&frozen.cuts.isEmpty()))
        return VisibleInkSnapshot(noteId,frozen.page?.revision?:0,strokes.map{it.withCuts(byStroke[it.id].orEmpty())}).also{
            val decodedAt=System.nanoTime()
            runCatching{readObserver?.invoke(ReadTiming(ReadKind.INK,frozenAt-started,decodedAt-frozenAt,rows.size+frozen.cuts.size))}
        }
    }
    /** Bounded geometry batches at one revision. Callers discard the entire result on revision
     * change; cancellation releases the database between batches and never publishes a prefix. */
    suspend fun visitVisible(noteId:String,revision:Long,bounds:CanvasBounds?=null,visit:suspend (List<InkStroke>)->Unit){
        var cursor:SpatialIndex.Cursor?=null
        while(true){
            currentCoroutineContext().ensureActive()
            val candidates=db.withTransaction{
                owner(noteId);check((dao.page(noteId)?.revision?:0)==revision){"INK_WINDOW_REVISION_CHANGED"}
                val sql=db.openHelper.writableDatabase
                if(bounds!=null)SpatialIndex.prepare(sql,noteId)
                SpatialIndex.ink(sql,noteId,bounds,cursor).let{rows->
                    var points=0;var bytes=0L
                    rows.takeWhile{row->
                        check(row.pointCount in 1..InkLimits.MAX_POINTS&&row.byteCount in 1..InkLimits.MAX_STROKE_BYTES)
                        (points+row.pointCount<=65_536&&bytes+row.byteCount<=InkLimits.MAX_STROKE_BYTES).also{if(it){points+=row.pointCount;bytes+=row.byteCount}}
                    }
                }
            }
            if(candidates.isEmpty())break
            val snapshot=readVisibleSnapshot(noteId,candidates.map{it.id})
            check(snapshot.revision==revision&&snapshot.strokes.map{it.id}==candidates.map{it.id}){"INK_WINDOW_REVISION_CHANGED"}
            visit(snapshot.strokes)
            val last=candidates.last();cursor=SpatialIndex.Cursor(last.revision,last.id)
        }
        check((dao.page(noteId)?.revision?:0)==revision){"INK_WINDOW_REVISION_CHANGED"}
    }
    suspend fun revision(noteId:String):Long=db.withTransaction{owner(noteId);dao.page(noteId)?.revision?:0}
    suspend fun visibleBounds(noteId:String,revision:Long):CanvasBounds?=db.withTransaction{
        owner(noteId);check((dao.page(noteId)?.revision?:0)==revision){"INK_WINDOW_REVISION_CHANGED"}
        val sql=db.openHelper.writableDatabase;SpatialIndex.prepare(sql,noteId)
        sql.query("SELECT MIN(b.minX),MIN(b.minY),MAX(b.maxX),MAX(b.maxY) FROM page_spatial_bounds b JOIN page_spatial_entries e ON e.key=b.key WHERE e.pageId=? AND e.kind=0",arrayOf(noteId)).use{c->
            check(c.moveToFirst());if(c.isNull(0))null else CanvasBounds(c.getDouble(0),c.getDouble(1),c.getDouble(2),c.getDouble(3))
        }
    }
    /** Validate backups without retaining a page of raw geometry, including hidden originals/masks. */
    suspend fun validateStored(noteId:String):Long {
        val (owner,head,metadata)=db.withTransaction{Triple(owner(noteId),dao.page(noteId),dao.strokeMetadata(noteId))}
        val revision=head?.revision?:0
        check(revision>=0&&metadata.all{it.pointCount in 1..InkLimits.MAX_POINTS})
        val ids=metadata.map{it.id}.toSet();ids.forEach{UUID.fromString(it)}
        check(ids.size==metadata.size&&(head!=null||ids.isEmpty()))
        for(batch in metadata.filterNot{it.visible}.chunked(16)){
            currentCoroutineContext().ensureActive()
            val rows=db.withTransaction{check((dao.page(noteId)?.revision?:0)==revision);dao.strokes(noteId,batch.map{it.id})}
            check(rows.size==batch.size)
            rows.forEach{row->val stroke=InkStrokeCodec.decode(row.payload);check(stroke.id==row.id&&stroke.world==owner.world&&stroke.samples.size==row.pointCount)}
        }
        var visible=0L;visitVisible(noteId,revision){visible+=it.size}
        check(visible==metadata.count{it.visible}.toLong())
        val masks=db.withTransaction{check((dao.page(noteId)?.revision?:0)==revision);dao.cutMetadata(noteId)}
        check(head!=null||masks.isEmpty())
        for(batch in masks.chunked(16)){
            currentCoroutineContext().ensureActive()
            val rows=db.withTransaction{check((dao.page(noteId)?.revision?:0)==revision);dao.cuts(noteId,batch.map{it.id})}
            check(rows.size==batch.size)
            rows.forEach{row->val cut=InkCutCodec.decode(row.payload);check(cut.id==row.id);val selection=EraseSelection(cut,row.strokeIds.split(','));check(selection.strokeIds.all{it in ids})}
        }
        check((dao.page(noteId)?.revision?:0)==revision){"INK_WINDOW_REVISION_CHANGED"}
        return revision
    }
    internal data class CopiedInk(val sourceRevision:Long,val strokeIds:Map<String,String>)
    /** Caller owns the encompassing copy/receipt transaction. Geometry never accumulates across batches. */
    internal suspend fun copyVisibleTo(source:String,target:String):CopiedInk {
        check(db.inTransaction());require(source!=target&&dao.page(target)==null)
        val revision=dao.page(source)?.revision?:0;val ids=linkedMapOf<String,String>();val cuts=mutableMapOf<String,String>()
        dao.insertPage(InkPageRow(target,0));var count=0L
        visitVisible(source,revision){batch->batch.forEach{old->
            val id=UUID.randomUUID().toString();ids[old.id]=id
            val masks=old.cuts.map{cut->InkCut(cuts.getOrPut(cut.id){UUID.randomUUID().toString()},cut.radius,cut.points,cut.shape)}
            val copied=InkStroke(id,old.pen,old.color,old.width,old.tool,old.samples,old.world,masks,old.appearance)
            dao.insertStroke(InkStrokeRow(id,target,InkStrokeCodec.encodeStored(copied),copied.samples.size,true,++count))
            SpatialIndex.put(db.openHelper.writableDatabase,target,SpatialIndex.INK,id,copied.bounds())
        }}
        if(count>0)check(dao.compareAndSet(target,0,count)==1)
        return CopiedInk(revision,ids)
    }
    /** Read-only previews never deserialize hidden move/erase history. */
    suspend fun readVisible(noteId:String):List<InkStroke> = readVisibleSnapshot(noteId).strokes
    fun observeVisible(noteId:String)=dao.observeRevision(noteId).distinctUntilChanged().map{readVisible(noteId)}
    suspend fun save(command:CommitInk):InkCommitResult {
        val result=try{db.withTransaction {
            val digest=command.digest();val old=dao.receipt(command.commandId)
            if(old!=null)return@withTransaction if(old.noteId==command.noteId&&old.digest==digest)InkCommitResult.Committed(old.committedRevision)else InkCommitResult.Rejected
            if(db.pages().get(command.noteId)==null&&db.notes().note(command.noteId)==null)return@withTransaction InkCommitResult.Conflict
            val owner=owner(command.noteId);val current=dao.page(command.noteId)
            if(owner.trashedAt!=null || db.workspace().get(owner.notebookId)?.trashedAt!=null)return@withTransaction InkCommitResult.Conflict
            if((current?.revision?:0)!=command.expectedRevision)return@withTransaction InkCommitResult.Conflict
            val next=command.expectedRevision+1
            val visible=when(val change=command.mutation){
                is InkMutation.Replace->{
                    for(id in change.hidden){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict}
                    if(change.added.any{it.world!=owner.world||dao.stroke(it.id)!=null})return@withTransaction InkCommitResult.Rejected
                    true
                }
                is InkMutation.Swap->{
                    for(id in change.hide){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict}
                    for(id in change.show){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||row.visible)return@withTransaction InkCommitResult.Conflict}
                    true
                }
                is InkMutation.Add->{if(change.stroke.world!=owner.world||dao.stroke(change.stroke.id)!=null)return@withTransaction InkCommitResult.Rejected;true}
                is InkMutation.Visibility->{for(id in change.ids){val r=dao.stroke(id);if(r==null||r.noteId!=command.noteId||r.visible==change.visible)return@withTransaction InkCommitResult.Conflict}
                    change.visible}
                is InkMutation.Cut->{
                    val c=change.selection
                    if(dao.cut(c.cut.id)!=null)return@withTransaction InkCommitResult.Rejected
                    val effective=readVisibleSnapshot(command.noteId,c.strokeIds).strokes.associateBy{it.id}
                    for(id in c.strokeIds){val s=effective[id]?:return@withTransaction InkCommitResult.Conflict;if(s.cuts.size>=InkLimits.MAX_CUTS||s.cuts.sumOf{it.points.size}+c.cut.points.size>InkLimits.MAX_CUT_POINTS)return@withTransaction InkCommitResult.Rejected}
                    true
                }
                is InkMutation.CutVisibility->{
                    val oldCut=dao.cut(change.cutId)
                    if(oldCut==null||oldCut.noteId!=command.noteId||oldCut.visible==change.visible)return@withTransaction InkCommitResult.Conflict
                    if(change.visible){
                        val cut=InkCutCodec.decode(oldCut.payload);check(cut.id==oldCut.id)
                        val selection=EraseSelection(cut,oldCut.strokeIds.split(','))
                        val owned=dao.strokeIds(command.noteId).toSet();check(selection.strokeIds.all{it in owned})
                        val targets=readVisibleSnapshot(command.noteId,selection.strokeIds).strokes
                        try{targets.forEach{it.withCuts(listOf(cut))}}catch(_:IllegalArgumentException){return@withTransaction InkCommitResult.Rejected}
                    }
                    change.visible
                }
            }
            // Preserve target conflicts before layer validation; both checks still precede every write.
            val authoring=try{PageAuthoringRepository(db).acceptInk(command)}catch(e:IllegalArgumentException){throw GroupAbort(if(e.message=="LAYER_WRITE_SCOPE_CHANGED")InkCommitResult.Conflict else InkCommitResult.Rejected)}
            if(current==null)dao.insertPage(InkPageRow(command.noteId,next))else check(dao.compareAndSet(command.noteId,command.expectedRevision,next)==1)
            when(val change=command.mutation){
                is InkMutation.Replace->{
                    change.hidden.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,false)==it.size)}
                    change.added.forEach{stroke->dao.insertStroke(InkStrokeRow(stroke.id,command.noteId,InkStrokeCodec.encodeStored(stroke),stroke.samples.size,true,next))}
                }
                is InkMutation.Swap->{
                    change.hide.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,false)==it.size)}
                    change.show.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,true)==it.size)}
                }
                is InkMutation.Add->dao.insertStroke(InkStrokeRow(change.stroke.id,command.noteId,InkStrokeCodec.encodeStored(change.stroke),change.stroke.samples.size,true,next))
                is InkMutation.Visibility->change.ids.chunked(900).forEach{check(dao.setVisibility(command.noteId,it,change.visible)==it.size)}
                is InkMutation.Cut->dao.insertCut(InkCutRow(change.selection.cut.id,command.noteId,InkCutCodec.encode(change.selection.cut),change.selection.strokeIds.joinToString(","),true,next))
                is InkMutation.CutVisibility->check(dao.cutVisibility(change.cutId,command.noteId,change.visible)==1)
            }
            val added=when(val change=command.mutation){is InkMutation.Add->listOf(change.stroke);is InkMutation.Replace->change.added;else->emptyList()}
            added.forEach{SpatialIndex.put(db.openHelper.writableDatabase,command.noteId,SpatialIndex.INK,it.id,it.bounds())}
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
        }}catch(abort:GroupAbort){abort.result};fault(InkFaultPoint.AFTER_TRANSACTION)
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
        file.strokes.forEachIndexed{index,old->val s=InkStroke(checkNotNull(strokeIds[old.id]),old.pen,old.color,old.width,old.tool,old.samples,old.world,old.cuts,old.appearance);dao.insertStroke(InkStrokeRow(s.id,id,InkStrokeCodec.encodeStored(s),s.samples.size,true,index.toLong()+1))}
        val objects=PageObjectRepository(db).import(id,file.objects,strokeIds,file.imageSources)
        file.authoring?.let{state->val owner=checkNotNull(db.pages().get(id));val copied=state.copied(id,strokeIds,objects);PageAuthoringRepository(db).validate(AuthoringScope.page(owner.notebookId,id),copied);PageAuthoringRepository(db).storePage(id,owner.notebookId,0,copied)}
    }
    suspend fun importCopy(file:InkPageFile):Note=db.withTransaction {
        val title=(file.title.take(115)+" · 副本").take(120)
        val n=WorkspaceRepository(db).create(title,file.world,file.paper)
        if(file.text.isNotEmpty())check(NoteRepository(db).save(SaveNote(UUID.randomUUID().toString(),n.id,1,n.title,file.text)) is SaveResult.Committed)
        populateImportedPage(n.id,file);checkNotNull(NoteRepository(db).read(n.id))
    }
}
