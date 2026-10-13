// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HistoryCollectionTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(block:suspend(NoteDatabase,String,String,String)->Unit)=runBlocking {
        val name="history-batches-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        try{
            val book=WorkspaceRepository(db).create("分批清理",false,PaperStyle.BLANK).id
            val source=UUID.randomUUID().toString()
            val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("合成来源","",listOf(stroke),false,PaperStyle.BLANK).encode()
            db.withTransaction{repeat(1281){i->db.sourceVersions().insert(StudySourceRevisionRow(source,i+1L,book,book,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}}
            block(db,name,book,source)
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun oneScanSurvivesRoomObserverRefreshAcrossSevenBatches()=fixture{db,_,_,source->
        db.withTransaction{repeat(257){i->db.objects().putPreview(ObjectPreviewRow(i.toString(16).padStart(64,'0'),byteArrayOf(1,2,3)))}}
        val notifications=AtomicInteger()
        val observer=object:InvalidationTracker.Observer("study_source_revisions","object_previews"){
            override fun onInvalidated(tables:Set<String>){notifications.incrementAndGet()}
        }
        db.invalidationTracker.addObserver(observer)
        try{
            // Install Room's triggers before taking the first collection guard.
            db.withTransaction{}
            var scans=0;var stats=0;var batches=0
            val report=HistoryStorage(db){phase->when(phase){
                "after-roots"->scans++
                "after-stats"->stats++
                "after-batch"->{batches++;db.invalidationTracker.refreshVersionsSync()}
            }}.collectDetached()
            assertFalse(report.collectionDeferred)
            assertEquals(1280L,report.detachedSources);assertEquals(257L,report.detachedPreviews)
            assertEquals(1,scans);assertEquals(1,stats);assertEquals(7,batches)
            assertTrue("Room observers must see committed collection",notifications.get()>0)
            assertNotNull(db.sourceVersions().get(source,1281))
            assertEquals(0L,HistoryStorage(db).audit().reclaimableBytes)
        }finally{db.invalidationTracker.removeObserver(observer)}
    }

    @Test fun sameRoomWriterAddingRootAfterFirstBatchDefersRemainingCandidates()=newRootBetweenBatches(false)
    @Test fun separateRoomWriterAddingRootAfterFirstBatchDefersRemainingCandidates()=newRootBetweenBatches(true)
    @Test fun sameRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot()=authorQueuedBeforeDeleteCommit(false)
    @Test fun separateRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot()=authorQueuedBeforeDeleteCommit(true)

    private fun authorQueuedBeforeDeleteCommit(separate:Boolean)=fixture{db,name,book,source->coroutineScope{
        val other=if(separate)NoteDatabase.open(context,name)else db
        val beforeCommit=CompletableDeferred<Unit>();val allowCommit=CountDownLatch(1)
        val afterCommit=CompletableDeferred<Unit>();val allowNextBatch=CountDownLatch(1)
        val authorEntered=CompletableDeferred<Unit>()
        val beforeBatches=AtomicInteger();val afterBatches=AtomicInteger()
        var collecting:Deferred<HistoryStorageReport>?=null;var saving:Deferred<SaveResult>?=null
        try{
            val existing=checkNotNull(NoteRepository(db).read(book))
            assertTrue(NoteRepository(db).save(SaveNote(UUID.randomUUID().toString(),book,existing.revision,
                existing.title,"Synthetic body preserved across queued rename")) is SaveResult.Committed)
            // Open the other helper and read the author before taking the collection guard.
            val initial=checkNotNull(NoteRepository(other).read(book))
            val protectedSource=checkNotNull(db.sourceVersions().get(source,300))
            val card=StudyCardRow(UUID.randomUUID().toString(),book,1,"Synthetic new live root","")
            val title="Synthetic author queued behind deletion"
            val work=async(Dispatchers.IO){HistoryStorage(db){phase->when(phase){
                "before-batch-commit"->if(beforeBatches.incrementAndGet()==1){
                    val sql=db.openHelper.writableDatabase
                    assertTrue(sql.inTransaction())
                    // Observe the real DELETE effects on this transaction's own connection.
                    sql.query("SELECT COUNT(*) FROM study_source_revisions WHERE sourceId=? AND revision<=256",arrayOf(source)).use{
                        assertTrue(it.moveToFirst());assertEquals(0L,it.getLong(0))
                    }
                    sql.query("SELECT COUNT(*) FROM study_source_revisions WHERE sourceId=?",arrayOf(source)).use{
                        assertTrue(it.moveToFirst());assertEquals(1025L,it.getLong(0))
                    }
                    beforeCommit.complete(Unit);check(allowCommit.await(10,TimeUnit.SECONDS))
                }
                "after-batch"->if(afterBatches.incrementAndGet()==1){
                    afterCommit.complete(Unit);check(allowNextBatch.await(10,TimeUnit.SECONDS))
                }
            }}.collectDetached()}
            collecting=work
            withTimeout(10_000){beforeCommit.await()}
            // Start from the coordinator, never from the GC hook/transaction context.
            // UNDISPATCHED reaches the writer suspension before returning to the test.
            val author=async(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED){other.withTransaction{
                authorEntered.complete(Unit)
                val saved=NoteRepository(other).rename(RenameNote(UUID.randomUUID().toString(),book,initial.revision,title))
                assertTrue(saved is SaveResult.Committed)
                assertEquals(initial.revision+1,(saved as SaveResult.Committed).note.revision)
                other.study().addCard(card);other.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
                other.sourceVersions().insert(StudyCardSourceSetRow(card.id,1,
                    StudySourceRefs.encode(listOf(StudySourceVersionRef(source,300))),true))
                saved
            }}
            saving=author
            assertFalse("Author must wait while the actual deletion transaction is held",author.isCompleted)
            assertFalse("Author transaction must not enter the held GC writer",authorEntered.isCompleted)
            allowCommit.countDown()
            withTimeout(10_000){afterCommit.await()}
            val saved=withTimeout(2_000){author.await()}
            assertTrue(saved is SaveResult.Committed);assertTrue(authorEntered.isCompleted)
            assertEquals(initial.revision+1,(saved as SaveResult.Committed).note.revision)
            assertNull(db.sourceVersions().get(source,256));assertNotNull(db.sourceVersions().get(source,257))
            assertEquals(listOf(StudySourceVersionRef(source,300)),checkNotNull(db.sourceVersions().set(card.id,1)).refs())
            allowNextBatch.countDown()
            val report=withTimeout(10_000){work.await()}
            assertTrue(report.collectionDeferred);assertEquals(256L,report.detachedSources)
            assertEquals(1,beforeBatches.get());assertEquals(1,afterBatches.get())
            assertEquals((257L..1281L).toList(),db.sourceVersions().all().map{it.revision})
            val finished=HistoryStorage(db).collectDetached()
            assertFalse(finished.collectionDeferred);assertEquals(1023L,finished.detachedSources)
            assertEquals(listOf(300L,1281L),db.sourceVersions().all().map{it.revision})
            assertArrayEquals(protectedSource.snapshot,checkNotNull(db.sourceVersions().get(source,300)).snapshot)
            val finalNote=checkNotNull(NoteRepository(db).read(book))
            assertEquals(initial.revision+1,finalNote.revision);assertEquals(title,finalNote.title);assertEquals(initial.text,finalNote.text)
            val evidence=JSONObject().put("connection",if(separate)"separate-room" else "same-room")
                .put("artificialGates",true).put("performanceMeasurement",false)
                .put("firstDeleted",report.detachedSources).put("firstDeferred",report.collectionDeferred)
                .put("finishDeleted",finished.detachedSources).put("authorCommitted",true)
                .put("initialRevision",initial.revision).put("finalRevision",finalNote.revision)
                .put("retainedRevisions",JSONArray(listOf(300L,1281L))).put("contentVerified",true)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","HISTORY_DELETE_WRITER_CORRECTNESS $evidence\n")
            })
        }finally{
            allowCommit.countDown();allowNextBatch.countDown()
            try{withContext(NonCancellable){
                collecting?.cancel();saving?.cancel()
                collecting?.join();saving?.join()
            }}finally{if(separate)other.close()}
        }
    }}

    @Test fun overlappingRootSetsAcrossFullAndPartialBatchesRetainEveryVersion()=fixture{db,_,book,source->
        val card=UUID.randomUUID().toString()
        db.withTransaction{
            db.study().addCard(StudyCardRow(card,book,2,"跨批引用",""))
            for((revision,range) in listOf(1L to (1L..129L),2L to (128L..257L))){
                db.study().revision(StudyCardRevisionRow(card,revision,"跨批引用","",null))
                db.sourceVersions().insert(StudyCardSourceSetRow(card,revision,
                    StudySourceRefs.encode(range.map{StudySourceVersionRef(source,it)}),true))
            }
        }
        val report=HistoryStorage(db).collectDetached()
        assertFalse(report.collectionDeferred);assertEquals(1023L,report.detachedSources)
        assertEquals((1L..257L).toList()+1281L,db.sourceVersions().all().map{it.revision})
    }

    @Test fun malformedTransformAfterAFullRootBatchAbortsBeforeAnyDeletion()=fixture{db,_,book,source->
        val card=UUID.randomUUID().toString()
        db.withTransaction{
            db.study().addCard(StudyCardRow(card,book,1,"损坏引用",""))
            db.study().revision(StudyCardRevisionRow(card,1,"损坏引用","",null))
            db.sourceVersions().insert(StudyCardSourceSetRow(card,1,
                StudySourceRefs.encode((1L..129L).map{StudySourceVersionRef(source,it)}),true))
            // Transform payloads are decoded after all card source sets, so one complete
            // root batch has been inserted before this deliberately damaged payload.
            db.cardTransforms().insert(CardTransformOperationRow(UUID.randomUUID().toString(),book,
                "a".repeat(64),ByteArray(4),"b".repeat(64)))
        }
        try{HistoryStorage(db).collectDetached();fail("malformed historical root accepted")}catch(_:IllegalArgumentException){}
        assertEquals(1281,db.sourceVersions().all().size)
        assertNotNull(db.sourceVersions().get(source,130))
    }

    private fun newRootBetweenBatches(separate:Boolean)=fixture{db,name,book,source->coroutineScope{
        val other=if(separate)NoteDatabase.open(context,name)else db
        // Open the second helper before the collection snapshot.
        other.notes().note(book)
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);var batches=0
        val collecting=async(Dispatchers.IO){HistoryStorage(db){if(it=="after-batch"&&++batches==1){
            entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))
        }}.collectDetached()}
        try{
            withTimeout(10_000){entered.await()}
            assertNull(db.sourceVersions().get(source,256));assertNotNull(db.sourceVersions().get(source,300))
            withTimeout(2_000){other.withTransaction{
                val card=StudyCardRow(UUID.randomUUID().toString(),book,1,"新增活引用","")
                other.study().addCard(card);other.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
                other.sourceVersions().insert(StudyCardSourceSetRow(card.id,1,StudySourceRefs.encode(listOf(StudySourceVersionRef(source,300))),true))
            }}
            release.countDown();val report=collecting.await()
            assertTrue(report.collectionDeferred);assertEquals(256L,report.detachedSources)
            assertNotNull(db.sourceVersions().get(source,300));assertNotNull(db.sourceVersions().get(source,1281))
            assertEquals(1023L,HistoryStorage(db).collectDetached().detachedSources)
            assertNotNull(db.sourceVersions().get(source,300))
        }finally{release.countDown();collecting.cancelAndJoin();if(separate)other.close()}
    }}

    @Test fun interruptedBatchRollsBackAndReopenedCollectionResumes()=fixture{db,name,_,source->
        var batches=0
        try{
            HistoryStorage(db){if(it=="before-batch-commit"&&++batches==2)throw CancellationException("synthetic interruption")}.collectDetached()
            fail("interruption ignored")
        }catch(_:CancellationException){}
        assertNull(db.sourceVersions().get(source,256));assertNotNull(db.sourceVersions().get(source,257))
        db.close()
        val reopened=NoteDatabase.open(context,name)
        try{
            assertEquals(1024L,HistoryStorage(reopened).collectDetached().detachedSources)
            assertNotNull(reopened.sourceVersions().get(source,1281))
            assertEquals(1,reopened.sourceVersions().all().size)
        }finally{reopened.close()}
    }

    @Test fun missingRoomGuardLogAbortsWithoutDeletingCandidates()=fixture{db,_,_,source->
        db.withTransaction{db.openHelper.writableDatabase.execSQL("DROP TABLE room_table_modification_log")}
        try{HistoryStorage(db).collectDetached();fail("missing writer guard accepted")}catch(_:android.database.SQLException){}
        assertNotNull(db.sourceVersions().get(source,1));assertEquals(1281,db.sourceVersions().all().size)
    }
}
