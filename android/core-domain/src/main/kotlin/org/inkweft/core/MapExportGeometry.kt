// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.ceil
import kotlin.math.min

data class MapExportGeometry(val width:Int,val height:Int,val scale:Double,val translateX:Double,val translateY:Double)

/** Fit a complete measured scene with a margin; never allocate world-sized bitmaps. */
object MapExportSizing {
    const val MAX_EDGE=4096
    const val MAX_PIXELS=8_388_608L
    fun fit(bounds:CanvasBounds,maxEdge:Int=MAX_EDGE,maxPixels:Long=MAX_PIXELS,padding:Int=32):MapExportGeometry{
        require(maxEdge in 128..MAX_EDGE&&maxPixels in 16_384..MAX_PIXELS&&padding in 0..64)
        val w=bounds.right-bounds.left;val h=bounds.bottom-bounds.top
        require(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all{it.isFinite()}&&w>0&&h>0)
        val available=maxEdge-2*padding
        require(available>0)
        var scale=min(1.0,min(available/w,available/h))
        fun dimensions(s:Double)=ceil(w*s).toInt()+2*padding to ceil(h*s).toInt()+2*padding
        if(dimensions(scale).let{(x,y)->x.toLong()*y}>maxPixels){
            var low=0.0;var high=scale
            repeat(60){val mid=(low+high)/2;val (x,y)=dimensions(mid);if(x.toLong()*y<=maxPixels)low=mid else high=mid}
            scale=low
        }
        val (width,height)=dimensions(scale)
        require(scale>0&&width in 1..maxEdge&&height in 1..maxEdge&&width.toLong()*height<=maxPixels){"MAP_EXPORT_BOUNDS"}
        return MapExportGeometry(width,height,scale,padding-bounds.left*scale,padding-bounds.top*scale)
    }
}
