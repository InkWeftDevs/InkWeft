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
        val text="墨织手写查找";val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=48f;typeface=TextStyles.face(TextFont.WENKAI);color=Color.BLACK}
        val bitmap=Bitmap.createBitmap(paint.measureText(text).toInt()+24,72,Bitmap.Config.ARGB_8888)
        try{val c=Canvas(bitmap);c.drawColor(Color.WHITE);c.drawText(text,12f,54f,paint)
            val result=app.handwriting.recognizeBitmap(bitmap);assertEquals(text,result.text);assertTrue(result.confidence>.7f)
        }finally{bitmap.recycle()}
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
            entry("page.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body><h1>InkWeft reading</h1><p>Original paragraph and fixed annotation coordinates.</p></body></html>""")
        }
        val pdf=DocumentImports.convert(app,buffer.toByteArray(),"epub");assertTrue(pdf.size>1000)
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
    @Test fun corruptedEbookAndEncryptedMobiFailWithoutProducingPages()=runBlocking {
        try{DocumentImports.convert(app,ByteArray(120),"mobi");fail()}catch(_:Exception){}
        try{DocumentImports.convert(app,"not epub".toByteArray(),"epub");fail()}catch(_:Exception){}
    }
}
