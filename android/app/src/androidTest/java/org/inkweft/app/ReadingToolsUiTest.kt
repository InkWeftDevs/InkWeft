// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** V61 draft: real MainActivity, synthetic repository fixtures, actual native
 * views and touches. No stylus replay is presented as real handwriting. The
 * author has not compiled/run this file; physical tablet acceptance is separate.
 */
class ReadingToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = provider()[NotebookViewModel::class.java]
    private fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]
    private fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    private val database get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private fun id() = UUID.randomUUID().toString()
    private val settings = linkedMapOf<String, Map<String, Any?>>()
    private var currentBook: String? = null
    private var referenceCanvas: InkCanvasView? = null

    @Before fun saveSettings() {
        listOf("inkweft-editor", "inkweft-reading", "inkweft-study-window", "inkweft-learning", "inkweft-timers", "inkweft-pen-widths")
            .forEach(::capturePreference)
        // A custom writing configuration makes accidental reading-toolbar
        // writes and remount request replay observable.
        app.getSharedPreferences("inkweft-editor", 0).edit()
            .putString("toolbar-order-v32", (listOf("map", "pen", "eraser", "undo", "redo") +
                EditorToolOrder.labels.keys.filterNot { it in setOf("map", "pen", "eraser", "undo", "redo") }).joinToString(","))
            .putStringSet("toolbar-hidden-v32", (EditorToolOrder.defaultHidden + setOf("image", "text")) - "readonly")
            .putBoolean("finger-writes", true).putBoolean("case-collapsed", true)
            .putFloat("case-x", 0f).putFloat("case-y", .35f).commit()
    }

    @After fun restoreSettings() {
        try {
            // All happy paths exit full screen and use the real return-writing
            // button. This cleanup also restores a failed test's session state.
            if (compose.onAllNodesWithTag("exit-fullscreen").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("exit-fullscreen").assertIsDisplayed().performTouchInput { click() }
                compose.waitForIdle()
            }
            compose.runOnIdle {
                currentBook?.let { lock(it).request(false) }
                notebook().back()
            }
            waitFor("new-note")
        } finally {
            settings.forEach { (name, values) ->
                val editor = app.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> putPreference(editor, key, value) }
                assertTrue("Restore $name", editor.commit())
            }
        }
    }
    private fun capturePreference(name: String) {
        if (name !in settings) settings[name] = preference(name)
    }
    private fun preference(name: String): Map<String, Any?> = app.getSharedPreferences(name, 0).all
        .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> error("Unexpected preference type ${value.javaClass.name}")
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun configured(size: String, density: Int, widthDp: Int, font: Float, body: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        val oldWidth = compose.activity.resources.configuration.screenWidthDp
        val oldScale = compose.activity.resources.configuration.fontScale
        try {
            shell("wm size $size"); shell("wm density $density"); shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - widthDp) <= 4 && abs(c.fontScale - font) < .02f }
            body()
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale $oldFont" else "settings delete system font_scale")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - oldWidth) <= 4 && abs(c.fontScale - oldScale) < .02f }
        }
    }

    private data class Fixture(val note: Note, val other: Note, val sourcePage: String, val thirdPage: String,
        val excerpt: String, val mapId: String, val mapCard: String, val node: String)
    private fun seed(continuous: Boolean = false): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("RT61 阅读工具 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val other = runBlocking { app.workspaceRepository.create("RT61 另一资料 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val f = Fixture(note, other, id(), id(), id(), id(), id(), id())
        val source = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(180f, 900f, 0), InkSample(320f, 940f, 100)))
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage); app.pages.addAfter(note.id, f.sourcePage, f.thirdPage)
            for ((page, stroke) in listOf(note.id to InkStroke(id(), InkPen.PEN, 0xff4a4a4a.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(140f, 600f, 0), InkSample(360f, 630f, 100))), f.sourcePage to source,
                f.thirdPage to InkStroke(id(), source.pen, source.color, source.width, source.tool, source.samples),
                other.id to InkStroke(id(), source.pen, source.color, source.width, source.tool, source.samples)))
                assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), page, 0, InkMutation.Add(stroke))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE_EXCERPT, cardId = f.excerpt,
                title = "已有 alpha 摘录", body = "已有原迹快照，只允许阅读。",
                source = StudySourceDraft(f.sourcePage, 1, source.bounds(), listOf(source.id))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition("阅读图 alpha")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.mapCard, nodeId = f.node,
                title = "阅读图节点", body = "只用于阅读入口验证", mapId = f.mapId,
                source = StudySourceDraft(f.sourcePage, 1, source.bounds(), listOf(source.id))))
            for (book in listOf(note.id, other.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "alpha 已校对资料", method = "MANUAL"))
            app.pages.select(note.id, note.id)
        }
        val penName = "inkweft-pen-widths-book-${note.id}"; capturePreference(penName)
        val penStore = PenWidthStore(app, penName)
        runBlocking { assertTrue(penStore.savePreset(0, 4.5f, 0xff126b50.toInt(), InkPen.PENCIL)) }
        penStore.applyRecipe(0, BrushRecipe())
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", continuous).commit()
        app.getSharedPreferences("inkweft-study-window", 0).edit().putString("${note.id}-mode", "FOCUS").commit()
        currentBook = note.id
        compose.runOnIdle { notebook().select(note) }
        if (continuous) { waitFor("continuous-pages"); compose.waitUntil(15_000) { app.navigationReady.value } }
        else { singlePageEditor(); compose.waitForSavedInk() }
        return f
    }
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle()
    }
    private fun tap(tag: String, scroll: Boolean = false) {
        waitFor(tag)
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }; compose.waitForIdle()
    }
    private fun tapDocumentAction(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) tap("document-more")
        tap(tag)
        compose.onNodeWithTag("document-more-menu").assertDoesNotExist()
    }
    private fun singlePageEditor() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("quick-settings").fetchSemanticsNodes().isEmpty()) tap("document-more")
        compose.singlePageEditor()
    }
    private fun closePanel(description: String) {
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }; compose.waitForIdle()
    }
    private fun views(): List<View> {
        val found = mutableListOf<View>(); val queue = java.util.ArrayDeque<View>(); queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) { val view = queue.removeFirst(); found.add(view)
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) } }
        return found
    }
    private fun paper(): InkCanvasView = views().filterIsInstance<InkCanvasView>().single {
        it.isShown && !it.preview && !it.embeddedPage && it !== referenceCanvas }
    private fun visibleCanvases(): List<InkCanvasView> = views().filterIsInstance<InkCanvasView>().filter { it.isShown && !it.preview }
    private fun rawScreenRect(view: View): RectF {
        val origin = IntArray(2); view.getLocationOnScreen(origin)
        return RectF(origin[0].toFloat(), origin[1].toFloat(), (origin[0] + view.width).toFloat(), (origin[1] + view.height).toFloat())
    }
    private fun screenRect(tag: String): RectF {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val origin = compose.runOnIdle {
            val owner = views().first { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" && it.isShown }
            IntArray(2).also { owner.getLocationOnScreen(it) }
        }
        return RectF(origin[0] + bounds.left, origin[1] + bounds.top, origin[0] + bounds.right, origin[1] + bounds.bottom)
    }
    private fun closeRect(a: RectF, b: RectF): Boolean = abs(a.left - b.left) <= 3f && abs(a.top - b.top) <= 3f &&
        abs(a.right - b.right) <= 3f && abs(a.bottom - b.bottom) <= 3f
    private fun assertReference(f: Fixture) {
        waitFor("reference-canvas")
        compose.waitUntil(15_000) {
            val measured = screenRect("reference-canvas")
            compose.runOnIdle {
                val match = views().filterIsInstance<InkCanvasView>().singleOrNull {
                    it.isShown && !it.preview && !it.embeddedPage && closeRect(measured, rawScreenRect(it)) }
                referenceCanvas = match
                match != null && match.documentContentReady && !match.rasterPending && match.displayedStrokeCount == 1
            }
        }
        compose.runOnIdle {
            val actual = checkNotNull(referenceCanvas)
            assertEquals(f.note.id, notebook().ui.value.selectedId)
            assertFalse(actual.allowInput)
            assertEquals(f.other.id, InkCanvasView::class.java.getDeclaredField("documentId")
                .apply { isAccessible = true }.get(actual))
        }
        compose.onNode(hasText(f.other.title) and hasAnyAncestor(hasTestTag("reference-pane")), useUnmergedTree = true).assertIsDisplayed()
    }
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(15_000) { compose.runOnIdle {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true } }
        compose.waitForIdle()
    }
    private fun enterRead(f: Fixture) {
        tap("quick-readonly")
        waitFor("exit-readonly")
        assertRead(f)
        compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
    }
    private fun assertRead(f: Fixture, continuous: Boolean = false) {
        compose.waitUntil(15_000) { compose.runOnIdle { lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite &&
            visibleCanvases().isNotEmpty() && visibleCanvases().all { !it.allowInput && !it.fingerWrites } } }
        compose.onNodeWithTag("exit-readonly").assertIsDisplayed()
        compose.onNodeWithTag("exit-readonly").assertContentDescriptionEquals("书写批注")
        for (tag in listOf("editor-toolbar", "top-draw", "top-eraser", "ink-select", "quick-finger", "document-add-page", "toolbar-customize", "floating-pen-case"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        if (continuous) compose.onNodeWithTag("continuous-pages").assertIsDisplayed()
        compose.runOnIdle { assertEquals(f.note.id, notebook().ui.value.selectedId); assertTrue(lock(f.note.id).readOnly.value) }
    }
    private fun returnWriting(f: Fixture) {
        tap("exit-readonly")
        compose.waitUntil(15_000) { compose.runOnIdle { !lock(f.note.id).readOnly.value && lock(f.note.id).canWrite } }
        waitFor("editor-toolbar")
        compose.onNodeWithTag("reading-toolbar").assertDoesNotExist()
        compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
        compose.onNodeWithTag("pen-width-dialog").assertDoesNotExist()
        compose.onNodeWithTag("toolbar-customize").assertDoesNotExist()
    }
    private fun checkGeometry(fullscreen: Boolean = false, compact: Boolean = false) {
        val containerTag = if (fullscreen) "reading-toolbar" else "document-toolbar"
        val moreTag = if (fullscreen) "toolbar-more" else "document-more"
        val container = compose.onNodeWithTag(containerTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        if (!fullscreen) compose.onNodeWithTag("reading-toolbar").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        val rectangles = listOf("quick-study", "read-excerpts", "document-associations", "exit-readonly", moreTag).map { tag ->
            val r = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag has an actual 48dp target: $r", r.width >= 48f * density - 1 && r.height >= 48f * density - 1)
            val owner=if(fullscreen&&tag=="exit-readonly")compose.onNodeWithTag("study-pane-switcher").fetchSemanticsNode().boundsInRoot else container
            assertTrue("$tag stays wholly inside its shared work-state or context toolbar", r.left >= owner.left && r.right <= owner.right && r.top >= owner.top && r.bottom <= owner.bottom)
            r
        }
        for (a in rectangles.indices) for (b in a + 1 until rectangles.size)
            assertFalse("Primary reading targets must not overlap", rectangles[a].overlaps(rectangles[b]))
        assertEquals("Only one reachable document menu", 1, compose.onAllNodesWithTag(moreTag).fetchSemanticsNodes().size)
        val nestedMore = compose.onAllNodes(hasTestTag(moreTag) and hasAnyAncestor(hasTestTag(containerTag))).fetchSemanticsNodes().size
        assertEquals(1, nestedMore)
        compose.onNodeWithTag("document-more-menu").assertDoesNotExist()
        if (!fullscreen) checkDocumentMenu()
    }
    private fun checkDocumentMenu() {
        tap("document-more")
        val menu = compose.onNodeWithTag("document-more-menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val targets = listOf("quick-overview", "quick-settings", "book-search").map { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag has an actual 48dp target in the document menu", bounds.width >= 48f * density - 1 && bounds.height >= 48f * density - 1)
            assertTrue("$tag stays wholly inside the document menu", bounds.left >= menu.left && bounds.right <= menu.right && bounds.top >= menu.top && bounds.bottom <= menu.bottom)
            bounds
        }
        for (a in targets.indices) for (b in a + 1 until targets.size)
            assertFalse("Document menu targets must not overlap", targets[a].overlaps(targets[b]))
        tapDocumentAction("quick-overview"); waitFor("pages-directory-dialog"); tap("pages-directory-dialog-close")
    }
    private fun openReadingMore() {
        val fullscreen = compose.onAllNodesWithTag("exit-fullscreen").fetchSemanticsNodes().isNotEmpty()
        tap(if (fullscreen) "toolbar-more" else "document-more")
        waitFor(if (fullscreen) "reading-more-menu" else "document-more-menu")
        for (tag in listOf("quick-fullscreen", "quick-export", "quick-timer")) compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("toolbar-customize").assertDoesNotExist()
        compose.onNodeWithTag("top-draw").assertDoesNotExist()
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation(); instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1); val decor = compose.activity.window.decorView
        val draw = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(draw); decor.invalidate() }
        try { assertTrue("Capture follows actual native draw", latch.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { decor.viewTreeObserver.removeOnDrawListener(draw) } }
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private val authorQueries = listOf(
        "SELECT * FROM notes WHERE id=?",
        "SELECT * FROM note_revisions WHERE noteId=? ORDER BY revision",
        "SELECT * FROM command_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT noteId,world,paper,folder,tags,favorite,trashedAt,revision,coverKey,pinned FROM notebook_workspace WHERE noteId=?",
        "SELECT * FROM notebook_covers WHERE noteId=?",
        "SELECT id,notebookId,position,world,paper,createdAfterId,trashedAt FROM notebook_pages WHERE notebookId=? ORDER BY id",
        "SELECT * FROM page_insert_receipts WHERE notebookId=? ORDER BY commandId",
        "SELECT * FROM page_edit_receipts WHERE notebookId=? ORDER BY commandId",
        "SELECT * FROM library_content_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT i.* FROM ink_pages i JOIN notebook_pages p ON p.id=i.noteId WHERE p.notebookId=? ORDER BY i.noteId",
        "SELECT s.* FROM ink_strokes s JOIN notebook_pages p ON p.id=s.noteId WHERE p.notebookId=? ORDER BY s.id",
        "SELECT c.* FROM ink_cuts c JOIN notebook_pages p ON p.id=c.noteId WHERE p.notebookId=? ORDER BY c.id",
        "SELECT r.* FROM ink_receipts r JOIN notebook_pages p ON p.id=r.noteId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT o.* FROM page_objects o JOIN notebook_pages p ON p.id=o.pageId WHERE p.notebookId=? ORDER BY o.pageId",
        "SELECT r.* FROM object_receipts r JOIN notebook_pages p ON p.id=r.pageId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT s.* FROM page_search_text s JOIN notebook_pages p ON p.id=s.pageId WHERE p.notebookId=? ORDER BY s.pageId",
        "SELECT * FROM document_sources WHERE notebookId=? ORDER BY id",
        "SELECT c.* FROM document_chunks c JOIN document_sources s ON s.id=c.documentId WHERE s.notebookId=? ORDER BY c.documentId,c.position",
        "SELECT p.* FROM document_pages p JOIN document_sources s ON s.id=p.documentId WHERE s.notebookId=? ORDER BY p.pageId",
        "SELECT * FROM study_cards WHERE notebookId=? ORDER BY id",
        "SELECT r.* FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=? ORDER BY r.cardId,r.revision",
        "SELECT * FROM study_nodes WHERE notebookId=? ORDER BY id",
        "SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE c.notebookId=? ORDER BY s.cardId",
        "SELECT * FROM study_receipts WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
        "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId",
    )
    private fun authorStamp(book: String): List<String> = runBlocking { database.withTransaction { buildList {
        for (sql in authorQueries) {
            add(sql)
            database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
                while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_INTEGER -> "int:" + cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> "float:" + cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).size + ":" + sha(cursor.getBlob(column))
                        else -> "text:" + cursor.getString(column).length + ":" + cursor.getString(column)
                    }
                })
            }
        }
    } } }
    private fun assertAuthors(f: Fixture, before: List<String>, other: List<String>) {
        assertEquals(before, authorStamp(f.note.id)); assertEquals(other, authorStamp(f.other.id))
    }

    @Test fun wideReadingPreservesCollapsedPenCaseToolbarAndNativePenThenReturnsThroughGate() =
        configured("1440x2200", 160, 1440, 1f) {
            val f = seed()
            compose.runOnIdle { assertTrue(paper().allowInput); assertTrue(paper().fingerWrites); assertEquals(InkPen.PENCIL, paper().pen) }
            tap("top-draw"); waitFor("pen-width-dialog"); tap("close-pen-settings")
            tap("case-collapse")
            assertTrue(app.getSharedPreferences("inkweft-editor", 0).getBoolean("case-collapsed", false))
            compose.onNodeWithTag("pen-kind-pencil").assertDoesNotExist()
            val editor = preference("inkweft-editor"); val pen = preference("inkweft-pen-widths-book-${f.note.id}")
            val before = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val writingTop = compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot.top
            enterRead(f); checkGeometry()
            val readingTop = compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Reading returns the full writing-tool row to content", writingTop - readingTop >= 55f * compose.activity.resources.displayMetrics.density)
            compose.onNodeWithTag("notebook-tabs").assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithTag("tabs-list").fetchSemanticsNodes().size)
            val old = compose.runOnIdle { paper().snapshotViewport() }
            compose.pinchCanvasOut()
            compose.waitUntil(15_000) { compose.runOnIdle { paper().snapshotViewport() != old } }
            compose.onNodeWithTag("ink-surface").performTouchInput {
                swipe(Offset(width * .45f, height * .6f), Offset(width * .53f, height * .55f), 240) }
            compose.waitForIdle(); assertRead(f)
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
            shot("comfort01-rt-wide-reading.png")
            returnWriting(f)
            compose.onNodeWithTag("pen-kind-pencil").assertDoesNotExist()
            assertTrue(app.getSharedPreferences("inkweft-editor", 0).getBoolean("case-collapsed", false))
            compose.runOnIdle { assertTrue(paper().allowInput); assertTrue(paper().fingerWrites)
                assertEquals(InkPen.PENCIL, paper().pen); assertEquals(4.5f, paper().penWidth, .001f); assertEquals(0xff126b50.toInt(), paper().penColor) }
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
        }

    @Test fun narrowLargeFontRecreationAndFullscreenKeepReachableReadingActionsWithoutReplay() =
        configured("750x1600", 320, 375, 1.6f) {
            val f = seed(); enterRead(f); checkGeometry(compact = true)
            val editor = preference("inkweft-editor"); val pen = preference("inkweft-pen-widths-book-${f.note.id}")
            val before = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - 375) <= 4 && abs(c.fontScale - 1.6f) < .02f }
            waitFor("exit-readonly"); assertRead(f); checkGeometry(compact = true)
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other); shot("comfort01-rt-narrow-recreated.png")
            openReadingMore(); tap("quick-fullscreen")
            waitFor("exit-fullscreen"); assertRead(f); checkGeometry(fullscreen = true, compact = true)
            compose.onNodeWithTag("book-search").assertDoesNotExist()
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            openReadingMore(); assertAuthors(f, before, other); shot("comfort01-rt-fullscreen-menu.png")
            tap("quick-export")
            compose.onNodeWithText("导出整本内容副本", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("取消", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
            compose.waitForIdle(); compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            openReadingMore(); tap("quick-timer"); waitFor("notebook-timer")
            compose.onNodeWithTag("timer-value").assertIsDisplayed(); closePanel("关闭计时器")
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            openReadingMore(); tap("quick-fullscreen")
            waitFor("document-toolbar"); checkGeometry(compact = true)
            compose.onNodeWithTag("exit-fullscreen").assertDoesNotExist()
            returnWriting(f)
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
        }

    @Test fun continuousReadingUsesExistingExcerptMapAndHeaderNavigationWithoutAuthorWrites() =
        configured("1200x1920", 160, 1200, 1f) {
            val f = seed(continuous = true)
            enterRead(f); assertRead(f, continuous = true)
            val before = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val editor = preference("inkweft-editor"); val pen = preference("inkweft-pen-widths-book-${f.note.id}")
            repeat(2) { compose.onNodeWithTag("continuous-pages").performTouchInput {
                swipe(Offset(width * .5f, height * .82f), Offset(width * .5f, height * .2f), 360) }; compose.waitForIdle() }
            compose.waitUntil(15_000) { compose.runOnIdle { pages(f.note.id).ui.value.selectedId != f.note.id } }
            assertRead(f, continuous = true)
            compose.runOnIdle { assertTrue(visibleCanvases().all { it.embeddedPage && !it.allowInput }) }
            tap("read-excerpts"); waitFor("excerpt-panel")
            compose.onNodeWithTag("excerpt-list").performScrollToNode(hasTestTag("excerpt-item-${f.excerpt}"))
            compose.onNodeWithTag("excerpt-preview-${f.excerpt}").assertIsDisplayed()
            compose.onNodeWithTag("excerpt-comment-${f.excerpt}").assertIsNotEnabled()
            assertAuthors(f, before, other)
            shot("comfort01-rt-continuous-reading.png")
            tap("excerpt-panel-close")
            tap("quick-study"); waitFor("study-map-picker"); tap("study-map-picker")
            tap("study-map-${f.mapId}"); tap("study-management"); tap("map-menu-group-0"); tap("study-tab-2")
            waitFor("study-map")
            compose.waitUntil(15_000) { compose.runOnIdle { study(f.note.id).mapId.value == f.mapId && !study(f.note.id).ui.value.loading &&
                views().filterIsInstance<MindMapView>().any { it.isShown && it.nodeBounds(f.node) != null && !it.authorEditing } } }
            tap("study-close")
            tapDocumentAction("book-search"); waitFor("book-search-query")
            compose.onNodeWithTag("book-search-query").performTextReplacement("alpha")
            waitFor("book-search-hit-${f.sourcePage}")
            closePanel("关闭查找页内文字")
            tapDocumentAction("quick-overview"); waitFor("pages-directory-dialog"); tap("pages-directory-dialog-close")
            tapDocumentAction("quick-settings"); waitFor("document-settings-dialog")
            compose.onNodeWithTag("continuous-setting").assertIsOn()
            tap("document-settings-dialog-close")
            assertRead(f, continuous = true)
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
            returnWriting(f)
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
        }

    @Test fun narrowSplitKeepsReadingAndHeaderActionsReachableWithoutChangingReference() =
        configured("750x1600", 320, 375, 1.6f) {
            val f = seed(); enterRead(f)
            // Warm the real reference notebook as a tab, then filter the actual
            // tab menu so old retained tabs cannot virtualize this fixture away.
            compose.runOnIdle { notebook().select(f.other) }
            singlePageEditor(); compose.waitForSavedInk()
            compose.runOnIdle { notebook().select(f.note) }
            singlePageEditor(); compose.waitForSavedInk()
            waitFor("exit-readonly"); assertRead(f)
            tap("tabs-list")
            compose.onNodeWithTag("tabs-filter").performTextReplacement(f.other.title)
            tap("tabs-actions-${f.other.id}"); tap("tab-split-horizontal"); hideKeyboard()
            assertReference(f); assertRead(f); checkGeometry(compact = true)
            val editor = preference("inkweft-editor"); val pen = preference("inkweft-pen-widths-book-${f.note.id}")
            val before = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val density = compose.activity.resources.displayMetrics.density
            val lane = compose.onNodeWithTag("ink-surface").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Real left/right split gives a roughly 186.5dp editor", 186.5f, lane.width / density, 3f)
            val header = compose.onNodeWithTag("document-toolbar").fetchSemanticsNode().boundsInRoot
            fun bounds(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val title = bounds("tabs-list")
            val mode = bounds("exit-readonly")
            val more = bounds("document-more")
            val destinations = listOf("quick-study", "document-associations", "read-excerpts").map(::bounds)
            val modes=listOf("quick-readonly","exit-readonly","workspace-recall").map(::bounds)
            modes.forEach{assertEquals("The three work states share one compact row",mode.top,it.top,1f)}
            destinations.forEach { assertEquals("The three named destinations share one row", destinations.first().top, it.top, 1f) }
            // A narrow document puts title/menu first, then the three short work states.
            assertTrue("Modes follow the title and document menu",mode.top>=maxOf(title.bottom,more.bottom)-1f)
            assertTrue("Destinations follow the shared mode row",destinations.first().top>=mode.bottom-1f)
            assertEquals("Header ends after the destination row",header.bottom,destinations.maxOf{it.bottom},2f)
            val reference = compose.onNodeWithTag("reference-pane").fetchSemanticsNode().boundsInRoot
            val targets = listOf("back-library", "document-more", "document-associations",
                "quick-study", "read-excerpts", "exit-readonly").map { tag ->
                val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag has a complete 48dp actual target in split: $bounds", bounds.width >= 48f * density - 1 && bounds.height >= 48f * density - 1)
                assertTrue("$tag stays inside editor lane: $bounds / $lane", bounds.left >= lane.left - 1 && bounds.right <= lane.right + 1)
                assertFalse("$tag does not intersect the reference pane", bounds.overlaps(reference))
                bounds
            }
            for (a in targets.indices) for (b in a + 1 until targets.size)
                assertFalse("Persistent split actions must not overlap", targets[a].overlaps(targets[b]))
            tapDocumentAction("book-search"); waitFor("book-search-query")
            compose.onNodeWithTag("book-search-query").performTextReplacement("alpha")
            waitFor("book-search-results")
            compose.onNodeWithTag("book-search-results").performScrollToNode(hasTestTag("book-search-hit-${f.sourcePage}"))
            compose.onNodeWithTag("book-search-hit-${f.sourcePage}").assertIsDisplayed()
            closePanel("关闭查找页内文字"); hideKeyboard()
            tapDocumentAction("quick-overview"); waitFor("pages-directory-dialog"); tap("pages-directory-dialog-close")
            tapDocumentAction("quick-settings"); waitFor("document-settings-dialog"); tap("document-settings-dialog-close")
            tap("read-excerpts"); waitFor("excerpt-panel")
            compose.onNodeWithTag("excerpt-comment-${f.excerpt}").assertIsNotEnabled()
            tap("excerpt-panel-close")
            tap("quick-study"); waitFor("study-panel")
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value) }
            tap("study-close")
            assertReference(f); assertRead(f); checkGeometry(compact = true)
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other); shot("comfort01-rt-narrow-split.png")
            returnWriting(f); assertReference(f)
            compose.runOnIdle { assertTrue(paper().allowInput); assertTrue(paper().fingerWrites); assertFalse(checkNotNull(referenceCanvas).allowInput) }
            assertEquals(editor, preference("inkweft-editor")); assertEquals(pen, preference("inkweft-pen-widths-book-${f.note.id}"))
            assertAuthors(f, before, other)
            tap("split-close"); referenceCanvas = null
            compose.onNodeWithTag("reference-pane").assertDoesNotExist()
        }
}
