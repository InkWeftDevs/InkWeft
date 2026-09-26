// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Emulator interactions, not stylus/thermal proof. All note content is synthetic. */
class WorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun waitForShelf(){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private var createdId=""
    private fun settleKeyboard(){
        compose.activityRule.scenario.onActivity{activity->
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window,activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        compose.waitForIdle()
    }
    private fun create(world:Boolean){waitForShelf();compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput((if(world)"知识草稿 · 无界"else"微积分 · 随手推导")+" · "+UUID.randomUUID().toString().take(6));if(world)compose.onNodeWithTag("create-world").performClick();compose.onNodeWithTag("create-note").performClick();saved(0)
        settleKeyboard()
        createdId=runBlocking{app.repository.observeNotes().first()}.first().id
    }
    private fun saved(n:Int){try{compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true).assertTextContains("$n 笔",substring=true)}.isSuccess}}catch(error:Throwable){
        runCatching{shot("workspace-failure.png")}
        runCatching{val data=runBlocking{app.diagnostics.bundle()};File(compose.activity.getExternalFilesDir(null),"workspace-failure-diagnostics.zip").writeBytes(data)}
        throw error
    }}
    private fun draw(){compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.25f,height*.30f),Offset(width*.44f,height*.56f),260)}}
    private fun canvas():InkCanvasView {
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun shot(name:String){compose.waitForIdle();val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());File(compose.activity.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    private fun assertViewportClip(world:Boolean){
        compose.runOnIdle{
            val view=InkCanvasView(compose.activity)
            view.configure(world,PaperStyle.GRID,null);view.layout(0,0,160,220)
            val image=Bitmap.createBitmap(320,340,Bitmap.Config.ARGB_8888)
            try{
                image.eraseColor(Color.MAGENTA)
                val target=Canvas(image);target.translate(60f,50f);view.draw(target)
                assertEquals("view draw must not fill parent above",Color.MAGENTA,image.getPixel(80,20))
                assertEquals("view draw must not fill parent below",Color.MAGENTA,image.getPixel(80,290))
                assertEquals("view draw must not fill parent left",Color.MAGENTA,image.getPixel(20,80))
                assertEquals("view draw must not fill parent right",Color.MAGENTA,image.getPixel(250,80))
                assertNotEquals("the viewport itself must render",Color.MAGENTA,image.getPixel(100,100))
                target.drawColor(Color.CYAN)
                assertEquals("clip must be restored for parent draw",Color.CYAN,image.getPixel(10,10))
            }finally{image.recycle()}
        }
    }
    private fun assertToolbarPixels(){
        // Semantics can remain clickable under an overflowing AndroidView. Inspect
        // actual screen pixels above its viewport as well as asserting controls.
        compose.onNodeWithTag("back-library").assertIsDisplayed()
        compose.onNodeWithTag("ink-tool-0").assertIsDisplayed()
        compose.waitForIdle()
        var top=0;var left=0;var right=0
        compose.runOnIdle{val v=canvas();val location=IntArray(2);v.getLocationOnScreen(location);top=location[1];left=location[0];right=left+v.width}
        val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{
            val y0=(top-110).coerceAtLeast(35);val y1=(top-4).coerceAtMost(image.height)
            var dark=0
            for(y in y0 until y1)for(x in left.coerceAtLeast(0) until right.coerceAtMost(image.width)){
                val color=image.getPixel(x,y)
                if(Color.red(color)<150 && Color.green(color)<165 && Color.blue(color)<160)dark++
            }
            assertTrue("toolbar text/icons were painted over by the canvas: $dark visible pixels",dark>80)
        }finally{image.recycle()}
    }
    @Test fun realBoardNegativeCoordinatesPanZoomAndReopen(){
        create(true);assertViewportClip(true);compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.4f),Offset(width*.7f,height*.65f),250)}
        compose.onNodeWithTag("zoom-in").performClick()
        var before=CanvasViewport();compose.runOnIdle{before=canvas().snapshotViewport()}
        val notes=runBlocking{app.repository.observeNotes().first()}
        val note=notes.first{it.id==createdId}
        val paths=runBlocking{app.inkRepository.read(note.id).strokes.map{it.stroke}}
        assertTrue(paths.single().world);assertTrue(paths.single().samples.any{it.x<0||it.y<0})
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(note.id).zoom>0}}
        compose.activityRule.scenario.recreate();saved(1)
        compose.runOnIdle{assertEquals(before,canvas().snapshotViewport())}
        compose.onNodeWithTag("fit-content").performClick();assertToolbarPixels();shot("workspace-board.png")
        val reread=runBlocking{app.inkRepository.read(note.id).strokes.map{it.stroke}}
        assertEquals(paths.single().samples,reread.single().samples)
        compose.onNodeWithTag("back-library").performClick()
    }
    @Test fun libraryHasRealSearchFilterFavoriteAndRecycle(){
        try{
            waitForShelf()
            val prefix="A2-"+UUID.randomUUID().toString().take(5)
            val a=runBlocking{app.workspaceRepository.create("$prefix 原文笔记",false,PaperStyle.RULED)}
            val b=runBlocking{app.workspaceRepository.create("$prefix 思维草稿",true,PaperStyle.DOTS)}
            compose.waitUntil(10_000){compose.onAllNodesWithText("$prefix 思维草稿").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("library-search").performTextInput(prefix)
            compose.onNodeWithTag("library-type-board").performClick();compose.onNodeWithText("$prefix 思维草稿").assertExists();compose.onNodeWithText("$prefix 原文笔记").assertDoesNotExist()
            compose.onNodeWithTag("note-menu-${b.id}").performClick()
            // The product menu, not a test shell command, dismisses the search IME.
            compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
            compose.onNodeWithTag("favorite-note-${b.id}").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(b.id).favorite}}
            compose.onNodeWithTag("note-menu-${b.id}").performClick()
            compose.waitUntil(10_000){compose.onAllNodesWithText("取消收藏",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("trash-note-${b.id}").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("trash-note-dialog").assertIsDisplayed()
            // Distinct confirmation target: never click the same menu label twice.
            compose.onNodeWithTag("confirm-trash-${b.id}").assertIsDisplayed().performClick()
            compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(b.id).trashedAt!=null}}
            runBlocking{val row=app.workspaceRepository.get(b.id);assertTrue(app.workspaceRepository.organize(b.id,row.revision,"数学","复习",true,false))}
            compose.onNodeWithTag("library-search").performTextClearance();compose.onNodeWithTag("library-type-all").performClick();settleKeyboard();shot("workspace-library.png")
            assertNotNull(runBlocking{app.repository.read(a.id)})
        }catch(error:Throwable){
            runCatching{shot("shelf-menu-failure.png")}
            runCatching{File(compose.activity.getExternalFilesDir(null),"shelf-menu-semantics.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())}
            throw error
        }
    }
    @Test fun fitModesAndPaperChangesDoNotRewriteSamples(){
        create(false);assertViewportClip(false);compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        val n=runBlocking{app.repository.observeNotes().first()}.first{it.id==createdId}
        val before=runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples}
        compose.onNodeWithTag("fit-page").performClick();var small=0.0;compose.runOnIdle{small=canvas().snapshotViewport().zoom}
        compose.onNodeWithTag("fit-width").performClick();compose.runOnIdle{assertTrue(canvas().snapshotViewport().zoom>=small)}
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithText("纸面 · 方格",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).paper==PaperStyle.GRID.ordinal}}
        assertEquals(before,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples});assertToolbarPixels();shot("workspace-page.png");compose.onNodeWithTag("back-library").performClick()
    }
    @Test fun compactLayoutStillCreatesBoardAndCanOpenDiagnostics(){
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        try {
            shell("wm size 900x1500");Thread.sleep(900);waitForShelf();create(true)
            compose.onNodeWithTag("document-more").performClick();compose.onNodeWithTag("open-diagnostics").performClick();compose.onNodeWithTag("diagnostics-dialog").assertExists();compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1);shot("workspace-narrow.png")
            compose.onNodeWithTag("back-library").performClick()
        }finally{shell("wm size 1920x1200");Thread.sleep(700)}
    }
}
