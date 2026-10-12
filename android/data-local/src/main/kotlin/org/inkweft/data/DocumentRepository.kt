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

@Entity(tableName="document_sources",indices=[Index("notebookId"),Index("digest")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentSourceRow(@PrimaryKey val id:String,val notebookId:String,@ColumnInfo(name="digest") val sha256:String,val pageCount:Int,val byteCount:Int)
@Entity(tableName="document_chunks",primaryKeys=["documentId","position"],foreignKeys=[ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentChunkRow(val documentId:String,val position:Int,val payload:ByteArray)
@Entity(tableName="document_pages",indices=[Index("documentId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION),ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentPageRow(@PrimaryKey val pageId:String,val documentId:String,val sourcePage:Int)
data class OriginalStorageStatus(val pdfBytes:Long,val imageBytes:Long,val cacheBytes:Long,val freeBytes:Long,
    val originalFileBytes:Long=0,val databaseBytes:Long=0)
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

/** Metadata is transactional; immutable originals are durable before their references commit. */
class DocumentRepository(private val db:NoteDatabase) {
    suspend fun storageStatus():OriginalStorageStatus {
        val path=db.openHelper.readableDatabase.path
        return OriginalStorageStatus(db.documents().totalBytes(),db.images().totalBytes(),db.originalFiles.diskBytes(),checkNotNull(db.documentScratch).usableSpace,
            db.originalStore.diskBytes(),File(path).length()+File(path+"-wal").length()+File(path+"-shm").length())
    }
    suspend fun read(pageId:String,cache:MutableMap<String,PdfDocumentSource> = mutableMapOf()):PdfPageSource? {
        val ref=db.documents().page(pageId)?:return null
        val owner=checkNotNull(db.pages().get(pageId));val metadata=checkNotNull(db.documents().source(ref.documentId))
        require(!owner.world&&metadata.notebookId==owner.notebookId)
        val document=cache[metadata.id]?:run {
            require(metadata.byteCount in 8..PdfDocumentSource.MAX_BYTES)
            val content=OriginalStorage.read(db,metadata.byteCount,metadata.sha256,"document_chunks","documentId=?",arrayOf(metadata.id))
            PdfDocumentSource.fromStream(content,metadata.pageCount).also{cache[metadata.id]=it}
        }
        return PdfPageSource(document,ref.sourcePage)
    }
    suspend fun openFile(source:PdfDocumentSource):OriginalFileCache.Lease {
        val context=currentCoroutineContext()
        return if(db.originalStore.contains(source.sha256))db.originalStore.acquire(source.size,source.sha256){context.ensureActive()}
        else {OriginalStorage.requireCacheFallback(db,source.sha256);db.originalFiles.acquire(source.size,source.sha256){out,active->source.copyTo(out,active)}}
    }
    fun trimFiles()=db.originalFiles.trim()
    internal suspend fun attach(pageId:String,source:PdfPageSource?,validated:ValidatedImportOriginals?=null) {
        if(source==null)return
        val page=checkNotNull(db.pages().get(pageId));require(!page.world&&db.documents().page(pageId)==null)
        val doc=source.document
        val stored=db.documents().matching(page.notebookId,doc.sha256)?:run {
            val context=currentCoroutineContext()
            db.originalStore.publish(doc.size,doc.sha256,{context.ensureActive()},
                {file->if(validated?.contains(doc)!=true)validatePdfFile(file,doc.pages){context.ensureActive()}}){out->doc.copyTo(out){context.ensureActive()}}
            val id=UUID.randomUUID().toString();val row=DocumentSourceRow(id,page.notebookId,doc.sha256,doc.pages,doc.size)
            db.documents().insertSource(row)
            row
        }
        require(stored.pageCount==doc.pages)
        db.documents().insertPage(DocumentPageRow(pageId,stored.id,source.page))
    }
    companion object {
        const val CHUNK=512_000
        fun validatePdf(source:PdfDocumentSource,directory:File,checkActive:()->Unit={}) {
            val file=File.createTempFile("inkweft-validate-",".pdf",directory)
            try{file.outputStream().use{source.copyTo(it,checkActive)};validatePdfFile(file,source.pages,checkActive)}finally{file.delete()}
        }
        internal fun validatePdfFile(file:File,pages:Int,checkActive:()->Unit={}) {
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
                require(renderer.pageCount==pages)
                repeat(renderer.pageCount){i->checkActive();renderer.openPage(i).use{p->require(p.width>0&&p.height>0)}}
            }}
        }
    }
}
