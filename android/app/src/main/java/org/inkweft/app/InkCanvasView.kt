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

/** A bounded viewport. Partial erase clips ink, never paints the paper colour. */
class InkCanvasView(context:Context):View(context){
    var onStroke:(InkStroke)->Unit={}
    var onErase:(List<InkSample>,Float,Boolean,Boolean)->Unit={_,_,_,_->}
    var onGesture:(Boolean)->Unit={}
    var onNotice:(String)->Unit={}
    var onAxes:(Boolean,Boolean)->Unit={_,_->}
    var onViewport:(CanvasViewport)->Unit={}
    var onScale:(Double)->Unit={}
    var allowInput=false
    var fingerWrites=false
    var eraseMode=false
    var eraserWhole=false
    var eraserHighlighterOnly=false
    var eraserDiameterDp=28f
    var embeddedPage=false
    var preview=false
    var pen=InkPen.PEN
    var penColor=0xff24342f.toInt()
    var penWidth=3f
    private var selectedIds=emptySet<String>()
    private var selectedDx=0f
    private var selectedDy=0f
    fun selectionPreview(ids:Set<String>,dx:Float=0f,dy:Float=0f){
        if(ids==selectedIds&&dx==selectedDx&&dy==selectedDy)return
        selectedIds=ids;selectedDx=dx;selectedDy=dy;invalidate()
    }
    fun focusRegion(bounds:CanvasBounds){cancelGesture();viewport=CanvasViewport.fit(bounds.padded(60.0),width/density,height/density);transform();invalidate()}
    private var world=false
    private var paper=PaperStyle.RULED
    private var configured=false
    private var restored=false
    private var viewport=CanvasViewport()
    private var objects=emptyList<PageObject>()
    private var objectDraft:PageObject?=null
    private val objectPainter=PageObjectPainter()
    fun showObjects(next:List<PageObject>){if(objects==next)return;objects=next;if(preview&&world&&width>0&&height>0)fitContent(false);invalidate()}
    fun previewObject(value:PageObject?){objectDraft=value;invalidate()}
    private fun drawnObjects()=objects.map{if(it.id==objectDraft?.id)objectDraft!! else it}
    private var content=emptyList<InkStroke>()
    private val bounds=mutableMapOf<String,CanvasBounds>()
    private val meshes=object:LinkedHashMap<String,Stroke>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Stroke>?)=size>128}
    private val maskPaths=object:LinkedHashMap<InkCut,Path>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<InkCut,Path>?)=size>128}
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
    private var gestureWhole=false
    private var gestureOnlyHighlighter=false
    private var gestureRadius=12f
    private var cursor:CanvasPoint?=null
    private var eraseTargets=emptySet<String>()
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
            if(inputId!=-1&&inputKind==InkTool.STYLUS)return false
            viewport=viewport.zoomAt(detector.scaleFactor.toDouble(),detector.focusX.toDouble(),detector.focusY.toDouble(),width.toDouble(),height.toDouble(),density)
            movingViewport=true;transform();invalidate();return true
        }
    })
    init{isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="手写画布，双指缩放。橡皮圆圈为实际屏幕擦除范围。"}
    fun configure(board:Boolean,style:PaperStyle,saved:CanvasViewport?){
        paper=style
        if(preview&&configured&&world!=board){configured=false;restored=false;meshes.clear()}
        if(!configured){world=board;configured=true;restored=saved!=null;viewport=saved?:if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport();if(width>0&&height>0&&!restored)initialFit()}
        check(world==board){"A canvas identity cannot change coordinate space"};transform();invalidate()
    }
    fun snapshotViewport()=viewport
    fun showStrokes(strokes:List<InkStroke>){
        if(content===strokes)return
        content=strokes;val ids=strokes.map{it.id}.toSet();meshes.keys.retainAll(ids);bounds.keys.retainAll(ids)
        for(s in strokes){if(!bounds.containsKey(s.id))bounds[s.id]=s.bounds();transient.remove(s.id)?.let{meshes[s.id]=it}}
        if(preview&&width>0&&height>0)if(world)fitContent(false)else fitPage(false)
        invalidate()
    }
    private fun toInk(s:InkStroke):Stroke=InkBrushes.stroke(s)
    private fun transform(){val f=(viewport.zoom*density).toFloat();matrix.setScale(f,f);matrix.postTranslate((width/2-viewport.centerX*f).toFloat(),(height/2-viewport.centerY*f).toFloat());onScale(viewport.zoom)}
    private fun initialFit(){viewport=if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport.pageWidth(width/density,height/density);if(preview||embeddedPage)fitPage(false);transform()}
    fun fitPage(publish:Boolean=true){cancelGesture();viewport=CanvasViewport.fit(CanvasBounds(0.0,0.0,1000.0,1414.0),width/density,height/density);transform();invalidate();if(publish)onViewport(viewport)}
    fun fitWidth(){cancelGesture();viewport=CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    fun origin(){cancelGesture();viewport=if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    fun fitContent(publish:Boolean=true){cancelGesture();val allBounds=bounds.values+objects.map{it.bounds()};if(allBounds.isEmpty()){if(world)viewport=CanvasViewport(0.0,0.0,.8)else fitPage(false)}else viewport=CanvasViewport.fit(allBounds.reduce{a,b->a.union(b)}.padded(40.0),width/density,height/density);transform();invalidate();if(publish)onViewport(viewport)}
    fun zoomBy(ratio:Double){cancelGesture();viewport=viewport.zoomAt(ratio,width/2.0,height/2.0,width.toDouble(),height.toDouble(),density);transform();invalidate();onViewport(viewport)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){cancelGesture();if(configured&&oldw==0&&!restored)initialFit();if(embeddedPage&&configured)fitPage(false);if(preview)if(world)fitContent(false)else fitPage(false);transform()}
    override fun draw(c:Canvas){val save=c.save();try{c.clipRect(0,0,width,height);super.draw(c)}finally{c.restoreToCount(save)}}
    internal fun cutPath(cut:InkCut):Path=maskPaths[cut]?:when(cut.shape){
        InkCutShape.ROUND->sweptPath(cut.points,cut.radius)
        InkCutShape.RECTANGLE->Path().apply{addRect(cut.points[0].x,cut.points[0].y,cut.points[1].x,cut.points[1].y,Path.Direction.CW)}
        InkCutShape.POLYGON->Path().apply{moveTo(cut.points[0].x,cut.points[0].y);cut.points.drop(1).forEach{lineTo(it.x,it.y)};close()}
    }.also{maskPaths[cut]=it}
    private fun sweptPath(points:List<EraserPoint>,radius:Float):Path{
        val result=Path();if(points.isEmpty())return result
        if(points.size==1){result.addCircle(points[0].x,points[0].y,radius,Path.Direction.CW);return result}
        val center=Path();center.moveTo(points[0].x,points[0].y);points.drop(1).forEach{center.lineTo(it.x,it.y)}
        val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=radius*2;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        stroke.getFillPath(center,result);return result
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);if(!configured)return
        canvas.drawColor(if(world)Color.WHITE else Color.rgb(241,243,244))
        val visible=viewport.visible(width.toDouble(),height.toDouble(),density);val save=canvas.save();canvas.concat(matrix)
        if(!world){paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawRect(0f,0f,1000f,1414f,paint);canvas.clipRect(0f,0f,1000f,1414f)}
        drawGuide(canvas,visible)
        objectPainter.draw(canvas,drawnObjects(),false,visible)
        val activeMask=if(inputId!=-1&&gestureErase&&!gestureWhole&&raw.isNotEmpty())sweptPath(raw.map{EraserPoint(it.x,it.y)},gestureRadius)else null
        for(s in content){
            if(bounds[s.id]?.intersects(visible)!=true)continue
            val clipped=canvas.save();if(s.id in selectedIds)canvas.translate(selectedDx,selectedDy);s.cuts.forEach{canvas.clipOutPath(cutPath(it))}
            if(activeMask!=null&&s.id in eraseTargets)canvas.clipOutPath(activeMask)
            val mesh=meshes[s.id]?:toInk(s).also{meshes[s.id]=it};renderer.draw(canvas,mesh,matrix);canvas.restoreToCount(clipped)
        }
        transient.values.forEach{renderer.draw(canvas,it,matrix)}
        if(inputId!=-1&&!gestureErase&&raw.isNotEmpty()){live.updateShape();renderer.draw(canvas,live,matrix)}
        objectPainter.draw(canvas,drawnObjects(),true,visible)
        canvas.restoreToCount(save)
        // Draw cursor in screen space, outside the paper clip. Its diameter is
        // identical to the preview and does not vary with zoom or pen pressure.
        if((eraseMode||gestureErase)&&cursor!=null&&!preview){val p=checkNotNull(cursor);paint.style=Paint.Style.FILL;paint.color=0x183f7d67;val radius=(eraserDiameterDp*density/2).toFloat();canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=(3*density).toFloat();paint.color=Color.WHITE;canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.strokeWidth=density.toFloat();paint.color=0xff22272e.toInt();canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.style=Paint.Style.FILL}
    }
    private fun drawGuide(c:Canvas,v:CanvasBounds){
        val f=viewport.zoom*density
        val guides=PaperTemplates.guides(paper,v,world,f)
        PaperPainter.draw(c,guides,f)
    }
    override fun onHoverEvent(e:MotionEvent):Boolean {if(preview||!eraseMode)return super.onHoverEvent(e);cursor=if(e.actionMasked==MotionEvent.ACTION_HOVER_EXIT)null else CanvasPoint(e.x.toDouble(),e.y.toDouble());invalidate();return true}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(preview)return false;if(!configured)return true
        if(e.actionMasked==MotionEvent.ACTION_CANCEL||(e.flags and MotionEvent.FLAG_CANCELED)!=0){cancelGesture();panPointer=-1;finishViewport();return true}
        if(embeddedPage&&inputId==-1&&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER)return false
        if(inputId==-1||inputKind!=InkTool.STYLUS)scaleDetector.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){if(inputKind!=InkTool.STYLUS)cancelGesture();panPointer=-1;return true}
        if(e.actionMasked==MotionEvent.ACTION_DOWN){
            if(inputId!=-1)cancelGesture();val type=e.getToolType(0);inputKind=when(type){MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER->InkTool.STYLUS;MotionEvent.TOOL_TYPE_MOUSE->InkTool.MOUSE;else->InkTool.TOUCH}
            if(inputKind==InkTool.TOUCH&&!fingerWrites){panPointer=e.getPointerId(0);panLastX=e.x;panLastY=e.y;return true};if(!allowInput)return true
            val p=viewport.screenToWorld(e.x.toDouble(),e.y.toDouble(),width.toDouble(),height.toDouble(),density)
            if(!world&&(p.x !in 0.0..1000.0||p.y !in 0.0..1414.0))return true
            if(abs(p.x)>BoardLimits.WORLD||abs(p.y)>BoardLimits.WORLD){onNotice("已到达画布数值安全边界，请返回内容区域。");return true}
            parent?.requestDisallowInterceptTouchEvent(true);requestFocus();inputId=e.getPointerId(0);startTime=e.eventTime
            gesturePen=pen;gestureColor=penColor;gestureWidth=penWidth;gestureErase=eraseMode||type==MotionEvent.TOOL_TYPE_ERASER
            gestureWhole=eraserWhole;gestureOnlyHighlighter=eraserHighlighterOnly;gestureRadius=(eraserDiameterDp/(2*viewport.zoom)).toFloat()
            eraseTargets=content.filter{!gestureOnlyHighlighter||it.pen==InkPen.HIGHLIGHTER}.map{it.id}.toSet();cursor=CanvasPoint(e.x.toDouble(),e.y.toDouble())
            raw=ArrayList();val device=e.device;val pressure=device?.getMotionRange(MotionEvent.AXIS_PRESSURE,e.source)
            hasPressure=inputKind==InkTool.STYLUS&&pressure!=null&&pressure.max>pressure.min;pressureMin=pressure?.min?:0f;pressureSpan=(pressure?.range?:1f).coerceAtLeast(.001f)
            hasTilt=inputKind==InkTool.STYLUS&&device?.getMotionRange(MotionEvent.AXIS_TILT,e.source)!=null;hasOrientation=inputKind==InkTool.STYLUS&&device?.getMotionRange(MotionEvent.AXIS_ORIENTATION,e.source)!=null
            onAxes(hasPressure,hasTilt);onGesture(true);if(!gestureErase)live.start(InkBrushes.brush(gesturePen,gestureColor,gestureWidth,hasPressure));append(e,0,-1);postInvalidateOnAnimation();return true
        }
        if(inputId==-1){if(e.actionMasked==MotionEvent.ACTION_MOVE&&panPointer!=-1&&e.pointerCount==1&&!scaleDetector.isInProgress){viewport=viewport.pan((e.x-panLastX).toDouble(),(e.y-panLastY).toDouble(),density);panLastX=e.x;panLastY=e.y;movingViewport=true;transform();invalidate()};if(e.actionMasked==MotionEvent.ACTION_UP){panPointer=-1;finishViewport();performClick()};return true}
        val index=e.findPointerIndex(inputId);if(index<0){cancelGesture();return true}
        when(e.actionMasked){MotionEvent.ACTION_MOVE->{if(scaleDetector.isInProgress&&inputKind!=InkTool.STYLUS){cancelGesture();return true};for(i in 0 until e.historySize)append(e,index,i);append(e,index,-1);postInvalidateOnAnimation()};MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{if(e.getPointerId(e.actionIndex)!=inputId)return true;append(e,index,-1);finishGesture();performClick()}}
        return true
    }
    private fun append(e:MotionEvent,index:Int,history:Int){
        if(raw.size>=InkLimits.MAX_POINTS)return
        fun axis(a:Int)=if(history<0)e.getAxisValue(a,index)else e.getHistoricalAxisValue(a,index,history)
        val x=if(history<0)e.getX(index)else e.getHistoricalX(index,history);val y=if(history<0)e.getY(index)else e.getHistoricalY(index,history);val time=(if(history<0)e.eventTime else e.getHistoricalEventTime(history))-startTime
        if(!x.isFinite()||!y.isFinite()||time<0||time>3_600_000)return
        cursor=CanvasPoint(x.toDouble(),y.toDouble())
        val pressure=if(hasPressure)((axis(MotionEvent.AXIS_PRESSURE)-pressureMin)/pressureSpan).coerceIn(0f,1f)else -1f
        val tilt=if(hasTilt)axis(MotionEvent.AXIS_TILT).coerceIn(0f,Math.PI.toFloat()/2)else -1f
        val orientation=if(hasOrientation)((axis(MotionEvent.AXIS_ORIENTATION)%(2*Math.PI.toFloat()))+2*Math.PI.toFloat())%(2*Math.PI.toFloat())else -1f
        if(!pressure.isFinite()||!tilt.isFinite()||!orientation.isFinite())return
        val w=viewport.screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
        if((world||gestureErase)&&(abs(w.x)>BoardLimits.WORLD||abs(w.y)>BoardLimits.WORLD)){onNotice("边界外采样未接收，请抬笔返回内容区域。");return}
        // An eraser crossing the paper edge keeps its actual world center;
        // clamping it to the paper edge would disagree with the screen cursor.
        // These gesture samples are never persisted as author InkStroke points.
        val freeGesture=world||gestureErase
        val point=InkSample(if(freeGesture)w.x.toFloat()else w.x.toFloat().coerceIn(0f,1000f),if(freeGesture)w.y.toFloat()else w.y.toFloat().coerceIn(0f,1414f),time,pressure,tilt,orientation,freeGesture)
        if(raw.lastOrNull()?.let{it==point||it.elapsedMs>time}==true)return
        try{if(!gestureErase){incremental.clear();InkBrushes.add(incremental,point,inputKind,gesturePen);live.enqueueInputs(incremental,empty)};raw.add(point);if(raw.size==InkLimits.MAX_POINTS)onNotice("达到单笔采样上限，请抬笔提交后继续。")}catch(_:IllegalArgumentException){onNotice("无效设备采样未进入笔迹。")}
    }
    private fun finishGesture(){
        if(inputId==-1)return
        try{if(raw.isNotEmpty()){if(gestureErase)onErase(raw.toList(),gestureRadius,gestureWhole,gestureOnlyHighlighter)else{live.finishInput();live.updateShape();val s=InkStroke(UUID.randomUUID().toString(),gesturePen,gestureColor,gestureWidth,inputKind,raw,world);transient[s.id]=live.toImmutable();try{onStroke(s)}catch(e:Exception){transient.remove(s.id);throw e}}}}
        catch(_:Exception){onNotice("本次操作未接收；已确认内容不变，请先导出副本并检查容量。")}
        finally{inputId=-1;raw.clear();gestureErase=false;onGesture(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    }
    private fun finishViewport(){if(movingViewport){movingViewport=false;onViewport(viewport)}}
    fun cancelGesture(){val active=inputId!=-1;inputId=-1;raw.clear();gestureErase=false;cursor=null;parent?.requestDisallowInterceptTouchEvent(false);if(active)onGesture(false);invalidate()}
    override fun onDetachedFromWindow(){objectPainter.clear();cancelGesture();if(configured&&!preview)onViewport(viewport);super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
