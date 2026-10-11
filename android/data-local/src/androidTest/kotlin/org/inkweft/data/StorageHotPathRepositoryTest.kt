// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** Isolated Room/platform regression checks; no application/user database is opened. */
class StorageHotPathRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking {
        val name="storage-hot-path-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    private fun pdf():ByteArray {
        val document=PdfDocument()
        return try{
            repeat(2){i->val page=document.startPage(PdfDocument.PageInfo.Builder(200,300,i+1).create());document.finishPage(page)}
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        }finally{document.close()}
    }
    private fun preview():String {
        val bitmap=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888)
        return try{Base64.getEncoder().encodeToString(ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}.toByteArray())}
        finally{bitmap.recycle()}
    }

    @Test fun sourceOwnershipUsesIdsAndStillRejectsForeignOrRepeatedReferences()=fixture{db->
        val book=WorkspaceRepository(db).create("来源ID合成检查",false,PaperStyle.BLANK).id
        val stroke=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0,.4f)))
        assertTrue(InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
        // Poison this disposable row beyond CursorWindow/codec limits: identity checks must not fetch it.
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=zeroblob(3000000) WHERE id=?",arrayOf(stroke.id))
        val repo=PageObjectRepository(db);val text=PageObject(id(),PageObjectKind.TEXT,text="保留来源",sourceStrokeIds=listOf(stroke.id))
        assertEquals(1L,repo.save(book,0,id(),listOf(text),expectedInk=1))
        assertEquals(listOf(stroke.id),repo.read(book).objects.single().sourceStrokeIds)
        for(objects in listOf(listOf(text.copy(sourceStrokeIds=listOf(id()))),listOf(text,text.copy(id=id())))){
            assertTrue(runCatching{repo.save(book,1,id(),objects,expectedInk=1)}.isFailure)
            assertEquals(ObjectSnapshot(1,listOf(text)),repo.read(book))
        }
        assertTrue(runCatching{InkRepository(db).read(book)}.isFailure)
    }

    @Test fun movingImagesPreservesCasAndDamagedNewOrStoredPreviewsAreRejected()=fixture{db->
        val book=WorkspaceRepository(db).create("图片校验合成检查",false,PaperStyle.BLANK).id
        val repo=PageObjectRepository(db);val original=PageObject(id(),PageObjectKind.IMAGE,image=preview())
        assertEquals(1L,repo.save(book,0,id(),listOf(original)))
        db.validatedImagePreviews.validate(listOf(original)){fail("Saved previews must share validation with transient repositories")}
        val moved=original.copy(x=140f,width=440f)
        val batch=InkRepository(db).saveCanvasBatch(emptyList(),listOf(ObjectWrite(book,1,id(),listOf(moved))))
        assertEquals(listOf(2L),batch.objects);assertNull(batch.failure)
        assertTrue(runCatching{repo.save(book,1,id(),listOf(original))}.isFailure)
        val damaged=moved.copy(image=Base64.getEncoder().encodeToString(byteArrayOf(-1,-40,0,1,2,3)))
        assertTrue(runCatching{repo.save(book,2,id(),listOf(damaged))}.isFailure)
        assertEquals(ObjectSnapshot(2,listOf(moved)),repo.read(book))
        val poisoned=PageObjectStore.record(book,0,damaged)
        db.withTransaction{PageObjectStore(db).write(book,3,PageObjectStore.prepare(book,listOf(damaged)))}
        assertTrue(runCatching{PageObjectRepository(db).save(book,3,id(),listOf(damaged.copy(x=150f)))}.isFailure)
        assertArrayEquals(poisoned.payload,db.objects().records(book).single().payload)
    }

    @Test fun sharedPdfIsValidatedBeforeTransactionAndRehashedOnceDuringAtomicImport()=fixture{db->
        val bytes=pdf();val identity=OriginalBytes.memory(bytes);val transactionStates=mutableListOf<Boolean>()
        val source=PdfDocumentSource.fromStream(OriginalBytes.stream(identity.size,identity.sha256){transactionStates+=db.inTransaction();bytes.inputStream()},2)
        ContentTransfer.document("事务外验证合成PDF",source,source.sha256).use{prepared->
            val command=ImportNotebook(id(),id(),prepared.sha256)
            val repo=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_IMPORT_VALIDATION)assertFalse(db.inTransaction())}
            val note=repo.import(command,prepared)
            assertEquals(listOf(false,true),transactionStates)
            assertEquals(2,db.pages().list(note.id).size)
            assertEquals(bytes.size.toLong(),db.documents().totalBytes())
            assertEquals(note,repo.import(command,prepared))
            assertEquals(listOf(false,true),transactionStates)
            assertArrayEquals(bytes,DocumentRepository(db).read(note.id)!!.document.bytes())
        }
    }

    @Test fun sourceChangedAfterValidationRollsBackTheWholeImportAndReceipt()=fixture{db->
        val bytes=pdf();val identity=OriginalBytes.memory(bytes);var current=bytes
        val source=PdfDocumentSource.fromStream(OriginalBytes.stream(identity.size,identity.sha256){current.inputStream()},2)
        ContentTransfer.document("变化来源合成PDF",source,source.sha256).use{prepared->
            val command=ImportNotebook(id(),id(),prepared.sha256)
            val repo=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_IMPORT_VALIDATION){
                assertFalse(db.inTransaction());current=bytes.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            }}
            val failure=runCatching{repo.import(command,prepared)}.exceptionOrNull()
            assertEquals("ORIGINAL_CHECKSUM_CHANGED",failure?.message)
            assertNull(db.notes().note(command.destinationId));assertNull(db.libraryContent().receipt(command.commandId))
            assertTrue(db.pages().list(command.destinationId).isEmpty());assertEquals(0L,db.documents().totalBytes())
            current=bytes
            assertEquals(command.destinationId,LibraryContentRepository(db).import(command,prepared).id)
        }
    }

    @Test fun backupSyncReleasesTheWriterWithoutChangingItsCapturedSnapshot()=fixture{db->
        val note=WorkspaceRepository(db).create("快照原标题",false,PaperStyle.BLANK)
        val backup=LibraryBackupRepository(context,db){if(it==LibraryBackupRepository.BackupFault.BEFORE_SNAPSHOT_SYNC){
            assertFalse(db.inTransaction())
            runBlocking{withTimeout(5000){NoteRepository(db).save(SaveNote(id(),note.id,1,"同步时新标题","新正文"))}}
        }}
        backup.snapshot().use{snapshot->
            val reader=LibraryBackupRepository(context,db)
            snapshot.file.inputStream().use{reader.inspect(it)}.use{preview->
                assertEquals("快照原标题",preview.stage.notes().note(note.id)!!.title)
            }
        }
        assertEquals("同步时新标题",db.notes().note(note.id)!!.title)
    }

    @Test fun failedFinalSnapshotSyncDoesNotPublishOrLeaveItsFile()=fixture{db->
        val note=WorkspaceRepository(db).create("同步失败保持原库",false,PaperStyle.BLANK)
        val cache=File(context.cacheDir,"snapshot-sync-${id()}").apply{check(mkdir())}
        val isolated=object:ContextWrapper(context){override fun getCacheDir()=cache}
        try{
            val backup=LibraryBackupRepository(isolated,db){if(it==LibraryBackupRepository.BackupFault.BEFORE_SNAPSHOT_SYNC)throw IOException("synthetic fsync failure")}
            assertTrue(runCatching{backup.snapshot()}.exceptionOrNull() is IOException)
            assertTrue(File(cache,"library-backup").listFiles().orEmpty().isEmpty())
            assertEquals(note,NoteRepository(db).read(note.id))
        }finally{check(cache.deleteRecursively())}
    }
}
