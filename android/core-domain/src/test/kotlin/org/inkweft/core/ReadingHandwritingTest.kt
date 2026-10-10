package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReadingHandwritingTest {
    private fun id()=UUID.randomUUID().toString()
    private fun sourceBook(vararg sizes:Int):NotebookFile {
        // Header-only synthetic sources exercise the codec contract, not PDF rendering.
        val pages=sizes.map{size->
            val source=PdfDocumentSource(ByteArray(size).also{"%PDF-1.7\n".toByteArray().copyInto(it)},1)
            InkPageFile("p","",emptyList(),source=PdfPageSource(source,0))
        }
        // IWB2 writes the (-1, 0) source reference even for this final plain page.
        return NotebookFile("b","",pages+InkPageFile("p","",emptyList()))
    }
    @Test fun sourceBookByteLimitIncludesReferencesAndChecksum(){
        val overhead=sourceBook(9,10,11).encode().size-30
        for(excess in listOf(0,1,8)){
            val book=sourceBook(PdfDocumentSource.ARRAY_MAX_BYTES,PdfDocumentSource.ARRAY_MAX_BYTES-1,NotebookFile.MAX_BYTES-overhead-2*PdfDocumentSource.ARRAY_MAX_BYTES+1+excess)
            if(excess==0){
                val bytes=book.encode();assertEquals(NotebookFile.MAX_BYTES,bytes.size)
                val copy=NotebookFile.decode(bytes);assertEquals(book.title,copy.title);assertEquals(4,copy.pages.size)
                assertEquals(book.pages.map{it.source?.document?.sha256},copy.pages.map{it.source?.document?.sha256})
                assertEquals(listOf(0,0,0,null),copy.pages.map{it.source?.page})
            }else assertThrows(IllegalArgumentException::class.java){book.encode()}
        }
    }
    @Test fun bookStoresSharedSourceOnceAndRetainsPageOrder(){
        val bytes="%PDF-1.7\n".toByteArray()+ByteArray(100_000){42};val source=PdfDocumentSource(bytes,100)
        val pages=List(100){InkPageFile("课件","",emptyList(),source=PdfPageSource(source,99-it))}
        val encoded=NotebookFile("课件","",pages).encode();assertTrue(encoded.size<150_000)
        val decoded=NotebookFile.decode(encoded);assertEquals(99,decoded.pages.first().source!!.page)
        assertSame(decoded.pages.first().source!!.document,decoded.pages.last().source!!.document)
        assertArrayEquals(bytes,decoded.pages[42].source!!.document.bytes())
        assertEquals(ContentTransfer.Kind.BOOK,ContentTransfer.kind(encoded))
    }
    @Test fun singlePageIncludesWholeOriginalAndChecksDigest(){
        val doc=PdfDocumentSource("%PDF-1.7\noriginal".toByteArray(),3)
        val page=InkPageFile("第2页","",emptyList(),source=PdfPageSource(doc,1))
        val bytes=page.encode();val copy=(ContentTransfer.decode(bytes).content as ContentTransfer.Content.Page).value
        assertEquals(1,copy.source!!.page);assertEquals(doc.sha256,copy.source!!.document.sha256)
        bytes[bytes.size-36]=5;try{InkPageFile.decode(bytes);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun sourceBytesCannotBeMutatedByCaller(){
        val bytes="%PDF-1.7\noriginal".toByteArray();val doc=PdfDocumentSource(bytes,1);bytes[0]=0
        val copy=doc.bytes();copy[0]=0;assertEquals('%'.code,doc.bytes()[0].toInt())
    }
    @Test fun fontAndOriginalReferencesRoundTrip(){
        val stroke=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(30f,40f,0),InkSample(50f,70f,20)))
        val o=PageObject(id(),PageObjectKind.TEXT,text="双语 Bilingual",font=TextFont.WENKAI,lineSpacing=1.6f,bold=true,sourceStrokeIds=listOf(stroke.id))
        val page=InkPageFile("美化","",listOf(stroke),objects=listOf(o))
        assertEquals(o,InkPageFile.decode(page.encode()).objects.single())
        assertEquals(stroke.samples,InkPageFile.decode(page.encode()).strokes.single().samples)
    }
    @Test fun absentOriginalIsNotExportedAsDanglingReference(){
        val o=PageObject(id(),PageObjectKind.TEXT,text="文字",sourceStrokeIds=listOf(id()))
        assertTrue(InkPageFile.decode(InkPageFile("副本","",emptyList(),objects=listOf(o)).encode()).objects.single().sourceStrokeIds.isEmpty())
    }
    @Test fun lineGroupingIgnoresHighlighterAndKeepsSeparateRows(){
        fun stroke(x:Float,y:Float,pen:InkPen=InkPen.PEN)=InkStroke(id(),pen,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(x,y,0),InkSample(x+10,y+20,20)))
        val rows=HandwritingLines.split(listOf(stroke(20f,20f),stroke(50f,20f),stroke(20f,100f),stroke(0f,0f,InkPen.HIGHLIGHTER)))
        assertEquals(2,rows.size);assertEquals(2,rows.first().strokes.size)
    }
    @Test fun upperDotJoinsBodyWhileDistantColumnsRemainSeparate(){
        fun stroke(x:Float,y:Float,w:Float,h:Float)=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),1f,InkTool.STYLUS,listOf(InkSample(x,y,0),InkSample(x+w,y+h,20)))
        val sources=listOf(stroke(20f,100f,25f,65f),stroke(50f,100f,25f,70f),stroke(60f,75f,3f,14f),stroke(25f,125f,20f,2f),stroke(50f,130f,20f,2f),stroke(400f,100f,30f,70f))
        val lines=HandwritingLines.split(sources)
        assertEquals(2,lines.size);assertEquals(5,lines.first().strokes.size)
    }
    @Test fun oneShortMarkDoesNotBecomeTheHeightOfASingleTallBody(){
        val body=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,200f,0),InkSample(125f,260f,20)))
        val dot=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(112f,176f,0),InkSample(115f,179f,20)))
        val lines=HandwritingLines.split(listOf(body,dot))
        assertEquals(1,lines.size);assertEquals(setOf(body.id,dot.id),lines.single().strokes.map{it.id}.toSet())
    }
    @Test fun splittingColumnsCannotBypassRecognitionBudget(){
        val source=List(101){i->InkStroke(id(),InkPen.PEN,0xff000000.toInt(),1f,InkTool.STYLUS,
            listOf(InkSample(100f+i*300,100f,0,world=true),InkSample(110f+i*300,120f,20,world=true)),world=true)}
        assertThrows(IllegalArgumentException::class.java){HandwritingLines.split(source)}
    }
}
