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
    var geometry=VisibleInkGeometry()
    var region:InkRegion?=null
    var selected:List<InkStroke> = emptyList()
    var selectedObjects:List<CanvasBounds> = emptyList()
    var objectIds:Set<String> = emptySet()
    var worldSelection=false
    var enabledInput=true
    var freehand=false
    var marker=false
    var onCaptureDrag:(()->CaptureTransfer?)?=null
    private var captureStart=false
    var onRegion:(InkRegion?)->Unit={}
    var onShift:(Float,Float)->Unit={_,_->}
    var onTap:(Float,Float)->Unit={_,_->}
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
        if(marker&&pointer!=-1&&trail.size>=2){
            val path=Path();trail.forEachIndexed{i,p->val point=screen(p);if(i==0)path.moveTo(point.x.toFloat(),point.y.toFloat())else path.lineTo(point.x.toFloat(),point.y.toFloat())}
            paint.style=Paint.Style.STROKE;paint.color=0x553b82f6;paint.strokeWidth=(24*canvasView!!.snapshotViewport().zoom*density).toFloat();paint.strokeCap=Paint.Cap.ROUND;c.drawPath(path,paint);return
        }
        val r=if(pointer!=-1&&!moving&&trail.size>=2)runCatching{InkRegion(if(freehand)trail.toList()else listOf(start,trail.last()),!freehand)}.getOrNull()else region
        if(r!=null&&(selected.isEmpty()&&selectedObjects.isEmpty()||pointer!=-1&&!moving)){val path=shape(r);paint.style=Paint.Style.FILL;paint.color=0x10216b59;c.drawPath(path,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=(1.5*density).toFloat();paint.color=0xff216b59.toInt();paint.pathEffect=DashPathEffect(floatArrayOf((6*density).toFloat(),(4*density).toFloat()),0f);c.drawPath(path,paint);paint.pathEffect=null}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=density.toFloat();paint.color=0x77216b59
        selected.forEach{s->val vp=canvasView!!.snapshotViewport();val f=(vp.zoom*density).toFloat()
            val outline=Path(geometry.path(s));outline.transform(Matrix().apply{setScale(f,f);postTranslate((width/2-vp.centerX*f+dx*f).toFloat(),(height/2-vp.centerY*f+dy*f).toFloat())});c.drawPath(outline,paint)}
        selectedObjects.forEach{b->val a=screen(EraserPoint(b.left.toFloat()+dx,b.top.toFloat()+dy));val z=screen(EraserPoint(b.right.toFloat()+dx,b.bottom.toFloat()+dy));c.drawRect(a.x.toFloat(),a.y.toFloat(),z.x.toFloat(),z.y.toFloat(),paint)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        val view=canvasView?:return true
        if(!enabledInput){cancel();return true}
        if(e.actionMasked==MotionEvent.ACTION_DOWN)view.onTouchEvent(e)
        if(e.pointerCount>1){cancel();view.onTouchEvent(e);return true}
        if(e.actionMasked==MotionEvent.ACTION_CANCEL){cancel();view.onTouchEvent(e);return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{pointer=e.getPointerId(0);start=author(e.x,e.y);trail.clear();trail+=start;dx=0f;dy=0f
                captureStart=onCaptureDrag!=null&&region?.bounds?.let{start.x>=it.left&&start.x<=it.right&&start.y>=it.top&&start.y<=it.bottom}==true
                moving=(selected.mapNotNull{geometry.bounds(it)}+selectedObjects).reduceOrNull{a,b->a.union(b)}?.padded(8.0)?.let{start.x>=it.left&&start.x<=it.right&&start.y>=it.top&&start.y<=it.bottom}==true
                if(moving){
                    // Cache translation bounds once, not a page-sized allocation at every move.
                    val samples=selected.asSequence().flatMap{it.samples.asSequence()}.toList()
                    val world=worldSelection
                    val xs=samples.map{it.x}+selectedObjects.flatMap{listOf(it.left.toFloat(),it.right.toFloat())}
                    val ys=samples.map{it.y}+selectedObjects.flatMap{listOf(it.top.toFloat(),it.bottom.toFloat())}
                    minDx=(if(world)-BoardLimits.WORLD else 0f)-xs.min()
                    maxDx=(if(world)BoardLimits.WORLD else InkLimits.WIDTH)-xs.max()
                    minDy=(if(world)-BoardLimits.WORLD else 0f)-ys.min()
                    maxDy=(if(world)BoardLimits.WORLD else InkLimits.HEIGHT)-ys.max()
                    if(world)selectedObjects.forEach{b->
                        minDx=maxOf(minDx,(-BoardLimits.WORLD+(b.right-b.left)-b.left).toFloat())
                        minDy=maxOf(minDy,(-BoardLimits.WORLD+(b.bottom-b.top)-b.top).toFloat())
                    }
                    activeIds=selected.map{it.id}.toSet()
                }
                parent?.requestDisallowInterceptTouchEvent(true);onActive(true);invalidate()}
            MotionEvent.ACTION_MOVE->{if(pointer==-1)return true
                val index=e.findPointerIndex(pointer);if(index<0){cancel();return true};val p=author(e.getX(index),e.getY(index))
                if(captureStart&&kotlin.math.hypot(p.x-start.x,p.y-start.y)>8){
                    val transfer=onCaptureDrag?.invoke()
                    if(transfer!=null){cancel();startDragAndDrop(android.content.ClipData.newPlainText("摘录",""),CaptureShadow(this),transfer,0);return true}
                }
                if(moving){dx=p.x-start.x;dy=p.y-start.y
                    dx=dx.coerceIn(minDx,maxDx);dy=dy.coerceIn(minDy,maxDy)
                    view.selectionPreview(activeIds,dx,dy);view.previewSelectionObjects(objectIds,dx,dy)
                }else if(freehand){
                    for(h in 0 until e.historySize){val q=author(e.getHistoricalX(index,h),e.getHistoricalY(index,h));appendTrail(q)}
                    appendTrail(p)
                }else {if(trail.size==1)trail+=p else trail[1]=p};invalidate()}
            MotionEvent.ACTION_UP->{if(pointer==-1){view.onTouchEvent(e);return true}
                val release=author(e.x,e.y);if(freehand&&!moving)appendTrail(release)
                val shiftX=if(moving)(release.x-start.x).coerceIn(minDx,maxDx)else dx
                val shiftY=if(moving)(release.y-start.y).coerceIn(minDy,maxDy)else dy
                val next=if(moving)null else runCatching{val end=author(e.x,e.y);if(marker){val xs=trail.map{it.x}+end.x;val ys=trail.map{it.y}+end.y;InkRegion(listOf(EraserPoint((xs.min()-12).coerceAtLeast(-BoardLimits.WORLD),(ys.min()-12).coerceAtLeast(-BoardLimits.WORLD)),EraserPoint((xs.max()+12).coerceAtMost(BoardLimits.WORLD),(ys.max()+12).coerceAtMost(BoardLimits.WORLD))))}else InkRegion(if(freehand)trail.toList()else listOf(start,end),!freehand)}.getOrNull()
                val end=author(e.x,e.y)
                val tapRadius=(12/(view.snapshotViewport().zoom*density))
                val tapped=!moving&&hypot(end.x-start.x,end.y-start.y)<tapRadius&&trail.all{hypot(it.x-start.x,it.y-start.y)<tapRadius}
                val wasMoving=moving;view.onTouchEvent(e);cancel();if(wasMoving){if(abs(shiftX)+abs(shiftY)>.01f)onShift(shiftX,shiftY)}else if(tapped)onTap(end.x,end.y)else onRegion(next)
                performClick()}
        };return true
    }
    private fun appendTrail(p:EraserPoint){if(hypot(p.x-trail.last().x,p.y-trail.last().y)<.5f)return;if(trail.size>=510){val reduced=trail.filterIndexed{i,_->i%2==0};trail.clear();trail.addAll(reduced)};trail+=p}
    private fun cancel(){val active=pointer!=-1;pointer=-1;moving=false;dx=0f;dy=0f;trail.clear();canvasView?.selectionPreview(emptySet());canvasView?.previewSelectionObjects(emptySet());if(active)onActive(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
