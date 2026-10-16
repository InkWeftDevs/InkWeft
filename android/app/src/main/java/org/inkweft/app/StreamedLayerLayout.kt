// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Rect
import kotlinx.coroutines.*
import org.inkweft.core.CanvasViewport
import kotlin.math.ceil
import kotlin.math.floor

/** At most 32 rectangles, resolved without decoding stroke geometry. */
internal class StreamedLayerLayout(private val changed:()->Unit,private val failed:()->Unit) {
    private data class Key(val source:InkRenderSource,val viewport:CanvasViewport,val width:Int,val height:Int,val density:Double)
    private var key:Key?=null
    private var rectangles:Map<String,Rect>?=null
    private var completedKey:Key?=null
    private var generation=0
    private var job:Job?=null
    val pending get()=job!=null||key!=completedKey
    fun clear(){generation++;job?.cancel();job=null;key=null;rectangles=null;completedKey=null}
    fun request(source:InkRenderSource,viewport:CanvasViewport,width:Int,height:Int,density:Double):Map<String,Rect>? {
        val next=Key(source,viewport,width,height,density)
        if(key!=next){
            val old=completedKey
            val reusable=old!=null&&old.density==density&&(old.source.sameContent(source)||
                source.appendGeneration!=null&&source.appendGeneration===old.source.appendGeneration&&
                source.repository===old.source.repository&&source.page==old.source.page&&source.authoringRevision==old.source.authoringRevision&&
                old.source.revision<=source.revision&&old.viewport==viewport&&old.width==width&&old.height==height)
            generation++;job?.cancel();job=null;key=next
            if(!reusable){rectangles=null;completedKey=null}
            val token=generation
            // Keep the last completed layout anchored to its own viewport. A pan/zoom
            // preview must not erase the layer rasters while fresh bounds are pending.
            RenderResources.inFlightJobs.incrementAndGet()
            job=CoroutineScope(Dispatchers.Main.immediate).launch{
                try{
                    val bounds=withContext(Dispatchers.IO){source.repository.visibleLayerBounds(source.page,source.revision,checkNotNull(source.authoringRevision),viewport.visible(width.toDouble(),height.toDouble(),density))}
                    if(generation==token){
                        rectangles=buildMap{bounds.forEach{(layer,b)->
                            val a=viewport.worldToScreen(b.left,b.top,width.toDouble(),height.toDouble(),density)
                            val z=viewport.worldToScreen(b.right,b.bottom,width.toDouble(),height.toDouble(),density)
                            val r=Rect((floor(a.x)-2).toInt().coerceIn(0,width),(floor(a.y)-2).toInt().coerceIn(0,height),
                                (ceil(z.x)+2).toInt().coerceIn(0,width),(ceil(z.y)+2).toInt().coerceIn(0,height))
                            if(!r.isEmpty)put(layer,r)
                        }}
                        completedKey=next
                        changed()
                    }
                }catch(c:CancellationException){throw c}catch(_:Exception){if(generation==token)failed()}
                finally{RenderResources.inFlightJobs.decrementAndGet();if(generation==token)job=null}
            }
        }
        val old=completedKey?:return null
        val completed=rectangles?:return null
        if(old.viewport==viewport&&old.width==width&&old.height==height)return completed
        val ratio=viewport.zoom/old.viewport.zoom
        val dx=(old.viewport.centerX-viewport.centerX)*viewport.zoom*density
        val dy=(old.viewport.centerY-viewport.centerY)*viewport.zoom*density
        return buildMap{completed.forEach{(layer,r)->
            val transformed=Rect(
                floor((r.left-old.width/2.0)*ratio+width/2.0+dx).toInt().coerceIn(0,width),
                floor((r.top-old.height/2.0)*ratio+height/2.0+dy).toInt().coerceIn(0,height),
                ceil((r.right-old.width/2.0)*ratio+width/2.0+dx).toInt().coerceIn(0,width),
                ceil((r.bottom-old.height/2.0)*ratio+height/2.0+dy).toInt().coerceIn(0,height))
            if(!transformed.isEmpty)put(layer,transformed)
        }}
    }
    companion object {
        // 32 MiB for a view's saved ink leaves room for the replacement, another pane and PDF.
        const val PIXELS=8_000_000L
        fun budgets(rectangles:Map<String,Rect>):Map<String,Long> {
            val total=rectangles.values.sumOf{it.width().toLong()*it.height()}
            return rectangles.mapValues{(_,r)->(r.width().toLong()*r.height()*minOf(1.0,PIXELS.toDouble()/maxOf(1,total))).toLong().coerceAtLeast(1)}
        }
    }
}
