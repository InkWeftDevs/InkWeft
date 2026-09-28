package org.inkweft.app

import android.graphics.*
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.util.concurrent.Executors
import kotlin.math.*

/** Publish complete coverage at each resolution. Partial graphite tiles never reach the UI. */
internal class AsyncInkRaster(private val changed:()->Unit,private val failed:()->Unit={}) {
    private data class Key(val width:Int,val height:Int,val viewport:CanvasViewport,val density:Double,val world:Boolean,val embedded:Boolean)
    private data class Frame(val key:Key,val strokes:List<InkStroke>,val bitmap:Bitmap,val complete:Boolean=true)
    private var frame:Frame?=null
    private var fallback:Frame?=null
    private var request:Pair<Key,List<InkStroke>>?=null
    private var job:Job?=null
    private var rejected:Pair<Key,List<InkStroke>>?=null
    private var generation=0L
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    internal val pending get()=job!=null
    fun contains(id:String)=frame?.strokes?.any{it.id==id}==true
    private fun same(a:InkStroke,b:InkStroke)=a===b||(a.id==b.id&&a.pen==b.pen&&a.width==b.width&&a.color==b.color&&a.world==b.world&&a.appearance==b.appearance&&a.samples==b.samples&&a.cuts.size==b.cuts.size&&a.cuts.indices.all{val x=a.cuts[it];val y=b.cuts[it];x===y||(x.id==y.id&&x.radius==y.radius&&x.shape==y.shape&&x.points==y.points)})
    private fun prefix(a:List<InkStroke>,b:List<InkStroke>)=a.size<=b.size&&a.indices.all{same(a[it],b[it])}
    fun clear(){generation++;job?.cancel();job=null;request=null;frame=null;fallback=null;rejected=null}
    fun draw(c:Canvas,width:Int,height:Int,viewport:CanvasViewport,density:Double,world:Boolean,embedded:Boolean,strokes:List<InkStroke>){
        if(width<=0||height<=0)return
        val key=Key(width,height,viewport,density,world,embedded)
        val old=frame
        if(old!=null&&(old.key!=key||!prefix(old.strokes,strokes))){
            fallback=old.takeIf{it.strokes.size==strokes.size&&prefix(it.strokes,strokes)&&it.key.world==world&&it.key.embedded==embedded};frame=null
        }
        fallback?.let{if(it.strokes.size!=strokes.size||!prefix(it.strokes,strokes))fallback=null}
        if(frame==null){
            val index=cache.indexOfLast{it.key==key&&it.strokes.size==strokes.size&&prefix(it.strokes,strokes)}
            if(index>=0){frame=cache.removeAt(index).also{cache.add(it)};fallback=null}
        }
        val complete=frame?.let{it.complete&&it.key==key&&it.strokes.size==strokes.size}==true
        val active=request
        if(active!=null&&(active.first!=key||!prefix(active.second,strokes))){generation++;job?.cancel();job=null;request=null}
        val rejectedNow=rejected?.let{it.first==key&&it.second.size==strokes.size&&prefix(it.second,strokes)}==true
        if(!complete&&!rejectedNow&&request==null){
            generation++;job?.cancel();val token=generation;val source=strokes.toList();val base=frame?.takeIf{it.complete}
            request=key to source
            job=CoroutineScope(Dispatchers.Main.immediate).launch {
                try {withContext(worker){
                    val context=currentCoroutineContext()
                    suspend fun render(budget:Double,detailed:Boolean){
                        val scale=min(1.0,sqrt(budget/(width.toDouble()*height))).toFloat()
                        val w=max(1,(width*scale).roundToInt());val h=max(1,(height*scale).roundToInt())
                        val start=base?.takeIf{detailed}
                        val bitmap=start?.bitmap?.copy(Bitmap.Config.ARGB_8888,true)?:Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        var published=false
                        try {
                            val canvas=Canvas(bitmap);canvas.scale(w.toFloat()/width,h.toFloat()/height)
                            val factor=(viewport.zoom*density).toFloat()
                            val matrix=Matrix().apply{setScale(factor,factor);postTranslate((width/2-viewport.centerX*factor).toFloat(),(height/2-viewport.centerY*factor).toFloat())}
                            canvas.concat(matrix);if(!world&&!embedded)canvas.clipRect(0f,0f,1000f,1414f)
                            val pencil=PencilTileRenderer(if(detailed)max(.5f,.5f/(factor*scale)) else max(.5f,1f/(factor*scale))){context.ensureActive()}
                            val renderer=InkBrushes.renderer()
                            for(i in (start?.strokes?.size?:0) until source.size){
                                context.ensureActive();val stroke=source[i];val n=canvas.save()
                                stroke.cuts.forEach{canvas.clipOutPath(VisibleInkGeometry.cutPath(it))}
                                if(stroke.pen==InkPen.PENCIL)pencil.draw(canvas,stroke)else renderer.draw(canvas,InkBrushes.stroke(stroke),matrix)
                                canvas.restoreToCount(n)
                            }
                            context.ensureActive();bitmap.prepareToDraw()
                            withContext(Dispatchers.Main.immediate){if(token==generation){
                                val result=Frame(key,source,bitmap,detailed);frame=result;fallback=null;published=true
                                if(detailed){cache.add(result);var bytes=cache.sumOf{it.bitmap.allocationByteCount.toLong()+it.strokes.sumOf{s->s.samples.size.toLong()*48+s.cuts.sumOf{cut->cut.points.size.toLong()*16}}};while(bytes>48L*1024*1024&&cache.isNotEmpty()){val removed=cache.removeAt(0);bytes-=removed.bitmap.allocationByteCount.toLong()+removed.strokes.sumOf{s->s.samples.size.toLong()*48+s.cuts.sumOf{cut->cut.points.size.toLong()*16}}}}
                                changed()
                            }}
                        }finally{if(!published)bitmap.recycle()}
                    }
                    if(base==null&&width.toLong()*height>500_000&&source.any{it.pen==InkPen.PENCIL})render(220_000.0,false)
                    render(4_000_000.0,true)
                }}catch(_:CancellationException){}catch(_:Exception){if(token==generation){rejected=key to source;failed()}}finally{if(token==generation){job=null;request=null;changed()}}
            }
        }
        val current=frame?.takeIf{it.key==key}
        if(current!=null)c.drawBitmap(current.bitmap,null,Rect(0,0,width,height),paint)
        else fallback?.let{prior->
            val oldFactor=prior.key.viewport.zoom*prior.key.density;val factor=viewport.zoom*density;val ratio=factor/oldFactor
            val m=Matrix().apply{setScale((prior.key.width.toDouble()/prior.bitmap.width*ratio).toFloat(),(prior.key.height.toDouble()/prior.bitmap.height*ratio).toFloat());postTranslate((width/2.0-prior.key.width/2.0*ratio+(prior.key.viewport.centerX-viewport.centerX)*factor).toFloat(),(height/2.0-prior.key.height/2.0*ratio+(prior.key.viewport.centerY-viewport.centerY)*factor).toFloat())}
            c.drawBitmap(prior.bitmap,m,paint)
        }
    }
    companion object {
        // UI-thread LRU: immutable buffers remain valid while a view still references them.
        private val cache=mutableListOf<Frame>()
        internal fun clearMemoryCache(){cache.clear()}
        private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"InkWeft-page-raster").apply{isDaemon=true}}.asCoroutineDispatcher()
    }
}
