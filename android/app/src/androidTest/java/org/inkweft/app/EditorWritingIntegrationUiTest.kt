// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real MainActivity, repository objects and stylus events on its existing native canvas. */
class EditorWritingIntegrationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun id()=UUID.randomUUID().toString()
    private fun canvas():InkCanvasView {
        fun find(v:View):InkCanvasView? {
            if(v is InkCanvasView&&v.isShown&&!v.preview&&!v.embeddedPage)return v
            if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun tap(tag:String){compose.revealAction(tag);val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.assertIsDisplayed().assertIsEnabled().performClick();compose.waitForIdle()}
    private fun saved(book:String,count:Int){compose.waitUntil(30_000){app.navigationReady.value&&runBlocking{InkSession(app.inkRepository.read(book)).visibleDraft().size==count}};compose.waitForIdle()}
    private fun strokes(book:String)=runBlocking{InkSession(app.inkRepository.read(book)).visibleDraft()}
    private fun seed(singlePage:Boolean=true,savedView:CanvasViewport?=null):String {
        compose.waitUntil(30_000){runCatching{compose.onNodeWithTag("new-note").assertIsDisplayed().assertIsEnabled()}.isSuccess}
        compose.waitForIdle()
        val book=runBlocking {
            val book=app.workspaceRepository.create("高等数学 · 导数与变化率",false,PaperStyle.GRID).id
            fun text(x:Float,y:Float,w:Float,s:String,size:Float=23f,color:Int=0xff24342f.toInt())=
                PageObject(id(),PageObjectKind.TEXT,x,y,w,65f,text=s,fontSize=size,font=TextFont.WENKAI,color=color)
            val objects=listOf(
                text(54f,35f,870f,"03   导数与变化率",32f),
                text(54f,105f,425f,"从平均变化率到瞬时变化率"),
                text(54f,155f,430f,"Δy / Δx = [ f(x + Δx) − f(x) ] / Δx",20f),
                text(54f,215f,425f,"当 Δx → 0，割线趋近于切线。"),
                text(54f,275f,425f,"例：f(x) = x²，f′(x) = 2x",24f,0xff2f53aa.toInt()),
                text(540f,105f,405f,"几何意义：曲线在该点的斜率"),
                text(540f,155f,405f,"切线：y − f(a) = f′(a)(x − a)",22f),
                text(540f,215f,405f,"在 a = 1 处，斜率为 2。"),
                text(54f,380f,860f,"检查：定义域 → 求导 → 代入 → 解释实际意义",23f))
            app.pageObjects.save(book,0,id(),objects)
            assertArrayEquals(PageObjectCodec.encode(objects),PageObjectCodec.encode(app.pageObjects.read(book).objects))
            app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="导数与变化率",body="从原笔记回看定义、例题与几何意义。",x=60.0,y=80.0))
            savedView?.let{app.workspaceRepository.saveViewport(book,it)}
            book
        }
        val note=runBlocking{checkNotNull(app.repository.read(book))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        if(singlePage)compose.singlePageEditor() else compose.waitUntil(30_000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        saved(book,0);compose.waitForIdle()
        return book
    }
    private fun stylus(y:Float){
        compose.runOnIdle{
            val v=canvas();val time=SystemClock.uptimeMillis()
            for(i in 0..12){
                val p=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val c=MotionEvent.PointerCoords().apply{x=v.width*(.1f+i*.022f);this.y=v.height*y+kotlin.math.sin(i*.35f)*8;pressure=.6f}
                val e=MotionEvent.obtain(time,time+i*20L,when(i){0->MotionEvent.ACTION_DOWN;12->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},1,arrayOf(p),arrayOf(c),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try{assertTrue(v.dispatchTouchEvent(e))}finally{e.recycle()}
            }
        }
    }
    private fun shot(name:String){compose.waitForIdle();instrumentation.waitForIdleSync()
        val frame=java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync{val decor=compose.activity.window.decorView;decor.postOnAnimation{decor.postOnAnimation{frame.countDown()}}}
        assertTrue("Capture must follow actual native frames",frame.await(30,java.util.concurrent.TimeUnit.SECONDS))
        val bmp=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}}
    private fun shell(command:String){instrumentation.uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}

    @Test fun landscapeRealInkPresetsHistoryPanelsAndRecreation(){
        val book=seed()
        for(tag in listOf("top-draw","pen-kind-highlighter","top-eraser","ink-select","page-layers-open","toolbar-more"))
            compose.onNodeWithTag(tag).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("floating-pen-case").assertDoesNotExist()
        tap("quick-width-2");tap("quick-color-2");stylus(.57f);saved(book,1)
        val first=strokes(book).single();assertEquals(6f,first.width,0f);assertEquals(0xff2f53aa.toInt(),first.color);assertEquals(InkTool.STYLUS,first.tool)
        tap("quick-width-0");tap("quick-color-1");stylus(.62f);saved(book,2)
        assertArrayEquals(InkStrokeCodec.encode(first),InkStrokeCodec.encode(strokes(book)[0]));assertEquals(1.5f,strokes(book)[1].width,0f);assertEquals(0xffb83239.toInt(),strokes(book)[1].color)
        tap("ink-undo");saved(book,1);assertArrayEquals(InkStrokeCodec.encode(first),InkStrokeCodec.encode(strokes(book).single()));tap("ink-redo");saved(book,2)
        tap("pen-kind-highlighter");tap("quick-color-4");stylus(.51f);saved(book,3)
        assertEquals(InkPen.HIGHLIGHTER,strokes(book).last().pen);assertEquals(0x66e1ad19,strokes(book).last().color)
        tap("top-eraser");tap("ink-select");tap("top-draw")
        val before=strokes(book).map{InkStrokeCodec.encode(it).toList()}
        repeat(3){tap("page-layers-open");compose.onNodeWithTag("current-writable-layer").assertTextContains("书写层",substring=true);compose.onNodeWithContentDescription("关闭图层").performClick()}
        tap("page-layers-open")
        val toolbar=compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().layoutInfo.coordinates
        val panel=compose.onNodeWithTag("page-layers").fetchSemanticsNode().layoutInfo.coordinates
        assertTrue("Layer card must open below the writing tools",panel.positionOnScreen().y>=toolbar.positionOnScreen().y+toolbar.size.height)
        shot("production-layers-landscape.png");compose.onNodeWithContentDescription("关闭图层").performClick()
        val readingBefore=readingState()
        repeat(3){tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed();assertReadingContext(readingBefore);assertLeadingReadable();tap("study-close");assertReadingContext(readingBefore)}
        tap("quick-study");assertLeadingReadable();shot("production-map-landscape.png")
        val dockedReading=readingState();compose.activityRule.scenario.recreate();saved(book,3)
        compose.onNodeWithTag("study-map").assertIsDisplayed();assertReadingContext(dockedReading);assertLeadingReadable();tap("study-close")
        assertEquals(before,strokes(book).map{InkStrokeCodec.encode(it).toList()});shot("production-writing-landscape.png")
        compose.activityRule.scenario.recreate();saved(book,3)
        assertEquals(before,strokes(book).map{InkStrokeCodec.encode(it).toList()});compose.onNodeWithTag("quick-width-0").assertIsOn();compose.onNodeWithTag("quick-color-1").assertIsOn()
    }

    @Test fun narrowLargeTextKeepsToolsParametersAndSourceReachable(){
        try{
            shell("wm size 375x800");shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000){compose.activity.resources.configuration.screenWidthDp==375&&compose.activity.resources.configuration.fontScale>1.5f}
            val book=seed()
            for(tag in listOf("top-draw","pen-kind-highlighter","top-eraser","ink-select","toolbar-more"))compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsEqualTo(48.dp)
            compose.onNodeWithTag("quick-width-0").assertDoesNotExist()
            compose.openCurrentPen();compose.revealAction("width-preset-0");tap("width-preset-0");compose.closePenSettings()
            stylus(.45f);saved(book,1);assertEquals(1.5f,strokes(book).single().width,0f)
            tap("page-layers-open");compose.onNodeWithContentDescription("关闭图层").assertIsDisplayed();shot("production-layers-narrow-font16.png");compose.onNodeWithContentDescription("关闭图层").performClick()
            val readingBefore=readingState();assertLeadingReadable()
            tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed();assertReadingContext(readingBefore);assertLeadingReadable()
            val window=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
            val page=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("Opening a map must retain a useful source region",window.top-page.top>=page.height*.25f)
            shot("production-map-narrow-font16.png")
            repeat(4){panReading("ink-surface",-95f,0f)}
            assertTrue("Two fingers must reach the right column while the map stays open",readingState().left>350)
            shot("reading-single-narrow-panned.png")
            val preZoom=readingState();zoomReading("ink-surface")
            assertTrue("A native pinch must enlarge the single-page source",readingState().zoom>preZoom.zoom*1.2)
            val chosen=readingState();tap("study-close");assertReadingContext(chosen);tap("quick-study");assertReadingContext(chosen)
            compose.activityRule.scenario.recreate();saved(book,1);assertReadingContext(chosen);tap("study-close")
            shot("production-writing-narrow-font16.png")
            val before=strokes(book).map{InkStrokeCodec.encode(it).toList()};compose.activityRule.scenario.recreate();saved(book,1);assertEquals(before,strokes(book).map{InkStrokeCodec.encode(it).toList()})
        }finally{shell("settings put system font_scale 1.0");shell("wm size reset");compose.activityRule.scenario.recreate()}
    }
    private fun readingCanvas():InkCanvasView {
        fun find(v:View):InkCanvasView? {
            if(v is InkCanvasView&&v.isShown&&!v.preview)return v
            if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private data class ReadingState(val zoom:Double,val left:Double,val top:Double)
    private fun readingBounds():android.graphics.RectF {
        val tag=if(compose.onAllNodesWithTag("continuous-viewport").fetchSemanticsNodes().isNotEmpty())"continuous-viewport"else"ink-surface"
        val coordinates=compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
        val origin=coordinates.positionOnScreen()
        return android.graphics.RectF(origin.x,origin.y,origin.x+coordinates.size.width,origin.y+coordinates.size.height)
    }
    private fun readingState():ReadingState {
        val visible=readingBounds()
        return compose.runOnIdle {
            val v=readingCanvas();val origin=IntArray(2);v.getLocationOnScreen(origin)
            val camera=v.snapshotViewport();val world=camera.screenToWorld((visible.left-origin[0]).toDouble(),(visible.top-origin[1]).toDouble(),v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble())
            ReadingState(camera.zoom,world.x,world.y)
        }
    }
    private fun assertReadingContext(expected:ReadingState){
        compose.waitUntil(30_000){runCatching{val actual=readingState();kotlin.math.abs(expected.zoom-actual.zoom)<.002&&kotlin.math.abs(expected.left-actual.left)<2&&kotlin.math.abs(expected.top-actual.top)<2}.getOrDefault(false)}
        val actual=readingState();assertEquals(expected.zoom,actual.zoom,.002);assertEquals(expected.left,actual.left,2.0);assertEquals(expected.top,actual.top,2.0)
    }
    private fun assertLeadingReadable(){
        val map=compose.onAllNodesWithTag("study-panel").fetchSemanticsNodes().firstOrNull()?.layoutInfo?.coordinates
        val mapOrigin=map?.positionOnScreen();val mapWidth=map?.size?.width
        val visible=readingBounds()
        compose.runOnIdle {
            val v=readingCanvas();assertTrue(v.isShown)
            val origin=IntArray(2);v.getLocationOnScreen(origin)
            val camera=v.snapshotViewport();val density=v.resources.displayMetrics.density.toDouble()
            assertTrue("A 20-unit formula must be at least 15dp high, not an unreadable page thumbnail",20*camera.zoom>=15)
            val bottom=if(mapOrigin!=null&&mapWidth!=null&&mapOrigin.x<visible.right&&mapOrigin.x+mapWidth>visible.left)minOf(visible.bottom.toFloat(),mapOrigin.y)else visible.bottom.toFloat()
            for(y in listOf(35.0,105.0,155.0)){
                val start=camera.worldToScreen(54.0,y,v.width.toDouble(),v.height.toDouble(),density)
                val reading=camera.worldToScreen(300.0,y+30,v.width.toDouble(),v.height.toDouble(),density)
                assertTrue("Title, explanation and formula leading text must stay in the visible source lane",origin[0]+start.x>=visible.left-1&&origin[0]+reading.x<=visible.right+1&&origin[1]+start.y>=visible.top-1&&origin[1]+reading.y<=bottom+1)
            }
        }
    }
    private fun panReading(tag:String,dx:Float,dy:Float){
        compose.onNodeWithTag(tag).performTouchInput {
            val a=Offset(width*.60f,80f);val b=Offset(width*.85f,80f)
            down(0,a);down(1,b)
            repeat(10){i->val delta=Offset(dx,dy)*((i+1)/10f);updatePointerTo(0,a+delta);updatePointerTo(1,b+delta);move(30)}
            up(1);up(0)
        }
        compose.waitForIdle()
    }
    private fun zoomReading(tag:String){
        val native=if(tag=="ink-surface")compose.runOnIdle{readingCanvas()}else null
        val config=native?.let{ViewConfiguration.get(it.context)}
        var moves=0;var receivedSpan=0f;var expectedSpan=0f;val before=readingState()
        if(native!=null)compose.runOnIdle{native.setOnTouchListener{_,event->
            if(event.actionMasked==MotionEvent.ACTION_MOVE&&event.pointerCount==2){
                assertNotEquals(event.getPointerId(0),event.getPointerId(1));moves++
                receivedSpan=maxOf(receivedSpan,kotlin.math.hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0)))
            }
            false // Observe the real native dispatch without consuming or altering it.
        }}
        try{
            compose.onNodeWithTag(tag).performTouchInput {
                // Android's native recognizer starts only beyond its physical minimum span.
                val start=config?.let{it.scaledMinimumScalingSpan+2f*it.scaledTouchSlop}?:width*.24f
                val end=if(config!=null)width-32f else width*.60f
                expectedSpan=end
                assertTrue("Visible source must provide room for a recognised pinch: $start -> $end",end>start*1.3f)
                println("READING_PINCH tag=$tag width=$width minSpan=${config?.scaledMinimumScalingSpan} span=$start->$end")
                pinch(start0=Offset(centerX-start/2,80f),start1=Offset(centerX+start/2,80f),end0=Offset(centerX-end/2,80f),end1=Offset(centerX+end/2,80f),durationMillis=600)
            }
            compose.waitForIdle()
            if(native!=null){assertTrue("Two-pointer moves must reach the native source",moves>0);assertTrue("Native source must receive the full spread",receivedSpan>=expectedSpan-2f)}
        }finally{
            if(native!=null)compose.runOnIdle{native.setOnTouchListener(null)}
            println("READING_PINCH_RESULT tag=$tag moves=$moves span=$receivedSpan before=$before after=${readingState()}")
        }
    }
    private fun continuousReading(narrow:Boolean){
        try{
            if(narrow){shell("wm size 375x800");shell("settings put system font_scale 1.6");compose.activityRule.scenario.recreate()
                compose.waitUntil(30_000){compose.activity.resources.configuration.screenWidthDp==375&&compose.activity.resources.configuration.fontScale>1.5f}}
            val book=seed(singlePage=false)
            val objects=runBlocking{PageObjectCodec.encode(app.pageObjects.read(book).objects)}
            val before=readingState();assertLeadingReadable()
            tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed();assertReadingContext(before);assertLeadingReadable()
            shot(if(narrow)"reading-continuous-map-narrow-font16.png"else"reading-continuous-map-landscape.png")
            if(narrow){repeat(4){panReading("continuous-viewport",-95f,0f)}
                assertTrue("Continuous source remains horizontally readable alongside the map",readingState().left>350)
                shot("reading-continuous-narrow-panned.png")}
            val preZoom=readingState();zoomReading("continuous-viewport")
            assertTrue("An actual pinch must enlarge the retained source",readingState().zoom>preZoom.zoom*1.2)
            panReading("continuous-viewport",-60f,-45f)
            val chosen=readingState();assertTrue(chosen.top>before.top+10)
            repeat(2){tap("study-close");assertReadingContext(chosen);tap("quick-study");assertReadingContext(chosen)}
            compose.activityRule.scenario.recreate();saved(book,0)
            compose.onNodeWithTag("study-map").assertIsDisplayed();assertReadingContext(chosen)
            assertArrayEquals(objects,runBlocking{PageObjectCodec.encode(app.pageObjects.read(book).objects)})
            assertTrue(strokes(book).isEmpty());tap("study-close")
        }finally{if(narrow){shell("settings put system font_scale 1.0");shell("wm size reset");compose.activityRule.scenario.recreate()}}
    }
    @Test fun landscapeContinuousMapRetainsReadingScalePanAndRecreation()=continuousReading(false)
    @Test fun narrowContinuousMapKeepsReadableContentAndTwoFingerNavigation()=continuousReading(true)

    @Test fun continuousReopensSavedReadingViewportWithoutFittingItAway(){
        val savedView=CanvasViewport(450.0,500.0,1.6)
        val book=seed(singlePage=false,savedView=savedView)
        val bounds=readingBounds();val density=compose.activity.resources.displayMetrics.density
        val expected=ReadingState(savedView.zoom,savedView.centerX-bounds.width()/(2*savedView.zoom*density),savedView.centerY-bounds.height()/(2*savedView.zoom*density))
        assertReadingContext(expected)
        tap("quick-study");assertReadingContext(expected);tap("study-close");assertReadingContext(expected)
        compose.activityRule.scenario.recreate();saved(book,0);assertReadingContext(expected)
        assertTrue(strokes(book).isEmpty())
    }

}
