// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.inkweft.core.*
import java.io.File
import java.util.UUID

@Entity(tableName="document_sources",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentSourceRow(@PrimaryKey val id:String,val notebookId:String,@ColumnInfo(name="digest") val sha256:String,val pageCount:Int,val byteCount:Int)
@Entity(tableName="document_chunks",primaryKeys=["documentId","position"],foreignKeys=[ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentChunkRow(val documentId:String,val position:Int,val payload:ByteArray)
@Entity(tableName="document_pages",indices=[Index("documentId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION),ForeignKey(entity=DocumentSourceRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.NO_ACTION)])
data class DocumentPageRow(@PrimaryKey val pageId:String,val documentId:String,val sourcePage:Int)
@Dao interface DocumentDao {
    @Query("SELECT * FROM document_pages WHERE pageId=:id") suspend fun page(id:String):DocumentPageRow?
    @Query("SELECT * FROM document_sources WHERE id=:id") suspend fun source(id:String):DocumentSourceRow?
    @Query("SELECT * FROM document_sources WHERE notebookId=:book AND digest=:hash LIMIT 1") suspend fun matching(book:String,hash:String):DocumentSourceRow?
    @Query("SELECT * FROM document_chunks WHERE documentId=:id ORDER BY position") suspend fun chunks(id:String):List<DocumentChunkRow>
    @Query("SELECT COALESCE(SUM(byteCount),0) FROM document_sources") suspend fun totalBytes():Long
    @Insert suspend fun insertSource(row:DocumentSourceRow)
    @Insert suspend fun insertChunk(row:DocumentChunkRow)
    @Insert suspend fun insertPage(row:DocumentPageRow)
}

/** Chunked owned bytes stay inside backup transactions and below Android's CursorWindow limit. */
class DocumentRepository(private val db:NoteDatabase) {
    suspend fun read(pageId:String,cache:MutableMap<String,PdfDocumentSource> = mutableMapOf()):PdfPageSource? {
        val ref=db.documents().page(pageId)?:return null
        val owner=checkNotNull(db.pages().get(pageId));val metadata=checkNotNull(db.documents().source(ref.documentId))
        require(!owner.world&&metadata.notebookId==owner.notebookId)
        val document=cache[metadata.id]?:run {
            require(metadata.byteCount in 8..PdfDocumentSource.MAX_BYTES)
            val chunks=db.documents().chunks(metadata.id);require(chunks.size==(metadata.byteCount+CHUNK-1)/CHUNK)
            val bytes=ByteArray(metadata.byteCount);var offset=0
            chunks.forEachIndexed{i,c->require(c.position==i&&c.payload.size==minOf(CHUNK,bytes.size-offset));c.payload.copyInto(bytes,offset);offset+=c.payload.size}
            PdfDocumentSource(bytes,metadata.pageCount).also{require(it.sha256==metadata.sha256);cache[metadata.id]=it}
        }
        return PdfPageSource(document,ref.sourcePage)
    }
    internal suspend fun attach(pageId:String,source:PdfPageSource?) {
        if(source==null)return
        val page=checkNotNull(db.pages().get(pageId));require(!page.world&&db.documents().page(pageId)==null)
        val doc=source.document
        val stored=db.documents().matching(page.notebookId,doc.sha256)?:run {
            require(db.documents().totalBytes()+doc.size<=80_000_000){"DOCUMENT_LIBRARY_BUDGET"}
            validatePdf(doc,checkNotNull(db.documentScratch))
            val id=UUID.randomUUID().toString();val row=DocumentSourceRow(id,page.notebookId,doc.sha256,doc.pages,doc.size)
            db.documents().insertSource(row)
            val bytes=doc.bytes();var offset=0;var index=0
            while(offset<bytes.size){val end=minOf(bytes.size,offset+CHUNK);db.documents().insertChunk(DocumentChunkRow(id,index++,bytes.copyOfRange(offset,end)));offset=end};row
        }
        require(stored.pageCount==doc.pages)
        db.documents().insertPage(DocumentPageRow(pageId,stored.id,source.page))
    }
    companion object {
        const val CHUNK=512_000
        fun validatePdf(source:PdfDocumentSource,directory:File) {
            val file=File.createTempFile("inkweft-validate-",".pdf",directory)
            try{file.writeBytes(source.bytes());ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
                require(renderer.pageCount==source.pages)
                repeat(renderer.pageCount){i->renderer.openPage(i).use{p->require(p.width>0&&p.height>0)}}
            }}}finally{file.delete()}
        }
    }
}
