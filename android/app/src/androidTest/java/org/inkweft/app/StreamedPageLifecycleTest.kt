// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Small real-Room actor checks; no full UI matrix or stylus timing claim. */
class StreamedPageLifecycleTest {
    private fun id()=UUID.randomUUID().toString()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync{action()}
    private fun fixture(fault:(InkFaultPoint)->Unit={},block:suspend(InkViewModel,InkRepository,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="page-release-${id()}.db";val db=NoteDatabase.open(context,name);val store=ViewModelStore()
        try{val page=WorkspaceRepository(db).create("合成离屏页",false,PaperStyle.BLANK).id;val repo=InkRepository(db,fault);lateinit var model:InkViewModel
            main{model=InkViewModel(page,repo);store.put("page",model);model.attachPage()}
            withTimeout(15_000){model.ui.first{!it.loading}};block(model,repo,page)
        }finally{main{store.clear()};db.close();context.deleteDatabase(name)}
    }
    private fun stroke()=InkStroke(id(),InkPen.PENCIL,0xff223344.toInt(),3f,InkTool.STYLUS,listOf(InkSample(10f,20f,0,.2f),InkSample(120f,200f,50,.9f)))
    private suspend fun ready(model:InkViewModel,revision:Long)=withTimeout(15_000){model.ui.first{!it.loading&&!it.suspended&&it.queued==0&&it.blocked==null&&it.revision==revision}}
    @Test fun directPdfProviderImportHashesTheStreamAndClosesItsStagingFile()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val file=java.io.File(context.cacheDir,"provider-${id()}.pdf")
        try{
            val pdf=android.graphics.pdf.PdfDocument()
            try{val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(612,792,1).create())
                page.canvas.drawText("Streamed PDF",72f,100f,android.graphics.Paint().apply{textSize=20f});pdf.finishPage(page)
                file.outputStream().use{pdf.writeTo(it)}
            }finally{pdf.close()}
            val expected=OriginalBytes.file(file,PdfDocumentSource.MAX_BYTES)
            val prepared=DocumentImports.read(context,android.net.Uri.fromFile(file))
            val source=(prepared.content as ContentTransfer.Content.Book).value.pages.single().source!!.document
            try{assertEquals(expected.size,prepared.byteCount);assertEquals(expected.sha256,source.sha256);source.copyTo(object:java.io.OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,o:Int,n:Int){}})}
            finally{prepared.close()}
            assertThrows(IllegalArgumentException::class.java){source.openStream()}
        }finally{file.delete()}
        Unit
    }
    @Test fun offscreenReloadKeepsPressureUndoRedoAndOtherActiveView()=fixture{model,repo,page->
        val original=stroke();main{model.attachPage();model.accept(original)};ready(model,1)
        main{model.detachPage();assertFalse(model.ui.value.suspended);model.detachPage();assertTrue(model.ui.value.suspended);assertTrue(model.ui.value.strokes.isEmpty());model.attachPage()}
        ready(model,1);assertArrayEquals(InkStrokeCodec.encode(original),InkStrokeCodec.encode(model.ui.value.strokes.single()))
        main{model.undo()};ready(model,2);assertTrue(model.ui.value.strokes.isEmpty())
        main{model.detachPage();assertTrue(model.ui.value.suspended);model.attachPage()};ready(model,2)
        main{model.redo()};ready(model,3);assertArrayEquals(InkStrokeCodec.encode(original),InkStrokeCodec.encode(model.ui.value.strokes.single()))
        assertEquals(3L,repo.read(page).revision)
    }
    @Test fun unknownCommitCannotReleaseDraftAndReceiptRetryReleasesOnlyAfterConfirmation(){
        var failAfterCommit=true
        fixture(fault={if(failAfterCommit&&it==InkFaultPoint.AFTER_TRANSACTION)error("synthetic response loss")}){model,_,_->
            val original=stroke();main{model.accept(original)}
            withTimeout(15_000){model.ui.first{it.blocked==InkCommitResult.Unknown}}
            main{model.detachPage();assertFalse(model.ui.value.suspended);assertEquals(original.id,model.ui.value.strokes.single().id);failAfterCommit=false;model.retry()}
            withTimeout(15_000){model.ui.first{it.suspended}}
            main{model.attachPage()};ready(model,1);assertEquals(1,model.ui.value.strokes.size)
        }
    }
    @Test fun changedStoredRevisionAfterReleaseDoesNotReplayStaleUndo()=fixture{model,repo,page->
        main{model.accept(stroke())};ready(model,1);main{model.detachPage();assertTrue(model.ui.value.suspended)}
        assertTrue(repo.save(CommitInk(id(),page,1,InkMutation.Add(stroke()))) is InkCommitResult.Committed)
        main{model.attachPage()};ready(model,2)
        assertEquals(2,model.ui.value.strokes.size);assertFalse(model.ui.value.canUndo)
        main{model.undo()};assertEquals(2L,repo.read(page).revision)
    }
}
