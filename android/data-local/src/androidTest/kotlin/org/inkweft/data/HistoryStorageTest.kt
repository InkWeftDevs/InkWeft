// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryStorageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val name="history-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("引用闭包",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun source(db:NoteDatabase,book:String,source:String=id(),revision:Long=1):StudySourceRevisionRow {
        var row=db.ink().strokes(book).firstOrNull()
        if(row==null){
            val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            check(InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            row=db.ink().strokes(book).single()
        }
        val stroke=InkStrokeCodec.decode(row.payload)
        val bytes=InkPageFile("来源","",listOf(stroke),false,PaperStyle.BLANK).encode()
        return StudySourceRevisionRow(source,revision,book,book,1,10.0,10.0,30.0,30.0,stroke.id,bytes).also{db.sourceVersions().insert(it)}
    }
    @Test fun newReferenceBetweenAuditAndCommitDefersDeletion()=fixture{db,book->coroutineScope{
        val s=source(db,book);source(db,book,s.sourceId,2)
        db.objects().putPreview(ObjectPreviewRow("c".repeat(64),byteArrayOf(1)))
        val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
        val collecting=async(Dispatchers.IO){HistoryStorage(db){if(it=="after-roots"){entered.complete(Unit);check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}}.collectDetached()}
        try{
            withTimeout(5_000){entered.await()}
            withTimeout(2_000){db.withTransaction{
                val card=StudyCardRow(id(),book,1,"新增引用","");db.study().addCard(card);db.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
                db.sourceVersions().insert(StudyCardSourceSetRow(card.id,1,StudySourceRefs.encode(listOf(s.ref())),true))
            }}
            release.countDown();val report=collecting.await();assertTrue(report.collectionDeferred);assertEquals(0L,report.detachedSources);assertEquals(0L,report.detachedPreviews)
            assertNotNull(db.sourceVersions().get(s.sourceId,1));assertNotNull(db.objects().preview("c".repeat(64)))
            assertEquals(1L,HistoryStorage(db).collectDetached().detachedPreviews)
            assertNotNull(db.sourceVersions().get(s.sourceId,1))
        }finally{release.countDown();collecting.cancelAndJoin()}
    }}
    @Test fun detachedOldSourcesCollectedLatestSequenceAndAllReceiptsSurviveBackup()=fixture{db,book->
        val first=source(db,book);source(db,book,first.sourceId,2)
        val before=db.notes().note(book)!!;val rename=RenameNote(id(),book,before.revision,"新标题")
        val saved=NoteRepository(db).rename(rename)
        val report=HistoryStorage(db).audit();assertEquals(1L,report.detachedSources)
        assertEquals(1L,HistoryStorage(db).collectDetached().detachedSources)
        assertNull(db.sourceVersions().get(first.sourceId,1));assertNotNull(db.sourceVersions().get(first.sourceId,2))
        assertEquals(saved,NoteRepository(db).rename(rename))
        assertEquals(0L,HistoryStorage(db).collectDetached().detachedSources)
        LibraryBackupRepository(context,db).snapshot().use{s->s.file.inputStream().use{input->LibraryBackupRepository(context,db).inspect(input).use{assertEquals(1,it.notes)}}}
    }
    @Test fun historicalCardSetsRetainEvenTrashedCardsAndOldSources()=fixture{db,book->
        val s=source(db,book);source(db,book,s.sourceId,2)
        val card=StudyCardRow(id(),book,2,"旧卡","内容",1)
        db.study().addCard(card)
        for(rev in 1L..2L)db.study().revision(StudyCardRevisionRow(card.id,rev,card.title,card.body,card.trashedAt))
        db.sourceVersions().insert(StudyCardSourceSetRow(card.id,1,StudySourceRefs.encode(listOf(s.ref())),true))
        db.sourceVersions().insert(StudyCardSourceSetRow(card.id,2,"",true))
        assertEquals(0L,HistoryStorage(db).collectDetached().detachedSources)
        assertNotNull(db.sourceVersions().get(s.sourceId,1))
    }
    @Test fun transformAndRecallPayloadsRetainIndependentSourceRoots()=fixture{db,book->
        val transformed=source(db,book);source(db,book,transformed.sourceId,2)
        val recalled=source(db,book);source(db,book,recalled.sourceId,2)
        val card=StudyCardRow(id(),book,1,"卡片","ab");db.study().addCard(card)
        db.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
        val input=CardTransformCard(card.id,1,card.title,card.body,KnowledgeData.CardPresentation(card.id),listOf(transformed.ref()))
        val targets=listOf("a","b").map{CardTransformTarget(id(),"拆分",it,"",input.sources)}
        val plan=CardTransformPlan(id(),book,CardTransformKind.SPLIT,"a".repeat(64),listOf(input),targets)
        db.cardTransforms().insert(CardTransformOperationRow(plan.operationId,book,plan.digest(),CardTransformCodec.encode(plan),"b".repeat(64)))
        val q=id();val payload=KnowledgeCodec.encode(KnowledgeData.Question(card.id,"复习"))
        db.knowledge().insert(KnowledgeRow(q,book,1,payload))
        val spec=RecallQuestionSpec(q,1,card.id,1,"复习",sourceRefs=listOf(recalled.ref()))
        db.recall().insert(RecallQuestionRow(q,book,1,0,RecallCodec.spec(spec)))
        db.recall().insert(RecallQuestionVersionRow(q,1,book,RecallCodec.spec(spec)))
        assertEquals(0L,HistoryStorage(db).collectDetached().detachedSources)
        assertNotNull(db.sourceVersions().get(transformed.sourceId,1));assertNotNull(db.sourceVersions().get(recalled.sourceId,1))
    }
    @Test fun invalidReferenceAbortsWholeCollectionIncludingOrphanPreview()=fixture{db,book->
        val s=source(db,book);source(db,book,s.sourceId,2)
        val card=StudyCardRow(id(),book,1,"卡片","");db.study().addCard(card)
        db.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
        db.sourceVersions().insert(StudyCardSourceSetRow(card.id,1,"${id()}@1",true))
        db.objects().putPreview(ObjectPreviewRow("a".repeat(64),byteArrayOf(1)))
        try{HistoryStorage(db).collectDetached();fail("missing root accepted")}catch(_:IllegalArgumentException){}
        assertNotNull(db.sourceVersions().get(s.sourceId,1));assertNotNull(db.objects().preview("a".repeat(64)))
        assertFalse(db.openHelper.readableDatabase.query("SELECT 1 FROM sqlite_temp_master WHERE name='history_source_roots'").use{it.moveToFirst()})
    }
    @Test fun shadowPayloadProtectsSourcesAndPreviewsUntilItsClosureIsKnown()=fixture{db,book->
        val s=source(db,book);source(db,book,s.sourceId,2)
        db.openHelper.writableDatabase.execSQL("CREATE TABLE shadow_versions(entity TEXT,revision TEXT,value TEXT)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO shadow_versions VALUES('source','1','{}')")
        db.objects().putPreview(ObjectPreviewRow("b".repeat(64),byteArrayOf(1,2)))
        val r=HistoryStorage(db).collectDetached();assertTrue(r.shadowProtected);assertEquals(0L,r.detachedSources);assertEquals(0L,r.detachedPreviews)
        assertNotNull(db.sourceVersions().get(s.sourceId,1));assertNotNull(db.objects().preview("b".repeat(64)))
    }
    @Test fun repeatedCollectionUsesMetadataForThousandsOfVersions()=fixture{db,book->
        db.withTransaction{repeat(1000){val s=source(db,book);source(db,book,s.sourceId,2)}}
        assertEquals(1000L,HistoryStorage(db).collectDetached().detachedSources)
        assertEquals(0L,HistoryStorage(db).audit().detachedSources)
        assertEquals(1000,db.sourceVersions().all().size)
    }
}
