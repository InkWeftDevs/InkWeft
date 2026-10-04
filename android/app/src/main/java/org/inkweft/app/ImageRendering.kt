// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import java.io.ByteArrayInputStream
import kotlin.math.*

/** A region in oriented image fractions, independent of the object's author-space placement. */
internal data class ImageFrame(val source:String,val bitmap:Bitmap,val region:Rect,val rawWidth:Int,val rawHeight:Int,val orientation:Int) {
    private val corners=floatArrayOf(region.left.toFloat()/rawWidth,region.top.toFloat()/rawHeight,
        region.right.toFloat()/rawWidth,region.top.toFloat()/rawHeight,region.left.toFloat()/rawWidth,region.bottom.toFloat()/rawHeight)
        .also{imageOrientation(orientation).mapPoints(it)}
    fun bounds(o:PageObject):RectF=RectF(corners.filterIndexed{i,_->i%2==0}.min(),corners.filterIndexed{i,_->i%2==1}.min(),
        corners.filterIndexed{i,_->i%2==0}.max(),corners.filterIndexed{i,_->i%2==1}.max()).apply{
        set(o.x+left*o.width,o.y+top*o.height,o.x+right*o.width,o.y+bottom*o.height)
    }
    fun draw(canvas:Canvas,o:PageObject,paint:Paint){
        val destination=corners.copyOf();for(i in 0..2){destination[i*2]=o.x+destination[i*2]*o.width;destination[i*2+1]=o.y+destination[i*2+1]*o.height}
        val transform=Matrix().apply{setPolyToPoly(floatArrayOf(0f,0f,bitmap.width.toFloat(),0f,0f,bitmap.height.toFloat()),0,destination,0,3)}
        canvas.drawBitmap(bitmap,transform,paint)
    }
}

/** EXIF's eight orthogonal transforms, in normalized coordinates (raw -> display). */
internal fun imageOrientation(orientation:Int)=Matrix().apply{setValues(when(orientation){
    2->floatArrayOf(-1f,0f,1f,0f,1f,0f,0f,0f,1f)
    3->floatArrayOf(-1f,0f,1f,0f,-1f,1f,0f,0f,1f)
    4->floatArrayOf(1f,0f,0f,0f,-1f,1f,0f,0f,1f)
    5->floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
    6->floatArrayOf(0f,-1f,1f,1f,0f,0f,0f,0f,1f)
    7->floatArrayOf(0f,-1f,1f,-1f,0f,1f,0f,0f,1f)
    8->floatArrayOf(0f,1f,0f,-1f,0f,1f,0f,0f,1f)
    else->floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
})}

/** Only the current visible regions are retained. No original is decoded on the drawing thread. */
internal class ImageRendering(private val context:Context,private val changed:()->Unit,private val failed:()->Unit={}) {
    private data class Key(val id:String,val source:String,val crop:CanvasBounds,val width:Int,val height:Int)
    private data class Request(val key:Key,val objectValue:PageObject)
    private data class Saved(val key:Key,val frame:ImageFrame)
    private val owner="original-images-"+java.util.UUID.randomUUID()
    private var generation=0L
    private var wanted=emptyList<Request>()
    private val frames=linkedMapOf<String,Saved>()
    private var job:Job?=null
    internal val pending get()=job!=null
    internal var decodeCount=0
        private set
    fun frame(o:PageObject):ImageFrame?=frames[o.id]?.frame?.takeIf{it.source==o.imageSource}
    fun clear(){generation++;job?.cancel();job=null;wanted=emptyList();frames.values.forEach{RenderResources.release(it.frame.bitmap,owner)};frames.clear()}
    fun retain(objects:List<PageObject>){
        val images=objects.filter{!it.hidden&&it.kind==PageObjectKind.IMAGE}.associateBy{it.id}
        frames.keys.filter{frames[it]?.frame?.source!=images[it]?.imageSource}.forEach{frames.remove(it)?.let{s->RenderResources.release(s.frame.bitmap,owner)}}
        // Cancel even before the next draw, so a deleted object cannot be republished.
        if(wanted.any{images[it.key.id]?.imageSource!=it.key.source}){generation++;job?.cancel();job=null;wanted=emptyList()}
    }
    fun request(objects:List<PageObject>,visible:CanvasBounds,pixelsPerWorld:Double,read:suspend (PageObject)->ImageSource?){
        val next=objects.filter{!it.hidden&&it.kind==PageObjectKind.IMAGE&&it.imageSource!=null&&it.bounds().intersects(visible)}.mapNotNull{o->
            val l=max(o.x.toDouble(),visible.left);val t=max(o.y.toDouble(),visible.top)
            val r=min((o.x+o.width).toDouble(),visible.right);val b=min((o.y+o.height).toDouble(),visible.bottom)
            if(r<=l||b<=t)null else Request(Key(o.id,checkNotNull(o.imageSource),CanvasBounds((l-o.x)/o.width,(t-o.y)/o.height,(r-o.x)/o.width,(b-o.y)/o.height),
                ceil(o.width*pixelsPerWorld).toInt().coerceAtLeast(1),ceil(o.height*pixelsPerWorld).toInt().coerceAtLeast(1)),o)
        }
        retain(objects)
        val keys=next.map{it.key}
        frames.keys.filter{id->next.none{it.key.id==id}}.forEach{frames.remove(it)?.let{s->RenderResources.release(s.frame.bitmap,owner)}}
        if(wanted.map{it.key}==keys)return
        wanted=next;val token=++generation;job?.cancel();job=null
        if(next.isEmpty())return
        job=CoroutineScope(Dispatchers.Main.immediate).launch{
            delay(80)
            RenderResources.inFlightJobs.incrementAndGet()
            try{
                for(request in next){
                    if(frames[request.key.id]?.key==request.key)continue
                    var retry=true
                    while(retry){
                        retry=false
                        try{
                            withContext(Dispatchers.IO){decodeLock.withLock{
                                BackgroundBudget.await(context);ensureActive()
                                val source=read(request.objectValue)?:error("IMAGE_ORIGINAL_MISSING")
                                require(source.sha256==request.key.source)
                                RenderResources.track(source,source.size.toLong(),"image-original-bytes",owner,RenderResources.Role.IN_FLIGHT)
                                try{BackgroundBudget.memory(source.size.toLong()*2){
                                    val result=decode(source,request.key)
                                    var published=false
                                    try{
                                        ensureActive()
                                        withContext(Dispatchers.Main.immediate){
                                            if(token==generation){
                                                frames.remove(request.key.id)?.let{RenderResources.release(it.frame.bitmap,owner)}
                                                // ponytail: keep 32 MiB per view; lower layers retain their small previews under extreme overlap.
                                                while(frames.isNotEmpty()&&frames.values.sumOf{it.frame.bitmap.allocationByteCount.toLong()}+result.bitmap.allocationByteCount>FRAME_BYTES){
                                                    val first=frames.keys.first();RenderResources.release(frames.remove(first)!!.frame.bitmap,owner)
                                                }
                                                frames[request.key.id]=Saved(request.key,result)
                                                RenderResources.track(result.bitmap,result.bitmap.allocationByteCount.toLong(),"image-region",owner,RenderResources.Role.ACTIVE)
                                                decodeCount++;published=true;changed()
                                            }
                                        }
                                    }finally{if(!published){RenderResources.release(result.bitmap,owner);result.bitmap.recycle()}}
                                }}finally{RenderResources.release(source,owner)}
                            }}
                        }catch(_:RenderBudgetBusy){delay(500);retry=true}
                    }
                }
            }catch(cancel:CancellationException){RenderResources.cancelledJobs.incrementAndGet();throw cancel}
            catch(_:Exception){if(token==generation)failed()}
            finally{RenderResources.inFlightJobs.decrementAndGet();if(token==generation){job=null;changed()}}
        }
    }
    private suspend fun decode(source:ImageSource,key:Key):ImageFrame {
        currentCoroutineContext().ensureActive()
        val bytes=source.bytes()
        val orientation=ByteArrayInputStream(bytes).use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
        val decoder=BitmapRegionDecoder.newInstance(bytes,0,bytes.size)
        try{
            val w=decoder.width;val h=decoder.height
            require(w in 1..20000&&h in 1..20000&&w.toLong()*h<=100_000_000)
            val crop=RectF(key.crop.left.toFloat(),key.crop.top.toFloat(),key.crop.right.toFloat(),key.crop.bottom.toFloat())
            val inverse=Matrix();check(imageOrientation(orientation).invert(inverse));inverse.mapRect(crop)
            // One source-pixel overlap protects filtering at the clipped viewport edge.
            val region=Rect((floor(crop.left*w).toInt()-1).coerceIn(0,w-1),(floor(crop.top*h).toInt()-1).coerceIn(0,h-1),
                (ceil(crop.right*w).toInt()+1).coerceIn(1,w),(ceil(crop.bottom*h).toInt()+1).coerceIn(1,h))
            val rotated=orientation in 5..8
            val scale=max(key.width.toDouble()/(if(rotated)h else w),key.height.toDouble()/(if(rotated)w else h))
            var sample=1;while(sample<16384&&sample*2*scale<=1)sample*=2
            val pixels=((region.width()+sample-1)/sample).toLong()*((region.height()+sample-1)/sample)
            RenderResources.admit(pixels*4)
            val bitmap=checkNotNull(decoder.decodeRegion(region,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}))
            RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"image-region",owner,RenderResources.Role.IN_FLIGHT)
            return ImageFrame(source.sha256,bitmap,region,w,h,orientation)
        }finally{decoder.recycle()}
    }
    companion object {
        private const val FRAME_BYTES=32L*1024*1024
        // Serialize original-byte reconstruction and decoder scratch space across continuous pages.
        private val decodeLock=Mutex()
    }
}
