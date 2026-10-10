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
        if(!x.isFinite()||!y.isFinite())return false
        if(x<bounds.left||x>bounds.right||y<bounds.top||y>bounds.bottom)return false
        if(rectangle)return true
        var inside=false;var j=points.lastIndex
        for(i in points.indices){val a=points[i];val b=points[j]
            val ax=a.x.toDouble();val ay=a.y.toDouble();val bx=b.x.toDouble();val by=b.y.toDouble()
            if(x>=min(ax,bx)&&x<=max(ax,bx)&&y>=min(ay,by)&&y<=max(ay,by)&&
                abs((bx-ax)*(y-ay)-(by-ay)*(x-ax))<=1e-7*max(1.0,hypot(bx-ax,by-ay)))return true
            if((ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax)inside=!inside
            j=i
        };return inside
    }
    /** Test every interval cut by a polygon edge. Fixed-step sampling misses narrow notches. */
    fun containsSegment(ax:Double,ay:Double,bx:Double,by:Double):Boolean {
        if(!contains(ax,ay)||!contains(bx,by))return false
        if(rectangle||ax==bx&&ay==by)return true
        val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
        var splits:DoubleArray?=null;var count=2
        fun split(t:Double){if(t>0.0&&t<1.0){
            val values=splits?:DoubleArray(points.size*2+2).also{it[1]=1.0;splits=it}
            values[count++]=t
        }}
        for(i in points.indices){
            val a=points[i];val b=points[(i+1)%points.size]
            if(max(a.x,b.x)<min(ax,bx)||min(a.x,b.x)>max(ax,bx)||max(a.y,b.y)<min(ay,by)||min(a.y,b.y)>max(ay,by))continue
            val ex=b.x.toDouble()-a.x;val ey=b.y.toDouble()-a.y;val qx=a.x.toDouble()-ax;val qy=a.y.toDouble()-ay
            val cross=dx*ey-dy*ex
            if(cross!=0.0){
                val t=(qx*ey-qy*ex)/cross;val u=(qx*dy-qy*dx)/cross
                if(u>=0.0&&u<=1.0)split(t)
            }else if(qx*dy-qy*dx==0.0){
                split((qx*dx+qy*dy)/length);split(((b.x-ax)*dx+(b.y-ay)*dy)/length)
            }
        }
        // Most author segments stay away from the contour. Allocate only at a crossing.
        val values=splits?:return true
        java.util.Arrays.sort(values,0,count)
        for(i in 1 until count){
            if(values[i]==values[i-1])continue
            val t=(values[i]+values[i-1])/2
            if(!contains(ax+dx*t,ay+dy*t))return false
        }
        return true
    }
    /** Full containment avoids silently deleting a neighbouring long stroke. */
    fun selects(box:CanvasBounds):Boolean {
        if(box.left<bounds.left||box.right>bounds.right||box.top<bounds.top||box.bottom>bounds.bottom)return false
        if(rectangle)return true
        val corners=listOf(box.left to box.top,box.right to box.top,box.right to box.bottom,box.left to box.bottom)
        if(corners.any{!contains(it.first,it.second)})return false
        // A concave notch may cross the object even when all four corners lie inside.
        if(points.any{it.x>box.left&&it.x<box.right&&it.y>box.top&&it.y<box.bottom})return false
        return corners.indices.all{i->val a=corners[i];val b=corners[(i+1)%4];containsSegment(a.first,a.second,b.first,b.second)}
    }
    fun selects(stroke:InkStroke):Boolean {
        val b=stroke.bounds()
        if(rectangle)return b.left>=bounds.left&&b.right<=bounds.right&&b.top>=bounds.top&&b.bottom<=bounds.bottom
        if(!bounds.intersects(b))return false
        return contains(stroke.samples.first().x.toDouble(),stroke.samples.first().y.toDouble())&&
            (1 until stroke.samples.size).all{i->val a=stroke.samples[i-1];val b=stroke.samples[i]
                containsSegment(a.x.toDouble(),a.y.toDouble(),b.x.toDouble(),b.y.toDouble())}
    }
    fun mask(id:String=UUID.randomUUID().toString()):InkCut = if(rectangle)InkCut(id,.01f,listOf(
        EraserPoint(bounds.left.toFloat(),bounds.top.toFloat()),EraserPoint(bounds.right.toFloat(),bounds.bottom.toFloat())),InkCutShape.RECTANGLE)
        else InkCut(id,.01f,points,InkCutShape.POLYGON)
}
object InkSelectionEdit {
    const val MAX_SELECTED=1024
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
                    val before=hypot(ux,uy);val after=hypot(vx,vy);val product=before*after
                    if(product<.001f||(ux*vx+uy*vy)/product<.75f)p else {
                        // MotionEvent sampling is not evenly spaced. A simple midpoint
                        // moves a perfectly straight stroke when the pen changes speed.
                        val t=before/(before+after)
                        val dx=(a.x+(b.x-a.x)*t-p.x)*strength*.6f;val dy=(a.y+(b.y-a.y)*t-p.y)*strength*.6f
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
        val bounds=strokes.map{it.sampleBounds()}.reduce{a,b->a.union(b)};val cx=((bounds.left+bounds.right)/2).toFloat();val cy=((bounds.top+bounds.bottom)/2).toFloat()
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
