// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

class OriginalStorageRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(legacyVacuum:Boolean=false,incrementalVacuum:Boolean=false,block:suspend(Context,File,NoteDatabase,NoteDatabase)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"original-store-${id()}").apply{check(mkdirs())}
        if(legacyVacuum||incrementalVacuum){
            // Establish the requested pre-existing format on one connection before Room's WAL pool.
            val mode=if(incrementalVacuum)2 else 0
            val options=android.database.sqlite.SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .setJournalMode("DELETE").build()
            android.database.sqlite.SQLiteDatabase.openDatabase(File(root,"source.db"),options).use{sql->
                sql.execSQL("PRAGMA auto_vacuum=$mode");sql.execSQL("VACUUM");sql.version=0
                assertEquals(mode,sql.rawQuery("PRAGMA auto_vacuum",null).use{it.moveToFirst();it.getInt(0)})
            }
        }
        val source=NoteDatabase.open(context,File(root,"source.db").absolutePath)
        val target=NoteDatabase.open(context,File(root,"target.db").absolutePath)
        try{block(context,root,source,target)}finally{source.close();target.close();check(root.deleteRecursively())}
    }
    private fun count(db:NoteDatabase,table:String)=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getLong(0)}
    private fun number(db:NoteDatabase,pragma:String)=db.openHelper.readableDatabase.query("PRAGMA $pragma").use{it.moveToFirst();it.getLong(0)}
    private fun evidence(kind:String,vararg values:Pair<String,Any>){
        val json=org.json.JSONObject().put("kind",kind);values.forEach{json.put(it.first,it.second)}
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","ORIGINAL_STORAGE_EVIDENCE $json\n")
        })
    }
    private suspend fun attach(db:NoteDatabase,source:PdfDocumentSource):String {
        val book=WorkspaceRepository(db).create("合成原件",false,PaperStyle.BLANK).id
        db.withTransaction{DocumentRepository(db).attach(book,PdfPageSource(source,0))};return book
    }
    private suspend fun legacy(db:NoteDatabase,source:PdfDocumentSource,document:String,corrupt:Boolean=false):String {
        val book=WorkspaceRepository(db).create("合成部分迁移",false,PaperStyle.BLANK).id
        db.withTransaction{
            db.documents().insertSource(DocumentSourceRow(document,book,source.sha256,1,source.size))
            storeOriginal(source.size,source.sha256,DocumentRepository.CHUNK,source::openStream){position,bytes->
                if(corrupt&&position==1)bytes[0]=(bytes[0].toInt() xor 1).toByte()
                db.documents().insertChunk(DocumentChunkRow(document,position,bytes))
            }
            db.documents().insertPage(DocumentPageRow(book,document,0))
        };return book
    }
    @Test fun importCopyAndBackupUseOneFilePerDigestAndNoLiveSqliteBlobs()=fixture{context,root,db,target->
        val file=File(root,"source.pdf");pdf(file,8*1024*1024);val original=PdfDocumentSource.fromFile(file,1)
        val first=attach(db,original)
        val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        val (jpeg,png)=try{
            ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)}.toByteArray() to
                ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        }finally{bitmap.recycle()}
        val image=ImageSource(png)
        val item=PageObject(id(),PageObjectKind.IMAGE,image=java.util.Base64.getEncoder().encodeToString(jpeg),imageSource=image.sha256)
        PageObjectRepository(db).save(first,0,id(),listOf(item),originals=listOf(image))
        val second=LibraryContentRepository(db).duplicate(CopyNotebook(id(),first,id())).id
        val storedBytes=original.size.toLong()+png.size
        assertEquals(0L,count(db,"document_chunks"));assertEquals(2L,count(db,"document_sources"))
        assertEquals(0L,count(db,"image_chunks"));assertEquals(2L,count(db,"image_sources"))
        assertEquals(storedBytes,db.originalStore.diskBytes())
        val repo=DocumentRepository(db)
        repo.openFile(checkNotNull(repo.read(first)).document).use{a->repo.openFile(checkNotNull(repo.read(second)).document).use{b->assertEquals(a.file,b.file)}}
        assertEquals(0L,db.originalFiles.diskBytes())
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        assertTrue(File(db.openHelper.writableDatabase.path).length()<original.size/4)
        val backup=LibraryBackupRepository(context,db);val restored=LibraryBackupRepository(context,target)
        backup.snapshot().use{snapshot->snapshot.file.inputStream().use{restored.inspect(it)}.use{preview->
            assertNotEquals(db.originalRoot,preview.stage.originalRoot);assertNotEquals(target.originalRoot,preview.stage.originalRoot)
            assertEquals(storedBytes,preview.stage.originalStore.diskBytes());assertEquals(0L,count(preview.stage,"document_chunks"))
            assertEquals(0L,count(preview.stage,"image_chunks"))
            assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restored.restore(preview))
            assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restored.restore(preview))
        }}
        assertEquals(0L,count(target,"document_chunks"));assertEquals(0L,count(target,"image_chunks"));assertEquals(storedBytes,target.originalStore.diskBytes())
        assertArrayEquals(png,ImageSourceRepository(target).read(second,image.sha256).bytes())
        DocumentRepository(target).openFile(checkNotNull(DocumentRepository(target).read(second)).document).use{
            assertEquals(original.sha256,OriginalBytes.file(it.file,original.size).sha256)
        }
        evidence("deduplicated-files","logicalPdfBytes" to db.documents().totalBytes(),"logicalImageBytes" to db.images().totalBytes(),
            "physicalOriginalBytes" to db.originalStore.diskBytes(),"databaseFileBytes" to File(db.openHelper.writableDatabase.path).length(),
            "documentChunkRows" to count(db,"document_chunks"),"imageChunkRows" to count(db,"image_chunks"),"restoredPhysicalBytes" to target.originalStore.diskBytes())
    }
    @Test fun restoreRollbackLeavesNoReferencesAndOrphanCollectionReleasesItsBytes()=fixture{context,root,db,target->
        val file=File(root,"source.pdf");pdf(file,2*1024*1024);val original=PdfDocumentSource.fromFile(file,1);attach(db,original)
        val backup=LibraryBackupRepository(context,db)
        val restore=LibraryBackupRepository(context,target){if(it==LibraryBackupRepository.BackupFault.BEFORE_RESTORE_COMMIT)throw IOException("injected rollback")}
        backup.snapshot().use{snapshot->snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->
            try{restore.restore(preview);fail("restore must roll back")}catch(expected:IOException){assertEquals("injected rollback",expected.message)}
            assertEquals(0L,count(target,"notes"));assertEquals(0L,count(target,"document_sources"))
            assertEquals(original.size.toLong(),target.originalStore.diskBytes())
            val report=OriginalStorageMaintenance(target).reclaim()
            assertEquals(original.size.toLong(),report.removedFileBytes);assertEquals(0L,target.originalStore.diskBytes())
            assertEquals(original.size.toLong(),preview.stage.originalStore.diskBytes())
        }}
        assertEquals(original.size.toLong(),db.originalStore.diskBytes())
    }
    @Test fun oldChunksRetryAfterPublicationThenVacuumReturnsPhysicalDatabaseSpace()=fixture(legacyVacuum=true){_,root,db,_->
        val sql=db.openHelper.writableDatabase
        assertEquals(0L,number(db,"auto_vacuum"));val pageSize=number(db,"page_size")
        val file=File(root,"old.pdf");pdf(file,6*1024*1024);val original=PdfDocumentSource.fromFile(file,1)
        val book=WorkspaceRepository(db).create("合成旧分块",false,PaperStyle.BLANK).id;val document=id()
        db.withTransaction{
            db.documents().insertSource(DocumentSourceRow(document,book,original.sha256,1,original.size))
            storeOriginal(original.size,original.sha256,DocumentRepository.CHUNK,original::openStream){position,bytes->db.documents().insertChunk(DocumentChunkRow(document,position,bytes))}
            db.documents().insertPage(DocumentPageRow(book,document,0))
        }
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use{while(it.moveToNext())Unit}
        val before=File(sql.path).length();assertTrue(before>original.size)
        val reading=checkNotNull(DocumentRepository(db).read(book)).document.openStream()
        val first=ByteArray(97);assertEquals(first.size,reading.read(first))
        // State after a process stops between durable publication and the reference transaction.
        db.originalStore.publish(original.size,original.sha256){original.copyTo(it)}
        val report=OriginalStorageMaintenance(db).reclaim()
        assertEquals(1,report.migratedOriginals);assertEquals(original.size.toLong(),report.migratedBytes)
        assertEquals(0L,count(db,"document_chunks"));assertEquals(1L,count(db,"document_sources"))
        assertEquals(2L,number(db,"auto_vacuum"));assertEquals(pageSize,number(db,"page_size"));assertEquals(0L,report.freePagesAfter)
        assertNull(report.compactionDeferred);assertTrue(report.databaseBytesAfter<report.databaseBytesBefore-original.size/2)
        assertTrue(File(sql.path).length()<before-original.size/2)
        evidence("legacy-physical-reclamation","databaseBytesBefore" to report.databaseBytesBefore,"databaseBytesAfter" to report.databaseBytesAfter,
            "migratedBytes" to report.migratedBytes,"originalBytesAfter" to report.originalBytesAfter,"freePagesAfter" to report.freePagesAfter,"pageSize" to pageSize)
        reading.use{input->
            val digest=MessageDigest.getInstance("SHA-256");digest.update(first)
            val block=ByteArray(64*1024);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)}
            assertEquals(original.sha256,digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
        }
        DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document).use{assertEquals(original.sha256,OriginalBytes.file(it.file,original.size).sha256)}
    }
    @Test fun referencedTrashedAndLeasedOriginalsSurviveUntilTheirLastRetainerIsReleased()=fixture{_,root,db,_->
        val file=File(root,"keep.pdf");pdf(file,1024*1024);val original=PdfDocumentSource.fromFile(file,1);val book=attach(db,original)
        val workspace=WorkspaceRepository(db);val state=workspace.get(book)
        assertTrue(workspace.organize(book,state.revision,"","",false,true))
        assertEquals(0,OriginalStorageMaintenance(db).reclaim().removedFiles)
        val lease=DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document)
        db.withTransaction{
            db.openHelper.writableDatabase.execSQL("DELETE FROM document_pages WHERE pageId=?",arrayOf(book))
            db.openHelper.writableDatabase.execSQL("DELETE FROM document_sources WHERE notebookId=?",arrayOf(book))
        }
        assertEquals(0,OriginalStorageMaintenance(db).reclaim().removedFiles);assertTrue(lease.file.isFile)
        lease.close();val report=OriginalStorageMaintenance(db).reclaim()
        assertEquals(original.size.toLong(),report.removedFileBytes);assertFalse(lease.file.exists())
    }
    @Test fun corruptionIsReportedAndBothReferenceAndDamagedFileRemain()=fixture{_,root,db,_->
        val file=File(root,"damaged.pdf");pdf(file,1024*1024);val original=PdfDocumentSource.fromFile(file,1);val book=attach(db,original)
        val owned=DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document).use{it.file}
        RandomAccessFile(owned,"rw").use{it.seek(200);it.write(99)};check(owned.setLastModified(owned.lastModified()+5000))
        try{OriginalStorageMaintenance(db).reclaim();fail("corruption must be reported")}catch(expected:IllegalArgumentException){assertEquals("ORIGINAL_CHECKSUM_CHANGED",expected.message)}
        assertEquals(1L,count(db,"document_sources"));assertTrue(owned.isFile);assertEquals(original.size.toLong(),owned.length())
    }
    @Test fun corruptOldChunksAreNeverRemovedByMigration()=fixture{_,root,db,_->
        val file=File(root,"old.pdf");pdf(file,1024*1024);val original=PdfDocumentSource.fromFile(file,1)
        val book=WorkspaceRepository(db).create("合成旧分块损坏",false,PaperStyle.BLANK).id;val document=id()
        db.withTransaction{
            db.documents().insertSource(DocumentSourceRow(document,book,original.sha256,1,original.size))
            storeOriginal(original.size,original.sha256,DocumentRepository.CHUNK,original::openStream){position,bytes->
                if(position==1)bytes[0]=(bytes[0].toInt() xor 1).toByte()
                db.documents().insertChunk(DocumentChunkRow(document,position,bytes))
            }
            db.documents().insertPage(DocumentPageRow(book,document,0))
        }
        val chunks=count(db,"document_chunks")
        try{OriginalStorageMaintenance(db).reclaim();fail("bad legacy data must stay intact")}catch(expected:IllegalArgumentException){assertEquals("ORIGINAL_CHECKSUM_CHANGED",expected.message)}
        assertEquals(chunks,count(db,"document_chunks"));assertEquals(1L,count(db,"document_sources"));assertEquals(0L,db.originalStore.diskBytes())
    }
    @Test fun lowSpaceDefersCompactionAndRetryReclaimsWithoutChangingOriginals()=fixture{_,root,db,_->
        val sql=db.openHelper.writableDatabase;sql.execSQL("PRAGMA auto_vacuum=NONE");sql.execSQL("VACUUM")
        val file=File(root,"space.pdf");pdf(file,2*1024*1024);val original=PdfDocumentSource.fromFile(file,1)
        val book=WorkspaceRepository(db).create("合成空间不足",false,PaperStyle.BLANK).id;val document=id()
        db.withTransaction{
            db.documents().insertSource(DocumentSourceRow(document,book,original.sha256,1,original.size))
            storeOriginal(original.size,original.sha256,DocumentRepository.CHUNK,original::openStream){position,bytes->db.documents().insertChunk(DocumentChunkRow(document,position,bytes))}
            db.documents().insertPage(DocumentPageRow(book,document,0))
        }
        val deferred=OriginalStorageMaintenance(db,availableBytes={0L}).reclaim()
        assertEquals("SQLITE_COMPACTION_LOW_SPACE",deferred.compactionDeferred);assertTrue(deferred.freePagesAfter>0)
        assertEquals(1L,count(db,"document_sources"));assertEquals(0L,count(db,"document_chunks"))
        assertEquals(original.size.toLong(),db.originalStore.diskBytes())
        DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document).use{assertEquals(original.sha256,OriginalBytes.file(it.file,original.size).sha256)}
        val completed=OriginalStorageMaintenance(db).reclaim()
        assertNull(completed.compactionDeferred);assertEquals(0L,completed.freePagesAfter)
        assertTrue(completed.databaseBytesAfter<completed.databaseBytesBefore-original.size/2)
    }
    @Test fun laterCorruptionStillReclaimsSuccessfullyMigratedDatabaseBytes()=fixture(incrementalVacuum=true){_,root,db,_->
        val sql=db.openHelper.writableDatabase;assertEquals(2L,number(db,"auto_vacuum"))
        val goodFile=File(root,"good.pdf");pdf(goodFile,4*1024*1024);val good=PdfDocumentSource.fromFile(goodFile,1)
        val badFile=File(root,"bad.pdf");pdf(badFile,1024*1024);val bad=PdfDocumentSource.fromFile(badFile,1)
        val goodId="00000000-0000-0000-0000-000000000001";val badId="00000000-0000-0000-0000-000000000002"
        val book=legacy(db,good,goodId);legacy(db,bad,badId,true)
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use{while(it.moveToNext())Unit};val before=File(sql.path).length()
        val failure=runCatching{OriginalStorageMaintenance(db).reclaim()}.exceptionOrNull()
        assertEquals("ORIGINAL_CHECKSUM_CHANGED",failure?.message)
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM document_chunks WHERE documentId=?",arrayOf(goodId)).use{it.moveToFirst();it.getLong(0)})
        assertEquals((bad.size.toLong()+DocumentRepository.CHUNK-1)/DocumentRepository.CHUNK,count(db,"document_chunks"))
        assertEquals(2L,count(db,"document_sources"));assertEquals(good.size.toLong(),db.originalStore.diskBytes())
        assertEquals(0L,number(db,"freelist_count"));assertTrue(File(sql.path).length()<before-good.size/2)
        DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document).use{assertEquals(good.sha256,OriginalBytes.file(it.file,good.size).sha256)}
        evidence("partial-migration-reclamation","databaseBytesBefore" to before,"databaseBytesAfter" to File(sql.path).length(),
            "migratedBytes" to good.size,"retainedDamagedChunkRows" to count(db,"document_chunks"))
    }
    @Test fun cleanupFailureKeepsOriginalErrorAndRetryCompactsBeforeRevisitingBadSource()=fixture{_,root,db,_->
        val sql=db.openHelper.writableDatabase;sql.execSQL("PRAGMA auto_vacuum=NONE");sql.execSQL("VACUUM")
        val goodFile=File(root,"good.pdf");pdf(goodFile,2*1024*1024);val good=PdfDocumentSource.fromFile(goodFile,1)
        val badFile=File(root,"bad.pdf");pdf(badFile,1024*1024);val bad=PdfDocumentSource.fromFile(badFile,1)
        legacy(db,good,"00000000-0000-0000-0000-000000000001")
        legacy(db,bad,"00000000-0000-0000-0000-000000000002",true)
        sql.execSQL("VACUUM");assertEquals(0L,number(db,"freelist_count"))
        val failure=runCatching{OriginalStorageMaintenance(db,availableBytes={throw IOException("injected compaction failure")}).reclaim()}.exceptionOrNull()
        assertEquals("ORIGINAL_CHECKSUM_CHANGED",failure?.message)
        assertEquals(listOf("injected compaction failure"),checkNotNull(failure).suppressed.map{it.message})
        assertTrue(number(db,"freelist_count")>0)
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use{while(it.moveToNext())Unit};val before=File(sql.path).length()
        val retry=runCatching{OriginalStorageMaintenance(db).reclaim()}.exceptionOrNull()
        assertEquals("ORIGINAL_CHECKSUM_CHANGED",retry?.message)
        assertEquals(0L,number(db,"freelist_count"));assertTrue(File(sql.path).length()<before-good.size/2)
        assertEquals((bad.size.toLong()+DocumentRepository.CHUNK-1)/DocumentRepository.CHUNK,count(db,"document_chunks"))
        assertEquals(good.size.toLong(),db.originalStore.diskBytes());assertEquals(2L,count(db,"document_sources"))
    }
    @Test fun missingOwnedFileCannotBeMaskedByARebuildableRenderCache()=fixture{_,root,db,_->
        val file=File(root,"missing.pdf");pdf(file,1024*1024);val original=PdfDocumentSource.fromFile(file,1);val book=attach(db,original)
        val captured=checkNotNull(DocumentRepository(db).read(book)).document
        db.originalFiles.acquire(original.size,original.sha256){out,active->original.copyTo(out,active)}.close()
        val owned=db.originalStore.acquire(original.size,original.sha256).use{it.file};check(owned.delete())
        try{DocumentRepository(db).openFile(captured);fail("owned file must be reported missing")}catch(expected:IllegalArgumentException){assertEquals("ORIGINAL_FILE_MISSING",expected.message)}
        assertEquals(1L,count(db,"document_sources"));assertEquals(original.size.toLong(),db.originalFiles.diskBytes())
    }
    @Test fun schema18MigrationAddsIndexedDigestLookupAndRetainsItsChunkOriginal()=fixture{context,root,db,_->
        val file=File(root,"schema18.pdf");pdf(file,1024*1024);val original=PdfDocumentSource.fromFile(file,1)
        val book=id();val document=id()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(root,"source.db"),null).use{old->
            val schema=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/18.json").bufferedReader().use{it.readText()}).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO notes VALUES (?,1,'合成18版原件','原文',1234)",arrayOf(book))
            old.execSQL("INSERT INTO note_revisions VALUES (?,1,'合成18版原件','原文',1234)",arrayOf(book))
            old.execSQL("INSERT INTO notebook_workspace(noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision,coverKey,selectedPageId,pinned) VALUES (?,0,0,'','',0,NULL,500,707,0,0,'auto','',0)",arrayOf(book))
            old.execSQL("INSERT INTO notebook_pages(id,notebookId,position,world,paper,centerX,centerY,zoom,createdAfterId,trashedAt) VALUES (?,?,0,0,0,500,707,0,NULL,NULL)",arrayOf(book,book))
            old.execSQL("INSERT INTO document_sources VALUES (?,?,?,?,?)",arrayOf<Any>(document,book,original.sha256,1,original.size))
            storeOriginal(original.size,original.sha256,DocumentRepository.CHUNK,original::openStream){position,bytes->old.execSQL("INSERT INTO document_chunks VALUES (?,?,?)",arrayOf<Any>(document,position,bytes))}
            old.execSQL("INSERT INTO document_pages VALUES (?,?,0)",arrayOf(book,document));old.version=18
        }
        assertEquals(19,db.openHelper.readableDatabase.version);assertEquals(original.sha256,checkNotNull(DocumentRepository(db).read(book)).document.sha256)
        for(table in listOf("document_sources","image_sources")){
            val plan=db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT 1 FROM $table WHERE digest=?",arrayOf(original.sha256)).use{cursor->buildString{while(cursor.moveToNext())append(cursor.getString(3))}}
            assertTrue(plan,plan.contains("SEARCH")&&plan.contains("index_${table}_digest"))
        }
        val report=OriginalStorageMaintenance(db).reclaim();assertEquals(1,report.migratedOriginals);assertEquals(0L,count(db,"document_chunks"))
        DocumentRepository(db).openFile(checkNotNull(DocumentRepository(db).read(book)).document).use{assertEquals(original.sha256,OriginalBytes.file(it.file,original.size).sha256)}
    }
    private fun pdf(file:File,size:Int) {
        val offsets=mutableListOf<Long>();var position=0L
        file.outputStream().buffered().use{out->
            fun text(value:String){val bytes=value.toByteArray(Charsets.US_ASCII);out.write(bytes);position+=bytes.size}
            fun obj(body:String){offsets+=position;text("${offsets.size} 0 obj\n$body\nendobj\n")}
            text("%PDF-1.7\n");obj("<< /Type /Catalog /Pages 2 0 R >>");obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>")
            obj("<< /Length 0 >>\nstream\nendstream")
            offsets+=position;val padding=size-4096;text("5 0 obj\n<< /Length $padding >>\nstream\n")
            val block=ByteArray(65_536);var left=padding
            while(left>0){val n=minOf(left,block.size);out.write(block,0,n);position+=n;left-=n}
            text("\nendstream\nendobj\n");val xref=position;text("xref\n0 6\n0000000000 65535 f \n")
            offsets.forEach{text(String.format(Locale.ROOT,"%010d 00000 n \n",it))}
            val tail="\ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
            val fill=size-position-tail.length;check(fill>=2);text("%"+"x".repeat(fill.toInt()-2)+"\n");text(tail)
        };check(file.length()==size.toLong())
    }
}
