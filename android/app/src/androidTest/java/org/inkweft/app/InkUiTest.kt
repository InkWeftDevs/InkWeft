// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android UI/Ink renderer in an emulator; touch injection is NOT Pencil3 validation. */
class InkUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun newNote() {
        compose.waitUntil(10_000){runCatching { compose.onNodeWithTag("new-note").assertIsEnabled() }.isSuccess}
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-title").performTextInput("手写测试 "+UUID.randomUUID().toString().take(8))
        compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(10_000){runCatching { compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true) }.isSuccess}
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick()
    }
    private fun count(n: Int) {
        compose.waitUntil(10_000){runCatching { compose.onNodeWithTag("ink-status").assertTextContains("$n 笔",substring=true) }.isSuccess}
    }
    private fun draw() {
        compose.onNodeWithTag("ink-surface").performTouchInput {
            swipe(Offset(width*.4f,height*.32f),Offset(width*.6f,height*.46f),250)
        }
    }
    @Test fun realTouchUndoRedoAndActivityRecreation() {
        newNote();draw();count(1)
        compose.onNodeWithTag("ink-tool-1").performScrollTo().performClick();draw();count(2)
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();count(1)
        compose.onNodeWithTag("ink-redo").performScrollTo().performClick();count(2)
        compose.activityRule.scenario.recreate();count(2)
        compose.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val out=File(compose.activity.getExternalFilesDir(null),"ink-ui-emulator.png")
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        compose.onNodeWithTag("back-library").performClick()
    }
    @Test fun pointerCancelDoesNotPersistAStroke() {
        newNote()
        compose.activityRule.scenario.onActivity { activity ->
            fun find(view: View):InkCanvasView? {
                if(view is InkCanvasView)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
                return null
            }
            val view=checkNotNull(find(activity.window.decorView))
            val time=SystemClock.uptimeMillis()
            for((action,delta) in listOf(MotionEvent.ACTION_DOWN to 0L,MotionEvent.ACTION_MOVE to 20L,MotionEvent.ACTION_CANCEL to 40L)) {
                val event=MotionEvent.obtain(time,time+delta,action,view.width*.5f,view.height*(.35f+delta*.001f),0)
                view.dispatchTouchEvent(event);event.recycle()
            }
        }
        compose.waitForIdle();count(0)
        draw();count(1)
        compose.onNodeWithTag("back-library").performClick()
    }
}
