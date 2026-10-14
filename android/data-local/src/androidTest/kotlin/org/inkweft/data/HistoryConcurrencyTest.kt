// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HistoryConcurrencyTest {
    @Test fun largeLibraryAuditAllowsAuthorCommitAndCancellation()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="history-concurrency-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        val baseline=InstrumentationRegistry.getArguments().getString("historyBaseline")=="true"
        val testStarted=System.nanoTime()
        fun phase(value:String){
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","HISTORY_CONCURRENCY_PHASE phase=$value elapsedMs=${(System.nanoTime()-testStarted)/1_000_000.0}\n")
            })
        }
        suspend fun <T> timed(label:String,millis:Long,block:suspend()->T):T {
            phase("$label:start")
            try{return withTimeout(millis){block()}.also{phase("$label:complete")}}
            catch(failure:TimeoutCancellationException){phase("$label:timeout");throw failure}
        }
        try{coroutineScope{
            phase("fixture:start")
            val book=WorkspaceRepository(db).create("历史并发",false,PaperStyle.BLANK).id
            val source=UUID.randomUUID().toString();val card=UUID.randomUUID().toString()
            val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("合成来源","",listOf(stroke),false,PaperStyle.BLANK).encode()
            db.withTransaction{
                db.study().addCard(StudyCardRow(card,book,20_000,"固定卡片",""))
                repeat(20_000){i->val revision=i+1L
                    db.sourceVersions().insert(StudySourceRevisionRow(source,revision,book,book,0,10.0,10.0,30.0,30.0,stroke.id,bytes))
                    db.study().revision(StudyCardRevisionRow(card,revision,"固定卡片","",null))
                    db.sourceVersions().insert(StudyCardSourceSetRow(card,revision,StudySourceRefs.encode(listOf(StudySourceVersionRef(source,revision))),true))
                }
            }
            phase("fixture:complete")
            val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            val history=HistoryStorage(db){phase("paused-audit:$it");if(it=="after-roots"){entered.complete(Unit);check(release.await(15,TimeUnit.SECONDS))}}
            val audit=async(Dispatchers.IO){history.audit()}
            try{
            timed("paused-audit:roots",30_000){entered.await()}
            val started=System.nanoTime();val edit=async(Dispatchers.IO){NoteRepository(db).rename(RenameNote(UUID.randomUUID().toString(),book,1,"审计时仍可保存"))}
            delay(500);val committedWhilePaused=edit.isCompleted;release.countDown()
            timed("paused-audit:edit",10_000){edit.await()};val waitMs=(System.nanoTime()-started)/1_000_000.0
            val report=timed("paused-audit:complete",30_000){audit.await()};assertEquals(0L,report.detachedSources)
            if(!baseline)assertTrue("Author commit blocked by root decoding",committedWhilePaused)
            val times=mutableListOf<Double>();val unpausedStart=System.nanoTime()
            val scanning=async(Dispatchers.IO){HistoryStorage(db){phase("unpaused-audit:$it")}.audit()}
            repeat(30){i->val before=System.nanoTime();val note=db.notes().note(book)!!;NoteRepository(db).rename(RenameNote(UUID.randomUUID().toString(),book,note.revision,"保存 $i"));times+=(System.nanoTime()-before)/1_000_000.0}
            scanning.await();val unpausedMs=(System.nanoTime()-unpausedStart)/1_000_000.0
            val cancelEntered=CompletableDeferred<Unit>();val cancelRelease=CountDownLatch(1)
            val cancelled=launch(Dispatchers.IO){HistoryStorage(db){phase("cancel-audit:$it");if(it=="after-roots"){cancelEntered.complete(Unit);check(cancelRelease.await(15,TimeUnit.SECONDS))}}.audit()}
            try{
            timed("cancel-audit:roots",30_000){cancelEntered.await()};val cancelStart=System.nanoTime();cancelled.cancel();cancelRelease.countDown();timed("cancel-audit:join",5_000){cancelled.join()}
            assertEquals(20_000,db.sourceVersions().all().size)
            val sorted=times.sorted();val evidence=JSONObject().put("baseline",baseline).put("sourceVersions",20000).put("historicalCardRoots",20000)
                .put("committedWhileAuditPaused",committedWhilePaused).put("pausedProbeWindowMs",500).put("editCompletionIncludingProbeWaitMs",waitMs)
                .put("unpausedAuditAndThirtyEditsMs",unpausedMs).put("editMedianMs",sorted[15]).put("editP95Ms",sorted[28])
                .put("cancellationMs",(System.nanoTime()-cancelStart)/1_000_000.0).put("historyRetained",true)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","HISTORY_CONCURRENCY_EVIDENCE $evidence\n")})
            }finally{cancelRelease.countDown();withContext(NonCancellable){cancelled.cancelAndJoin()}}
            }finally{release.countDown();withContext(NonCancellable){audit.cancelAndJoin()}}
        }}finally{db.close();context.deleteDatabase(name)}
    }
}
