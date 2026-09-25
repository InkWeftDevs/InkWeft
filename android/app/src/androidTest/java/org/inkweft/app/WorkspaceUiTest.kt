// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    private fun create(world:Boolean){waitForShelf();compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("new-title").performTextInput((if(world)"知识草稿 · 无界"else"微积分 · 随手推导")+" · "+UUID.randomUUID().toString().take(6));if(world)compose.onNodeWithTag("create-world").performClick();compose.onNodeWithTag("create-note").performClick();saved(0)
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==false}
        compose.waitForIdle()
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
    @Test fun realBoardNegativeCoordinatesPanZoomAndReopen(){
        create(true);compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        compose.onNodeWithTag("ink-finger").performScrollTo().performClick()
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
        compose.onNodeWithTag("fit-content").performClick();shot("workspace-board.png")
        val reread=runBlocking{app.inkRepository.read(note.id).strokes.map{it.stroke}}
        assertEquals(paths.single().samples,reread.single().samples)
        compose.onNodeWithTag("back-library").performClick()
    }
    @Test fun libraryHasRealSearchFilterFavoriteAndRecycle(){
        waitForShelf()
        val prefix="A2-"+UUID.randomUUID().toString().take(5)
        val a=runBlocking{app.workspaceRepository.create("$prefix 原文笔记",false,PaperStyle.RULED)}
        val b=runBlocking{app.workspaceRepository.create("$prefix 思维草稿",true,PaperStyle.DOTS)}
        compose.waitUntil(10_000){compose.onAllNodesWithText("$prefix 思维草稿").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("library-search").performTextInput(prefix)
        compose.onNodeWithTag("library-type-board").performClick();compose.onNodeWithText("$prefix 思维草稿").assertExists();compose.onNodeWithText("$prefix 原文笔记").assertDoesNotExist()
        compose.onNodeWithTag("note-menu-${b.id}").performClick();compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(b.id).favorite}}
        compose.onNodeWithTag("note-menu-${b.id}").performClick();compose.onNodeWithText("移入回收站",useUnmergedTree=true).performClick();compose.onNodeWithText("移入回收站",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(b.id).trashedAt!=null}}
        runBlocking{val row=app.workspaceRepository.get(b.id);assertTrue(app.workspaceRepository.organize(b.id,row.revision,"数学","复习",true,false))}
        compose.onNodeWithTag("library-search").performTextClearance();compose.onNodeWithTag("library-type-all").performClick();shot("workspace-library.png")
        assertNotNull(runBlocking{app.repository.read(a.id)})
    }
    @Test fun fitModesAndPaperChangesDoNotRewriteSamples(){
        create(false);compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        val n=runBlocking{app.repository.observeNotes().first()}.first{it.id==createdId}
        val before=runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples}
        compose.onNodeWithTag("fit-page").performClick();var small=0.0;compose.runOnIdle{small=canvas().snapshotViewport().zoom}
        compose.onNodeWithTag("fit-width").performClick();compose.runOnIdle{assertTrue(canvas().snapshotViewport().zoom>=small)}
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithText("纸面 · 方格",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).paper==PaperStyle.GRID.ordinal}}
        assertEquals(before,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples});shot("workspace-page.png");compose.onNodeWithTag("back-library").performClick()
    }
    @Test fun compactLayoutStillCreatesBoardAndCanOpenDiagnostics(){
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        try {
            shell("wm size 900x1500");Thread.sleep(900);waitForShelf();create(true)
            compose.onNodeWithTag("open-diagnostics").performClick();compose.onNodeWithTag("diagnostics-dialog").assertExists();compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1);shot("workspace-narrow.png")
            compose.onNodeWithTag("back-library").performClick()
        }finally{shell("wm size 1920x1200");Thread.sleep(700)}
    }
}
