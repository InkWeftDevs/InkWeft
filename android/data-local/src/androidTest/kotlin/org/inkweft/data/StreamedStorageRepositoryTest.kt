// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.*
import java.util.Locale
import java.util.UUID

/** Opt-in large synthetic device test. Run on an empty test installation with at least 6 GiB free. */
class StreamedStorageRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun pdf300MBImportDuplicateAndFullBackupRestorePreserveOriginals()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Needs 6 GiB of test scratch space",context.cacheDir.usableSpace>=6L*1024*1024*1024)
        val a="stream-source-${id()}.db";val b="stream-target-${id()}.db"
        val source=NoteDatabase.open(context,a);val target=NoteDatabase.open(context,b)
        val file=File(context.cacheDir,"stream-300MB-${id()}.pdf")
        try {
            syntheticPdf(file,300_000_000)
            val original=PdfDocumentSource.fromFile(file,2)
            ContentTransfer.document("合成300MB原件",original,original.sha256).use{prepared->
                val imports=LibraryContentRepository(source);val command=ImportNotebook(id(),id(),prepared.sha256)
                val note=imports.import(command,prepared)
                assertEquals(note.id,imports.import(command,prepared).id)
                val copy=imports.duplicate(CopyNotebook(id(),note.id,id()))
                assertEquals(600_000_000L,source.documents().totalBytes())
                for(book in listOf(note.id,copy.id)){
                    val pages=source.pages().list(book);assertEquals(2,pages.size)
                    val repo=DocumentRepository(source)
                    val first=checkNotNull(repo.read(pages.first().id));val last=checkNotNull(repo.read(pages.last().id))
                    assertEquals(0,first.page);assertEquals(1,last.page);assertEquals(original.sha256,first.document.sha256)
                    repo.openFile(last.document).use{lease->assertEquals(original.sha256,OriginalBytes.file(lease.file,PdfDocumentSource.MAX_BYTES).sha256)}
                    repo.trimFiles()
                }
                val backup=LibraryBackupRepository(context,source);val destination=LibraryBackupRepository(context,target)
                backup.snapshot().use{snapshot->
                    snapshot.file.inputStream().use{input->destination.inspect(input)}.use{preview->
                        assertEquals(2,preview.notes);assertEquals(4,preview.pages)
                        assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,destination.restore(preview))
                        assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,destination.restore(preview))
                    }
                }
                assertEquals(600_000_000L,target.documents().totalBytes())
                val restored=checkNotNull(DocumentRepository(target).read(copy.id))
                var seen=0L;restored.document.copyTo(object:OutputStream(){override fun write(b:Int){seen++};override fun write(b:ByteArray,o:Int,n:Int){assertTrue(n<=65_536);seen+=n}})
                assertEquals(300_000_000L,seen);assertEquals(original.sha256,restored.document.sha256)
                assertThrows(IllegalArgumentException::class.java){restored.document.bytes()}
            }
        }finally{source.originalFiles.trim();target.originalFiles.trim();source.close();target.close();context.deleteDatabase(a);context.deleteDatabase(b);file.delete()}
        Unit
    }
    private fun syntheticPdf(file:File,size:Int) {
        val offsets=mutableListOf<Long>();var position=0L
        file.outputStream().buffered().use{out->
            fun text(s:String){val bytes=s.toByteArray(Charsets.US_ASCII);out.write(bytes);position+=bytes.size}
            fun obj(body:String){offsets+=position;text("${offsets.size} 0 obj\n$body\nendobj\n")}
            text("%PDF-1.7\n");obj("<< /Type /Catalog /Pages 2 0 R >>");obj("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
            repeat(2){obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>")}
            obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            val content="BT /F1 18 Tf 72 700 Td (InkWeft streamed original) Tj ET\n";obj("<< /Length ${content.length} >>\nstream\n${content}endstream")
            offsets+=position;val padding=size-4096;text("7 0 obj\n<< /Length $padding >>\nstream\n")
            val block=ByteArray(65_536);var left=padding
            while(left>0){val n=minOf(left,block.size);out.write(block,0,n);position+=n;left-=n}
            text("\nendstream\nendobj\n");val xref=position;text("xref\n0 8\n0000000000 65535 f \n")
            offsets.forEach{text(String.format(Locale.ROOT,"%010d 00000 n \n",it))}
            val tail="\ntrailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n";val fill=size-position-tail.length;check(fill>=2)
            text("%"+"x".repeat(fill.toInt()-2)+"\n");text(tail)
        };check(file.length()==size.toLong())
    }
}
