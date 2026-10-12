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
    private var job:Job?=null
    val pending get()=job!=null||key!=null&&rectangles==null
    fun clear(){job?.cancel();job=null;key=null;rectangles=null}
    fun request(source:InkRenderSource,viewport:CanvasViewport,width:Int,height:Int,density:Double):Map<String,Rect>? {
        val next=Key(source,viewport,width,height,density)
        if(key!=next){
            val old=key
            val reusable=rectangles.takeIf{old!=null&&source.appendGeneration!=null&&source.appendGeneration===old.source.appendGeneration&&
                source.repository===old.source.repository&&source.page==old.source.page&&source.authoringRevision==old.source.authoringRevision&&
                old.viewport==viewport&&old.width==width&&old.height==height&&old.density==density}
            clear();key=next
            // Until new extents arrive, a full viewport cannot clip a just-appended stroke.
            rectangles=reusable?.mapValues{Rect(0,0,width,height)}
            RenderResources.inFlightJobs.incrementAndGet()
            job=CoroutineScope(Dispatchers.Main.immediate).launch{
                try{
                    val bounds=withContext(Dispatchers.IO){source.repository.visibleLayerBounds(source.page,source.revision,checkNotNull(source.authoringRevision),viewport.visible(width.toDouble(),height.toDouble(),density))}
                    if(key==next){
                        rectangles=buildMap{bounds.forEach{(layer,b)->
                            val a=viewport.worldToScreen(b.left,b.top,width.toDouble(),height.toDouble(),density)
                            val z=viewport.worldToScreen(b.right,b.bottom,width.toDouble(),height.toDouble(),density)
                            val r=Rect((floor(a.x)-2).toInt().coerceIn(0,width),(floor(a.y)-2).toInt().coerceIn(0,height),
                                (ceil(z.x)+2).toInt().coerceIn(0,width),(ceil(z.y)+2).toInt().coerceIn(0,height))
                            if(!r.isEmpty)put(layer,r)
                        }}
                        changed()
                    }
                }catch(c:CancellationException){throw c}catch(_:Exception){if(key==next)failed()}
                finally{RenderResources.inFlightJobs.decrementAndGet();if(key==next)job=null}
            }
        }
        return rectangles
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
