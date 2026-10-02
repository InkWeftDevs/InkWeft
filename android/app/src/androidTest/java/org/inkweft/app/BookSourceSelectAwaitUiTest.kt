// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.EditPageResult
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** V60 real MainActivity/native UI draft; not executed by its author.
 * Frame controls hidden by an inspector are not activated through semantics.
 */
class BookSourceSelectAwaitUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var h: SelectAwaitTestSupport
    @Before fun prepare() { h = SelectAwaitTestSupport(compose); h.captureSettings() }
    @After fun restore() { if (::h.isInitialized) h.closeAndRestoreSettings() }

    @Test fun sourceWaitsForRealDifferentPageSelectionBeforeDismissingAndFocusing() {
        val f = h.seed(); val original = h.source(f.card)
        val before = h.authorStamp(f.note.id); val other = h.authorStamp(f.unrelated.id)
        h.openBody(f)
        val context = h.graph(f); val viewport = h.paperViewport()
        val gate = h.beginSourceGate(f, "different-page-success")
        try {
            // A real selection is holding the existing selection Mutex while
            // queued behind the SAME app Room transaction; it has not published.
            compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
            compose.onNodeWithTag("study-card-details").assertIsDisplayed()
            compose.onNodeWithTag("study-open-source").assertIsNotEnabled()
            assertEquals(viewport, h.paperViewport()); assertEquals(context, h.graph(f))
            h.assertCurrent(f, f.note.id); h.assertReadOnly(f)
        } finally { gate.finish() }
        h.settle(f)
        // This also rejects the V59 regression where selecting the new page
        // briefly becomes unready and destroys the caller before revealSource.
        h.assertFocusedSource(f, original)
        assertEquals(context, h.graph(f))
        h.assertAuthors(f, before, other, original)
        h.tapFooter("study-window-source-return")
        h.waitMap(f); h.openBody(f, second = true)
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        h.assertAuthors(f, before, other, original)
    }

    @Test fun genuinelyRecycledSourcePageOrBookKeepsDetailsAndSnapshotWithoutFocus() {
        val f = h.seed(); val other = h.authorStamp(f.unrelated.id)
        val original = h.source(f.card)
        h.openBody(f)
        val context = h.graph(f); val viewport = h.paperViewport()
        var expectedAfterPageFixture: List<String>? = null
        val pageGate = h.beginSourceGate(f, "page-recycled-at-commit")
        try {
            // These are real author operations on this synthetic fixture. Their
            // expected post-operation baseline is captured in the gate's owned
            // transaction before the queued source select is allowed to run.
            pageGate.finish {
                assertTrue(h.recycleSourcePage(f) is EditPageResult.Applied)
                expectedAfterPageFixture = h.stampInOwnedTransaction(f.note.id)
            }
        } finally { pageGate.finish() }
        h.settle(f)
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertIsDisplayed()
        }.isSuccess }
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("study-open-source").assertIsEnabled()
        compose.onNodeWithTag("study-window-source-return").assertDoesNotExist()
        h.assertCurrent(f, f.note.id); h.assertReadOnly(f)
        assertEquals(viewport, h.paperViewport()); assertEquals(context, h.graph(f))
        h.assertAuthors(f, checkNotNull(expectedAfterPageFixture), other, original)

        h.tapFooter("card-back"); h.openBody(f, second = true)
        val secondSource = h.source(f.secondCard); val secondContext = h.graph(f)
        var expectedAfterBookFixture: List<String>? = null
        val bookGate = h.beginSourceGate(f, "book-recycled-at-commit")
        try {
            bookGate.finish {
                h.recycleBook(f)
                expectedAfterBookFixture = h.stampInOwnedTransaction(f.note.id)
            }
        } finally { bookGate.finish() }
        h.settle(f)
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertIsDisplayed()
        }.isSuccess }
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        compose.onNodeWithTag("study-open-source").assertIsEnabled()
        compose.onNodeWithTag("study-window-source-return").assertDoesNotExist()
        h.assertCurrent(f, f.note.id); h.assertReadOnly(f)
        assertEquals(viewport, h.paperViewport()); assertEquals(secondContext, h.graph(f))
        h.assertAuthors(f, checkNotNull(expectedAfterBookFixture), other, secondSource)
    }

    @Test fun cancelBackRecreateAndOrdinaryPageOverrideCannotPublishAnOldSource() {
        val f = h.seed(); val original = h.source(f.card)
        val before = h.authorStamp(f.note.id); val other = h.authorStamp(f.unrelated.id)
        h.openBody(f)
        val firstGate = h.beginSourceGate(f, "card-back-new-card")
        try {
            h.tapFooter("card-back")
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            h.openBody(f, second = true)
            compose.onNodeWithTag("study-open-source").assertIsEnabled()
        } finally { firstGate.finish() }
        h.settle(f)
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        h.assertNoRevealOrError(f, f.note.id); h.assertAuthors(f, before, other, original)

        val backGate = h.beginSourceGate(f, "system-back")
        try {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("study-card-details").fetchSemanticsNodes().isEmpty() }
        } finally { backGate.finish() }
        h.settle(f)
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        h.assertNoRevealOrError(f, f.note.id); h.assertAuthors(f, before, other, original)

        h.openBody(f)
        val context = h.graph(f)
        val recreateGate = h.beginSourceGate(f, "activity-recreate")
        try {
            compose.activityRule.scenario.recreate()
            h.waitFor("card-full-body"); h.waitMap(f)
            h.assertCurrent(f, f.note.id); h.assertReadOnly(f)
            compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("study-open-source").assertIsEnabled() }.isSuccess }
        } finally { recreateGate.finish() }
        h.settle(f)
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        h.assertNoRevealOrError(f, f.note.id); assertEquals(context, h.graph(f))
        h.assertAuthors(f, before, other, original)

        val overrideGate = h.beginSourceGate(f, "ordinary-page-override")
        try {
            // Invoke the real ordinary-selection API; the inspector covers the
            // directory, so a semantics-only jump would not be a real touch.
            compose.runOnIdle { h.pages(f.note.id).select(f.secondPage) }
            compose.runOnIdle { assertEquals(f.secondPage, h.pages(f.note.id).ui.value.selectedId) }
        } finally { overrideGate.finish() }
        h.settle(f)
        h.assertNoRevealOrError(f, f.secondPage)
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("study-open-source").assertIsEnabled()
        h.assertAuthors(f, before, other, original)

        // A new physical source touch must still work after every cancellation.
        h.tapFooter("study-open-source")
        h.assertFocusedSource(f, original)
        h.assertAuthors(f, before, other, original)
    }
}
