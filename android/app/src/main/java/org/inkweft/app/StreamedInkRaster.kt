// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.os.Trace
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.InkRepository
import org.inkweft.data.InkRasterCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.ceil
import kotlin.math.round

internal data class InkRenderSource(val repository:InkRepository,val page:String,val revision:Long,
    val committedIds:Set<String>,val authoringRevision:Long?=null,val layerCounts:Map<String,Long>?=null,val appendGeneration:Any?=null)

/** Immutable tiles are shared between complete frames. A partial replacement never publishes. */
internal class StreamedInkRaster(private val changed:()->Unit,private val failed:()->Unit){
    private data class Key(val width:Int,val height:Int,val viewport:CanvasViewport,val density:Double,
        val world:Boolean,val embedded:Boolean,val source:InkRenderSource,val allowed:Set<String>?,val omitted:Set<String>,val layer:String?,val pixels:Rect,val budget:Long)
    private data class Tile(val pixels:Rect,val signature:String,val bitmap:Bitmap)
    private data class Frame(val key:Key,val tiles:List<Tile>,val committed:Set<String>,val geometry:InkRasterCache)
    internal data class BuildStats(val tiles:Int,val rebuilt:Int,val decoded:Int,val bitmapBytes:Long,val metadataMs:Double=0.0,val rasterMs:Double=0.0,val publicationMs:Double=0.0,val reusedStrokes:Int=0,val geometryBytes:Long=0,val nativeStrokeBuilds:Int=0)
    internal var lastBuild:BuildStats?=null;private set
    private val owner="streamed-ink-"+java.util.UUID.randomUUID()
    private var frame:Frame?=null
    private var requested:Key?=null
    private var rejected:Key?=null
    private var job:Job?=null
    private var generation=0
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private fun scale(key:Key):Double {
        var value=2.0.pow(floor(log2(min(1.0,sqrt(key.budget.toDouble()/(key.pixels.width().toLong()*key.pixels.height()))))))
        val r=key.pixels
        while((ceil(r.right*value)-floor(r.left*value))*(ceil(r.bottom*value)-floor(r.top*value))>key.budget)value/=2
        return value
    }
    private fun projection(old:Key,next:Key):Boolean = old.source.repository===next.source.repository&&old.source.page==next.source.page&&
        old.width==next.width&&old.height==next.height&&old.viewport.zoom==next.viewport.zoom&&old.density==next.density&&
        old.world==next.world&&old.embedded==next.embedded&&old.layer==next.layer&&scale(old)==scale(next)&&
        old.source.authoringRevision==next.source.authoringRevision
    private fun compatible(old:Key,next:Key):Boolean = old==next||
        (projection(old,next)&&old.viewport==next.viewport&&next.source.appendGeneration!=null&&old.source.appendGeneration===next.source.appendGeneration&&
        old.source.revision<=next.source.revision&&old.allowed==next.allowed&&old.omitted==next.omitted)
    private fun previewCompatible(old:Key,next:Key)=old.source==next.source&&old.width==next.width&&old.height==next.height&&old.density==next.density&&
        old.world==next.world&&old.embedded==next.embedded&&old.layer==next.layer&&old.allowed==next.allowed&&old.omitted==next.omitted
    private fun notifyChanged()=InkFrameInvalidations.enqueue(changed)
    var frameReady=false;private set
    val pending get()=job!=null
    fun contains(id:String)=frame?.let{frameReady&&id in it.committed}==true
    private fun release(value:Frame){value.tiles.forEach{RenderResources.release(it.bitmap,owner)};RenderResources.release(value.geometry,owner)}
    fun clear(){
        generation++;job?.cancel();job=null;requested=null;rejected=null;frameReady=false;lastBuild=null
        frame?.let(::release);frame=null
    }
    fun draw(canvas:Canvas,width:Int,height:Int,viewport:CanvasViewport,density:Double,world:Boolean,embedded:Boolean,
        source:InkRenderSource,allowed:Set<String>?,omitted:Set<String>,layer:String?=null,pixelBounds:Rect?=null,pixelBudget:Long=StreamedLayerLayout.PIXELS){
        frameReady=false
        if(width<=0||height<=0)return
        val pixels=pixelBounds?.let(::Rect)?:Rect(0,0,width,height)
        if(pixels.isEmpty)return
        require(pixels.left>=0&&pixels.top>=0&&pixels.right<=width&&pixels.bottom<=height&&pixelBudget>0)
        val key=Key(width,height,viewport,density,world,embedded,source,allowed,omitted,layer,pixels,pixelBudget)
        frame?.takeUnless{projection(it.key,key)||previewCompatible(it.key,key)}?.let{release(it);frame=null}
        if(requested!=null&&requested!=key){generation++;job?.cancel();job=null;requested=null}
        if(frame?.key!=key&&requested==null&&rejected!=key){
            requested=key;val token=++generation
            val previous=frame
            val append=previous?.let{it.key.source.revision<source.revision&&compatible(it.key,key)}==true
            val geometry=previous?.takeIf{it.key.source.repository===source.repository&&it.key.source.page==source.page&&
                (it.key.source.revision==source.revision||append)}?.geometry?:source.repository.rasterCache()
            val workerOwner="$owner-$token"
            // Pin shared tiles before leaving Main; clear cannot unaccount worker references.
            previous?.tiles?.forEach{RenderResources.track(it.bitmap,it.bitmap.allocationByteCount.toLong(),"ink-frame",workerOwner,RenderResources.Role.IN_FLIGHT)}
            if(previous?.geometry===geometry)RenderResources.track(geometry,InkRasterCache.MAX_BYTES,"ink-geometry-reservation",workerOwner,RenderResources.Role.IN_FLIGHT)
            RenderResources.inFlightJobs.incrementAndGet()
            job=CoroutineScope(Dispatchers.Main.immediate).launch{
                val cookie=cookies.incrementAndGet();Trace.beginAsyncSection("InkWeft.ink.frame",cookie)
                val created=mutableListOf<Tile>();var published=false
                try{withContext(worker){
                    val context=currentCoroutineContext()
                    // Reserve a bounded geometry cache independently of bitmap resolution.
                    // Fractional pans and zooms still rerasterize with the exact new matrix.
                    if(previous?.geometry!==geometry)RenderResources.admit(InkRasterCache.MAX_BYTES)
                    RenderResources.track(geometry,InkRasterCache.MAX_BYTES,"ink-geometry-reservation",workerOwner,RenderResources.Role.IN_FLIGHT)
                    val previousHits=geometry.hits
                    val scale=scale(key)
                    // Stable grid in view coordinates. Quantized resolution retains tiles when
                    // an append changes only a layer's crop or slightly changes its budget.
                    // Align cropped edges to sample pixels, so every tile has the same matrix
                    // and graphite phase. Rounded allocations are included in scale's budget.
                    val grid=Rect((floor(pixels.left*scale)/scale).toInt(),(floor(pixels.top*scale)/scale).toInt(),
                        (ceil(pixels.right*scale)/scale).toInt(),(ceil(pixels.bottom*scale)/scale).toInt())
                    val size=(512/scale).toInt().coerceAtLeast(512)
                    // World-anchored sample grid: integral sample translations reuse complete
                    // interior tiles, while newly exposed edges get their own signatures.
                    val factor=(viewport.zoom*density).toFloat()
                    val originX=((floor((width/2-viewport.centerX*factor)*scale)%512)/scale).toInt()
                    val originY=((floor((height/2-viewport.centerY*factor)*scale)%512)/scale).toInt()
                    val firstX=Math.floorDiv(grid.left-originX,size);val firstY=Math.floorDiv(grid.top-originY,size)
                    val columns=Math.floorDiv(grid.right-1-originX,size)-firstX+1;val rows=Math.floorDiv(grid.bottom-1-originY,size)-firstY+1
                    require(columns.toLong()*rows<=512)
                    val rectangles=buildList{for(y in 0 until rows)for(x in 0 until columns)add(Rect(
                        maxOf(grid.left,originX+(firstX+x)*size),maxOf(grid.top,originY+(firstY+y)*size),
                        minOf(grid.right,originX+(firstX+x+1)*size),minOf(grid.bottom,originY+(firstY+y+1)*size)))}
                    fun bounds(r:Rect):CanvasBounds {
                        val a=viewport.screenToWorld(r.left.toDouble(),r.top.toDouble(),width.toDouble(),height.toDouble(),density)
                        val b=viewport.screenToWorld(r.right.toDouble(),r.bottom.toDouble(),width.toDouble(),height.toDouble(),density)
                        return CanvasBounds(a.x,a.y,b.x,b.y).padded(2/(viewport.zoom*density*scale))
                    }
                    val regions=rectangles.map(::bounds)
                    val metadataStart=System.nanoTime()
                    val states=source.repository.rasterRegions(source.page,source.revision,source.authoringRevision,layer,regions,allowed,omitted,source.committedIds)
                    val metadataMs=(System.nanoTime()-metadataStart)/1_000_000.0;val rasterStart=System.nanoTime()
                    val old=previous?.takeIf{projection(it.key,key)}?.let{f->
                        val dx=(f.key.viewport.centerX-viewport.centerX)*factor;val dy=(f.key.viewport.centerY-viewport.centerY)*factor
                        if(kotlin.math.abs(dx-round(dx))>1e-6||kotlin.math.abs(dy-round(dy))>1e-6||kotlin.math.abs(dx*scale-round(dx*scale))>1e-6||kotlin.math.abs(dy*scale-round(dy*scale))>1e-6)emptyMap()
                        else f.tiles.map{it.copy(pixels=Rect(it.pixels).apply{offset(round(dx).toInt(),round(dy).toInt())})}.associateBy{it.pixels}
                    }.orEmpty()
                    val dirty=mutableListOf<Int>()
                    val tiles=rectangles.mapIndexed{i,r->old[r]?.takeIf{it.signature==states[i].signature}?:run{
                        context.ensureActive()
                        val w=(r.width()*scale).toInt().coerceAtLeast(1);val h=(r.height()*scale).toInt().coerceAtLeast(1)
                        RenderResources.admit(w.toLong()*h*4)
                        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"ink-frame",workerOwner,RenderResources.Role.IN_FLIGHT)
                        Tile(r,states[i].signature,bitmap).also{created.add(it);dirty.add(i)}
                    }}
                    val matrix=Matrix().apply{setScale(factor,factor);postTranslate((width/2-viewport.centerX*factor).toFloat(),(height/2-viewport.centerY*factor).toFloat())}
                    val painters=dirty.associateWith{i->val t=tiles[i];Canvas(t.bitmap).apply{
                        scale(t.bitmap.width.toFloat()/t.pixels.width(),t.bitmap.height.toFloat()/t.pixels.height());translate(-t.pixels.left.toFloat(),-t.pixels.top.toFloat());concat(matrix)
                        if(!world&&!embedded)clipRect(0f,0f,1000f,1414f)
                    }}
                    val renderer=InkBrushes.renderer()
                    val pencil=PencilTileRenderer(min(.5f,(1/(factor*scale)).toFloat()),false){context.ensureActive()}
                    var decoded=0;var nativeStrokeBuilds=0
                    try{
                        if(dirty.isNotEmpty()){
                            val region=dirty.map{regions[it]}.reduce{a,b->a.union(b)}
                            source.repository.visitVisible(source.page,source.revision,region,source.authoringRevision,layer,
                                regions=dirty.map{regions[it]},allowed=allowed,omitted=omitted,cache=geometry,allowRevisionAdvance=append){batch->
                                for(stroke in batch){
                                    context.ensureActive();decoded++
                                    if(stroke.id in omitted||allowed!=null&&stroke.id !in allowed)continue
                                    val inkBounds=stroke.bounds()
                                    // Stroke and its mesh are immutable. Retain only this
                                    // stroke's native shape until all its dirty tiles finish.
                                    var nativeStroke:Stroke?=null
                                    for(i in dirty){if(!inkBounds.intersects(regions[i]))continue
                                        val painter=painters.getValue(i);val saved=painter.save()
                                        try{stroke.cuts.forEach{painter.clipOutPath(VisibleInkGeometry.cutPath(it))}
                                            if(stroke.pen==InkPen.PENCIL)pencil.draw(painter,stroke)else{
                                                val ink=nativeStroke?:InkBrushes.stroke(stroke).also{nativeStroke=it;nativeStrokeBuilds++}
                                                renderer.draw(painter,ink,matrix)
                                            }
                                        }finally{painter.restoreToCount(saved)}
                                    }
                                }
                                pencil.clear()
                            }
                        }
                        source.repository.validateRenderRevision(source.page,source.revision,source.authoringRevision)
                        val rasterMs=(System.nanoTime()-rasterStart)/1_000_000.0;val publicationStart=System.nanoTime()
                        context.ensureActive();created.forEach{it.bitmap.prepareToDraw()}
                        withContext(Dispatchers.Main.immediate){if(generation==token){
                            tiles.forEach{RenderResources.track(it.bitmap,it.bitmap.allocationByteCount.toLong(),"ink-frame",owner,RenderResources.Role.ACTIVE)}
                            RenderResources.track(geometry,InkRasterCache.MAX_BYTES,"ink-geometry-reservation",owner,RenderResources.Role.CACHE)
                            frame?.tiles?.filter{oldTile->tiles.none{it.bitmap===oldTile.bitmap}}?.forEach{RenderResources.release(it.bitmap,owner)}
                            frame?.takeIf{it.geometry!==geometry}?.let{RenderResources.release(it.geometry,owner)}
                            frame=Frame(key,tiles,states.flatMap{it.committed}.toSet(),geometry)
                            val reused=geometry.hits-previousHits
                            lastBuild=BuildStats(tiles.size,dirty.size,decoded-reused,tiles.sumOf{it.bitmap.allocationByteCount.toLong()},metadataMs,rasterMs,(System.nanoTime()-publicationStart)/1_000_000.0,reused,geometry.retainedBytes,nativeStrokeBuilds)
                            published=true;notifyChanged()
                        }}
                    }finally{pencil.clear()}
                }}catch(_:CancellationException){RenderResources.cancelledJobs.incrementAndGet()}
                catch(_:RenderBudgetBusy){if(generation==token){frame?.let(::release);frame=null;delay(500);notifyChanged()}}
                catch(_:Exception){if(generation==token){rejected=key;failed()}}
                finally{
                    RenderResources.releaseOwner(workerOwner)
                    if(!published)created.forEach{it.bitmap.recycle()}
                    Trace.endAsyncSection("InkWeft.ink.frame",cookie);RenderResources.inFlightJobs.decrementAndGet()
                    if(generation==token){job=null;requested=null;notifyChanged()}
                }
            }
        }
        frame?.takeIf{compatible(it.key,key)}?.let{f->
            f.tiles.forEach{canvas.drawBitmap(it.bitmap,null,it.pixels,paint)};frameReady=f.key==key
        }
        if(!frameReady)frame?.takeIf{it.key!=key&&previewCompatible(it.key,key)}?.let{f->
            val ratio=(viewport.zoom/f.key.viewport.zoom).toFloat();val dx=((f.key.viewport.centerX-viewport.centerX)*viewport.zoom*density).toFloat();val dy=((f.key.viewport.centerY-viewport.centerY)*viewport.zoom*density).toFloat()
            f.tiles.forEach{t->canvas.drawBitmap(t.bitmap,null,RectF((t.pixels.left-width/2)*ratio+width/2+dx,(t.pixels.top-height/2)*ratio+height/2+dy,
                (t.pixels.right-width/2)*ratio+width/2+dx,(t.pixels.bottom-height/2)*ratio+height/2+dy),paint)}
            // This same-source preview is never a completed frame or a capture acknowledgement.
        }
    }
    private companion object{
        val cookies=AtomicInteger()
        val worker=Executors.newSingleThreadExecutor{r->Thread(r,"InkWeft-streamed-raster").apply{isDaemon=true}}.asCoroutineDispatcher()
    }
}

/** Layer completions share the platform's next frame, avoiding a redraw per layer callback. */
private object InkFrameInvalidations {
    private val callbacks=LinkedHashSet<()->Unit>()
    fun enqueue(callback:()->Unit){
        val schedule=callbacks.isEmpty();callbacks.add(callback)
        if(schedule)android.view.Choreographer.getInstance().postFrameCallback{
            val pending=callbacks.toList();callbacks.clear();pending.forEach{it()}
        }
    }
}
