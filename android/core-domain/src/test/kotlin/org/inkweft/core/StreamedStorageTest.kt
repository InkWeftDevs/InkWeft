// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.concurrent.CancellationException

class StreamedStorageTest {
    private fun rejects(code:String,body:()->Unit){try{body();fail("Expected $code")}catch(e:IllegalArgumentException){assertEquals(code,e.message)}}
    @Test fun fileCopiesAreBoundedRepeatableAndVerifyChangedInput(){
        val file=File.createTempFile("original-test-",".pdf")
        try{
            val bytes="%PDF-1.7\n".toByteArray()+ByteArray(300_000){(it%251).toByte()};file.writeBytes(bytes)
            val source=PdfDocumentSource.fromFile(file,2);assertEquals(ContentTransfer.hash(bytes),source.sha256)
            repeat(2){var seen=0;source.copyTo(object:OutputStream(){override fun write(b:Int){seen++};override fun write(b:ByteArray,o:Int,n:Int){assertTrue(n<=65536);seen+=n}});assertEquals(bytes.size,seen)}
            file.writeBytes(bytes.also{it[it.lastIndex]=(it.last()+1).toByte()})
            rejects("ORIGINAL_CHECKSUM_CHANGED"){source.copyTo(OutputStream.nullOutputStream())}
        }finally{file.delete()}
    }
    @Test fun zeroLengthStreamReadsAndTruncationCannotHideDamage(){
        val bytes=ByteArray(83){it.toByte()}
        val source=OriginalBytes.stream(bytes.size,ContentTransfer.hash(bytes)){
            object:ByteArrayInputStream(bytes){var calls=0;override fun read(b:ByteArray,o:Int,n:Int):Int=if(++calls%2==1)0 else super.read(b,o,n)}
        }
        assertArrayEquals(bytes,source.bytes(100))
        rejects("ORIGINAL_TRUNCATED"){OriginalBytes.stream(bytes.size,ContentTransfer.hash(bytes)){bytes.copyOf(40).inputStream()}.copyTo(OutputStream.nullOutputStream())}
        rejects("ORIGINAL_LENGTH_CHANGED"){OriginalBytes.stream(40,ContentTransfer.hash(bytes)){bytes.inputStream()}.copyTo(OutputStream.nullOutputStream())}
    }
    @Test fun copyCancellationPropagatesBeforeReadingTheWholeOriginal(){
        val bytes=ByteArray(300_000);var checks=0
        try{OriginalBytes.memory(bytes).copyTo(OutputStream.nullOutputStream()){if(++checks==3)throw CancellationException()};fail()}
        catch(_:CancellationException){assertEquals(3,checks)}
    }
    @Test fun largePdfImportIntentAndCopyPreflightNeverOpenAnArray(){
        val pdf=PdfDocumentSource.fromStream(OriginalBytes.stream(300_000_000,"a".repeat(64)){throw AssertionError("No eager original read")},2)
        var cleaned=0
        val prepared=ContentTransfer.document("large",pdf,"a".repeat(64)){cleaned++}
        assertEquals(300_000_000,prepared.byteCount);assertEquals(2,(prepared.content as ContentTransfer.Content.Book).value.pages.size)
        rejects("CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"){pdf.bytes()}
        rejects("CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"){(prepared.content as ContentTransfer.Content.Book).value.encode()}
        rejects("CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"){InkPageFile("large","",emptyList(),source=PdfPageSource(pdf,0)).encode()}
        prepared.close();prepared.close();assertEquals(1,cleaned)
    }
    @Test fun pageWindowRetainsVisibleNeighboursAndSelectionWithoutGrowingWithBookSize(){
        assertEquals(setOf(0,1),PageLoadWindow.indices(500,emptyList(),0))
        assertEquals(setOf(98,99,100,101,400),PageLoadWindow.indices(500,listOf(99,100),400))
        assertEquals(setOf(498,499),PageLoadWindow.indices(500,listOf(499),499))
        assertTrue(PageLoadWindow.indices(0,emptyList(),0).isEmpty())
    }
    @Test fun archiveV1RemainsReadableAndNewWritersDeclareV2(){
        val schema=listOf(LibraryArchive.Table("sample",listOf(LibraryArchive.Column("id",'I')),listOf("id")))
        val rows=object:LibraryArchive.Rows{override fun count(table:Int)=1L;override fun visit(table:Int,consume:(List<Any?>)->Unit){consume(listOf(42L))}}
        for(version in 1..2){val output=ByteArrayOutputStream();LibraryArchive.write(output,schema,rows,7,version)
            val bytes=output.toByteArray();assertEquals(version,DataInputStream(bytes.inputStream()).run{readInt();readInt()})
            var seen=0;LibraryArchive.read(bytes.inputStream(),schema,{_,row->assertEquals(listOf(42L),row);seen++});assertEquals(1,seen)}
        assertEquals(8_589_934_592L,LibraryArchive.MAX_BYTES)
    }
}
