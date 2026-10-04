// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.graphics.toArgb
import org.inkweft.app.ui.designsystem.InkTheme

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.*
import org.inkweft.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/** A bounded viewport. Partial erase clips ink, never paints the paper colour. */
class InkCanvasView(context:Context):View(context){
    internal var authorSession:ShadowAuthorSession?=null
        set(value){check(!isAttachedToWindow){"Bind author session before attaching canvas"};field=value}
    var onCheckpoint:(InkStroke)->Unit={}
    var onCheckpointCancel:(String)->Unit={}
    private var checkpointAt=0L
    var onStroke:(InkStroke)->Unit={}
    var finishStroke:(InkStroke)->InkStroke={it}
    var onViewportGesture:(Boolean)->Unit={}
    internal val documentContentReady get()=documentId==null||documentKnownAbsent||(documentTile!=null&&!documentError)
    internal var sourceInteractionEpoch=0L
        private set
    internal val sourceContentReady get()=documentId==null||documentKnownAbsent||
        (documentTile!=null&&!documentError&&completedDocumentGeneration==documentGeneration)
    internal val sourceFrameFailed get()=failedDocumentGeneration==documentGeneration
    internal fun hasDrawnSourceFrame(page:String,expectedViewport:CanvasViewport,expectedWidth:Int,expectedHeight:Int,expectedDensity:Double):Boolean=
        documentId==page&&!documentError&&drawnDocumentGeneration==documentGeneration&&
            drawnViewport==expectedViewport&&drawnWidth==expectedWidth&&drawnHeight==expectedHeight&&drawnDensity==expectedDensity&&
            viewport==expectedViewport&&width==expectedWidth&&height==expectedHeight&&density==expectedDensity
    internal val inputReady get()=allowInput&&documentContentReady
    var seamWriting=false
    var onLiveSamples:(List<InkSample>)->Unit={}
    var seamDraft:InkStroke? = null
        set(value){field=value;invalidate()}
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
    var eraserTapeOnly=false
    var eraserDiameterDp=28f
    var embeddedPage=false
    var preview=false
    var pen=InkPen.PEN
    var penColor=0xff24342f.toInt()
    var penWidth=3f
    var brushRecipe=BrushRecipe()
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
    private var suppressedStrokeIds=emptySet<String>()
    private val documentPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val documentRect=RectF()
    private var objectDraft:PageObject?=null
    private var selectionObjects=emptySet<String>()
    private var objectDx=0f;private var objectDy=0f
    fun previewSelectionObjects(ids:Set<String>,dx:Float=0f,dy:Float=0f){selectionObjects=ids;objectDx=dx;objectDy=dy;invalidate()}
    private val imageRendering=ImageRendering(context,{postInvalidateOnAnimation()},{onNotice("图片原件暂时无法显示，已保留预览；请退出后重开。")})
    private val objectPainter=PageObjectPainter().apply{originalImage=imageRendering::frame}
    private var snapshotImageSources:List<ImageSource>?=null
    /** A file snapshot is a closed set: never fall through to the application's database. */
    fun showImageSources(sources:List<ImageSource>){
        if(snapshotImageSources==sources)return
        imageRendering.clear();snapshotImageSources=sources.toList();drawnViewport=null;invalidate()
    }
    internal val imageFramesPending get()=imageRendering.pending
    private fun requestImages(drawn:List<PageObject>,visible:CanvasBounds){
        val page=documentId;val sources=snapshotImageSources
        val repo=authorSession?.objects?:(context.applicationContext as? InkWeftApplication)?.pageObjects
        imageRendering.request(drawn,visible,viewport.zoom*density){o->
            if(sources!=null)sources.firstOrNull{it.sha256==o.imageSource}
            else if(page!=null)repo?.originals(page,listOf(o))?.singleOrNull() else null
        }
    }
    private var mapSceneJob:Job?=null
    private var mapBook:String?=null
    private fun observeMapScenes(){
        val book=objects.firstOrNull{it.mapEmbed?.policy==MapEmbedPolicy.LIVE}?.mapEmbed?.target?.notebookId
        if(book==mapBook&&mapSceneJob?.isActive==true)return
        mapSceneJob?.cancel();mapSceneJob=null;mapBook=book
        if(book==null){objectPainter.mapScenes=emptyMap();return}
        if(!isAttachedToWindow)return
        val maps=authorSession?.maps?:(context.applicationContext as? InkWeftApplication)?.mapGraphs?:return
        mapSceneJob=CoroutineScope(Dispatchers.Main.immediate).launch{
            maps.observe(book).flowOn(Dispatchers.IO).catch{if(it is CancellationException)throw it;emit(listOf(MapScene(MapRef(book),"",emptyList(),"",false)))}.collect{scenes->objectPainter.mapScenes=scenes.associateBy{it.ref};invalidate()}
        }
    }
    fun showObjects(next:List<PageObject>){if(objects==next)return;objects=next;imageRendering.retain(next);drawnViewport=null;observeMapScenes();restoreAppearance();suppressedStrokeIds=next.flatMap{it.sourceStrokeIds}.toSet();if(preview&&world&&width>0&&height>0)fitContent(false);invalidate()}
    fun previewObject(value:PageObject?){objectDraft=value;invalidate()}
    private var appearanceObjects=emptyList<PageObject>()
    private fun restoreAppearance(){val sources=content.associateBy{it.id};appearanceObjects=objects.map{BeautyAppearance.restore(it,sources)}}
    private fun drawnObjects()=appearanceObjects.filterNot{gestureErase&&gestureOnlyTape&&ObjectGeometry.intersectsTape(it,raw,gestureRadius)}.map{if(it.id==objectDraft?.id)BeautyAppearance.restore(objectDraft!!,content.associateBy{it.id}) else if(it.id in selectionObjects)it.copy(x=it.x+objectDx,y=it.y+objectDy)else it}
    private var documentId:String?=null
    private var documentTile:DocumentTile?=null
    private var documentJob:Job?=null
    private var documentRequest:String?=null
    private var documentKnownAbsent=false
    private var documentError=false
    private var documentGeneration=0L
    private var completedDocumentGeneration=-1L
    private var failedDocumentGeneration=-1L
    private var drawnDocumentGeneration=-1L
    private var drawnViewport:CanvasViewport?=null
    private var drawnWidth=0;private var drawnHeight=0;private var drawnDensity=0.0
    private var capturingExcerpt=false
    private val observedVisiblePixels=Rect()
    // Continuous sheets grow with zoom, but their raster must only cover the window.
    // Parent scrolling can move an unchanged View/display list without calling onSizeChanged.
    private val visibleAreaListener=ViewTreeObserver.OnPreDrawListener {
        val pixels=visiblePixels()
        if(pixels!=observedVisiblePixels){observedVisiblePixels.set(pixels);requestDocument();invalidate()}
        true
    }
    private fun visiblePixels():Rect {
        val pixels=Rect(0,0,width,height)
        if(isAttachedToWindow&&!getLocalVisibleRect(pixels))pixels.setEmpty()
        if(!pixels.intersect(0,0,width,height))pixels.setEmpty()
        return pixels
    }
    private fun rasterViewport(pixels:Rect):CanvasViewport {
        val center=viewport.screenToWorld(pixels.exactCenterX().toDouble(),pixels.exactCenterY().toDouble(),width.toDouble(),height.toDouble(),density)
        return CanvasViewport.safe(center.x,center.y,viewport.zoom)
    }
    private fun releaseDocumentTile(){documentTile?.let{tile->documentId?.let{RenderResources.release(tile.bitmap,it)}};documentTile=null;drawnViewport=null}
    fun showDocument(id:String?){if(documentId==id)return;imageRendering.clear();snapshotImageSources=null;releaseDocumentTile();documentJob?.cancel();documentId=id;documentRequest=null;documentKnownAbsent=false;documentError=false;requestDocument();invalidate()}
    private fun requestDocument(){
        val id=documentId?:return;if(documentKnownAbsent||width<=0||height<=0||!isAttachedToWindow)return
        val area=visiblePixels();if(area.isEmpty)return
        val visible=rasterViewport(area).visible(area.width().toDouble(),area.height().toDouble(),density)
        val left=visible.left.coerceIn(0.0,999.0);val top=visible.top.coerceIn(0.0,1413.0)
        val rect=CanvasBounds(left,top,visible.right.coerceIn(left+1,1000.0),visible.bottom.coerceIn(top+1,1414.0))
        val pixels=if(preview)256 else ceil(max(rect.right-rect.left,rect.bottom-rect.top)*viewport.zoom*density).toInt().coerceAtLeast(1)
        val request="$id:$rect:$pixels";if(documentRequest==request)return;documentRequest=request;documentJob?.cancel()
        val generation=++documentGeneration
        documentJob=CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(80)
            try{val tile=(authorSession?.rendering?:(context.applicationContext as InkWeftApplication).documentRendering).render(id,rect,pixels)
                ensureActive();if(documentRequest==request&&documentGeneration==generation){releaseDocumentTile();documentTile=tile;documentKnownAbsent=tile==null;documentError=false;completedDocumentGeneration=generation;invalidate()}
            }catch(c:CancellationException){throw c}catch(_:RenderBudgetBusy){delay(500);if(documentRequest==request){documentRequest=null;documentJob=null;requestDocument()}}
            catch(_:Exception){if(documentRequest==request&&documentGeneration==generation){documentError=true;failedDocumentGeneration=generation;invalidate();onNotice("文档页面读取失败，请离开后重新打开；原文件保留。")}}
        }
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();viewTreeObserver.addOnPreDrawListener(visibleAreaListener);observeMapScenes();documentRequest=null;requestDocument()}

    private var content=emptyList<InkStroke>()
    internal val displayedStrokeCount get()=content.size
    private val bounds=mutableMapOf<String,CanvasBounds>()
    private val meshes=object:LinkedHashMap<String,Stroke>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Stroke>?)=size>128}
    private val maskPaths=object:LinkedHashMap<InkCut,Path>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<InkCut,Path>?)=size>128}
    private val transient=LinkedHashMap<String,Stroke>()
    private val transientPencils=LinkedHashMap<String,InkStroke>()
    private val renderer=InkBrushes.renderer()
    private val pageRaster=InkPageRaster()
    private val asyncRaster=AsyncInkRaster({postInvalidateOnAnimation()},{onNotice("笔迹显示未完成，请退出后重开；原笔迹已保留。")})
    internal val rasterPending get()=asyncRaster.pending
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
    private var gestureAppearance=StrokeAppearance()
    private var gestureId=UUID.randomUUID().toString()
    fun liveStroke(samples:List<InkSample>,dy:Float=0f)=InkStroke(gestureId,gesturePen,gestureColor,gestureWidth,inputKind,samples.map{it.copy(world=true)},true,appearance=gestureAppearance.translated(0f,dy))
    fun capturedStroke()=liveStroke(raw.toList())
    private var gestureErase=false
    private var gestureWhole=false
    private var gestureOnlyHighlighter=false
    private var gestureOnlyTape=false
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
    private var multiPanIds=emptySet<Int>()
    private var multiPanX=0f
    private var multiPanY=0f
    private var movingViewport=false
    private val density get()=resources.displayMetrics.density.toDouble()
    private val scaleDetector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean{
            if(inputId!=-1&&inputKind==InkTool.STYLUS)return false
            val focusX=if(multiPanIds.isEmpty())detector.focusX else multiPanX
            val focusY=if(multiPanIds.isEmpty())detector.focusY else multiPanY
            viewport=viewport.zoomAt(detector.scaleFactor.toDouble(),focusX.toDouble(),focusY.toDouble(),width.toDouble(),height.toDouble(),density)
            movingViewport=true;onViewportGesture(true);transform();invalidate();return true
        }
    })
    init{isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="手写画布，双指移动和缩放。橡皮圆圈为实际屏幕擦除范围。"}
    fun configure(board:Boolean,style:PaperStyle,saved:CanvasViewport?){
        paper=style
        if(preview&&configured&&world!=board){configured=false;restored=false;meshes.clear()}
        if(!configured){world=board;configured=true;restored=saved!=null;viewport=saved?:if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport();if(width>0&&height>0&&!restored)initialFit()}
        check(world==board){"A canvas identity cannot change coordinate space"};transform();invalidate()
    }
    fun snapshotViewport()=viewport
    fun showStrokes(strokes:List<InkStroke>){
        if(content===strokes)return
        val removed=content.map{it.id}.toSet()-strokes.map{it.id}.toSet();transient.keys.removeAll(removed);transientPencils.keys.removeAll(removed)
        content=strokes;drawnViewport=null;restoreAppearance();val ids=strokes.map{it.id}.toSet();meshes.keys.retainAll(ids);bounds.keys.retainAll(ids)
        for(s in strokes){if(!bounds.containsKey(s.id))bounds[s.id]=s.bounds();transient[s.id]?.let{meshes[s.id]=it}}
        if(preview&&width>0&&height>0)if(world)fitContent(false)else fitPage(false)
        invalidate()
    }
    private fun toInk(s:InkStroke):Stroke=InkBrushes.stroke(s)
    private fun transform(){if(!world&&!preview&&!embeddedPage)viewport=viewport.constrainedToPaper(width/density,height/density);val f=(viewport.zoom*density).toFloat();matrix.setScale(f,f);matrix.postTranslate((width/2-viewport.centerX*f).toFloat(),(height/2-viewport.centerY*f).toFloat());onScale(viewport.zoom);requestDocument()}
    private fun initialFit(){viewport=if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport.pageWidth(width/density,height/density);if(preview||embeddedPage)fitPage(false);transform()}
    fun fitPage(publish:Boolean=true){cancelGesture();viewport=if(embeddedPage)CanvasViewport(500.0,707.0,(width/density/1000.0).coerceIn(.02,8.0))else CanvasViewport.fit(CanvasBounds(0.0,0.0,1000.0,1414.0),width/density,height/density);transform();invalidate();if(publish)onViewport(viewport)}
    fun fitWidth(){cancelGesture();viewport=CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    fun origin(){cancelGesture();viewport=if(world)CanvasViewport(0.0,0.0,.8)else CanvasViewport.pageWidth(width/density,height/density);transform();invalidate();onViewport(viewport)}
    internal var previewPadding=40.0
    fun fitContent(publish:Boolean=true){cancelGesture();val allBounds=bounds.values+objects.map{it.bounds()};if(allBounds.isEmpty()){if(world)viewport=CanvasViewport(0.0,0.0,.8)else fitPage(false)}else viewport=CanvasViewport.fit(allBounds.reduce{a,b->a.union(b)}.padded(if(preview)previewPadding else 40.0),width/density,height/density);transform();invalidate();if(publish)onViewport(viewport)}
    fun zoomBy(ratio:Double){cancelGesture();viewport=viewport.zoomAt(ratio,width/2.0,height/2.0,width.toDouble(),height.toDouble(),density);transform();invalidate();onViewport(viewport)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){cancelGesture();if(configured&&oldw==0&&!restored)initialFit();if(embeddedPage&&configured)fitPage(false);if(preview)if(world)fitContent(false)else fitPage(false);transform()}
    override fun draw(c:Canvas){val save=c.save();try{c.clipRect(0,0,width,height);super.draw(c)}finally{c.restoreToCount(save)}}
    internal fun cutPath(cut:InkCut):Path=maskPaths[cut]?:VisibleInkGeometry.cutPath(cut).also{maskPaths[cut]=it}
    private fun sweptPath(points:List<EraserPoint>,radius:Float)=VisibleInkGeometry.sweptPath(points,radius)
    var onObjectTap:(String)->Unit={}
    internal fun imageAt(x:Float,y:Float):String? {
        val p=viewport.screenToWorld(x.toDouble(),y.toDouble(),width.toDouble(),height.toDouble(),density)
        val candidates=objects.asReversed().filter{!it.hidden&&it.kind in listOf(PageObjectKind.IMAGE,PageObjectKind.MAP,PageObjectKind.SHAPE,PageObjectKind.TAPE)}
        return (candidates.filter{it.kind==PageObjectKind.TAPE}+candidates.filter{it.kind!=PageObjectKind.TAPE}).firstOrNull{ObjectGeometry.hit(it,p.x.toFloat(),p.y.toFloat())}?.id
    }
    private var tapImage:String?=null
    private var tapX=0f;private var tapY=0f
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);if(!configured)return
        canvas.drawColor(if(world||preview||embeddedPage)Color.WHITE else InkTheme.Workspace.toArgb())
        val visible=viewport.visible(width.toDouble(),height.toDouble(),density);val save=canvas.save();canvas.concat(matrix)
        if(!world){paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawRect(0f,0f,1000f,1414f,paint);if(!embeddedPage)canvas.clipRect(0f,0f,1000f,1414f)}
        if(documentId==null||documentKnownAbsent)drawGuide(canvas,visible)
        documentTile?.let{tile->val b=tile.bounds;documentRect.set(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat());canvas.drawBitmap(tile.bitmap,null,documentRect,documentPaint)}
        if(documentId!=null&&!documentKnownAbsent&&(documentTile==null||documentError)){paint.color=Color.DKGRAY;paint.textSize=24f;canvas.drawText(if(documentError)"文档载入失败"else"正在载入文档…",80f,100f,paint)}
        val beautyMask=if(gestureErase&&!gestureOnlyTape&&!gestureOnlyHighlighter&&raw.isNotEmpty())sweptPath(raw.map{EraserPoint(it.x,it.y)},gestureRadius)else null
        val drawn=drawnObjects()
        val area=visiblePixels()
        val imageVisible=if(area.isEmpty)CanvasBounds(0.0,0.0,0.0,0.0)else rasterViewport(area).visible(area.width().toDouble(),area.height().toDouble(),density)
        if(isAttachedToWindow&&!capturingExcerpt)requestImages(drawn,imageVisible)
        objectPainter.draw(canvas,drawn,false,visible,beautyMask,gestureWhole)
        val activeMask=if(inputId!=-1&&gestureErase&&!gestureOnlyTape&&!gestureWhole&&raw.isNotEmpty())sweptPath(raw.map{EraserPoint(it.x,it.y)},gestureRadius)else null
        val separateErasing=activeMask!=null&&gestureOnlyHighlighter
        val movingOrErasing=selectedIds + if(separateErasing)eraseTargets else emptySet()
        // Keep content identity stable across zoom; the worker culls offscreen strokes.
        val stable=content.filter{it.id !in suppressedStrokeIds && it.id !in movingOrErasing}
        // The raster is in viewport pixels; live input and object layers stay independent.
        canvas.restoreToCount(save)
        val staticSave=canvas.save()
        if(activeMask!=null&&!gestureOnlyHighlighter){val screenMask=Path(activeMask);screenMask.transform(matrix);canvas.clipOutPath(screenMask)}
        if(isAttachedToWindow){
            val area=visiblePixels()
            if(!area.isEmpty){
                canvas.translate(area.left.toFloat(),area.top.toFloat())
                asyncRaster.draw(canvas,area.width(),area.height(),rasterViewport(area),density,world,embeddedPage,stable)
            }
        }else pageRaster.draw(canvas,width,height,listOf(viewport,world),stable){c,s->
            val n=c.save();c.concat(matrix);if(!world&&!embeddedPage)c.clipRect(0f,0f,1000f,1414f)
            drawSavedStroke(c,s);c.restoreToCount(n)
        }
        canvas.restoreToCount(staticSave)
        val inkSave=canvas.save();canvas.concat(matrix)
        if(!world&&!embeddedPage)canvas.clipRect(0f,0f,1000f,1414f)
        for(s in content){
            if(s.id !in movingOrErasing || s.id in suppressedStrokeIds)continue
            val clipped=canvas.save();if(s.id in selectedIds)canvas.translate(selectedDx,selectedDy)
            if(activeMask!=null&&s.id in eraseTargets)canvas.clipOutPath(activeMask)
            drawSavedStroke(canvas,s);canvas.restoreToCount(clipped)
        }
        transient.keys.removeAll{it in suppressedStrokeIds||asyncRaster.contains(it)}
        transientPencils.keys.removeAll{it in suppressedStrokeIds||asyncRaster.contains(it)}
        transient.values.forEach{renderer.draw(canvas,it,matrix)}
        transientPencils.values.forEach{PencilRenderer.draw(canvas,it)}
        if(inputId!=-1&&!gestureErase&&raw.isNotEmpty()){if(gesturePen==InkPen.PENCIL)PencilRenderer.draw(canvas,liveStroke(raw))else{live.updateShape();renderer.draw(canvas,live,matrix)}}
        seamDraft?.let{if(it.pen==InkPen.PENCIL)PencilRenderer.draw(canvas,it)else renderer.draw(canvas,InkBrushes.stroke(it),matrix)}
        objectPainter.draw(canvas,drawn,true,visible)
        canvas.restoreToCount(inkSave)
        if(asyncRaster.pending&&!preview&&inputId==-1&&content.isNotEmpty()){paint.color=Color.GRAY;paint.textSize=(12*density).toFloat();paint.style=Paint.Style.FILL;canvas.drawText("正在呈现笔迹…",(16*density).toFloat(),(height-18*density).toFloat(),paint)}
        // Draw cursor in screen space, outside the paper clip. Its diameter is
        // identical to the preview and does not vary with zoom or pen pressure.
        if((eraseMode||gestureErase)&&cursor!=null&&!preview){val p=checkNotNull(cursor);paint.style=Paint.Style.FILL;paint.color=0x183f7d67;val radius=(eraserDiameterDp*density/2).toFloat();canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=(3*density).toFloat();paint.color=Color.WHITE;canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.strokeWidth=density.toFloat();paint.color=0xff22272e.toInt();canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),radius,paint);paint.style=Paint.Style.FILL}
        // Only the current normal window frame may release a pending source decoration.
        if(!capturingExcerpt&&isAttachedToWindow&&(!isHardwareAccelerated||canvas.isHardwareAccelerated)&&!asyncRaster.pending&&sourceContentReady&&!imageFramesPending){
            drawnDocumentGeneration=documentGeneration;drawnViewport=viewport;drawnWidth=width;drawnHeight=height;drawnDensity=density
        }
    }
    /** Capture only the rendered paper, never toolbars or selection decorations. */
    internal fun excerptPreview(region:CanvasBounds):ByteArray {
        check(!rasterPending&&!imageFramesPending&&(documentId==null||documentKnownAbsent||documentTile!=null&&!documentError)){"页面仍在呈现，请稍后重试"}
        val a=viewport.worldToScreen(region.left,region.top,width.toDouble(),height.toDouble(),density)
        val b=viewport.worldToScreen(region.right,region.bottom,width.toDouble(),height.toDouble(),density)
        val area=visiblePixels()
        check(!area.isEmpty&&a.x>=area.left-1&&a.y>=area.top-1&&b.x<=area.right+1&&b.y<=area.bottom+1){"请将摘录区域完整移到屏幕内"}
        check(max(b.x-a.x,b.y-a.y)/min(b.x-a.x,b.y-a.y)<=160){"选区过窄，请扩大一些再摘录"}
        val scale=min(1.0,800.0/max(b.x-a.x,b.y-a.y))
        val bitmap=Bitmap.createBitmap(max(1,((b.x-a.x)*scale).toInt()),max(1,((b.y-a.y)*scale).toInt()),Bitmap.Config.ARGB_8888)
        try{
            val canvas=Canvas(bitmap);canvas.scale(scale.toFloat(),scale.toFloat());canvas.translate(-a.x.toFloat(),-a.y.toFloat())
            capturingExcerpt=true
            try{draw(canvas)}finally{capturingExcerpt=false}
            for(quality in listOf(90,75,55,35)){
                val stream=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,stream)
                val bytes=stream.toByteArray();if(bytes.size<=240_000)return bytes
            }
            error("摘录区域过于复杂，请缩小范围")
        }finally{bitmap.recycle()}
    }
    private fun drawSavedStroke(canvas:Canvas,s:InkStroke){
        val n=canvas.save();s.cuts.forEach{canvas.clipOutPath(cutPath(it))}
        if(s.pen==InkPen.PENCIL)PencilRenderer.draw(canvas,s)
        else {val mesh=meshes[s.id]?:toInk(s).also{meshes[s.id]=it};renderer.draw(canvas,mesh,matrix)}
        canvas.restoreToCount(n)
    }
    private fun drawGuide(c:Canvas,v:CanvasBounds){
        val f=viewport.zoom*density
        val guides=PaperTemplates.guides(paper,v,world,f)
        PaperPainter.draw(c,guides,f)
    }
    override fun onHoverEvent(e:MotionEvent):Boolean {if(preview||!eraseMode)return super.onHoverEvent(e);cursor=if(e.actionMasked==MotionEvent.ACTION_HOVER_EXIT)null else CanvasPoint(e.x.toDouble(),e.y.toDouble());invalidate();return true}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        BackgroundBudget.lastInput=android.os.SystemClock.elapsedRealtime()
        if(preview)return false;if(!configured)return true
        if(e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_POINTER_DOWN)sourceInteractionEpoch++
        if(e.actionMasked==MotionEvent.ACTION_CANCEL||(e.flags and MotionEvent.FLAG_CANCELED)!=0){tapImage=null;cancelGesture();panPointer=-1;multiPanIds=emptySet();finishViewport();return true}
        if(embeddedPage&&!fingerWrites&&inputId==-1&&e.actionMasked!=MotionEvent.ACTION_POINTER_DOWN&&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER)return false
        // Embedded sheets share one parent viewport; scaling a child would break the seam.
        if(!embeddedPage&&(inputId==-1||inputKind!=InkTool.STYLUS))scaleDetector.onTouchEvent(e)
        val downIndex=if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN)e.actionIndex else 0
        val stylusDown=e.actionMasked==MotionEvent.ACTION_POINTER_DOWN&&(e.getToolType(downIndex)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(downIndex)==MotionEvent.TOOL_TYPE_ERASER)
        if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN&&!stylusDown){
            tapImage=null
            if(inputId==-1||inputKind!=InkTool.STYLUS){cancelGesture();if(!embeddedPage)trackMultiPan(e,false)}
            panPointer=-1;return true
        }
        if(e.actionMasked==MotionEvent.ACTION_DOWN||stylusDown){
            multiPanIds=emptySet()
            if(stylusDown&&inputId!=-1&&inputKind==InkTool.STYLUS)return true
            if(inputId!=-1)cancelGesture();val type=e.getToolType(downIndex);inputKind=when(type){MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER->InkTool.STYLUS;MotionEvent.TOOL_TYPE_MOUSE->InkTool.MOUSE;else->InkTool.TOUCH}
            if(inputKind==InkTool.TOUCH&&!fingerWrites){tapImage=if(allowInput&&!eraseMode)imageAt(e.x,e.y)else null;tapX=e.x;tapY=e.y;panPointer=e.getPointerId(0);panLastX=e.x;panLastY=e.y;return true};if(!allowInput||(documentId!=null&&!documentKnownAbsent&&(documentTile==null||documentError)))return true
            val p=viewport.screenToWorld(e.getX(downIndex).toDouble(),e.getY(downIndex).toDouble(),width.toDouble(),height.toDouble(),density)
            if(!world&&(p.x !in 0.0..1000.0||p.y !in 0.0..1414.0))return true
            if(abs(p.x)>BoardLimits.WORLD||abs(p.y)>BoardLimits.WORLD){onNotice("已到达画布数值安全边界，请返回内容区域。");return true}
            tapImage=if(!eraseMode)imageAt(e.getX(downIndex),e.getY(downIndex))else null
            parent?.requestDisallowInterceptTouchEvent(true);requestFocus();inputId=e.getPointerId(downIndex);startTime=e.eventTime
            gestureId=UUID.randomUUID().toString();gestureAppearance=StrokeAppearance(brushRecipe,UUID.randomUUID().leastSignificantBits,p.x.toFloat(),p.y.toFloat())
            gesturePen=pen;gestureColor=penColor;gestureWidth=penWidth;gestureErase=eraseMode||type==MotionEvent.TOOL_TYPE_ERASER
            gestureOnlyTape=eraserTapeOnly;gestureWhole=eraserWhole||gestureOnlyTape;gestureOnlyHighlighter=eraserHighlighterOnly&&!gestureOnlyTape;gestureRadius=(eraserDiameterDp/(2*viewport.zoom)).toFloat()
            eraseTargets=content.filter{it.id !in suppressedStrokeIds&&(!gestureOnlyHighlighter||it.pen==InkPen.HIGHLIGHTER)}.map{it.id}.toSet();cursor=CanvasPoint(e.x.toDouble(),e.y.toDouble())
            raw=ArrayList();checkpointAt=0;val device=e.device;val pressure=device?.getMotionRange(MotionEvent.AXIS_PRESSURE,e.source)
            hasPressure=inputKind==InkTool.STYLUS&&pressure!=null&&pressure.max>pressure.min;pressureMin=pressure?.min?:0f;pressureSpan=(pressure?.range?:1f).coerceAtLeast(.001f)
            hasTilt=inputKind==InkTool.STYLUS&&device?.getMotionRange(MotionEvent.AXIS_TILT,e.source)!=null;hasOrientation=inputKind==InkTool.STYLUS&&device?.getMotionRange(MotionEvent.AXIS_ORIENTATION,e.source)!=null
            onAxes(hasPressure,hasTilt);BackgroundBudget.input(this,true);onGesture(true);if(!gestureErase&&gesturePen!=InkPen.PENCIL)live.start(InkBrushes.brush(gesturePen,gestureColor,gestureWidth,hasPressure,gestureAppearance));append(e,downIndex,-1);if(seamWriting&&!gestureErase)onLiveSamples(raw);postInvalidateOnAnimation();return true
        }
        if(inputId==-1){
            if(!embeddedPage&&e.pointerCount>=2){trackMultiPan(e,e.actionMasked==MotionEvent.ACTION_MOVE);return true}
            if(e.actionMasked==MotionEvent.ACTION_MOVE&&panPointer!=-1&&e.pointerCount==1&&!scaleDetector.isInProgress){if(hypot(e.x-tapX,e.y-tapY)>android.view.ViewConfiguration.get(context).scaledTouchSlop)tapImage=null;viewport=viewport.pan((e.x-panLastX).toDouble(),(e.y-panLastY).toDouble(),density);if(kotlin.math.abs(e.y-panLastY)>1f)onViewportGesture(false);panLastX=e.x;panLastY=e.y;movingViewport=true;transform();invalidate()}
            if(e.actionMasked==MotionEvent.ACTION_UP){val hit=tapImage;tapImage=null;panPointer=-1;multiPanIds=emptySet();finishViewport();if(hit!=null)onObjectTap(hit);performClick()};return true
        }
        val index=e.findPointerIndex(inputId);if(index<0){cancelGesture();return true}
        when(e.actionMasked){MotionEvent.ACTION_MOVE->{if(scaleDetector.isInProgress&&inputKind!=InkTool.STYLUS){cancelGesture();return true};for(i in 0 until e.historySize)append(e,index,i);append(e,index,-1);if(seamWriting&&!gestureErase)onLiveSamples(raw);postInvalidateOnAnimation()};MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{if(e.getPointerId(e.actionIndex)!=inputId)return true;append(e,index,-1);finishGesture();performClick()}}
        return true
    }
    private fun trackMultiPan(e:MotionEvent,move:Boolean){
        val fingers=(0 until e.pointerCount).filter{e.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER&&!(e.actionMasked==MotionEvent.ACTION_POINTER_UP&&it==e.actionIndex)}
        if(fingers.size<2){multiPanIds=emptySet();return}
        val ids=fingers.map{e.getPointerId(it)}.toSet()
        val x=fingers.sumOf{e.getX(it).toDouble()}.toFloat()/fingers.size
        val y=fingers.sumOf{e.getY(it).toDouble()}.toFloat()/fingers.size
        if(move&&ids==multiPanIds){
            viewport=viewport.pan((x-multiPanX).toDouble(),(y-multiPanY).toDouble(),density)
            movingViewport=true;onViewportGesture(scaleDetector.isInProgress);transform();invalidate()
        }
        multiPanIds=ids;multiPanX=x;multiPanY=y
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
        val freeGesture=world||gestureErase||seamWriting
        val point=InkSample(if(freeGesture)w.x.toFloat()else w.x.toFloat().coerceIn(0f,1000f),if(freeGesture)w.y.toFloat()else w.y.toFloat().coerceIn(0f,1414f),time,pressure,tilt,orientation,freeGesture)
        val previous=raw.lastOrNull()
        if(previous==point||previous!=null&&previous.elapsedMs>time)return
        if(previous!=null&&previous.x==point.x&&previous.y==point.y&&previous.elapsedMs==time){
            raw[raw.lastIndex]=point
            if(!gestureErase&&gesturePen!=InkPen.PENCIL){live.start(InkBrushes.brush(gesturePen,gestureColor,gestureWidth,hasPressure,gestureAppearance));incremental.clear();raw.forEach{InkBrushes.add(incremental,it,inputKind,gesturePen,gestureAppearance)};live.enqueueInputs(incremental,empty)}
            return
        }
        try{if(!gestureErase&&gesturePen!=InkPen.PENCIL){incremental.clear();InkBrushes.add(incremental,point,inputKind,gesturePen,gestureAppearance);live.enqueueInputs(incremental,empty)};raw.add(point)
            if(!gestureErase&&raw.size>=128&&(raw.size%256==0||time-checkpointAt>=1000)){
                checkpointAt=time;onCheckpoint(InkStroke(gestureId,gesturePen,gestureColor,gestureWidth,inputKind,raw.toList(),world||seamWriting,appearance=gestureAppearance))
            };if(raw.size==InkLimits.MAX_POINTS)onNotice("达到单笔采样上限，请抬笔提交后继续。")}catch(_:IllegalArgumentException){onNotice("无效设备采样未进入笔迹。")}
    }
    internal fun retainLiveStroke(s:InkStroke){if(s.pen==InkPen.PENCIL)transientPencils[s.id]=s else transient[s.id]=InkBrushes.stroke(s);invalidate()}
    private fun finishGesture(){
        if(inputId==-1)return
        try{if(raw.isNotEmpty()){
            val tap=tapImage.takeIf{!gestureErase&&raw.all{p->hypot(p.x-raw.first().x,p.y-raw.first().y)*viewport.zoom*density<android.view.ViewConfiguration.get(context).scaledTouchSlop}}
            if(tap!=null){onObjectTap(tap)}else if(gestureErase)onErase(raw.toList(),gestureRadius,gestureWhole,gestureOnlyHighlighter)else{if(gesturePen!=InkPen.PENCIL){live.finishInput();live.updateShape()};val s=finishStroke(InkStroke(gestureId,gesturePen,gestureColor,gestureWidth,inputKind,raw,world||seamWriting,appearance=gestureAppearance));if(!seamWriting){if(s.pen==InkPen.PENCIL)transientPencils[s.id]=s else transient[s.id]=if(s.samples==raw)live.toImmutable()else InkBrushes.stroke(s)};try{onStroke(s)}catch(e:Exception){transient.remove(s.id);transientPencils.remove(s.id);throw e}}}}
        catch(_:Exception){onNotice("本次操作未接收；已确认内容不变，请先导出副本并检查容量。")}
        finally{tapImage=null;onLiveSamples(emptyList());inputId=-1;BackgroundBudget.input(this,false);raw.clear();gestureErase=false;onGesture(false);parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    }
    private fun finishViewport(){if(movingViewport){movingViewport=false;onViewport(viewport)}}
    fun cancelGesture(discardCheckpoint:Boolean=true){if(discardCheckpoint&&inputId!=-1&&!gestureErase)onCheckpointCancel(gestureId);tapImage=null;onLiveSamples(emptyList());val active=inputId!=-1;inputId=-1;BackgroundBudget.input(this,false);raw.clear();gestureErase=false;cursor=null;parent?.requestDisallowInterceptTouchEvent(false);if(active)onGesture(false);invalidate()}
    override fun onDetachedFromWindow(){viewTreeObserver.removeOnPreDrawListener(visibleAreaListener);observedVisiblePixels.setEmpty();mapSceneJob?.cancel();mapSceneJob=null;PencilRenderer.forget(content.filter{it.pen==InkPen.PENCIL}.map{it.id}.toSet()+gestureId);pageRaster.clear();asyncRaster.clear();documentJob?.cancel();releaseDocumentTile();documentRequest=null;imageRendering.clear();objectPainter.clear();cancelGesture(false);if(configured&&!preview)onViewport(viewport);super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
