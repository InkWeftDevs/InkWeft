// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
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

/** Opt-in conditional arrivals, never a replacement for the frozen natural exposure probe.
 * The author is ready before GC starts. Its notification follows a successful batch guard;
 * GC never waits for the author, delays a DELETE, or retries to obtain more exposure. */
class HistoryDeletionCausalTest {
    @Test fun sameRoomWriterConditionalDeleteArrival()=runProbe(false)
    @Test fun separateRoomWriterConditionalDeleteArrival()=runProbe(true)

    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","$marker $value\n")
        })
    }

    private fun runProbe(separate:Boolean):Unit=runBlocking{
        assumeTrue("Explicit synthetic diagnostic only",InstrumentationRegistry.getArguments()
            .getString("historyGcContentionDiagnostic")=="true")
        val records=mutableListOf<JSONObject>();var complete=false;var failure:Throwable?=null
        try{
            repeat(30){trial(it,separate,records)}
            complete=true
        }catch(problem:Throwable){failure=problem}
        finally{try{
            val timed=records.filter{!it.isNull("saveMs")}
            val exposed=timed.filter{it.getBoolean("deletionTransactionExposed")}
            fun distribution(values:List<JSONObject>,quantiles:Boolean):JSONObject{
                val samples=values.map{it.getDouble("saveMs")};val sorted=samples.sorted();val n=sorted.size
                return JSONObject().put("count",n).put("sampleIndices",JSONArray(values.map{it.getInt("sample")}))
                    .put("saveSamplesMs",JSONArray(samples)).put("maxMs",sorted.lastOrNull()?:JSONObject.NULL)
                    .put("p50Ms",if(quantiles&&n>0)sorted[(n+1)/2-1]else JSONObject.NULL)
                    .put("p95Ms",if(quantiles&&n>0)sorted[(95*n+99)/100-1]else JSONObject.NULL)
            }
            emit("HISTORY_GC_CAUSAL_SUMMARY",JSONObject()
                .put("scenario","CONDITIONAL_DELETE_ARRIVAL").put("replacesNaturalAcceptance",false)
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
                .put("exposedSaves",distribution(exposed,complete&&exposed.size>=20)))
        }catch(problem:Throwable){
            // Evidence export must not replace the original functional failure.
            if(failure==null)failure=problem else if(failure!==problem)failure!!.addSuppressed(problem)
        }}
        failure?.let{throw it}
        Unit
    }

    private suspend fun trial(sample:Int,separate:Boolean,records:MutableList<JSONObject>){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="history-delete-causal-${UUID.randomUUID()}.db"
        fun id(label:String)=UUID.nameUUIDFromBytes("history-delete-causal-$sample-$label".toByteArray(Charsets.UTF_8)).toString()
        val record=JSONObject().put("sample",sample).put("scenario","CONDITIONAL_DELETE_ARRIVAL")
            .put("replacesNaturalAcceptance",false).put("connection",if(separate)"separate-room" else "same-room")
            .put("fixtureSourceVersions",1281).put("fixtureDetachedSources",1280).put("fixtureExplicitRoots",0)
            .put("fixtureLatestRetained",1).put("batchLimit",256).put("artificialGates",false)
            .put("committed",false).put("saveOutcome","NOT_STARTED").put("saveWatchdogMs",5000)
            .put("eventCapacity",64).put("phaseCapacity",32)
            .put("writerEntryNs",JSONObject.NULL).put("pureSqliteLockWaitMs",JSONObject.NULL)
        var database:NoteDatabase?=null;var other:NoteDatabase?=null;var failure:Throwable?=null
        var stage="open-fixture";var authorStage="not-started"
        var collectorStart:Long?=null;var collectorEnd:Long?=null;var collectorFailure:String?=null
        var authorReadyNs:Long?=null;var notificationNs:Long?=null
        var saveStart:Long?=null;var saveEnd:Long?=null;var beforeReceipt:Long?=null;var afterRenameTransaction:Long?=null
        var finishStart:Long?=null;var finishEnd:Long?=null
        var first:HistoryStorageReport?=null;var finish:HistoryStorageReport?=null;var finishScans=0
        // One GC coroutine writes these bounded arrays. They are read only after its join.
        // No JSON, I/O, growing list or wait occurs in either callback.
        val events=arrayOfNulls<HistoryGcBatchEvent>(64);val eventNs=LongArray(64);val hookNs=LongArray(64);var eventCount=0
        val phases=arrayOfNulls<String>(32);val phaseNs=LongArray(32);var phaseCount=0
        fun remember(problem:Throwable){
            if(failure==null)failure=problem else if(failure!==problem)failure!!.addSuppressed(problem)
        }
        try{
            val db=NoteDatabase.open(context,name);database=db
            stage="create-fixture"
            val created=WorkspaceRepository(db).create("Synthetic conditional deletion",false,PaperStyle.BLANK,operationId=id("create"))
            assertTrue(NoteRepository(db).save(SaveNote(id("body"),created.id,created.revision,created.title,
                "Synthetic preserved body $sample")) is SaveResult.Committed)
            val source=id("source")
            val stroke=InkStroke(id("stroke"),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("Synthetic source","",listOf(stroke),false,PaperStyle.BLANK).encode()
            db.withTransaction{repeat(1281){i->db.sourceVersions().insert(
                StudySourceRevisionRow(source,i+1L,created.id,created.id,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}}
            val authorDb=if(separate)NoteDatabase.open(context,name)else db;other=authorDb
            val initial=checkNotNull(NoteRepository(authorDb).read(created.id))
            val title="Synthetic conditional rename $sample"
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
            coroutineScope{
                val ready=CompletableDeferred<Unit>();val deleting=CompletableDeferred<Unit>()
                // Created outside the GC transaction context, on an independent dispatcher.
                val author=async(Dispatchers.Default){
                    authorStage="waiting-for-delete";authorReadyNs=System.nanoTime();ready.complete(Unit)
                    withTimeout(10_000){deleting.await()}
                    authorStage="rename";record.put("saveOutcome","THREW").put("committed",JSONObject.NULL)
                    saveStart=System.nanoTime()
                    val saved=try{withTimeout(5_000){NoteRepository(authorDb){point->when(point){
                        NoteFaultPoint.BEFORE_RENAME_RECEIPT->beforeReceipt=System.nanoTime()
                        NoteFaultPoint.AFTER_RENAME_TRANSACTION->afterRenameTransaction=System.nanoTime()
                    }}.rename(command)}}finally{saveEnd=System.nanoTime()}
                    record.put("saveOutcome",if(saved is SaveResult.Committed)"COMMITTED" else saved.javaClass.simpleName)
                        .put("committed",saved is SaveResult.Committed)
                    assertTrue(saved is SaveResult.Committed)
                    val committed=saved as SaveResult.Committed
                    record.put("savedRevision",committed.note.revision)
                    assertEquals(initial.revision+1,committed.note.revision)
                    assertEquals(title,committed.note.title);assertEquals(initial.text,committed.note.text)
                    authorStage="complete"
                }
                var collecting:Deferred<Unit>?=null
                try{
                    stage="await-author-ready";withTimeout(10_000){ready.await()}
                    stage="collect-and-author"
                    val collector=async(Dispatchers.IO){
                        collectorStart=System.nanoTime()
                        try{first=HistoryStorage(db,phase={phase->
                            check(phaseCount<phases.size){"Synthetic phase buffer full"}
                            phases[phaseCount]=phase;phaseNs[phaseCount]=System.nanoTime();phaseCount++
                        },batchObserver={event->
                            val begin=System.nanoTime();check(eventCount<events.size){"Synthetic batch buffer full"}
                            val index=eventCount++;events[index]=event;eventNs[index]=begin
                            if(event.phase=="before-delete"&&notificationNs==null){
                                notificationNs=System.nanoTime();deleting.complete(Unit)
                            }
                            hookNs[index]=System.nanoTime()-begin
                        }).collectDetached()}
                        catch(problem:Throwable){collectorFailure=problem.javaClass.simpleName;throw problem}
                        finally{collectorEnd=System.nanoTime()}
                    }
                    collecting=collector
                    // The author owns its original 10s notification/5s rename watchdogs.
                    author.await()
                    withTimeout(10_000){collector.await()}
                }finally{withContext(NonCancellable){
                    author.cancel();collecting?.cancel();author.join();collecting?.join()
                }}
            }
            stage="finish-collection";finishStart=System.nanoTime()
            try{finish=HistoryStorage(db){if(it=="after-roots")finishScans++}.collectDetached()}
            finally{finishEnd=System.nanoTime()}
            stage="verify"
            val initialReport=checkNotNull(first);val finalReport=checkNotNull(finish)
            assertNotNull(notificationNs);assertTrue(initialReport.detachedSources in 256L..1280L)
            assertEquals(0L,initialReport.detachedSources%256)
            assertEquals(initialReport.detachedSources/256,(0 until phaseCount).count{phases[it]=="after-batch"}.toLong())
            val starts=(0 until eventCount).filter{events[it]?.phase=="before-delete"}
            assertEquals(initialReport.detachedSources,starts.sumOf{checkNotNull(events[it]).candidates.toLong()})
            for(i in starts){
                val event=checkNotNull(events[i])
                assertEquals(event.expectedDataVersion,event.observedDataVersion)
                assertEquals(event.expectedChanges,event.observedChanges)
                val ended=(i+1 until eventCount).single{events[it]?.afterSequence==event.afterSequence&&events[it]?.phase=="after-delete"}
                val returned=(ended+1 until eventCount).single{events[it]?.afterSequence==event.afterSequence&&events[it]?.phase=="transaction-returned"}
                assertTrue(eventNs[i]<=eventNs[ended]&&eventNs[ended]<=eventNs[returned])
            }
            assertFalse(finalReport.collectionDeferred)
            assertEquals(1280L,initialReport.detachedSources+finalReport.detachedSources)
            val retained=db.sourceVersions().all();assertEquals(listOf(1281L),retained.map{it.revision})
            assertArrayEquals(bytes,retained.single().snapshot)
            val finalNote=checkNotNull(NoteRepository(db).read(created.id))
            assertEquals(initial.revision+1,finalNote.revision);assertEquals(title,finalNote.title);assertEquals(initial.text,finalNote.text)
            val before=checkNotNull(beforeReceipt);val after=checkNotNull(afterRenameTransaction)
            assertTrue(checkNotNull(saveStart)<=before&&before<=after&&after<=checkNotNull(saveEnd))
            record.put("finalRevision",finalNote.revision).put("contentVerified",true).put("retainedRevisions",JSONArray(listOf(1281L)))
            stage="complete"
        }catch(problem:Throwable){remember(problem)}
        finally{
            try{if(other!==database)other?.close()}catch(problem:Throwable){remember(problem)}
            try{database?.close()}catch(problem:Throwable){remember(problem)}
            try{if(database!=null)check(context.deleteDatabase(name)){"Synthetic fixture cleanup failed"}}
            catch(problem:Throwable){remember(problem)}
            try{
            val begin=saveStart;val end=saveEnd
            val covered=(0 until phaseCount).filter{phases[it]=="before-batch-commit"&&begin!=null&&end!=null&&phaseNs[it]>=begin&&phaseNs[it]<=end}
            val intervals=JSONArray();val rawEvents=JSONArray()
            for(i in 0 until eventCount){
                val event=checkNotNull(events[i])
                rawEvents.put(JSONObject().put("phase",event.phase).put("atNs",eventNs[i]).put("callbackNs",hookNs[i])
                    .put("afterSequence",event.afterSequence).put("candidates",event.candidates)
                    .put("expectedDataVersion",event.expectedDataVersion).put("expectedChanges",event.expectedChanges)
                    .put("observedDataVersion",event.observedDataVersion).put("observedChanges",event.observedChanges)
                    .put("guardMatched",event.expectedDataVersion==event.observedDataVersion&&event.expectedChanges==event.observedChanges))
                if(event.phase=="before-delete"){
                    val deleted=(i+1 until eventCount).firstOrNull{events[it]?.afterSequence==event.afterSequence&&events[it]?.phase=="after-delete"}
                    val returned=(i+1 until eventCount).firstOrNull{events[it]?.afterSequence==event.afterSequence&&events[it]?.phase=="transaction-returned"}
                    intervals.put(JSONObject().put("afterSequence",event.afterSequence).put("candidates",event.candidates)
                        .put("deleteStartNs",eventNs[i]).put("deleteEndNs",deleted?.let{eventNs[it]}?:JSONObject.NULL)
                        .put("transactionReturnedNs",returned?.let{eventNs[it]}?:JSONObject.NULL)
                        .put("saveStartedDuringDelete",begin!=null&&deleted!=null&&begin>=eventNs[i]&&begin<=eventNs[deleted]))
                }
            }
            record.put("status",if(failure==null)"PASS" else "FAIL").put("stage",stage).put("authorStage",authorStage)
                .put("failureClass",failure?.javaClass?.simpleName?:JSONObject.NULL)
                .put("suppressedFailureClasses",JSONArray(failure?.suppressed?.map{it.javaClass.simpleName}?:emptyList<String>()))
                .put("collectorFailureClass",collectorFailure?:JSONObject.NULL)
                .put("authorReadyNs",authorReadyNs?:JSONObject.NULL).put("notificationNs",notificationNs?:JSONObject.NULL)
                .put("collectorStartNs",collectorStart?:JSONObject.NULL).put("collectorEndNs",collectorEnd?:JSONObject.NULL)
                .put("saveStartNs",begin?:JSONObject.NULL).put("saveEndNs",end?:JSONObject.NULL)
                .put("saveMs",if(begin!=null&&end!=null)(end-begin)/1_000_000.0 else JSONObject.NULL)
                .put("beforeRenameReceiptNs",beforeReceipt?:JSONObject.NULL).put("afterRenameTransactionNs",afterRenameTransaction?:JSONObject.NULL)
                .put("firstDeleted",first?.detachedSources?:JSONObject.NULL).put("firstDeferred",first?.collectionDeferred?:JSONObject.NULL)
                .put("firstRootScans",(0 until phaseCount).count{phases[it]=="after-roots"})
                .put("firstCommittedBatches",(0 until phaseCount).count{phases[it]=="after-batch"})
                .put("finishStartNs",finishStart?:JSONObject.NULL).put("finishEndNs",finishEnd?:JSONObject.NULL)
                .put("finishDeleted",finish?.detachedSources?:JSONObject.NULL).put("finishDeferred",finish?.collectionDeferred?:JSONObject.NULL)
                .put("finishRootScans",finishScans).put("deletionTransactionExposed",covered.isNotEmpty())
                .put("coveredBeforeBatchCommitNs",JSONArray(covered.map{phaseNs[it]}))
                .put("phases",JSONArray((0 until phaseCount).map{JSONObject().put("phase",phases[it]).put("atNs",phaseNs[it])}))
                .put("batchEvents",rawEvents).put("deleteIntervals",intervals)
            records.add(record);emit("HISTORY_GC_CAUSAL_TRIAL",record)
            }catch(problem:Throwable){remember(problem)}
        }
        failure?.let{throw it}
    }
}
