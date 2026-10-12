// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.os.Bundle
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LayerRasterBudgetTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun oneFourAndEightLayersDecodeEachVisibleStrokeOnceWithinOnePixelBudget()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="layer-budget-${id()}.db";val db=NoteDatabase.open(context,name)
        val target=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888);val events=Channel<Unit>(Channel.CONFLATED)
        val rasters=mutableListOf<StreamedInkRaster>();var layout:StreamedLayerLayout?=null
        val baselineJobs=RenderResources.inFlightJobs.get();val baselineBytes=RenderResources.snapshot()["category.ink-frame"]?:0
        try{
            val page=WorkspaceRepository(db).create("相同总笔数多层显示",true,PaperStyle.BLANK).id
            fun stroke(x:Float,y:Float,color:Int=Color.BLUE)=InkStroke(id(),InkPen.PEN,color,3f,InkTool.STYLUS,listOf(InkSample(x,y,0,world=true),InkSample(x+20,y,20,world=true)),true)
            val ink=List(320){stroke(-900f+(it%20)*95,-400f+(it/20)*50)}
            val offscreen=stroke(10_000f,10_000f);val hidden=stroke(0f,500f,Color.RED)
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                (ink+offscreen+hidden).forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
            }
            val repo=InkRepository(db);val viewport=CanvasViewport(0.0,0.0,1.5)
            for(count in listOf(1,4,8)){
                val layers=List(count){UserLayer(id(),"可见 ${it+1}",locked=it==count-1)}
                val farLayer=UserLayer(id(),"只有页外内容");val hiddenLayer=UserLayer(id(),"隐藏答案",visible=false)
                val memberships=ink.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),layers[i%count].id)}+
                    listOf(LayerMembership(LayerContent(LayerContentKind.INK,offscreen.id),farLayer.id),LayerMembership(LayerContent(LayerContentKind.INK,hidden.id),hiddenLayer.id))
                repo.authoringRepository.storePage(page,page,count.toLong(),PageAuthoring(UserLayers(layers+farLayer+hiddenLayer,farLayer.id,memberships)))
                val source=InkRenderSource(repo,page,1,emptySet(),count.toLong())
                var failed=false
                withContext(Dispatchers.Main){layout=StreamedLayerLayout({events.trySend(Unit)},{failed=true;events.trySend(Unit)})}
                var rectangles:Map<String,Rect>?=null
                withTimeout(15_000){while(rectangles==null){
                    withContext(Dispatchers.Main){rectangles=layout!!.request(source,viewport,3000,2000,1.0)}
                    if(rectangles==null)events.receive();check(!failed)
                }}
                val extents=checkNotNull(rectangles);assertEquals(layers.map{it.id}.toSet(),extents.keys)
                val budgets=StreamedLayerLayout.budgets(extents);assertTrue(budgets.values.sum()<=StreamedLayerLayout.PIXELS)
                val nativePixels=extents.values.sumOf{it.width().toLong()*it.height()}
                if(count==8)assertTrue("fixture crosses the former hard display cutoff",nativePixels>20_000_000)
                val decoded=AtomicInteger();val reads=AtomicInteger();val points=AtomicLong();val blobs=AtomicLong()
                repo.readObserver={decoded.addAndGet(it.entries);reads.incrementAndGet();points.addAndGet(it.decodedPoints);blobs.addAndGet(it.payloadBytes)}
                withContext(Dispatchers.Main){repeat(count){rasters.add(StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)}))}}
                suspend fun draw():Boolean=withContext(Dispatchers.Main){
                    target.eraseColor(Color.WHITE);val canvas=Canvas(target);var ready=true
                    layers.forEachIndexed{i,layer->rasters[i].draw(canvas,3000,2000,viewport,1.0,true,false,source,null,emptySet(),layer.id,extents.getValue(layer.id),budgets.getValue(layer.id));ready=ready&&rasters[i].frameReady}
                    ready
                }
                val started=System.nanoTime()
                withTimeout(30_000){while(!draw()){events.receive();check(!failed)}}
                assertEquals(320,decoded.get());assertEquals(640L,points.get());assertEquals(Color.WHITE,target.getPixel(1500,1750))
                assertTrue("visible ink remains drawn",Color.red(target.getPixel(165,400))<230)
                val rasterBytes=(RenderResources.snapshot()["category.ink-frame"]?:0)-baselineBytes
                assertTrue(rasterBytes<=StreamedLayerLayout.PIXELS*4+1_048_576)
                val timings=List(30){val start=System.nanoTime();assertTrue(draw());System.nanoTime()-start}.sorted()
                val evidence=JSONObject().put("visibleLayers",count).put("visibleStrokes",320).put("decodedRows",decoded.get()).put("decodedPoints",points.get())
                    .put("payloadBytes",blobs.get()).put("geometryReads",reads.get()).put("nativeExtentPixels",nativePixels).put("inkBitmapBytes",rasterBytes)
                    .put("readyMs",(System.nanoTime()-started)/1_000_000.0).put("softwareDrawP95Ms",timings[28]/1_000_000.0)
                    .put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("processPssKiB",android.os.Debug.getPss())
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","LAYER_RASTER_EVIDENCE $evidence\n")})
                withContext(Dispatchers.Main){rasters.forEach{it.clear()};rasters.clear();layout!!.clear();layout=null}
                repo.readObserver=null
            }
        }finally{
            withContext(Dispatchers.Main){rasters.forEach{it.clear()};layout?.clear()}
            withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>baselineJobs)delay(20)}
            events.close();target.recycle();db.close();context.deleteDatabase(name)
        }
    }
}
