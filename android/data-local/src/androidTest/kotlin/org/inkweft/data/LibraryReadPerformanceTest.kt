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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Also compiles unchanged at a128bb07. The optional new projection observer is discovered
 * reflectively; timing and correctness use the same fixture/API on both revisions.
 * Save checkpoints are emitted outside measured save calls. Index progress is emitted
 * after commits; logging can contribute to index wall time and concurrent scheduling.
 * Optional -e libraryStageDiagnostics true records coarse candidate-only stages. It is
 * off by default; enabled clock/queue/logging costs make that run diagnostic evidence,
 * not a timing comparison with ordinary runs. Events contain no author text or IDs. */
class LibraryReadPerformanceTest {
    private fun id()=UUID.randomUUID().toString()
    private fun checkpoint(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\n")})
    }
    @Test fun coldSearchConcurrentSavesAndShelfReadScale()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="library-read-probe-${id()}.db";val db=NoteDatabase.open(context,name)
        var diagnostics:StageDiagnostics?=null
        var failure:Throwable?=null
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
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK)
            val headerMethod=NoteRepository::class.java.methods.firstOrNull{it.name=="observeNoteHeaders"}
            val headerStart=System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val notes=if(headerMethod==null)noteRepo.observeNotes().first() else (headerMethod.invoke(noteRepo) as Flow<List<Note>>).first()
            val headerMs=(System.nanoTime()-headerStart)/1e6
            val repo=LibrarySearchRepository(db)
            if(InstrumentationRegistry.getArguments().getString("libraryStageDiagnostics")=="true")diagnostics=StageDiagnostics(repo)
            val request=LibrarySearchRequest(1,notes,emptyMap(),"target-only")
            val scanned=AtomicInteger();val entered=CompletableDeferred<Unit>()
            repo.scanObserver={_,count->scanned.set(count);if(count==1)entered.complete(Unit)}
            val coldStart=System.nanoTime()
            val cold=async(Dispatchers.IO){repo.read(request)}
            withTimeout(30_000){entered.await()}
            val saves=async(Dispatchers.IO){buildList{repeat(30){i->
                val start=System.nanoTime()
                val result=try{noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic concurrent edit $i"))}
                catch(t:Throwable){diagnostics?.failed("direct",i,start,System.nanoTime(),t);throw t}
                diagnostics?.let{if(result !is SaveResult.Committed)it.saved("direct",i,start,System.nanoTime(),result)}
                assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                val end=System.nanoTime();add((end-start)/1e6)
                diagnostics?.saved("direct",i,start,end,result)
            }}}
            val first=cold.await();val coldMs=(System.nanoTime()-coldStart)/1e6;val saveSamples=saves.await();val latencies=saveSamples.sorted()
            assertEquals(targetBook,first.items.single().note.id);assertEquals(targetPage,first.items.single().pageId)
            assertEquals(20_000,first.pendingIndex)
            val coldScanned=scanned.get()
            diagnostics?.flush("cold")
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
            diagnostics?.flush("paused-reader")
            // Additional scenario: the real observe() path builds FTS while author saves run.
            // The paused-reader save advanced the head; refresh before issuing the next edit.
            writer=checkNotNull(noteRepo.read(writer.id))
            val observedAuthorStartRevision=writer.revision
            val observeRows=AtomicInteger();val observeChecks=AtomicInteger()
            val firstObservedBatch=CompletableDeferred<Unit>()
            val allRowsCommitted=CompletableDeferred<Unit>()
            val observedIndexReady=CompletableDeferred<LibrarySearchResult>()
            val observeStart=System.nanoTime()
            var firstObservedBatchMs=0.0;var allObservedRowsMs=0.0
            repo.scanObserver={_,_->observeChecks.incrementAndGet()}
            repo.indexObserver={_,count->
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
                    val result=try{noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic indexed edit $i"))}
                    catch(t:Throwable){diagnostics?.failed("observe",i,start,System.nanoTime(),t);throw t}
                    val end=System.nanoTime();val elapsed=(end-start)/1e6
                    val rowsAfter=observeRows.get();val checksAfter=observeChecks.get()
                    diagnostics?.saved("observe",i,start,end,result)
                    assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                    durations+=elapsed
                    samples.put(JSONObject().put("sample",i).put("elapsedMs",elapsed)
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
                diagnostics?.flush("observe-saves-finished")
                allRowsCommitted.await()
                observedIndexReady.await()
                val readyMs=(System.nanoTime()-observeStart)/1e6
                assertEquals(first.pendingIndex,observeRows.get())
                savedSamples.put("indexRowsFinal",observeRows.get())
                    .put("allIndexRowsCommittedMs",allObservedRowsMs).put("searchIndexReadyMs",readyMs)
                    .put("sourceChecks",observeChecks.get())
            }finally{
                withContext(NonCancellable){observeJob.cancelAndJoin()}
                repo.indexObserver=null;repo.scanObserver=null
            }
            diagnostics?.flush("observe-finished")
            val warmSamples=buildList{repeat(5){sample->val start=System.nanoTime();val result=repo.read(request)
                add((System.nanoTime()-start)/1e6);assertEquals(targetPage,result.items.single().pageId);assertEquals(0,result.pendingIndex)
                diagnostics?.flush("warm-$sample")}}
            val warm=warmSamples.sorted()
            val observed=HashMap<String,Pair<Int,Int>>()
            val projection: (String,Int,Int)->Unit={kind,rows,chars->observed[kind]=rows to chars}
            LibrarySearchRepository::class.java.methods.firstOrNull{it.name.startsWith("setReadObserver")}?.invoke(repo,projection)
            val shelf=repo.read(request.copy(query="",pageSize=60))
            diagnostics?.flush("shelf")
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
                .put("stageDiagnosticsEnabled",diagnostics!=null)
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
        }}catch(t:Throwable){failure=t;throw t}
        finally{
            try{diagnostics?.flush("final-cleanup")}
            catch(t:Throwable){if(failure==null)throw t else failure.addSuppressed(t)}
            finally{try{db.close()}finally{context.deleteDatabase(name)}}
        }
    }

    // Reflection keeps the ordinary probe source compatible with a128bb07. Explicitly
    // enabling candidate-only diagnostics on a baseline without the hook fails clearly.
    private inner class StageDiagnostics(repo:LibrarySearchRepository) {
        private val originNs=System.nanoTime()
        private val events=ConcurrentLinkedQueue<StageEvent>()
        private val saves=ConcurrentLinkedQueue<SaveEvent>()
        private var part=0
        init{
            val observer:(String,Long,Long,Long,Long,Int)->Unit={stage,generation,version,readId,atNs,count->
                events.add(StageEvent(stage,generation,version,readId,atNs,count))
            }
            val setter=LibrarySearchRepository::class.java.methods.firstOrNull{it.name.startsWith("setStageObserver")}
            checkNotNull(setter){"libraryStageDiagnostics requires the optional stage observer"}.invoke(repo,observer)
        }
        fun saved(phase:String,sample:Int,startNs:Long,endNs:Long,result:SaveResult){
            val committed=result as? SaveResult.Committed
            saves.add(SaveEvent(phase,sample,startNs,endNs,committed!=null,committed?.note?.revision,
                if(committed!=null)"committed" else "not-committed",null,result.javaClass.name))
        }
        fun failed(phase:String,sample:Int,startNs:Long,endNs:Long,failure:Throwable){
            // An interrupted/throwing call has no returned commit acknowledgement.
            // That alone cannot prove whether the author transaction committed.
            try{saves.add(SaveEvent(phase,sample,startNs,endNs,null,null,
                if(failure is CancellationException)"cancelled" else "exception",failure.javaClass.name,null))}
            catch(diagnosticFailure:Throwable){if(diagnosticFailure!==failure)failure.addSuppressed(diagnosticFailure)}
        }
        fun flush(boundary:String){
            // Queue insertion is thread safe. Different reads may interleave; timestamps
            // and run-local read IDs, not queue order, identify each measured interval.
            while(true){
                val batch=JSONArray()
                repeat(128){events.poll()?.let{event->batch.put(JSONObject()
                    .put("stage",event.stage).put("generation",event.generation).put("sourceVersion",event.version)
                    .put("readId",event.readId).put("elapsedNs",event.atNs-originNs).put("count",event.count))}}
                val saved=JSONArray()
                repeat(128){saves.poll()?.let{save->saved.put(JSONObject().put("phase",save.phase).put("sample",save.sample)
                    .put("startNs",save.startNs).put("endNs",save.endNs).put("committed",save.committed?:JSONObject.NULL)
                    .put("revision",save.revision?:JSONObject.NULL).put("outcome",save.outcome)
                    .put("failureClass",save.failureClass?:JSONObject.NULL).put("resultClass",save.resultClass?:JSONObject.NULL))}}
                if(batch.length()==0&&saved.length()==0)return
                checkpoint("LIBRARY_STAGE_DIAGNOSTICS",JSONObject().put("schema",1).put("checkpoint",boundary)
                    .put("part",part++).put("clock","System.nanoTime").put("originNs",originNs).put("events",batch).put("saves",saved))
            }
        }
    }
    private data class StageEvent(val stage:String,val generation:Long,val version:Long,val readId:Long,val atNs:Long,val count:Int)
    private data class SaveEvent(val phase:String,val sample:Int,val startNs:Long,val endNs:Long,val committed:Boolean?,val revision:Long?,
        val outcome:String,val failureClass:String?,val resultClass:String?)
}
