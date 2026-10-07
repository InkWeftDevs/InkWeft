// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Debug-variant captures of the runnable proposal; not production editor acceptance. */
class EditorDesignReviewTest {
    @get:Rule val compose = createAndroidComposeRule<EditorDesignReviewActivity>()
    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }
    private fun shot(name: String) {
        compose.waitForIdle()
        // Window insets can settle after a wm-size change; wait for the native window too.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 15_000)
        listOf("review-paper", "review-map-panel", "review-map-body", "review-source").forEach { tag ->
            val bounds = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if(bounds != null) println("DESIGN_BOUNDS $name $tag $bounds")
        }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(compose.activity.getExternalFilesDir(null), "design-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }

    @Test fun landscapeThreePaths() = scene("1200x800", "landscape", true)
    @Test fun portraitThreePaths() = scene("800x1200", "portrait", false)
    @Test fun narrowComparison() = scene("375x800", "narrow", false)

    private fun scene(size: String, label: String, wide: Boolean) {
        try {
            shell("wm size $size"); shell("wm density 160"); shell("settings put system font_scale 1.0")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            shot("$label-writing")
            if(wide) {
                tap("review-tool-pen")
                compose.onNodeWithTag("review-pen-properties").assertIsDisplayed()
                shot("$label-pen-properties")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
            }
            // Actual native pointer input and undo, while document fixture stays isolated.
            compose.onNodeWithTag("review-ink").performTouchInput { swipe(Offset(65f, 60f), Offset(220f, 90f), 250) }
            compose.runOnIdle { assertEquals(1, compose.activity.model.strokes.size) }
            tap("review-undo")
            compose.runOnIdle { assertTrue(compose.activity.model.strokes.isEmpty()) }
            tap("review-map")
            val paper = compose.onNodeWithTag("review-paper").fetchSemanticsNode().boundsInRoot
            val map = compose.onNodeWithTag("review-map-panel").fetchSemanticsNode().boundsInRoot
            val source = compose.onNodeWithTag("review-source").fetchSemanticsNode().boundsInRoot
            assertTrue("Source action stays inside its panel", source.top >= map.top && source.bottom <= map.bottom)
            if (wide) { assertTrue(paper.right <= map.left); assertTrue(paper.width >= 600f) }
            else { assertTrue(paper.bottom <= map.top); assertTrue(paper.height > 200f) }
            shot("$label-map")
            tap("review-source")
            compose.runOnIdle { assertTrue(compose.activity.model.sourceFocused) }
            if(wide) shot("$label-map-source")
            if(!wide) {
                tap("review-view-导图")
                compose.onNodeWithTag("review-paper").assertDoesNotExist()
                tap("review-view-对照")
            }
            tap("review-map-close")
            tap("review-layers")
            val current = compose.activity.model.layers.currentId
            tap("review-layer-辅助草稿")
            compose.runOnIdle { assertEquals(current, compose.activity.model.layers.currentId) }
            shot("$label-layers")
            tap("review-layer-重点批注")
            compose.onNodeWithTag("review-write-layer").performScrollTo()
            tap("review-write-layer")
            compose.runOnIdle { assertNotEquals(current, compose.activity.model.layers.currentId) }
            tap("review-layer-close")
            compose.onNodeWithTag("review-ink").performTouchInput { swipe(Offset(65f, 60f), Offset(220f, 90f), 250) }
            compose.runOnIdle { assertEquals(compose.activity.model.layers.currentId, compose.activity.model.strokes.last().layer) }
            shot("$label-resume-writing")
        } finally {
            shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0")
        }
    }
}
