// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.inkweft.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Entity(tableName="document_sources",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentSourceRow(@PrimaryKey val id:String,val notebookId:String,@ColumnInfo(name="digest") val sha256:String,val pageCount:Int,val byteCount:Int)
@Entity(tableName="document_chunks",primaryKeys=["documentId","position"],foreignKeys=[ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentChunkRow(val documentId:String,val position:Int,val payload:ByteArray)
@Entity(tableName="document_pages",indices=[Index("documentId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION),ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentPageRow(@PrimaryKey val pageId:String,val documentId:String,val sourcePage:Int)
data class OriginalStorageStatus(val pdfBytes:Long,val imageBytes:Long,val cacheBytes:Long,val freeBytes:Long)
@Dao interface DocumentDao {
    @Query("SELECT * FROM document_pages WHERE pageId=:id") suspend fun page(id:String):DocumentPageRow?
    @Query("SELECT * FROM document_sources WHERE id=:id") suspend fun source(id:String):DocumentSourceRow?
    @Query("SELECT * FROM document_sources WHERE notebookId=:book AND digest=:hash LIMIT 1") suspend fun matching(book:String,hash:String):DocumentSourceRow?
    @Query("SELECT * FROM document_chunks WHERE documentId=:id ORDER BY position") suspend fun chunks(id:String):List<DocumentChunkRow>
    @Query("SELECT COUNT(*) FROM document_chunks WHERE documentId=:id") suspend fun chunkCount(id:String):Int
    @Query("SELECT COALESCE(SUM(byteCount),0) FROM document_sources") suspend fun totalBytes():Long
    @Insert suspend fun insertSource(row:DocumentSourceRow)
    @Insert suspend fun insertChunk(row:DocumentChunkRow)
    @Insert suspend fun insertPage(row:DocumentPageRow)
}

/** Chunked owned bytes stay inside backup transactions and below Android's CursorWindow limit. */
class DocumentRepository(private val db:NoteDatabase) {
    suspend fun storageStatus()=OriginalStorageStatus(db.documents().totalBytes(),db.images().totalBytes(),db.originalFiles.diskBytes(),checkNotNull(db.documentScratch).usableSpace)
    suspend fun read(pageId:String,cache:MutableMap<String,PdfDocumentSource> = mutableMapOf()):PdfPageSource? {
        val ref=db.documents().page(pageId)?:return null
        val owner=checkNotNull(db.pages().get(pageId));val metadata=checkNotNull(db.documents().source(ref.documentId))
        require(!owner.world&&metadata.notebookId==owner.notebookId)
        val document=cache[metadata.id]?:run {
            require(metadata.byteCount in 8..PdfDocumentSource.MAX_BYTES)
            require(db.documents().chunkCount(metadata.id)==(metadata.byteCount+CHUNK-1)/CHUNK){"DOCUMENT_CHUNKS_INVALID"}
            val content=OriginalBytes.stream(metadata.byteCount,metadata.sha256){OriginalChunkInput(metadata.byteCount,CHUNK){position->
                db.openHelper.readableDatabase.query("SELECT payload FROM document_chunks WHERE documentId=? AND position=?",arrayOf<Any>(metadata.id,position)).use{c->if(c.moveToFirst())c.getBlob(0)else null}
            }}
            PdfDocumentSource.fromStream(content,metadata.pageCount).also{cache[metadata.id]=it}
        }
        return PdfPageSource(document,ref.sourcePage)
    }
    suspend fun openFile(source:PdfDocumentSource):OriginalFileCache.Lease=
        db.originalFiles.acquire(source.size,source.sha256){out,active->source.copyTo(out,active)}
    fun trimFiles()=db.originalFiles.trim()
    internal suspend fun attach(pageId:String,source:PdfPageSource?) {
        if(source==null)return
        val page=checkNotNull(db.pages().get(pageId));require(!page.world&&db.documents().page(pageId)==null)
        val doc=source.document
        val stored=db.documents().matching(page.notebookId,doc.sha256)?:run {
            val context=currentCoroutineContext()
            require(checkNotNull(db.documentScratch).usableSpace>=doc.size.toLong()*2+32L*1024*1024){"ORIGINAL_LOW_SPACE"}
            validatePdf(doc,checkNotNull(db.documentScratch)){context.ensureActive()}
            val id=UUID.randomUUID().toString();val row=DocumentSourceRow(id,page.notebookId,doc.sha256,doc.pages,doc.size)
            db.documents().insertSource(row)
            storeOriginal(doc.size,doc.sha256,CHUNK,doc::openStream){index,bytes->db.documents().insertChunk(DocumentChunkRow(id,index,bytes))};row
        }
        require(stored.pageCount==doc.pages)
        db.documents().insertPage(DocumentPageRow(pageId,stored.id,source.page))
    }
    companion object {
        const val CHUNK=512_000
        fun validatePdf(source:PdfDocumentSource,directory:File,checkActive:()->Unit={}) {
            val file=File.createTempFile("inkweft-validate-",".pdf",directory)
            try{file.outputStream().use{source.copyTo(it,checkActive)};ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
                require(renderer.pageCount==source.pages)
                repeat(renderer.pageCount){i->checkActive();renderer.openPage(i).use{p->require(p.width>0&&p.height>0)}}
            }}}finally{file.delete()}
        }
    }
}
