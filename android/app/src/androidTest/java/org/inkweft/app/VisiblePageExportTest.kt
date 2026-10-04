// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/** Synthetic content only. Export must finish completely before the caller offers a destination. */
class VisiblePageExportTest {
    private val app get()=ApplicationProvider.getApplicationContext<InkWeftApplication>()
    private fun id()=UUID.randomUUID().toString()
    private fun image():PageObject {
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val bytes=try{ByteArrayOutputStream().also{check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}.toByteArray()}finally{bitmap.recycle()}
        return PageObject(id(),PageObjectKind.IMAGE,0f,0f,64f,64f,image=Base64.getEncoder().encodeToString(bytes))
    }
    private fun <T> withoutRenderBudget(work:()->T):T {
        val held=Any();val owner="visible-export-test-${id()}"
        RenderResources.track(held,RenderResources.BUDGET,"fixture",owner,RenderResources.Role.ACTIVE)
        return try{work()}finally{RenderResources.release(held,owner)}
    }

    @Test fun imageBudgetFailureReturnsNoPdfAndRetryPreservesPixelsAndAuthorData()=runBlocking {
        val note=app.workspaceRepository.create("可见导出合成图片 ${id()}",true,PaperStyle.BLANK)
        val image=image();app.pageObjects.save(note.id,0,id(),listOf(image))
        val before=app.pageObjects.read(note.id)
        val authoring=PageAuthoringCodec.fingerprint(app.authoring.readPage(note.id).state)
        // PDF does not reserve a PNG output bitmap, so this fails specifically at the image decoder.
        withoutRenderBudget {
            assertThrows(RenderBudgetBusy::class.java){runBlocking{VisiblePageExport.encode(app,note.id,false,true)}}
        }
        assertEquals(before,app.pageObjects.read(note.id))
        assertEquals(authoring,PageAuthoringCodec.fingerprint(app.authoring.readPage(note.id).state))
        val pdf=VisiblePageExport.encode(app,note.id,false,true)
        assertEquals("%PDF-",pdf.copyOfRange(0,5).toString(Charsets.US_ASCII))
        val png=VisiblePageExport.encode(app,note.id,false,false)
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(png,0,png.size))
        try{val center=bitmap.getPixel(bitmap.width/2,bitmap.height/2);assertTrue(Color.red(center)>220&&Color.green(center)<30&&Color.blue(center)<30)}finally{bitmap.recycle()}
        assertEquals(before,app.pageObjects.read(note.id))
    }

    @Test fun malformedVisiblePreviewIsRejectedButInteractivePlaceholderStillWorks(){
        val broken=image().copy(image=Base64.getEncoder().encodeToString(byteArrayOf(-1,-40,0,0,0)))
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val interactive=PageObjectPainter();val exporting=PageObjectPainter(requireCompleteImages=true)
        try{
            interactive.draw(Canvas(bitmap),listOf(broken),false,broken.bounds())
            assertEquals(Color.LTGRAY,bitmap.getPixel(32,32))
            val failure=assertThrows(IllegalStateException::class.java){exporting.draw(Canvas(bitmap),listOf(broken),false,broken.bounds())}
            assertTrue(failure.message.orEmpty().contains("本次未输出"))
        }finally{interactive.clear();exporting.clear();bitmap.recycle()}
    }

    @Test fun unavailablePdfRasterRejectsExportInsteadOfSubstitutingPaperAndCanRetry()=runBlocking {
        val source=PdfDocument()
        val bytes=try{
            val page=source.startPage(PdfDocument.PageInfo.Builder(1000,1414,1).create())
            page.canvas.drawColor(Color.RED);source.finishPage(page)
            ByteArrayOutputStream().also{source.writeTo(it)}.toByteArray()
        }finally{source.close()}
        val pdf=PdfDocumentSource(bytes,1)
        val prepared=ContentTransfer.document("可见导出合成文档 ${id()}",pdf,pdf.sha256)
        val note=app.libraryContent.import(ImportNotebook(id(),id(),prepared.sha256),prepared)
        val page=app.pages.activePages(note.id).single().id
        withoutRenderBudget {
            assertThrows(RenderBudgetBusy::class.java){runBlocking{VisiblePageExport.encode(app,page,false,true)}}
        }
        assertArrayEquals(bytes,checkNotNull(app.documents.read(page)).document.bytes())
        val encoded=VisiblePageExport.encode(app,page,false,false)
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(encoded,0,encoded.size))
        try{assertEquals(Color.RED,bitmap.getPixel(bitmap.width/2,bitmap.height/2))}finally{bitmap.recycle()}
        assertArrayEquals(bytes,checkNotNull(app.documents.read(page)).document.bytes())
    }
}
