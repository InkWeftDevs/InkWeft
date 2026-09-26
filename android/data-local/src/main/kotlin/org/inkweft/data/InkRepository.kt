// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*
import java.util.UUID

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
    @Query("SELECT * FROM ink_strokes WHERE id=:id") suspend fun stroke(id:String):InkStrokeRow?
    @Query("SELECT * FROM ink_receipts WHERE commandId=:id") suspend fun receipt(id:String):InkReceiptRow?
    @Query("SELECT * FROM ink_cuts WHERE noteId=:id ORDER BY createdRevision,id") suspend fun cuts(id:String):List<InkCutRow>
    @Query("SELECT * FROM ink_cuts WHERE id=:id") suspend fun cut(id:String):InkCutRow?
    @Query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=:id") suspend fun count(id:String):Int
    @Query("SELECT COALESCE(SUM(pointCount),0) FROM ink_strokes WHERE noteId=:id") suspend fun pointCount(id:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertPage(row:InkPageRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertStroke(row:InkStrokeRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertReceipt(row:InkReceiptRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertCut(row:InkCutRow)
    @Query("UPDATE ink_cuts SET visible=:visible WHERE id=:id AND noteId=:noteId AND visible!=:visible") suspend fun cutVisibility(id:String,noteId:String,visible:Boolean):Int
    @Query("UPDATE ink_pages SET revision=:next WHERE noteId=:id AND revision=:expected") suspend fun compareAndSet(id:String,expected:Long,next:Long):Int
    @Query("UPDATE ink_strokes SET visible=:visible WHERE noteId=:noteId AND id IN (:ids)") suspend fun setVisibility(noteId:String,ids:List<String>,visible:Boolean):Int
}
enum class InkFaultPoint { BEFORE_RECEIPT,AFTER_TRANSACTION }
class InkRepository(private val db:NoteDatabase,private val fault:(InkFaultPoint)->Unit={}) {
    private val dao=db.ink()
    private suspend fun owner(id:String)=db.pages().get(id)?:NotebookPages(db).ensureFirst(id)
    suspend fun read(noteId:String):InkPage=db.withTransaction {
        val owner=owner(noteId);val page=dao.page(noteId);val rows=dao.strokes(noteId)
        check(rows.size<=InkLimits.MAX_STROKES&&rows.sumOf{it.pointCount}<=InkLimits.MAX_PAGE_POINTS)
        val decoded=rows.map{val s=InkStrokeCodec.decode(it.payload);check(s.id==it.id&&s.samples.size==it.pointCount&&s.world==owner.world);StoredInk(s,it.visible,it.createdRevision)}
        val ids=decoded.map{it.stroke.id}.toSet()
        val cuts=dao.cuts(noteId);check(cuts.size<=InkLimits.MAX_CUTS)
        val masks=cuts.map{r->val cut=InkCutCodec.decode(r.payload);check(cut.id==r.id);val target=r.strokeIds.split(',');check(target.all{it in ids});StoredCut(EraseSelection(cut,target),r.visible,r.createdRevision)}
        check(page!=null||(rows.isEmpty()&&masks.isEmpty()))
        InkPage(noteId,page?.revision?:0,decoded,masks).also{InkSession(it).visibleDraft()}
    }
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
                    if(dao.count(command.noteId)+change.added.size>InkLimits.MAX_STROKES ||
                        dao.pointCount(command.noteId)+change.added.sumOf{it.samples.size}>InkLimits.MAX_PAGE_POINTS)
                        return@withTransaction InkCommitResult.Rejected
                    for(id in change.hidden){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict}
                    if(change.added.any{it.world!=owner.world||dao.stroke(it.id)!=null})return@withTransaction InkCommitResult.Rejected
                    true
                }
                is InkMutation.Swap->{
                    for(id in change.hide){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||!row.visible)return@withTransaction InkCommitResult.Conflict}
                    for(id in change.show){val row=dao.stroke(id);if(row==null||row.noteId!=command.noteId||row.visible)return@withTransaction InkCommitResult.Conflict}
                    true
                }
                is InkMutation.Add->{if(change.stroke.world!=owner.world||dao.stroke(change.stroke.id)!=null||dao.count(command.noteId)>=InkLimits.MAX_STROKES||dao.pointCount(command.noteId)+change.stroke.samples.size>InkLimits.MAX_PAGE_POINTS)return@withTransaction InkCommitResult.Rejected;true}
                is InkMutation.Visibility->{for(id in change.ids){val r=dao.stroke(id);if(r==null||r.noteId!=command.noteId||r.visible==change.visible)return@withTransaction InkCommitResult.Conflict};change.visible}
                is InkMutation.Cut->{
                    val c=change.selection;val oldCuts=dao.cuts(command.noteId)
                    if(dao.cut(c.cut.id)!=null||oldCuts.size>=InkLimits.MAX_CUTS)return@withTransaction InkCommitResult.Rejected
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
            if(current==null)dao.insertPage(InkPageRow(command.noteId,next))else check(dao.compareAndSet(command.noteId,command.expectedRevision,next)==1)
            when(val change=command.mutation){
                is InkMutation.Replace->{
                    if(change.hidden.isNotEmpty())check(dao.setVisibility(command.noteId,change.hidden,false)==change.hidden.size)
                    change.added.forEach{stroke->dao.insertStroke(InkStrokeRow(stroke.id,command.noteId,InkStrokeCodec.encode(stroke),stroke.samples.size,true,next))}
                }
                is InkMutation.Swap->{
                    if(change.hide.isNotEmpty())check(dao.setVisibility(command.noteId,change.hide,false)==change.hide.size)
                    if(change.show.isNotEmpty())check(dao.setVisibility(command.noteId,change.show,true)==change.show.size)
                }
                is InkMutation.Add->dao.insertStroke(InkStrokeRow(change.stroke.id,command.noteId,InkStrokeCodec.encode(change.stroke),change.stroke.samples.size,true,next))
                is InkMutation.Visibility->check(dao.setVisibility(command.noteId,change.ids,change.visible)==change.ids.size)
                is InkMutation.Cut->dao.insertCut(InkCutRow(change.selection.cut.id,command.noteId,InkCutCodec.encode(change.selection.cut),change.selection.strokeIds.joinToString(","),true,next))
                is InkMutation.CutVisibility->check(dao.cutVisibility(change.cutId,command.noteId,change.visible)==1)
            }
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
        };fault(InkFaultPoint.AFTER_TRANSACTION);return result
    }
    internal suspend fun populateImportedPage(id:String,file:InkPageFile){
        check(db.ink().page(id)==null);db.pages().paper(id,file.paper.ordinal)
        dao.insertPage(InkPageRow(id,file.strokes.size.toLong()))
        file.strokes.forEachIndexed{index,old->val s=InkStroke(UUID.randomUUID().toString(),old.pen,old.color,old.width,old.tool,old.samples,old.world,old.cuts);dao.insertStroke(InkStrokeRow(s.id,id,InkStrokeCodec.encode(s),s.samples.size,true,index.toLong()+1))}
    }
    suspend fun importCopy(file:InkPageFile):Note=db.withTransaction {
        val title=(file.title.take(115)+" · 副本").take(120)
        val n=WorkspaceRepository(db).create(title,file.world,file.paper)
        if(file.text.isNotEmpty())check(NoteRepository(db).save(SaveNote(UUID.randomUUID().toString(),n.id,1,n.title,file.text)) is SaveResult.Committed)
        populateImportedPage(n.id,file);checkNotNull(NoteRepository(db).read(n.id))
    }
}
