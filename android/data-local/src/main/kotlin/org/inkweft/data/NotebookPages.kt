// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.util.UUID

/** Book identity and page identity are separate. Existing single pages keep their
 * original UUID as page 1, so original ink payloads need no rewrite. */
@Entity(tableName="notebook_pages",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class NotebookPageRow(@PrimaryKey val id:String,val notebookId:String,val position:Int,val world:Boolean,val paper:Int,
    val centerX:Double=500.0,val centerY:Double=707.0,val zoom:Double=0.0,val createdAfterId:String?=null,
    @ColumnInfo(defaultValue="NULL") val trashedAt:Long?=null)
@Entity(tableName="page_search_text",foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class PageSearchRow(@PrimaryKey val pageId:String,val inkRevision:Long,val text:String,val method:String="MANUAL")
data class PageSearchHit(val notebookId:String,val pageId:String,val position:Int,val text:String)
@Dao
interface NotebookPageDao {
    @Query("SELECT * FROM notebook_pages WHERE notebookId=:id ORDER BY position,id") suspend fun allPages(id:String):List<NotebookPageRow>
    @Query("SELECT * FROM notebook_pages WHERE notebookId=:id ORDER BY position,id") fun observeAll(id:String):Flow<List<NotebookPageRow>>
    @Query("UPDATE notebook_pages SET position=:position,trashedAt=:trashedAt WHERE id=:id") suspend fun arrange(id:String,position:Int,trashedAt:Long?):Int
    @Query("SELECT * FROM notebook_pages WHERE notebookId=:id AND trashedAt IS NULL ORDER BY position,id") fun observe(id:String):Flow<List<NotebookPageRow>>
    @Query("SELECT * FROM notebook_pages WHERE notebookId=:id AND trashedAt IS NULL ORDER BY position,id") suspend fun list(id:String):List<NotebookPageRow>
    @Query("SELECT * FROM notebook_pages WHERE id=:id") suspend fun get(id:String):NotebookPageRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:NotebookPageRow)
    @Query("UPDATE notebook_pages SET position=position+1 WHERE notebookId=:id AND trashedAt IS NULL AND position>:after") suspend fun shiftAfter(id:String,after:Int)
    @Query("UPDATE notebook_pages SET centerX=:x,centerY=:y,zoom=:zoom WHERE id=:id") suspend fun viewport(id:String,x:Double,y:Double,zoom:Double):Int
    @Query("UPDATE notebook_pages SET paper=:paper WHERE id=:id") suspend fun paper(id:String,paper:Int):Int
    @Query("SELECT * FROM page_search_text WHERE pageId=:id") suspend fun search(id:String):PageSearchRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSearch(row:PageSearchRow)
    @Query("SELECT p.notebookId AS notebookId,p.id AS pageId,p.position AS position,s.text AS text FROM page_search_text s JOIN notebook_pages p ON p.id=s.pageId LEFT JOIN ink_pages h ON h.noteId=p.id WHERE p.trashedAt IS NULL AND s.inkRevision=COALESCE(h.revision,0)")
    fun observeSearch():Flow<List<PageSearchHit>>
}
class NotebookPages(private val db:NoteDatabase) {
    suspend fun insert(command:InsertPages):InsertPagesResult = PageInsertionRepository(db).insert(command)
    fun observe(id:String)=db.pages().observe(id)
    suspend fun activePages(id:String)=db.pages().list(id)
    fun observeAll(id:String)=db.pages().observeAll(id)
    suspend fun edit(command:EditPage):EditPageResult=PageEditingRepository(db).apply(command)
    suspend fun inkRevision(id:String):Long=db.ink().page(id)?.revision?:0
    fun observeSearch()=db.pages().observeSearch()
    suspend fun ensureFirst(notebookId:String):NotebookPageRow=db.withTransaction {
        checkNotNull(db.notes().note(notebookId))
        db.pages().get(notebookId)?:run {
            val w=db.workspace().get(notebookId)?:WorkspaceRow(notebookId).also{db.workspace().insert(it)}
            NotebookPageRow(notebookId,notebookId,0,w.world,w.paper,w.centerX,w.centerY,w.zoom).also{db.pages().insert(it)}
        }
    }
    /** newId is generated once by the UI and reused if the commit outcome is unknown. */
    suspend fun addAfter(notebookId:String,afterId:String,newId:String):NotebookPageRow=db.withTransaction {
        UUID.fromString(newId);ensureFirst(notebookId)
        val after=checkNotNull(db.pages().get(afterId));require(after.notebookId==notebookId&&!after.world&&after.trashedAt==null)
        val old=db.pages().get(newId)
        if(old!=null){require(old.notebookId==notebookId&&old.createdAfterId==afterId);return@withTransaction old}
        val book=checkNotNull(db.workspace().get(notebookId));check(book.trashedAt==null)
        require(db.pages().allPages(notebookId).size<500){"Page budget reached"}
        db.pages().shiftAfter(notebookId,after.position)
        val next=NotebookPageRow(newId,notebookId,after.position+1,false,after.paper,createdAfterId=afterId)
        db.pages().insert(next);db.notes().touch(notebookId,System.currentTimeMillis());next
    }
    suspend fun select(notebookId:String,pageId:String)=db.withTransaction {
        val p=checkNotNull(db.pages().get(pageId));require(p.notebookId==notebookId&&p.trashedAt==null)
        check(db.workspace().selectPage(notebookId,pageId)==1)
    }
    suspend fun searchText(pageId:String):PageSearchRow?=db.pages().search(pageId)
    /** Explicit transcription, not an OCR claim. It becomes non-searchable after
     * ANY ink revision change, including erase/undo, until the user updates it. */
    suspend fun saveSearchText(pageId:String,expectedInkRevision:Long,text:String):Boolean=db.withTransaction {
        require(db.pages().get(pageId)?.trashedAt==null && db.pages().get(pageId)!=null);require(text.length<=20_000)
        if((db.ink().page(pageId)?.revision?:0)!=expectedInkRevision)return@withTransaction false
        db.pages().putSearch(PageSearchRow(pageId,expectedInkRevision,text));true
    }
    suspend fun exportBook(notebookId:String):NotebookFile=db.withTransaction {
        val n=checkNotNull(db.notes().note(notebookId));ensureFirst(notebookId)
        val pages=db.pages().list(notebookId)
        var bytes=0L
        val copies=pages.map{p->InkPageFile(n.title,"",InkSession(InkRepository(db).read(p.id)).visibleDraft(),p.world,PaperStyle.entries[p.paper]).also{bytes+=it.encode().size;require(bytes<NotebookFile.MAX_BYTES-500_000)}}
        NotebookFile(n.title,n.text,copies)
    }
    suspend fun importBook(file:NotebookFile):Note=db.withTransaction {
        val first=file.pages.first();val n=WorkspaceRepository(db).create(file.title,false,first.paper)
        if(file.text.isNotEmpty())check(NoteRepository(db).save(SaveNote(UUID.randomUUID().toString(),n.id,1,n.title,file.text)) is SaveResult.Committed)
        file.pages.forEachIndexed{index,p->
            val id=if(index==0)n.id else UUID.randomUUID().toString()
            if(index>0)db.pages().insert(NotebookPageRow(id,n.id,index,false,p.paper.ordinal))
            InkRepository(db).populateImportedPage(id,p)
        }
        checkNotNull(NoteRepository(db).read(n.id))
    }
}
