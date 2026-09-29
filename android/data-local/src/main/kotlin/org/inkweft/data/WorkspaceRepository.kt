// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.util.UUID

/** Shelf appearance is stored with organization; never encoded into pen data. */
@Entity(tableName="notebook_workspace",foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class WorkspaceRow(@PrimaryKey val noteId:String,val world:Boolean=false,val paper:Int=1,
    val folder:String="",val tags:String="",val favorite:Boolean=false,val trashedAt:Long?=null,
    val centerX:Double=500.0,val centerY:Double=707.0,val zoom:Double=0.0,val revision:Long=0,
    @ColumnInfo(defaultValue="'auto'") val coverKey:String="auto",
    @ColumnInfo(defaultValue="''") val selectedPageId:String="",
    @ColumnInfo(defaultValue="0") val pinned:Boolean=false)

data class LibraryInkCount(val noteId:String,val visibleCount:Int,val modifiedRevision:Long)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM notebook_workspace") fun observe():Flow<List<WorkspaceRow>>
    @Query("SELECT * FROM notebook_workspace WHERE noteId=:id") suspend fun get(id:String):WorkspaceRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:WorkspaceRow)
    @Update suspend fun update(row:WorkspaceRow):Int
    @Query("UPDATE notebook_workspace SET centerX=:x,centerY=:y,zoom=:zoom WHERE noteId=:id")
    suspend fun viewport(id:String,x:Double,y:Double,zoom:Double):Int
    @Query("UPDATE notebook_workspace SET selectedPageId=:pageId WHERE noteId=:id") suspend fun selectPage(id:String,pageId:String):Int
    @Query("SELECT n.id AS noteId,COALESCE((SELECT COUNT(*) FROM notebook_pages p JOIN ink_strokes s ON s.noteId=p.id WHERE p.notebookId=n.id AND p.trashedAt IS NULL AND s.visible=1),0) AS visibleCount,COALESCE((SELECT SUM(h.revision) FROM notebook_pages p JOIN ink_pages h ON h.noteId=p.id WHERE p.notebookId=n.id),0) AS modifiedRevision FROM notes n")
    fun observeInkCounts():Flow<List<LibraryInkCount>>
    @Query("SELECT updatedAt FROM notes WHERE id=:id") suspend fun updatedAt(id:String):Long?
}

class WorkspaceRepository(private val db:NoteDatabase) {
    fun observe()=db.workspace().observe()
    fun observeInkCounts()=db.workspace().observeInkCounts()
    suspend fun get(id:String):WorkspaceRow=db.withTransaction {
        checkNotNull(db.notes().note(id))
        db.workspace().get(id)?:WorkspaceRow(id).also { db.workspace().insert(it) }
    }
    suspend fun create(title:String,world:Boolean,paper:PaperStyle,cover:NotebookCover=NotebookCover.AUTO,operationId:String?=null,customCover:ByteArray?=null):Note=db.withTransaction {
        val coverBytes=customCover?.copyOf();require((cover==NotebookCover.CUSTOM)==(coverBytes!=null));coverBytes?.let(::validateCoverPayload)
        val clean=title.trim();require(RenameNote.validTitle(clean))
        operationId?.let{UUID.fromString(it)}
        val digest=ContentTransfer.hash((listOf("create.v1",clean,world.toString(),paper.name,cover.key)+if(coverBytes==null)emptyList()else listOf(ContentTransfer.hash(coverBytes))).joinToString("\u0000").toByteArray())
        operationId?.let{op->db.libraryContent().receipt(op)?.let{r->require(r.kind=="CREATE"&&r.digest==digest);return@withTransaction checkNotNull(NoteRepository(db).read(r.noteId))}}
        val id=operationId?:UUID.randomUUID().toString();val at=System.currentTimeMillis()
        db.notes().insertNote(NoteRow(id,1,clean,"",at));db.notes().insertRevision(NoteRevisionRow(id,1,clean,"",at))
        db.workspace().insert(WorkspaceRow(id,world,paper.ordinal,centerX=if(world)0.0 else 500.0,centerY=if(world)0.0 else 707.0,coverKey=cover.key))
        coverBytes?.let{require(db.covers().otherBytes(id)+it.size<=32_000_000){"COVER_LIBRARY_BUDGET"};db.covers().put(NotebookCoverRow(id,it))}
        db.pages().insert(NotebookPageRow(id,id,0,world,paper.ordinal,centerX=if(world)0.0 else 500.0,centerY=if(world)0.0 else 707.0))
        if(operationId!=null)db.libraryContent().insert(LibraryContentReceipt(operationId,"CREATE",digest,id))
        Note(id,1,clean,"")
    }
    suspend fun organize(id:String,expected:Long,folder:String,tags:String,favorite:Boolean,trash:Boolean):Boolean=db.withTransaction {
        checkNotNull(db.notes().note(id))
        val row=db.workspace().get(id)?:WorkspaceRow(id).also { db.workspace().insert(it) }
        if(row.revision!=expected)return@withTransaction false
        val cleanFolder=folder.trim();require(cleanFolder.length<=48 && !cleanFolder.contains('\n'))
        val cleanTags=tags.split(',', '，','\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleanTags.size<=12 && cleanTags.all { it.length<=24 })
        check(db.workspace().update(row.copy(folder=cleanFolder,tags=cleanTags.joinToString("\n"),favorite=favorite,
            trashedAt=if(trash)row.trashedAt?:System.currentTimeMillis() else null,revision=row.revision+1))==1)
        true
    }
    /** Desired-state assignment; same cover is idempotent, stale different choice conflicts. */
    suspend fun customCover(id:String):ByteArray?=db.covers().get(id)?.payload?.also(::validateCoverPayload)
    suspend fun changeCover(id:String,expected:Long,cover:NotebookCover,custom:ByteArray?=null):Boolean=db.withTransaction {
        val bytes=custom?.copyOf();require((cover==NotebookCover.CUSTOM)==(bytes!=null));bytes?.let(::validateCoverPayload)
        val row=get(id)
        if(row.trashedAt!=null)return@withTransaction false
        if(row.coverKey==cover.key&&(bytes==null||db.covers().get(id)?.payload?.contentEquals(bytes)==true))return@withTransaction true
        if(row.revision!=expected)return@withTransaction false
        bytes?.let{require(db.covers().otherBytes(id)+it.size<=32_000_000){"COVER_LIBRARY_BUDGET"};db.covers().put(NotebookCoverRow(id,it))}
        check(db.workspace().update(row.copy(coverKey=cover.key,revision=row.revision+1))==1)
        true
    }
    /** Desired state, not a toggle: a repeated save cannot unpin a pinned note. */
    suspend fun setPinned(id:String,expected:Long,pinned:Boolean):Boolean=db.withTransaction {
        val row=get(id)
        if(row.trashedAt!=null)return@withTransaction false
        if(row.pinned==pinned)return@withTransaction true
        if(row.revision!=expected)return@withTransaction false
        check(db.workspace().update(row.copy(pinned=pinned,revision=row.revision+1))==1)
        true
    }
    suspend fun changePaper(id:String,paper:PaperStyle)=db.withTransaction {
        val page=db.pages().get(id)?:NotebookPages(db).ensureFirst(id)
        require(page.trashedAt==null)
        check(db.pages().paper(id,paper.ordinal)==1)
        if(id==page.notebookId){val row=get(id);check(db.workspace().update(row.copy(paper=paper.ordinal,revision=row.revision+1))==1)}
    }
    suspend fun saveViewport(id:String,v:CanvasViewport)=db.withTransaction {
        val page=db.pages().get(id)?:NotebookPages(db).ensureFirst(id)
        if(page.trashedAt!=null)return@withTransaction
        check(db.pages().viewport(id,v.centerX,v.centerY,v.zoom)==1)
        if(id==page.notebookId){get(id);check(db.workspace().viewport(id,v.centerX,v.centerY,v.zoom)==1)}
    }
    suspend fun modifiedAt(id:String)=db.workspace().updatedAt(id)?:0L
}
