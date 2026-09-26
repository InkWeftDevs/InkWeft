// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="library_content_receipts", indices=[Index("noteId")],
    foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class LibraryContentReceipt(@PrimaryKey val commandId:String,val kind:String,
    val digest:String,val noteId:String)
@Dao
interface LibraryContentDao {
    @Query("SELECT * FROM library_content_receipts WHERE commandId=:id")
    suspend fun receipt(id:String):LibraryContentReceipt?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:LibraryContentReceipt)
}
enum class LibraryContentFault { BEFORE_RECEIPT, AFTER_TRANSACTION }
data class ContentExport(val title:String,val extension:String,val bytes:ByteArray)

/** All transfer writes live in one transaction, with a durable replay receipt.
 * Copies deliberately exclude hidden/history data. This is NOT vault backup. */
class LibraryContentRepository(private val db:NoteDatabase,
    private val fault:(LibraryContentFault)->Unit={}) {
    private suspend fun replay(id:String,kind:String,digest:String):Note? {
        val old=db.libraryContent().receipt(id)?:return null
        require(old.kind==kind && old.digest==digest){"COMMAND_ID_REUSE"}
        return checkNotNull(NoteRepository(db).read(old.noteId)){"RECEIPT_TARGET_MISSING"}
    }
    suspend fun lookup(id:String,kind:String,digest:String):Note?=db.withTransaction{replay(id,kind,digest)}

    suspend fun duplicate(command:CopyNotebook):Note {
        val result=db.withTransaction {
            replay(command.commandId,"COPY",command.digest())?.let{return@withTransaction it}
            val source=checkNotNull(db.notes().note(command.sourceId)){"SOURCE_MISSING"}
            val metadata=WorkspaceRepository(db).get(command.sourceId)
            require(metadata.trashedAt==null){"SOURCE_IN_TRASH"}
            NotebookPages(db).ensureFirst(source.id)
            val pages=db.pages().list(source.id)
            validatePages(pages,metadata.world)
            require(db.notes().note(command.destinationId)==null){"DESTINATION_EXISTS"}
            val title=(source.title.take(115)+" · 副本").take(120)
            val target=create(command.destinationId,title,source.text,metadata.world,pages.first().paper,
                metadata.copy(coverKey=NotebookCover.fromKey(metadata.coverKey).resolved(source.id).key))
            db.covers().get(source.id)?.let{require(db.covers().otherBytes(target.id)+it.payload.size<=32_000_000){"COVER_LIBRARY_BUDGET"};db.covers().put(it.copy(noteId=target.id))}
            var encodedBytes=0L
            pages.forEachIndexed { index,page ->
                val state=InkRepository(db).read(page.id)
                val visible=InkSession(state).visibleDraft()
                val copy=InkPageFile(title,"",visible,page.world,PaperStyle.entries[page.paper],PageObjectRepository(db).read(page.id).objects)
                encodedBytes+=copy.encode().size
                require(encodedBytes<NotebookFile.MAX_BYTES-500_000){"COPY_SIZE_LIMIT"}
                val pageId=if(index==0)target.id else UUID.randomUUID().toString()
                if(index>0)db.pages().insert(NotebookPageRow(pageId,target.id,index,page.world,page.paper))
                populate(pageId,copy)
                // Only currently valid manual transcription travels to the new revision.
                db.pages().search(page.id)?.takeIf{it.inkRevision==state.revision&&it.method=="MANUAL"}?.let{
                    db.pages().putSearch(PageSearchRow(pageId,copy.strokes.size.toLong(),it.text))
                }
            }
            fault(LibraryContentFault.BEFORE_RECEIPT)
            db.libraryContent().insert(LibraryContentReceipt(command.commandId,"COPY",command.digest(),target.id))
            target
        }
        fault(LibraryContentFault.AFTER_TRANSACTION)
        return result
    }

    suspend fun import(command:ImportNotebook,prepared:ContentTransfer.Prepared):Note {
        require(command.contentSha256==prepared.sha256){"IMPORT_CONTENT_CHANGED"}
        val result=db.withTransaction {
            replay(command.commandId,"IMPORT",command.digest())?.let{return@withTransaction it}
            require(db.notes().note(command.destinationId)==null){"DESTINATION_EXISTS"}
            val title=prepared.content.title
            val text:String
            val pages:List<InkPageFile>
            when(val content=prepared.content){
                is ContentTransfer.Content.Page->{text=content.value.text;pages=listOf(content.value)}
                is ContentTransfer.Content.Book->{text=content.value.text;pages=content.value.pages}
            }
            val first=pages.first()
            val target=create(command.destinationId,title,text,first.world,first.paper.ordinal)
            pages.forEachIndexed{index,page->
                val pageId=if(index==0)target.id else UUID.randomUUID().toString()
                if(index>0)db.pages().insert(NotebookPageRow(pageId,target.id,index,page.world,page.paper.ordinal))
                populate(pageId,page)
            }
            fault(LibraryContentFault.BEFORE_RECEIPT)
            db.libraryContent().insert(LibraryContentReceipt(command.commandId,"IMPORT",command.digest(),target.id))
            target
        }
        fault(LibraryContentFault.AFTER_TRANSACTION)
        return result
    }

    /** One consistent saved snapshot, prepared before the system destination picker. */
    suspend fun export(notebookId:String):ContentExport=db.withTransaction {
        val note=checkNotNull(db.notes().note(notebookId))
        val metadata=WorkspaceRepository(db).get(notebookId)
        require(metadata.trashedAt==null){"SOURCE_IN_TRASH"}
        val rows=db.pages().list(notebookId)
        validatePages(rows,metadata.world)
        if(metadata.world){
            val p=rows.single()
            val file=InkPageFile(note.title,note.text,InkSession(InkRepository(db).read(p.id)).visibleDraft(),
                true,PaperStyle.entries[p.paper],PageObjectRepository(db).read(p.id).objects)
            ContentExport(note.title,"iwpage",file.encode())
        }else ContentExport(note.title,"iwbook",NotebookPages(db).exportBook(notebookId).encode())
    }
    private fun validatePages(rows:List<NotebookPageRow>,world:Boolean){
        require(rows.size in 1..500 && rows.withIndex().all{(index,p)->p.position==index&&p.world==world}){"PAGE_ORDER_INVALID"}
        require(!world||rows.size==1){"WORLD_NOTE_HAS_MULTIPLE_PAGES"}
    }
    private suspend fun create(id:String,title:String,text:String,world:Boolean,paper:Int,metadata:WorkspaceRow?=null):Note {
        require(RenameNote.validTitle(title) && text.length<=100_000)
        val at=System.currentTimeMillis()
        db.notes().insertNote(NoteRow(id,1,title,text,at))
        db.notes().insertRevision(NoteRevisionRow(id,1,title,text,at))
        db.workspace().insert(WorkspaceRow(id,world,paper,folder=metadata?.folder.orEmpty(),tags=metadata?.tags.orEmpty(),
            coverKey=metadata?.coverKey?:NotebookCover.AUTO.key,selectedPageId=id,
            centerX=if(world)0.0 else 500.0,centerY=if(world)0.0 else 707.0))
        db.pages().insert(NotebookPageRow(id,id,0,world,paper,centerX=if(world)0.0 else 500.0,centerY=if(world)0.0 else 707.0))
        return Note(id,1,title,text)
    }
    private suspend fun populate(pageId:String,page:InkPageFile){
        require(db.ink().page(pageId)==null)
        PageObjectRepository(db).import(pageId,page.objects)
        val cuts=mutableMapOf<String,String>()
        db.ink().insertPage(InkPageRow(pageId,page.strokes.size.toLong()))
        page.strokes.forEachIndexed{index,old->
            val masks=old.cuts.map{c->InkCut(cuts.getOrPut(c.id){UUID.randomUUID().toString()},c.radius,c.points,c.shape)}
            val stroke=InkStroke(UUID.randomUUID().toString(),old.pen,old.color,old.width,old.tool,old.samples,old.world,masks)
            db.ink().insertStroke(InkStrokeRow(stroke.id,pageId,InkStrokeCodec.encode(stroke),stroke.samples.size,true,index.toLong()+1))
        }
    }
}
