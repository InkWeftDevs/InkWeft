// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** One synthetic landscape capture; mechanical checks do not constitute visual acceptance. */
class WritingStudyReviewTest {
    @get:Rule val compose = createAndroidComposeRule<WritingStudyReviewActivity>()
    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    @Test fun landscapeWritingCapture() {
        try {
            shell("wm size 1200x800"); shell("wm density 160"); shell("settings put system font_scale 1.0")
            compose.activityRule.scenario.recreate(); compose.waitForIdle()
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            fun tutorialButton(): AccessibilityNodeInfo? = automation.windows.mapNotNull { it.root }
                .flatMap { it.findAccessibilityNodeInfosByText("Got it") }.firstOrNull()
            // A fresh AOSP emulator shows a one-time system tutorial for immersive pages.
            // Confirm the actual system button; do not crop or paint over the screenshot.
            automation.waitForIdle(500,15_000)
            tutorialButton()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitForIdle()
            val editor=compose.onNodeWithTag("study-editor").fetchSemanticsNode().boundsInRoot
            val paper=compose.onNodeWithTag("study-paper").fetchSemanticsNode().boundsInRoot
            val nav=compose.onNodeWithTag("study-document-bar").fetchSemanticsNode().boundsInRoot
            val tools=compose.onNodeWithTag("study-toolbar").fetchSemanticsNode().boundsInRoot
            assertEquals(48f,nav.height,.5f); assertEquals(56f,tools.height,.5f)
            assertTrue(paper.width >= editor.width*.95f)
            assertTrue(paper.top >= tools.bottom)
            compose.onNodeWithTag("study-color-0").performClick()
            compose.onNodeWithTag("study-width-4").performClick()
            compose.onNodeWithTag("study-ink").performTouchInput { swipe(Offset(50f,paper.height-30f),Offset(180f,paper.height-25f),200) }
            compose.runOnIdle { assertEquals(1,compose.activity.model.strokes.size); assertEquals(4f,compose.activity.model.strokes.single().width) }
            compose.onNodeWithTag("study-undo").performClick()
            compose.onNodeWithTag("study-redo").performClick()
            compose.runOnIdle { assertEquals(1,compose.activity.model.strokes.size) }
            compose.onNodeWithTag("study-undo").performClick()
            compose.onNodeWithTag("study-color-1").performClick()
            compose.onNodeWithTag("study-width-2").performClick()
            compose.runOnIdle { assertTrue(compose.activity.model.strokes.isEmpty()); compose.activity.model.redo.clear() }
            compose.waitForIdle()
            automation.waitForIdle(500,15_000)
            tutorialButton()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            automation.waitForIdle(500,15_000)
            assertNull("System tutorial must not cover the page",tutorialButton())
            val bitmap=checkNotNull(automation.takeScreenshot())
            try { File(compose.activity.getExternalFilesDir(null),"writing-study-landscape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        } finally {
            shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0")
        }
    }
}
