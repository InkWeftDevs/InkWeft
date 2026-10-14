// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Separate diagnostic entry: preserves the frozen common fixture and its assertions.
 * No values from this opt-in run are substituted into the common performance screen. */
class RasterCausalDiagnosticsTest {

    private val pairSeed by lazy{checkNotNull(InstrumentationRegistry.getArguments().getString("pairSeed")).toLong().also{check(it==4101L)}}
    private val pairRandom by lazy{java.util.Random(pairSeed)}
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
        JSONObject().put("label",label).put("seed",pairSeed)
            .put("logicalSha256",digest.digest().joinToString(""){"%02x".format(it)})
            .put("rowCounts",counts).put("writerSqlite",settings)
    }

    /** Bounded, thread-safe collection only; no formatting or I/O in worker callbacks. */
    private class Samples<T> {
        private val values=mutableListOf<T>()
        var dropped=0;private set
        @Synchronized fun add(value:T){if(values.size<128)values.add(value)else dropped++}
        @Synchronized fun snapshot():List<T> = values.toList()
    }
    private fun frameJson(frame:RasterFrameDiagnostics.Snapshot)=JSONObject()
        .put("phase",frame.phase).put("generation",frame.generation).put("revision",frame.revision)
        .put("timesNanos",JSONObject(frame.times.mapKeys{it.key.name}))
        .put("threadIds",JSONObject(frame.threads.mapKeys{it.key.name}))
        .put("notificationRequests",frame.notificationRequests).put("notificationCallbacks",frame.notificationCallbacks)
        .put("notificationsSuperseded",frame.notificationsSuperseded)
        .put("nativeAttempts",frame.nativeAttempts).put("nativeCompleted",frame.nativeCompleted)
        .put("nativePreparationAndBuildWallNanos",frame.nativeWallNanos)
        .put("nativePreparationAndBuildThreadCpuNanos",frame.nativeCpuNanos?:JSONObject.NULL)
    private fun metadataJson(phase:String,layer:Int,t:InkRasterMetadataTiming)=JSONObject()
        .put("phase",phase).put("layer",layer).put("regions",t.regions)
        .put("preparationBatches",t.preparationBatches).put("preparationRows",t.preparationRows)
        .put("queryBatches",t.queryBatches).put("metadataRows",t.metadataRows)
        .put("admissionAndPreparationNanos",t.admissionAndPreparationNanos)
        .put("queryAndDigestNanos",t.queryAndDigestNanos).put("queryAndDigestThreadCpuNanos",t.queryAndDigestCpuNanos?:JSONObject.NULL)
        .put("hexAndResultNanos",t.hexAndResultNanos).put("hexAndResultThreadCpuNanos",t.hexAndResultCpuNanos?:JSONObject.NULL)
        .put("queryStartThreadId",t.queryStartThreadId).put("hexStartThreadId",t.hexStartThreadId).put("hexEndThreadId",t.hexEndThreadId)

    // Keep actual Jobs, including jobs replaced by a later draw. A global counter
    // or clear() alone cannot prove that this test's worker finally has completed.
    private class RasterJobs {
        private val field=StreamedInkRaster::class.java.getDeclaredField("job").apply{isAccessible=true}
        private val jobs=linkedSetOf<Job>()
        private val baselineJobs=RenderResources.inFlightJobs.get()
        private val baselineGeometry=RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L
        var joined=false;private set
        fun remember(raster:StreamedInkRaster){(field.get(raster) as Job?)?.let{jobs.add(it);joined=false}}
        suspend fun clearAndJoin(rasters:List<StreamedInkRaster>)=withContext(NonCancellable){
            joined=false
            withContext(Dispatchers.Main){rasters.forEach{remember(it);it.clear()};jobs.forEach{it.cancel()}}
            // Deliberately no withTimeoutOrNull: a stuck worker must not let the
            // runner start the next method or close a database it can still use.
            jobs.joinAll()
            joined=true
            assertTrue("All owned raster jobs must complete",jobs.all{it.isCompleted})
            assertEquals("Raster worker count after join",baselineJobs,RenderResources.inFlightJobs.get())
            assertEquals("Geometry ownership after join",baselineGeometry,RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L)
            jobs.clear()
        }
    }
    @Test fun oneFourEightLayerAppendReportsFrameStagesAndMatchesColdFrames()=runBlocking{
        assumeTrue(InstrumentationRegistry.getArguments().getString("rasterCausalDiagnostics")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val name="tile-local-${id()}.db"
        var openedDatabase:NoteDatabase?=null;var allocatedTarget:Bitmap?=null;var allocatedReference:Bitmap?=null
        val events=Channel<Unit>(Channel.CONFLATED);val active=mutableListOf<StreamedInkRaster>();val full=mutableListOf<StreamedInkRaster>()
        val jobs=RasterJobs();var failed=false
        var observedRepository:InkRepository?=null
        var primaryFailure:Throwable?=null
        fun raster()=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)}).apply{diagnostics=RasterFrameDiagnostics()}
        fun stroke(x:Float,y:Float,color:Int=Color.BLUE)=InkStroke(id(),InkPen.PEN,color,5f,InkTool.STYLUS,
            listOf(InkSample(x,y,0,world=true),InkSample(x+15,y+5,10,world=true)),true)
        try{
            val db=NoteDatabase.open(context,name).also{openedDatabase=it}
            val target=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888).also{allocatedTarget=it}
            val reference=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888).also{allocatedReference=it}
            for(count in listOf(1,4,8)){
                val page=WorkspaceRepository(db).create("局部重绘 $count 层",true,PaperStyle.BLANK,operationId=id()).id
                val layers=List(count){UserLayer(id(),"层 $it",locked=count>1&&it==count-1)}
                val values=List(320){stroke(150f+(it%20)*130,150f+(it/20)*95)}
                db.withTransaction{
                    if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                    values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
                }
                val memberships=values.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),layers[i%count].id)}
                val repo=InkRepository(db);observedRepository=repo;repo.authoringRepository.storePage(page,page,1,PageAuthoring(UserLayers(layers,layers.first().id,memberships)))
                val viewport=CanvasViewport(1500.0,1000.0,1.0);val epoch=Any()
                var source=InkRenderSource(repo,page,1,values.map{it.id}.toSet(),1,appendGeneration=epoch)
                val rectangles=repo.visibleLayerBounds(page,1,1,CanvasBounds(0.0,0.0,3000.0,2000.0)).mapValues{(_,b)->Rect(b.left.toInt()-2,b.top.toInt()-2,b.right.toInt()+3,b.bottom.toInt()+3)}
                val budgets=StreamedLayerLayout.budgets(rectangles)
                val metadata=Samples<Pair<String,InkRasterMetadataTiming>>()
                val reads=Samples<Pair<String,ReadTiming>>()
                fun observe(phase:String,rasters:List<StreamedInkRaster>){
                    rasters.forEach{it.diagnostics!!.phase=phase}
                    repo.rasterMetadataObserver={metadata.add(phase to it)}
                    repo.readObserver={reads.add(phase to it)}
                }
                withContext(Dispatchers.Main){repeat(count){active+=raster();full+=raster()}}
                suspend fun draw(rasters:List<StreamedInkRaster>,bitmap:Bitmap):Boolean=withContext(Dispatchers.Main){
                    bitmap.eraseColor(Color.WHITE);val canvas=Canvas(bitmap);var ready=true
                    layers.forEachIndexed{i,l->
                        jobs.remember(rasters[i])
                        try{rasters[i].draw(canvas,3000,2000,viewport,1.0,true,false,source,null,emptySet(),l.id,rectangles.getValue(l.id),budgets.getValue(l.id));ready=ready&&rasters[i].frameReady}
                        finally{jobs.remember(rasters[i])}
                    }
                    ready
                }
                suspend fun ready(phase:String,rasters:List<StreamedInkRaster>,bitmap:Bitmap):Double{
                    observe(phase,rasters)
                    try{
                        val start=System.nanoTime()
                        withTimeout(60_000){while(!draw(rasters,bitmap)){events.receive();check(!failed)}}
                        return (System.nanoTime()-start)/1_000_000.0
                    }finally{repo.rasterMetadataObserver=null;repo.readObserver=null}
                }
                val fixture=fixtureEvidence(db,"raster-$count-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","ink_pages","ink_strokes","canvas_authoring","page_layer_memberships"))
                val first=ready("first",active,target)
                assertEquals(320,active.sumOf{it.lastBuild!!.decoded})
                val added=stroke(170f,170f,Color.RED)
                // Keep a stable layer configuration; adding membership does not change its
                // configuration revision. Use the normal scoped author commit.
                val scope=LayerWriteScope(layers.first().id,1)
                assertTrue(repo.save(CommitInk(id(),page,1,InkMutation.Add(added),scope)) is InkCommitResult.Committed)
                source=source.copy(revision=2,committedIds=source.committedIds+added.id)
                val local=ready("local",active,target)
                val cold=ready("full",full,reference)
                val metadataMs=active.sumOf{it.lastBuild!!.metadataMs};val rasterMs=active.sumOf{it.lastBuild!!.rasterMs};val publicationMs=active.sumOf{it.lastBuild!!.publicationMs}
                val localDecoded=active.sumOf{it.lastBuild!!.decoded};val localTiles=active.sumOf{it.lastBuild!!.rebuilt}
                // Preserve the common fixture's counter lookup outside its timed operations.
                val nativeStrokeBuilds=active.map{raster->raster.lastBuild!!.let{stats->
                    (stats.javaClass.methods.firstOrNull{it.name=="getNativeStrokeBuilds"&&it.parameterCount==0}?.invoke(stats) as? Number)?.toLong()
                }}.takeIf{counts->counts.all{it!=null}}?.sumOf{it!!}
                val totalTiles=full.sumOf{it.lastBuild!!.rebuilt}
                assertTrue("Local decoding $localDecoded versus full 321",localDecoded in 1 until 321)
                assertTrue("Local tiles $localTiles versus $totalTiles",localTiles in 1 until totalTiles)
                assertEquals(321,full.sumOf{it.lastBuild!!.decoded})
                assertTrue("All updated frame pixels equal the forced full build",target.sameAs(reference))
                assertTrue(active.sumOf{it.lastBuild!!.bitmapBytes}<=StreamedLayerLayout.PIXELS*4)
                // A destructive update cannot display old pixels while waiting, and must
                // match a clean frame after the replacement completes.
                withContext(Dispatchers.Main){full.forEach{it.clear()}}
                assertTrue(repo.save(CommitInk(id(),page,2,InkMutation.Visibility(listOf(added.id),false),scope)) is InkCommitResult.Committed)
                source=source.copy(revision=3,appendGeneration=Any())
                observe("hidden-local",active)
                withContext(Dispatchers.Main){target.eraseColor(Color.WHITE);layers.forEachIndexed{i,l->
                    jobs.remember(active[i]);try{active[i].draw(Canvas(target),3000,2000,viewport,1.0,true,false,source,null,emptySet(),l.id,rectangles.getValue(l.id),budgets.getValue(l.id))}finally{jobs.remember(active[i])}
                };assertEquals(Color.WHITE,target.getPixel(175,171))}
                ready("hidden-local",active,target);ready("hidden-full",full,reference);assertTrue(target.sameAs(reference))
                val evidence=JSONObject().put("layers",count).put("initialStrokes",320).put("finalStrokes",321)
                    .put("firstCompleteMs",first).put("appendCompleteMs",local).put("forcedFullCompleteMs",cold)
                    .put("localDecoded",localDecoded).put("fullDecoded",321).put("localRebuiltTiles",localTiles).put("fullRebuiltTiles",totalTiles)
                    .put("metadataMs",metadataMs).put("rasterMs",rasterMs).put("publicationMs",publicationMs)
                    .put("pixelsEqual",true).put("bitmapBytes",active.sumOf{it.lastBuild!!.bitmapBytes})
                nativeStrokeBuilds?.let{evidence.put("nativeStrokeBuilds",it)}
                jobs.clearAndJoin(active+full)
                repo.rasterMetadataObserver=null;repo.readObserver=null
                val frames=JSONArray()
                for((role,rasters)in listOf("active" to active,"full" to full))rasters.forEachIndexed{layer,raster->
                    val storage=checkNotNull(raster.diagnostics)
                    assertEquals("No diagnostic frame may be dropped",0,storage.dropped)
                    for(frame in storage.snapshots()){
                        assertTrue(frame.times.containsKey(RasterFrameDiagnostics.Stage.WORKER_FINALLY))
                        assertTrue(frame.times.containsKey(RasterFrameDiagnostics.Stage.READY_DRAW_END))
                        assertFalse(frame.times.containsKey(RasterFrameDiagnostics.Stage.FAILED))
                        frames.put(frameJson(frame).put("role",role).put("layer",layer))
                    }
                    raster.diagnostics=null
                }
                assertEquals("All initial/local/full/hidden frames are represented",count*5,frames.length())
                assertEquals(0,metadata.dropped);assertEquals(0,reads.dropped)
                val metadataJson=JSONArray(metadata.snapshot().map{(phase,t)->
                    val layer=layers.indexOfFirst{it.id==t.layer};check(layer>=0)
                    metadataJson(phase,layer,t)
                })
                val readJson=JSONArray(reads.snapshot().map{(phase,t)->JSONObject().put("phase",phase).put("kind",t.kind.name)
                    .put("freezeNanos",t.freezeNanos).put("decodeNanos",t.decodeNanos).put("entries",t.entries)
                    .put("payloadBytes",t.payloadBytes).put("decodedPoints",t.decodedPoints).put("candidateRows",t.candidateRows)})
                val retained=RenderResources.snapshot()
                val resources=JSONObject()
                listOf("totalBytes","budgetBytes","peakTrackedBytes","inFlightJobs","cancelledJobs",
                    "cacheBytes","in_flightBytes","activeBytes","live_inkBytes").forEach{resources.put(it,retained.getValue(it))}
                resources.put("geometryReservationBytes",retained["category.ink-geometry-reservation"]?:0L)
                val output=JSONObject().put("scope","DIAGNOSTIC_ONLY").put("seed",pairSeed).put("fixture",fixture)
                    .put("result",evidence).put("frames",frames).put("metadata",metadataJson).put("reads",readJson)
                    .put("joined",jobs.joined).put("resources",resources)
                assertTrue("Finite diagnostic output",output.toString().toByteArray().size<=256*1024)
                emit("RASTER_CAUSAL_DIAGNOSTIC",output)
                active.clear();full.clear()
            }
        }catch(error:Throwable){primaryFailure=error;throw error}
        finally{
            observedRepository?.rasterMetadataObserver=null;observedRepository?.readObserver=null
            var cleanupFailure:Throwable?=null
            fun recordCleanup(error:Throwable){
                val first=primaryFailure?:cleanupFailure
                if(first==null)cleanupFailure=error else if(first!==error)first.addSuppressed(error)
            }
            fun cleanup(action:()->Unit){try{action()}catch(error:Throwable){recordCleanup(error)}}
            try{jobs.clearAndJoin(active+full)}
            catch(error:Throwable){recordCleanup(error)}
            if(jobs.joined){
                cleanup{events.close()}
                cleanup{allocatedTarget?.recycle()}
                cleanup{allocatedReference?.recycle()}
                cleanup{openedDatabase?.close()}
                cleanup{check(context.deleteDatabase(name)){"DIAGNOSTIC_DATABASE_DELETE_FAILED"}}
            }
            cleanupFailure?.let{throw it}
        }
    }
}
