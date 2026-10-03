// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.inkweft.app.ui.designsystem.InkTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StudyWindowGeometryUiTest {
    @get:Rule val compose = createComposeRule()
    private val book = "window-geometry-${UUID.randomUUID()}"
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("inkweft-study-window", Context.MODE_PRIVATE)

    @After fun clearWindowPreferences() {
        prefs.edit().remove("$book-mode").remove("$book-x").remove("$book-y").commit()
    }

    private fun window(mode: StudyWindowMode, initiallyMinimized: Boolean, initiallyReading: Boolean) {
        prefs.edit().putString("$book-mode", mode.name).putFloat("$book-x", 1f)
            .putFloat("$book-y", 1f).commit()
        val minimized = mutableStateOf(initiallyMinimized)
        val reading = mutableStateOf(initiallyReading)
        val opened = mutableStateOf(true)
        compose.setContent {
            InkTheme.Content {
                BoxWithConstraints(Modifier.widthIn(max = 800.dp).heightIn(max = 600.dp).fillMaxSize()
                    .testTag("study-frame-host")) {
                    if (reading.value) TextButton(onClick = { reading.value = false },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("study-window-source-return")) {
                        Text("返回导图")
                    }
                    if (opened.value) FloatingStudyWindow(book, enabled = true,
                        minimized = minimized.value, onMinimize = { minimized.value = it },
                        docked = false, onDock = {}, close = { opened.value = false },
                        paneLayout = StudyPaneLayout(true, minOf(360.dp, maxWidth)),
                        topInset = 48.dp, paneActive = true, onActivate = {}, sourceReading = reading.value) {
                        val chrome = checkNotNull(LocalStudyWindowChrome.current)
                        Row(Modifier.fillMaxWidth().height(48.dp)) {
                            Spacer(Modifier.weight(1f))
                            chrome.controls()
                        }
                    }
                }
            }
        }
    }

    private fun assertFrameAndCloseStayInsideHost() {
        val host = compose.onNodeWithTag("study-frame-host").fetchSemanticsNode().boundsInRoot
        val panelNode = compose.onNodeWithTag("study-panel").fetchSemanticsNode()
        val origin = panelNode.positionInRoot
        val frame = Rect(origin.x, origin.y, origin.x + panelNode.size.width, origin.y + panelNode.size.height)
        val panel = panelNode.boundsInRoot
        assertTrue("Every animation frame must fit its host: frame=$frame host=$host",
            frame.left >= host.left && frame.top >= host.top &&
                frame.right <= host.right && frame.bottom <= host.bottom)
        val close = compose.onNodeWithTag("study-close").assertIsDisplayed().assertIsEnabled()
        close.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val node = close.fetchSemanticsNode()
        var owner = node
        while (owner.parent != null) owner = checkNotNull(owner.parent)
        val touch = node.touchBoundsInRoot.intersect(panel).intersect(host).intersect(owner.boundsInRoot)
        val minimum = with(compose.density) { 48.dp.toPx() } - .5f
        assertTrue("The actual close target must remain 48dp: touch=$touch panel=$panel host=$host",
            touch.width >= minimum && touch.height >= minimum)
    }

    private fun expandAndCheckEveryFrame(action: String) {
        assertFrameAndCloseStayInsideHost()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(action).performTouchInput { click() }
        // Inspect from the first expanded layout, not only after the travel animation settles.
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertFrameAndCloseStayInsideHost()
        }
        compose.onNodeWithTag("study-close").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("study-panel").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun returningFromSourceToFocusKeepsCloseTargetInsideEveryFrame() {
        window(StudyWindowMode.FOCUS, initiallyMinimized = false, initiallyReading = true)
        expandAndCheckEveryFrame("study-window-source-return")
    }

    @Test fun restoringBottomRightWindowKeepsExpandedFrameInsideEveryFrame() {
        window(StudyWindowMode.ORGANIZE, initiallyMinimized = true, initiallyReading = false)
        expandAndCheckEveryFrame("study-window-minimize")
    }
}
