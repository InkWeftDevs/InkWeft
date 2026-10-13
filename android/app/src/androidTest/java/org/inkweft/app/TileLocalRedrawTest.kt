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
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class TileLocalRedrawTest {
    private fun id()=UUID.randomUUID().toString()
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
    private class InterruptedPanWait:RuntimeException("Synthetic interruption after the initial draw")
    @Test fun interruptedPanWaitJoinsItsWorkerBeforeClosingResources()=runBlocking{
        val failure=runCatching{panAndZoom(interruptFirstWait=true)}.exceptionOrNull()
        assertTrue("The intended wait must be interrupted",failure is InterruptedPanWait)
        assertEquals("Cleanup must not fail",0,failure!!.suppressed.size)
    }
    @Test fun integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture()=runBlocking{panAndZoom()}
    private suspend fun panAndZoom(interruptFirstWait:Boolean=false){
        val diagnostics=InstrumentationRegistry.getArguments().getString("tilePanDiagnostics")=="true"
        val jobs=RasterJobs()
        val context=ApplicationProvider.getApplicationContext<Context>();val name="tile-pan-${id()}.db";val db=NoteDatabase.open(context,name)
        val target=Bitmap.createBitmap(2000,1600,Bitmap.Config.ARGB_8888);val reference=Bitmap.createBitmap(2000,1600,Bitmap.Config.ARGB_8888)
        val events=Channel<Unit>(Channel.CONFLATED);var failed=false
        val cached=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        val cold=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        var primaryFailure:Throwable?=null
        fun evidence(value:JSONObject){InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","TILE_PAN_STAGE $value\n")})}
        try{
            val page=WorkspaceRepository(db).create("平移与缩放",true,PaperStyle.BLANK).id;val repo=InkRepository(db)
            val values=List(96){index->val x=50f+(index%12)*150;val y=50f+(index/12)*180
                InkStroke(id(),if(index%3==0)InkPen.PENCIL else InkPen.PEN,Color.BLUE,5f,InkTool.STYLUS,listOf(InkSample(x,y,0,world=true),InkSample(x+30,y+20,10,world=true)),true)}
            db.withTransaction{if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1);values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}}
            val source=InkRenderSource(repo,page,1,values.map{it.id}.toSet());var viewport=CanvasViewport(1000.0,800.0,1.0)
            suspend fun draw(raster:StreamedInkRaster,bitmap:Bitmap)=withContext(Dispatchers.Main){
                jobs.remember(raster)
                try{bitmap.eraseColor(Color.WHITE);raster.draw(Canvas(bitmap),2000,1600,viewport,1.0,true,false,source,null,emptySet());raster.frameReady}
                finally{jobs.remember(raster)}
            }
            suspend fun ready(stage:String,raster:StreamedInkRaster,bitmap:Bitmap){
                if(diagnostics)evidence(JSONObject().put("stage",stage).put("event","start").put("deadlineMs",30_000))
                val started=System.nanoTime()
                try{
                    withTimeout(30_000){while(!draw(raster,bitmap)){
                        if(interruptFirstWait&&stage=="initial-cached")throw InterruptedPanWait()
                        events.receive();check(!failed)
                    }}
                    if(diagnostics)evidence(JSONObject().put("stage",stage).put("event","complete").put("elapsedMs",(System.nanoTime()-started)/1_000_000.0))
                }catch(error:Throwable){
                    try{
                        val details=withContext(NonCancellable+Dispatchers.Main){JSONObject().put("stage",stage).put("event","failed")
                            .put("error",error.javaClass.simpleName).put("elapsedMs",(System.nanoTime()-started)/1_000_000.0)
                            .put("pending",raster.pending).put("frameReady",raster.frameReady)
                            .put("lastCompletedBuild",raster.lastBuild?.toString()?:JSONObject.NULL).put("resources",JSONObject(RenderResources.snapshot()))}
                        evidence(details)
                    }catch(diagnostic:Throwable){error.addSuppressed(diagnostic)}
                    throw error
                }
            }
            ready("initial-cached",cached,target);viewport=viewport.copy(centerX=1256.0)
            assertFalse(draw(cached,target));assertFalse(cached.contains(values.first().id))
            ready("pan-cached",cached,target);ready("pan-cold",cold,reference);assertTrue(target.sameAs(reference))
            val pan=cached.lastBuild!!;assertTrue("Pan rebuilt ${pan.rebuilt}/${pan.tiles}",pan.rebuilt in 1 until pan.tiles)
            withContext(Dispatchers.Main){cold.clear()};viewport=viewport.copy(zoom=1.2)
            assertFalse(draw(cached,target));assertFalse(cached.frameReady)
            ready("zoom-cached",cached,target);ready("zoom-cold",cold,reference);assertTrue(target.sameAs(reference))
            assertEquals("A same-revision zoom rerasterizes retained geometry without decoding it",0,cached.lastBuild!!.decoded)
            assertTrue(cached.lastBuild!!.reusedStrokes>0)
            assertTrue(cached.lastBuild!!.bitmapBytes<=StreamedLayerLayout.PIXELS*4)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","TILE_PAN_EVIDENCE ${JSONObject().put("rebuilt",pan.rebuilt).put("tiles",pan.tiles).put("panPixelsEqual",true).put("zoomPixelsEqual",true).put("previewCaptureReady",false)}\n")})
        }catch(error:Throwable){primaryFailure=error;throw error}
        finally{
            try{jobs.clearAndJoin(listOf(cached,cold));if(diagnostics)evidence(JSONObject().put("event","workers-joined").put("resources",JSONObject(RenderResources.snapshot())))}
            catch(cleanup:Throwable){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup)else throw cleanup}
            finally{if(jobs.joined){events.close();target.recycle();reference.recycle();db.close();context.deleteDatabase(name)}}
        }
    }
    @Test fun appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="tile-append-cache-${id()}.db";val db=NoteDatabase.open(context,name)
        val target=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);val reference=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        val events=Channel<Unit>(Channel.CONFLATED);val jobs=RasterJobs();var failed=false;var primaryFailure:Throwable?=null
        fun raster()=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        val active=List(2){raster()};val full=List(2){raster()}
        fun stroke(x:Float)=InkStroke(id(),InkPen.PEN,Color.BLUE,5f,InkTool.STYLUS,
            listOf(InkSample(x,100f,0,world=true),InkSample(x+12,112f,10,world=true)),true)
        try{
            val page=WorkspaceRepository(db).create("追加几何缓存",true,PaperStyle.BLANK).id;val repo=InkRepository(db)
            val layers=List(2){UserLayer(id(),"层 $it")};val values=listOf(stroke(100f),stroke(150f))
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
            }
            repo.authoringRepository.storePage(page,page,1,PageAuthoring(UserLayers(layers,layers.first().id,
                values.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),layers[i].id)})))
            var source=InkRenderSource(repo,page,1,values.map{it.id}.toSet(),1,appendGeneration=Any())
            var viewport=CanvasViewport(256.0,256.0,1.0)
            suspend fun ready(rasters:List<StreamedInkRaster>,bitmap:Bitmap){
                withTimeout(30_000){while(true){
                    val complete=withContext(Dispatchers.Main){
                        bitmap.eraseColor(Color.WHITE);val canvas=Canvas(bitmap);var done=true
                        rasters.forEachIndexed{i,r->
                            jobs.remember(r)
                            try{r.draw(canvas,512,512,viewport,1.0,true,false,source,null,emptySet(),layers[i].id);done=done&&r.frameReady}
                            finally{jobs.remember(r)}
                        };done
                    }
                    if(complete)break
                    events.receive();check(!failed)
                }}
            }
            suspend fun compareCold(){
                withContext(Dispatchers.Main){full.forEach{it.clear()}}
                ready(full,reference);assertTrue("All pixels match the independent cold frame",target.sameAs(reference))
            }
            suspend fun append(x:Float):InkStroke{
                val added=stroke(x)
                assertTrue(repo.save(CommitInk(id(),page,source.revision,InkMutation.Add(added),LayerWriteScope(layers[0].id,1))) is InkCommitResult.Committed)
                source=source.copy(revision=source.revision+1,committedIds=source.committedIds+added.id)
                return added
            }
            ready(active,target);assertEquals(2,active.sumOf{it.lastBuild!!.decoded})
            // Legal writes stay on the current layer. Offscreen appends advance the
            // page head without dirtying either visible layer or changing its configuration.
            for(x in listOf(1000f,1200f)){
                append(x);ready(active,target)
                assertEquals("Offscreen appends do not decode visible geometry",0,active.sumOf{it.lastBuild!!.decoded})
                assertEquals("Both visible layers retain their complete tiles",0,active.sumOf{it.lastBuild!!.rebuilt})
                compareCold()
            }
            val added=append(280f);ready(active,target)
            assertEquals("The idle layer's cache may skip several page revisions",1,active.sumOf{it.lastBuild!!.decoded})
            assertEquals(1,active[0].lastBuild!!.reusedStrokes);assertEquals(2,active[0].lastBuild!!.nativeStrokeBuilds)
            assertEquals("The other layer still keeps its complete tile",0,active[1].lastBuild!!.rebuilt)
            compareCold()
            for(visible in listOf(false,true)){
                assertTrue(repo.save(CommitInk(id(),page,source.revision,InkMutation.Visibility(listOf(added.id),visible),LayerWriteScope(layers[0].id,1))) is InkCommitResult.Committed)
                source=source.copy(revision=source.revision+1,appendGeneration=Any())
                ready(active,target);assertEquals("Hide/undo starts a new append identity",0,active.sumOf{it.lastBuild!!.reusedStrokes})
                compareCold()
            }
            val cut=InkCut(id(),5f,listOf(EraserPoint(105f,105f)))
            assertTrue(repo.save(CommitInk(id(),page,source.revision,InkMutation.Cut(EraseSelection(cut,listOf(values[0].id))),LayerWriteScope(layers[0].id,1))) is InkCommitResult.Committed)
            source=source.copy(revision=source.revision+1,appendGeneration=Any())
            ready(active,target);assertEquals(0,active.sumOf{it.lastBuild!!.reusedStrokes});compareCold()
            append(320f);viewport=viewport.copy(centerX=256.25)
            ready(active,target);assertEquals("A simultaneous viewport change must not grant append reuse",0,active.sumOf{it.lastBuild!!.reusedStrokes})
            compareCold()
            assertTrue(active.all{it.lastBuild!!.geometryBytes in 1..InkRasterCache.MAX_BYTES})
            append(360f)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val blocked=async(Dispatchers.IO){db.withTransaction{entered.complete(Unit);release.await()}}
            try{
                withTimeout(5000){entered.await()}
                withContext(Dispatchers.Main){active.forEachIndexed{i,r->
                    jobs.remember(r)
                    try{r.draw(Canvas(target),512,512,viewport,1.0,true,false,source,null,emptySet(),layers[i].id);assertTrue(r.pending)}
                    finally{jobs.remember(r);r.clear()}
                    assertFalse(r.frameReady);assertNull(r.lastBuild)
                }}
            }finally{release.complete(Unit);withContext(NonCancellable){blocked.await()}}
            // The shared cache is still pinned by each cancelled worker until the outer
            // clearAndJoin finishes; its exact ownership total is checked there.
        }catch(error:Throwable){primaryFailure=error;throw error}
        finally{
            try{jobs.clearAndJoin(active+full)}
            catch(cleanup:Throwable){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup)else throw cleanup}
            finally{if(jobs.joined){events.close();target.recycle();reference.recycle();db.close();context.deleteDatabase(name)}}
        }
    }
    @Test fun oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions()=runBlocking{
        val metadataDiagnostics=InstrumentationRegistry.getArguments().getString("rasterMetadataDiagnostics")=="true"
        val context=ApplicationProvider.getApplicationContext<Context>();val name="tile-local-${id()}.db";val db=NoteDatabase.open(context,name)
        val target=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888);val reference=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888)
        val events=Channel<Unit>(Channel.CONFLATED);val active=mutableListOf<StreamedInkRaster>();val full=mutableListOf<StreamedInkRaster>()
        val jobs=RasterJobs();var failed=false
        var primaryFailure:Throwable?=null
        fun raster()=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        fun stroke(x:Float,y:Float,color:Int=Color.BLUE)=InkStroke(id(),InkPen.PEN,color,5f,InkTool.STYLUS,
            listOf(InkSample(x,y,0,world=true),InkSample(x+15,y+5,10,world=true)),true)
        try{
            for(count in listOf(1,4,8)){
                val page=WorkspaceRepository(db).create("局部重绘 $count 层",true,PaperStyle.BLANK).id
                val layers=List(count){UserLayer(id(),"层 $it",locked=count>1&&it==count-1)}
                val values=List(320){stroke(150f+(it%20)*130,150f+(it/20)*95)}
                db.withTransaction{
                    if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                    values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
                }
                val memberships=values.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),layers[i%count].id)}
                val repo=InkRepository(db);repo.authoringRepository.storePage(page,page,1,PageAuthoring(UserLayers(layers,layers.first().id,memberships)))
                val viewport=CanvasViewport(1500.0,1000.0,1.0);val epoch=Any()
                var source=InkRenderSource(repo,page,1,values.map{it.id}.toSet(),1,appendGeneration=epoch)
                val rectangles=repo.visibleLayerBounds(page,1,1,CanvasBounds(0.0,0.0,3000.0,2000.0)).mapValues{(_,b)->Rect(b.left.toInt()-2,b.top.toInt()-2,b.right.toInt()+3,b.bottom.toInt()+3)}
                val budgets=StreamedLayerLayout.budgets(rectangles)
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
                suspend fun ready(rasters:List<StreamedInkRaster>,bitmap:Bitmap):Double{
                    val start=System.nanoTime()
                    withTimeout(60_000){while(!draw(rasters,bitmap)){events.receive();check(!failed)}}
                    return (System.nanoTime()-start)/1_000_000.0
                }
                val first=ready(active,target)
                assertEquals(320,active.sumOf{it.lastBuild!!.decoded})
                val added=stroke(170f,170f,Color.RED)
                // Keep a stable layer configuration; adding membership does not change its
                // configuration revision. Use the normal scoped author commit.
                val scope=LayerWriteScope(layers.first().id,1)
                assertTrue(repo.save(CommitInk(id(),page,1,InkMutation.Add(added),scope)) is InkCommitResult.Committed)
                source=source.copy(revision=2,committedIds=source.committedIds+added.id)
                val metadataTimings=if(metadataDiagnostics)CopyOnWriteArrayList<InkRasterMetadataTiming>()else null
                if(metadataTimings!=null)repo.rasterMetadataObserver={metadataTimings.add(it);Unit}
                val local=try{ready(active,target)}finally{repo.rasterMetadataObserver=null}
                val cold=ready(full,reference)
                val metadataMs=active.sumOf{it.lastBuild!!.metadataMs};val rasterMs=active.sumOf{it.lastBuild!!.rasterMs};val publicationMs=active.sumOf{it.lastBuild!!.publicationMs}
                val localDecoded=active.sumOf{it.lastBuild!!.decoded};val localTiles=active.sumOf{it.lastBuild!!.rebuilt}
                // The same diagnostic patch runs on the baseline, where this counter is absent.
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
                withContext(Dispatchers.Main){target.eraseColor(Color.WHITE);layers.forEachIndexed{i,l->
                    jobs.remember(active[i]);try{active[i].draw(Canvas(target),3000,2000,viewport,1.0,true,false,source,null,emptySet(),l.id,rectangles.getValue(l.id),budgets.getValue(l.id))}finally{jobs.remember(active[i])}
                };assertEquals(Color.WHITE,target.getPixel(175,171))}
                ready(active,target);ready(full,reference);assertTrue(target.sameAs(reference))
                val evidence=JSONObject().put("layers",count).put("initialStrokes",320).put("finalStrokes",321)
                    .put("firstCompleteMs",first).put("appendCompleteMs",local).put("forcedFullCompleteMs",cold)
                    .put("localDecoded",localDecoded).put("fullDecoded",321).put("localRebuiltTiles",localTiles).put("fullRebuiltTiles",totalTiles)
                    .put("metadataMs",metadataMs).put("rasterMs",rasterMs).put("publicationMs",publicationMs)
                    .put("pixelsEqual",true).put("bitmapBytes",active.sumOf{it.lastBuild!!.bitmapBytes})
                nativeStrokeBuilds?.let{evidence.put("nativeStrokeBuilds",it)}
                metadataTimings?.let{timings->evidence.put("rasterMetadataDiagnostics",JSONArray().apply{timings.forEach{t->
                    put(JSONObject().put("layerIndex",layers.indexOfFirst{it.id==t.layer}).put("regions",t.regions)
                        .put("preparationBatches",t.preparationBatches).put("preparationRows",t.preparationRows)
                        .put("queryBatches",t.queryBatches).put("metadataRows",t.metadataRows)
                        .put("admissionAndPreparationNanos",t.admissionAndPreparationNanos)
                        .put("queryAndDigestNanos",t.queryAndDigestNanos).put("queryAndDigestCpuNanos",t.queryAndDigestCpuNanos?:JSONObject.NULL)
                        .put("hexAndResultNanos",t.hexAndResultNanos).put("hexAndResultCpuNanos",t.hexAndResultCpuNanos?:JSONObject.NULL)
                        .put("queryStartThreadId",t.queryStartThreadId).put("hexStartThreadId",t.hexStartThreadId).put("hexEndThreadId",t.hexEndThreadId))
                }})}
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","TILE_LOCAL_EVIDENCE $evidence\n")})
                jobs.clearAndJoin(active+full);active.clear();full.clear()
            }
        }catch(error:Throwable){primaryFailure=error;throw error}
        finally{
            try{jobs.clearAndJoin(active+full)}
            catch(cleanup:Throwable){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup)else throw cleanup}
            finally{if(jobs.joined){events.close();target.recycle();reference.recycle();db.close();context.deleteDatabase(name)}}
        }
    }
}
