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
    private fun seed():String {
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
            book
        }
        val note=runBlocking{checkNotNull(app.repository.read(book))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();saved(book,0)
        compose.runOnIdle{canvas().fitWidth()};compose.waitForIdle()
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
    private fun shot(name:String){compose.waitForIdle();val bmp=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}}
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
        repeat(3){tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed();tap("study-close")}
        tap("quick-study");shot("production-map-landscape.png");tap("study-close")
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
            tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed()
            val window=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
            val page=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("Opening a map must retain a useful source region",window.top-page.top>=page.height*.25f)
            shot("production-map-narrow-font16.png");tap("study-close")
            shot("production-writing-narrow-font16.png")
            val before=strokes(book).map{InkStrokeCodec.encode(it).toList()};compose.activityRule.scenario.recreate();saved(book,1);assertEquals(before,strokes(book).map{InkStrokeCodec.encode(it).toList()})
        }finally{shell("settings put system font_scale 1.0");shell("wm size reset");compose.activityRule.scenario.recreate()}
    }
}
