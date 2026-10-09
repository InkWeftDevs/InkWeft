// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Presentation-only boundary and geometry checks. These do not mutate any
 * notebook, source snapshot, device density or global font preference.
 */
class StudyCapacityPanelUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun warningsStartAtEightyPercentForEachBudgetAndStayAbsentBelowIt() {
        val below = StudyCapacityUsage(activeNodes = (StudyGraph.MAX_NODES*4+4)/5-1, nodeRecords = (StudyGraph.MAX_RECORDS*4+4)/5-1, cards = (StudyCapacity.MAX_CARDS_PER_NOTEBOOK*4+4)/5-1)
        val usage = mutableStateOf<StudyCapacityUsage?>(below)
        val snapshot = mutableStateOf(StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)))
        var opened = 0
        compose.setContent {
            InkTheme.Content { StudyCapacityWarning(usage.value, snapshot.value, enabled = true, onOpen = { opened++ }) }
        }
        compose.onNodeWithTag("capacity-warning").assertDoesNotExist()
        val crossings = listOf(
            below.copy(activeNodes = (StudyGraph.MAX_NODES*4+4)/5) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below.copy(nodeRecords = (StudyGraph.MAX_RECORDS*4+4)/5) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below.copy(cards = (StudyCapacity.MAX_CARDS_PER_NOTEBOOK*4+4)/5) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5)),
        )
        crossings.forEach { (nextUsage, nextSnapshot) ->
            compose.runOnIdle { usage.value = nextUsage; snapshot.value = nextSnapshot }
            compose.onNodeWithTag("capacity-warning").assertIsDisplayed().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
            compose.runOnIdle { usage.value = below; snapshot.value = StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)) }
            compose.onNodeWithTag("capacity-warning").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(4, opened) }
        val full = listOf(
            below.copy(activeNodes = StudyGraph.MAX_NODES) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below.copy(nodeRecords = StudyGraph.MAX_RECORDS) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below.copy(cards = StudyCapacity.MAX_CARDS_PER_NOTEBOOK) to StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5-1)),
            below to StudySnapshotUsage(bytes = StudyCapacity.MAX_SNAPSHOT_BYTES),
        )
        full.forEach { (nextUsage, nextSnapshot) ->
            compose.runOnIdle { usage.value = nextUsage; snapshot.value = nextSnapshot }
            compose.onNodeWithTag("capacity-warning").assertIsDisplayed().assertTextContains("已满", substring = true)
        }
        compose.runOnIdle { usage.value = below; snapshot.value = StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES+1)) }
        compose.onNodeWithTag("capacity-warning").assertIsDisplayed().assertTextContains("超过上限", substring = true)
        compose.runOnIdle { usage.value = null; snapshot.value = StudySnapshotUsage(failed = true) }
        compose.onNodeWithTag("capacity-warning").assertDoesNotExist()
    }

    @Test fun narrowLargeTypePanelScrollsToFullTouchTargetsAndRetriesSnapshotFailure() {
        val snapshot = mutableStateOf(StudySnapshotUsage(failed = true))
        val actions = mutableListOf<StudyCapacityAction>()
        var retries = 0
        val closed = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                InkTheme.Content {
                    Box(Modifier.widthIn(max = 187.5.dp).heightIn(max = 480.dp).fillMaxSize().testTag("capacity-test-host")) {
                        if (!closed.value) StudyCapacityPanel(embedded = true,
                            mapTitle = "用于窄窗口容量核对的命名导图，保留完整中文标题",
                            usage = StudyCapacityUsage((StudyGraph.MAX_NODES*4+4)/5, (StudyGraph.MAX_RECORDS*4+4)/5, (StudyCapacity.MAX_CARDS_PER_NOTEBOOK*4+4)/5, trashedCards = 40),
                            snapshot = snapshot.value, graphFailed = false,
                            navigationEnabled = true, createMapEnabled = true,
                            dismiss = { closed.value = true }, retry = { retries++ }, onAction = { actions.add(it) })
                    }
                }
            }
        }
        compose.onNodeWithTag("capacity-panel").assertIsDisplayed()
        compose.onNodeWithTag("capacity-map-active").assertTextContains("${(StudyGraph.MAX_NODES*4+4)/5} / ${StudyGraph.MAX_NODES}", substring = true)
        compose.onNodeWithTag("capacity-map-total").assertTextContains("${(StudyGraph.MAX_RECORDS*4+4)/5} / ${StudyGraph.MAX_RECORDS}", substring = true)
        compose.onNodeWithTag("capacity-book-cards").assertTextContains("${(StudyCapacity.MAX_CARDS_PER_NOTEBOOK*4+4)/5} / ${StudyCapacity.MAX_CARDS_PER_NOTEBOOK}", substring = true)
        val retry = compose.onNodeWithTag("capacity-retry").performScrollTo()
        assertFullTouchTarget("capacity-retry")
        retry.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, retries)
            snapshot.value = StudySnapshotUsage(bytes = (StudyCapacity.MAX_SNAPSHOT_BYTES*4/5))
        }
        compose.onNodeWithTag("capacity-snapshot-bytes").performScrollTo().assertIsDisplayed()
            .assertTextEquals("204.80 / 256.00 MB · 接近上限")
        listOf(
            "capacity-open-map" to StudyCapacityAction.MAP,
            "capacity-open-cards" to StudyCapacityAction.CARDS,
            "capacity-open-trash" to StudyCapacityAction.TRASH,
            "capacity-new-map" to StudyCapacityAction.NEW_MAP,
        ).forEach { (tag, action) ->
            compose.onNodeWithTag(tag).performScrollTo()
            assertFullTouchTarget(tag)
            compose.onNodeWithTag(tag).performTouchInput { click() }
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        assertFullTouchTarget("capacity-close")
        compose.onNodeWithTag("capacity-close").performTouchInput { click() }
        compose.runOnIdle { assertTrue(closed.value) }
        compose.onNodeWithTag("capacity-panel").assertDoesNotExist()
    }

    private fun assertFullTouchTarget(tag: String) {
        val target = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        target.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val node = target.fetchSemanticsNode()
        val panel = compose.onNodeWithTag("capacity-panel").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("capacity-test-host").fetchSemanticsNode().boundsInRoot
        var owner = node
        while (owner.parent != null) owner = checkNotNull(owner.parent)
        val visible = node.touchBoundsInRoot.intersect(panel).intersect(host).intersect(owner.boundsInRoot)
        val minimum = with(compose.density) { 48.dp.toPx() } - .5f
        assertTrue("$tag needs a complete 48dp touch target inside the panel: $visible",
            visible.width >= minimum && visible.height >= minimum)
    }
}
