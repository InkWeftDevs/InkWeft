// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
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
