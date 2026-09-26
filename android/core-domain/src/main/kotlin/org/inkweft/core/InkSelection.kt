// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID
import kotlin.math.*

/** Explicit selection geometry. Selecting does not mutate author input. */
class InkRegion(points:List<EraserPoint>,val rectangle:Boolean=true) {
    val points:List<EraserPoint> = java.util.Collections.unmodifiableList(ArrayList(points))
    val bounds:CanvasBounds
    init{
        require(points.size in (if(rectangle)2 else 3)..512)
        if(rectangle)require(points.size==2)
        bounds=CanvasBounds(points.minOf{it.x}.toDouble(),points.minOf{it.y}.toDouble(),points.maxOf{it.x}.toDouble(),points.maxOf{it.y}.toDouble())
        require(bounds.right-bounds.left>=.01&&bounds.bottom-bounds.top>=.01)
    }
    fun contains(x:Double,y:Double):Boolean {
        if(x<bounds.left||x>bounds.right||y<bounds.top||y>bounds.bottom)return false
        if(rectangle)return true
        var inside=false;var j=points.lastIndex
        for(i in points.indices){val a=points[i];val b=points[j]
            if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside
            j=i
        };return inside
    }
    /** Full containment avoids silently deleting a neighbouring long stroke. */
    fun selects(stroke:InkStroke):Boolean {
        val b=stroke.bounds()
        if(rectangle)return b.left>=bounds.left&&b.right<=bounds.right&&b.top>=bounds.top&&b.bottom<=bounds.bottom
        if(!bounds.intersects(b))return false
        return stroke.samples.all{contains(it.x.toDouble(),it.y.toDouble())}&&stroke.samples.zipWithNext().all{(a,b)->
            val steps=ceil(hypot(b.x-a.x,b.y-a.y)/4).toInt().coerceIn(1,512)
            (1 until steps).all{n->contains((a.x+(b.x-a.x)*n/steps).toDouble(),(a.y+(b.y-a.y)*n/steps).toDouble())}
        }
    }
    fun mask(id:String=UUID.randomUUID().toString()):InkCut = if(rectangle)InkCut(id,.01f,listOf(
        EraserPoint(bounds.left.toFloat(),bounds.top.toFloat()),EraserPoint(bounds.right.toFloat(),bounds.bottom.toFloat())),InkCutShape.RECTANGLE)
        else InkCut(id,.01f,points,InkCutShape.POLYGON)
}
object InkSelectionEdit {
    const val MAX_SELECTED=256
    fun copy(strokes:List<InkStroke>,dx:Float,dy:Float):List<InkStroke> {
        require(strokes.size in 1..MAX_SELECTED&&dx.isFinite()&&dy.isFinite())
        val cuts=mutableMapOf<String,String>()
        return strokes.map{s->InkStroke(UUID.randomUUID().toString(),s.pen,s.color,s.width,s.tool,
            s.samples.map{it.copy(x=it.x+dx,y=it.y+dy)},s.world,
            s.cuts.map{c->InkCut(cuts.getOrPut(c.id){UUID.randomUUID().toString()},c.radius,c.points.map{EraserPoint(it.x+dx,it.y+dy)},c.shape)})}
    }
    /** Geometric stabilisation, NOT handwriting recognition or a generated font.
     * Endpoints, timing, axes and sharp turns are retained; masked strokes are
     * not smoothed, to avoid exposing regions already erased by the user. */
    fun beautify(strokes:List<InkStroke>,strength:Float):List<InkStroke> {
        require(strokes.size in 1..MAX_SELECTED&&strength in 0f..1f)
        return strokes.map{s->
            require(s.pen==InkPen.PEN&&s.cuts.isEmpty()){"BEAUTIFY_REQUIRES_UNMASKED_PEN"}
            val samples=s.samples.mapIndexed{i,p->
                if(i==0||i==s.samples.lastIndex||strength==0f)p else {
                    val a=s.samples[i-1];val b=s.samples[i+1]
                    val ux=p.x-a.x;val uy=p.y-a.y;val vx=b.x-p.x;val vy=b.y-p.y
                    val product=hypot(ux,uy)*hypot(vx,vy)
                    if(product<.001f||(ux*vx+uy*vy)/product<.75f)p else {
                        val dx=((a.x+b.x)/2-p.x)*strength*.6f;val dy=((a.y+b.y)/2-p.y)*strength*.6f
                        val limit=minOf(1.5f,s.width*.4f);val factor=minOf(1f,limit/maxOf(.0001f,hypot(dx,dy)))
                        p.copy(x=p.x+dx*factor,y=p.y+dy*factor)
                    }
                }
            }
            // De-duplicate exact consecutive transformed samples, not time stamps.
            val unique=ArrayList<InkSample>();samples.forEach{if(unique.lastOrNull()!=it)unique.add(it)}
            InkStroke(UUID.randomUUID().toString(),s.pen,s.color,s.width,s.tool,unique,s.world)
        }
    }
    fun recolor(strokes:List<InkStroke>,color:Int)=strokes.map{s->
        val c=if(s.pen==InkPen.HIGHLIGHTER)(color and 0xffffff) or (s.color and 0xff000000.toInt()) else color or 0xff000000.toInt()
        InkStroke(UUID.randomUUID().toString(),s.pen,c,s.width,s.tool,s.samples,s.world,s.cuts)
    }
}
