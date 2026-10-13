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
class KvmHistoryPairProbe {

    private val pairSeed=checkNotNull(InstrumentationRegistry.getArguments().getString("pairSeed")).toLong()
    private val pairRandom=java.util.Random(pairSeed)
    private fun id()=UUID(pairRandom.nextLong(),pairRandom.nextLong()).toString()
    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\n")})
    }
    // Outside all measured operations. Hash actual logical rows, not a fixture recipe.
    // Keep creation timestamps deterministic; later real saves retain their normal clock.
    private suspend fun fixtureEvidence(db:NoteDatabase,label:String,tables:List<String>)=db.withTransaction{
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE notes SET updatedAt=1700000000000 WHERE updatedAt>1000000000000")
        sql.execSQL("UPDATE note_revisions SET committedAt=1700000000000 WHERE committedAt>1000000000000")
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun add(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        val counts=JSONObject()
        for(table in tables){
            require(table.matches(Regex("[a-z_]+")))
            add(table.toByteArray(Charsets.UTF_8));var rows=0
            val order=if(table=="page_layer_memberships")"1,2,3" else "1,2"
            sql.query("SELECT * FROM $table ORDER BY $order").use{c->
                c.columnNames.forEach{add(it.toByteArray(Charsets.UTF_8))}
                while(c.moveToNext()){
                    rows++
                    for(i in 0 until c.columnCount){
                        val type=c.getType(i);add(byteArrayOf(type.toByte()))
                        add(when(type){
                            android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf()
                            android.database.Cursor.FIELD_TYPE_BLOB->c.getBlob(i)
                            else->c.getString(i).toByteArray(Charsets.UTF_8)
                        })
                    }
                }
            }
            counts.put(table,rows)
        }
        val settings=JSONObject()
        for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
            sql.query("PRAGMA $setting").use{it.moveToFirst();settings.put(setting,if(setting=="journal_mode")it.getString(0) else it.getLong(0))}
        }
        assertEquals(1024L,settings.getLong("page_size"));assertEquals(1L,settings.getLong("auto_vacuum"))
        assertEquals("wal",settings.getString("journal_mode"));assertEquals(2L,settings.getLong("synchronous"))
        emit("KVM_PAIR_FIXTURE",JSONObject().put("label",label).put("seed",pairSeed)
            .put("logicalSha256",digest.digest().joinToString(""){"%02x".format(it)})
            .put("rowCounts",counts).put("writerSqlite",settings))
    }

    @Test fun collectLargeGarbageWithRootsAndConcurrentAuthorSaves()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="history-collection-probe-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        val baseline=InstrumentationRegistry.getArguments().getString("historyScanBaseline")=="true"
        try{coroutineScope{
            val book=WorkspaceRepository(db).create("垃圾清理探针",false,PaperStyle.BLANK,operationId=id()).id
            val source=id();val card=id()
            val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
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
            fixtureEvidence(db,"history-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","study_cards","study_card_revisions","study_source_revisions","study_card_source_sets"))
            val initialAuthor=checkNotNull(NoteRepository(db).read(book))
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
                val pausedProgress=JSONArray();val liveProgress=JSONArray()
                val saves=mutableListOf<Double>()
                try{
                    withTimeout(30_000){entered.await()}
                    repeat(30){i->
                        val note=db.notes().note(book)!!;val started=System.nanoTime()
                        val saved=withTimeout(5_000){NoteRepository(db).rename(RenameNote(id(),book,note.revision,"并发保存 $i"))}
                        val ended=System.nanoTime();saves+=(ended-started)/1_000_000.0
                        assertTrue(saved is SaveResult.Committed);assertEquals(note.revision+1,(saved as SaveResult.Committed).note.revision)
                        pausedProgress.put(JSONObject().put("sample",i).put("startNs",started).put("endNs",ended).put("committed",true).put("revision",saved.note.revision))
                    }
                    release.countDown();val deferred=collecting.await()
                    assertTrue(deferred.collectionDeferred);assertEquals(0L,deferred.detachedSources)
                }finally{release.countDown();collecting.cancelAndJoin()}

                // Also time an unpaused cleanup competing with a stream of actual saves.
                val liveSaves=mutableListOf<Double>();val liveScans=AtomicInteger();val liveStarted=System.nanoTime()
                val liveEntered=CompletableDeferred<Unit>()
                val liveCallStart=java.util.concurrent.atomic.AtomicLong();val liveCallEnd=java.util.concurrent.atomic.AtomicLong()
                val rootEvents=java.util.concurrent.CopyOnWriteArrayList<Long>()
                val liveCollection=async(Dispatchers.IO){
                    liveCallStart.set(System.nanoTime())
                    try{HistoryStorage(db){if(it=="after-roots"){
                        rootEvents.add(System.nanoTime());liveScans.incrementAndGet();liveEntered.complete(Unit)
                    }}.collectDetached()}finally{liveCallEnd.set(System.nanoTime())}
                }
                withTimeout(30_000){liveEntered.await()}
                repeat(30){i->
                    val note=db.notes().note(book)!!;val started=System.nanoTime()
                    val saved=NoteRepository(db).rename(RenameNote(id(),book,note.revision,"持续保存 $i"))
                    val ended=System.nanoTime();liveSaves+=(ended-started)/1_000_000.0
                    assertTrue(saved is SaveResult.Committed);assertEquals(note.revision+1,(saved as SaveResult.Committed).note.revision)
                    liveProgress.put(JSONObject().put("sample",i).put("startNs",started).put("endNs",ended).put("committed",true).put("revision",saved.note.revision))
                }
                val liveReport=liveCollection.await()
                val finishStart=System.nanoTime()
                val finished=HistoryStorage(db){if(it=="after-roots")liveScans.incrementAndGet()}.collectDetached()
                val finishEnd=System.nanoTime()
                val liveMs=(finishEnd-liveStarted)/1_000_000.0
                assertFalse(finished.collectionDeferred)
                assertEquals(garbage.toLong(),liveReport.detachedSources+finished.detachedSources)
                assertEquals(roots+1,db.sourceVersions().all().size)
                val finalAuthor=checkNotNull(NoteRepository(db).read(book))
                assertEquals(initialAuthor.revision+60,finalAuthor.revision);assertEquals("持续保存 29",finalAuthor.title)
                emit("KVM_HISTORY_INTERVALS",JSONObject().put("paused",pausedProgress).put("live",liveProgress)
                    .put("collectionStartNs",liveCallStart.get()).put("collectionEndNs",liveCallEnd.get()).put("afterRootsNs",JSONArray(rootEvents))
                    .put("finishStartNs",finishStart).put("finishEndNs",finishEnd)
                    .put("firstDeleted",liveReport.detachedSources).put("finishDeleted",finished.detachedSources).put("finalRevision",finalAuthor.revision))
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
        }}finally{db.close();context.deleteDatabase(name)}
    }
}
