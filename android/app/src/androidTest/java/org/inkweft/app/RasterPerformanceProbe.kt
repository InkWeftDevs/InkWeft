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
import java.util.concurrent.atomic.AtomicInteger

/** Copy this unchanged to the fixed baseline for same-device paired evidence. Optional new
 * counters use reflection; timing, actual decode callbacks and pixel equality work on both. */
class RasterPerformanceProbe {
    private fun id(index:Int)=UUID.nameUUIDFromBytes("raster-probe-20261012-$index".toByteArray()).toString()
    @Test fun continuousTransformsAndSeparatedDirtyTiles()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="raster-probe-${UUID.randomUUID()}.db"
        val db=NoteDatabase.open(context,name);val repo=InkRepository(db)
        val target=Bitmap.createBitmap(2400,1800,Bitmap.Config.ARGB_8888);val reference=Bitmap.createBitmap(2400,1800,Bitmap.Config.ARGB_8888)
        val events=Channel<Unit>(Channel.CONFLATED);var failed=false;val initialJobs=RenderResources.inFlightJobs.get()
        val active=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        val cold=StreamedInkRaster({events.trySend(Unit)},{failed=true;events.trySend(Unit)})
        try{
            val page=WorkspaceRepository(db).create("合成绘制对照",true,PaperStyle.BLANK).id
            fun stroke(index:Int,x:Float,y:Float)=InkStroke(id(index),if(index%16==0)InkPen.PENCIL else InkPen.PEN,Color.BLUE,5f,InkTool.STYLUS,
                listOf(InkSample(x,y,0,world=true),InkSample(x+24,y+16,10,world=true)),true)
            val values=List(320){stroke(it,100f+(it%20)*110,100f+(it/20)*100)}
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
            }
            var source=InkRenderSource(repo,page,1,values.map{it.id}.toSet(),appendGeneration=Any())
            var viewport=CanvasViewport(1200.0,900.0,1.0)
            // Compare raster work with the same ready spatial index. First-index backfill
            // is a separate cost and is not included in the snapshot decode counters.
            repo.visibleBounds(page,1)
            val payloadRows=AtomicInteger()
            repo.readObserver={payloadRows.addAndGet(it.entries)}
            suspend fun draw(raster:StreamedInkRaster,bitmap:Bitmap)=withContext(Dispatchers.Main){
                bitmap.eraseColor(Color.WHITE);raster.draw(Canvas(bitmap),2400,1800,viewport,1.0,true,false,source,null,emptySet());raster.frameReady
            }
            suspend fun ready(raster:StreamedInkRaster,bitmap:Bitmap,expectPending:Boolean=false):Double {
                val start=System.nanoTime()
                if(expectPending){assertFalse(draw(raster,bitmap));assertFalse(raster.frameReady)}
                withTimeout(90_000){while(!draw(raster,bitmap)){events.receive();check(!failed)}}
                return (System.nanoTime()-start)/1_000_000.0
            }
            suspend fun compare(label:String){
                payloadRows.set(0);val elapsed=ready(active,target,label!="initial");val rows=payloadRows.get();val stats=active.lastBuild!!
                withContext(Dispatchers.Main){cold.clear()}
                payloadRows.set(0);val fullMs=ready(cold,reference);val fullRows=payloadRows.get()
                assertTrue("$label pixels differ from a complete cold rebuild",target.sameAs(reference))
                assertTrue(stats.bitmapBytes<=StreamedLayerLayout.PIXELS*4)
                val result=JSONObject().put("case",label).put("completeMs",elapsed).put("decoded",stats.decoded)
                    .put("snapshotReadRows",rows).put("rebuilt",stats.rebuilt).put("tiles",stats.tiles)
                    .put("metadataMs",stats.metadataMs).put("rasterMs",stats.rasterMs).put("publicationMs",stats.publicationMs)
                    .put("coldMs",fullMs).put("coldDecoded",cold.lastBuild!!.decoded).put("coldSnapshotReadRows",fullRows)
                    .put("coldRebuilt",cold.lastBuild!!.rebuilt).put("pixelsEqual",true)
                for(field in listOf("reusedStrokes","geometryBytes","nativeStrokeBuilds")){
                    val getter="get"+field.replaceFirstChar{it.uppercase()}
                    result.put(field,stats.javaClass.methods.firstOrNull{it.name==getter}?.invoke(stats)?:JSONObject.NULL)
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","RASTER_PAIRED_EVIDENCE $result\n")})
            }
            compare("initial")
            repeat(3){cycle->
                for((index,next) in listOf(
                    CanvasViewport(1200.0+64*(cycle+1),900.0,1.0),
                    CanvasViewport(1200.375+64*(cycle+1),900.625,1.0),
                    CanvasViewport(1200.375,900.625,1.125),
                    CanvasViewport(1200.0,900.0,1.0)
                ).withIndex()){
                    viewport=next
                    compare("transform-$cycle-$index")
                }
            }
            // The two dirty tiles cover opposite corners; their union includes almost all
            // clean geometry. Keep stable source/fixture ordering on both implementations.
            for(index in 320..321){
                val s=if(index==320)stroke(index,130f,130f)else stroke(index,2220f,1640f)
                assertTrue(repo.save(CommitInk(id(index+1000),page,source.revision,InkMutation.Add(s))) is InkCommitResult.Committed)
                source=source.copy(revision=source.revision+1,committedIds=source.committedIds+s.id)
            }
            compare("separated-append")
            // After every timed frame, pin connection-local PRAGMAs to the primary writer.
            val writerSqlite=db.withTransaction{
                val settings=JSONObject();val sql=db.openHelper.writableDatabase
                for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
                    sql.query("PRAGMA $setting").use{it.moveToFirst();settings.put(setting,if(setting=="journal_mode")it.getString(0) else it.getLong(0))}
                }
                settings
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","RASTER_SQLITE_EVIDENCE ${JSONObject().put("writerSqlite",writerSqlite)}\n")})
        }finally{
            withContext(Dispatchers.Main){active.clear();cold.clear()}
            withTimeoutOrNull(5000){while(RenderResources.inFlightJobs.get()>initialJobs)delay(20)}
            events.close();target.recycle();reference.recycle();db.close();context.deleteDatabase(name)
        }
    }
}
