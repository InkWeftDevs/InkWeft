// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.inkweft.core.*
import kotlin.math.*

internal class PageObjectOverlay(context:Context):View(context) {
    var canvasView:InkCanvasView?=null
    var objects:List<PageObject> = emptyList()
    var selected:String?=null
    var enabledInput=true
    var world=false
    var onSelect:(String?)->Unit={}
    var onChange:(PageObject)->Unit={}
    var onActive:(Boolean)->Unit={}
    private var original:PageObject?=null
    private var draft:PageObject?=null
    private var start=CanvasPoint(0.0,0.0)
    private var resize=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get()=resources.displayMetrics.density.toDouble()
    private fun author(x:Float,y:Float)=canvasView!!.snapshotViewport().screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
    private fun screen(x:Float,y:Float)=canvasView!!.snapshotViewport().worldToScreen(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
    override fun onDraw(c:Canvas){super.onDraw(c);if(canvasView==null)return
        val o=draft?:objects.find{it.id==selected}?:return
        val a=screen(o.x,o.y);val b=screen(o.x+o.width,o.y+o.height)
        paint.color=0xff216b59.toInt();paint.strokeWidth=(2*density).toFloat();paint.style=Paint.Style.STROKE
        c.drawRect(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint)
        paint.style=Paint.Style.FILL;c.drawCircle(b.x.toFloat(),b.y.toFloat(),(8*density).toFloat(),paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val v=canvasView?:return true
        if(!enabledInput){cancel();return true}
        if(e.pointerCount>1){cancel();v.onTouchEvent(e);return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                start=author(e.x,e.y)
                val old=objects.find{it.id==selected}
                resize=old?.let{val p=screen(it.x+it.width,it.y+it.height);hypot(e.x-p.x,e.y-p.y)<=24*density}?:false
                original=if(resize)old else objects.asReversed().find{start.x>=it.x&&start.x<=it.x+it.width&&start.y>=it.y&&start.y<=it.y+it.height}
                draft=original;onSelect(original?.id)
                if(original!=null){parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}else v.onTouchEvent(e)
                invalidate()
            }
            MotionEvent.ACTION_MOVE->{
                val o=original
                if(o==null){v.onTouchEvent(e);invalidate();return true}
                val p=author(e.x,e.y);val dx=(p.x-start.x).toFloat();val dy=(p.y-start.y).toFloat()
                val maxX=if(world)BoardLimits.WORLD else 1000f;val maxY=if(world)BoardLimits.WORLD else 1414f
                draft=if(resize){
                    val maxW=min(4000f,maxX-o.x);val maxH=min(4000f,maxY-o.y)
                    if(o.kind==PageObjectKind.IMAGE){val ratio=o.height/o.width;val w=(o.width+dx).coerceIn(max(24f,24f/ratio),min(maxW,maxH/ratio));o.copy(width=w,height=w*ratio)}
                    else if(o.kind==PageObjectKind.TEXT){
                        val w=(o.width+dx).coerceIn(24f,maxW)
                        val layout=android.text.StaticLayout.Builder.obtain(o.text,0,o.text.length,android.text.TextPaint().apply{textSize=o.fontSize},w.toInt()).setIncludePad(false).build()
                        val h=max(24f,layout.height.toFloat()+8f)
                        if(h<=maxH)o.copy(width=w,height=h)else o
                    }else o.copy(width=(o.width+dx).coerceIn(24f,maxW),height=(o.height+dy).coerceIn(24f,maxH))
                }else o.copy(x=(o.x+dx).coerceIn(if(world)-BoardLimits.WORLD+o.width else 0f,maxX-o.width),y=(o.y+dy).coerceIn(if(world)-BoardLimits.WORLD+o.height else 0f,maxY-o.height))
                v.previewObject(draft);invalidate()
            }
            MotionEvent.ACTION_UP->{val result=draft;val changed=result!=original;val active=original!=null;cancel();if(changed&&result!=null)onChange(result);if(!active)v.onTouchEvent(e);performClick()}
            MotionEvent.ACTION_CANCEL->cancel()
        };return true
    }
    private fun cancel(){val active=original!=null;original=null;draft=null;canvasView?.previewObject(null);if(active)onActive(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
