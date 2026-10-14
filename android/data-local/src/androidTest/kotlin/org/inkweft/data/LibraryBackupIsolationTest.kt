// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LibraryBackupIsolationTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(Context,NoteDatabase,File)->Unit)=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val root=File(app.cacheDir,"backup-isolation-${id()}").apply{check(mkdir())}
        val context=object:ContextWrapper(app){override fun getCacheDir()=root}
        val db=NoteDatabase.open(context,File(root,"author.db").absolutePath)
        try{block(context,db,root)}finally{
            db.close();check(root.canonicalFile.parentFile==app.cacheDir.canonicalFile);root.deleteRecursively()
        }
    }

    @Test fun walSnapshotKeepsOneGenerationWhileAuthorTransactionCommits()=fixture{context,db,_->coroutineScope{
        val note=WorkspaceRepository(db).create("快照前 · WAL",false,PaperStyle.GRID)
        val sql=db.openHelper.writableDatabase
        sql.query("PRAGMA wal_autocheckpoint=0").close()
        assertTrue(File(sql.path+"-wal").length()>0)
        val writing=CompletableDeferred<Unit>();val commit=CompletableDeferred<Unit>()
        val copied=CompletableDeferred<Unit>();val finish=CountDownLatch(1)
        val writer=async(Dispatchers.IO){db.withTransaction{
            NoteRepository(db).save(SaveNote(id(),note.id,1,"快照后的作者编辑","新正文"))
            writing.complete(Unit);commit.await()
        }}
        writing.await()
        val backup=async(Dispatchers.IO){LibraryBackupRepository(context,db){fault->
            if(fault==LibraryBackupRepository.BackupFault.AFTER_ISOLATED_COPY){
                copied.complete(Unit);check(finish.await(10,TimeUnit.SECONDS))
            }
        }.snapshot()}
        try{
            // VACUUM INTO must finish even while an independent author holds a write transaction.
            withTimeout(10_000){copied.await()}
            val started=System.nanoTime();commit.complete(Unit)
            withTimeout(2_000){writer.await()}
            val commitMs=(System.nanoTime()-started)/1_000_000
            val added=withTimeout(2_000){WorkspaceRepository(db).create("快照后新笔记",false,PaperStyle.BLANK)}
            assertEquals("快照后的作者编辑",db.notes().note(note.id)!!.title)
            finish.countDown()
            withTimeout(10_000){backup.await()}.use{snapshot->
                val repo=LibraryBackupRepository(context,db)
                snapshot.file.inputStream().use{repo.inspect(it)}.use{preview->
                    assertEquals(1,preview.notes)
                    assertEquals(note.title,preview.stage.notes().note(note.id)!!.title)
                    assertEquals(1L,preview.stage.notes().note(note.id)!!.revision)
                    assertNull(preview.stage.notes().note(added.id))
                    assertNull(preview.stage.notes().revision(note.id,2))
                    println("BACKUP_ISOLATION committedWhileSnapshotPausedMs=$commitMs snapshotNotes=1 authorNotes=2 walIncluded=true singleGeneration=true")
                }
            }
        }finally{
            commit.complete(Unit);finish.countDown()
            withContext(NonCancellable){writer.cancelAndJoin();backup.cancelAndJoin();if(!backup.isCancelled)runCatching{backup.await().close()}}
        }
    }}

    @Test fun exactPreflightIncludesRepeatedOriginalsAndLeavesSharedFilesOwnedByAuthor()=fixture{context,db,root->
        val bytes=ByteArrayOutputStream().also{out->
            val pdf=PdfDocument()
            try{pdf.startPage(PdfDocument.PageInfo.Builder(64,64,1).create()).also{pdf.finishPage(it)};pdf.writeTo(out)}
            finally{pdf.close()}
        }.toByteArray()
        val hash=ContentTransfer.hash(bytes)
        db.withTransaction{
            db.originalStore.publish(bytes.size,hash){it.write(bytes)}
            repeat(3){
                val note=WorkspaceRepository(db).create("共享原件 · 第${it+1}本",false,PaperStyle.BLANK)
                val document=id();db.documents().insertSource(DocumentSourceRow(document,note.id,hash,1,bytes.size))
                db.documents().insertPage(DocumentPageRow(note.id,document,0))
            }
        }
        val repo=LibraryBackupRepository(context,db)
        val estimate=repo.archiveBytes(db.openHelper.readableDatabase)
        repo.snapshot().use{snapshot->
            assertEquals(estimate,snapshot.summary.bytes);assertEquals(estimate,snapshot.file.length())
            snapshot.file.inputStream().use{repo.inspect(it)}.use{assertEquals(3,it.notes)}
        }
        assertEquals(bytes.size.toLong(),db.originalStore.diskBytes())
        assertArrayEquals(bytes,db.originalStore.acquire(bytes.size,hash).use{it.file.readBytes()})
        assertTrue(File(root,"library-backup").listFiles().orEmpty().isEmpty())
        println("BACKUP_PREFLIGHT originalPhysicalBytes=${bytes.size} references=3 exactArchiveBytes=$estimate sharedOriginalRetained=true")
    }

    @Test fun authorCommitContinuesWhilePrivateValidationTransactionIsPaused()=fixture{context,db,root->coroutineScope{
        val note=WorkspaceRepository(db).create("验证前的作者版次",false,PaperStyle.BLANK)
        val started=System.nanoTime();var stage="fixture-note-complete"
        val lastFault=java.util.concurrent.atomic.AtomicReference("none")
        fun phase(value:String){
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","BACKUP_VALIDATION_ISOLATION_PHASE phase=$value elapsedMs=${(System.nanoTime()-started)/1_000_000.0}\n")
            })
        }
        phase(stage)
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val backup=async(Dispatchers.IO){LibraryBackupRepository(context,db){fault->
            lastFault.set(fault.name);phase("snapshot:${fault.name}")
            // This hook runs after the isolated Room transaction has acquired its writer.
            if(fault==LibraryBackupRepository.BackupFault.BEFORE_SNAPSHOT_VALIDATE){
                entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))
            }
        }.snapshot()}
        try{
            stage="await-validation-hook";phase("$stage:start")
            withTimeout(10_000){entered.await()}
            phase("$stage:complete")
            stage="author-save";phase("$stage:start")
            withTimeout(2_000){NoteRepository(db).save(SaveNote(id(),note.id,1,"验证期间已保存","独立作者事务"))}
            phase("$stage:complete")
            assertEquals("验证期间已保存",db.notes().note(note.id)!!.title)
            release.countDown()
            stage="await-snapshot";phase("$stage:start")
            withTimeout(10_000){backup.await()}.use{snapshot->
                phase("$stage:complete")
                stage="inspect";phase("$stage:start")
                snapshot.file.inputStream().use{LibraryBackupRepository(context,db).inspect(it)}.use{preview->
                    assertEquals(note.title,preview.stage.notes().note(note.id)!!.title)
                    assertEquals(1L,preview.stage.notes().note(note.id)!!.revision)
                    assertNull(preview.stage.notes().revision(note.id,2))
                }
                phase("$stage:complete")
            }
            stage="scratch-cleanup-assertion"
            assertTrue(File(root,"library-backup").listFiles().orEmpty().isEmpty())
            phase("complete")
        }catch(failure:Throwable){
            runCatching{phase("failure currentStage=$stage lastSnapshotFault=${lastFault.get()} exception=${failure.javaClass.simpleName}")}
                .onFailure(failure::addSuppressed)
            throw failure
        }finally{
            release.countDown()
            withContext(NonCancellable){backup.cancelAndJoin();if(!backup.isCancelled)runCatching{backup.await().close()}}
        }
    }}

    @Test fun cloudLimitAndSpaceRejectBeforeIsolatedCopyOrArchive()=fixture{context,db,root->
        // Deliberately metadata-only: rejection must happen before attempting to open/copy 900 MB.
        val hash="a".repeat(64)
        repeat(3){
            val note=WorkspaceRepository(db).create("容量预检 $it",false,PaperStyle.BLANK)
            db.documents().insertSource(DocumentSourceRow(id(),note.id,hash,1,300_000_000))
        }
        var copied=false
        val repo=LibraryBackupRepository(context,db){if(it==LibraryBackupRepository.BackupFault.BEFORE_ISOLATED_COPY)copied=true}
        val estimated=repo.archiveBytes(db.openHelper.readableDatabase)
        assertTrue(estimated>900_000_000L)
        try{repo.snapshot(500L*1024*1024).close();fail("Cloud limit was not checked")}
        catch(failure:IllegalArgumentException){assertEquals("BACKUP_TOO_LARGE",failure.message)}
        assertFalse(copied);assertTrue(File(root,"library-backup").listFiles().orEmpty().isEmpty())
        try{repo.snapshot(additionalBytes={root.usableSpace}).close();fail("Space was not checked")}
        catch(failure:IllegalArgumentException){assertEquals("BACKUP_LOW_SPACE",failure.message)}
        assertFalse(copied);assertTrue(File(root,"library-backup").listFiles().orEmpty().isEmpty())
        assertEquals(900_000_000L,db.documents().totalBytes())
        println("BACKUP_PREFLIGHT shared300MBReferences=3 estimatedArchiveBytes=$estimated cloudRejectedBeforeCopy=true lowSpaceRejectedBeforeCopy=true authorMetadataRetained=true")
    }

    @Test fun isolatedCopyFailureAndCancellationRemoveOnlyPrivateFiles()=fixture{context,db,root->
        val note=WorkspaceRepository(db).create("隔离副本失败保留作者",false,PaperStyle.BLANK)
        for(cancel in listOf(false,true)){
            val repo=LibraryBackupRepository(context,db){if(it==LibraryBackupRepository.BackupFault.AFTER_ISOLATED_COPY){
                if(cancel)throw CancellationException("synthetic") else error("synthetic")
            }}
            val failure=runCatching{repo.snapshot().close()}.exceptionOrNull()
            assertNotNull(failure)
            if(cancel)assertTrue(failure is CancellationException)else assertTrue(failure is IllegalStateException)
            assertEquals(note.title,db.notes().note(note.id)!!.title)
            assertTrue(File(root,"library-backup").listFiles().orEmpty().isEmpty())
        }
    }
}
