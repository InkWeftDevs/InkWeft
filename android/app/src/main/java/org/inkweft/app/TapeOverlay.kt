package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.inkweft.core.*
import java.util.UUID
import kotlin.math.*

/** Drawing a tape never inserts a default object at the viewport centre. */
internal class TapeOverlay(context:Context):View(context){
    var canvasView:InkCanvasView?=null
    var objects:List<PageObject> = emptyList()
    var world=false
    var enabledInput=true
    var mode=2
    var pattern=TapePattern.STRIPES
    var tapeWidth=32f
    var tapeColor=0xffe5c66c.toInt()
    var onCreate:(PageObject)->Unit={}
    var onToggle:(PageObject)->Unit={}
    var onActive:(Boolean)->Unit={}
    private val points=mutableListOf<CanvasPoint>()
    private var pressed:PageObject?=null
    private var active=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get()=resources.displayMetrics.density.toDouble()
    private fun point(e:MotionEvent)=canvasView!!.snapshotViewport().screenToWorld(e.x.toDouble(),e.y.toDouble(),width.toDouble(),height.toDouble(),density)
    private fun draft():PageObject? {
        if(points.size<2)return null
        val source=if(mode==2)points else listOf(points.first(),points.last())
        val radius=if(mode==1)0f else tapeWidth/2
        val maxX=if(world)BoardLimits.WORLD.toDouble() else 1000.0;val maxY=if(world)BoardLimits.WORLD.toDouble()else 1414.0
        val min=if(world)-BoardLimits.WORLD.toDouble()+4000 else 0.0
        val l=(source.minOf{it.x}-radius).coerceIn(min,maxX-24);val t=(source.minOf{it.y}-radius).coerceIn(min,maxY-24)
        val w=(source.maxOf{it.x}+radius-l).coerceIn(24.0,minOf(4000.0,maxX-l));val h=(source.maxOf{it.y}+radius-t).coerceIn(24.0,minOf(4000.0,maxY-t))
        return PageObject(UUID.randomUUID().toString(),PageObjectKind.TAPE,l.toFloat(),t.toFloat(),w.toFloat(),h.toFloat(),color=tapeColor,
            tapePoints=if(mode==1)emptyList()else source.map{TapePoint((it.x-l).toFloat().coerceIn(0f,w.toFloat()),(it.y-t).toFloat().coerceIn(0f,h.toFloat()))},lineWidth=tapeWidth,tapePattern=pattern)
    }
    override fun onDraw(c:Canvas){
        val v=canvasView?:return;val d=draft()?:return;val vp=v.snapshotViewport();val f=(vp.zoom*density).toFloat()
        val n=c.save();c.translate((width/2-vp.centerX*f).toFloat(),(height/2-vp.centerY*f).toFloat());c.scale(f,f)
        TapeArt.draw(c,d);c.restoreToCount(n)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val v=canvasView?:return true
        if(!enabledInput||e.pointerCount>1||e.actionMasked==MotionEvent.ACTION_CANCEL){cancel();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{active=true;points.clear();val p=point(e);points+=p;pressed=objects.asReversed().firstOrNull{!it.hidden&&it.kind==PageObjectKind.TAPE&&ObjectGeometry.hit(it,p.x.toFloat(),p.y.toFloat())};parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}
            MotionEvent.ACTION_MOVE->{if(active){val p=point(e);if(points.size<2048&&hypot(p.x-points.last().x,p.y-points.last().y)>1)points+=p;invalidate()}}
            MotionEvent.ACTION_UP->{if(active){val p=point(e);val tap=points.all{hypot(it.x-points.first().x,it.y-points.first().y)<8/(v.snapshotViewport().zoom*density)}&&hypot(p.x-points.first().x,p.y-points.first().y)<8/(v.snapshotViewport().zoom*density)
                if(points.size<2048)points+=p
                val hit=pressed;val result=if(tap)null else draft();cancel()
                if(tap&&hit!=null)onToggle(hit)else if(result!=null)onCreate(result);performClick()
            }}
        };return true
    }
    private fun cancel(){if(active)onActive(false);active=false;points.clear();pressed=null;parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
