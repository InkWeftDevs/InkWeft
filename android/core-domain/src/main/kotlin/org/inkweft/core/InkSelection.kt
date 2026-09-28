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
    fun selects(box:CanvasBounds):Boolean {
        if(box.left<bounds.left||box.right>bounds.right||box.top<bounds.top||box.bottom>bounds.bottom)return false
        if(rectangle)return true
        val corners=listOf(box.left to box.top,box.right to box.top,box.right to box.bottom,box.left to box.bottom)
        if(corners.any{!contains(it.first,it.second)})return false
        // A concave notch may cross the object even when all four corners lie inside.
        if(points.any{it.x>box.left&&it.x<box.right&&it.y>box.top&&it.y<box.bottom})return false
        fun side(a:Pair<Double,Double>,b:Pair<Double,Double>,p:Pair<Double,Double>)=(b.first-a.first)*(p.second-a.second)-(b.second-a.second)*(p.first-a.first)
        val polygon=points.map{it.x.toDouble() to it.y.toDouble()}
        return polygon.indices.none{i->val a=polygon[i];val b=polygon[(i+1)%polygon.size];corners.indices.any{j->
            val c=corners[j];val d=corners[(j+1)%4]
            side(a,b,c)*side(a,b,d)<0&&side(c,d,a)*side(c,d,b)<0
        }}
    }
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
            s.cuts.map{c->InkCut(cuts.getOrPut(c.id){UUID.randomUUID().toString()},c.radius,c.points.map{EraserPoint(it.x+dx,it.y+dy)},c.shape)},s.appearance.translated(dx,dy))}
    }
    /** Geometric stabilisation, NOT handwriting recognition or a generated font.
     * Endpoints, timing, axes and sharp turns are retained; masked strokes are
     * not smoothed, to avoid exposing regions already erased by the user. */
    fun beautify(strokes:List<InkStroke>,strength:Float):List<InkStroke> {
        require(strokes.size in 1..MAX_SELECTED&&strength in 0f..1f)
        return strokes.map{s->
            require(s.pen!=InkPen.HIGHLIGHTER&&s.cuts.isEmpty()){"BEAUTIFY_REQUIRES_UNMASKED_PEN"}
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
            InkStroke(UUID.randomUUID().toString(),s.pen,s.color,s.width,s.tool,unique,s.world,appearance=s.appearance)
        }
    }
    /** Uniform resize/reflection keeps pressure and moves erase masks with their ink. */
    fun transform(strokes:List<InkStroke>,scale:Float=1f,flipX:Boolean=false,flipY:Boolean=false):List<InkStroke>{
        require(strokes.size in 1..MAX_SELECTED&&scale in .1f..10f)
        val points=strokes.flatMap{it.samples};val cx=(points.minOf{it.x}+points.maxOf{it.x})/2;val cy=(points.minOf{it.y}+points.maxOf{it.y})/2
        fun point(x:Float,y:Float)=EraserPoint(cx+(x-cx)*scale*(if(flipX)-1 else 1),cy+(y-cy)*scale*(if(flipY)-1 else 1))
        fun sample(p:InkSample):InkSample {val q=point(p.x,p.y);return p.copy(x=q.x,y=q.y)}
        val cuts=mutableMapOf<String,String>()
        return strokes.map{s->
            val origin=point(s.appearance.originX,s.appearance.originY)
            val masks=s.cuts.map{c->var ps=c.points.map{point(it.x,it.y)}
                if(c.shape==InkCutShape.RECTANGLE)ps=listOf(EraserPoint(ps.minOf{it.x},ps.minOf{it.y}),EraserPoint(ps.maxOf{it.x},ps.maxOf{it.y}))
                InkCut(cuts.getOrPut(c.id){UUID.randomUUID().toString()},(c.radius*scale).coerceAtLeast(.01f),ps,c.shape)}
            InkStroke(UUID.randomUUID().toString(),s.pen,s.color,s.width*scale,s.tool,s.samples.map(::sample),s.world,masks,
                s.appearance.copy(originX=origin.x,originY=origin.y,leading=s.appearance.leading?.let(::sample),trailing=s.appearance.trailing?.let(::sample)))
        }
    }
    fun recolor(strokes:List<InkStroke>,color:Int)=strokes.map{s->
        val c=if(s.pen==InkPen.HIGHLIGHTER)(color and 0xffffff) or (s.color and 0xff000000.toInt()) else color or 0xff000000.toInt()
        InkStroke(UUID.randomUUID().toString(),s.pen,c,s.width,s.tool,s.samples,s.world,s.cuts,s.appearance)
    }
}
