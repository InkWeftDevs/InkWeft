// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Debug
import androidx.room.withTransaction
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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Independent diagnostic, never a replacement for the frozen paired acceptance probe.
 * Readiness differs by mode and is explicit. Differences are not additive causal estimates.
 * CPU values are thread counters, not exclusive CPU across suspended Room operations. */
class LibrarySaveCausalTest {
    private enum class Mode { IDLE, TRACKER, INDEX_ONLY, INDEX_TRACKER, READS_ONLY, FULL }
    private data class Event(val owner:String,val stage:String,val sample:Int,val wall:Long,val cpu:Long,val tid:Long)
    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\n")})
    }
    private suspend fun fixtureEvidence(db:NoteDatabase):JSONObject=db.withTransaction{
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE notes SET updatedAt=1700000000000 WHERE updatedAt>1000000000000")
        sql.execSQL("UPDATE note_revisions SET committedAt=1700000000000 WHERE committedAt>1000000000000")
        val digest=java.security.MessageDigest.getInstance("SHA-256");val counts=JSONObject()
        fun add(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        for(table in listOf("notes","note_revisions","notebook_workspace","notebook_pages","page_search_text","library_search_pending")){
            add(table.toByteArray(Charsets.UTF_8));var rows=0
            sql.query("SELECT * FROM $table ORDER BY 1,2").use{cursor->
                cursor.columnNames.forEach{add(it.toByteArray(Charsets.UTF_8))}
                while(cursor.moveToNext()){
                    rows++
                    for(column in 0 until cursor.columnCount){
                        val type=cursor.getType(column);add(byteArrayOf(type.toByte()))
                        add(when(type){android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf()
                            android.database.Cursor.FIELD_TYPE_BLOB->cursor.getBlob(column)
                            else->cursor.getString(column).toByteArray(Charsets.UTF_8)})
                    }
                }
            }
            counts.put(table,rows)
        }
        assertEquals(501,counts.getInt("notes"));assertEquals(20_000,counts.getInt("page_search_text"))
        return@withTransaction JSONObject().put("rowCounts",counts)
            .put("logicalSha256",digest.digest().joinToString(""){"%02x".format(it)})
    }

    @Test fun separateTrackerReaderAndIndexContention()=runBlocking{
        assumeTrue(InstrumentationRegistry.getArguments().getString("libraryCausalDiagnostics")=="true")
        // Reverse the fixed order in the second block; retain every result. No threshold
        // or performance assertion: these instrumented runs only identify where time goes.
        for(block in 0..1)for(mode in if(block==0)Mode.entries else Mode.entries.reversed())runCase(mode,block)
    }

    private suspend fun runCase(mode:Mode,block:Int)=coroutineScope {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val random=java.util.Random(4101L+block)
        fun id()=UUID(random.nextLong(),random.nextLong()).toString()
        val name="save-causal-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        val jobs=ArrayList<Job>();val events=ConcurrentLinkedQueue<Event>();val overflow=AtomicInteger()
        val eventCount=AtomicInteger();val sample=AtomicInteger(-1);val indexBatch=AtomicInteger(-1)
        val origin=System.nanoTime();var completed=false;var savesStarted=false
        val samples=JSONArray();val indexed=AtomicInteger();var primaryFailure:Throwable?=null
        val repository=NoteRepository(db);val search=LibrarySearchRepository(db)
        fun event(owner:String,stage:String,number:Int){
            val at=System.nanoTime();val cpu=Debug.threadCpuTimeNanos();val tid=Thread.currentThread().id
            if(eventCount.incrementAndGet()<=2048)events.add(Event(owner,stage,number,at-origin,cpu,tid))else overflow.incrementAndGet()
        }
        try{
            val body="Synthetic note body "+"b".repeat(20_000)
            val pageBody="合成页面索引 "+"a".repeat(1024)
            var targetBook="";var targetPage=""
            db.withTransaction{
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
                        sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only"else pageBody))
                    }
                }
            }
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK,operationId=id())
            val fixture=fixtureEvidence(db)
            val request=LibrarySearchRequest(1,repository.observeNoteHeaders().first(),emptyMap(),"target-only")
            val trackerReady=CompletableDeferred<Unit>();val firstBatch=CompletableDeferred<Unit>();val firstSource=CompletableDeferred<Unit>()
            val checks=AtomicInteger()
            repository.saveStageObserver={event("save",it,sample.get())}
            search.stageObserver={stage,_,_,readId,_,_->event("read",stage,readId.toInt())}
            search.scanObserver={_,_->checks.incrementAndGet();firstSource.complete(Unit)}
            val indexStep:(String)->Unit={stage->
                if(stage=="preparing-batch")indexBatch.incrementAndGet()
                event("index",stage,indexBatch.get())
            }
            search.indexBatchStepObserver=indexStep
            val indexCommit:(Int)->Unit={rows->indexed.addAndGet(rows);event("index","committed-rows",indexed.get());firstBatch.complete(Unit)}
            search.indexObserver={_,rows->indexed.set(rows);event("index","committed-rows",rows);firstBatch.complete(Unit)}
            if(mode==Mode.TRACKER||mode==Mode.INDEX_TRACKER){
                jobs+=launch(Dispatchers.IO){db.invalidationTracker.createFlow("notes").collect{
                    event("tracker","notes-invalidation",it.size);trackerReady.complete(Unit)
                }}
                withTimeout(30_000){trackerReady.await()}
            }
            if(mode==Mode.INDEX_ONLY||mode==Mode.INDEX_TRACKER){
                jobs+=launch(Dispatchers.IO){LibraryTextIndex.build(db,onBatchStep=indexStep){indexCommit(it)}}
            }
            if(mode==Mode.READS_ONLY){
                jobs+=launch(Dispatchers.Main){
                    LibrarySearchObservation{r,_->search.read(r)}.results(flowOf(request),
                        db.invalidationTracker.createFlow("notes","notebook_workspace","notebook_pages","page_search_text","ink_pages","study_cards"),
                        MutableStateFlow(0)).collect{result->
                            assertFalse(result.failed);assertEquals(targetBook,result.items.single().note.id)
                            assertEquals(targetPage,result.items.single().pageId)
                        }
                }
            }
            if(mode==Mode.FULL){
                jobs+=launch(Dispatchers.Main){search.observe(flowOf(request)).collect{result->
                    assertFalse(result.failed);assertEquals(targetBook,result.items.single().note.id)
                    assertEquals(targetPage,result.items.single().pageId)
                }}
            }
            val readiness=when(mode){
                Mode.INDEX_ONLY,Mode.INDEX_TRACKER,Mode.FULL->{withTimeout(30_000){firstBatch.await()};"first-index-commit"}
                Mode.READS_ONLY->{withTimeout(30_000){firstSource.await()};"first-source-check"}
                Mode.TRACKER->"tracker-registered"
                Mode.IDLE->"fixture-ready"
            }
            event("control","saves-start",0);savesStarted=true
            withContext(Dispatchers.IO){repeat(30){i->
                sample.set(i);val start=System.nanoTime();val before=indexed.get();val beforeChecks=checks.get()
                val result=repository.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic indexed edit $i"))
                val end=System.nanoTime();val after=indexed.get();val afterChecks=checks.get()
                assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                samples.put(JSONObject().put("sample",i).put("startNs",start-origin).put("endNs",end-origin)
                    .put("elapsedMs",(end-start)/1e6).put("committed",true).put("revision",writer.revision)
                    .put("indexRowsStart",before).put("indexRowsEnd",after)
                    .put("sourceChecksStart",beforeChecks).put("sourceChecksEnd",afterChecks))
            }}
            event("control","saves-end",30);val sourceChecks=checks.get();val rowsAfterSaves=indexed.get()
            withContext(NonCancellable){jobs.forEach{it.cancel()};jobs.joinAll()}
            search.stageObserver=null;search.scanObserver=null;search.indexBatchStepObserver=null;search.indexObserver=null
            repository.saveStageObserver=null
            assertEquals("Synthetic indexed edit 29",repository.read(writer.id)!!.text)
            // A fresh database snapshot must include the new author head even though the
            // request still holds its old directory header. This check is outside timing.
            assertEquals(writer.id,search.read(request.copy(query="Synthetic indexed edit 29")).items.single().note.id)
            assertEquals(0,overflow.get());assertEquals(30,samples.length())
            emit("LIBRARY_SAVE_CAUSAL_RESULT",JSONObject().put("schema",1).put("diagnosticOnly",true)
                .put("mode",mode.name).put("block",block).put("seed",4101+block).put("readiness",readiness)
                .put("fixture",fixture).put("sourceChecks",sourceChecks)
                .put("indexRowsAfterSaves",rowsAfterSaves).put("saves",samples))
            completed=true
        }catch(failure:Throwable){primaryFailure=failure;throw failure
        }finally{
            var cleanupFailure:Throwable?=null
            fun record(failure:Throwable){
                val previous=cleanupFailure
                if(previous==null)cleanupFailure=failure else if(previous!==failure)previous.addSuppressed(failure)
            }
            try{withContext(NonCancellable){jobs.forEach{it.cancel()};jobs.joinAll()}}catch(failure:Throwable){record(failure)}
            repository.saveStageObserver=null;search.stageObserver=null;search.scanObserver=null
            search.indexBatchStepObserver=null;search.indexObserver=null
            try{
                var part=0
                while(events.isNotEmpty()){
                    val batch=JSONArray()
                    repeat(128){events.poll()?.let{e->batch.put(JSONObject().put("owner",e.owner).put("stage",e.stage)
                        .put("sample",e.sample).put("wallNs",e.wall).put("threadCpuNs",e.cpu).put("threadId",e.tid))}}
                    emit("LIBRARY_SAVE_CAUSAL_STAGES",JSONObject().put("schema",1).put("mode",mode.name)
                        .put("block",block).put("part",part++).put("events",batch))
                }
                emit("LIBRARY_SAVE_CAUSAL_END",JSONObject().put("schema",1).put("mode",mode.name).put("block",block)
                    .put("completed",completed).put("savesStarted",savesStarted).put("committedSaves",samples.length())
                    .put("lastAttemptedSample",sample.get()).put("failureOutcome",if(primaryFailure==null)"none" else "unknown")
                    .put("recordedEvents",eventCount.get()-overflow.get()).put("droppedEvents",overflow.get()))
            }catch(failure:Throwable){record(failure)}
            try{db.close()}catch(failure:Throwable){record(failure)}
            try{context.deleteDatabase(name)}catch(failure:Throwable){record(failure)}
            cleanupFailure?.let{cleanup->primaryFailure?.let{if(it!==cleanup)it.addSuppressed(cleanup)}?:throw cleanup}
        }
    }
}
