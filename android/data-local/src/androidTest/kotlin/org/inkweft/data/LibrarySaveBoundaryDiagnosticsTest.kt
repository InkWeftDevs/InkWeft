// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Debug
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One seed, instrumented causal-location probe. It never replaces the public paired windows.
 * Every event's CPU counter belongs to its own thread; consumers must reject cross-thread
 * subtraction. SQLite END includes commit/rollback and connection release, not fsync alone. */
class LibrarySaveBoundaryDiagnosticsTest {
    private data class Event(val stage:String,val sample:Int,val task:Long,val transaction:Long,
        val depth:Int,val success:Boolean,val outcome:String,val wall:Long,val cpu:Long,val thread:Long)
    private data class ReadEvent(val owner:String,val stage:String,val read:Long,val count:Int,val wall:Long,val cpu:Long,val thread:Long)
    private class Recorder(private val capacity:Int=4096) {
        @Volatile var enabled=false
        val events=ConcurrentLinkedQueue<Event>()
        val dropped=AtomicInteger()
        private val count=AtomicInteger()
        private val pending=ConcurrentHashMap.newKeySet<Long>()
        val origin=System.nanoTime()
        val diagnostics=SaveBoundaryDiagnostics { stage,sample,task,transaction,depth,success,outcome ->
            if(stage=="writer-submit")pending.add(task)
            if(enabled) {
                val wall=System.nanoTime()-origin; val cpu=Debug.threadCpuTimeNanos(); val thread=Thread.currentThread().id
                if(count.incrementAndGet()<=capacity)events.add(Event(stage,sample,task,transaction,depth,success,outcome,wall,cpu,thread))
                else dropped.incrementAndGet()
            }
            // Track tasks even outside recording, so a paused-window task cannot leak its
            // trailing exit into the next window. Publish its event before signalling exit.
            if(stage=="writer-run-exit"||stage=="writer-submit-failed")pending.remove(task)
        }
        suspend fun awaitWorkers()=withTimeout(5_000) {
            while(pending.isNotEmpty())yield()
            val copy=events.toList()
            val submitted=copy.filter{it.stage=="writer-submit"}.map{it.task}.toSet()
            val exited=copy.filter{it.stage=="writer-run-exit"||it.stage=="writer-submit-failed"}.map{it.task}.toSet()
            assertTrue("Recorded writer tasks must all exit before sealing the window",exited.containsAll(submitted))
        }
        fun complete() { assertFalse("Diagnostic callback/depth failure",diagnostics.invalid);assertEquals("Dropped events",0,dropped.get()) }
        fun reset() { enabled=false;events.clear();count.set(0);dropped.set(0) }
    }
    private fun optedIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("librarySaveBoundaryDiagnostics")=="true")
    private fun emit(marker:String,value:JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\n")})
    }

    @Test fun boundariesPreserveCommitRollbackReplayAndRecovery()=runBlocking {
        optedIn()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="save-boundary-guard-${UUID.randomUUID()}.db"
        val recorder=Recorder();var db=NoteDatabase.open(context,name,recorder.diagnostics)
        try {
            db.withTransaction{assertEquals(2,db.openHelper.writableDatabase.query("PRAGMA synchronous").use{it.moveToFirst();it.getInt(0)})}
            val authorId="00000000-0000-0000-0000-000000000001"
            val repository=NoteRepository(db);val command=SaveNote("00000000-0000-0000-0000-000000000002",authorId,0,"Title","Body")
            recorder.enabled=true
            suspend fun save(sample:Int,command:SaveNote)=withContext(Dispatchers.IO+recorder.diagnostics.sample.asContextElement(sample)){repository.save(command)}
            assertTrue(save(0,command) is SaveResult.Committed)
            assertTrue(save(1,command) is SaveResult.Committed)
            assertTrue(save(2,command.copy(commandId="00000000-0000-0000-0000-000000000003",expectedRevision=0)) is SaveResult.Conflict)
            val failure=IllegalStateException("synthetic guard")
            repository.saveStageObserver={if(it=="head-written")throw failure}
            try { save(3,command.copy(commandId="00000000-0000-0000-0000-000000000004",expectedRevision=1,text="Must roll back"));fail("Expected injected failure") }
            catch(actual:IllegalStateException){assertSame(failure,actual)}
            val cancelled=CancellationException("synthetic rollback")
            repository.saveStageObserver={if(it=="head-written")throw cancelled}
            try { save(4,command.copy(commandId="00000000-0000-0000-0000-000000000005",expectedRevision=1,text="Must roll back"));fail("Expected cancellation") }
            catch(actual:CancellationException){assertSame(cancelled,actual)}
            repository.saveStageObserver=null
            // Keep Room's actual serial writer occupied. Cancellation must remove the queued
            // caller without allowing its body to become an author write after the queue drains.
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val blocker=launch(Dispatchers.IO){db.withTransaction{entered.complete(Unit);release.await()}}
            var queued:Job?=null
            try {
                entered.await()
                queued=launch(Dispatchers.IO+recorder.diagnostics.sample.asContextElement(5)) {
                    repository.save(command.copy(commandId="00000000-0000-0000-0000-000000000006",expectedRevision=1,text="Must never write"))
                }
                withTimeout(5_000){while(recorder.events.none{it.sample==5&&it.stage=="writer-submit"})yield()}
                queued.cancel()
            } finally {
                release.complete(Unit)
                withContext(NonCancellable){queued?.cancelAndJoin();blocker.cancelAndJoin()}
            }
            val committed=save(6,command.copy(commandId="00000000-0000-0000-0000-000000000007",expectedRevision=1,text="Persisted")) as SaveResult.Committed
            assertEquals(2L,committed.note.revision)
            withContext(Dispatchers.IO+recorder.diagnostics.sample.asContextElement(7)) {
                db.withTransaction { assertTrue(repository.save(SaveNote("00000000-0000-0000-0000-000000000008","00000000-0000-0000-0000-000000000009",0,"Nested","Body")) is SaveResult.Committed) }
            }
            recorder.awaitWorkers();recorder.complete()
            val events=recorder.events.toList()
            for(sample in listOf(0,1,2,3,4,6))assertOuterPair(events.filter{it.sample==sample},sample in listOf(0,1,2,6))
            assertOuterPair(events.filter{it.sample==7},true)
            assertTrue(events.any{it.sample==7&&it.stage=="room-begin-exit"&&it.depth==2})
            assertFalse(events.any{it.sample==5&&it.stage=="save-writer-acquired"})
            assertEquals("Persisted",repository.read(authorId)!!.text)
            db.close();db=NoteDatabase.open(context,name)
            assertNull(db.saveBoundaryDiagnostics)
            val beforeUntraced=recorder.events.size
            val reopened=NoteRepository(db)
            assertEquals(committed.note,reopened.read(authorId))
            assertTrue(reopened.save(command.copy(commandId="00000000-0000-0000-0000-00000000000a",expectedRevision=2,text="After reopen")) is SaveResult.Committed)
            assertEquals("Disabled path emitted a diagnostic event",beforeUntraced,recorder.events.size)
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun observerFailureOverflowAndDelegateFailureDoNotChangeSaveOutcome()=runBlocking {
        optedIn()
        val executorTrace=Recorder();executorTrace.enabled=true
        val submitted=ArrayList<Runnable>();var calls=0
        val executor=executorTrace.diagnostics.executor(java.util.concurrent.Executor{submitted.add(it)})
        executorTrace.diagnostics.sample.set(19)
        executor.execute{calls++;assertEquals(19,executorTrace.diagnostics.sample.get());executorTrace.diagnostics.sample.set(99)}
        assertEquals(0,calls);assertEquals(1,submitted.size)
        executorTrace.diagnostics.sample.set(23)
        submitted.single().run()
        assertEquals(1,calls);assertEquals(23,executorTrace.diagnostics.sample.get())
        executorTrace.diagnostics.point("after-executor")
        assertEquals(0L,executorTrace.events.last().task)
        val rejected=java.util.concurrent.RejectedExecutionException("synthetic rejection")
        val rejecting=executorTrace.diagnostics.executor(java.util.concurrent.Executor{throw rejected})
        try{rejecting.execute{fail("Rejected command ran")};fail("Expected rejection")}catch(actual:java.util.concurrent.RejectedExecutionException){assertSame(rejected,actual)}
        assertEquals(23,executorTrace.diagnostics.sample.get())
        executorTrace.diagnostics.sample.remove()
        executorTrace.awaitWorkers();executorTrace.complete()
        val context=ApplicationProvider.getApplicationContext<Context>();val name="save-boundary-fault-${UUID.randomUUID()}.db"
        val diagnosticFailure=IllegalStateException("observer failure")
        val broken=SaveBoundaryDiagnostics { _,_,_,_,_,_,_->throw diagnosticFailure }
        var db=NoteDatabase.open(context,name,broken)
        val authorId="00000000-0000-0000-0000-00000000000b"
        try {
            assertTrue(NoteRepository(db).save(SaveNote("00000000-0000-0000-0000-00000000000c",authorId,0,"Title","Body")) is SaveResult.Committed)
            assertTrue(broken.invalid)
            db.close()
            val bounded=Recorder(1);bounded.enabled=true
            db=NoteDatabase.open(context,name,bounded.diagnostics)
            assertTrue(NoteRepository(db).save(SaveNote("00000000-0000-0000-0000-00000000000d",authorId,1,"Title","Next")) is SaveResult.Committed)
            assertTrue(bounded.dropped.get()>0)
            assertEquals("Next",NoteRepository(db).read(authorId)!!.text)
            val endFailure=IllegalStateException("delegate end failure")
            val fake=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) { _,method,_->
                when(method.name){"beginTransaction"->null;"endTransaction"->throw endFailure;else->error("Unexpected fake delegate call")}
            } as SupportSQLiteDatabase
            val trace=Recorder();trace.enabled=true;val wrapped=trace.diagnostics.database(fake)
            wrapped.beginTransaction()
            try{wrapped.endTransaction();fail("Expected delegate END failure")}catch(actual:IllegalStateException){assertSame(endFailure,actual)}
            assertTrue(trace.diagnostics.invalid)
            assertEquals("threw",trace.events.last().outcome)
        } finally { db.close();context.deleteDatabase(name) }
    }

    private fun assertOuterPair(events:List<Event>,successful:Boolean) {
        val entered=events.single{it.stage=="save-writer-acquired"}
        val roomBegin=events.single{it.stage=="room-begin-exit"&&it.depth==1&&it.outcome=="returned"}
        val roomEnd=events.single{it.stage=="room-end-exit"&&it.depth==1}
        assertEquals(roomBegin.transaction,roomEnd.transaction)
        assertEquals(successful,roomEnd.success);assertEquals("returned",roomEnd.outcome)
        // Room may perform a separate trigger-sync transaction during BEGIN. Select the
        // outer SQLite transaction that actually spans the author body, never a nested DAO END.
        val begin=events.filter{it.stage=="sqlite-begin-exit"&&it.depth==1&&it.outcome=="returned"&&it.wall<=entered.wall}
            .maxBy{it.wall}
        val end=events.single{it.stage=="sqlite-end-exit"&&it.depth==1&&it.transaction==begin.transaction}
        assertTrue(begin.wall<=roomBegin.wall&&roomBegin.wall<=entered.wall&&entered.wall<=end.wall&&end.wall<=roomEnd.wall)
        assertEquals(successful,end.success);assertEquals("returned",end.outcome)
        assertEquals(begin.thread,end.thread)
        val submit=events.single{it.stage=="writer-submit"}
        val worker=events.single{it.stage=="writer-run-enter"}
        val workerExit=events.single{it.stage=="writer-run-exit"}
        assertEquals(submit.task,worker.task);assertEquals(worker.task,workerExit.task)
        assertEquals(worker.task,roomBegin.task);assertEquals(worker.task,begin.task);assertEquals(worker.task,roomEnd.task)
        assertTrue(submit.wall<=worker.wall&&worker.wall<=roomBegin.wall)
        assertEquals(worker.thread,roomBegin.thread);assertEquals(worker.thread,roomEnd.thread)
    }

    @Test fun singleSeedIdleDirectAndObserveBoundaries()=runBlocking {
        optedIn()
        runScenario(false)
        // Preserve the public direct -> paused-reader save -> observe sequence, including
        // the writer's revision 32 at observe entry. IDLE has different readiness by design.
        runScenario(true)
    }

    private suspend fun runScenario(publicSequence:Boolean)=coroutineScope {
        val context=ApplicationProvider.getApplicationContext<Context>();val random=java.util.Random(4101L)
        fun id()=UUID(random.nextLong(),random.nextLong()).toString()
        val name="save-boundary-${id()}.db";val recorder=Recorder();val db=NoteDatabase.open(context,name,recorder.diagnostics)
        val repository=NoteRepository(db);val search=LibrarySearchRepository(db);val jobs=ArrayList<Job>()
        val stages=ConcurrentLinkedQueue<ReadEvent>();val stageCount=AtomicInteger();val stageDropped=AtomicInteger()
        val sourceChecks=AtomicInteger();val indexRows=AtomicInteger();var primaryFailure:Throwable?=null
        data class Window(val mode:String,val saves:JSONArray,val startRevision:Long,val readiness:String,
            val events:List<Event>,val reads:List<ReadEvent>,val dropped:Int,val droppedReads:Int,val invalid:Boolean,val completed:Boolean)
        val windows=ArrayList<Window>();var activeMode=if(publicSequence)"DIRECT"else "IDLE";var activeSaves=JSONArray()
        var activeStartRevision=1L;var activeReadiness="not-ready";var windowOpen=false
        fun finishWindow(completed:Boolean) {
            recorder.enabled=false
            windows+=Window(activeMode,activeSaves,activeStartRevision,activeReadiness,recorder.events.toList(),stages.toList(),
                recorder.dropped.get(),stageDropped.get(),recorder.diagnostics.invalid,completed)
            windowOpen=false
        }
        var fixture:JSONObject?=null
        try {
            val body="Synthetic note body "+"b".repeat(20_000);val pages="合成页面索引 "+"a".repeat(1024)
            var targetBook="";var targetPage=""
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                repeat(500){bookIndex->
                    val book=id();val title="合成笔记 ${bookIndex.toString().padStart(3,'0')}"
                    db.notes().insertNote(NoteRow(book,1,title,body,bookIndex.toLong()))
                    db.notes().insertRevision(NoteRevisionRow(book,1,title,body,bookIndex.toLong()))
                    db.workspace().insert(WorkspaceRow(book))
                    repeat(40){position->
                        val page=if(position==0)book else id();val target=bookIndex==499&&position==39
                        if(target){targetBook=book;targetPage=page}
                        db.pages().insert(NotebookPageRow(page,book,position,false,0))
                        sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only"else pages))
                    }
                }
            }
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK,operationId=id())
            fixture=fixtureEvidence(db)
            val request=LibrarySearchRequest(1,repository.observeNoteHeaders().first(),emptyMap(),"target-only")
            val firstSource=CompletableDeferred<Unit>();val firstBatch=CompletableDeferred<Unit>()
            fun stage(owner:String,label:String,read:Long=0,count:Int=-1) {
                val wall=System.nanoTime()-recorder.origin;val cpu=Debug.threadCpuTimeNanos();val thread=Thread.currentThread().id
                if(stageCount.incrementAndGet()<=2048)stages.add(ReadEvent(owner,label,read,count,wall,cpu,thread))else stageDropped.incrementAndGet()
            }
            search.scanObserver={_,_->sourceChecks.incrementAndGet();firstSource.complete(Unit)}
            search.indexObserver={_,count->indexRows.set(count);stage("index","committed",count=count);firstBatch.complete(Unit)}
            search.indexBatchStepObserver={stage("index",it)}
            search.stageObserver={label,_,_,read,_,count->stage("read",label,read,count)}
            recorder.enabled=true;windowOpen=true
            val direct=if(publicSequence)async(Dispatchers.IO){
                stage("control","direct-start")
                try{search.read(request)}finally{stage("control","direct-end")}
            }.also{jobs+=it}else null
            activeReadiness=if(publicSequence){withTimeout(30_000){firstSource.await()};"first-source-check"}else "fixture-ready"
            suspend fun saveWindow() {
                activeStartRevision=writer.revision
                withContext(Dispatchers.IO){repeat(30){sample->
                    withContext(recorder.diagnostics.sample.asContextElement(sample)) {
                        val start=System.nanoTime()-recorder.origin;val checksBefore=sourceChecks.get();val rowsBefore=indexRows.get()
                        val text=if(activeMode=="OBSERVE")"Synthetic indexed edit $sample"else "Synthetic concurrent edit $sample"
                        val result=repository.save(SaveNote(id(),writer.id,writer.revision,writer.title,text))
                        val end=System.nanoTime()-recorder.origin
                        assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                        activeSaves.put(JSONObject().put("sample",sample).put("startNs",start).put("endNs",end).put("elapsedMs",(end-start)/1e6)
                            .put("committed",true).put("revision",writer.revision).put("sourceChecksStart",checksBefore).put("sourceChecksEnd",sourceChecks.get())
                            .put("indexRowsStart",rowsBefore).put("indexRowsEnd",indexRows.get()))
                    }
                }}
            }
            saveWindow()
            direct?.await()?.let{assertEquals(targetBook,it.items.single().note.id);assertEquals(targetPage,it.items.single().pageId);assertEquals(20_000,it.pendingIndex)}
            recorder.awaitWorkers();recorder.complete();assertEquals(0,stageDropped.get())
            repeat(30){sample->assertOuterPair(recorder.events.filter{it.sample==sample},true)}
            finishWindow(true)
            if(publicSequence) {
                // Original public pause check, outside the recorded windows. No artificial
                // pauses are introduced into the natural direct/observe save distributions.
                search.stageObserver=null;search.indexBatchStepObserver=null
                val pauseEntered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
                search.scanObserver={_,count->if(count==1){pauseEntered.complete(Unit);check(release.await(30,TimeUnit.SECONDS))}}
                val paused=async(Dispatchers.IO){search.read(request)};jobs+=paused
                try {
                    withTimeout(30_000){pauseEntered.await()}
                    val saving=async(Dispatchers.IO){repository.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Paused reader edit"))};jobs+=saving
                    val committedWhilePaused=withTimeoutOrNull(750){saving.await();true}?:false
                    release.countDown();assertTrue(saving.await() is SaveResult.Committed)
                    assertEquals(targetPage,paused.await().items.single().pageId)
                    assertTrue("Independent WAL reader must not reserve the Room writer",committedWhilePaused)
                } finally { release.countDown();withContext(NonCancellable){paused.cancelAndJoin()} }
                writer=checkNotNull(repository.read(writer.id));assertEquals(32L,writer.revision)
                recorder.awaitWorkers()
                recorder.reset();stages.clear();stageCount.set(0);stageDropped.set(0);sourceChecks.set(0);indexRows.set(0)
                activeMode="OBSERVE";activeSaves=JSONArray();activeReadiness="not-ready"
                search.scanObserver={_,_->sourceChecks.incrementAndGet()}
                search.stageObserver={label,_,_,read,_,count->stage("read",label,read,count)}
                search.indexBatchStepObserver={stage("index",it)}
                recorder.enabled=true;windowOpen=true
                jobs+=launch(Dispatchers.Main){search.observe(flowOf(request.copy(generation=2))).collect{result->
                    assertFalse(result.failed);assertEquals(targetBook,result.items.single().note.id);assertEquals(targetPage,result.items.single().pageId)
                }}
                withTimeout(30_000){firstBatch.await()};activeReadiness="first-index-commit"
                saveWindow()
            }
            withContext(NonCancellable){jobs.forEach{it.cancel()};jobs.joinAll()}
            recorder.awaitWorkers();recorder.complete();assertEquals(0,stageDropped.get())
            repeat(30){sample->assertOuterPair(recorder.events.filter{it.sample==sample},true)}
            if(windowOpen)finishWindow(true)
            assertEquals(if(publicSequence)62L else 31L,writer.revision);assertEquals(writer,repository.read(writer.id))
            search.stageObserver=null;search.scanObserver=null;search.indexObserver=null;search.indexBatchStepObserver=null
            assertEquals(writer.id,search.read(request.copy(query=writer.text)).items.single().note.id)
        } catch(failure:Throwable){primaryFailure=failure;throw failure
        } finally {
            var cleanupFailure:Throwable?=null
            fun record(failure:Throwable){val previous=cleanupFailure;if(previous==null)cleanupFailure=failure else if(previous!==failure)previous.addSuppressed(failure)}
            try{withContext(NonCancellable){jobs.forEach{it.cancel()};jobs.joinAll()};recorder.awaitWorkers()}catch(failure:Throwable){record(failure)}
            search.stageObserver=null;search.scanObserver=null;search.indexObserver=null;search.indexBatchStepObserver=null
            recorder.enabled=false
            if(windowOpen)finishWindow(false)
            try {
              for(window in windows) {
                val mode=window.mode;val events=window.events
                emit("LIBRARY_SAVE_BOUNDARY_RESULT",JSONObject().put("schema",1).put("diagnosticOnly",true).put("seed",4101)
                    .put("mode",mode).put("readiness",window.readiness).put("fixture",fixture).put("startRevision",window.startRevision).put("saves",window.saves))
                events.chunked(96).forEachIndexed{part,chunk->emit("LIBRARY_SAVE_BOUNDARY_EVENTS",JSONObject().put("schema",1).put("mode",mode).put("part",part)
                    .put("events",JSONArray().apply{chunk.forEach{e->put(JSONObject().put("stage",e.stage).put("sample",e.sample).put("task",e.task)
                        .put("transaction",e.transaction).put("depth",e.depth).put("successRequested",e.success).put("outcome",e.outcome)
                        .put("wallNs",e.wall).put("threadCpuNs",e.cpu).put("threadId",e.thread))}}))}
                window.reads.chunked(96).forEachIndexed{part,chunk->emit("LIBRARY_SAVE_BOUNDARY_READS",JSONObject().put("schema",1).put("mode",mode).put("part",part)
                    .put("events",JSONArray().apply{chunk.forEach{e->put(JSONObject().put("owner",e.owner).put("stage",e.stage).put("read",e.read)
                        .put("count",e.count).put("wallNs",e.wall).put("threadCpuNs",e.cpu).put("threadId",e.thread))}}))}
                emit("LIBRARY_SAVE_BOUNDARY_END",JSONObject().put("schema",1).put("mode",mode).put("completed",window.completed).put("committedSaves",window.saves.length())
                    .put("recordedEvents",events.size).put("recordedReadEvents",window.reads.size).put("droppedEvents",window.dropped).put("droppedReadEvents",window.droppedReads).put("invalid",window.invalid))
              }
            }catch(failure:Throwable){record(failure)}
            try{db.close()}catch(failure:Throwable){record(failure)}
            try{context.deleteDatabase(name)}catch(failure:Throwable){record(failure)}
            cleanupFailure?.let{cleanup->primaryFailure?.let{if(it!==cleanup)it.addSuppressed(cleanup)}?:throw cleanup}
        }
    }

    private suspend fun fixtureEvidence(db:NoteDatabase):JSONObject=db.withTransaction {
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE notes SET updatedAt=1700000000000 WHERE updatedAt>1000000000000")
        sql.execSQL("UPDATE note_revisions SET committedAt=1700000000000 WHERE committedAt>1000000000000")
        val digest=java.security.MessageDigest.getInstance("SHA-256");val counts=JSONObject()
        fun add(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        for(table in listOf("notes","note_revisions","notebook_workspace","notebook_pages","page_search_text","library_search_pending")) {
            add(table.toByteArray(Charsets.UTF_8));var rows=0
            sql.query("SELECT * FROM $table ORDER BY 1,2").use{cursor->
                cursor.columnNames.forEach{add(it.toByteArray(Charsets.UTF_8))}
                while(cursor.moveToNext()) { rows++;for(column in 0 until cursor.columnCount) {
                    val type=cursor.getType(column);add(byteArrayOf(type.toByte()))
                    add(when(type){android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf();android.database.Cursor.FIELD_TYPE_BLOB->cursor.getBlob(column)
                        else->cursor.getString(column).toByteArray(Charsets.UTF_8)})
                }}
            };counts.put(table,rows)
        }
        assertEquals(501,counts.getInt("notes"));assertEquals(20_001,counts.getInt("notebook_pages"));assertEquals(20_000,counts.getInt("page_search_text"))
        val settings=JSONObject()
        for(key in listOf("page_size","auto_vacuum","journal_mode","synchronous"))sql.query("PRAGMA $key").use{it.moveToFirst();settings.put(key,if(key=="journal_mode")it.getString(0)else it.getLong(0))}
        assertEquals(1024L,settings.getLong("page_size"));assertEquals(1L,settings.getLong("auto_vacuum"));assertEquals("wal",settings.getString("journal_mode"));assertEquals(2L,settings.getLong("synchronous"))
        JSONObject().put("rowCounts",counts).put("logicalSha256",digest.digest().joinToString(""){"%02x".format(it)}).put("writerSqlite",settings)
    }
}
