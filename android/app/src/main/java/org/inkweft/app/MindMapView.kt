// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.*
import org.inkweft.data.*
import kotlin.math.*

/** Bounded native map canvas. Cards own text; this view owns only temporary drag/pan. */
internal class MindMapView(context:Context):View(context){
    var enabledInput=true
    var onOpen:(StudyNodeRow)->Unit={}
    var onMove:(StudyNodeRow,Double,Double)->Unit={_,_,_->}
    var onActive:(Boolean)->Unit={}
    private var nodes=emptyList<StudyNodeRow>();private var titles=emptyMap<String,String>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale=.8f;private var tx=20f;private var ty=20f
    private var active:StudyNodeRow?=null;private var moving=false;private var multi=false
    private var startX=0f;private var startY=0f;private var lastX=0f;private var lastY=0f
    private var dx=0f;private var dy=0f
    private val d get()=resources.displayMetrics.density
    private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(s:ScaleGestureDetector):Boolean{val old=scale;scale=(scale*s.scaleFactor).coerceIn(.15f,2.5f);tx=s.focusX-(s.focusX-tx)*scale/old;ty=s.focusY-(s.focusY-ty)*scale/old;invalidate();return true}
    })
    init{contentDescription="可缩放思维导图，拖动节点移动；需要无障碍浏览时切换大纲视图。"}
    fun show(items:List<StudyNodeRow>,cards:List<StudyCardRow>){
        val oldEmpty=nodes.isEmpty();nodes=items.filter{!it.removed};titles=cards.associate{it.id to it.title}
        if(oldEmpty&&nodes.isNotEmpty()&&width>0)fit();invalidate()
    }
    fun fit(){if(nodes.isEmpty()||width==0||height==0)return
        val left=nodes.minOf{it.x};val right=nodes.maxOf{it.x}+216;val top=nodes.minOf{it.y};val bottom=nodes.maxOf{it.y}+84
        scale=min((width-40*d)/((right-left)*d).toFloat(),(height-40*d)/((bottom-top)*d).toFloat()).coerceIn(.15f,1.3f)
        tx=width/2-((left+right)/2*d*scale).toFloat();ty=height/2-((top+bottom)/2*d*scale).toFloat();invalidate()
    }
    fun zoom(f:Float){val old=scale;scale=(scale*f).coerceIn(.15f,2.5f);tx=width/2-(width/2-tx)*scale/old;ty=height/2-(height/2-ty)*scale/old;invalidate()}
    private fun x(n:StudyNodeRow)=n.x.toFloat()+if(n.id==active?.id)dx else 0f
    private fun y(n:StudyNodeRow)=n.y.toFloat()+if(n.id==active?.id)dy else 0f
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(oldw==0)fit()}
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(248,250,249));val save=c.save();c.translate(tx,ty);c.scale(scale*d,scale*d)
        val lookup=nodes.associateBy{it.id};paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=0xff92aaa1.toInt()
        for(n in nodes){val p=lookup[n.parentId]?:continue;val path=Path();path.moveTo(x(p)+216,y(p)+42);path.cubicTo(x(p)+244,y(p)+42,x(n)-28,y(n)+42,x(n),y(n)+42);c.drawPath(path,paint)}
        for(n in nodes){val left=x(n);val top=y(n);val box=RectF(left,top,left+216,top+84)
            paint.style=Paint.Style.FILL;paint.color=if(n.id==active?.id)0xffe0f0e8.toInt()else Color.WHITE;c.drawRoundRect(box,12f,12f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=if(n.id==active?.id)2f else 1f;paint.color=0xff73a38e.toInt();c.drawRoundRect(box,12f,12f,paint)
            paint.style=Paint.Style.FILL;paint.color=0xff20342c.toInt();paint.textSize=16f;paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
            val text=titles[n.cardId].orEmpty().replace('\n',' ');val n1=paint.breakText(text,true,188f,null)
            c.drawText(text.take(n1),left+14,top+30,paint);val rest=text.drop(n1);val n2=paint.breakText(rest,true,176f,null)
            c.drawText(rest.take(n2)+(if(rest.length>n2)"…"else""),left+14,top+53,paint)
            paint.typeface=Typeface.DEFAULT;paint.textSize=10f;paint.color=0xff637b70.toInt();c.drawText("摘要卡 · 点击编辑 / 拖动移动",left+14,top+73,paint)
        };c.restoreToCount(save)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(!enabledInput){active=null;dx=0f;dy=0f;onActive(false);invalidate();return true}
        detector.onTouchEvent(e)
        if(e.pointerCount>1){multi=true;active=null;dx=0f;dy=0f;invalidate();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{startX=e.x;startY=e.y;lastX=e.x;lastY=e.y;dx=0f;dy=0f;moving=false;multi=false
                val px=(e.x-tx)/(scale*d);val py=(e.y-ty)/(scale*d);active=nodes.lastOrNull{px>=it.x&&px<=it.x+216&&py>=it.y&&py<=it.y+84}
                parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}
            MotionEvent.ACTION_MOVE->{if(multi)return true
                if(hypot(e.x-startX,e.y-startY)>ViewConfiguration.get(context).scaledTouchSlop)moving=true
                if(active!=null){dx=(e.x-startX)/(scale*d);dy=(e.y-startY)/(scale*d)}else {tx+=e.x-lastX;ty+=e.y-lastY}
                lastX=e.x;lastY=e.y;invalidate()}
            MotionEvent.ACTION_UP->{val n=active;val mx=dx;val my=dy;active=null;dx=0f;dy=0f;onActive(false);parent?.requestDisallowInterceptTouchEvent(false)
                if(!multi&&n!=null){if(moving)onMove(n,(n.x+mx).coerceIn(-40000.0,40000.0),(n.y+my).coerceIn(-40000.0,40000.0))else onOpen(n)};invalidate();performClick()}
            MotionEvent.ACTION_CANCEL->{active=null;dx=0f;dy=0f;onActive(false);invalidate()}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
