// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.*
import org.inkweft.data.*
import kotlin.math.*
import androidx.compose.ui.graphics.toArgb
import org.inkweft.app.ui.designsystem.InkTheme

/** Bounded native map canvas. Cards own text; this view owns only temporary drag/pan. */
internal data class MapViewport(val scale:Float,val x:Float,val y:Float)
internal class MindMapView(context:Context):View(context){
    var enabledInput=true
    var captureBook=""
    var captureMapKey=""
    private var capturedMap=""
    var captureGraph=""
    var onCapture:(CaptureTransfer,StudyNodeRow?,Double,Double,String)->Unit={_,_,_,_,_->}
    private var capturedGraph=""
    private var dropPoint:PointF?=null
    private var dropParent:StudyNodeRow?=null
    private var candidate:String?=null
    private var candidateSince=0L
    override fun onDragEvent(e:DragEvent):Boolean{
        val transfer=e.localState as? CaptureTransfer?:return false
        if(transfer.book!=captureBook)return false
        when(e.action){
            DragEvent.ACTION_DRAG_STARTED->{capturedGraph=captureGraph;capturedMap=captureMapKey;dropParent=null;candidate=null;return true}
            DragEvent.ACTION_DRAG_LOCATION,DragEvent.ACTION_DROP->{
                if(!enabledInput||capturedMap!=captureMapKey){dropPoint=null;dropParent=null;invalidate();return true}
                val p=PointF((e.x-tx)/(scale*d),(e.y-ty)/(scale*d));dropPoint=p
                fun distance(n:StudyNodeRow):Float{val sx=(n.x*d*scale+tx).toFloat();val sy=(n.y*d*scale+ty).toFloat();return hypot((sx-e.x).coerceAtLeast(0f)+(e.x-sx-216*d*scale).coerceAtLeast(0f),(sy-e.y).coerceAtLeast(0f)+(e.y-sy-84*d*scale).coerceAtLeast(0f))}
                val nearest=dropParent?.takeIf{distance(it)<=36*d}?:nodes.minByOrNull{distance(it)}?.takeIf{distance(it)<=24*d}
                val now=android.os.SystemClock.uptimeMillis()
                if(candidate!=nearest?.id){candidate=nearest?.id;candidateSince=now}
                if(nearest==null)dropParent=null else if(now-candidateSince>=120)dropParent=nearest
                if(e.action==DragEvent.ACTION_DROP){
                    // An unstable near-node target is rejected, never silently changed to a root.
                    if(nearest==null||dropParent?.id==nearest.id)onCapture(transfer,dropParent,p.x.toDouble().coerceIn(-40000.0,40000.0),p.y.toDouble().coerceIn(-40000.0,40000.0),capturedGraph)
                    dropPoint=null;dropParent=null
                }
                invalidate()
            }
            DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->{dropPoint=null;dropParent=null;candidate=null;invalidate()}
        };return true
    }
    var selectedNodeId:String?=null
    var onViewport:(MapViewport)->Unit={}
    private var positioned=false
    fun snapshotViewport()=MapViewport(scale,tx,ty)
    fun restoreViewport(v:MapViewport){scale=v.scale;tx=v.x;ty=v.y;positioned=true;invalidate()}
    private fun changedViewport(){positioned=true;onViewport(snapshotViewport())}
    var onOpen:(StudyNodeRow)->Unit={}
    var onMove:(StudyNodeRow,Double,Double)->Unit={_,_,_->}
    var onActive:(Boolean)->Unit={}
    private var nodes=emptyList<StudyNodeRow>();private var titles=emptyMap<String,String>()
    private var relationEdges=emptyList<Pair<String,String>>()
    private var relationMode=false
    private var hiddenCounts=emptyMap<String,Int>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale=.8f;private var tx=20f;private var ty=20f
    private var active:StudyNodeRow?=null;private var moving=false;private var multi=false
    private var startX=0f;private var startY=0f;private var lastX=0f;private var lastY=0f
    private var dx=0f;private var dy=0f
    private val d get()=resources.displayMetrics.density
    private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(s:ScaleGestureDetector):Boolean{val old=scale;scale=(scale*s.scaleFactor).coerceIn(.15f,2.5f);tx=s.focusX-(s.focusX-tx)*scale/old;ty=s.focusY-(s.focusY-ty)*scale/old;changedViewport();invalidate();return true}
    })
    init{contentDescription="可缩放思维导图，拖动节点移动；需要无障碍浏览时切换大纲视图。"}
    fun show(items:List<StudyNodeRow>,cards:List<StudyCardRow>,collapsed:Map<String,Int> = emptyMap()){
        relationMode=false;relationEdges=emptyList()
        hiddenCounts=collapsed
        val oldEmpty=nodes.isEmpty();nodes=items.filter{!it.removed};titles=cards.associate{it.id to it.title}
        if(!positioned&&oldEmpty&&nodes.isNotEmpty()&&width>0)fit();invalidate()
    }
    fun showRelations(items:List<StudyNodeRow>,labels:Map<String,String>,edges:List<Pair<String,String>>){
        val empty=nodes.isEmpty();nodes=items;relationMode=true;relationEdges=edges;titles=labels;hiddenCounts=emptyMap()
        if(empty&&nodes.isNotEmpty()&&width>0)fit();invalidate()
    }
    fun decorations(edges:List<Pair<String,String>>){relationEdges=edges;invalidate()}
    fun fit(){if(nodes.isEmpty()||width==0||height==0)return
        val left=nodes.minOf{it.x};val right=nodes.maxOf{it.x}+216;val top=nodes.minOf{it.y};val bottom=nodes.maxOf{it.y}+84
        scale=min((width-40*d)/((right-left)*d).toFloat(),(height-40*d)/((bottom-top)*d).toFloat()).coerceIn(.8f,1.3f)
        // Keep the leading nodes whole when readable scaling requires panning.
        tx=max(width/2-((left+right)/2*d*scale).toFloat(),20*d-(left*d*scale).toFloat())
        ty=max(height/2-((top+bottom)/2*d*scale).toFloat(),20*d-(top*d*scale).toFloat());changedViewport();invalidate()
    }
    fun zoom(f:Float){val old=scale;scale=(scale*f).coerceIn(.15f,2.5f);tx=width/2-(width/2-tx)*scale/old;ty=height/2-(height/2-ty)*scale/old;changedViewport();invalidate()}
    private fun x(n:StudyNodeRow)=n.x.toFloat()+if(n.id==active?.id)dx else 0f
    private fun y(n:StudyNodeRow)=n.y.toFloat()+if(n.id==active?.id)dy else 0f
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(oldw==0&&!positioned)fit()}
    // AndroidView can share a Compose canvas with surrounding controls. A background
    // drawColor and transformed nodes must never paint outside this viewport.
    override fun draw(canvas:Canvas){val save=canvas.save();try{canvas.clipRect(0,0,width,height);super.draw(canvas)}finally{canvas.restoreToCount(save)}}
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(InkTheme.Workspace.toArgb());val save=c.save();c.translate(tx,ty);c.scale(scale*d,scale*d)
        val lookup=nodes.associateBy{it.id};paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=InkTheme.Divider.toArgb()
        for(n in nodes){val p=lookup[n.parentId]?:continue;val right=x(n)>=x(p);val start=x(p)+if(right)216 else 0;val end=x(n)+if(right)0 else 216;val direction=if(right)1 else -1;val path=Path();path.moveTo(start,y(p)+42);path.cubicTo(start+28*direction,y(p)+42,end-28*direction,y(n)+42,end,y(n)+42);c.drawPath(path,paint)}
        paint.color=InkTheme.Accent.toArgb()
        for((a,b) in relationEdges){val start=lookup[a]?:continue;val end=lookup[b]?:continue
            val horizontal=abs(x(end)-x(start))>=abs(y(end)-y(start));val forward=if(horizontal)x(end)>=x(start)else y(end)>=y(start)
            val sx=x(start)+if(horizontal){if(forward)216 else 0}else 108;val ex=x(end)+if(horizontal){if(forward)0 else 216}else 108
            val sy=y(start)+if(horizontal)42 else if(forward)84 else 0;val ey=y(end)+if(horizontal)42 else if(forward)0 else 84
            c.drawLine(sx,sy,ex,ey,paint)
            val angle=atan2(ey-sy,ex-sx);val ax=sx+(ex-sx)*.7f;val ay=sy+(ey-sy)*.7f
            if(relationMode){c.drawLine(ax,ay,ax-12*cos(angle-.45f),ay-12*sin(angle-.45f),paint);c.drawLine(ax,ay,ax-12*cos(angle+.45f),ay-12*sin(angle+.45f),paint)}
        }
        for(n in nodes){val left=x(n);val top=y(n);val box=RectF(left,top,left+216,top+84)
            val rootNode=!relationMode&&n.parentId==null
            paint.style=Paint.Style.FILL;paint.color=if(rootNode)InkTheme.Accent.toArgb()else if((n.id==active?.id||n.id==selectedNodeId))InkTheme.Selected.toArgb()else Color.WHITE;c.drawRoundRect(box,12f,12f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=if(n.id==active?.id||n.id==selectedNodeId)2f else 1f;paint.color=if(n.id==active?.id||n.id==selectedNodeId||rootNode)InkTheme.Accent.toArgb()else InkTheme.ControlBorder.toArgb();c.drawRoundRect(box,12f,12f,paint)
            paint.style=Paint.Style.FILL;paint.color=if(rootNode)Color.WHITE else InkTheme.Text.toArgb();paint.textSize=16f*resources.configuration.fontScale;paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
            val text=titles[if(relationMode)n.id else n.cardId].orEmpty().replace('\n',' ');val n1=paint.breakText(text,true,188f,null)
            c.drawText(text.take(n1),left+14,top+30,paint);val rest=text.drop(n1);val n2=paint.breakText(rest,true,176f,null)
            c.drawText(rest.take(n2)+(if(rest.length>n2)"…"else""),left+14,top+53,paint)
            paint.typeface=Typeface.DEFAULT;paint.textSize=12f;paint.color=if(rootNode)Color.WHITE else InkTheme.Secondary.toArgb();c.drawText(if((hiddenCounts[n.id]?:0)>0)"已收起 ${hiddenCounts[n.id]} 个下级"else"",left+14,top+73,paint)
        }
        dropPoint?.let{point->
            paint.style=Paint.Style.STROKE;paint.color=InkTheme.Accent.toArgb();paint.strokeWidth=2f/scale
            paint.pathEffect=DashPathEffect(floatArrayOf(6f/scale,4f/scale),0f)
            dropParent?.let{parent->c.drawRoundRect(parent.x.toFloat()-3,parent.y.toFloat()-3,parent.x.toFloat()+219,parent.y.toFloat()+87,12f,12f,paint);c.drawLine(parent.x.toFloat()+216,parent.y.toFloat()+42,point.x,point.y,paint)}
            c.drawRoundRect(point.x,point.y,point.x+168,point.y+52,8f,8f,paint);paint.pathEffect=null
        }
        c.restoreToCount(save)
        if(dropPoint!=null){
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawRoundRect(8*d,8*d,width-8*d,44*d,8*d,8*d,paint)
            paint.color=InkTheme.Accent.toArgb();paint.textSize=13*d*resources.configuration.fontScale
            val label=dropParent?.let{"松手：添加到 "+titles[it.cardId].orEmpty()}?:if(candidate!=null)"停留以选择分支"else"松手：放到根层"
            val end=paint.breakText(label,true,width-32*d,null);c.drawText(label.take(end),16*d,32*d,paint)
        }
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
                lastX=e.x;lastY=e.y;if(active==null)changedViewport();invalidate()}
            MotionEvent.ACTION_UP->{val n=active;val mx=dx;val my=dy;active=null;dx=0f;dy=0f;onActive(false);parent?.requestDisallowInterceptTouchEvent(false)
                if(!multi&&n!=null){if(moving)onMove(n,(n.x+mx).coerceIn(-40000.0,40000.0),(n.y+my).coerceIn(-40000.0,40000.0))else onOpen(n)};invalidate();performClick()}
            MotionEvent.ACTION_CANCEL->{active=null;dx=0f;dy=0f;onActive(false);invalidate()}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
