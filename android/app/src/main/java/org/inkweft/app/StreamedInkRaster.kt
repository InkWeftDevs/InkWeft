// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.os.Trace
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.InkRepository
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Saved geometry is consumed a batch at a time; live input remains the canvas overlay. */
internal data class InkRenderSource(val repository:InkRepository,val page:String,val revision:Long,
    val committedIds:Set<String>)

/** A frame is published only after every spatial candidate at the requested revision is drawn. */
internal class StreamedInkRaster(private val changed:()->Unit,private val failed:()->Unit){
    private data class Key(val width:Int,val height:Int,val viewport:CanvasViewport,val density:Double,
        val world:Boolean,val embedded:Boolean,val source:InkRenderSource,val allowed:Set<String>?,val omitted:Set<String>)
    private data class Frame(val key:Key,val bitmap:Bitmap)
    private val owner="streamed-ink-"+java.util.UUID.randomUUID()
    private var frame:Frame?=null
    private var requested:Key?=null
    private var rejected:Key?=null
    private var job:Job?=null
    private var generation=0
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    var frameReady=false;private set
    val pending get()=job!=null
    fun contains(id:String)=frame?.let{f->frameReady&&id in f.key.source.committedIds&&id !in f.key.omitted&&(f.key.allowed==null||id in f.key.allowed)}==true
    fun clear(){
        generation++;job?.cancel();job=null;requested=null;rejected=null;frameReady=false
        frame?.let{RenderResources.release(it.bitmap,owner)};frame=null
    }
    fun draw(canvas:Canvas,width:Int,height:Int,viewport:CanvasViewport,density:Double,world:Boolean,embedded:Boolean,
        source:InkRenderSource,allowed:Set<String>?,omitted:Set<String>){
        frameReady=false
        if(width<=0||height<=0)return
        val key=Key(width,height,viewport,density,world,embedded,source,allowed,omitted)
        if(requested!=null&&requested!=key){generation++;job?.cancel();job=null;requested=null}
        if(frame?.key!=key&&requested==null&&rejected!=key){
            requested=key;val token=++generation
            RenderResources.inFlightJobs.incrementAndGet()
            job=CoroutineScope(Dispatchers.Main.immediate).launch{
                val cookie=cookies.incrementAndGet();Trace.beginAsyncSection("InkWeft.ink.frame",cookie)
                try{withContext(worker){
                    val context=currentCoroutineContext()
                    RenderResources.admit(width.toLong()*height*4)
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    val workerOwner="$owner-$token"
                    RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"ink-frame",workerOwner,RenderResources.Role.IN_FLIGHT)
                    val factor=(viewport.zoom*density).toFloat()
                    val matrix=Matrix().apply{setScale(factor,factor);postTranslate((width/2-viewport.centerX*factor).toFloat(),(height/2-viewport.centerY*factor).toFloat())}
                    val painter=Canvas(bitmap).apply{concat(matrix);if(!world&&!embedded)clipRect(0f,0f,1000f,1414f)}
                    val renderer=InkBrushes.renderer()
                    val pencil=PencilTileRenderer(min(.5f,1f/factor),false){context.ensureActive()}
                    val visible=viewport.visible(width.toDouble(),height.toDouble(),density)
                    var published=false
                    fun draw(stroke:InkStroke){
                        context.ensureActive()
                        if(stroke.id in omitted||(allowed!=null&&stroke.id !in allowed)||!stroke.bounds().intersects(visible))return
                        val saved=painter.save()
                        stroke.cuts.forEach{painter.clipOutPath(VisibleInkGeometry.cutPath(it))}
                        if(stroke.pen==InkPen.PENCIL)pencil.draw(painter,stroke)else renderer.draw(painter,InkBrushes.stroke(stroke),matrix)
                        painter.restoreToCount(saved)
                    }
                    try{
                        source.repository.visitVisible(source.page,source.revision,visible){batch->
                            batch.forEach(::draw)
                            // Graphite tiles can retain source samples; release them at the same batch seam.
                            pencil.clear()
                        }
                        context.ensureActive();bitmap.prepareToDraw()
                        withContext(Dispatchers.Main.immediate){if(generation==token){
                            frame?.let{RenderResources.release(it.bitmap,owner)}
                            frame=Frame(key,bitmap);RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"ink-frame",owner,RenderResources.Role.ACTIVE)
                            published=true;changed()
                        }}
                    }finally{pencil.clear();RenderResources.release(bitmap,workerOwner);if(!published)bitmap.recycle()}
                }}catch(_:CancellationException){RenderResources.cancelledJobs.incrementAndGet()}
                catch(_:RenderBudgetBusy){if(generation==token){delay(500);changed()}}
                catch(_:Exception){if(generation==token){rejected=key;failed()}}
                finally{Trace.endAsyncSection("InkWeft.ink.frame",cookie);RenderResources.inFlightJobs.decrementAndGet();if(generation==token){job=null;requested=null;changed()}}
            }
        }
        frame?.takeIf{it.key.width==width&&it.key.height==height&&it.key.viewport==viewport&&it.key.world==world&&it.key.embedded==embedded}?.let{
            canvas.drawBitmap(it.bitmap,0f,0f,paint);frameReady=it.key==key
        }
    }
    private companion object{
        val cookies=AtomicInteger()
        val worker=Executors.newSingleThreadExecutor{r->Thread(r,"InkWeft-streamed-raster").apply{isDaemon=true}}.asCoroutineDispatcher()
    }
}
