// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ContentTransferTest {
    private fun id() = UUID.randomUUID().toString()
    private fun page(world: Boolean = false) = InkPageFile("测试", "正文", emptyList(), world)
    private fun rejects(block: () -> Unit) { try { block(); fail("accepted invalid input") } catch (_: IllegalArgumentException) {} }
    @Test fun readsPageWithUnknownAvailableAndSingleByteChunks() {
        val bytes = page().encode()
        val source = object : ByteArrayInputStream(bytes) {
            override fun available() = 0
            override fun read(b: ByteArray, o: Int, n: Int) = super.read(b, o, minOf(1,n))
        }
        val result = ContentTransfer.read(source)
        assertEquals(ContentTransfer.Kind.PAGE,result.kind)
        assertEquals(bytes.size,result.byteCount)
        assertEquals("正文",(result.content as ContentTransfer.Content.Page).value.text)
    }
    @Test fun handlesOneZeroProgressReadWithoutLooping() {
        val bytes = page().encode()
        val source = object : ByteArrayInputStream(bytes) {
            var zero = true
            override fun read(b: ByteArray, o: Int, n: Int): Int { if(zero){zero=false;return 0};return super.read(b,o,n) }
        }
        assertEquals(bytes.size,ContentTransfer.read(source).byteCount)
    }
    @Test fun actualBookOverFourMegabytesRoundTrips() {
        val strokes = List(10) { n -> InkStroke(id(),InkPen.PEN,0xff223344.toInt(),3f,InkTool.STYLUS,
            List(8192) { i -> InkSample(30f+(i%800),40f+(i%1200),(i+n).toLong()) }) }
        val first = InkPageFile("大样本", "", strokes)
        val book = NotebookFile("三页", "正文", listOf(first,first,first))
        val bytes = book.encode(); assertTrue(bytes.size>InkPageFile.MAX_BYTES)
        val parsed = ContentTransfer.read(ByteArrayInputStream(bytes))
        assertEquals(ContentTransfer.Kind.BOOK,parsed.kind)
        val content=parsed.content as ContentTransfer.Content.Book
        assertEquals(3,content.value.pages.size)
        assertEquals(strokes.first().samples,content.value.pages[2].strokes.first().samples)
    }
    @Test fun rejectsUnknownHeaderBeforeReadingBody() {
        var reads=0
        val input=object:InputStream(){override fun read():Int { reads++;return 0 }}
        rejects{ContentTransfer.read(input)};assertEquals(4,reads)
    }
    @Test fun rejectsTruncatedHeader() { for(n in 0..3) rejects{ContentTransfer.read(ByteArrayInputStream(ByteArray(n)))} }
    @Test fun pageCannotUseLargerBookLimit() {
        val input=object:InputStream(){var i=0;val h=byteArrayOf(0x49,0x57,0x50,0x32)
            override fun read():Int { val index=i++;return if(index<4)h[index].toInt()and 255 else 0 }}
        rejects{ContentTransfer.read(input)}
        assertEquals(InkPageFile.MAX_BYTES+1,input.i)
    }
    @Test fun corruptChecksumAndTrailingBytesAreRejected() {
        val bytes=page().encode();val changed=bytes.clone();changed[10]=(changed[10].toInt()xor 1).toByte()
        rejects{ContentTransfer.decode(changed)};rejects{ContentTransfer.decode(bytes+byteArrayOf(0))}
    }
    @Test fun boardCopyRemainsBoardWithNegativeCoordinates() {
        val p=InkPageFile("无界","",listOf(InkStroke(id(),InkPen.PEN,0xff112233.toInt(),2f,InkTool.STYLUS,
            listOf(InkSample(-40f,-80f,0,world=true)),true)),true,PaperStyle.DOTS)
        val decoded=ContentTransfer.read(ByteArrayInputStream(p.encode())).content as ContentTransfer.Content.Page
        assertTrue(decoded.value.world);assertEquals(-40f,decoded.value.strokes[0].samples[0].x,0f)
    }
    @Test fun cancellationIsNotSwallowedAndDoesNotCloseCallerStream() {
        val input=object:ByteArrayInputStream(page().encode()){var closed=false;override fun close(){closed=true;super.close()}}
        try{ContentTransfer.read(input){throw CancellationException()};fail("not cancelled")}catch(_:CancellationException){}
        assertFalse(input.closed)
    }
    @Test fun providerIOExceptionPropagates() {
        try{ContentTransfer.read(object:InputStream(){override fun read():Int=throw IOException("synthetic")});fail("ignored")}catch(_:IOException){}
    }
    @Test fun copyAndImportCommandDigestsAreStableAndScopeBound() {
        val a=id();val b=id();val c=id();val copy=CopyNotebook(a,b,c)
        assertEquals(copy.digest(),copy.copy().digest());assertNotEquals(copy.digest(),copy.copy(sourceId=id()).digest())
        val imp=ImportNotebook(a,c,"0".repeat(64));assertNotEquals(imp.digest(),copy.digest())
        rejects{CopyNotebook(a,b,b)};rejects{ImportNotebook(a,c,"invalid")}
    }
    @Test fun suggestedFilenameHasNoControlOrPathParts() {
        assertEquals("笔记.iwbook",ContentTransfer.safeSuggestedName("../笔记/\n", "iwbook").removePrefix(".."))
        assertEquals("墨织内容副本.iwpage",ContentTransfer.safeSuggestedName("/\\\n", "iwpage"))
        rejects{ContentTransfer.safeSuggestedName("x","exe")}
    }
    @Test fun shelfPinsFirstWithoutTurningFavoritesIntoPins() {
        data class Item(val title:String,val pinned:Boolean,val favorite:Boolean=false)
        val a=Item("B",false,true);val b=Item("C",true);val c=Item("A",true)
        assertEquals(listOf(b,c,a),ShelfOrder.arrange(listOf(a,b,c),{it.pinned},{it.title},false))
        assertEquals(listOf(c,b,a),ShelfOrder.arrange(listOf(a,b,c),{it.pinned},{it.title},true))
    }
}
