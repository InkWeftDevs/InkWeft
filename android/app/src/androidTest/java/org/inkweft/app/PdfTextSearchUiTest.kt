// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.NoteDatabase
import org.inkweft.data.PageSearchRow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** PS56: synthetic PDFs, real MainActivity navigation and native canvas/input.
 * Screenshot calls follow successful assertions, without a separate screenshot run.
 */
class PdfTextSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private val alpha = "PS56ALPHA"
    private val beta = "PS56BETA"
    private fun id() = UUID.randomUUID().toString()

    @After fun closeProbe() { probeDatabase?.close(); probeDatabase = null }

    private data class Fixture(val note: Note, val pages: List<String>, val sourceHash: String)
    private data class AuthorStamp(
        val note: Note?, val contentHash: String,
        val inkHeads: List<Pair<String, Long>>, val objectHeads: List<Pair<String, Long>>,
        val indexes: Map<String, PageSearchRow?>, val receipts: List<Long>,
    )

    private fun ready() = compose.waitUntil(15_000) {
        runCatching { compose.onNodeWithTag("new-note").assertIsEnabled() }.isSuccess
    }

    /** Use the real repository signature, not a test-only import adapter. */
    private fun pdf(text: List<String>, manual: Map<Int, String> = emptyMap(), imageOnly: Boolean = false): Fixture {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        try {
            text.forEachIndexed { index, words ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(1000, 1414, index + 1).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
                page.canvas.drawColor(Color.WHITE)
                if (imageOnly) {
                    // The visible word is inside an image; it has no searchable PDF text layer.
                    val raster = Bitmap.createBitmap(1000, 1414, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(raster).apply { drawColor(Color.WHITE); drawText(words, 100f, 900f, paint) }
                        page.canvas.drawBitmap(raster, 0f, 0f, null)
                    } finally { raster.recycle() }
                } else {
                    page.canvas.drawText(words, 100f, 900f, paint)
                    page.canvas.drawText("source-page-${index + 1}", 100f, 1250f, paint)
                }
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
        val bytes = output.toByteArray()
        val source = PdfDocumentSource(bytes, text.size)
        val prepared = ContentTransfer.document("PS56 PDF " + id().take(8), source, ContentTransfer.hash(bytes))
        val note = runBlocking { app.libraryContent.import(ImportNotebook(id(), id(), prepared.sha256), prepared) }
        val pages = runBlocking { app.pages.activePages(note.id).map { it.id } }
        assertEquals(text.size, pages.size)
        runBlocking {
            // Fresh imports have empty ink at revision 0. Avoid depending on an OCR model.
            pages.forEachIndexed { index, page ->
                assertTrue(app.pages.saveSearchText(page, 0, manual[index].orEmpty(), method = "MANUAL"))
                val attached = checkNotNull(app.documents.read(page))
                assertEquals(source.sha256, attached.document.sha256)
                assertEquals(index, attached.page)
            }
        }
        return Fixture(note, pages, source.sha256)
    }

    private fun open(note: Note, single: Boolean = true) {
        compose.runOnIdle { ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note) }
        if (single) { compose.singlePageEditor(); compose.waitForSavedInk() }
        else compose.waitUntil(15_000) {
            app.navigationReady.value && compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(tag: String, unmerged: Boolean = false) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tap(tag: String, physical: Boolean = false) {
        compose.revealAction(tag)
        waitFor(tag)
        val node = compose.onNodeWithTag(tag)
        node.assertIsEnabled().assertIsDisplayed()
        if (physical) node.performTouchInput { click() } else node.performClick()
    }

    private fun search(term: String, physical: Boolean = false) {
        if (compose.onAllNodesWithTag("book-search-panel").fetchSemanticsNodes().isEmpty()) tap("book-search", physical)
        waitFor("book-search-query")
        compose.onNodeWithTag("book-search-query").performTextReplacement(term)
    }

    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity {
            val keyboard = checkNotNull(it.getSystemService(InputMethodManager::class.java))
            val roots = if(android.os.Build.VERSION.SDK_INT>=29)WindowInspector.getGlobalWindowViews()else listOf(it.window.decorView)
            roots.filter { root -> root.isAttachedToWindow }.forEach { root ->
                keyboard.hideSoftInputFromWindow(root.windowToken, 0)
            }
            it.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private fun closeSearch(physical: Boolean = false) {
        // The production title supplies this exact description; no test-only close tag.
        val close = compose.onNodeWithContentDescription("关闭查找页内文字")
        close.assertIsDisplayed()
        if (physical) close.performTouchInput { click() } else close.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-search-panel").fetchSemanticsNodes().isEmpty() }
    }

    private fun hit(page: String): SemanticsNodeInteraction {
        val tag = "book-search-hit-$page"
        waitFor("book-search-results")
        compose.onNodeWithTag("book-search-results").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun labels(page: String, pdf: Boolean, ink: Boolean) {
        hit(page)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("pdf-search-progress").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("book-search-pdf-$page", useUnmergedTree = true).fetchSemanticsNodes().size == (if (pdf) 1 else 0) &&
                compose.onAllNodesWithTag("book-search-ink-$page", useUnmergedTree = true).fetchSemanticsNodes().size == (if (ink) 1 else 0)
        }
        compose.onAllNodesWithTag("book-search-hit-$page", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("book-search-pdf-$page", useUnmergedTree = true).assertCountEquals(if (pdf) 1 else 0)
        compose.onAllNodesWithTag("book-search-ink-$page", useUnmergedTree = true).assertCountEquals(if (ink) 1 else 0)
    }

    private fun absentFromResults(page: String) {
        assertTrue("Another book's page must never be in this book's result list",
            runCatching { compose.onNodeWithTag("book-search-results")
                .performScrollToNode(hasTestTag("book-search-hit-$page")) }.isFailure)
    }

    private fun canvas(): InkCanvasView {
        fun find(view: View): InkCanvasView? {
            if (view is InkCanvasView && !view.preview && !view.embeddedPage && view.isShown) return view
            if (view is ViewGroup) repeat(view.childCount) { find(view.getChildAt(it))?.let { result -> return result } }
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }

    private fun assertPage(book: String, page: String, sourcePage: Int? = null, sourceHash: String? = null) {
        compose.waitForSavedInk()
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val provider = ViewModelProvider(compose.activity)
                provider[NotebookViewModel::class.java].ui.value.selectedId == book &&
                    provider["book-$book", BookPagesViewModel::class.java].ui.value.selectedId == page
            }
        }
        if (sourcePage != null) runBlocking {
            val source = checkNotNull(app.documents.read(page))
            assertEquals(sourcePage, source.page)
            assertEquals(sourceHash, source.document.sha256)
        }
    }

    private fun assertPdfFocus() {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val viewport = canvas().snapshotViewport()
                // The first matching word was drawn at x=100, baseline y=900.
                viewport.centerX in 95.0..420.0 && viewport.centerY in 820.0..960.0
            }
        }
        compose.runOnIdle {
            val view = canvas()
            val visible = view.snapshotViewport().visible(view.width.toDouble(), view.height.toDouble(),
                view.resources.displayMetrics.density.toDouble())
            assertTrue("Search must focus the native original-page region", visible.intersects(CanvasBounds(100.0, 850.0, 380.0, 910.0)))
        }
    }

    private fun stamp(note: Note): AuthorStamp = runBlocking {
        val pages = app.pages.activePages(note.id)
        val receipts = listOf(
            "SELECT COUNT(*) FROM ink_receipts WHERE noteId IN (SELECT id FROM notebook_pages WHERE notebookId=?)",
            "SELECT COUNT(*) FROM object_receipts WHERE pageId IN (SELECT id FROM notebook_pages WHERE notebookId=?)",
            "SELECT COUNT(*) FROM study_receipts WHERE notebookId=?",
            "SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?",
            "SELECT COUNT(*) FROM page_edit_receipts WHERE notebookId=?",
        ).map { sql -> probe.openHelper.readableDatabase.query(sql, arrayOf(note.id)).use { it.moveToFirst(); it.getLong(0) } }
        AuthorStamp(app.repository.read(note.id), ContentTransfer.hash(app.pages.exportBook(note.id).encode()),
            pages.map { it.id to app.inkRepository.read(it.id).revision },
            pages.map { it.id to app.pageObjects.read(it.id).revision },
            pages.associate { it.id to app.pages.searchText(it.id) }, receipts)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun pdfAndManualHitsMergeByPageAndOpenTheOriginalRegionInReadMode() {
        ready()
        val fixture = pdf(listOf("$alpha first origin", "$beta second origin", "$alpha third origin"),
            mapOf(0 to "manual $alpha same page", 1 to "manual $alpha other page"))
        val other = pdf(listOf("$alpha belongs to another book"), mapOf(0 to "manual $alpha private other book"))
        val before = stamp(fixture.note)
        val otherBefore = stamp(other.note)
        open(fixture.note, single = false)
        compose.runOnIdle {
            val readLock = ViewModelProvider(compose.activity)["read-lock-${fixture.note.id}", BookReadLockViewModel::class.java]
            assertTrue(readLock.request(true))
        }
        search(alpha)
        labels(fixture.pages[0], pdf = true, ink = true)
        labels(fixture.pages[1], pdf = false, ink = true)
        labels(fixture.pages[2], pdf = true, ink = false)
        absentFromResults(other.pages.single())
        hideKeyboard(); hit(fixture.pages[0]); shot("ps56-merged-results.png")
        hit(fixture.pages[0]).performClick()
        assertPage(fixture.note.id, fixture.pages[0], 0, fixture.sourceHash)
        compose.onNodeWithTag("continuous-pages").assertDoesNotExist()
        assertPdfFocus()
        compose.runOnIdle {
            val readLock = ViewModelProvider(compose.activity)["read-lock-${fixture.note.id}", BookReadLockViewModel::class.java]
            assertTrue(readLock.readOnly.value)
        }
        assertEquals("Read/search may not create author or PDF-derived OCR records", before, stamp(fixture.note))
        assertEquals(otherBefore, stamp(other.note))
        shot("ps56-focused-original.png")
        // Reopen through the actual document action: do not replace the query and hide lost state.
        tap("book-search")
        waitFor("book-search-query")
        compose.onNodeWithTag("book-search-query").assertTextContains(alpha, substring = true)
        labels(fixture.pages[0], pdf = true, ink = true)
        labels(fixture.pages[1], pdf = false, ink = true)
        labels(fixture.pages[2], pdf = true, ink = false)
        closeSearch()
        assertEquals(before, stamp(fixture.note))
    }

    @Test fun rotationRapidReplacementClearAndCloseDoNotPublishAnOldQuery() {
        ready()
        val fixture = pdf(List(24) { if (it == 23) "$beta newest query" else "$alpha obsolete query $it" })
        open(fixture.note)
        val before = stamp(fixture.note)
        search(alpha)
        compose.onNodeWithTag("pdf-search-stop").performClick()
        compose.onNodeWithText("PDF 查找已暂停").assertExists()
        compose.onNodeWithTag("pdf-search-retry").assertIsEnabled()
        compose.onNodeWithTag("book-search-query").performTextReplacement(beta)
        labels(fixture.pages.last(), pdf = true, ink = false)
        absentFromResults(fixture.pages.first())
        // The search term is trimmed. A raw-input-only edit must retain completed PDF hits.
        compose.onNodeWithTag("book-search-query").performTextReplacement("  $beta  ")
        labels(fixture.pages.last(), pdf = true, ink = false)
        val originalRequest = compose.activity.requestedOrientation
        val originalOrientation = compose.activity.resources.configuration.orientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation =
                if (originalOrientation == Configuration.ORIENTATION_PORTRAIT) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation != originalOrientation }
            waitFor("book-search-query")
            compose.onNodeWithTag("book-search-query").assertTextContains("  $beta  ", substring = true)
            labels(fixture.pages.last(), pdf = true, ink = false)
            compose.onNodeWithTag("book-search-query").performTextReplacement(alpha)
            compose.onNodeWithTag("book-search-query").performTextReplacement("PS56MISSING")
            compose.onNodeWithTag("book-search-query").performTextReplacement("")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("book-search-results").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("pdf-search-progress").fetchSemanticsNodes().isEmpty()
            }
            search(alpha); closeSearch()
            search(beta)
            labels(fixture.pages.last(), pdf = true, ink = false)
            absentFromResults(fixture.pages.first())
            assertEquals(before, stamp(fixture.note))
        } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = originalRequest } }
    }

    @Test fun returningToSearchRestoresTheLastPageResultWithoutAnotherSearchOrScroll() {
        ready()
        val fixture = pdf(List(24) { "$alpha context source page ${it + 1}" })
        open(fixture.note)
        val before = stamp(fixture.note)
        search(alpha)
        val lastPage = fixture.pages.last()
        // Initial search may publish partial hits; wait until a real scroll can reach the final row.
        compose.waitUntil(30_000) {
            runCatching { hit(lastPage).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        labels(lastPage, pdf = true, ink = false)
        assertTrue("The initial result context must be away from the first row", runCatching {
            compose.onNodeWithTag("book-search-hit-${fixture.pages.first()}").assertIsDisplayed()
        }.isFailure)
        hideKeyboard()
        hit(lastPage).performClick()
        assertPage(fixture.note.id, lastPage, 23, fixture.sourceHash)
        assertPdfFocus()
        assertEquals(before, stamp(fixture.note))

        // Reenter with one real action. No query replacement or test-driven result scrolling follows.
        tap("book-search")
        waitFor("book-search-query")
        compose.onNodeWithTag("book-search-query").assertTextContains(alpha, substring = true)
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("book-search-hit-$lastPage").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("book-search-pdf-$lastPage", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("book-search-ink-$lastPage", useUnmergedTree = true).assertCountEquals(0)
        assertTrue("Returning must retain a nonzero result context", runCatching {
            compose.onNodeWithTag("book-search-hit-${fixture.pages.first()}").assertIsDisplayed()
        }.isFailure)
        assertEquals(before, stamp(fixture.note))
        shot("ps56-context-return.png")
    }

    private fun edit(fixture: Fixture, page: String, kind: PageEditKind, stay: String, location: PageInsertLocation = PageInsertLocation.END) = runBlocking {
        val rows = app.pages.activePages(fixture.note.id)
        val result = app.pages.edit(EditPage(id(), fixture.note.id, page, kind,
            InsertPages.orderHash(rows.map { it.id }), app.pages.inkRevision(page), location = location, stayOnPageId = stay))
        assertTrue("Fixture page edit must complete", result is EditPageResult.Applied)
    }

    @Test fun reorderingAndRecyclingUseStablePageAndPdfSourceIdentity() {
        ready()
        val fixture = pdf(listOf("$alpha first", "$beta second", "$alpha third"))
        open(fixture.note); search(alpha)
        labels(fixture.pages[2], pdf = true, ink = false)
        edit(fixture, fixture.pages[2], PageEditKind.MOVE, fixture.pages[0], PageInsertLocation.START)
        compose.waitUntil(15_000) { runBlocking { app.pages.activePages(fixture.note.id).first().id } == fixture.pages[2] }
        compose.waitUntil(15_000) { runCatching { hit(fixture.pages[2]).assertTextContains("第 1 页", substring = true) }.isSuccess }
        hideKeyboard(); hit(fixture.pages[2]).performClick()
        assertPage(fixture.note.id, fixture.pages[2], 2, fixture.sourceHash)
        assertPdfFocus(); shot("ps56-reordered-source.png")
        search(alpha)
        edit(fixture, fixture.pages[0], PageEditKind.TRASH, fixture.pages[2])
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val bookPages = ViewModelProvider(compose.activity)["book-${fixture.note.id}", BookPagesViewModel::class.java]
                bookPages.ui.value.pages.none { it.id == fixture.pages[0] }
            }
        }
        compose.waitForIdle()
        absentFromResults(fixture.pages[0])
        val beforeNavigation = stamp(fixture.note)
        hit(fixture.pages[2]).performClick()
        assertPage(fixture.note.id, fixture.pages[2], 2, fixture.sourceHash)
        assertEquals(beforeNavigation, stamp(fixture.note))
    }

    @Test fun imageOnlyPdfIsExplicitAndManualHandwritingSearchStillWorks() {
        ready()
        val scan = pdf(listOf("$alpha visible in scanned image"), imageOnly = true)
        open(scan.note)
        val scanBefore = stamp(scan.note)
        search(alpha); waitFor("pdf-search-image-only", unmerged = true)
        compose.onNodeWithTag("pdf-search-image-only", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("book-search-hit-${scan.pages.single()}").assertCountEquals(0)
        assertEquals(scanBefore, stamp(scan.note))
        hideKeyboard(); shot("ps56-image-only-pdf.png"); closeSearch()

        val handwritten = runBlocking { app.workspaceRepository.create("PS56 handwritten " + id().take(8), false, PaperStyle.BLANK) }
        runBlocking {
            app.inkRepository.save(CommitInk(id(), handwritten.id, 0, InkMutation.Add(InkStroke(id(), InkPen.PEN,
                Color.BLACK, 3f, InkTool.STYLUS, listOf(InkSample(200f, 500f, 0), InkSample(450f, 530f, 60))))))
            assertTrue(app.pages.saveSearchText(handwritten.id, 1, "corrected handwriting $alpha", method = "MANUAL"))
        }
        open(handwritten)
        val before = stamp(handwritten)
        search(alpha); labels(handwritten.id, pdf = false, ink = true)
        compose.onAllNodesWithTag("pdf-search-image-only", useUnmergedTree = true).assertCountEquals(0)
        hit(handwritten.id).performClick(); assertPage(handwritten.id, handwritten.id)
        assertEquals(before, stamp(handwritten))
        search(alpha); tap("search-correct-page")
        waitFor("page-search-text")
        compose.onNodeWithTag("page-search-text").assertTextContains("corrected handwriting $alpha", substring = true)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    @Test fun narrowLargeFontUsesRealTouchesAndPdfRegionCanStillBeExcerpted() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600"); shell("wm density 320"); shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val config = compose.activity.resources.configuration
                abs(config.screenWidthDp - 375) <= 4 && abs(config.fontScale - 1.6f) < .02f }
            ready()
            val fixture = pdf(listOf("$beta first page", "$alpha original region"))
            open(fixture.note)
            val before = stamp(fixture.note)
            search(alpha, physical = true)
            labels(fixture.pages[1], pdf = true, ink = false)
            hideKeyboard()
            val result = hit(fixture.pages[1])
            val bounds = result.fetchSemanticsNode().boundsInRoot
            if(bounds.height<95f)shot("ps56-narrow-failure.png")
            assertTrue("Result must retain a 48dp touch target: bounds=$bounds density=${compose.activity.resources.displayMetrics.density}", bounds.height >= 96f - 1f)
            assertTrue("Result must remain inside the 375dp window", bounds.left >= 0 && bounds.right <= 751f)
            result.performTouchInput { click() }
            assertPage(fixture.note.id, fixture.pages[1], 1, fixture.sourceHash)
            assertPdfFocus(); assertEquals(before, stamp(fixture.note))

            // Exercise the existing region-excerpt path on the original PDF, not a new text extraction path.
            tap("top-excerpt", physical = true)
            if (compose.onAllNodesWithTag("excerpt-settings").fetchSemanticsNodes().isEmpty()) tap("top-excerpt", physical = true)
            tap("excerpt-region-mode", physical = true); tap("capture-destination-inbox", physical = true)
            compose.onNodeWithContentDescription("关闭摘要笔").performTouchInput { click() }
            waitFor("selection-overlay")
            var from = Offset.Zero; var to = Offset.Zero
            compose.runOnIdle {
                val view = canvas(); val viewport = view.snapshotViewport(); val density = view.resources.displayMetrics.density.toDouble()
                val a = viewport.worldToScreen(140.0, 865.0, view.width.toDouble(), view.height.toDouble(), density)
                val b = viewport.worldToScreen(295.0, 915.0, view.width.toDouble(), view.height.toDouble(), density)
                from = Offset(a.x.toFloat(), a.y.toFloat()); to = Offset(b.x.toFloat(), b.y.toFloat())
            }
            compose.onNodeWithTag("selection-overlay").performTouchInput { swipe(from, to, 350) }
            waitFor("capture-confirm")
            assertTrue(runBlocking { app.study.cards(fixture.note.id).first() }.isEmpty())
            assertEquals(before, stamp(fixture.note))
            tap("capture-confirm", physical = true)
            compose.waitUntil(15_000) { runBlocking { app.study.cards(fixture.note.id).first().size } == 1 }
            val card = runBlocking { app.study.cards(fixture.note.id).first().single() }
            val excerpt = runBlocking { checkNotNull(app.study.source(card.id)) }
            assertEquals(fixture.pages[1], excerpt.pageId)
            assertEquals(0L, excerpt.inkRevision)
            assertTrue("PDF crop must contain a real source image", InkPageFile.decode(excerpt.snapshot).objects.isNotEmpty())
            // Excerpt creates its own card/receipt; the original document and index remain untouched.
            val after = stamp(fixture.note)
            val expectedReceipts = before.receipts.mapIndexed { index, count -> count + if (index == 2) 1L else 0L }
            assertEquals(before.copy(receipts = expectedReceipts), after)
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale $oldFont" else "settings delete system font_scale")
        }
    }
}
