// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.inkweft.core.*
import kotlin.math.*

/** Selection-only input surface. Never creates pen samples or writes storage. */
internal class SelectionOverlayView(context:Context):View(context){
    var canvasView:InkCanvasView?=null
    var region:InkRegion?=null
    var selected:List<InkStroke> = emptyList()
    var enabledInput=true
    var freehand=false
    var onRegion:(InkRegion?)->Unit={}
    var onShift:(Float,Float)->Unit={_,_->}
    var onActive:(Boolean)->Unit={}
    private var pointer=-1
    private var start=EraserPoint(0f,0f)
    private val trail=ArrayList<EraserPoint>()
    private var moving=false
    private var dx=0f;private var dy=0f
    private var minDx=0f;private var maxDx=0f;private var minDy=0f;private var maxDy=0f
    private var activeIds=emptySet<String>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get()=resources.displayMetrics.density.toDouble()
    private fun author(x:Float,y:Float):EraserPoint{
        val p=canvasView!!.snapshotViewport().screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
        return EraserPoint(p.x.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),p.y.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD))
    }
    private fun screen(p:EraserPoint):CanvasPoint=canvasView!!.snapshotViewport().worldToScreen(p.x.toDouble(),p.y.toDouble(),width.toDouble(),height.toDouble(),density)
    private fun shape(r:InkRegion):Path=Path().apply{
        if(r.rectangle){val a=screen(EraserPoint(r.bounds.left.toFloat()+dx,r.bounds.top.toFloat()+dy));val b=screen(EraserPoint(r.bounds.right.toFloat()+dx,r.bounds.bottom.toFloat()+dy));addRect(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),Path.Direction.CW)}
        else{r.points.forEachIndexed{i,p->val s=screen(EraserPoint(p.x+dx,p.y+dy));if(i==0)moveTo(s.x.toFloat(),s.y.toFloat())else lineTo(s.x.toFloat(),s.y.toFloat())};close()}
    }
    override fun onDraw(c:Canvas){super.onDraw(c);if(canvasView==null)return
        val r=if(pointer!=-1&&!moving&&trail.size>=2)runCatching{InkRegion(if(freehand)trail.toList()else listOf(start,trail.last()),!freehand)}.getOrNull()else region
        if(r!=null){val path=shape(r);paint.style=Paint.Style.FILL;paint.color=0x10216b59;c.drawPath(path,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=(1.5*density).toFloat();paint.color=0xff216b59.toInt();paint.pathEffect=DashPathEffect(floatArrayOf((6*density).toFloat(),(4*density).toFloat()),0f);c.drawPath(path,paint);paint.pathEffect=null}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=density.toFloat();paint.color=0x77216b59
        selected.forEach{s->val b=s.bounds();val a=screen(EraserPoint(b.left.toFloat()+dx,b.top.toFloat()+dy));val z=screen(EraserPoint(b.right.toFloat()+dx,b.bottom.toFloat()+dy));c.drawRect(a.x.toFloat(),a.y.toFloat(),z.x.toFloat(),z.y.toFloat(),paint)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        val view=canvasView?:return true
        if(!enabledInput){cancel();return true}
        if(e.actionMasked==MotionEvent.ACTION_DOWN)view.onTouchEvent(e)
        if(e.pointerCount>1){cancel();view.onTouchEvent(e);return true}
        if(e.actionMasked==MotionEvent.ACTION_CANCEL){cancel();view.onTouchEvent(e);return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{pointer=e.getPointerId(0);start=author(e.x,e.y);trail.clear();trail+=start;dx=0f;dy=0f
                moving=selected.isNotEmpty()&&region?.contains(start.x.toDouble(),start.y.toDouble())==true
                if(moving){
                    // Cache translation bounds once, not a page-sized allocation at every move.
                    val samples=selected.asSequence().flatMap{it.samples.asSequence()}.toList()
                    val world=selected.first().world
                    minDx=(if(world)-BoardLimits.WORLD else 0f)-samples.minOf{it.x}
                    maxDx=(if(world)BoardLimits.WORLD else InkLimits.WIDTH)-samples.maxOf{it.x}
                    minDy=(if(world)-BoardLimits.WORLD else 0f)-samples.minOf{it.y}
                    maxDy=(if(world)BoardLimits.WORLD else InkLimits.HEIGHT)-samples.maxOf{it.y}
                    activeIds=selected.map{it.id}.toSet()
                }
                parent?.requestDisallowInterceptTouchEvent(true);onActive(true);invalidate()}
            MotionEvent.ACTION_MOVE->{if(pointer==-1)return true
                val index=e.findPointerIndex(pointer);if(index<0){cancel();return true};val p=author(e.getX(index),e.getY(index))
                if(moving){dx=p.x-start.x;dy=p.y-start.y
                    dx=dx.coerceIn(minDx,maxDx);dy=dy.coerceIn(minDy,maxDy)
                    view.selectionPreview(activeIds,dx,dy)
                }else if(freehand){if(trail.size<512&&hypot(p.x-trail.last().x,p.y-trail.last().y)>1)trail+=p}else {if(trail.size==1)trail+=p else trail[1]=p};invalidate()}
            MotionEvent.ACTION_UP->{if(pointer==-1){view.onTouchEvent(e);return true}
                val shiftX=dx;val shiftY=dy
                val next=if(moving)null else runCatching{val end=author(e.x,e.y);InkRegion(if(freehand)trail.toList()else listOf(start,end),!freehand)}.getOrNull()
                val wasMoving=moving;view.onTouchEvent(e);cancel();if(wasMoving){if(abs(shiftX)+abs(shiftY)>.01f)onShift(shiftX,shiftY)}else onRegion(next)
                performClick()}
        };return true
    }
    private fun cancel(){val active=pointer!=-1;pointer=-1;moving=false;dx=0f;dy=0f;trail.clear();canvasView?.selectionPreview(emptySet());if(active)onActive(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
