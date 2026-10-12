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
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamedInkRasterTest {
    private fun id()=UUID.randomUUID().toString()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync{action()}
    @Test fun clearingPendingTransformReleasesPinnedGeometryWithoutPublishing()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="raster-cancel-${id()}.db";val db=NoteDatabase.open(context,name)
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);val events=Channel<Unit>(Channel.CONFLATED)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val baseline=RenderResources.inFlightJobs.get()
        val baselineGeometry=RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L
        var raster:StreamedInkRaster?=null;var failed=false
        try{
            val page=WorkspaceRepository(db).create("取消合成平移",true,PaperStyle.BLANK).id;val repo=InkRepository(db)
            val stroke=InkStroke(id(),InkPen.PEN,Color.BLUE,3f,InkTool.STYLUS,listOf(InkSample(90f,100f,0,world=true),InkSample(110f,100f,10,world=true)),true)
            assertTrue(repo.save(CommitInk(id(),page,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val source=InkRenderSource(repo,page,1,setOf(stroke.id));var viewport=CanvasViewport(100.0,100.0,1.0)
            var ready=false
            fun draw(){bitmap.eraseColor(Color.WHITE);raster!!.draw(Canvas(bitmap),200,200,viewport,1.0,true,false,source,null,emptySet());ready=raster!!.frameReady}
            main{raster=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)});draw()}
            withTimeout(10_000){while(!ready){events.receive();main{draw()};check(!failed)}}
            val blocked=async(Dispatchers.IO){db.withTransaction{entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            main{viewport=viewport.copy(centerX=100.375);draw();assertFalse(ready);assertTrue(raster!!.pending);raster!!.clear()}
            release.countDown();blocked.await()
            withTimeout(5000){while(RenderResources.inFlightJobs.get()>baseline)delay(20)}
            main{assertFalse(raster!!.pending);assertFalse(raster!!.frameReady);assertNull(raster!!.lastBuild)}
            assertEquals(baselineGeometry,RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L)
        }finally{
            release.countDown();main{raster?.clear()};withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>baseline)delay(20)}
            events.close();bitmap.recycle();db.close();context.deleteDatabase(name)
        }
    }
    @Test fun omittedStrokeCannotReappearWhileItsReplacementFrameIsDelayed()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="stream-semantics-${id()}.db";val db=NoteDatabase.open(context,name)
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        val events=Channel<Unit>(Channel.CONFLATED);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val baseline=RenderResources.inFlightJobs.get();var raster:StreamedInkRaster?=null
        try{
            val page=WorkspaceRepository(db).create("选区旧帧隔离",true,PaperStyle.BLANK).id
            val stroke=InkStroke(id(),InkPen.PEN,Color.RED,12f,InkTool.STYLUS,listOf(InkSample(40f,100f,0,world=true),InkSample(60f,100f,10,world=true)),true)
            val repo=InkRepository(db);assertTrue(repo.save(CommitInk(id(),page,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val source=InkRenderSource(repo,page,1,setOf(stroke.id));val viewport=CanvasViewport(100.0,100.0,1.0)
            var failed=false;var ready=false
            fun draw(omitted:Set<String>){bitmap.eraseColor(Color.WHITE);raster!!.draw(Canvas(bitmap),200,200,viewport,1.0,true,false,source,null,omitted);ready=raster!!.frameReady}
            main{raster=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)});draw(emptySet())}
            withTimeout(10_000){while(!ready){events.receive();main{draw(emptySet())};check(!failed)}}
            assertNotEquals(Color.WHITE,bitmap.getPixel(50,100))
            // Omitted geometry need no longer be decoded. Hold the metadata transaction
            // instead, so the pending-frame assertion does not depend on redundant reads.
            val blocked=async(Dispatchers.IO){db.withTransaction{entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            main{draw(setOf(stroke.id))}
            // Observe the whole 500 ms pending interval, not just the completed frame.
            repeat(25){main{draw(setOf(stroke.id));assertFalse(ready);assertEquals(Color.WHITE,bitmap.getPixel(50,100))};delay(20)}
            release.countDown();blocked.await()
            withTimeout(10_000){while(!ready){events.receive();main{draw(setOf(stroke.id))};check(!failed)}}
            assertEquals(Color.WHITE,bitmap.getPixel(50,100))
            main{draw(emptySet())}
            withTimeout(10_000){while(!ready){events.receive();main{draw(emptySet())};check(!failed)}}
            assertNotEquals(Color.WHITE,bitmap.getPixel(50,100))
        }finally{release.countDown();main{raster?.clear()};withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>baseline)delay(20)};events.close();bitmap.recycle();db.close();context.deleteDatabase(name)}
    }
    @Test fun completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="stream-frame-${id()}.db";val db=NoteDatabase.open(context,name)
        var raster:StreamedInkRaster?=null;val events=Channel<Unit>(Channel.CONFLATED)
        val bitmap=Bitmap.createBitmap(400,500,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
        val baseline=RenderResources.inFlightJobs.get()
        val baselineGeometry=RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L
        try{
            val page=WorkspaceRepository(db).create("完整帧分批验证",false,PaperStyle.BLANK).id
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,2))else check(db.ink().compareAndSet(page,0,2)==1)
                repeat(1200){val s=InkStroke(id(),InkPen.PEN,Color.BLUE,3f,InkTool.STYLUS,List(64){InkSample(100f+it*.1f,100f,it.toLong())})
                    db.ink().insertStroke(InkStrokeRow(s.id,page,InkStrokeCodec.encodeStored(s),64,true,1))}
                val last=InkStroke(id(),InkPen.PEN,Color.RED,12f,InkTool.STYLUS,listOf(InkSample(790f,200f,0),InkSample(810f,200f,20)))
                db.ink().insertStroke(InkStrokeRow(last.id,page,InkStrokeCodec.encodeStored(last),2,true,2))
            }
            val repo=InkRepository(db);val window=repo.readForEditing(page,bounds=CanvasBounds(0.0,0.0,1000.0,1414.0),pointBudget=8192)
            assertFalse(window.windowComplete);assertTrue(window.strokes.none{it.stroke.color==Color.RED})
            val decoded=AtomicInteger();var failed=false
            repo.readObserver={decoded.accumulateAndGet(it.entries,::maxOf)}
            val source=InkRenderSource(repo,page,2,window.strokes.map{it.stroke.id}.toSet())
            val viewport=CanvasViewport(500.0,707.0,.25)
            var ready=false
            main{raster=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)});raster!!.draw(Canvas(bitmap),400,500,viewport,1.0,false,false,source,null,emptySet())}
            withTimeout(30_000){while(!ready){events.receive();main{raster!!.draw(Canvas(bitmap),400,500,viewport,1.0,false,false,source,null,emptySet());ready=raster!!.frameReady};check(!failed)}}
            val pixel=viewport.worldToScreen(800.0,200.0,400.0,500.0,1.0)
            val color=bitmap.getPixel(pixel.x.toInt(),pixel.y.toInt());assertTrue("Last batch must appear in the complete frame",Color.red(color)>180&&Color.blue(color)<100)
            assertTrue(decoded.get() in 1..256)
            main{raster!!.clear()};withTimeout(5000){while(RenderResources.inFlightJobs.get()>baseline)delay(20)}
            assertFalse(raster!!.pending)
            assertEquals(baselineGeometry,RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L)
        }finally{main{raster?.clear()};withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>baseline)delay(20)};events.close();bitmap.recycle();db.close();context.deleteDatabase(name)}
    }
}
