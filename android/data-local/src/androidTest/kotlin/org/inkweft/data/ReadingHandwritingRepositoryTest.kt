package org.inkweft.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class ReadingHandwritingRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{val name="reading-${id()}.db";val db=NoteDatabase.open(context,name);try{block(db)}finally{db.close();context.deleteDatabase(name)}}
    private fun pdf():PdfDocumentSource{val out=ByteArrayOutputStream();val d=PdfDocument();try{repeat(2){i->val p=d.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create());p.canvas.drawColor(if(i==0)android.graphics.Color.WHITE else android.graphics.Color.YELLOW);d.finishPage(p)};d.writeTo(out)}finally{d.close()};return PdfDocumentSource(out.toByteArray(),2)}
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff111111.toInt(),3f,InkTool.STYLUS,listOf(InkSample(30f,50f,0),InkSample(70f,60f,20)))
    @Test fun pdfCopyExportBackupAndRestoreRetainOwnedBytes()=fixture{db->
        val source=pdf();val book=NotebookFile("文档","",List(2){InkPageFile("文档","",listOf(stroke()),source=PdfPageSource(source,it))})
        val original=NotebookPages(db).importBook(book)
        assertEquals(source.size.toLong(),db.documents().totalBytes())
        val duplicate=LibraryContentRepository(db).duplicate(CopyNotebook(id(),original.id,id()))
        assertEquals(source.sha256,DocumentRepository(db).read(duplicate.id)!!.document.sha256)
        val exported=NotebookFile.decode(NotebookPages(db).exportBook(original.id).encode())
        assertEquals(1,exported.pages[1].source!!.page)
        val name="restore-reading-${id()}.db";val target=NoteDatabase.open(context,name)
        try{LibraryBackupRepository(context,db).snapshot().use{snapshot->val restore=LibraryBackupRepository(context,target);snapshot.file.inputStream().use{restore.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(it))}}
            assertArrayEquals(source.bytes(),DocumentRepository(target).read(original.id)!!.document.bytes())
            assertEquals(2,NotebookPages(target).activePages(original.id).size)
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun corruptPdfRollsBackWholeImport()=fixture{db->
        val broken=PdfDocumentSource("%PDF-1.7\nnot a document".toByteArray(),1)
        try{NotebookPages(db).importBook(NotebookFile("损坏","",listOf(InkPageFile("损坏","",emptyList(),source=PdfPageSource(broken,0)))));fail()}catch(_:Exception){}
        assertEquals(0L,db.documents().totalBytes());assertTrue(db.openHelper.readableDatabase.query("SELECT id FROM notes").use{!it.moveToFirst()})
    }
    @Test fun beautyPreservesInkRemapsCopiesAndInvalidatesSearch()=fixture{db->
        val ink=stroke();val note=InkRepository(db).importCopy(InkPageFile("原迹","",listOf(ink)))
        val source=InkSession(InkRepository(db).read(note.id)).visibleDraft().single()
        val repo=PageObjectRepository(db);val text=PageObject(id(),PageObjectKind.TEXT,text="墨织",font=TextFont.WENKAI,sourceStrokeIds=listOf(source.id))
        assertTrue(NotebookPages(db).saveSearchText(note.id,1,"旧索引",0,"OCR"))
        repo.save(note.id,0,id(),listOf(text),1)
        assertNull(NotebookPages(db).searchText(note.id))
        assertFalse(NotebookPages(db).saveSearchText(note.id,1,"过期识别",0,"OCR"))
        assertTrue(NotebookPages(db).saveSearchText(note.id,1,"墨织",1,"OCR"))
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),note.id,id()))
        val copied=repo.read(copy.id).objects.single();val copiedInk=InkSession(InkRepository(db).read(copy.id)).visibleDraft().single()
        assertEquals(listOf(copiedInk.id),copied.sourceStrokeIds);assertNotEquals(source.id,copiedInk.id)
        repo.save(note.id,1,id(),emptyList());assertArrayEquals(InkStrokeCodec.encode(source),InkStrokeCodec.encode(InkSession(InkRepository(db).read(note.id)).visibleDraft().single()))
    }
    @Test fun staleBeautyDoesNotApplyAndForeignOriginalIsRejected()=fixture{db->
        val note=WorkspaceRepository(db).create("并发",false,PaperStyle.BLANK);val repo=PageObjectRepository(db)
        val text=PageObject(id(),PageObjectKind.TEXT,text="文字")
        try{repo.save(note.id,0,id(),listOf(text),1);fail()}catch(_:IllegalArgumentException){}
        try{repo.save(note.id,0,id(),listOf(text.copy(sourceStrokeIds=listOf(id()))),0);fail()}catch(_:IllegalArgumentException){}
        assertTrue(repo.read(note.id).objects.isEmpty())
    }
}
