// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/** Explicit large-capacity bridge. Only new synthetic databases and test-package files are used. */
class LargeBackupArchiveBridgeProbe {
    private fun id()=UUID.randomUUID().toString()
    @Test fun exportOrRestoreActualAuthorArchive()=runBlocking{
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue("Explicit capacity fixture required",args.containsKey("archiveBytes"));val bytes=args.getString("archiveBytes")!!.toLong()
        require(bytes in 1..LibraryArchive.MAX_BYTES)
        val root=File(context.cacheDir,"large-cloud-${id()}").apply{check(mkdirs())}
        val db=NoteDatabase.open(context,File(root,"synthetic.db").absolutePath);val repo=LibraryBackupRepository(context,db)
        val export=File(context.getExternalFilesDir(null),"v91-capacity").apply{mkdirs()}
        val started=System.nanoTime()
        try{
            if(args.getString("restoreArchive")=="true"){
                val input=File(export,"received-$bytes.iwbackup");require(input.isFile&&input.length()==bytes)
                input.inputStream().use{repo.inspect(it)}.use{preview->
                    assertTrue(preview.notes>0);assertEquals(bytes,preview.summary.bytes)
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,repo.restore(preview))
                    val sources=db.openHelper.readableDatabase.query("SELECT id,notebookId,digest,pageCount,byteCount FROM document_sources").use{c->buildList{while(c.moveToNext())add(DocumentSourceRow(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getInt(4)))}};assertEquals(preview.notes,sources.size)
                    for(source in sources.distinctBy{it.sha256}){val document=checkNotNull(DocumentRepository(db).read(source.notebookId)).document
                        DocumentRepository(db).openFile(document).use{assertEquals(source.sha256,OriginalBytes.file(it.file,source.byteCount).sha256)}}
                    evidence(JSONObject().put("phase","empty-library-restored").put("archiveBytes",bytes).put("notes",preview.notes).put("archiveDigest",preview.summary.sha256)
                        .put("originalBytes",db.originalStore.diskBytes()).put("seconds",(System.nanoTime()-started)/1e9).put("freeBytes",root.usableSpace))
                }
                return@runBlocking
            }
            // Repeated ownership of the same real PDF makes logical archives large while
            // exercising shared-original restoration; every source row is still emitted.
            val whole=PdfDocumentSource.MAX_BYTES;val count=((bytes-2_000_000)/whole).toInt().coerceAtLeast(0)
            val books=List(count+1){WorkspaceRepository(db).create("容量合成 ${it+1}",false,PaperStyle.BLANK)}
            val documents=List(books.size){id()};val placeholder="a".repeat(64)
            db.withTransaction{books.forEachIndexed{index,n->db.documents().insertSource(DocumentSourceRow(documents[index],n.id,placeholder,1,if(index<count)whole else 4096));db.documents().insertPage(DocumentPageRow(n.id,documents[index],0))}}
            suspend fun estimate():Long{var size=0L;try{repo.snapshot(additionalBytes={size=it;error("CAPACITY_MEASURE")}).close()}catch(e:IllegalArgumentException){if(e.message=="BACKUP_SIZE_LIMIT")return LibraryArchive.MAX_BYTES+1;throw e}catch(e:IllegalStateException){check(e.message=="CAPACITY_MEASURE")};return size}
            val sql=db.openHelper.writableDatabase;var low=4096;var high=whole
            while(low<high){val mid=low+(high-low+1)/2;sql.execSQL("UPDATE document_sources SET byteCount=? WHERE id=?",arrayOf<Any>(mid,documents.last()));if(estimate()<=bytes)low=mid else high=mid-1}
            sql.execSQL("UPDATE document_sources SET byteCount=? WHERE id=?",arrayOf<Any>(low,documents.last()))
            val fill=bytes-estimate();require(fill in 0..4096)
            sql.execSQL("UPDATE notes SET text=? WHERE id=?",arrayOf("x".repeat(fill.toInt()),books.last().id));sql.execSQL("UPDATE note_revisions SET text=? WHERE noteId=?",arrayOf("x".repeat(fill.toInt()),books.last().id))
            // Text occurs in both current and revision rows. Adjust half the gap; an odd
            // remaining byte can be supplied by one independent historical title.
            if(estimate()!=bytes){sql.execSQL("UPDATE notes SET text=? WHERE id=?",arrayOf("x".repeat((fill/2).toInt()),books.last().id));sql.execSQL("UPDATE note_revisions SET text=? WHERE noteId=?",arrayOf("x".repeat((fill-fill/2).toInt()),books.last().id))}
            assertEquals(bytes,estimate())
            for((size,indices) in listOf(whole to (0 until count).toList(),low to listOf(count))){
                if(indices.isEmpty())continue
                val pdf=File(root,"$size.pdf");pdf(pdf,size);val original=PdfDocumentSource.fromFile(pdf,1)
                db.originalStore.publish(size,original.sha256){out->original.copyTo(out)}
                indices.forEach{index->sql.execSQL("UPDATE document_sources SET digest=? WHERE id=?",arrayOf(original.sha256,documents[index]))};pdf.delete()
            }
            repo.snapshot().use{snapshot->assertEquals(bytes,snapshot.file.length());val target=File(export,"archive-$bytes.iwbackup");require(!target.exists());snapshot.file.copyTo(target);assertEquals(bytes,target.length())
                evidence(JSONObject().put("phase","author-archive-exported").put("archiveBytes",bytes).put("archiveDigest",snapshot.summary.sha256).put("notes",books.size)
                    .put("originalBytes",db.originalStore.diskBytes()).put("path",target.absolutePath).put("seconds",(System.nanoTime()-started)/1e9).put("freeBytes",root.usableSpace))}
        }finally{db.close();check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator));root.deleteRecursively()}
    }
    private fun evidence(j:JSONObject){InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","LARGE_ARCHIVE_EVIDENCE $j\n")})}
    private fun pdf(file:File,size:Int){
        val offsets=mutableListOf<Long>();var position=0L
        file.outputStream().buffered().use{out->
            fun text(v:String){val b=v.toByteArray(Charsets.US_ASCII);out.write(b);position+=b.size}
            fun obj(v:String){offsets+=position;text("${offsets.size} 0 obj\n$v\nendobj\n")}
            text("%PDF-1.7\n");obj("<< /Type /Catalog /Pages 2 0 R >>");obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>");obj("<< /Length 0 >>\nstream\nendstream")
            offsets+=position;val padding=size-4096;text("5 0 obj\n<< /Length $padding >>\nstream\n");val block=ByteArray(65_536);var left=padding
            while(left>0){val n=minOf(left,block.size);out.write(block,0,n);position+=n;left-=n}
            text("\nendstream\nendobj\n");val xref=position;text("xref\n0 6\n0000000000 65535 f \n");offsets.forEach{text(String.format(Locale.ROOT,"%010d 00000 n \n",it))}
            val tail="\ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n";val fill=size-position-tail.length;check(fill>=2);text("%"+"x".repeat(fill.toInt()-2)+"\n");text(tail)
        };check(file.length()==size.toLong())
    }
}
