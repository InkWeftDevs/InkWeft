// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Debug
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/** Additional opt-in boundaries on the unchanged after-scan arrival workload.
 * No pause, retry, extra transaction or larger fixture manufactures DELETE exposure.
 * BEGIN elapsed is not pure SQLite lock wait. This never replaces the frozen probe. */
class HistoryDeletionBoundaryDiagnosticsTest {
    @Test fun sameRoomNaturalArrivalBoundaries()=runProbe(false)
    @Test fun separateRoomNaturalArrivalBoundaries()=runProbe(true)

    private data class Phase(val name:String,val atNs:Long)
    private val boundaryColumns=listOf("stage","ownerToken","task","transaction","depth",
        "successRequested","outcome","atNs","cpuNs","thread")
    private data class Boundary(val stage:String,val owner:Int,val task:Long,val transaction:Long,
        val depth:Int,val success:Boolean,val outcome:String,val atNs:Long,val cpuNs:Long,val thread:Long) {
        // Fixed columns preserve every value; only repeated JSON field names are removed.
        fun json()=JSONArray(listOf(stage,owner,task,transaction,depth,success,outcome,atNs,cpuNs,thread))
    }
    private class Recorder(val database:String,private val recordedOwner:Int) {
        @Volatile var enabled=false
        private val slots=AtomicReferenceArray<Boundary>(512)
        private val count=AtomicInteger()
        val dropped=AtomicInteger()
        private val pending=ConcurrentHashMap.newKeySet<Long>()
        val diagnostics=SaveBoundaryDiagnostics { stage,owner,task,transaction,depth,success,outcome ->
            // Track setup tasks too: a trailing worker exit must not enter a later window.
            if(stage=="writer-submit")pending.add(task)
            // Author events alone explain its wait; all tasks still enter/leave pending.
            if(enabled&&owner==recordedOwner) {
                val event=Boundary(stage,owner,task,transaction,depth,success,outcome,
                    System.nanoTime(),Debug.threadCpuTimeNanos(),Thread.currentThread().id)
                val index=count.getAndIncrement()
                if(index<slots.length())slots.set(index,event)else dropped.incrementAndGet()
            }
            // Publish the terminal event before signalling quiescence.
            if(stage=="writer-run-exit"||stage=="writer-submit-failed")pending.remove(task)
        }
        fun events():List<Boundary> = (0 until minOf(count.get(),slots.length())).map{checkNotNull(slots.get(it))}
        suspend fun awaitWorkers()=withTimeout(5_000) {
            while(pending.isNotEmpty())yield()
        }
        fun validate() {
            assertFalse(diagnostics.invalid);assertEquals(0,dropped.get())
            val events=events()
            val submitted=events.filter{it.stage=="writer-submit"}.map{it.task}
            val terminal=events.filter{it.stage=="writer-run-exit"||it.stage=="writer-submit-failed"}.map{it.task}
            assertEquals("Each recorded submit must have exactly one terminal",submitted.sorted(),terminal.sorted())
            assertEquals(submitted.size,submitted.toSet().size)
        }
    }
    private data class Batch(val event:HistoryGcBatchEvent,val atNs:Long,val cpuNs:Long,val thread:Long,var callbackNs:Long=0) {
        fun json()=JSONObject().put("phase",event.phase).put("afterSequence",event.afterSequence)
            .put("candidates",event.candidates).put("expectedDataVersion",event.expectedDataVersion)
            .put("expectedChanges",event.expectedChanges).put("observedDataVersion",event.observedDataVersion)
            .put("observedChanges",event.observedChanges).put("atNs",atNs).put("cpuNs",cpuNs)
            .put("thread",thread).put("callbackNs",callbackNs)
    }
    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","$marker $value\n")
        })
    }

    private fun runProbe(separate:Boolean)=runBlocking{
        assumeTrue("Explicit synthetic diagnostic only",InstrumentationRegistry.getArguments()
            .getString("historyGcBoundaryDiagnostics")=="true")
        val records=mutableListOf<JSONObject>();var complete=false;var failure:Throwable?=null
        try{
            repeat(30){sample->trial(sample,separate,records)}
            complete=true
        }catch(problem:Throwable){failure=problem}
        finally{try{
            // Keep all completed call intervals, including a failed call if a trial fails.
            // Partial runs never get promoted to a complete 30-sample distribution.
            val timed=records.filter{!it.isNull("saveMs")}
            val exposed=timed.filter{it.getBoolean("deletionTransactionExposed")}
            val intersected=timed.filter{it.getBoolean("deleteWindowIntersected")}
            val startedInside=timed.filter{it.getBoolean("saveStartedDuringDelete")}
            fun distribution(values:List<JSONObject>,quantiles:Boolean):JSONObject{
                val samples=values.map{it.getDouble("saveMs")};val sorted=samples.sorted();val n=sorted.size
                return JSONObject().put("count",n).put("sampleIndices",JSONArray(values.map{it.getInt("sample")}))
                    .put("saveSamplesMs",JSONArray(samples)).put("maxMs",sorted.lastOrNull()?:JSONObject.NULL)
                    .put("p50Ms",if(quantiles&&n>0)sorted[(n+1)/2-1]else JSONObject.NULL)
                    .put("p95Ms",if(quantiles&&n>0)sorted[(95*n+99)/100-1]else JSONObject.NULL)
            }
            emit("HISTORY_GC_BOUNDARY_SUMMARY",JSONObject()
                .put("scenario","AFTER_SCAN_ARRIVAL_BOUNDARIES").put("replacesFrozenProbe",false)
                .put("connection",if(separate)"separate-room" else "same-room")
                .put("plannedTrials",30).put("recordedTrials",records.size)
                .put("passedTrials",records.count{it.getString("status")=="PASS"})
                .put("committedSaves",records.count{it.optBoolean("committed",false)})
                .put("failedTrials",records.count{it.getString("status")=="FAIL"})
                .put("unknownCommitOutcomes",records.count{it.isNull("committed")})
                .put("notStartedSaves",records.count{it.getString("saveOutcome")=="NOT_STARTED"})
                .put("functionalStatus",if(complete)"PASS" else "FAILED_INCOMPLETE")
                .put("exposureStatus",when{!complete->"RUN_INCOMPLETE";exposed.size<20->"INSUFFICIENT_EXPOSURE";else->"SUFFICIENT_EXPOSURE"})
                .put("minimumExposedSamplesForQuantiles",20).put("latencyAcceptance","NOT_EVALUATED")
                .put("saveWatchdogMs",5000).put("watchdogIsLatencyAcceptance",false)
                .put("quantileDefinition","nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28")
                .put("allSaves",distribution(timed,complete&&timed.size==30))
                .put("exposedSaves",distribution(exposed,complete&&exposed.size>=20))
                .put("deleteWindowIntersectedSaves",distribution(intersected,complete&&intersected.size>=20))
                .put("saveStartedDuringDeleteSaves",distribution(startedInside,complete&&startedInside.size>=20))
                .put("pureSqliteLockWaitMs",JSONObject.NULL))
        }catch(problem:Throwable){if(failure==null)failure=problem else if(failure!==problem)failure!!.addSuppressed(problem)}}
        failure?.let{throw it}
        Unit
    }

    private suspend fun trial(sample:Int,separate:Boolean,records:MutableList<JSONObject>){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="history-delete-boundary-${UUID.randomUUID()}.db"
        fun id(label:String)=UUID.nameUUIDFromBytes("history-delete-exposure-$sample-$label".toByteArray(Charsets.UTF_8)).toString()
        val record=JSONObject().put("sample",sample).put("connection",if(separate)"separate-room" else "same-room")
            .put("scenario","AFTER_SCAN_ARRIVAL_BOUNDARIES").put("replacesFrozenProbe",false)
            .put("fixtureSourceVersions",1281).put("fixtureDetachedSources",1280).put("fixtureExplicitRoots",0)
            .put("fixtureLatestRetained",1).put("artificialGates",false).put("committed",false)
            .put("saveOutcome","NOT_STARTED").put("saveWatchdogMs",5000)
            .put("batchLimit",256).put("authorBoundaries",JSONObject.NULL)
            .put("boundaryCapacityPerDatabase",512).put("batchCapacity",64).put("phaseCapacity",32)
            .put("boundaryScope","AUTHOR_ONLY")
            .put("pureSqliteLockWaitMs",JSONObject.NULL)
        val gcToken=sample*3;val authorToken=gcToken+1;val finishToken=gcToken+2
        val collectorRecorder=Recorder("collector",authorToken)
        val authorRecorder=if(separate)Recorder("author",authorToken)else collectorRecorder
        val recorders=listOf(collectorRecorder,authorRecorder).distinct()
        record.put("gcOwnerToken",gcToken).put("authorOwnerToken",authorToken).put("finishOwnerToken",finishToken)
        val batches=arrayOfNulls<Batch>(64);var batchCount=0;var batchDropped=0
        val phaseSlots=arrayOfNulls<Phase>(32);var phaseCount=0;var phaseDropped=0
        var workersExited=false;var helpersClosed=false;var fixtureDeleted=false
        var database:NoteDatabase?=null;var other:NoteDatabase?=null;var failure:Throwable?=null
        var stage="open-fixture"
        var collectorStart:Long?=null;var collectorEnd:Long?=null;var collectorFailure:String?=null
        var saveStart:Long?=null;var saveEnd:Long?=null;var beforeReceipt:Long?=null;var afterRename:Long?=null
        var finishStart:Long?=null;var finishEnd:Long?=null
        var first:HistoryStorageReport?=null;var finish:HistoryStorageReport?=null
        var finishScans=0
        fun remember(problem:Throwable){
            if(failure==null)failure=problem else if(failure!==problem)failure!!.addSuppressed(problem)
        }
        try{
            val db=NoteDatabase.open(context,name,collectorRecorder.diagnostics);database=db
            stage="create-fixture"
            val created=WorkspaceRepository(db).create("Synthetic deletion exposure",false,PaperStyle.BLANK,operationId=id("create"))
            val seeded=NoteRepository(db).save(SaveNote(id("body"),created.id,created.revision,created.title,"Synthetic preserved body $sample"))
            assertTrue(seeded is SaveResult.Committed)
            val source=id("source")
            val stroke=InkStroke(id("stroke"),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("Synthetic source","",listOf(stroke),false,PaperStyle.BLANK).encode()
            db.withTransaction{repeat(1281){i->db.sourceVersions().insert(
                StudySourceRevisionRow(source,i+1L,created.id,created.id,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}}
            val authorDb=if(separate)NoteDatabase.open(context,name,authorRecorder.diagnostics)else db;other=authorDb
            // Open the second helper and capture the author outside the measured work.
            val initial=checkNotNull(NoteRepository(authorDb).read(created.id))
            val title="Synthetic concurrent rename $sample"
            val command=RenameNote(id("rename"),created.id,initial.revision,title)
            record.put("initialRevision",initial.revision)
            suspend fun writerSettings(target:NoteDatabase)=target.withTransaction{
                val values=JSONObject();val sql=target.openHelper.writableDatabase
                for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
                    sql.query("PRAGMA $setting").use{it.moveToFirst();values.put(setting,if(setting=="journal_mode")it.getString(0)else it.getLong(0))}
                };values
            }
            val writerSqlite=writerSettings(db)
            record.put("writerSqlite",writerSqlite).put("authorWriterSqlite",if(separate)writerSettings(authorDb)else writerSqlite)
            for(settings in listOf(writerSqlite,record.getJSONObject("authorWriterSqlite"))){
                assertEquals(1024L,settings.getLong("page_size"));assertEquals(1L,settings.getLong("auto_vacuum"))
                assertEquals("wal",settings.getString("journal_mode"));assertEquals(2L,settings.getLong("synchronous"))
            }
            recorders.forEach{it.awaitWorkers();it.validate();it.enabled=true}
            coroutineScope{
                val scanned=CompletableDeferred<Unit>()
                val collecting=async(Dispatchers.IO+collectorRecorder.diagnostics.sample.asContextElement(gcToken)){
                    collectorStart=System.nanoTime()
                    try{HistoryStorage(db,phase={phase->
                        if(phaseCount<phaseSlots.size)phaseSlots[phaseCount++]=Phase(phase,System.nanoTime())else phaseDropped++
                        // Notify only. The GC coroutine continues straight into its writer race.
                        if(phase=="after-scan")scanned.complete(Unit)
                    },batchObserver={event->
                        val begin=System.nanoTime()
                        if(batchCount<batches.size) {
                            val batch=Batch(event,begin,Debug.threadCpuTimeNanos(),Thread.currentThread().id)
                            batches[batchCount++]=batch;batch.callbackNs=System.nanoTime()-begin
                        }else batchDropped++
                    }).collectDetached().also{first=it}}
                    catch(problem:Throwable){collectorFailure=problem.javaClass.simpleName;throw problem}
                    finally{collectorEnd=System.nanoTime()}
                }
                try{
                    stage="await-scan";withTimeout(10_000){scanned.await()}
                    stage="author-rename";record.put("saveOutcome","THREW").put("committed",JSONObject.NULL)
                    // No new dispatcher or outer transaction: tag the original rename call.
                    val saved=withContext(authorRecorder.diagnostics.sample.asContextElement(authorToken)) {
                        saveStart=System.nanoTime()
                        try{withTimeout(5_000){NoteRepository(authorDb){point->when(point){
                            NoteFaultPoint.BEFORE_RENAME_RECEIPT->beforeReceipt=System.nanoTime()
                            NoteFaultPoint.AFTER_RENAME_TRANSACTION->afterRename=System.nanoTime()
                        }}.rename(command)}}finally{saveEnd=System.nanoTime()}
                    }
                    record.put("saveOutcome",if(saved is SaveResult.Committed)"COMMITTED" else saved.javaClass.simpleName)
                        .put("committed",saved is SaveResult.Committed)
                    assertTrue(saved is SaveResult.Committed)
                    val committed=saved as SaveResult.Committed
                    record.put("savedRevision",committed.note.revision)
                    assertEquals(initial.revision+1,committed.note.revision)
                    assertEquals(title,committed.note.title);assertEquals(initial.text,committed.note.text)
                    stage="await-first-collection";withTimeout(10_000){collecting.await()}
                }finally{withContext(NonCancellable){collecting.cancelAndJoin()}}
            }
            stage="finish-collection";finishStart=System.nanoTime()
            try{withContext(collectorRecorder.diagnostics.sample.asContextElement(finishToken)) {
                finish=HistoryStorage(db){if(it=="after-roots")finishScans++}.collectDetached()
            }}
            finally{finishEnd=System.nanoTime()}
            recorders.forEach{it.awaitWorkers();it.enabled=false;it.validate()};workersExited=true
            stage="verify"
            assertEquals(0,batchDropped);assertEquals(0,phaseDropped)
            val phases=phaseSlots.take(phaseCount).map{checkNotNull(it)}
            val initialReport=checkNotNull(first);val finalReport=checkNotNull(finish)
            assertTrue(initialReport.detachedSources in 0L..1280L)
            assertEquals(0L,initialReport.detachedSources%256)
            assertEquals(initialReport.detachedSources/256,phases.count{it.name=="after-batch"}.toLong())
            assertFalse(finalReport.collectionDeferred)
            assertEquals(1280L,initialReport.detachedSources+finalReport.detachedSources)
            val retained=db.sourceVersions().all();assertEquals(listOf(1281L),retained.map{it.revision})
            assertArrayEquals(bytes,retained.single().snapshot)
            val finalNote=checkNotNull(NoteRepository(db).read(created.id))
            assertEquals(initial.revision+1,finalNote.revision);assertEquals(title,finalNote.title);assertEquals(initial.text,finalNote.text)
            record.put("finalRevision",finalNote.revision).put("contentVerified",true).put("retainedRevisions",JSONArray(listOf(1281L)))
            val author=authorRecorder.events().filter{it.owner==authorToken}
            record.put("authorBoundaries",validateAuthor(author,checkNotNull(saveStart),checkNotNull(beforeReceipt),checkNotNull(afterRename),checkNotNull(saveEnd)))
            val observed=batches.take(batchCount).map{checkNotNull(it)}
            val deletes=observed.filter{it.event.phase=="before-delete"}
            assertEquals(initialReport.detachedSources,deletes.sumOf{it.event.candidates.toLong()})
            for(begin in deletes) {
                assertEquals(begin.event.expectedDataVersion,begin.event.observedDataVersion)
                assertEquals(begin.event.expectedChanges,begin.event.observedChanges)
                val end=observed.single{it.event.phase=="after-delete"&&it.event.afterSequence==begin.event.afterSequence}
                val returned=observed.single{it.event.phase=="transaction-returned"&&it.event.afterSequence==begin.event.afterSequence}
                assertTrue(begin.atNs<=end.atNs&&end.atNs<=returned.atNs)
            }
            stage="complete"
        }catch(problem:Throwable){remember(problem)}
        finally{
            // No child can retain either helper: the collector is cancelled/joined above,
            // including when a save, assertion or phase wait failed.
            // No producer remains after coroutineScope. Its writer Runnable can finish later
            // than the continuation; join its ledger before sealing or closing a helper.
            workersExited=false
            try{withContext(NonCancellable){recorders.forEach{it.awaitWorkers()}};workersExited=true}
            catch(problem:Throwable){remember(problem)}
            recorders.forEach{it.enabled=false}
            try{recorders.forEach{it.validate()}}catch(problem:Throwable){remember(problem)}
            if(workersExited) {
                var authorClosed=true;var collectorClosed=true
                try{if(other!==database)other?.close()}catch(problem:Throwable){authorClosed=false;remember(problem)}
                try{database?.close()}catch(problem:Throwable){collectorClosed=false;remember(problem)}
                helpersClosed=authorClosed&&collectorClosed
                try{if(helpersClosed&&database!=null){check(context.deleteDatabase(name)){"Synthetic fixture cleanup failed"};fixtureDeleted=true}}
                catch(problem:Throwable){remember(problem)}
            }
            try{
            val phases=phaseSlots.take(phaseCount).map{checkNotNull(it)}
            val observed=batches.take(batchCount).map{checkNotNull(it)}
            val begin=saveStart;val end=saveEnd
            val covered=phases.filter{it.name=="before-batch-commit"&&begin!=null&&end!=null&&it.atNs>=begin&&it.atNs<=end}
            val intervals=observed.filter{it.event.phase=="before-delete"}.map{start->
                val stop=observed.singleOrNull{it.event.phase=="after-delete"&&it.event.afterSequence==start.event.afterSequence}
                val returned=observed.singleOrNull{it.event.phase=="transaction-returned"&&it.event.afterSequence==start.event.afterSequence}
                JSONObject().put("afterSequence",start.event.afterSequence).put("candidates",start.event.candidates)
                    .put("deleteStartNs",start.atNs).put("deleteEndNs",stop?.atNs?:JSONObject.NULL)
                    .put("transactionReturnedNs",returned?.atNs?:JSONObject.NULL)
                    .put("saveIntersected",begin!=null&&end!=null&&stop!=null&&maxOf(begin,start.atNs)<minOf(end,stop.atNs))
                    .put("saveStartedDuringDelete",begin!=null&&stop!=null&&begin>=start.atNs&&begin<=stop.atNs)
            }
            record.put("status",if(failure==null)"PASS" else "FAIL").put("stage",stage)
                .put("failureClass",failure?.javaClass?.simpleName?:JSONObject.NULL)
                .put("suppressedFailureClasses",JSONArray(failure?.suppressed?.map{it.javaClass.simpleName}?:emptyList<String>()))
                .put("collectorFailureClass",collectorFailure?:JSONObject.NULL)
                .put("collectorStartNs",collectorStart?:JSONObject.NULL).put("collectorEndNs",collectorEnd?:JSONObject.NULL)
                .put("saveStartNs",begin?:JSONObject.NULL).put("saveEndNs",end?:JSONObject.NULL)
                .put("saveMs",if(begin!=null&&end!=null)(end-begin)/1_000_000.0 else JSONObject.NULL)
                .put("beforeRenameReceiptNs",beforeReceipt?:JSONObject.NULL)
                .put("afterRenameTransactionNs",afterRename?:JSONObject.NULL)
                .put("firstDeleted",first?.detachedSources?:JSONObject.NULL).put("firstDeferred",first?.collectionDeferred?:JSONObject.NULL)
                .put("firstRootScans",phases.count{it.name=="after-roots"}).put("firstCommittedBatches",phases.count{it.name=="after-batch"})
                .put("finishStartNs",finishStart?:JSONObject.NULL).put("finishEndNs",finishEnd?:JSONObject.NULL)
                .put("finishDeleted",finish?.detachedSources?:JSONObject.NULL).put("finishRootScans",finishScans)
                .put("finishDeferred",finish?.collectionDeferred?:JSONObject.NULL)
                .put("deletionTransactionExposed",covered.isNotEmpty())
                .put("deleteWindowIntersected",intervals.any{it.getBoolean("saveIntersected")})
                .put("saveStartedDuringDelete",intervals.any{it.getBoolean("saveStartedDuringDelete")})
                .put("deleteIntervals",JSONArray(intervals)).put("batchEvents",JSONArray(observed.map{it.json()}))
                .put("batchDropped",batchDropped).put("phaseDropped",phaseDropped)
                .put("workersExited",workersExited).put("helpersClosed",helpersClosed).put("fixtureDeleted",fixtureDeleted)
                .put("boundaries",JSONArray(recorders.map{recorder->JSONObject().put("database",recorder.database)
                    .put("count",recorder.events().size).put("dropped",recorder.dropped.get()).put("invalid",recorder.diagnostics.invalid)}))
                .put("coveredBeforeBatchCommitNs",JSONArray(covered.map{it.atNs}))
                .put("phases",JSONArray(phases.map{JSONObject().put("phase",it.name).put("atNs",it.atNs)}))
            records.add(record)
            // Bounded chunks, emitted only after measured work and owned writer exits.
            for(recorder in recorders) {
                val chunks=recorder.events().chunked(48)
                chunks.forEachIndexed{part,events->emit("HISTORY_GC_BOUNDARY_EVENTS",JSONObject()
                    .put("sample",sample).put("connection",if(separate)"separate-room"else"same-room")
                    .put("database",recorder.database).put("part",part).put("parts",chunks.size)
                    .put("eventSchema","author-boundary-v1").put("eventColumns",JSONArray(boundaryColumns))
                    .put("events",JSONArray(events.map{it.json()})))}
            }
            emit("HISTORY_GC_BOUNDARY_TRIAL",record)
            }catch(problem:Throwable){remember(problem)}
        }
        failure?.let{throw it}
    }

    private fun validateAuthor(events:List<Boundary>,start:Long,receipt:Long,returned:Long,end:Long):JSONObject {
        val submit=events.single{it.stage=="writer-submit"}
        val run=events.single{it.stage=="writer-run-enter"}
        val exit=events.single{it.stage=="writer-run-exit"}
        val roomBegin=events.single{it.stage=="room-begin-exit"&&it.depth==1&&it.outcome=="returned"}
        val roomEnd=events.single{it.stage=="room-end-exit"&&it.depth==1}
        // BEGIN can sync triggers in its own SQLite transaction first. Pick the one
        // spanning the actual rename receipt, not that transaction or a nested DAO END.
        val begin=events.filter{it.stage=="sqlite-begin-exit"&&it.depth==1&&it.outcome=="returned"&&it.atNs<=roomBegin.atNs}.maxBy{it.atNs}
        val finish=events.single{it.stage=="sqlite-end-exit"&&it.depth==1&&it.transaction==begin.transaction}
        assertTrue(start<=submit.atNs&&submit.atNs<=run.atNs&&run.atNs<=begin.atNs)
        assertTrue(begin.atNs<=roomBegin.atNs&&roomBegin.atNs<=receipt&&receipt<=finish.atNs)
        assertTrue(finish.atNs<=roomEnd.atNs&&roomEnd.atNs<=returned&&returned<=end)
        assertEquals(submit.task,run.task);assertEquals(run.task,exit.task)
        assertEquals(run.task,begin.task);assertEquals(run.task,roomEnd.task)
        assertEquals(roomBegin.transaction,roomEnd.transaction)
        assertEquals(begin.thread,finish.thread);assertEquals(run.thread,roomEnd.thread)
        assertTrue(roomEnd.success&&finish.success)
        assertEquals("returned",roomEnd.outcome);assertEquals("returned",finish.outcome);assertEquals("returned",exit.outcome)
        assertTrue(events.none{it.stage=="writer-submit-failed"})
        fun point(layer:String,operation:String,edge:String,transaction:Long)=events.single{
            it.stage=="$layer-$operation-$edge"&&it.depth==1&&it.transaction==transaction
        }
        for((layer,transaction) in listOf("room" to roomBegin.transaction,"sqlite" to begin.transaction)) {
            for(operation in listOf("begin","success","end")) {
                val entered=point(layer,operation,"enter",transaction)
                val exited=point(layer,operation,"exit",transaction)
                assertEquals("entered",entered.outcome);assertEquals("returned",exited.outcome)
                assertEquals(entered.thread,exited.thread);assertEquals(run.task,entered.task);assertEquals(run.task,exited.task)
                assertTrue(entered.atNs<=exited.atNs&&entered.cpuNs<=exited.cpuNs)
            }
        }
        val beginEnter=point("sqlite","begin","enter",begin.transaction)
        val endEnter=point("sqlite","end","enter",begin.transaction)
        val points=listOf(start,submit.atNs,run.atNs,beginEnter.atNs,begin.atNs,roomBegin.atNs,
            receipt,endEnter.atNs,finish.atNs,roomEnd.atNs,returned,end)
        assertEquals(points.sorted(),points)
        val names=listOf("requestToSubmit","writerQueue","workerToSqliteBegin","sqliteBeginCall",
            "sqliteBeginToRoomBegin","roomBeginToReceipt","receiptToSqliteEnd","sqliteEndCall",
            "sqliteEndToRoomEnd","roomEndToRenameReturn","renameReturnToCallEnd")
        return JSONObject().put("databaseTask",run.task).put("sqliteTransaction",begin.transaction)
            .put("roomTransaction",roomBegin.transaction).put("writerThread",run.thread)
            .put("segments",JSONArray(names.indices.map{i->JSONObject().put("name",names[i])
                .put("startNs",points[i]).put("endNs",points[i+1]).put("elapsedNs",points[i+1]-points[i])}))
            .put("sqliteBeginThreadCpuNs",begin.cpuNs-beginEnter.cpuNs)
            .put("sqliteEndThreadCpuNs",finish.cpuNs-endEnter.cpuNs)
            .put("pureSqliteLockWaitMs",JSONObject.NULL)
    }
}
