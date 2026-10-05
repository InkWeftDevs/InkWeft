// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class PdfViewportRasterTest {
    @Test fun croppedPdfKeepsNativeScreenResolutionBeyond2048WithoutChangingTheSource()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>()
        val id=UUID.randomUUID().toString();val name="pdf-viewport-$id.db"
        val root=File(app.cacheDir,"pdf-viewport-$id");val db=NoteDatabase.open(app,name)
        var tile:DocumentTile?=null;var pageId:String?=null
        try {
            val bytes=ByteArrayOutputStream().use{out->
                val pdf=PdfDocument()
                try{
                    val page=pdf.startPage(PdfDocument.PageInfo.Builder(1000,1414,1).create())
                    page.canvas.drawRect(480f,580f,520f,620f,Paint().apply{color=Color.BLACK})
                    pdf.finishPage(page);pdf.writeTo(out)
                }finally{pdf.close()};out.toByteArray()
            }
            val source=PdfDocumentSource(bytes,1)
            val prepared=ContentTransfer.document("PDF viewport synthetic",source,source.sha256)
            val imported=LibraryContentRepository(db).import(ImportNotebook(UUID.randomUUID().toString(),UUID.randomUUID().toString(),prepared.sha256),prepared)
            val page=NotebookPages(db).activePages(imported.id).single().id;pageId=page
            val repository=DocumentRepository(db)
            val bounds=CanvasBounds(400.0,500.0,600.0,700.0)
            val rendered=checkNotNull(DocumentRendering(app,repository,root).render(page,bounds,2304));tile=rendered
            assertEquals(bounds,rendered.bounds)
            assertEquals(2304,rendered.bitmap.width);assertEquals(2304,rendered.bitmap.height)
            assertTrue(Color.red(rendered.bitmap.getPixel(1152,1152))<32)
            assertEquals(Color.WHITE,rendered.bitmap.getPixel(0,0))
            assertArrayEquals("Display resolution must never rewrite PDF bytes",bytes,checkNotNull(repository.read(page)).document.bytes())
        }finally{
            tile?.let{RenderResources.release(it.bitmap,checkNotNull(pageId));it.bitmap.recycle()}
            db.close();app.deleteDatabase(name);root.deleteRecursively()
        }
    }
}
