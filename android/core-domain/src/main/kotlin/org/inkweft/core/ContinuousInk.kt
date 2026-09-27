// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID
import kotlin.math.floor

/** Split a captured book-space gesture at exact paper edges, including upward/re-entry strokes. */
object ContinuousInk {
    fun split(stroke:InkStroke,origin:Int,pageCount:Int):Map<Int,List<InkStroke>> {
        require(origin in 0 until pageCount && stroke.cuts.isEmpty())
        val result=linkedMapOf<Int,MutableList<InkStroke>>()
        var current=-1;var points=mutableListOf<InkSample>()
        fun flush(){if(points.isNotEmpty()){
            result.getOrPut(current){mutableListOf()}.add(InkStroke(UUID.randomUUID().toString(),stroke.pen,stroke.color,stroke.width,stroke.tool,points.toList()))
            points=mutableListOf()
        }}
        fun emit(page:Int,p:InkSample){
            if(page!=current){flush();current=page}
            val local=p.copy(x=p.x.coerceIn(0f,1000f),y=(p.y-page*1414f).coerceIn(0f,1414f),world=false)
            if(points.lastOrNull()!=local)points.add(local)
        }
        val source=stroke.samples.map{it.copy(y=(it.y+origin*1414f).coerceIn(0f,pageCount*1414f),world=true)}
        fun page(y:Float)=floor(y/1414f).toInt().coerceIn(0,pageCount-1)
        if(source.size==1)emit(page(source[0].y),source[0])
        source.zipWithNext().forEach{(a,b)->
            val ts=mutableListOf(0f,1f)
            if(a.y!=b.y){
                val first=floor(minOf(a.y,b.y)/1414f).toInt()+1
                val last=floor(maxOf(a.y,b.y)/1414f).toInt()
                for(edge in first..last){val t=(edge*1414f-a.y)/(b.y-a.y);if(t>0f&&t<1f)ts.add(t)}
            }
            fun lerp(t:Float):InkSample {
                if(t==0f)return a
                if(t==1f)return b
                fun f(x:Float,y:Float)=if(x<0f)-1f else x+(y-x)*t
                return InkSample(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.elapsedMs+((b.elapsedMs-a.elapsedMs)*t).toLong(),f(a.pressure,b.pressure),f(a.tilt,b.tilt),f(a.orientation,b.orientation),true)
            }
            ts.sorted().zipWithNext().forEach{(s,e)->val p=page(a.y+(b.y-a.y)*(s+e)/2);emit(p,lerp(s));emit(p,lerp(e))}
        }
        flush();require(result.values.sumOf{it.size}<=256)
        return result
    }
}
