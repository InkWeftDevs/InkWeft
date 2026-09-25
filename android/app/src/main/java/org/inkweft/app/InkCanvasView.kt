// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.*
import org.inkweft.core.*
import java.util.UUID
import kotlin.math.*

/** Viewport-sized drawing only. No giant bitmap, no author resampling when zooming. */
class InkCanvasView(context:Context):View(context){
    var onStroke:(InkStroke)->Unit={}
    var onErase:(List<InkSample>)->Unit={}
    var onGesture:(Boolean)->Unit={}
    var onNotice:(String)->Unit={}
    var onAxes:(Boolean,Boolean)->Unit={_,_->}
    var onViewport:(CanvasViewport)->Unit={}
    var onScale:(Double)->Unit={}
    var allowInput=false
    var fingerWrites=false
    var eraseMode=false
    var preview=false
    var pen=InkPen.PEN
    var penColor=0xff24342f.toInt()
    var penWidth=3f
    private var world=false
    private var paper=PaperStyle.RULED
    private var configured=false
    private var restored=false
    private var viewport=CanvasViewport()
    private var content=emptyList<InkStroke>()
    private val bounds=mutableMapOf<String,CanvasBounds>()
    private val meshes=object:LinkedHashMap<String,Stroke>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Stroke>?)=size>128}
    private val transient=LinkedHashMap<String,Stroke>()
    private val renderer=CanvasStrokeRenderer.create()
    private val live=InProgressStroke()
    private val incremental=MutableStrokeInputBatch()
    private val empty=MutableStrokeInputBatch()
    private val matrix=Matrix()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var inputId=-1
    private var inputKind=InkTool.TOUCH
    private var raw=ArrayList<InkSample>()
    private var gesturePen=InkPen.PEN
    private var gestureColor=penColor
    private var gestureWidth=penWidth
    private var gestureErase=false
    private var startTime=0L
    private var hasPressure=false
    private var hasTilt=false
    private var hasOrientation=false
    private var pressureMin=0f
    private var pressureSpan=1f
    private var panPointer=-1
    private var panLastX=0f
    private var panLastY=0f
    private var movingViewport=false
    private val density get()=resources.displayMetrics.density.toDouble()
    private val scaleDetector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean{
            if(inputId!=-1 && inputKind==InkTool.STYLUS)return false
            viewport=viewport.zoomAt(detector.scaleFactor.toDouble(),detector.focusX.toDouble(),detector.focusY.toDouble(),width.toDouble(),height.toDouble(),density)
            movingViewport=true;transform();invalidate();return true
        }
    })
    init{isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="手写画布。双指缩放，默认手指平移。手写尚未识别文字。"}
    fun configure(board:Boolean,style:PaperStyle,saved:CanvasViewport?){
        paper=style
        if(preview&&configured&&world!=board){configured=false;restored=false;meshes.clear()}
        if(!configured){world=board;configured=true;restored=saved!=null;viewport=saved?:if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport();if(width>0 && height>0 && !restored)initialFit()}
        check(world==board){"A canvas identity cannot change coordinate space"}
        transform();invalidate()
    }
    fun snapshotViewport()=viewport
    fun showStrokes(strokes:List<InkStroke>){
        if(content===strokes)return
        content=strokes
        val ids=strokes.map{it.id}.toSet();meshes.keys.retainAll(ids);bounds.keys.retainAll(ids)
        for(s in strokes){if(!bounds.containsKey(s.id))bounds[s.id]=s.bounds();transient.remove(s.id)?.let{meshes[s.id]=it}}
        if(preview && width>0 && height>0){if(world)fitContent(false)else fitPage(false)}
        invalidate()
    }
    private fun family(p:InkPen,pressure:Boolean)=when{p==InkPen.HIGHLIGHTER->StockBrushes.highlighter();pressure->StockBrushes.pressurePen();else->StockBrushes.marker()}
    private fun brush(p:InkPen,color:Int,w:Float,pressure:Boolean)=Brush.createWithColorIntArgb(family(p,pressure),color,w,.1f)
    private fun inputType(tool:InkTool)=when(tool){InkTool.STYLUS->InputToolType.STYLUS;InkTool.MOUSE->InputToolType.MOUSE;InkTool.TOUCH->InputToolType.TOUCH}
    private fun batchPoint(batch:MutableStrokeInputBatch,p:InkSample,tool:InkTool){batch.add(inputType(tool),p.x,p.y,p.elapsedMs,pressure=if(p.pressure<0)StrokeInput.NO_PRESSURE else p.pressure,tiltRadians=if(p.tilt<0)StrokeInput.NO_TILT else p.tilt,orientationRadians=if(p.orientation<0)StrokeInput.NO_ORIENTATION else p.orientation)}
    private fun toInk(s:InkStroke):Stroke{val inputs=MutableStrokeInputBatch();s.samples.forEach{batchPoint(inputs,it,s.tool)};return Stroke(brush(s.pen,s.color,s.width,s.samples.first().pressure>=0),inputs)}
    private fun transform(){val factor=(viewport.zoom*density).toFloat();matrix.setScale(factor,factor);matrix.postTranslate((width/2-viewport.centerX*factor).toFloat(),(height/2-viewport.centerY*factor).toFloat());onScale(viewport.zoom)}
    private fun initialFit(){if(world)viewport=CanvasViewport(0.0,0.0,.8)else viewport=CanvasViewport.pageWidth(width/density,height/density);if(preview)fitPage(false);transform()}
    fun fitPage(publish:Boolean=true){cancelGesture();viewport=CanvasViewport.fit(CanvasBounds(0.0,0.0,1000.0,1414.0),width/density,height/density);transform();invalidate();if(publish)onViewport(viewport)}
    fun fitWidth(){cancelGesture();viewport=CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    fun origin(){cancelGesture();viewport=if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    fun fitContent(publish:Boolean=true){
        cancelGesture()
        if(content.isEmpty()){if(world)viewport=CanvasViewport(0.0,0.0,.8)else fitPage(false)}
        else{val b=bounds.values.reduce{a,c->a.union(c)}.padded(40.0);viewport=CanvasViewport.fit(b,width/density,height/density)}
        transform();invalidate();if(publish)onViewport(viewport)
    }
    fun zoomBy(ratio:Double){cancelGesture();viewport=viewport.zoomAt(ratio,width/2.0,height/2.0,width.toDouble(),height.toDouble(),density);transform();invalidate();onViewport(viewport)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){cancelGesture();if(configured && oldw==0 && !restored)initialFit();if(preview)if(world)fitContent(false)else fitPage(false);transform()}
    override fun draw(canvas:Canvas){
        // AndroidView does not clip to its layout bounds. drawColor otherwise
        // paints over the Compose toolbar even while its semantics remain live.
        // The viewport clip is independent of the source-page clip and must stay
        // in local view coordinates, before any pan/zoom or Ink render transform.
        val viewportClip=canvas.save()
        try{
            canvas.clipRect(0,0,width,height)
            super.draw(canvas)
        }finally{canvas.restoreToCount(viewportClip)}
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(!configured)return
        canvas.drawColor(if(world)Color.WHITE else Color.rgb(241,243,244))
        val visible=viewport.visible(width.toDouble(),height.toDouble(),density)
        canvas.save();canvas.concat(matrix)
        if(!world){paint.color=Color.WHITE;canvas.drawRect(0f,0f,1000f,1414f,paint);canvas.clipRect(0f,0f,1000f,1414f)}
        drawGuide(canvas,visible)
        // Keep author order, including after hide/undo/cache eviction.
        for(s in content){if(bounds[s.id]?.intersects(visible)!=true)continue;val mesh=meshes[s.id]?:toInk(s).also{meshes[s.id]=it};renderer.draw(canvas,mesh,matrix)}
        transient.values.forEach{renderer.draw(canvas,it,matrix)}
        if(inputId!=-1 && !gestureErase && raw.isNotEmpty()){live.updateShape();renderer.draw(canvas,live,matrix)}
        if(inputId!=-1 && gestureErase && raw.isNotEmpty()){paint.color=0x553f7d67;val p=raw.last();canvas.drawCircle(p.x,p.y,14f,paint)}
        canvas.restore()
    }
    private fun drawGuide(c:Canvas,v:CanvasBounds){
        if(paper==PaperStyle.BLANK)return
        val factor=viewport.zoom*density
        var gap=if(paper==PaperStyle.RULED)55.0 else 40.0
        while(gap*factor<18.0)gap*=2
        paint.color=if(world)Color.rgb(226,233,230)else Color.rgb(232,236,234);paint.strokeWidth=(1.0/factor).toFloat()
        val l=if(world)v.left else max(40.0,v.left);val r=if(world)v.right else min(960.0,v.right)
        val t=if(world)v.top else max(80.0,v.top);val b=if(world)v.bottom else min(1334.0,v.bottom)
        if(l>r || t>b)return
        val startX=floor(l/gap)*gap;val startY=floor(t/gap)*gap
        val nx=ceil((r-startX)/gap).toInt().coerceIn(0,240);val ny=ceil((b-startY)/gap).toInt().coerceIn(0,240)
        if(paper==PaperStyle.DOTS){for(y in 0..ny)for(x in 0..nx)c.drawCircle((startX+x*gap).toFloat(),(startY+y*gap).toFloat(),(1.0/factor).toFloat(),paint)}
        else{for(y in 0..ny)c.drawLine(l.toFloat(),(startY+y*gap).toFloat(),r.toFloat(),(startY+y*gap).toFloat(),paint);if(paper==PaperStyle.GRID)for(x in 0..nx)c.drawLine((startX+x*gap).toFloat(),t.toFloat(),(startX+x*gap).toFloat(),b.toFloat(),paint)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(preview)return false
        if(!configured)return true
        if(e.actionMasked==MotionEvent.ACTION_CANCEL || (e.flags and MotionEvent.FLAG_CANCELED)!=0){cancelGesture();panPointer=-1;finishViewport();return true}
        if(inputId==-1 || inputKind!=InkTool.STYLUS)scaleDetector.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){if(inputKind!=InkTool.STYLUS)cancelGesture();panPointer=-1;return true}
        if(e.actionMasked==MotionEvent.ACTION_DOWN){
            if(inputId!=-1)cancelGesture()
            val type=e.getToolType(0);inputKind=when(type){MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER->InkTool.STYLUS;MotionEvent.TOOL_TYPE_MOUSE->InkTool.MOUSE;else->InkTool.TOUCH}
            if(inputKind==InkTool.TOUCH && !fingerWrites){panPointer=e.getPointerId(0);panLastX=e.x;panLastY=e.y;return true}
            if(!allowInput)return true
            val point=viewport.screenToWorld(e.x.toDouble(),e.y.toDouble(),width.toDouble(),height.toDouble(),density)
            if(!world && (point.x !in 0.0..1000.0 || point.y !in 0.0..1414.0))return true
            if(abs(point.x)>BoardLimits.WORLD || abs(point.y)>BoardLimits.WORLD){onNotice("已到达无界画布的数值安全范围，请返回内容区域或新建笔记。");return true}
            parent?.requestDisallowInterceptTouchEvent(true);requestFocus();inputId=e.getPointerId(0);startTime=e.eventTime
            gesturePen=pen;gestureColor=penColor;gestureWidth=penWidth;gestureErase=eraseMode||type==MotionEvent.TOOL_TYPE_ERASER
            raw=ArrayList();val device=e.device;val pressure=device?.getMotionRange(MotionEvent.AXIS_PRESSURE,e.source)
            hasPressure=inputKind==InkTool.STYLUS && pressure!=null && pressure.max>pressure.min;pressureMin=pressure?.min?:0f;pressureSpan=(pressure?.range?:1f).coerceAtLeast(.001f)
            hasTilt=inputKind==InkTool.STYLUS && device?.getMotionRange(MotionEvent.AXIS_TILT,e.source)!=null;hasOrientation=inputKind==InkTool.STYLUS && device?.getMotionRange(MotionEvent.AXIS_ORIENTATION,e.source)!=null
            onAxes(hasPressure,hasTilt);onGesture(true);if(!gestureErase)live.start(brush(gesturePen,gestureColor,gestureWidth,hasPressure))
            append(e,0,-1);postInvalidateOnAnimation();return true
        }
        if(inputId==-1){
            if(e.actionMasked==MotionEvent.ACTION_MOVE && panPointer!=-1 && e.pointerCount==1 && !scaleDetector.isInProgress){viewport=viewport.pan((e.x-panLastX).toDouble(),(e.y-panLastY).toDouble(),density);panLastX=e.x;panLastY=e.y;movingViewport=true;transform();invalidate()}
            if(e.actionMasked==MotionEvent.ACTION_UP){panPointer=-1;finishViewport();performClick()}
            return true
        }
        val index=e.findPointerIndex(inputId);if(index<0){cancelGesture();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_MOVE->{if(scaleDetector.isInProgress && inputKind!=InkTool.STYLUS){cancelGesture();return true};for(i in 0 until e.historySize)append(e,index,i);append(e,index,-1);postInvalidateOnAnimation()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{if(e.getPointerId(e.actionIndex)!=inputId)return true;append(e,index,-1);finishGesture();performClick()}
        };return true
    }
    private fun append(e:MotionEvent,index:Int,history:Int){
        if(raw.size>=InkLimits.MAX_POINTS)return
        fun axis(a:Int)=if(history<0)e.getAxisValue(a,index)else e.getHistoricalAxisValue(a,index,history)
        val x=if(history<0)e.getX(index)else e.getHistoricalX(index,history);val y=if(history<0)e.getY(index)else e.getHistoricalY(index,history)
        val time=(if(history<0)e.eventTime else e.getHistoricalEventTime(history))-startTime
        if(!x.isFinite()||!y.isFinite()||time<0||time>3_600_000)return
        val p=if(hasPressure)((axis(MotionEvent.AXIS_PRESSURE)-pressureMin)/pressureSpan).coerceIn(0f,1f)else -1f
        val tilt=if(hasTilt)axis(MotionEvent.AXIS_TILT).coerceIn(0f,Math.PI.toFloat()/2)else -1f
        val orientation=if(hasOrientation)((axis(MotionEvent.AXIS_ORIENTATION)%(2*Math.PI.toFloat()))+2*Math.PI.toFloat())%(2*Math.PI.toFloat())else -1f
        if(!p.isFinite()||!tilt.isFinite()||!orientation.isFinite())return
        val w=viewport.screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
        if(world && (abs(w.x)>BoardLimits.WORLD || abs(w.y)>BoardLimits.WORLD)){onNotice("达到画布数值边界，边界外采样未接收；请抬笔并返回内容。");return}
        val point=InkSample(if(world)w.x.toFloat()else w.x.toFloat().coerceIn(0f,1000f),if(world)w.y.toFloat()else w.y.toFloat().coerceIn(0f,1414f),time,p,tilt,orientation,world)
        if(raw.lastOrNull()?.let{it==point||it.elapsedMs>time}==true)return
        try{if(!gestureErase){incremental.clear();batchPoint(incremental,point,inputKind);live.enqueueInputs(incremental,empty)};raw.add(point);if(raw.size==InkLimits.MAX_POINTS)onNotice("达到单笔采样上限，请抬笔提交后继续。")}catch(_:IllegalArgumentException){onNotice("设备返回了无效采样，该点未进入笔迹。")}
    }
    private fun finishGesture(){
        if(inputId==-1)return
        try{if(raw.isNotEmpty()){
            if(gestureErase)onErase(raw.toList())
            else{live.finishInput();live.updateShape();val stroke=InkStroke(UUID.randomUUID().toString(),gesturePen,gestureColor,gestureWidth,inputKind,raw,world);transient[stroke.id]=live.toImmutable();try{onStroke(stroke)}catch(e:Exception){transient.remove(stroke.id);throw e}}
        }}catch(_:Exception){onNotice("本次操作未接收；已确认内容不变。请导出副本后检查限制。")}
        finally{inputId=-1;raw.clear();onGesture(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    }
    private fun finishViewport(){if(movingViewport){movingViewport=false;onViewport(viewport)}}
    fun cancelGesture(){val active=inputId!=-1;inputId=-1;raw.clear();parent?.requestDisallowInterceptTouchEvent(false);if(active)onGesture(false);invalidate()}
    override fun onDetachedFromWindow(){cancelGesture();if(configured&&!preview)onViewport(viewport);super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
