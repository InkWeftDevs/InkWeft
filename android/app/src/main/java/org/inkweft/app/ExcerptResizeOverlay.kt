// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.inkweft.core.*
import kotlin.math.hypot

internal class ExcerptResizeOverlay(context:Context):View(context) {
    var canvasView:InkCanvasView?=null
    var bounds=CanvasBounds(0.0,0.0,1.0,1.0)
    var world=false
    var editing=false
    var enabledInput=true
    var onChange:(CanvasBounds)->Unit={}
    var onActive:(Boolean)->Unit={}
    var onDismiss:()->Unit={}
    private var start:CanvasPoint?=null
    private var original:CanvasBounds?=null
    private var handle:ExcerptHandle?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get()=resources.displayMetrics.density.toDouble()
    private fun screen(x:Double,y:Double)=canvasView!!.snapshotViewport().worldToScreen(x,y,width.toDouble(),height.toDouble(),density)
    private fun author(x:Float,y:Float)=canvasView!!.snapshotViewport().screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
    private fun point(h:ExcerptHandle,b:CanvasBounds)=screen(when(h.x){-1->b.left;1->b.right;else->(b.left+b.right)/2},when(h.y){-1->b.top;1->b.bottom;else->(b.top+b.bottom)/2})
    override fun onDraw(c:Canvas){
        super.onDraw(c);if(canvasView==null)return
        c.clipRect(0,0,width,height)
        val a=screen(bounds.left,bounds.top);val b=screen(bounds.right,bounds.bottom)
        paint.color=0xffd15ba8.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=(2*density).toFloat()
        c.drawRect(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint)
        if(editing)ExcerptHandle.entries.filter{it!=ExcerptHandle.MOVE}.forEach{h->val p=point(h,bounds)
            paint.style=Paint.Style.FILL;paint.color=android.graphics.Color.WHITE;c.drawCircle(p.x.toFloat(),p.y.toFloat(),(5*density).toFloat(),paint)
            paint.style=Paint.Style.STROKE;paint.color=0xffd15ba8.toInt();c.drawCircle(p.x.toFloat(),p.y.toFloat(),(5*density).toFloat(),paint)
        }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(canvasView==null||!enabledInput)return true
        if(e.pointerCount>1){cancel();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                val p=author(e.x,e.y)
                val h=if(editing)ExcerptHandle.entries.filter{it!=ExcerptHandle.MOVE}.map{it to point(it,bounds)}
                    .minByOrNull{hypot(it.second.x-e.x,it.second.y-e.y)}?.takeIf{hypot(it.second.x-e.x,it.second.y-e.y)<=24*density}?.first else null
                handle=h?:ExcerptHandle.MOVE.takeIf{p.x in bounds.left..bounds.right&&p.y in bounds.top..bounds.bottom}
                if(handle==null){if(!editing)onDismiss();return true}
                if(editing){start=p;original=bounds;parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}
            }
            MotionEvent.ACTION_MOVE->move(e)
            MotionEvent.ACTION_UP->{move(e);finish();performClick()}
            MotionEvent.ACTION_CANCEL->cancel()
        };return true
    }
    private fun move(e:MotionEvent){
        val b=original?:return;val a=start?:return;val h=handle?:return;val p=author(e.x,e.y)
        val limit=if(world)CanvasBounds(-BoardLimits.WORLD.toDouble(),-BoardLimits.WORLD.toDouble(),BoardLimits.WORLD.toDouble(),BoardLimits.WORLD.toDouble())else CanvasBounds(0.0,0.0,1000.0,1414.0)
        bounds=ExcerptBounds.drag(b,h,p.x-a.x,p.y-a.y,limit);onChange(bounds);invalidate()
    }
    private fun cancel(){original?.let{bounds=it;onChange(it)};finish();invalidate()}
    private fun finish(){val active=original!=null;original=null;start=null;handle=null;if(active)onActive(false);parent?.requestDisallowInterceptTouchEvent(false)}
    override fun onDetachedFromWindow(){finish();super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
