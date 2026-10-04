// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.core.AnnotationTarget
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.math.roundToInt

/** Native production bind/drag/reorder/pinch/unbind/reopen flow with three seeded synthetic strokes.
 * View.draw checks production paint at the expected projection; this is not a real pen/device visual claim.
 */
class BoundAnnotationFlowUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun study(book:String)=ViewModelProvider(compose.activity)["study-$book",StudyViewModel::class.java]
    private fun author(book:String)=ViewModelProvider(compose.activity)["map-authoring-$book-main",PageAuthoringViewModel::class.java]
    private fun state(book:String)=runBlocking{app.authoring.read(AuthoringScope.map(MapRef(book)))}
    private fun graph(book:String)=runBlocking{app.study.readGraph(book)}
    private fun waitFor(tag:String){compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(tag:String){
        compose.revealAction(tag);waitFor(tag);val node=compose.onNodeWithTag(tag)
        runCatching{node.performScrollTo()}
        compose.waitUntil(15_000){runCatching{node.assertIsEnabled()}.isSuccess}
        node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()
    }
    private fun close(tag:String){
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()}
    }
    private fun map():MindMapView {
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){
            val view=queue.removeFirst();if(view is MindMapView&&view.isShown)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)queue.add(view.getChildAt(i))
        }
        error("Visible production map missing")
    }
    private fun settled(book:String){
        compose.waitUntil(15_000){
            val ui=compose.runOnIdle{study(book).ui.value};val annotation=compose.runOnIdle{author(book).ui.value}
            !ui.loading&&!ui.busy&&!ui.unknown&&!ui.readFailed&&annotation.ready&&ui.graph?.graphFingerprint==graph(book).graphFingerprint
        }
        compose.waitForIdle()
    }
    private fun select(node:String){
        val point=compose.runOnIdle{map().focusNode(node);checkNotNull(map().nodeBounds(node)).let{Offset(it.centerX(),it.centerY())}}
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput{advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong()+1);click(point)}
        compose.runOnIdle{assertEquals(node,map().selectedNodeId)}
    }
    private fun openAnnotation(node:String){select(node);tap("node-more");tap("node-annotation-open");waitFor("annotation-bind")}
    private fun drag(book:String,node:String){
        select(node)
        val before=graph(book).nodes.single{it.id==node}
        val start=compose.runOnIdle{checkNotNull(map().nodeBounds(node)).let{Offset(it.centerX(),it.centerY())}}
        val area=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot
        val end=start+Offset(area.width*.08f,area.height*.06f)
        compose.onNodeWithTag("study-map").performTouchInput{swipe(start,end,durationMillis=400)}
        compose.waitUntil(15_000){graph(book).nodes.single{it.id==node}.let{it.x!=before.x||it.y!=before.y}}
        settled(book)
    }
    private data class Fixture(val book:String,val first:String,val node:String,val card:String,val free:String,val hidden:String,val locked:String,
        val hiddenLayer:String,val lockedLayer:String,val original:PageAuthoring,val originalInk:ByteArray,val source:ByteArray)
    private fun fixture():Fixture {
        waitFor("new-note")
        val note=runBlocking{app.workspaceRepository.create("绑定批注组合合成 ${id().take(6)}",false,PaperStyle.BLANK)}
        val first=id();val node=id();val card=id();val free=id();val hidden=id();val locked=id()
        val hiddenLayer=UserLayer(id(),"隐藏批注",visible=false);val lockedLayer=UserLayer(id(),"锁定批注",locked=true)
        val originalInk=InkStroke(id(),InkPen.PEN,0xff223344.toInt(),3f,InkTool.STYLUS,listOf(InkSample(40f,80f,0),InkSample(100f,110f,20)))
        fun loose(id:String,y:Float,color:Int)=BoundAnnotation(InkStroke(id,InkPen.PEN,color,8f,InkTool.STYLUS,
            listOf(InkSample(180f,y,0,world=true),InkSample(220f,y+16f,100,world=true)),world=true),AnnotationTarget(AnnotationTargetKind.PAGE,note.id))
        val annotations=listOf(loose(free,392f,0xffc000c0.toInt()),loose(hidden,432f,0xffe07000.toInt()),loose(locked,472f,0xff0040f0.toInt()))
        // Deliberately non-default stack order; identity/flags/membership must survive every operation.
        val layers=UserLayers(listOf(lockedLayer,UserLayer(UserLayers.DEFAULT_ID,"基础层"),hiddenLayer),UserLayers.DEFAULT_ID,
            listOf(LayerMembership(LayerContent(LayerContentKind.ANNOTATION,free),UserLayers.DEFAULT_ID),
                LayerMembership(LayerContent(LayerContentKind.ANNOTATION,hidden),hiddenLayer.id),
                LayerMembership(LayerContent(LayerContentKind.ANNOTATION,locked),lockedLayer.id)))
        val original=PageAuthoring(layers,annotations=annotations)
        val source=runBlocking{
            app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(originalInk)))
            app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=id(),nodeId=first,title="上方主题",x=40.0,y=80.0))
            app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=card,nodeId=node,title="绑定目标",body="保留来源",x=40.0,y=360.0,
                source=StudySourceDraft(note.id,1,originalInk.bounds(),listOf(originalInk.id))))
            val scope=AuthoringScope.map(MapRef(note.id));val before=app.authoring.read(scope)
            app.authoring.save(scope,before,id(),original)
            checkNotNull(app.study.source(card)).snapshot.clone()
        }
        assertTrue(app.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${note.id}",false).commit())
        assertTrue(app.getSharedPreferences("inkweft-study-window",0).edit().putString("${note.id}-mode","FOCUS").commit())
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.waitForSavedInk();tap("quick-study");tap("study-tab-2");settled(note.id)
        return Fixture(note.id,first,node,card,free,hidden,locked,hiddenLayer.id,lockedLayer.id,original,InkStrokeCodec.encode(originalInk),source)
    }
    private fun assertPreserved(f:Fixture){
        val now=state(f.book).state
        assertEquals(f.original.layers.layers,now.layers.layers)
        assertEquals(f.original.layers.currentId,now.layers.currentId)
        // Membership is keyed data: the production codec sorts it by content kind/ID, unlike the layer stack.
        val expectedMembership=f.original.layers.memberships.associate{it.content to it.layerId}
        val actualMembership=now.layers.memberships.associate{it.content to it.layerId}
        assertEquals("Fixture membership keys must be unique",f.original.layers.memberships.size,expectedMembership.size)
        assertEquals("Persisted membership keys must be unique",now.layers.memberships.size,actualMembership.size)
        assertEquals("No membership may be added or dropped",f.original.layers.memberships.size,now.layers.memberships.size)
        assertEquals(expectedMembership,actualMembership)
        assertEquals(f.original.layers.deleted,now.layers.deleted)
        assertEquals(f.original.annotations.size,now.annotations.size)
        f.original.annotations.forEach{old->assertArrayEquals("Never transform the stored author samples",InkStrokeCodec.encode(old.stroke),InkStrokeCodec.encode(now.annotations.single{it.stroke.id==old.stroke.id}.stroke))}
        for(protected in listOf(f.hidden,f.locked)){
            val old=f.original.annotations.single{it.stroke.id==protected};val fresh=now.annotations.single{it.stroke.id==protected}
            assertEquals(old.target,fresh.target);assertEquals(old.localFrame,fresh.localFrame);assertEquals(old.referenceWidth,fresh.referenceWidth,0.0)
        }
        assertFalse(now.visibleAnnotations().any{it.stroke.id==f.hidden})
        assertFalse(now.layers.editable(LayerContent(LayerContentKind.ANNOTATION,f.locked)))
        runBlocking{
            val ink=app.inkRepository.read(f.book);assertEquals(1L,ink.revision);assertEquals(1,ink.strokes.size)
            assertArrayEquals(f.originalInk,InkStrokeCodec.encode(ink.strokes.single().stroke))
            assertArrayEquals(f.source,checkNotNull(app.study.source(f.card)).snapshot)
        }
    }
    private fun projectedPoint(annotation:BoundAnnotation,nodeBounds:CanvasBounds):CanvasPoint {
        val sample=annotation.stroke.samples
        val point=CanvasPoint((sample.first().x+sample.last().x)/2.0,(sample.first().y+sample.last().y)/2.0)
        val frame=if(annotation.target.kind==AnnotationTargetKind.PAGE)AnnotationFrame(0.0,0.0)else annotation.targetFrame(nodeBounds)
        return annotation.displayFrame(frame).project(point)
    }
    /** Sample actual production painting, so a double canvas/local transform cannot pass on metadata alone. */
    private fun assertPaintAt(point:CanvasPoint){
        compose.runOnIdle{
            val view=map();val camera=view.snapshotViewport();val density=view.resources.displayMetrics.density
            val x=(point.x*camera.scale*density+camera.x).roundToInt();val y=(point.y*camera.scale*density+camera.y).roundToInt()
            assertTrue("Expected annotation is inside the visible map",x in 5 until view.width-5&&y in 5 until view.height-5)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{
                view.draw(Canvas(bitmap))
                assertTrue("Production ink must be painted at exactly one projected position",(-4..4).any{dx->(-4..4).any{dy->
                    val color=bitmap.getPixel(x+dx,y+dy);android.graphics.Color.red(color)>130&&android.graphics.Color.green(color)<60&&android.graphics.Color.blue(color)>130
                }})
            }finally{bitmap.recycle()}
        }
    }
    @Test fun bindMoveReorderZoomDetachAndReopenKeepOneTransformAndProtectedLayerOwnership(){
        val f=fixture();val initial=state(f.book);val originalCards=graph(f.book).cards
        openAnnotation(f.node);tap("annotation-bind")
        compose.waitUntil(15_000){state(f.book).state.annotations.single{it.stroke.id==f.free}.target==AnnotationTarget(AnnotationTargetKind.MAP_OCCURRENCE,f.node)}
        settled(f.book);close("bound-annotation")
        val bound=state(f.book);assertEquals(initial.revision+1,bound.revision);assertPreserved(f)
        val beforeBounds=compose.runOnIdle{checkNotNull(map().annotationBounds(f.node))}
        val boundInk=bound.state.annotations.single{it.stroke.id==f.free}
        val beforePoint=projectedPoint(boundInk,beforeBounds)
        assertEquals(200.0,beforePoint.x,.001);assertEquals(400.0,beforePoint.y,.001);assertPaintAt(beforePoint)

        drag(f.book,f.node)
        val movedBounds=compose.runOnIdle{checkNotNull(map().annotationBounds(f.node))}
        val movedPoint=projectedPoint(boundInk,movedBounds)
        assertEquals(beforePoint.x+movedBounds.left-beforeBounds.left,movedPoint.x,.001)
        assertEquals(beforePoint.y+movedBounds.top-beforeBounds.top,movedPoint.y,.001)
        assertArrayEquals(PageAuthoringCodec.encode(bound.state),PageAuthoringCodec.encode(state(f.book).state));assertPaintAt(movedPoint)

        val inputTrace=java.util.ArrayDeque<String>()
        fun record(line:String){if(inputTrace.size>=200)inputTrace.removeFirst();inputTrace.addLast(line)}
        val instrumented=compose.runOnIdle{map()}
        val detector=MindMapView::class.java.getDeclaredField("detector").apply{isAccessible=true}.get(instrumented) as ScaleGestureDetector
        val originalViewport=compose.runOnIdle{instrumented.onViewport}
        var phase="select-and-reorder"
        var eventTime=0L
        fun detectorState()="progress=${detector.isInProgress} span=${detector.previousSpan}->${detector.currentSpan} spanXY=${detector.currentSpanX},${detector.currentSpanY} factor=${detector.scaleFactor} quick=${detector.isQuickScaleEnabled}"
        compose.runOnIdle{
            // Diagnostic observation only: preserve the production viewport callback and never consume a MotionEvent.
            instrumented.setOnTouchListener{_,event->
                eventTime=event.eventTime
                record("$phase event action=${event.actionMasked} index=${event.actionIndex} time=$eventTime down=${event.downTime} count=${event.pointerCount} "+
                    (0 until event.pointerCount).joinToString(";"){"p${event.getPointerId(it)}=${event.getX(it)},${event.getY(it)} tool=${event.getToolType(it)}"}+
                    " source=${event.source} size=${instrumented.width}x${instrumented.height} enabled=${instrumented.enabledInput} editing=${instrumented.editingTitle} viewport=${instrumented.snapshotViewport()} detectorBefore=${detectorState()}")
                false
            }
            instrumented.onViewport={viewport->
                val caller=Throwable().stackTrace.filter{it.className.startsWith("org.inkweft.app.MindMapView")}.joinToString(" <- "){it.methodName}
                record("$phase viewport time=$eventTime source=$caller value=$viewport detector=${detectorState()}")
                originalViewport(viewport)
            }
        }
        try{
            select(f.node);tap("node-more");tap("node-organize");tap("node-order-up")
            compose.waitUntil(15_000){graph(f.book).orderedNodeIds==listOf(f.node,f.first)};settled(f.book)
            assertEquals(movedBounds,compose.runOnIdle{map().annotationBounds(f.node)})
            assertArrayEquals(PageAuthoringCodec.encode(bound.state),PageAuthoringCodec.encode(state(f.book).state))
            val camera=compose.runOnIdle{map().snapshotViewport()}
            val beforeZoomNodes=graph(f.book).nodes
            assertTrue("The actual camera must have room to zoom in: ${camera.scale}",camera.scale<2.5f)
            phase="original-timing"
            val semanticBounds=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot
            val nativeSize=compose.runOnIdle{instrumented.width to instrumented.height}
            val pinchPoints=compose.runOnIdle{
                val visible=android.graphics.Rect()
                assertTrue("The native map must be visible before pinch",instrumented.getLocalVisibleRect(visible))
                val configuration=ViewConfiguration.get(instrumented.context)
                val density=instrumented.resources.displayMetrics.density
                val touchSlop=configuration.scaledTouchSlop.toFloat()
                val minimumSpan=configuration.scaledMinimumScalingSpan.toFloat()
                val margin=maxOf(8f*density,touchSlop)
                val safe=android.graphics.RectF(visible).apply{inset(margin,margin)}
                // Both spans clear Android's minimum; leave multiple touch-slops of motion after detection begins.
                val startSpan=minimumSpan+maxOf(touchSlop,1f)
                val endSpan=startSpan+maxOf(4f*touchSlop,16f*density)
                val horizontal=safe.width()>=endSpan
                record("$phase geometry native=${nativeSize.first}x${nativeSize.second} semantics=$semanticBounds visible=$visible margin=$margin minSpan=$minimumSpan touchSlop=$touchSlop span=$startSpan->$endSpan horizontal=$horizontal")
                assertTrue("Visible native map cannot fit a threshold-crossing pinch: safe=$safe requiredSpan=$endSpan minSpan=$minimumSpan touchSlop=$touchSlop",
                    safe.width()>0&&safe.height()>0&&(horizontal||safe.height()>=endSpan))
                fun point(span:Float,side:Float)=if(horizontal)Offset(safe.centerX()+side*span/2,safe.centerY())
                    else Offset(safe.centerX(),safe.centerY()+side*span/2)
                listOf(point(startSpan,-1f),point(endSpan,-1f),point(startSpan,1f),point(endSpan,1f))
            }
            record("$phase begin view=${System.identityHashCode(instrumented)} viewport=$camera")
            compose.onNodeWithTag("study-map").performTouchInput{
                assertEquals("Compose/native pinch coordinate width",nativeSize.first,width)
                assertEquals("Compose/native pinch coordinate height",nativeSize.second,height)
                pinch(start0=pinchPoints[0],end0=pinchPoints[1],start1=pinchPoints[2],end1=pinchPoints[3],durationMillis=400)
            }
            compose.waitForIdle()
            val zoomed=compose.runOnIdle{map().snapshotViewport()}
            record("$phase end view=${compose.runOnIdle{System.identityHashCode(map())}} viewport=$zoomed detector=${compose.runOnIdle{detectorState()}}")
            val diagnostic=inputTrace.joinToString("\n")
            assertTrue("Original native two-finger spread must increase scale: ${camera.scale} -> ${zoomed.scale}\n$diagnostic",zoomed.scale>camera.scale)
            assertEquals("Pinch must cancel node dragging without authoring positions",beforeZoomNodes,graph(f.book).nodes)
            assertEquals(bound.revision,state(f.book).revision)
            assertArrayEquals(PageAuthoringCodec.encode(bound.state),PageAuthoringCodec.encode(state(f.book).state));assertPaintAt(movedPoint);assertPreserved(f)
        }finally{
            compose.runOnIdle{instrumented.setOnTouchListener(null);instrumented.onViewport=originalViewport}
            println("BOUND_ANNOTATION_PINCH_TRACE\n${inputTrace.joinToString("\n")}")
        }

        openAnnotation(f.node);tap("annotation-unbind")
        compose.waitUntil(15_000){state(f.book).state.annotations.single{it.stroke.id==f.free}.target==AnnotationTarget(AnnotationTargetKind.PAGE,f.book)}
        settled(f.book);close("bound-annotation")
        val detached=state(f.book);val detachedInk=detached.state.annotations.single{it.stroke.id==f.free}
        assertEquals(bound.revision+1,detached.revision)
        val detachedPoint=projectedPoint(detachedInk,movedBounds)
        assertEquals(movedPoint.x,detachedPoint.x,.001);assertEquals(movedPoint.y,detachedPoint.y,.001);assertPaintAt(detachedPoint)
        drag(f.book,f.node)
        assertEquals(detachedPoint,projectedPoint(state(f.book).state.annotations.single{it.stroke.id==f.free},checkNotNull(compose.runOnIdle{map().annotationBounds(f.node)})))
        assertPaintAt(detachedPoint);assertPreserved(f)
        tap("study-close");tap("quick-study");settled(f.book)
        assertArrayEquals(PageAuthoringCodec.encode(detached.state),PageAuthoringCodec.encode(state(f.book).state))
        assertEquals(detached.revision,state(f.book).revision);assertEquals(originalCards,graph(f.book).cards);assertPreserved(f)
        // Reopen the real target panel and its layer UI; the detached editable stroke remains on the map.
        openAnnotation(f.node);compose.onNodeWithTag("annotation-unbind").assertDoesNotExist();tap("annotation-layers")
        compose.onNodeWithTag("layer-heading-${f.hiddenLayer}").assertTextContains("隐藏批注",substring=true).assertTextContains("已隐藏",substring=true)
        compose.onNodeWithTag("layer-heading-${f.lockedLayer}").assertTextContains("锁定批注",substring=true).assertTextContains("已锁定",substring=true)
        close("page-layers");close("bound-annotation")
    }
}
