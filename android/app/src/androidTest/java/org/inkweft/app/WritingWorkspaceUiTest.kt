// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class WritingWorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun saved(n:Int?=null){compose.waitUntil(10_000){runCatching{val node=compose.onNodeWithTag("ink-status");node.assertTextContains("已提交",substring=true);if(n!=null)node.assertTextContains("$n 笔",substring=true)}.isSuccess}}
    private fun create():String {
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val name="书写工作台-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput(name);compose.onNodeWithTag("create-note").performClick();saved(0)
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==name}.id
    }
    private fun finger(){compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick()}
    private fun draw(){compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.3f),Offset(width*.5f,height*.5f),200)}}
    private fun mode(){compose.onNodeWithTag("document-more").performClick();compose.onNodeWithTag("toggle-continuous").performClick();compose.waitForIdle()}
    private fun shot(name:String){val bmp=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}}
    @Test fun tabsKeepInkAndClosingOnlyClosesTheTab(){
        val a=create();finger();draw();saved(1)
        compose.onNodeWithTag("tabs-open-library").performClick();val b=create();finger();draw();draw();saved(2)
        compose.onNodeWithTag("notebook-tab-$a").performScrollTo().performClick();saved(1)
        compose.onNodeWithTag("notebook-tab-$b").performScrollTo().performClick();saved(2)
        shot("notebook-tabs.png")
        compose.onNodeWithTag("close-tab-$b").performScrollTo().performClick();saved(1)
        compose.onNodeWithTag("notebook-tab-$b").assertDoesNotExist()
        assertNotNull(runBlocking{app.repository.read(b)});assertEquals(2,runBlocking{app.inkRepository.read(b).strokes.size})
        compose.activityRule.scenario.recreate();saved(1)
    }
    @Test fun continuousFingerScrollAndStylusWriteStayOnTheirOwnPage(){
        val book=create();compose.onNodeWithTag("add-page").performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithTag("confirm-insert-pages").performClick();saved()
        val pages=runBlocking{app.pages.observe(book).first()};assertEquals(3,pages.size)
        compose.onNodeWithTag("page-directory").performClick();compose.onNodeWithTag("jump-page-1").performClick();saved();mode()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipeUp(durationMillis=450)}
        compose.waitForIdle();pages.forEach{assertTrue(runBlocking{app.inkRepository.read(it.id).strokes}.isEmpty())}
        // Put the boundary into view: both pages must coexist, instead of replacing one canvas.
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(1)
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.3f),Offset(centerX,height*.65f),500)}
        compose.waitForIdle();compose.onNodeWithTag("continuous-page-1").assertIsDisplayed();compose.onNodeWithTag("continuous-page-2").assertIsDisplayed();shot("continuous-page-boundary.png")
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(1);compose.waitForIdle()
        val page=pages[1]
        compose.waitUntil(10_000){app.navigationReady.value}
        compose.runOnIdle{
            val view=checkNotNull(compose.activity.window.decorView.findViewWithTag<InkCanvasView>("ink-page-${page.id}"))
            val time=SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).forEachIndexed{i,action->
                val p=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val c=MotionEvent.PointerCoords().apply{x=view.width*(.3f+i*.1f);y=80f+i*30;pressure=.5f;size=.1f}
                val e=MotionEvent.obtain(time,time+i*40L,action,1,arrayOf(p),arrayOf(c),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try{assertTrue(view.dispatchTouchEvent(e))}finally{e.recycle()}
            }
        }
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(page.id).strokes.size}==1};saved(1)
        assertTrue(runBlocking{app.inkRepository.read(pages[0].id).strokes}.isEmpty());assertTrue(runBlocking{app.inkRepository.read(pages[2].id).strokes}.isEmpty())
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();saved(0)
        compose.onNodeWithTag("ink-redo").performScrollTo().performClick();saved(1)
        mode();saved(1);compose.onNodeWithTag("next-page").performClick();saved(0);finger();draw();saved(1)
        assertEquals(1,runBlocking{app.inkRepository.read(pages[2].id).strokes.size});assertEquals(1,runBlocking{app.inkRepository.read(page.id).strokes.size})
        mode();compose.activityRule.scenario.recreate();compose.onNodeWithTag("continuous-pages").assertExists()
    }
    @Test fun settingsSaveTagsAndExposeLassoAndToolbarPlacement(){
        val id=create();compose.onNodeWithTag("document-more").performClick();compose.onNodeWithTag("document-settings").performClick()
        compose.onNodeWithTag("continuous-setting").performClick();shot("document-reading-settings.png")
        compose.onNodeWithTag("settings-tags").performScrollTo().performClick();compose.onNodeWithTag("notebook-tags").performTextInput("课程,复习");compose.onNodeWithTag("save-notebook-tags").performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(id).tags}.contains("复习")}
        compose.onNodeWithTag("continuous-pages").assertExists()
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("selection-tools").performClick()
        compose.onNodeWithTag("selection-lasso").assertIsSelected();compose.onNodeWithTag("continuous-pages").assertDoesNotExist()
        compose.onNodeWithTag("ink-tool-0").performClick();compose.onNodeWithTag("ink-more").performClick();compose.onNodeWithTag("toolbar-position").performClick()
        assertTrue(compose.activity.getSharedPreferences("inkweft-editor",0).getBoolean("toolbar-bottom",false))
        // Restore the shared preference used by subsequent tests.
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("toolbar-position").performClick()
    }
    @Test fun closingDirtyTextTabPreservesTheDraft(){
        val id=create();compose.onNodeWithTag("document-more").performClick();compose.onNodeWithTag("mode-text").performClick()
        compose.onNodeWithTag("note-body").performTextInput("保留未保存草稿")
        compose.onNodeWithTag("close-tab-$id").performScrollTo().performClick()
        compose.onNodeWithTag("notebook-tab-$id").assertExists();compose.onNodeWithTag("note-body").assertTextContains("保留未保存草稿",substring=true)
    }
}
