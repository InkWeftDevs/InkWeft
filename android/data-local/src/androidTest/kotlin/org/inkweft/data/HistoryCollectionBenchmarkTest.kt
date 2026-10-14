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

/** Copy this unchanged onto a128bb07 for a device-matched comparison.
 * -e historyScanBaseline true records the old repeated scans without the new scan-count assertion.
 * Reported times are device measurements only when this instrumentation test actually runs. */
class HistoryCollectionBenchmarkTest {
    @Test fun collectLargeGarbageWithRootsAndConcurrentAuthorSaves()=runBlocking{coroutineScope{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="history-collection-probe-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        val baseline=InstrumentationRegistry.getArguments().getString("historyScanBaseline")=="true"
        try{
            val book=WorkspaceRepository(db).create("垃圾清理探针",false,PaperStyle.BLANK).id
            val source=UUID.randomUUID().toString();val card=UUID.randomUUID().toString()
            val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("合成来源","",listOf(stroke),false,PaperStyle.BLANK).encode()
            val roots=2000;val garbage=4096
            db.withTransaction{
                db.study().addCard(StudyCardRow(card,book,roots.toLong(),"历史根",""))
                repeat(roots+garbage+1){i->db.sourceVersions().insert(StudySourceRevisionRow(source,i+1L,book,book,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}
                repeat(roots){i->val revision=i+1L
                    db.study().revision(StudyCardRevisionRow(card,revision,"历史根","",null))
                    db.sourceVersions().insert(StudyCardSourceSetRow(card,revision,StudySourceRefs.encode(listOf(StudySourceVersionRef(source,revision))),true))
                }
            }
            val notifications=AtomicInteger()
            val observer=object:InvalidationTracker.Observer("study_source_revisions"){
                override fun onInvalidated(tables:Set<String>){notifications.incrementAndGet()}
            }
            db.invalidationTracker.addObserver(observer);db.withTransaction{}
            try{
                val scans=AtomicInteger();val before=System.nanoTime()
                val report=HistoryStorage(db){if(it=="after-roots")scans.incrementAndGet()}.collectDetached()
                val collectionMs=(System.nanoTime()-before)/1_000_000.0
                assertFalse(report.collectionDeferred);assertEquals(garbage.toLong(),report.detachedSources)
                if(!baseline)assertEquals(1,scans.get())
                assertEquals(roots+1,db.sourceVersions().all().size)

                // Restore the same detached identities, then measure saves while a read scan
                // is deliberately paused. Every timed save excludes the probe's pause wait.
                db.withTransaction{repeat(garbage){i->db.sourceVersions().insert(StudySourceRevisionRow(source,roots+i+1L,book,book,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}}
                val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
                val concurrentScans=AtomicInteger()
                val collecting=async(Dispatchers.IO){HistoryStorage(db){if(it=="after-roots"){
                    concurrentScans.incrementAndGet();entered.complete(Unit);check(release.await(30,TimeUnit.SECONDS))
                }}.collectDetached()}
                val saves=mutableListOf<Double>()
                try{
                    withTimeout(30_000){entered.await()}
                    repeat(30){i->
                        val note=db.notes().note(book)!!;val started=System.nanoTime()
                        withTimeout(5_000){NoteRepository(db).rename(RenameNote(UUID.randomUUID().toString(),book,note.revision,"并发保存 $i"))}
                        saves+=(System.nanoTime()-started)/1_000_000.0
                    }
                    release.countDown();val deferred=collecting.await()
                    assertTrue(deferred.collectionDeferred);assertEquals(0L,deferred.detachedSources)
                }finally{release.countDown();collecting.cancelAndJoin()}

                // Also time an unpaused cleanup competing with a stream of actual saves.
                val liveSaves=mutableListOf<Double>();val liveScans=AtomicInteger();val liveStarted=System.nanoTime()
                val liveEntered=CompletableDeferred<Unit>()
                val liveCollection=async(Dispatchers.IO){HistoryStorage(db){if(it=="after-roots"){
                    liveScans.incrementAndGet();liveEntered.complete(Unit)
                }}.collectDetached()}
                withTimeout(30_000){liveEntered.await()}
                repeat(30){i->
                    val note=db.notes().note(book)!!;val started=System.nanoTime()
                    NoteRepository(db).rename(RenameNote(UUID.randomUUID().toString(),book,note.revision,"持续保存 $i"))
                    liveSaves+=(System.nanoTime()-started)/1_000_000.0
                }
                val liveReport=liveCollection.await()
                val finished=HistoryStorage(db){if(it=="after-roots")liveScans.incrementAndGet()}.collectDetached()
                val liveMs=(System.nanoTime()-liveStarted)/1_000_000.0
                assertFalse(finished.collectionDeferred)
                assertEquals(garbage.toLong(),liveReport.detachedSources+finished.detachedSources)
                assertEquals(roots+1,db.sourceVersions().all().size)
                fun JSONObject.timings(prefix:String,values:List<Double>):JSONObject {
                    val sorted=values.sorted()
                    return put(prefix+"P50Ms",sorted[14]).put(prefix+"P95Ms",sorted[28]).put(prefix+"MaxMs",sorted.last())
                        .put(prefix+"SamplesMs",JSONArray(values))
                }
                // After timing, pin connection-local PRAGMAs to the primary writer without setting them.
                val writerSqlite=db.withTransaction{
                    val settings=JSONObject();val sql=db.openHelper.writableDatabase
                    for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
                        sql.query("PRAGMA $setting").use{it.moveToFirst();settings.put(setting,if(setting=="journal_mode")it.getString(0) else it.getLong(0))}
                    }
                    settings
                }
                val evidence=JSONObject().put("baseline",baseline).put("historicalRoots",roots).put("detachedSources",garbage).put("writerSqlite",writerSqlite)
                    .put("collectionRootScans",scans.get()).put("collectionMs",collectionMs)
                    .put("pausedConcurrentRootScans",concurrentScans.get()).put("pausedScanSaves",saves.size)
                    .put("liveConcurrentRootScans",liveScans.get()).put("liveCollectionAndSavesMs",liveMs)
                    .put("liveFirstPassDeferred",liveReport.collectionDeferred).put("observerNotifications",notifications.get())
                    .timings("pausedScanSave",saves).timings("liveSave",liveSaves)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","HISTORY_COLLECTION_EVIDENCE $evidence\n")})
            }finally{db.invalidationTracker.removeObserver(observer)}
        }finally{db.close();context.deleteDatabase(name)}
    }}
}
