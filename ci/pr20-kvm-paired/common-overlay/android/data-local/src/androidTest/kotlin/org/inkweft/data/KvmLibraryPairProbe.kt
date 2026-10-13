// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Also compiles unchanged at a128bb07. The optional new projection observer is discovered
 * reflectively; timing and correctness use the same fixture/API on both revisions.
 * Save checkpoints are emitted outside measured save calls. Index progress is emitted
 * after commits; logging can contribute to index wall time and concurrent scheduling. */
class KvmLibraryPairProbe {

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

    private fun checkpoint(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\n")})
    }
    @Test fun coldSearchConcurrentSavesAndShelfReadScale()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="library-read-probe-${id()}.db";val db=NoteDatabase.open(context,name)
        try{coroutineScope{
            val body="Synthetic note body "+"b".repeat(20_000)
            val pages="合成页面索引 "+"a".repeat(1024)
            var targetPage="";var targetBook=""
            db.withTransaction{
                val sql=db.openHelper.writableDatabase
                repeat(500){bookIndex->
                    val book=id();val title="合成笔记 ${bookIndex.toString().padStart(3,'0')}"
                    db.notes().insertNote(NoteRow(book,1,title,body,bookIndex.toLong()))
                    db.notes().insertRevision(NoteRevisionRow(book,1,title,body,bookIndex.toLong()))
                    db.workspace().insert(WorkspaceRow(book))
                    repeat(40){position->
                        val page=if(position==0)book else id()
                        db.pages().insert(NotebookPageRow(page,book,position,false,0))
                        val target=bookIndex==499&&position==39
                        if(target){targetPage=page;targetBook=book}
                        sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only"else pages))
                    }
                }
            }
            val noteRepo=NoteRepository(db)
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK,operationId=id())
            fixtureEvidence(db,"library-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","page_search_text","library_search_pending"))
            val headerMethod=NoteRepository::class.java.methods.firstOrNull{it.name=="observeNoteHeaders"}
            val headerStart=System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val notes=if(headerMethod==null)noteRepo.observeNotes().first() else (headerMethod.invoke(noteRepo) as Flow<List<Note>>).first()
            val headerMs=(System.nanoTime()-headerStart)/1e6
            val repo=LibrarySearchRepository(db)
            val request=LibrarySearchRequest(1,notes,emptyMap(),"target-only")
            val scanned=AtomicInteger();val entered=CompletableDeferred<Unit>()
            repo.scanObserver={_,count->scanned.set(count);if(count==1)entered.complete(Unit)}
            val coldEnd=java.util.concurrent.atomic.AtomicLong()
            val directProgress=JSONArray()
            val coldStart=System.nanoTime()
            val cold=async(Dispatchers.IO){try{repo.read(request)}finally{coldEnd.set(System.nanoTime())}}
            withTimeout(30_000){entered.await()}
            val saves=async(Dispatchers.IO){buildList{repeat(30){i->
                val checksStart=scanned.get();val start=System.nanoTime()
                val result=noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic concurrent edit $i"))
                val end=System.nanoTime();val checksEnd=scanned.get()
                assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                val elapsed=(end-start)/1e6
                directProgress.put(JSONObject().put("sample",i).put("startNs",start).put("endNs",end).put("elapsedMs",elapsed).put("checksStart",checksStart).put("checksEnd",checksEnd).put("committedRevision",writer.revision).put("committed",true))
                add(elapsed)
            }}}
            val first=cold.await();val coldMs=(System.nanoTime()-coldStart)/1e6;val saveSamples=saves.await();val latencies=saveSamples.sorted()
            assertEquals(targetBook,first.items.single().note.id);assertEquals(targetPage,first.items.single().pageId)
            assertEquals(20_000,first.pendingIndex)
            val coldScanned=scanned.get()
            checkpoint("KVM_DIRECT_INTERVALS",JSONObject().put("coldStartNs",coldStart).put("coldEndNs",coldEnd.get()).put("saves",directProgress))
            // Deterministic contention probe: pause inside actual source scanning. A writer
            // must be able to commit while an optimized reader pins its old WAL snapshot.
            val pauseEntered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            repo.scanObserver={_,count->if(count==1){pauseEntered.complete(Unit);check(release.await(30,TimeUnit.SECONDS))}}
            val paused=async(Dispatchers.IO){repo.read(request)}
            val completedWhilePaused:Boolean
            try{
                withTimeout(30_000){pauseEntered.await()}
                val saving=async(Dispatchers.IO){noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Paused reader edit"))}
                completedWhilePaused=withTimeoutOrNull(750){saving.await();true}?:false
                release.countDown();assertTrue(saving.await() is SaveResult.Committed)
                assertEquals(targetPage,paused.await().items.single().pageId)
            }finally{release.countDown();paused.cancelAndJoin()}
            if(headerMethod!=null)assertTrue("Independent WAL reader must not reserve the Room writer",completedWhilePaused)
            repo.scanObserver=null
            checkpoint("LIBRARY_DIRECT_SAVE_CHECKPOINT",JSONObject()
                .put("coldMs",coldMs).put("coldSourceChecks",coldScanned)
                .put("saveCount",latencies.size).put("saveSamplesMs",JSONArray(saveSamples))
                .put("saveP50Ms",latencies[latencies.size/2])
                .put("saveP95Ms",latencies[(latencies.size*95+99)/100-1]).put("saveMaxMs",latencies.last())
                .put("writerCompletedDuringPausedSearch",completedWhilePaused))
            // Additional scenario: the real observe() path builds FTS while author saves run.
            // The paused-reader save advanced the head; refresh before issuing the next edit.
            writer=checkNotNull(noteRepo.read(writer.id))
            val observedAuthorStartRevision=writer.revision
            val observeRows=AtomicInteger();val observeChecks=AtomicInteger()
            val firstObservedBatch=CompletableDeferred<Unit>()
            val allRowsCommitted=CompletableDeferred<Unit>()
            val observedIndexReady=CompletableDeferred<LibrarySearchResult>()
            val batchProgress=java.util.concurrent.CopyOnWriteArrayList<Pair<Int,Long>>()
            val observeStart=System.nanoTime()
            var firstObservedBatchMs=0.0;var allObservedRowsMs=0.0
            repo.scanObserver={_,_->observeChecks.incrementAndGet()}
            repo.indexObserver={_,count->
                val committedNs=System.nanoTime();batchProgress.add(count to committedNs)
                observeRows.set(count)
                val firstBatch=!firstObservedBatch.isCompleted
                if(firstBatch){
                    firstObservedBatchMs=(System.nanoTime()-observeStart)/1e6
                    firstObservedBatch.complete(Unit)
                }
                if(count==first.pendingIndex){
                    allObservedRowsMs=(System.nanoTime()-observeStart)/1e6
                    allRowsCommitted.complete(Unit)
                }
                if(firstBatch||count%4096==0||count==first.pendingIndex){
                    checkpoint("INDEX_SAVE_PROGRESS",JSONObject().put("rows",count)
                        .put("elapsedMs",(System.nanoTime()-observeStart)/1e6))
                }
            }
            val observeJob=launch(Dispatchers.Main){
                repo.observe(flowOf(request.copy(generation=2))).collect{result->
                    assertFalse(result.failed)
                    assertEquals(targetBook,result.items.single().note.id)
                    assertEquals(targetPage,result.items.single().pageId)
                    if(result.pendingIndex==0)observedIndexReady.complete(result)
                }
            }
            val observeEvidence=try{
                // Natural first committed batch: no sleeps or artificial index/writer pauses.
                firstObservedBatch.await()
                val rowsBeforeSaves=observeRows.get()
                val samples=JSONArray();val durations=ArrayList<Double>()
                withContext(Dispatchers.IO){repeat(30){i->
                    val rowsBefore=observeRows.get();val checksBefore=observeChecks.get()
                    val start=System.nanoTime()
                    val result=noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic indexed edit $i"))
                    val end=System.nanoTime();val elapsed=(end-start)/1e6
                    val rowsAfter=observeRows.get();val checksAfter=observeChecks.get()
                    assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                    durations+=elapsed
                    samples.put(JSONObject().put("sample",i).put("elapsedMs",elapsed).put("startNs",start).put("endNs",end).put("committed",true).put("committedRevision",writer.revision)
                        .put("indexRowsStart",rowsBefore).put("indexRowsEnd",rowsAfter)
                        .put("sourceChecksStart",checksBefore).put("sourceChecksEnd",checksAfter))
                }}
                val rowsAfterSaves=observeRows.get()
                val sorted=durations.sorted()
                val savedSamples=JSONObject().put("indexRowsBeforeSaves",rowsBeforeSaves).put("indexRowsAfterSaves",rowsAfterSaves)
                    .put("firstCommittedBatchMs",firstObservedBatchMs)
                    .put("saveCount",durations.size).put("saveP50Ms",sorted[sorted.size/2])
                    .put("saveP95Ms",sorted[(sorted.size*95+99)/100-1]).put("saveMaxMs",sorted.last())
                    .put("saveSamplesMs",JSONArray(durations)).put("saveProgressSamples",samples)
                    .put("sourceChecksAfterSaves",observeChecks.get())
                checkpoint("LIBRARY_INDEX_SAVE_CHECKPOINT",savedSamples)
                allRowsCommitted.await()
                observedIndexReady.await()
                val readyMs=(System.nanoTime()-observeStart)/1e6
                assertEquals(first.pendingIndex,observeRows.get())
                checkpoint("KVM_INDEX_INTERVALS",JSONObject().put("observeStartNs",observeStart).put("batches",JSONArray().apply{batchProgress.forEach{put(JSONObject().put("rows",it.first).put("committedNs",it.second))}}))
                savedSamples.put("indexRowsFinal",observeRows.get())
                    .put("allIndexRowsCommittedMs",allObservedRowsMs).put("searchIndexReadyMs",readyMs)
                    .put("sourceChecks",observeChecks.get())
            }finally{
                withContext(NonCancellable){observeJob.cancelAndJoin()}
                repo.indexObserver=null;repo.scanObserver=null
            }
            val warmSamples=buildList{repeat(5){val start=System.nanoTime();val result=repo.read(request)
                add((System.nanoTime()-start)/1e6);assertEquals(targetPage,result.items.single().pageId);assertEquals(0,result.pendingIndex)}}
            val warm=warmSamples.sorted()
            val observed=HashMap<String,Pair<Int,Int>>()
            val projection: (String,Int,Int)->Unit={kind,rows,chars->observed[kind]=rows to chars}
            LibrarySearchRepository::class.java.methods.firstOrNull{it.name.startsWith("setReadObserver")}?.invoke(repo,projection)
            val shelf=repo.read(request.copy(query="",pageSize=60))
            assertEquals(60,shelf.items.size);assertNotNull(shelf.nextOffset)
            if(headerMethod!=null){
                assertEquals(0,notes.sumOf{it.text.length});assertEquals(0,shelf.active.sumOf{it.text.length})
                assertEquals(61,observed.getValue("previews").first)
                assertTrue(observed.getValue("previews").second<=61*600)
                assertEquals(body,noteRepo.read(targetBook)!!.text)
            }
            val persistedAuthor=checkNotNull(noteRepo.read(writer.id))
            assertEquals(observedAuthorStartRevision+30L,persistedAuthor.revision)
            assertEquals("Synthetic indexed edit 29",persistedAuthor.text)
            assertEquals(writer,persistedAuthor)
            // After timing, pin connection-local PRAGMAs to the primary writer without setting them.
            val writerSqlite=db.withTransaction{
                val settings=JSONObject();val sql=db.openHelper.writableDatabase
                for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
                    sql.query("PRAGMA $setting").use{it.moveToFirst();settings.put(setting,if(setting=="journal_mode")it.getString(0) else it.getLong(0))}
                }
                settings
            }
            val evidence=JSONObject().put("books",500).put("pages",20000).put("noteBodyChars",body.length).put("writerSqlite",writerSqlite)
                .put("headerReadMs",headerMs).put("headerReturnedBodyChars",notes.sumOf{it.text.length})
                .put("coldMs",coldMs).put("coldSourceChecks",coldScanned).put("warmMedianMs",warm[2]).put("warmSamplesMs",JSONArray(warmSamples))
                .put("saveCount",latencies.size).put("saveP50Ms",latencies[latencies.size/2])
                .put("saveP95Ms",latencies[(latencies.size*95+99)/100-1]).put("saveMaxMs",latencies.last())
                .put("saveSamplesMs",JSONArray(saveSamples))
                .put("observeIndexConcurrentSaves",observeEvidence)
                .put("writerCompletedDuringPausedSearch",completedWhilePaused)
                .put("shelfReturnedPreviewChars",shelf.items.sumOf{it.note.text.length})
                .put("shelfDirectoryBodyChars",shelf.active.sumOf{it.text.length})
                .put("projectionRows",observed["previews"]?.first?:JSONObject.NULL)
                .put("projectionChars",observed["previews"]?.second?:JSONObject.NULL)
                .put("fullBodyOnDemandMatches",noteRepo.read(targetBook)!!.text==body)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","LIBRARY_READ_EVIDENCE $evidence\n")})
        }}finally{db.close();context.deleteDatabase(name)}
    }
}
