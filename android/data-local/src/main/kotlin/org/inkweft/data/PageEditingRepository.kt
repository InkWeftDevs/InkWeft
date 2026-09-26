// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="page_edit_receipts",indices=[Index("notebookId")],foreignKeys=[
    ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class PageEditReceiptRow(@PrimaryKey val commandId:String,val notebookId:String,val digest:String,
    val kind:String,val pageId:String,val resultPageId:String)
@Dao
interface PageEditingDao {
    @Query("SELECT * FROM page_edit_receipts WHERE commandId=:id") suspend fun receipt(id:String):PageEditReceiptRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(row:PageEditReceiptRow)
    @Query("UPDATE notebook_workspace SET revision=revision+1 WHERE noteId=:id") suspend fun touch(id:String):Int
}
enum class PageEditFault { BEFORE_RECEIPT, AFTER_TRANSACTION }

/** Page recycling is a reversible projection change, never a DELETE. Order,
 * selected page, new content (copy) and replay receipt share one transaction. */
class PageEditingRepository(private val db:NoteDatabase,private val fault:(PageEditFault)->Unit={}) {
    suspend fun apply(c:EditPage):EditPageResult {
        val result=db.withTransaction {
            val digest=c.digest()
            val old=db.pageEdits().receipt(c.commandId)
            if(old!=null){
                if(old.digest!=digest||old.notebookId!=c.notebookId)return@withTransaction EditPageResult.CommandReused
                check(db.pages().get(old.resultPageId)?.notebookId==c.notebookId)
                val active=db.pages().list(c.notebookId)
                val selected=db.workspace().get(c.notebookId)?.selectedPageId
                val current=active.firstOrNull{it.id==selected}?.id?:checkNotNull(active.firstOrNull()).id
                return@withTransaction EditPageResult.Applied(old.resultPageId,current,true)
            }
            val book=db.workspace().get(c.notebookId)?:return@withTransaction EditPageResult.Unavailable
            if(book.world||book.trashedAt!=null)return@withTransaction EditPageResult.Unavailable
            val all=db.pages().allPages(c.notebookId)
            val active=all.filter{it.trashedAt==null}
            check(active.isNotEmpty()&&active.withIndex().all{(i,p)->p.position==i&&!p.world})
            if(InsertPages.orderHash(active.map{it.id})!=c.expectedOrder)return@withTransaction EditPageResult.OrderChanged
            val source=all.firstOrNull{it.id==c.pageId}?:return@withTransaction EditPageResult.Unavailable
            if(source.trashedAt!=c.expectedTrashedAt)return@withTransaction EditPageResult.SourceChanged
            val head=db.ink().page(source.id)?.revision?:0
            if(head!=c.expectedInkRevision)return@withTransaction EditPageResult.SourceChanged
            if(active.none{it.id==c.stayOnPageId})return@withTransaction EditPageResult.Unavailable
            if(c.kind==PageEditKind.TRASH&&active.size==1)return@withTransaction EditPageResult.LastPage
            if(c.kind==PageEditKind.COPY&&all.size>=InsertPages.MAX_PAGES)return@withTransaction EditPageResult.CapacityReached
            val newId=c.newPageId
            if(newId!=null&&(db.pages().get(newId)!=null||db.notes().note(newId)!=null))return@withTransaction EditPageResult.CommandReused
            if(c.anchorPageId!=null&&active.none{it.id==c.anchorPageId})return@withTransaction EditPageResult.Unavailable
            val ordered=active.map{it.id}.toMutableList()
            var resultId=source.id
            var selected=c.stayOnPageId
            when(c.kind){
                PageEditKind.MOVE->{val at=c.targetIndex(ordered);ordered.remove(source.id);ordered.add(at,source.id)}
                PageEditKind.TRASH->{
                    ordered.remove(source.id)
                    check(db.pages().arrange(source.id,source.position,System.currentTimeMillis())==1)
                    if(selected==source.id)selected=ordered[minOf(source.position,ordered.lastIndex)]
                }
                PageEditKind.RESTORE->{ordered.add(c.targetIndex(ordered),source.id);selected=source.id}
                PageEditKind.COPY->{
                    val snapshot=InkRepository(db).read(source.id)
                    val visible=InkSession(snapshot).visibleDraft()
                    resultId=checkNotNull(c.newPageId)
                    val at=c.targetIndex(ordered)
                    db.pages().insert(NotebookPageRow(resultId,c.notebookId,at,false,source.paper,
                        source.centerX,source.centerY,source.zoom,createdAfterId=ordered.getOrNull(at-1)))
                    val cuts=mutableMapOf<String,String>()
                    db.ink().insertPage(InkPageRow(resultId,visible.size.toLong()))
                    visible.forEachIndexed{i,s->
                        val masks=s.cuts.map{cut->InkCut(cuts.getOrPut(cut.id){UUID.randomUUID().toString()},cut.radius,cut.points)}
                        val copied=InkStroke(UUID.randomUUID().toString(),s.pen,s.color,s.width,s.tool,s.samples,s.world,masks)
                        db.ink().insertStroke(InkStrokeRow(copied.id,resultId,InkStrokeCodec.encode(copied),copied.samples.size,true,i+1L))
                    }
                    db.pages().search(source.id)?.takeIf{it.inkRevision==head&&it.method=="MANUAL"}?.let{
                        db.pages().putSearch(PageSearchRow(resultId,visible.size.toLong(),it.text))
                    }
                    ordered.add(at,resultId);selected=resultId
                }
            }
            ordered.forEachIndexed{i,id->check(db.pages().arrange(id,i,null)==1)}
            check(db.workspace().selectPage(c.notebookId,selected)==1)
            check(db.pageEdits().touch(c.notebookId)==1)
            check(db.notes().touch(c.notebookId,System.currentTimeMillis())==1)
            fault(PageEditFault.BEFORE_RECEIPT)
            db.pageEdits().record(PageEditReceiptRow(c.commandId,c.notebookId,digest,c.kind.name,source.id,resultId))
            EditPageResult.Applied(resultId,selected)
        }
        fault(PageEditFault.AFTER_TRANSACTION)
        return result
    }
}
