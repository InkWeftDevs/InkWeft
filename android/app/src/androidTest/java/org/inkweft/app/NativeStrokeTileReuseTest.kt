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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.floor

class NativeStrokeTileReuseTest {
    @Test fun oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="native-tile-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        val target=Bitmap.createBitmap(1600,1800,Bitmap.Config.ARGB_8888);val reference=Bitmap.createBitmap(1600,1800,Bitmap.Config.ARGB_8888)
        val crop=Rect(100,90,1500,1710);val events=Channel<Unit>(Channel.CONFLATED);var failed=false
        val raster=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)});val baselineJobs=RenderResources.inFlightJobs.get()
        try{
            val page=WorkspaceRepository(db).create("跨瓦片不可变笔迹",false,PaperStyle.BLANK).id;val repo=InkRepository(db)
            val cut=InkCut(UUID(0,100).toString(),1f,listOf(EraserPoint(450f,640f),EraserPoint(550f,774f)),InkCutShape.RECTANGLE)
            val values=listOf(
                InkStroke(UUID(0,1).toString(),InkPen.PEN,Color.BLUE,18f,InkTool.STYLUS,listOf(InkSample(0f,0f,0),InkSample(1000f,1414f,100)),cuts=listOf(cut)),
                InkStroke(UUID(0,2).toString(),InkPen.HIGHLIGHTER,0x997700cc.toInt(),24f,InkTool.STYLUS,listOf(InkSample(0f,1414f,0),InkSample(1000f,0f,100)),cuts=listOf(cut)),
                InkStroke(UUID(0,3).toString(),InkPen.PENCIL,Color.DKGRAY,5f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(130f,120f,10)))
            )
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),it.samples.size,true,1))}
            }
            val source=InkRenderSource(repo,page,1,values.map{it.id}.toSet())
            var viewport=CanvasViewport(500.0,707.0,1.0);var omitted=emptySet<String>();var allowed:Set<String>?=null
            suspend fun draw()=withContext(Dispatchers.Main){
                target.eraseColor(Color.WHITE);raster.draw(Canvas(target),1600,1800,viewport,1.0,false,false,source,allowed,omitted,pixelBounds=crop);raster.frameReady
            }
            // Differential oracle: the previous scale-1 raster path constructed native
            // Stroke separately inside each tile. Keep that behavior only in this test.
            fun perTileReference():Int {
                val factor=viewport.zoom.toFloat();val originX=(floor(800-viewport.centerX*factor)%512).toInt();val originY=(floor(900-viewport.centerY*factor)%512).toInt()
                val rectangles=buildList{for(y in Math.floorDiv(crop.top-originY,512)..Math.floorDiv(crop.bottom-1-originY,512))
                    for(x in Math.floorDiv(crop.left-originX,512)..Math.floorDiv(crop.right-1-originX,512))add(Rect(
                        maxOf(crop.left,originX+x*512),maxOf(crop.top,originY+y*512),minOf(crop.right,originX+(x+1)*512),minOf(crop.bottom,originY+(y+1)*512)))}
                val regions=rectangles.map{r->val a=viewport.screenToWorld(r.left.toDouble(),r.top.toDouble(),1600.0,1800.0,1.0)
                    val b=viewport.screenToWorld(r.right.toDouble(),r.bottom.toDouble(),1600.0,1800.0,1.0);CanvasBounds(a.x,a.y,b.x,b.y).padded(2/viewport.zoom)}
                val matrix=Matrix().apply{setScale(factor,factor);postTranslate((800-viewport.centerX*factor).toFloat(),(900-viewport.centerY*factor).toFloat())}
                val tiles=rectangles.map{Bitmap.createBitmap(it.width(),it.height(),Bitmap.Config.ARGB_8888)}
                val painters=tiles.mapIndexed{i,b->Canvas(b).apply{translate(-rectangles[i].left.toFloat(),-rectangles[i].top.toFloat());concat(matrix);clipRect(0f,0f,1000f,1414f)}}
                val renderer=InkBrushes.renderer();val pencil=PencilTileRenderer(.5f,false);var builds=0
                try{
                    for(stroke in values){
                        if(stroke.id in omitted||allowed?.let{stroke.id !in it}==true)continue
                        for(i in rectangles.indices){if(!stroke.bounds().intersects(regions[i]))continue
                            val painter=painters[i];val saved=painter.save()
                            try{stroke.cuts.forEach{painter.clipOutPath(VisibleInkGeometry.cutPath(it))}
                                if(stroke.pen==InkPen.PENCIL)pencil.draw(painter,stroke)else{builds++;renderer.draw(painter,InkBrushes.stroke(stroke),matrix)}
                            }finally{painter.restoreToCount(saved)}
                        }
                    }
                    reference.eraseColor(Color.WHITE);val canvas=Canvas(reference);val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                    tiles.forEachIndexed{i,b->canvas.drawBitmap(b,null,rectangles[i],paint)}
                    return builds
                }finally{pencil.clear();tiles.forEach{it.recycle()}}
            }
            for(phase in 0..3){
                if(phase==1)viewport=CanvasViewport(500.375,707.625,1.2)
                if(phase==2)omitted=setOf(values[0].id)
                if(phase==3)allowed=setOf(values[2].id)
                withTimeout(30_000){while(!draw()){events.receive();check(!failed)}}
                val legacyBuilds=withContext(Dispatchers.Default){perTileReference()};val stats=raster.lastBuild!!
                val expected=when(phase){0,1->2;2->1;else->0}
                assertEquals("Only drawn non-pencil strokes construct native shapes",expected,stats.nativeStrokeBuilds)
                if(expected>0)assertTrue("Fixture must exercise repeated per-tile construction",legacyBuilds>stats.nativeStrokeBuilds)
                assertTrue("phase $phase differs from the old per-tile construction",target.sameAs(reference))
                assertTrue(stats.tiles>1);assertTrue(stats.bitmapBytes<=StreamedLayerLayout.PIXELS*4)
                val gap=viewport.worldToScreen(500.0,707.0,1600.0,1800.0,1.0)
                assertEquals("Erase masks remain clipped in author space",Color.WHITE,target.getPixel(gap.x.toInt(),gap.y.toInt()))
                assertEquals("Crop exterior remains untouched",Color.WHITE,target.getPixel(crop.left-1,900))
                if(phase==0)assertEquals("Finite paper edge remains clipped",Color.WHITE,target.getPixel(299,192))
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","NATIVE_STROKE_TILE_EVIDENCE ${JSONObject()
                    .put("phase",phase).put("nativeStrokeBuilds",stats.nativeStrokeBuilds).put("perTileReferenceBuilds",legacyBuilds)
                    .put("tiles",stats.tiles).put("rebuilt",stats.rebuilt).put("pixelsEqual",true)}\n")})
            }
        }finally{
            withContext(Dispatchers.Main){raster.clear()};withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>baselineJobs)delay(20)}
            events.close();target.recycle();reference.recycle();db.close();context.deleteDatabase(name)
        }
    }
}
