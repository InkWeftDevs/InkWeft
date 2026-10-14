// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Debug
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controlled mechanism screening, not a public performance window or a causal B/C comparison.
 * The held reader pins real main-database data, then blocks without SQL or busy waiting.
 * Fixed arm order and enabled diagnostics are deliberate limitations of this one-seed test. */
class LibrarySnapshotHoldDiagnosticsTest {
    companion object {
        private const val FIXTURE_SHA="1c69bba77781ba0c0b642309b8e869ff21c9a440fd7714894e67c572b95c9dcf"
        private const val BOUNDARY_CAPACITY=1536
        private const val READ_CAPACITY=128
        private const val MAX_NS=600_000_000_000L
        private val MODES=listOf("IDLE","HELD_SNAPSHOT","ACTIVE_SEARCH")
        private val BOUNDARY_STAGES=listOf("save-request","save-writer-acquired","save-head-read","save-head-written",
            "save-revision-written","save-receipt-written","save-returned","save-failed","writer-submit",
            "writer-run-enter","writer-run-exit","writer-submit-failed","room-begin-enter","room-begin-exit",
            "room-success-enter","room-success-exit","room-end-enter","room-end-exit","sqlite-begin-enter",
            "sqlite-begin-exit","sqlite-success-enter","sqlite-success-exit","sqlite-end-enter","sqlite-end-exit")
        private val READ_STAGES=listOf("read-requested","read-start","connection-open","snapshot-open","drafts-ready",
            "scope-start","scope-end","hits-ready","page-hits-start","page-hits-end","card-hits-start","card-hits-end",
            "page-start","page-end","metadata-start","metadata-end","pending-start","pending-end","snapshot-closed",
            "connection-closed","read-end","read-cancel","read-error","pin-read","hold-enter","hold-exit",
            "pin-recheck","idle-ready","active-ready")
        private val BOUNDARY_CODES=BOUNDARY_STAGES.withIndex().associate{it.value to it.index}
        private val READ_CODES=READ_STAGES.withIndex().associate{it.value to it.index}
        private val OUTCOMES=listOf("entered","returned","threw")
        private fun rows(values:List<LongArray>)=JSONArray().apply{values.forEach{put(JSONArray(it.toList()))}}
    }

    private class Recorder {
        val origin=System.nanoTime()
        @Volatile var enabled=false
        @Volatile var invalidRead=false
        val events=ConcurrentLinkedQueue<LongArray>();val reads=ConcurrentLinkedQueue<LongArray>()
        val dropped=AtomicInteger();val droppedReads=AtomicInteger()
        private val count=AtomicInteger();private val readCount=AtomicInteger()
        private val pending=ConcurrentHashMap.newKeySet<Long>()
        val diagnostics=SaveBoundaryDiagnostics { stage,sample,task,transaction,depth,success,outcome ->
            if(stage=="writer-submit")pending.add(task)
            if(enabled) {
                val wall=System.nanoTime()-origin;val cpu=Debug.threadCpuTimeNanos();val thread=Thread.currentThread().id
                val code=checkNotNull(BOUNDARY_CODES[stage]);val result=OUTCOMES.indexOf(outcome);check(result>=0)
                if(count.incrementAndGet()<=BOUNDARY_CAPACITY)events.add(longArrayOf(code.toLong(),sample.toLong(),task,
                    transaction,depth.toLong(),if(success)1 else 0,result.toLong(),wall,cpu,thread))else dropped.incrementAndGet()
            }
            if(stage=="writer-run-exit"||stage=="writer-submit-failed")pending.remove(task)
        }
        fun read(owner:Int,stage:String,id:Long=0,count:Int=-1):Long {
            val wall=System.nanoTime()-origin;val cpu=Debug.threadCpuTimeNanos();val thread=Thread.currentThread().id
            val code=READ_CODES[stage]
            if(code==null){invalidRead=true;return wall}
            if(readCount.incrementAndGet()<=READ_CAPACITY)reads.add(longArrayOf(owner.toLong(),code.toLong(),id,count.toLong(),wall,cpu,thread))
            else droppedReads.incrementAndGet()
            return wall
        }
        suspend fun awaitWorkers()=withTimeout(5_000) {
            while(pending.isNotEmpty())yield()
            val copy=events.toList()
            val submitted=copy.filter{it[0]==8L}.map{it[2]}.toSet()
            val exited=copy.filter{it[0]==10L||it[0]==11L}.map{it[2]}.toSet()
            assertTrue("Every recorded writer submission must exit",exited.containsAll(submitted))
        }
        fun complete() {
            assertFalse(diagnostics.invalid||invalidRead);assertEquals(0,dropped.get());assertEquals(0,droppedReads.get())
            for(e in events){assertEquals(10,e.size);assertTrue(e[0] in 0L..23L&&e[1] in -1L..29L)
                assertTrue(e[2] in 0L..999_999L&&e[3] in 0L..999_999L&&e[4] in 0L..8L&&e[5] in 0L..1L&&e[6] in 0L..2L)
                assertTrue(e[7] in 0L..MAX_NS&&e[8] in 0L..MAX_NS&&e[9] in 1L..2_147_483_647L)}
            for(e in reads){assertEquals(7,e.size);assertTrue(e[0] in 0L..1L&&e[1] in 0L..28L&&e[2] in 0L..1L&&e[3] in -1L..20_001L)
                assertTrue(e[4] in 0L..MAX_NS&&e[5] in 0L..MAX_NS&&e[6] in 1L..2_147_483_647L)}
        }
    }

    private fun emit(kind:String,value:JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","LIBRARY_SNAPSHOT_CONTROL_$kind $value\n")
        })
    }

    @Test fun idleHeldSnapshotAndActiveSearchSaveBoundaries()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("librarySnapshotHoldDiagnostics")=="true")
        for(mode in MODES)runArm(mode)
    }

    private suspend fun runArm(mode:String)=coroutineScope {
        val context=ApplicationProvider.getApplicationContext<Context>();val random=java.util.Random(4101L)
        fun id()=UUID(random.nextLong(),random.nextLong()).toString()
        val name="snapshot-control-$mode-${id()}.db";val recorder=Recorder()
        val db=NoteDatabase.open(context,name,recorder.diagnostics);val repository=NoteRepository(db)
        val search=LibrarySearchRepository(db);val release=CountDownLatch(1);val ready=CompletableDeferred<Unit>()
        var reader:Deferred<Unit>?=null;var fixture:JSONObject?=null;var primary:Throwable?=null
        val saves=ArrayList<LongArray>();var coverage=false;var completed=false;var finalRevision=-1L
        var readerBefore=-1L;var readerAfter=-1L
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
            val authorId=writer.id
            fixture=fixtureEvidence(db)
            val request=LibrarySearchRequest(1,repository.observeNoteHeaders().first(),emptyMap(),"target-only")
            recorder.awaitWorkers();recorder.enabled=true
            if(mode=="ACTIVE_SEARCH") {
                search.stageObserver={stage,_,_,read,_,count->recorder.read(1,stage,read,count)}
                search.scanObserver={_,count->if(count==1){recorder.read(0,"active-ready");ready.complete(Unit)}}
                reader=async(Dispatchers.IO){
                    val result=search.read(request)
                    assertEquals(targetBook,result.items.single().note.id);assertEquals(targetPage,result.items.single().pageId)
                    assertEquals(20_000,result.pendingIndex)
                }
            } else {
                val path=db.openHelper.readableDatabase.path
                reader=async(Dispatchers.IO){
                    SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY){throw android.database.sqlite.SQLiteDatabaseCorruptException("Synthetic reader corruption")}.use{sql->
                        recorder.read(0,"connection-open");sql.beginTransactionDeferred()
                        var readFailure:Throwable?=null
                        try {
                            recorder.read(0,"snapshot-open")
                            fun revision()=sql.query("SELECT revision FROM notes WHERE id=?",arrayOf(authorId)).use{c->assertTrue(c.moveToFirst());c.getLong(0)}
                            readerBefore=revision();assertEquals(1L,readerBefore);recorder.read(0,"pin-read",count=readerBefore.toInt())
                            if(mode=="HELD_SNAPSHOT") {
                                recorder.read(0,"hold-enter");ready.complete(Unit)
                                check(release.await(30,TimeUnit.SECONDS)){"Held reader release timed out"}
                                recorder.read(0,"hold-exit")
                                readerAfter=revision();recorder.read(0,"pin-recheck",count=readerAfter.toInt());assertEquals(1L,readerAfter)
                            }
                        } catch(failure:Throwable){readFailure=failure;throw failure
                        } finally {
                            try{sql.endTransaction();recorder.read(0,"snapshot-closed")}
                            catch(endFailure:Throwable){readFailure?.let{if(it!==endFailure)it.addSuppressed(endFailure)}?:throw endFailure}
                        }
                    }
                    recorder.read(0,"connection-closed")
                    if(mode=="IDLE"){recorder.read(0,"idle-ready");ready.complete(Unit)}
                }
            }
            withTimeout(30_000){ready.await()}
            withContext(Dispatchers.IO){repeat(30){sample->
                withContext(recorder.diagnostics.sample.asContextElement(sample)) {
                    val command=SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic controlled edit $sample")
                    val start=System.nanoTime()-recorder.origin
                    val result=repository.save(command)
                    val end=System.nanoTime()-recorder.origin
                    assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                    saves+=longArrayOf(sample.toLong(),start,end,writer.revision,1)
                }
            }}
            release.countDown();withTimeout(30_000){checkNotNull(reader).await()}
            recorder.awaitWorkers();recorder.complete()
            val boundaryEvents=recorder.events.toList()
            repeat(30){sample->assertAuthorChain(boundaryEvents,sample)}
            saves.forEachIndexed{sample,row->assertEquals(5,row.size);assertEquals(sample.toLong(),row[0])
                assertTrue(row[1] in 0L..MAX_NS&&row[2] in row[1]..MAX_NS);assertEquals(sample+2L,row[3]);assertEquals(1L,row[4])}
            finalRevision=checkNotNull(repository.read(authorId)).revision
            assertEquals(31L,finalRevision);assertEquals(writer,repository.read(authorId))
            coverage=checkCoverage(mode,recorder.reads.toList(),saves)
            assertTrue("Controlled reader did not cover the complete save window; no automatic retry",coverage)
            completed=true
        } catch(failure:Throwable){primary=failure;throw failure
        } finally {
            release.countDown();var cleanupFailure:Throwable?=null;var cleanupErrors=0
            var readerExited=false;var writersExited=false;var databaseClosed=false;var databaseDeleted=false
            fun record(failure:Throwable){cleanupErrors++;val old=cleanupFailure;if(old==null)cleanupFailure=failure else if(old!==failure)old.addSuppressed(failure)}
            try{withContext(NonCancellable){withTimeout(5_000){reader?.cancelAndJoin()}}
                readerExited=reader?.isCompleted!=false;check(readerExited){"Reader still active"}
            }catch(failure:Throwable){record(failure)}
            try{withContext(NonCancellable){recorder.awaitWorkers()};writersExited=true}catch(failure:Throwable){record(failure)}
            search.stageObserver=null;search.scanObserver=null;recorder.enabled=false
            // An unjoined reader or undrained Room Runnable must retain its synthetic DB.
            // Closing/deleting it could race live native calls and hide the original fault.
            if(readerExited&&writersExited) {
                try{db.close();databaseClosed=true}catch(failure:Throwable){record(failure)}
                if(databaseClosed)try{check(context.deleteDatabase(name)){"Synthetic database deletion failed"};databaseDeleted=true}catch(failure:Throwable){record(failure)}
            }
            completed=completed&&cleanupFailure==null&&readerExited&&writersExited&&databaseClosed&&databaseDeleted
            try {
                emit("RESULT",JSONObject().put("schema",1).put("diagnosticOnly",true).put("seed",4101).put("mode",mode)
                    .put("readiness",when(mode){"IDLE"->"reader-closed";"HELD_SNAPSHOT"->"main-snapshot-pinned";else->"first-source-check"})
                    .put("fixture",fixture?:JSONObject.NULL).put("startRevision",1).put("finalRevision",finalRevision)
                    .put("readerBeforeRevision",readerBefore).put("readerAfterRevision",readerAfter).put("saves",rows(saves)))
                recorder.events.toList().chunked(96).forEachIndexed{part,chunk->emit("EVENTS",JSONObject().put("schema",1).put("mode",mode).put("part",part).put("events",rows(chunk)))}
                recorder.reads.toList().chunked(96).forEachIndexed{part,chunk->emit("READS",JSONObject().put("schema",1).put("mode",mode).put("part",part).put("events",rows(chunk)))}
                emit("END",JSONObject().put("schema",1).put("mode",mode).put("completed",completed).put("coverageValid",coverage)
                    .put("committedSaves",saves.size).put("recordedEvents",recorder.events.size).put("recordedReadEvents",recorder.reads.size)
                    .put("droppedEvents",recorder.dropped.get()).put("droppedReadEvents",recorder.droppedReads.get())
                    .put("invalid",recorder.diagnostics.invalid||recorder.invalidRead).put("readerExited",readerExited)
                    .put("writersExited",writersExited).put("databaseClosed",databaseClosed).put("databaseDeleted",databaseDeleted).put("cleanupErrors",cleanupErrors))
            }catch(failure:Throwable){record(failure)}
            cleanupFailure?.let{cleanup->primary?.let{if(it!==cleanup)it.addSuppressed(cleanup)}?:throw cleanup}
        }
    }

    private fun assertAuthorChain(events:List<LongArray>,sample:Int) {
        val own=events.filter{it[1]==sample.toLong()}
        fun point(stage:String,depth:Long?=null,transaction:Long?=null)=own.single{
            it[0]==BOUNDARY_CODES.getValue(stage).toLong()&&(depth==null||it[4]==depth)&&(transaction==null||it[3]==transaction)}
        val request=point("save-request");val submit=point("writer-submit");val run=point("writer-run-enter");val exit=point("writer-run-exit")
        val body=point("save-writer-acquired");val returned=point("save-returned")
        val room=listOf("room-begin-enter","room-begin-exit","room-success-enter","room-success-exit","room-end-enter","room-end-exit").map{point(it,1)}
        assertTrue(room.all{it[3]==room[0][3]});assertTrue(room[0][3]>0)
        val sqlBegin=own.filter{it[0]==19L&&it[4]==1L&&it[6]==1L&&it[7]<=body[7]}.maxBy{it[7]}
        val sql=listOf("sqlite-begin-enter","sqlite-begin-exit","sqlite-success-enter","sqlite-success-exit","sqlite-end-enter","sqlite-end-exit").map{point(it,1,sqlBegin[3])}
        val bodyPoints=listOf("save-head-read","save-head-written","save-revision-written","save-receipt-written").map{point(it)}
        val chain=listOf(request,submit,run,room[0],sql[0],sql[1],room[1],body)+bodyPoints+
            listOf(room[2],sql[2],sql[3],room[3],room[4],sql[4],sql[5],room[5],returned)
        assertTrue(chain.zipWithNext().all{(a,b)->a[7]<=b[7]});assertTrue(exit[7]>=room[5][7])
        assertTrue(own.none{it[0]==7L||it[0]==11L||it[6]==2L})
        val worker=own.filter{it[0] !in listOf(0L,6L,8L)}
        assertTrue(worker.all{it[2]==run[2]&&it[9]==run[9]});assertEquals(submit[2],run[2]);assertTrue(run[2]>0)
        assertTrue(worker.zipWithNext().all{(a,b)->a[7]<=b[7]&&a[8]<=b[8]})
        for(layer in listOf(room,sql))for(i in layer.indices){assertEquals(if(i%2==0)0L else 1L,layer[i][6]);assertEquals(if(i>=3)1L else 0L,layer[i][5])}
    }

    private fun checkCoverage(mode:String,reads:List<LongArray>,saves:List<LongArray>):Boolean {
        assertEquals(30,saves.size)
        fun event(owner:Long,stage:String)=reads.single{it[0]==owner&&it[1]==READ_CODES.getValue(stage).toLong()}
        val start=saves.first()[1];val end=saves.last()[2]
        return when(mode) {
            "IDLE"->event(0,"connection-closed")[4]<=event(0,"idle-ready")[4]&&event(0,"idle-ready")[4]<=start
            "HELD_SNAPSHOT"->{
                val pin=event(0,"pin-read");val hold=event(0,"hold-enter");val released=event(0,"hold-exit");val recheck=event(0,"pin-recheck")
                pin[3]==1L&&recheck[3]==1L&&pin[4]<=hold[4]&&hold[4]<=start&&end<=released[4]&&released[4]<=recheck[4]&&
                    reads.all{it[0]==0L&&it[6]==pin[6]}&&recheck[4]<=event(0,"snapshot-closed")[4]
            }
            else->{
                // Require the original pending-source query itself, not just coroutine cleanup,
                // to span all saves. An early completion fails this arm without retry or looping.
                event(1,"snapshot-open")[4]<=event(1,"page-hits-start")[4]&&
                    event(1,"page-hits-start")[4]<=event(0,"active-ready")[4]&&event(0,"active-ready")[4]<=start&&
                    end<=event(1,"page-hits-end")[4]&&event(1,"page-hits-end")[4]<=event(1,"snapshot-closed")[4]&&
                    event(1,"snapshot-closed")[4]<=event(1,"read-end")[4]
            }
        }
    }

    private suspend fun fixtureEvidence(db:NoteDatabase):JSONObject=db.withTransaction {
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE notes SET updatedAt=1700000000000 WHERE updatedAt>1000000000000")
        sql.execSQL("UPDATE note_revisions SET committedAt=1700000000000 WHERE committedAt>1000000000000")
        val digest=java.security.MessageDigest.getInstance("SHA-256");val counts=JSONObject()
        fun add(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        for(table in listOf("notes","note_revisions","notebook_workspace","notebook_pages","page_search_text","library_search_pending")) {
            add(table.toByteArray(Charsets.UTF_8));var count=0
            sql.query("SELECT * FROM $table ORDER BY 1,2").use{cursor->
                cursor.columnNames.forEach{add(it.toByteArray(Charsets.UTF_8))}
                while(cursor.moveToNext()){count++;for(column in 0 until cursor.columnCount){
                    val type=cursor.getType(column);add(byteArrayOf(type.toByte()))
                    add(when(type){android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf();android.database.Cursor.FIELD_TYPE_BLOB->cursor.getBlob(column)
                        else->cursor.getString(column).toByteArray(Charsets.UTF_8)})
                }}
            };counts.put(table,count)
        }
        for((table,count) in mapOf("notes" to 501,"note_revisions" to 501,"notebook_workspace" to 501,
            "notebook_pages" to 20_001,"page_search_text" to 20_000,"library_search_pending" to 20_000))assertEquals(count,counts.getInt(table))
        val settings=JSONObject()
        for(key in listOf("page_size","auto_vacuum","journal_mode","synchronous"))sql.query("PRAGMA $key").use{it.moveToFirst();settings.put(key,if(key=="journal_mode")it.getString(0)else it.getLong(0))}
        assertEquals(1024L,settings.getLong("page_size"));assertEquals(1L,settings.getLong("auto_vacuum"));assertEquals("wal",settings.getString("journal_mode"));assertEquals(2L,settings.getLong("synchronous"))
        val hash=digest.digest().joinToString(""){"%02x".format(it)};assertEquals(FIXTURE_SHA,hash)
        JSONObject().put("rowCounts",counts).put("logicalSha256",hash).put("writerSqlite",settings)
    }
}
