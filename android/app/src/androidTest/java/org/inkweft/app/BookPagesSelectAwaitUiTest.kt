// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real retained ViewModel + real application Room queue, not coroutine mocks.
 * The Activity also verifies that the read lock allows browsing selections.
 */
class BookPagesSelectAwaitUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var h: SelectAwaitTestSupport
    @Before fun prepare() { h = SelectAwaitTestSupport(compose); h.captureSettings() }
    @After fun restore() { if (::h.isInitialized) h.closeAndRestoreSettings() }

    @Test fun canceledOrSupersededAwaitCannotOverrideImmediateOrdinarySelection() {
        val f = h.seed(); val before = h.authorStamp(f.note.id); val other = h.authorStamp(f.unrelated.id)
        val context = h.graph(f); val viewport = h.paperViewport()
        val cancelGate = h.WriterGate("vm-caller-cancel")
        try {
            cancelGate.awaitHeld()
            val request = h.startAwait(f, f.sourcePage)
            h.awaitSelectionHeld(f)
            assertFalse(request.isCompleted)
            h.assertCurrent(f, f.note.id)
            compose.runOnIdle { assertFalse(h.pages(f.note.id).ui.value.busy); assertNull(h.pages(f.note.id).ui.value.error) }
            compose.runOnIdle { request.cancel() }
            cancelGate.finish()
            h.assertCanceled(request)
        } finally { cancelGate.finish() }
        h.settle(f)
        h.assertNoRevealOrError(f, f.note.id)
        assertEquals(context, h.graph(f)); assertEquals(viewport, h.paperViewport())
        h.assertAuthors(f, before, other)

        val overrideGate = h.WriterGate("vm-newer-ordinary-selection")
        try {
            overrideGate.awaitHeld()
            val oldRequest = h.startAwait(f, f.sourcePage)
            h.awaitSelectionHeld(f)
            assertFalse(oldRequest.isCompleted)
            compose.runOnIdle {
                h.pages(f.note.id).select(f.secondPage)
                assertEquals("Ordinary selection must remain immediate", f.secondPage, h.pages(f.note.id).ui.value.selectedId)
            }
            overrideGate.finish()
            h.assertCanceled(oldRequest)
        } finally { overrideGate.finish() }
        h.settle(f)
        h.assertNoRevealOrError(f, f.secondPage)
        assertEquals(f.secondPage, runBlocking { h.app.workspaceRepository.get(f.note.id).selectedPageId })
        h.assertAuthors(f, before, other)
    }

    @Test fun recycledBookIsRejectedEvenWhenTheCachedPageDirectoryStillContainsTarget() {
        val f = h.seed(); val other = h.authorStamp(f.unrelated.id)
        var expectedAfterFixture: List<String>? = null
        val gate = h.WriterGate("vm-cached-directory-recycled-book")
        try {
            gate.awaitHeld()
            compose.runOnIdle { assertTrue(h.pages(f.note.id).ui.value.pages.any { it.id == f.sourcePage }) }
            val request = h.startAwait(f, f.sourcePage)
            h.awaitSelectionHeld(f)
            assertFalse(request.isCompleted)
            gate.finish {
                h.recycleBook(f)
                expectedAfterFixture = h.stampInOwnedTransaction(f.note.id)
            }
            assertFalse("Genuine current unavailable book is false, not successful navigation", h.awaitResult(request))
        } finally { gate.finish() }
        h.settle(f)
        // Workspace recycling does not invalidate the page-list identity cache.
        compose.runOnIdle { assertTrue(h.pages(f.note.id).ui.value.pages.any { it.id == f.sourcePage }) }
        h.assertNoRevealOrError(f, f.note.id)
        assertEquals(f.note.id, runBlocking { h.app.workspaceRepository.get(f.note.id).selectedPageId })
        h.assertAuthors(f, checkNotNull(expectedAfterFixture), other)
    }
}
