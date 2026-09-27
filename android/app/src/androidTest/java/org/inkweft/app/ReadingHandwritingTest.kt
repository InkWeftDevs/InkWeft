package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.zip.*

class ReadingHandwritingTest {
    private val app get()=ApplicationProvider.getApplicationContext<InkWeftApplication>()
    @Test fun bundledModelActuallyRecognizesChineseAndEnglishOffline()=runBlocking {
        for(text in listOf("墨织手写查找","Bilingual notes 2026")){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=48f;typeface=TextStyles.face(TextFont.WENKAI);color=Color.BLACK}
        val bitmap=Bitmap.createBitmap(paint.measureText(text).toInt()+24,72,Bitmap.Config.ARGB_8888)
        try{val c=Canvas(bitmap);c.drawColor(Color.WHITE);c.drawText(text,12f,54f,paint)
            val result=app.handwriting.recognizeBitmap(bitmap);assertEquals(text,result.text);assertTrue(result.confidence>.7f)
        }finally{bitmap.recycle()}}
    }
    @Test fun nativeRecognitionHandlesEmptyInkWithoutLoadingOrInventingWords()=runBlocking {
        val result=app.handwriting.recognize(emptyList());assertEquals("",result.text);assertEquals(0,result.lines)
    }
    @Test fun allFontStylesProduceConsistentMeasurableLayouts(){
        val text=PageObject(UUID.randomUUID().toString(),PageObjectKind.TEXT,text="墨织中文与 English\n选择字体并调整行距",width=400f)
        for(font in TextFont.entries){val regular=TextStyles.layout(text.copy(font=font,lineSpacing=1f));val spaced=TextStyles.layout(text.copy(font=font,lineSpacing=2f,bold=true));assertTrue(regular.height>0);assertTrue(spaced.height>regular.height)}
        assertNotEquals(TextStyles.face(TextFont.WENKAI),TextStyles.face(TextFont.SYSTEM))
    }
    @Test fun epubConvertsLocallyToStableReadablePages()=runBlocking {
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use{z->fun entry(name:String,body:String){z.putNextEntry(ZipEntry(name));z.write(body.toByteArray());z.closeEntry()}
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf","""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>InkWeft test</dc:title><dc:identifier id="id">inkweft-owned-fixture</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="page"/></spine></package>""")
            entry("page.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body><h1>墨织阅读 InkWeft reading</h1><p>中文原文与固定批注坐标。Original paragraph and fixed annotation coordinates.</p></body></html>""")
        }
        val pdf=DocumentImports.convert(app,buffer.toByteArray(),"epub");assertTrue(pdf.size>1000)
        File(app.getExternalFilesDir(null),"bilingual-ebook-check.pdf").writeBytes(pdf)
        assertTrue("A short bilingual page must not embed a complete CJK font: ${pdf.size}",pdf.size<1_000_000)
        val native=com.artifex.mupdf.fitz.Document.openDocument(pdf,"pdf")
        try{val page=native.loadPage(0);try{val text=page.toStructuredText();try{val content=java.text.Normalizer.normalize(text.asText(),java.text.Normalizer.Form.NFKC);assertTrue(content.contains("墨织阅读"));assertTrue(content.contains("Original paragraph"))}finally{text.destroy()}}finally{page.destroy()}}finally{native.destroy()}
        val file=File.createTempFile("epub-test-",".pdf",app.cacheDir)
        try{file.writeBytes(pdf);ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{r->assertEquals(1,r.pageCount);r.openPage(0).use{assertTrue(it.width>0)}}}}
        finally{file.delete()}
    }
    @Test fun uncompressedMobiBecomesReadablePdf()=runBlocking {
        val text="<html><body><h1>InkWeft MOBI</h1><p>Owned test fixture.</p></body></html>".toByteArray()
        val bytes=ByteArray(152+text.size);val b=java.nio.ByteBuffer.wrap(bytes)
        "BOOKMOBI".toByteArray().copyInto(bytes,60);b.putShort(76,2);b.putInt(78,96);b.putInt(86,152)
        b.putShort(96,1);b.putInt(100,text.size);b.putShort(104,1);b.putShort(106,4096)
        "MOBI".toByteArray().copyInto(bytes,112);b.putInt(116,40);b.putInt(120,2);b.putInt(124,65001);b.putInt(132,6);text.copyInto(bytes,152)
        val pdf=DocumentImports.convert(app,bytes,"mobi");assertTrue(pdf.size>1000);assertEquals("%PDF-",String(pdf,0,5))
    }
    @Test fun epubKeepsAuthorEmbeddedFontAndCss()=runBlocking {
        val font=checkNotNull(File("/system/fonts").listFiles()?.firstOrNull{it.name.startsWith("Roboto")&&it.extension=="ttf"})
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use{z->fun entry(name:String,bytes:ByteArray){z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
            entry("mimetype","application/epub+zip".toByteArray())
            entry("META-INF/container.xml","""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            entry("book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Own font</dc:title><dc:identifier id="id">owned-style-test</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="p" href="page.xhtml" media-type="application/xhtml+xml"/><item id="f" href="author.ttf" media-type="application/x-font-ttf"/></manifest><spine><itemref idref="p"/></spine></package>""".toByteArray())
            entry("author.ttf",font.readBytes())
            entry("page.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Own font</title><style>@font-face {font-family:BookFace;src:url('author.ttf')} body {font-family:BookFace;color:#cc0000;font-size:24pt}</style></head><body><p>Author font stays</p></body></html>""".toByteArray())
        }
        val pdf=DocumentImports.convert(app,buffer.toByteArray(),"epub")
        assertTrue("Author's embedded Roboto must not become the default face",String(pdf,Charsets.ISO_8859_1).contains("Roboto"))
        val file=File.createTempFile("author-style-",".pdf",app.cacheDir)
        try{file.writeBytes(pdf);ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{r->r.openPage(0).use{page->
            val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
            try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var red=0;for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);if(Color.red(c)>100&&Color.green(c)<80&&Color.blue(c)<80)red++}
                assertTrue("Document text color must survive default-font injection",red>20)
            }finally{bitmap.recycle()}
        }}}}finally{file.delete()}
    }
    @Test fun corruptedEbookAndEncryptedMobiFailWithoutProducingPages()=runBlocking {
        try{DocumentImports.convert(app,ByteArray(120),"mobi");fail()}catch(_:Exception){}
        try{DocumentImports.convert(app,"not epub".toByteArray(),"epub");fail()}catch(_:Exception){}
    }
}
