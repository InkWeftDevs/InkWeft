// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
import kotlin.math.min

/** Native AndroidX Ink shape + renderer. No WebView, custom smoothing, or predicted author points. */
class InkCanvasView(context: Context) : View(context) {
    var onStroke: (InkStroke)->Unit = {}
    var onErase: (List<String>)->Unit = {}
    var onGesture: (Boolean)->Unit = {}
    var onNotice: (String)->Unit = {}
    var onAxes: (Boolean,Boolean)->Unit = {_,_->}
    var allowInput = false
    var fingerWrites = false
    var eraseMode = false
    var pen = InkPen.PEN
    var penColor = 0xff24342f.toInt()
    var penWidth = 3f
    private var content = emptyList<InkStroke>()
    private val cached = LinkedHashMap<String,Stroke>()
    private val transient = LinkedHashMap<String,Stroke>()
    private val renderer = CanvasStrokeRenderer.create()
    private val live = InProgressStroke()
    private val incremental = MutableStrokeInputBatch()
    private val empty = MutableStrokeInputBatch()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var inputId = -1
    private var inputKind = InkTool.TOUCH
    private var raw = ArrayList<InkSample>()
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
    private var zoom=1f
    private var panX=0f
    private var panY=0f
    private var factor=1f
    private var left=0f
    private var top=0f
    private var panPointer=-1
    private var panLastX=0f
    private var panLastY=0f
    private val scaleDetector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom=(zoom*detector.scaleFactor).coerceIn(1f,4f);transform();invalidate();return true
        }
    })
    init {
        isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription="手写页。双指缩放；默认仅手写笔落墨，可开启手指书写。未识别笔迹内容。"
        setBackgroundColor(Color.rgb(239,243,241))
    }
    fun showStrokes(strokes: List<InkStroke>) {
        content=strokes
        val ids=strokes.map { it.id }.toSet()
        cached.keys.retainAll(ids)
        for(s in strokes) {
            if(!cached.containsKey(s.id)) cached[s.id]=transient.remove(s.id)?:toInk(s)
        }
        invalidate()
    }
    private fun family(s: InkPen, pressure: Boolean) = when {
        s==InkPen.HIGHLIGHTER -> StockBrushes.highlighter()
        pressure -> StockBrushes.pressurePen()
        else -> StockBrushes.marker()
    }
    private fun brush(p: InkPen,color: Int,width: Float,pressure: Boolean) =
        Brush.createWithColorIntArgb(family(p,pressure),color,width,.1f)
    private fun inputType(tool: InkTool) = when(tool) {
        InkTool.STYLUS -> InputToolType.STYLUS
        InkTool.MOUSE -> InputToolType.MOUSE
        InkTool.TOUCH -> InputToolType.TOUCH
    }
    private fun batchPoint(batch: MutableStrokeInputBatch,point: InkSample,tool: InkTool) {
        batch.add(inputType(tool),point.x,point.y,point.elapsedMs,
            pressure=if(point.pressure<0) StrokeInput.NO_PRESSURE else point.pressure,
            tiltRadians=if(point.tilt<0) StrokeInput.NO_TILT else point.tilt,
            orientationRadians=if(point.orientation<0) StrokeInput.NO_ORIENTATION else point.orientation)
    }
    private fun toInk(s: InkStroke): Stroke {
        val inputs=MutableStrokeInputBatch()
        s.samples.forEach { batchPoint(inputs,it,s.tool) }
        return Stroke(brush(s.pen,s.color,s.width,s.samples.first().pressure>=0),inputs)
    }
    private fun transform() {
        factor=(min(width/InkLimits.WIDTH,height/InkLimits.HEIGHT)*.96f*zoom).coerceAtLeast(.01f)
        panX=panX.coerceIn(-width.toFloat(),width.toFloat());panY=panY.coerceIn(-height.toFloat(),height.toFloat())
        left=(width-InkLimits.WIDTH*factor)/2+panX;top=(height-InkLimits.HEIGHT*factor)/2+panY
        matrix.setScale(factor,factor);matrix.postTranslate(left,top)
    }
    fun fitPage() { cancelGesture();zoom=1f;panX=0f;panY=0f;transform();invalidate() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        if(inputId!=-1)cancelGesture()
        transform()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save();canvas.concat(matrix)
        paint.color=Color.WHITE;canvas.drawRect(0f,0f,InkLimits.WIDTH,InkLimits.HEIGHT,paint)
        canvas.clipRect(0f,0f,InkLimits.WIDTH,InkLimits.HEIGHT)
        // Muted paper guide; no invented OCR or template thumbnail substituted for actual ink.
        paint.color=Color.rgb(226,234,229);paint.strokeWidth=1f
        for(y in 100..1350 step 55)canvas.drawLine(40f,y.toFloat(),960f,y.toFloat(),paint)
        cached.values.forEach { renderer.draw(canvas,it,matrix) }
        transient.values.forEach { renderer.draw(canvas,it,matrix) }
        if(inputId!=-1 && !gestureErase && raw.isNotEmpty()) {
            live.updateShape();renderer.draw(canvas,live,matrix)
        }
        if(inputId!=-1 && gestureErase && raw.isNotEmpty()) {
            paint.color=0x553f7d67;val p=raw.last();canvas.drawCircle(p.x,p.y,14f,paint)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_CANCEL || (event.flags and MotionEvent.FLAG_CANCELED)!=0) {
            cancelGesture();panPointer=-1;return true
        }
        if(inputId==-1 || inputKind!=InkTool.STYLUS) scaleDetector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) {
            if(inputKind!=InkTool.STYLUS)cancelGesture()
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            if(inputId!=-1)cancelGesture()
            val type=event.getToolType(0)
            inputKind=when(type) {
                MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER -> InkTool.STYLUS
                MotionEvent.TOOL_TYPE_MOUSE -> InkTool.MOUSE
                else -> InkTool.TOUCH
            }
            if(inputKind==InkTool.TOUCH && !fingerWrites) {
                panPointer=event.getPointerId(0);panLastX=event.x;panLastY=event.y;return true
            }
            if(!allowInput)return true
            val x=(event.x-left)/factor;val y=(event.y-top)/factor
            if(x !in 0f..InkLimits.WIDTH || y !in 0f..InkLimits.HEIGHT)return true
            parent?.requestDisallowInterceptTouchEvent(true)
            requestFocus();inputId=event.getPointerId(0);startTime=event.eventTime
            gesturePen=pen;gestureColor=penColor;gestureWidth=penWidth
            gestureErase=eraseMode || type==MotionEvent.TOOL_TYPE_ERASER
            raw=ArrayList();val device=event.device
            val pressure=device?.getMotionRange(MotionEvent.AXIS_PRESSURE,event.source)
            hasPressure=inputKind==InkTool.STYLUS && pressure!=null && pressure.max>pressure.min
            pressureMin=pressure?.min?:0f;pressureSpan=(pressure?.range?:1f).coerceAtLeast(.001f)
            hasTilt=inputKind==InkTool.STYLUS && device?.getMotionRange(MotionEvent.AXIS_TILT,event.source)!=null
            hasOrientation=inputKind==InkTool.STYLUS && device?.getMotionRange(MotionEvent.AXIS_ORIENTATION,event.source)!=null
            onAxes(hasPressure,hasTilt);onGesture(true)
            if(!gestureErase)live.start(brush(gesturePen,gestureColor,gestureWidth,hasPressure))
            append(event,0,-1);postInvalidateOnAnimation();return true
        }
        if(inputId==-1) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE && panPointer!=-1 && event.pointerCount==1 && !scaleDetector.isInProgress) {
                panX+=event.x-panLastX;panY+=event.y-panLastY;panLastX=event.x;panLastY=event.y;transform();invalidate()
            }
            if(event.actionMasked==MotionEvent.ACTION_UP)panPointer=-1
            return true
        }
        val index=event.findPointerIndex(inputId)
        if(index<0){cancelGesture();return true}
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if(scaleDetector.isInProgress && inputKind!=InkTool.STYLUS){cancelGesture();return true}
                for(i in 0 until event.historySize)append(event,index,i)
                append(event,index,-1);postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                if(event.getPointerId(event.actionIndex)!=inputId)return true
                append(event,index,-1);finishGesture();performClick()
            }
        }
        return true
    }
    private fun append(e: MotionEvent,index: Int,history: Int) {
        if(raw.size>=InkLimits.MAX_POINTS)return
        fun axis(axis: Int)=if(history<0)e.getAxisValue(axis,index) else e.getHistoricalAxisValue(axis,index,history)
        val x=if(history<0)e.getX(index) else e.getHistoricalX(index,history)
        val y=if(history<0)e.getY(index) else e.getHistoricalY(index,history)
        val time=(if(history<0)e.eventTime else e.getHistoricalEventTime(history))-startTime
        if(!x.isFinite()||!y.isFinite()||time<0||time>3_600_000)return
        val p=if(hasPressure)((axis(MotionEvent.AXIS_PRESSURE)-pressureMin)/pressureSpan).coerceIn(0f,1f) else -1f
        val tilt=if(hasTilt)axis(MotionEvent.AXIS_TILT).coerceIn(0f,Math.PI.toFloat()/2) else -1f
        val orientation=if(hasOrientation)((axis(MotionEvent.AXIS_ORIENTATION)%(2*Math.PI.toFloat()))+2*Math.PI.toFloat())%(2*Math.PI.toFloat()) else -1f
        if(!p.isFinite()||!tilt.isFinite()||!orientation.isFinite())return
        val point=InkSample(((x-left)/factor).coerceIn(0f,InkLimits.WIDTH),((y-top)/factor).coerceIn(0f,InkLimits.HEIGHT),time,p,tilt,orientation)
        if(raw.lastOrNull()?.let { it==point || it.elapsedMs>time }==true)return
        try {
            if(!gestureErase) {
                incremental.clear();batchPoint(incremental,point,inputKind)
                live.enqueueInputs(incremental,empty)
            }
            raw.add(point)
            if(raw.size==InkLimits.MAX_POINTS)onNotice("达到单笔采样上限，请抬笔继续；当前笔段已保留。")
        } catch (_: IllegalArgumentException) { onNotice("输入设备返回了无效采样，该点未进入笔迹；请查看设备兼容性。") }
    }
    private fun finishGesture() {
        if(inputId==-1)return
        try {
            if(raw.isNotEmpty()) {
                if(gestureErase) {
                    val ids=content.filter { InkHitTest.hits(it,raw) }.map { it.id }
                    onErase(ids)
                } else {
                    live.finishInput();live.updateShape()
                    val stroke=InkStroke(UUID.randomUUID().toString(),gesturePen,gestureColor,gestureWidth,inputKind,raw)
                    transient[stroke.id]=live.toImmutable()
                    try { onStroke(stroke) } catch (e: Exception) { transient.remove(stroke.id);throw e }
                }
            }
        } catch (_: Exception) { onNotice("本次操作未接收，已确认内容没有改变。请导出页面后检查空间与限制。")
        } finally { inputId=-1;raw.clear();onGesture(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate() }
    }
    fun cancelGesture() {
        val wasActive=inputId!=-1
        inputId=-1;raw.clear();parent?.requestDisallowInterceptTouchEvent(false)
        if(wasActive)onGesture(false)
        invalidate()
    }
    override fun onDetachedFromWindow() { cancelGesture();super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick();return true }
}
