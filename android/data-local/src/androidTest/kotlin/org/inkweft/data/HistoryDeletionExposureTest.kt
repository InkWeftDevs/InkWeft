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
import org.junit.Test
import java.util.UUID

/** A separate, small workload: one rename per fresh 1281-source fixture, 30 trials.
 * No phase callback waits, sleeps, or retries to manufacture deletion exposure.
 * Insufficient exposure is evidence, not a functional failure or a latency pass. */
class HistoryDeletionExposureTest {
    @Test fun sameRoomWriterNaturalDeletionExposure()=runProbe(false)
    @Test fun separateRoomWriterNaturalDeletionExposure()=runProbe(true)

    private data class Phase(val name:String,val atNs:Long)
    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","$marker $value\n")
        })
    }

    private fun runProbe(separate:Boolean)=runBlocking{
        val records=mutableListOf<JSONObject>();var complete=false
        try{
            repeat(30){sample->trial(sample,separate,records)}
            complete=true
        }finally{
            // Keep all completed call intervals, including a failed call if a trial fails.
            // Partial runs never get promoted to a complete 30-sample distribution.
            val timed=records.filter{!it.isNull("saveMs")}
            val exposed=timed.filter{it.getBoolean("deletionTransactionExposed")}
            fun distribution(values:List<JSONObject>,quantiles:Boolean):JSONObject{
                val samples=values.map{it.getDouble("saveMs")};val sorted=samples.sorted();val n=sorted.size
                return JSONObject().put("count",n).put("sampleIndices",JSONArray(values.map{it.getInt("sample")}))
                    .put("saveSamplesMs",JSONArray(samples)).put("maxMs",sorted.lastOrNull()?:JSONObject.NULL)
                    .put("p50Ms",if(quantiles&&n>0)sorted[(n+1)/2-1]else JSONObject.NULL)
                    .put("p95Ms",if(quantiles&&n>0)sorted[(95*n+99)/100-1]else JSONObject.NULL)
            }
            emit("HISTORY_DELETION_EXPOSURE_SUMMARY",JSONObject()
                .put("connection",if(separate)"separate-room" else "same-room")
                .put("plannedTrials",30).put("recordedTrials",records.size)
                .put("passedTrials",records.count{it.getString("status")=="PASS"})
                .put("committedSaves",records.count{it.optBoolean("committed",false)})
                .put("functionalStatus",if(complete)"PASS" else "FAILED_INCOMPLETE")
                .put("exposureStatus",when{!complete->"RUN_INCOMPLETE";exposed.size<20->"INSUFFICIENT_EXPOSURE";else->"SUFFICIENT_EXPOSURE"})
                .put("minimumExposedSamplesForQuantiles",20).put("latencyAcceptance","NOT_EVALUATED")
                .put("saveWatchdogMs",5000).put("watchdogIsLatencyAcceptance",false)
                .put("quantileDefinition","nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28")
                .put("allSaves",distribution(timed,complete&&timed.size==30))
                .put("exposedSaves",distribution(exposed,complete&&exposed.size>=20)))
        }
    }

    private suspend fun trial(sample:Int,separate:Boolean,records:MutableList<JSONObject>){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="history-delete-exposure-${UUID.randomUUID()}.db"
        fun id(label:String)=UUID.nameUUIDFromBytes("history-delete-exposure-$sample-$label".toByteArray(Charsets.UTF_8)).toString()
        val record=JSONObject().put("sample",sample).put("connection",if(separate)"separate-room" else "same-room")
            .put("fixtureSourceVersions",1281).put("fixtureDetachedSources",1280).put("fixtureExplicitRoots",0)
            .put("fixtureLatestRetained",1).put("artificialGates",false).put("committed",false)
            .put("saveOutcome","NOT_STARTED").put("saveWatchdogMs",5000)
        var database:NoteDatabase?=null;var other:NoteDatabase?=null;var failure:Throwable?=null
        var stage="open-fixture";val phases=mutableListOf<Phase>()
        var collectorStart:Long?=null;var collectorEnd:Long?=null;var collectorFailure:String?=null
        var saveStart:Long?=null;var saveEnd:Long?=null;var beforeReceipt:Long?=null
        var finishStart:Long?=null;var finishEnd:Long?=null
        var first:HistoryStorageReport?=null;var finish:HistoryStorageReport?=null
        var finishScans=0
        fun remember(problem:Throwable){
            if(failure==null)failure=problem else if(failure!==problem)failure!!.addSuppressed(problem)
        }
        try{
            val db=NoteDatabase.open(context,name);database=db
            stage="create-fixture"
            val created=WorkspaceRepository(db).create("Synthetic deletion exposure",false,PaperStyle.BLANK,operationId=id("create"))
            val seeded=NoteRepository(db).save(SaveNote(id("body"),created.id,created.revision,created.title,"Synthetic preserved body $sample"))
            assertTrue(seeded is SaveResult.Committed)
            val source=id("source")
            val stroke=InkStroke(id("stroke"),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            val bytes=InkPageFile("Synthetic source","",listOf(stroke),false,PaperStyle.BLANK).encode()
            db.withTransaction{repeat(1281){i->db.sourceVersions().insert(
                StudySourceRevisionRow(source,i+1L,created.id,created.id,0,10.0,10.0,30.0,30.0,stroke.id,bytes))}}
            val authorDb=if(separate)NoteDatabase.open(context,name)else db;other=authorDb
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
            coroutineScope{
                val scanned=CompletableDeferred<Unit>()
                val collecting=async(Dispatchers.IO){
                    collectorStart=System.nanoTime()
                    try{HistoryStorage(db){phase->
                        phases.add(Phase(phase,System.nanoTime()))
                        // Notify only. The GC coroutine continues straight into its writer race.
                        if(phase=="after-scan")scanned.complete(Unit)
                    }.collectDetached().also{first=it}}
                    catch(problem:Throwable){collectorFailure=problem.javaClass.simpleName;throw problem}
                    finally{collectorEnd=System.nanoTime()}
                }
                try{
                    stage="await-scan";withTimeout(10_000){scanned.await()}
                    stage="author-rename";record.put("saveOutcome","THREW").put("committed",JSONObject.NULL)
                    saveStart=System.nanoTime()
                    val saved=try{
                        withTimeout(5_000){
                            NoteRepository(authorDb){point->if(point==NoteFaultPoint.BEFORE_RENAME_RECEIPT)beforeReceipt=System.nanoTime()}.rename(command)
                        }
                    }finally{saveEnd=System.nanoTime()}
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
            try{finish=HistoryStorage(db){if(it=="after-roots")finishScans++}.collectDetached()}
            finally{finishEnd=System.nanoTime()}
            stage="verify"
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
            stage="complete"
        }catch(problem:Throwable){remember(problem)}
        finally{
            // No child can retain either helper: the collector is cancelled/joined above,
            // including when a save, assertion or phase wait failed.
            try{if(other!==database)other?.close()}catch(problem:Throwable){remember(problem)}
            try{database?.close()}catch(problem:Throwable){remember(problem)}
            try{if(database!=null)check(context.deleteDatabase(name)){"Synthetic fixture cleanup failed"}}
            catch(problem:Throwable){remember(problem)}
            val begin=saveStart;val end=saveEnd
            val covered=phases.filter{it.name=="before-batch-commit"&&begin!=null&&end!=null&&it.atNs>=begin&&it.atNs<=end}
            record.put("status",if(failure==null)"PASS" else "FAIL").put("stage",stage)
                .put("failureClass",failure?.javaClass?.simpleName?:JSONObject.NULL)
                .put("suppressedFailureClasses",JSONArray(failure?.suppressed?.map{it.javaClass.simpleName}?:emptyList<String>()))
                .put("collectorFailureClass",collectorFailure?:JSONObject.NULL)
                .put("collectorStartNs",collectorStart?:JSONObject.NULL).put("collectorEndNs",collectorEnd?:JSONObject.NULL)
                .put("saveStartNs",begin?:JSONObject.NULL).put("saveEndNs",end?:JSONObject.NULL)
                .put("saveMs",if(begin!=null&&end!=null)(end-begin)/1_000_000.0 else JSONObject.NULL)
                .put("beforeRenameReceiptNs",beforeReceipt?:JSONObject.NULL)
                .put("firstDeleted",first?.detachedSources?:JSONObject.NULL).put("firstDeferred",first?.collectionDeferred?:JSONObject.NULL)
                .put("firstRootScans",phases.count{it.name=="after-roots"}).put("firstCommittedBatches",phases.count{it.name=="after-batch"})
                .put("finishStartNs",finishStart?:JSONObject.NULL).put("finishEndNs",finishEnd?:JSONObject.NULL)
                .put("finishDeleted",finish?.detachedSources?:JSONObject.NULL).put("finishRootScans",finishScans)
                .put("deletionTransactionExposed",covered.isNotEmpty())
                .put("coveredBeforeBatchCommitNs",JSONArray(covered.map{it.atNs}))
                .put("phases",JSONArray(phases.map{JSONObject().put("phase",it.name).put("atNs",it.atNs)}))
            records.add(record)
            emit("HISTORY_DELETION_EXPOSURE_TRIAL",record)
        }
        failure?.let{throw it}
    }
}
