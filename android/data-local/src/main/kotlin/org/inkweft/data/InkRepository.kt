// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="ink_pages",foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkPageRow(@PrimaryKey val noteId:String,val revision:Long)
@Entity(tableName="ink_strokes",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkStrokeRow(@PrimaryKey val id:String,val noteId:String,val payload:ByteArray,val pointCount:Int,val visible:Boolean,val createdRevision:Long)
@Entity(tableName="ink_receipts",indices=[Index("noteId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class InkReceiptRow(@PrimaryKey val commandId:String,val noteId:String,val digest:String,val committedRevision:Long,val strokeIds:String,val visible:Boolean)
@Dao
interface InkDao {
    @Query("SELECT * FROM ink_pages WHERE noteId=:id") suspend fun page(id:String):InkPageRow?
    @Query("SELECT * FROM ink_strokes WHERE noteId=:id ORDER BY createdRevision,id") suspend fun strokes(id:String):List<InkStrokeRow>
    @Query("SELECT * FROM ink_strokes WHERE id=:id") suspend fun stroke(id:String):InkStrokeRow?
    @Query("SELECT * FROM ink_receipts WHERE commandId=:id") suspend fun receipt(id:String):InkReceiptRow?
    @Query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=:id") suspend fun count(id:String):Int
    @Query("SELECT COALESCE(SUM(pointCount),0) FROM ink_strokes WHERE noteId=:id") suspend fun pointCount(id:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertPage(row:InkPageRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertStroke(row:InkStrokeRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertReceipt(row:InkReceiptRow)
    @Query("UPDATE ink_pages SET revision=:next WHERE noteId=:id AND revision=:expected") suspend fun compareAndSet(id:String,expected:Long,next:Long):Int
    @Query("UPDATE ink_strokes SET visible=:visible WHERE noteId=:noteId AND id IN (:ids)") suspend fun setVisibility(noteId:String,ids:List<String>,visible:Boolean):Int
}
enum class InkFaultPoint { BEFORE_RECEIPT,AFTER_TRANSACTION }
class InkRepository(private val db:NoteDatabase,private val fault:(InkFaultPoint)->Unit={}) {
    private val dao=db.ink()
    suspend fun read(noteId:String):InkPage=db.withTransaction {
        checkNotNull(db.notes().note(noteId)){"Saved note is missing"}
        val world=db.workspace().get(noteId)?.world?:false
        val page=dao.page(noteId);val rows=dao.strokes(noteId)
        check(rows.size<=InkLimits.MAX_STROKES && rows.sumOf{it.pointCount}<=InkLimits.MAX_PAGE_POINTS)
        val decoded=rows.map{val s=InkStrokeCodec.decode(it.payload);check(s.id==it.id && s.samples.size==it.pointCount && s.world==world);StoredInk(s,it.visible,it.createdRevision)}
        check(page!=null || rows.isEmpty());InkPage(noteId,page?.revision?:0,decoded)
    }
    suspend fun save(command:CommitInk):InkCommitResult {
        val result=db.withTransaction {
            val digest=command.digest();val old=dao.receipt(command.commandId)
            if(old!=null)return@withTransaction if(old.noteId==command.noteId && old.digest==digest)InkCommitResult.Committed(old.committedRevision) else InkCommitResult.Rejected
            if(db.notes().note(command.noteId)==null)return@withTransaction InkCommitResult.Conflict
            val current=dao.page(command.noteId)
            if((current?.revision?:0)!=command.expectedRevision)return@withTransaction InkCommitResult.Conflict
            val next=command.expectedRevision+1
            val visible=when(val change=command.mutation){
                is InkMutation.Add->{
                    val world=db.workspace().get(command.noteId)?.world?:false
                    if(change.stroke.world!=world || dao.stroke(change.stroke.id)!=null || dao.count(command.noteId)>=InkLimits.MAX_STROKES || dao.pointCount(command.noteId)+change.stroke.samples.size>InkLimits.MAX_PAGE_POINTS)return@withTransaction InkCommitResult.Rejected
                    true
                }
                is InkMutation.Visibility->{for(id in change.ids){val row=dao.stroke(id);if(row==null || row.noteId!=command.noteId || row.visible==change.visible)return@withTransaction InkCommitResult.Conflict};change.visible}
            }
            if(current==null)dao.insertPage(InkPageRow(command.noteId,next)) else check(dao.compareAndSet(command.noteId,command.expectedRevision,next)==1)
            when(val change=command.mutation){
                is InkMutation.Add->dao.insertStroke(InkStrokeRow(change.stroke.id,command.noteId,InkStrokeCodec.encode(change.stroke),change.stroke.samples.size,true,next))
                is InkMutation.Visibility->check(dao.setVisibility(command.noteId,change.ids,change.visible)==change.ids.size)
            }
            fault(InkFaultPoint.BEFORE_RECEIPT)
            dao.insertReceipt(InkReceiptRow(command.commandId,command.noteId,digest,next,command.strokeIds.joinToString(","),visible))
            db.notes().touch(command.noteId,System.currentTimeMillis())
            InkCommitResult.Committed(next)
        }
        fault(InkFaultPoint.AFTER_TRANSACTION);return result
    }
    suspend fun importCopy(file:InkPageFile):Note=db.withTransaction {
        val id=UUID.randomUUID().toString();val at=System.currentTimeMillis();val title=(file.title.take(115)+" · 副本").take(120)
        db.notes().insertNote(NoteRow(id,1,title,file.text,at));db.notes().insertRevision(NoteRevisionRow(id,1,title,file.text,at))
        db.workspace().insert(WorkspaceRow(id,file.world,file.paper.ordinal,centerX=if(file.world)0.0 else 500.0,centerY=if(file.world)0.0 else 707.0))
        dao.insertPage(InkPageRow(id,file.strokes.size.toLong()))
        file.strokes.forEachIndexed{index,old->val s=InkStroke(UUID.randomUUID().toString(),old.pen,old.color,old.width,old.tool,old.samples,old.world);dao.insertStroke(InkStrokeRow(s.id,id,InkStrokeCodec.encode(s),s.samples.size,true,index.toLong()+1))}
        Note(id,1,title,file.text)
    }
}
