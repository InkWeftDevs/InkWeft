// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/** Actual Activity, native canvas/map, lifecycle owners, writers and author DB.
 * MotionEvents and lost-ack SavedState fixtures are synthetic, not human pen input.
 */
class ReadLockUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private fun id() = UUID.randomUUID().toString()

    @After fun closeProbe() { probeDatabase?.close(); probeDatabase = null }

    private data class Fixture(val note: Note, val page: String, val stroke: String, val text: String, val tape: String,
        val card: String, val node: String, val child: String, val mainNode: String, val mapA: String, val mapB: String,
        val targetNode: String, val targetCard: String, val link: String, val question: String, val portal: String,
        val body: String = "阅读中可以查看目标知识；作者正文与原迹不能被浏览操作改写。")

    private fun provider() = ViewModelProvider(compose.activity)
    private fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]
    private fun objects(page: String) = provider()["objects-$page", PageObjectViewModel::class.java]
    private fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String, physical: Boolean = false) {
        compose.revealAction(tag)
        if (tag == "quick-study" && compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            waitFor("toolbar-more")
            compose.onNodeWithTag("toolbar-more").performClick()
        }
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed()
        if (physical) node.performTouchInput { click() } else node.performClick()
        compose.waitForIdle()
    }

    private inline fun <reified T : View> native(): T {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        WindowInspector.getGlobalWindowViews().filter { it !== compose.activity.window.decorView }.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is T && view.isShown && (view !is InkCanvasView || !view.preview && !view.embeddedPage)) return view
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        error("Visible native ${T::class.java.simpleName} is missing")
    }

    private fun seed(open: Boolean = true): Fixture {
        val note = runBlocking { app.workspaceRepository.create("RL52 原生阅读验收 " + id().take(6), false, PaperStyle.RULED) }
        val f = Fixture(note, id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), id())
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.page)
            app.pages.select(note.id, f.page)
            val stroke = InkStroke(f.stroke, InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(100f, 380f, 0), InkSample(440f, 390f, 100)))
            app.inkRepository.save(CommitInk(id(), f.page, 0, InkMutation.Add(stroke)))
            app.pageObjects.save(f.page, 0, id(), listOf(
                PageObject(f.text, PageObjectKind.TEXT, 90f, 90f, 760f, 140f, text = "原页已保存的文本对象"),
                PageObject(f.tape, PageObjectKind.TAPE, 100f, 270f, 760f, 56f)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapA, 0, KnowledgeData.MapDefinition("原图 · 阅读视角")))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapB, 0, KnowledgeData.MapDefinition("目标图 · 只浏览",
                structures = listOf(MapStructure(f.targetNode, null, "目标结构主题", 40.0, 80.0)))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                title = "原节点", body = f.body, mapId = f.mapA, source = StudySourceDraft(f.page, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = f.child,
                parentId = f.node, title = "可折叠子主题", body = "保留分支和位置", mapId = f.mapA, x = 320.0, y = 200.0))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = f.card, nodeId = f.mainNode))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.targetCard, nodeId = id(),
                title = "目标知识", body = "目标知识已有正文", x = 360.0, y = 420.0))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.link, 0,
                KnowledgeData.Link(TargetRef(TargetKind.CARD, f.card), TargetRef(TargetKind.CARD, f.targetCard))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.question, 0, KnowledgeData.Question(f.card, "原回忆题：怎样保护作者数据？")))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.portal, 0, KnowledgeData.MapPortal(f.mapA, f.node, f.mapB)))
        }
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false).commit()
        if (open) openPaper(note)
        return f
    }

    private fun openPaper(note: Note, settled: Boolean = true) {
        compose.runOnIdle { provider()[NotebookViewModel::class.java].select(note) }
        compose.singlePageEditor()
        if (settled) compose.waitForSavedInk()
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle { val page = pages(note.id).ui.value.selectedId
                ready = page != null && !ink(page).ui.value.loading && !objects(page).ui.value.loading }
            ready
        }
        // Pending Study recovery deliberately keeps global navigationReady false.
        // Fitting is view state, so it need not pretend that recovery has settled.
        compose.runOnIdle { native<InkCanvasView>().fitPage() }
        compose.waitForIdle()
    }

    private fun openMap(f: Fixture) {
        tap("quick-study")
        tap("study-map-picker")
        tap("study-map-${f.mapA}")
        waitMap(f, f.mapA, f.node)
        selectNode(f.node)
    }

    private fun waitMap(f: Fixture, mapId: String?, nodeId: String) {
        waitFor("study-map")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = study(f.note.id).mapId.value == mapId && !study(f.note.id).ui.value.loading &&
                runCatching { native<MindMapView>().nodeBounds(nodeId) != null }.getOrDefault(false) }
            ready
        }
    }

    private fun selectNode(node: String) {
        var point = Offset.Zero
        compose.runOnIdle { native<MindMapView>().focusNode(node)
            val b = checkNotNull(native<MindMapView>().nodeBounds(node)); point = Offset(b.centerX(), b.centerY()) }
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput { click(point) }
        waitFor("node-actions")
        compose.runOnIdle { assertEquals(node, native<MindMapView>().selectedNodeId) }
    }

    private data class SourceMapContext(val mapId: String?, val tab: Int,
        val viewports: Map<String, MapViewport>, val collapsed: Map<String, List<String>>,
        val focused: Map<String, String?>, val selected: Map<String, String?>,
        val nativeViewport: MapViewport, val nativeSelected: String?)

    private fun sourceMapContext(f: Fixture) = compose.runOnIdle {
        val vm = study(f.note.id)
        SourceMapContext(vm.mapId.value, vm.lastTab, vm.viewports.toMap(),
            vm.collapsedByMap.mapValues { it.value.toList() }, vm.focusedByMap.toMap(), vm.selectedByMap.toMap(),
            native<MindMapView>().snapshotViewport(), native<MindMapView>().selectedNodeId)
    }

    private fun restoreMapAfterSource(f: Fixture, expected: SourceMapContext) {
        compose.onNodeWithTag("study-window-source-return").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            val vm = study(f.note.id)
            assertEquals(expected.mapId, vm.mapId.value); assertEquals(expected.tab, vm.lastTab)
            assertEquals(expected.viewports, vm.viewports.toMap())
            assertEquals(expected.collapsed, vm.collapsedByMap.mapValues { it.value.toList() })
            assertEquals(expected.focused, vm.focusedByMap.toMap()); assertEquals(expected.selected, vm.selectedByMap.toMap())
            assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<InkCanvasView>().allowInput)
        }
        tap("study-window-source-return", physical = true)
        waitMap(f, expected.mapId, f.node)
        compose.runOnIdle {
            assertEquals(expected.nativeViewport, native<MindMapView>().snapshotViewport())
            assertEquals(expected.nativeSelected, native<MindMapView>().selectedNodeId)
            assertFalse(native<MindMapView>().authorEditing)
        }
    }

    private fun setReadOnly(f: Fixture, value: Boolean, fromStudy: Boolean = true) {
        val current = compose.runOnIdle { lock(f.note.id).readOnly.value }
        if (current != value) {
            if (fromStudy) tap("study-readonly", physical = true)
            else if (value) tapPaperReadOnly() else tap("exit-readonly", physical = true)
        }
        compose.waitUntil(15_000) { var actual = false
            compose.runOnIdle { actual = lock(f.note.id).readOnly.value == value && lock(f.note.id).canWrite != value }
            actual
        }
    }

    private fun tapPaperReadOnly() {
        if (compose.onAllNodesWithTag("quick-readonly").fetchSemanticsNodes().isEmpty()) {
            tap("toolbar-more")
            if (compose.onAllNodesWithTag("quick-readonly").fetchSemanticsNodes().isEmpty()) {
                tap("toolbar-customize")
                waitFor("toolbar-visible-readonly")
                val visibility = compose.onNodeWithTag("toolbar-visible-readonly")
                visibility.performScrollTo()
                if (runCatching { visibility.assertIsOff() }.isSuccess) visibility.performTouchInput { click() }
                visibility.assertIsOn()
                tap("toolbar-done")
                tap("toolbar-more")
            }
        }
        tap("quick-readonly", physical = true)
    }

    // OutlinedTextField merges its label into Text. Compare the exact author draft
    // through EditableText while retaining separate selection and target checks.
    private fun assertDraftText(tag: String, expected: String) = compose.onNodeWithTag(tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))

    private val authorQueries = listOf(
        "SELECT * FROM notes WHERE id=?",
        "SELECT * FROM note_revisions WHERE noteId=? ORDER BY revision",
        "SELECT * FROM command_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT noteId,world,paper,folder,tags,favorite,trashedAt,revision,coverKey,selectedPageId,pinned FROM notebook_workspace WHERE noteId=?",
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
        "SELECT * FROM study_cards WHERE notebookId=? ORDER BY id",
        "SELECT r.* FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=? ORDER BY r.cardId,r.revision",
        "SELECT * FROM study_nodes WHERE notebookId=? ORDER BY id",
        "SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE c.notebookId=? ORDER BY s.cardId",
        "SELECT * FROM study_receipts WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
        "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId",
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** Retains notes.updatedAt and every author value/history/receipt. Only viewport
     * centerX/centerY/zoom and device last-visit preferences are excluded. Selection
     * is compared after returning to the original page, independently of author mode.
     */
    private fun authorStamp(book: String, queries: List<String> = authorQueries): List<String> = runBlocking {
        probe.withTransaction { buildList {
            queries.forEach { sql -> add(sql)
                probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { c ->
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { column ->
                        when (c.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> "int:" + c.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> "float:" + c.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + c.getBlob(column).size + ":" + sha(c.getBlob(column))
                            else -> "text:" + c.getString(column).length + ":" + c.getString(column)
                        }
                    })
                }
            }
        } }
    }

    private fun count(sql: String, argument: String) = runBlocking {
        probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(argument))).use { c -> check(c.moveToFirst()); c.getLong(0) }
    }

    private fun awaitInk(page: String, revision: Long) {
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { val ui = ink(page).ui.value; ready = ui.revision == revision && ui.queued == 0 && ui.blocked == null && !ui.processing }
            ready
        }
        assertEquals(revision, runBlocking { app.inkRepository.read(page).revision })
    }

    private fun paperPoint(x: Double, y: Double): Offset = compose.runOnIdle {
        val v = native<InkCanvasView>()
        val p = v.snapshotViewport().worldToScreen(x, y, v.width.toDouble(), v.height.toDouble(), v.resources.displayMetrics.density.toDouble())
        Offset(p.x.toFloat(), p.y.toFloat())
    }

    private fun motion(view: InkCanvasView, down: Long, time: Long, action: Int, tool: Int, points: Array<Offset>) {
        val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coordinates = Array(points.size) { i -> MotionEvent.PointerCoords().apply {
            x = points[i].x; y = points[i].y; pressure = .5f; size = .1f } }
        val event = MotionEvent.obtain(down, time, action, points.size, properties, coordinates, 0, 0, 1f, 1f, -1, 0,
            if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun draw(tool: Int, y: Double) {
        val a = paperPoint(180.0, y); val b = paperPoint(310.0, y + 20); val c = paperPoint(440.0, y + 45)
        compose.runOnIdle { val v = native<InkCanvasView>(); val down = SystemClock.uptimeMillis()
            motion(v, down, down, MotionEvent.ACTION_DOWN, tool, arrayOf(a))
            motion(v, down, down + 20, MotionEvent.ACTION_MOVE, tool, arrayOf(b))
            motion(v, down, down + 40, MotionEvent.ACTION_UP, tool, arrayOf(c)) }
        compose.waitForIdle()
    }

    @Test fun paperNativeInputsAndObjectsStayReadOnlyThenResumeOnce() {
        val prefs = app.getSharedPreferences("inkweft-editor", 0)
        val priorFinger = prefs.getBoolean("finger-writes", false)
        prefs.edit().putBoolean("finger-writes", true).commit()
        try {
            val f = seed()
            val receipts = count("SELECT COUNT(*) FROM ink_receipts WHERE noteId=?", f.page)
            val oldRealInk = compose.runOnIdle { ink(f.page).ui.value.also { assertEquals(1L, it.revision) } }
            val a = paperPoint(180.0, 900.0); val b = paperPoint(260.0, 915.0)
            val c = paperPoint(350.0, 925.0); val d = paperPoint(440.0, 940.0)
            val down = SystemClock.uptimeMillis()
            compose.runOnIdle { val v = native<InkCanvasView>(); assertTrue(v.allowInput)
                motion(v, down, down, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(a))
                motion(v, down, down + 20, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(b))
                assertFalse("Real held native input must block the actual book mode transition", lock(f.note.id).request(true))
                assertFalse(lock(f.note.id).readOnly.value)
                assertTrue(lock(f.note.id).reason.isNotBlank()) }
            compose.runOnIdle { val v = native<InkCanvasView>()
                motion(v, down, down + 40, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(c))
                motion(v, down, down + 60, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(d)) }
            awaitInk(f.page, 2)
            assertEquals(receipts + 1, count("SELECT COUNT(*) FROM ink_receipts WHERE noteId=?", f.page))
            val saved = runBlocking { app.inkRepository.read(f.page).strokes.single { it.stroke.id != f.stroke }.stroke }
            assertTrue("Failed mode switch must retain the held stroke's sampled path", saved.samples.size >= 4)
            listOf(180f to 900f, 260f to 915f, 350f to 925f, 440f to 940f).forEach { (x, y) ->
                assertTrue("Held native sample $x,$y was lost", saved.samples.any { kotlin.math.abs(it.x - x) < .2f && kotlin.math.abs(it.y - y) < .2f }) }
            val beforeBeauty = authorStamp(f.note.id)
            compose.runOnIdle {
                val writer = objects(f.page)
                val currentRealInk = ink(f.page).ui.value
                assertEquals(2L, currentRealInk.revision)
                // Start the actual 750ms job from real pre/post native-stroke snapshots.
                // All checks and cancellation stay in this main-thread turn, so no
                // recognizer can run and no synthetic busy flag substitutes for a Job.
                writer.observeBeauty(currentRealInk, false, BeautyOptions(enabled = false), false, app)
                try {
                    writer.observeBeauty(oldRealInk, false, BeautyOptions(enabled = true), false, app)
                    writer.observeBeauty(currentRealInk, false, BeautyOptions(enabled = true), false, app)
                    assertTrue(writer.authorOperationActive)
                    assertFalse(writer.ui.value.busy)
                    assertFalse(writer.ui.value.pending)
                    assertNull(writer.beautyStatus.value)
                    assertFalse("Actual book gate must detect beauty's unpublished delay job", lock(f.note.id).request(true))
                    assertFalse(lock(f.note.id).readOnly.value)
                    assertEquals("Starting and guarding the delay creates no author SQL", beforeBeauty, authorStamp(f.note.id))
                } finally { writer.observeBeauty(currentRealInk, false, BeautyOptions(enabled = false), false, app) }
                assertFalse(writer.authorOperationActive)
            }
            setReadOnly(f, true, fromStudy = false)
            val before = authorStamp(f.note.id)
            compose.runOnIdle { assertFalse(native<InkCanvasView>().allowInput); assertFalse(native<InkCanvasView>().fingerWrites) }
            val beforeZoom = compose.runOnIdle { native<InkCanvasView>().snapshotViewport() }
            compose.pinchCanvasOut()
            val afterZoom = compose.runOnIdle { native<InkCanvasView>().snapshotViewport() }
            assertTrue("Read mode still accepts real two-finger page zoom", afterZoom.zoom > beforeZoom.zoom)
            compose.onNodeWithTag("ink-surface").performTouchInput {
                swipe(Offset(width * .4f, height * .7f), Offset(width * .5f, height * .65f), 240) }
            compose.waitForIdle()
            assertNotEquals("Read mode still accepts real single-finger page pan", afterZoom,
                compose.runOnIdle { native<InkCanvasView>().snapshotViewport() })
            compose.runOnIdle { native<InkCanvasView>().fitPage() }
            draw(MotionEvent.TOOL_TYPE_STYLUS, 1050.0)
            draw(MotionEvent.TOOL_TYPE_FINGER, 1100.0)
            val tape = runBlocking { app.pageObjects.read(f.page).objects.single { it.id == f.tape } }
            val tapePoint = paperPoint((tape.x + tape.width / 2).toDouble(), (tape.y + tape.height / 2).toDouble())
            compose.runOnIdle {
                val v = native<InkCanvasView>(); val time = SystemClock.uptimeMillis()
                motion(v, time, time, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(tapePoint))
                motion(v, time, time + 20, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, arrayOf(tapePoint))
                // Exercise the actual bound callback and VM as well as physical input.
                native<InkCanvasView>().onObjectTap(f.tape)
                objects(f.page).put(tape.copy(revealed = true))
                objects(f.page).put(PageObject(id(), PageObjectKind.TEXT, text = "must be rejected"))
                ink(f.page).accept(InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                    listOf(InkSample(100f, 1100f, 0), InkSample(400f, 1140f, 20))))
                ink(f.page).erase(listOf(f.stroke))
            }
            compose.waitForIdle()
            assertEquals("Stylus/finger, tape reveal, object put and dynamic ink callbacks must create no author writes", before, authorStamp(f.note.id))
            assertFalse(runBlocking { app.pageObjects.read(f.page).objects.single { it.id == f.tape }.revealed })
            setReadOnly(f, false, fromStudy = false)
            draw(MotionEvent.TOOL_TYPE_STYLUS, 1050.0)
            awaitInk(f.page, 3)
            assertEquals(receipts + 2, count("SELECT COUNT(*) FROM ink_receipts WHERE noteId=?", f.page))
            val objectReceipts = count("SELECT COUNT(*) FROM object_receipts WHERE pageId=?", f.page)
            compose.runOnIdle { objects(f.page).put(tape.copy(revealed = true)) }
            compose.waitUntil(15_000) { var ready = false
                compose.runOnIdle { ready = objects(f.page).revision == 2L && !objects(f.page).ui.value.busy }; ready }
            assertEquals(objectReceipts + 1, count("SELECT COUNT(*) FROM object_receipts WHERE pageId=?", f.page))
            assertTrue(runBlocking { app.pageObjects.read(f.page).objects.single { it.id == f.tape }.revealed })
        } finally { prefs.edit().putBoolean("finger-writes", priorFinger).commit() }
    }

    @Test fun summaryOutlineAndMapRejectActualAuthorIntents() {
        val f = seed(); openMap(f); setReadOnly(f, true)
        val before = authorStamp(f.note.id)
        tap("study-tab-0")
        tap("study-card-${f.card}")
        waitFor("card-full-body"); compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("study-edit-card").assertIsNotEnabled()
        tap("card-back")
        tap("study-tab-1")
        compose.onNodeWithTag("outline-child-${f.node}").assertIsNotEnabled()
        compose.onNodeWithTag("outline-sibling-${f.node}").assertIsNotEnabled()
        tap("outline-node-${f.node}"); waitFor("card-full-body"); tap("card-back")
        tap("study-tab-2"); waitMap(f, f.mapA, f.node); selectNode(f.node)
        listOf("node-rename", "node-add-child", "node-add-sibling").forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithTag("node-more").assertIsEnabled()
        var point = Offset.Zero
        compose.runOnIdle { val b = checkNotNull(native<MindMapView>().nodeBounds(f.node)); point = Offset(b.centerX(), b.centerY()) }
        compose.onNodeWithTag("study-map").performTouchInput { down(point); moveTo(point + Offset(60f, 30f), 240); up() }
        compose.runOnIdle {
            assertFalse(study(f.note.id).authorAllowed())
            study(f.note.id).submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card,
                expectedRevision = 1, title = "forbidden rename", body = "forbidden body"))
            study(f.note.id).submit(StudyCommand(id(), f.note.id, StudyAction.CREATE, cardId = id(), nodeId = id(), title = "forbidden child", mapId = f.mapA))
            study(f.note.id).submit(StudyCommand(id(), f.note.id, StudyAction.MOVE, nodeId = f.node,
                expectedRevision = 1, x = 900.0, y = 900.0, mapId = f.mapA))
            assertFalse(study(f.note.id).ui.value.busy)
            assertFalse(study(f.note.id).ui.value.unknown)
        }
        compose.waitForIdle()
        assertEquals("All three real views and VM submit reject author changes", before, authorStamp(f.note.id))
    }

    @Test fun readNavigationSearchSourceAndPortalReturnKeepAuthorRows() {
        val f = seed(); openMap(f); setReadOnly(f, true)
        val before = authorStamp(f.note.id)
        tap("node-more"); tap("node-menu-fold")
        compose.runOnIdle { assertNull(native<MindMapView>().nodeBounds(f.child)) }
        compose.onNodeWithTag("study-map").performTouchInput {
            val p = Offset(width * .5f, height * .3f)
            down(0, p - Offset(60f, 0f)); down(1, p + Offset(60f, 0f))
            for (i in 1..5) { moveTo(0, p - Offset(60f + i * 5, 0f), 16); moveTo(1, p + Offset(60f + i * 5, 0f), 16) }
            up(0); up(1)
        }
        selectNode(f.node)
        tap("study-content-search")
        waitFor("map-content-query"); compose.onNodeWithTag("map-content-query").performTextInput("目标结构主题")
        tap("map-search-all"); tap("map-hit-${f.mapB}-${f.targetNode}")
        waitMap(f, f.mapB, f.targetNode)
        tap("study-content-search"); waitMap(f, f.mapA, f.node); selectNode(f.node)
        tap("node-more"); tap("node-view-content")
        waitFor("card-full-body")
        tap("card-knowledge-links"); tap("card-link-choice-${f.link}")
        waitFor("card-link-preview-body"); compose.onNodeWithTag("card-link-preview-body").assertTextEquals("目标知识已有正文")
        tap("card-link-close-preview"); waitFor("card-full-body"); tap("card-back")
        selectNode(f.node)
        tap("node-more"); tap("node-view-source")
        waitFor("study-open-source")
        val sourceContext = sourceMapContext(f)
        tap("study-open-source")
        compose.runOnIdle { assertEquals(f.page, pages(f.note.id).ui.value.selectedId); assertTrue(lock(f.note.id).readOnly.value) }
        restoreMapAfterSource(f, sourceContext)
        waitMap(f, f.mapA, f.node); selectNode(f.node)
        lateinit var viewport: MapViewport
        lateinit var collapsed: List<String>
        var focus: String? = null
        compose.runOnIdle { viewport = native<MindMapView>().snapshotViewport()
            collapsed = study(f.note.id).collapsedByMap[f.mapA].orEmpty().toList(); focus = study(f.note.id).focusedByMap[f.mapA] }
        tap("node-more"); tap("node-map-portals")
        compose.onNodeWithTag("map-portal-create").assertIsNotEnabled()
        tap("map-portal-item-${f.portal}"); waitFor("map-portal-preview")
        compose.onNodeWithTag("map-portal-remove").assertIsNotEnabled()
        tap("map-portal-open"); waitMap(f, f.mapB, f.targetNode)
        tap("map-portal-back"); waitMap(f, f.mapA, f.node)
        compose.runOnIdle { assertEquals(f.node, native<MindMapView>().selectedNodeId)
            assertEquals(viewport, native<MindMapView>().snapshotViewport())
            assertEquals(collapsed, study(f.note.id).collapsedByMap[f.mapA].orEmpty())
            assertEquals(focus, study(f.note.id).focusedByMap[f.mapA]); assertEquals(f.page, pages(f.note.id).ui.value.selectedId) }
        val writer = compose.runOnIdle { provider()["study-map-writer-${f.note.id}", KnowledgeViewModel::class.java] }
        compose.runOnIdle { writer.submit(f.note.id, KnowledgeData.Question(f.card, "must not create a new question")) }
        compose.waitForIdle()
        assertEquals("Selection, zoom/fold/search, source, Link and portal reads plus ordinary Question submit leave author SQL unchanged", before, authorStamp(f.note.id))
        // Read lock preserves the existing explicit manual review capability.
        val keep = authorQueries.filterNot { it.startsWith("SELECT * FROM notes") || it.contains("knowledge_") }
        val immutableBeforeMark = authorStamp(f.note.id, keep)
        val receiptCount = count("SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", f.note.id)
        val revisionCount = count("SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?", f.note.id)
        val recordCount = count("SELECT COUNT(*) FROM knowledge_records WHERE notebookId=?", f.note.id)
        val otherKnowledge = runBlocking { probe.knowledge().forBook(f.note.id).filterNot { it.id == f.question }
            .map { "${it.id}:${it.revision}:${it.removed}:" + sha(it.payload) }.sorted() }
        selectNode(f.node); tap("node-more"); tap("node-review-branch")
        waitFor("branch-review-counts"); tap("branch-review-start")
        waitFor("review-question"); compose.onNodeWithTag("review-question").assertTextEquals("原回忆题：怎样保护作者数据？")
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        assertEquals(before, authorStamp(f.note.id))
        tap("reveal-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.body)
        assertEquals(before, authorStamp(f.note.id))
        tap("branch-review-mark-UNDERSTOOD"); waitFor("branch-review-ended")
        val marked = runBlocking { checkNotNull(probe.knowledge().get(f.question)) }
        assertEquals(2L, marked.revision)
        assertEquals(ManualState.UNDERSTOOD, (marked.data() as KnowledgeData.Question).state)
        assertEquals(receiptCount + 1, count("SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", f.note.id))
        assertEquals(revisionCount + 1, count("SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?", f.note.id))
        assertEquals(recordCount, count("SELECT COUNT(*) FROM knowledge_records WHERE notebookId=?", f.note.id))
        assertEquals(otherKnowledge, runBlocking { probe.knowledge().forBook(f.note.id).filterNot { it.id == f.question }
            .map { "${it.id}:${it.revision}:${it.removed}:" + sha(it.payload) }.sorted() })
        assertEquals(immutableBeforeMark, authorStamp(f.note.id, keep))
        tap("branch-review-close")
        compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value) }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    @Test fun sessionSurvivesRecreationMinimizeAndOtherBookWithoutLeaking() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600"); shell("wm density 320"); shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                kotlin.math.abs(c.screenWidthDp - 375) <= 4 && kotlin.math.abs(c.fontScale - 1.6f) < .02f }
            val f = seed(); openMap(f); setReadOnly(f, true)
            val before = authorStamp(f.note.id)
            compose.onNodeWithTag("study-readonly").assertIsDisplayed().assertIsEnabled()
            compose.activityRule.scenario.recreate(); waitFor("study-readonly")
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(native<InkCanvasView>().allowInput) }
            tap("study-window-minimize", physical = true)
            compose.onNodeWithTag("study-map").assertDoesNotExist()
            tap("study-window-minimize", physical = true); waitMap(f, f.mapA, f.node)
            tap("study-close", physical = true)
            tap("quick-study", physical = true); waitMap(f, f.mapA, f.node)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value) }
            tap("study-close")
            val other = runBlocking { app.workspaceRepository.create("RL52 另一份笔记 " + id().take(6), false, PaperStyle.DOTS) }
            app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${other.id}", false).commit()
            openPaper(other)
            compose.runOnIdle { assertTrue(lock(other.id).canWrite); assertFalse(lock(other.id).readOnly.value) }
            val otherBefore = authorStamp(other.id)
            openPaper(f.note)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(native<InkCanvasView>().allowInput) }
            tap("quick-study"); waitMap(f, f.mapA, f.node)
            setReadOnly(f, false); setReadOnly(f, true)
            assertEquals("Recreate, minimize/reopen, other-book and narrow-font mode transitions write no author history", before, authorStamp(f.note.id))
            assertEquals(otherBefore, authorStamp(other.id))
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
            shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont else "settings delete system font_scale")
        }
    }

    @Test fun draftPreventsModeChangeAndKeepsExactText() {
        val f = seed(); openMap(f)
        val before = authorStamp(f.note.id)
        val receipts = count("SELECT COUNT(*) FROM study_receipts WHERE notebookId=?", f.note.id)
        tap("node-rename"); waitFor("node-title-input")
        val title = "未保存主题：原卡与当前图 🧠"
        val selection = TextRange(2, 5)
        compose.onNodeWithTag("node-title-input").performTextReplacement(title)
        compose.onNodeWithTag("node-title-input").performTextInputSelection(selection)
        compose.runOnIdle { assertTrue(lock(f.note.id).hasDraft.value)
            assertFalse(lock(f.note.id).request(true)); assertFalse(lock(f.note.id).readOnly.value) }
        assertDraftText("node-title-input", title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        compose.activityRule.scenario.recreate()
        waitFor("node-title-input")
        assertDraftText("node-title-input", title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        // The lifecycle repair must still accept an intentional active caret
        // move to that same range end, then another user selection.
        compose.onNodeWithTag("node-title-input").performTextInputSelection(TextRange(selection.max))
        assertDraftText("node-title-input", title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(selection.max)))
        compose.onNodeWithTag("node-title-input").performTextInputSelection(selection)
        assertDraftText("node-title-input", title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        compose.runOnIdle {
            assertEquals(f.mapA, study(f.note.id).mapId.value)
            assertEquals(f.node, study(f.note.id).selectedByMap[f.mapA])
            assertEquals(f.card, study(f.note.id).ui.value.nodes.single { it.id == f.node }.cardId)
            assertFalse(lock(f.note.id).request(true)); assertTrue(lock(f.note.id).hasDraft.value)
        }
        assertEquals("Restoring a real title draft retains its exact target and makes no save", before, authorStamp(f.note.id))
        tap("node-title-cancel"); waitFor("node-actions")
        setReadOnly(f, true); setReadOnly(f, false)
        assertEquals("Cancelling the title draft allows mode switching without an author revision", before, authorStamp(f.note.id))
        tap("node-more"); tap("node-view-content"); tap("study-edit-card")
        waitFor("study-card-body")
        val exact = "未保存草稿：保留完整字句、换行与 🧠。\n第二行仍属于原卡。"
        compose.onNodeWithTag("study-card-body").performTextReplacement(exact)
        compose.runOnIdle { assertTrue(lock(f.note.id).hasDraft.value)
            assertFalse(lock(f.note.id).request(true)); assertFalse(lock(f.note.id).readOnly.value) }
        assertDraftText("study-card-body", exact)
        assertEquals(before, authorStamp(f.note.id))
        val originalRequest = compose.activity.requestedOrientation
        val originalOrientation = compose.activity.resources.configuration.orientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation =
                if (originalOrientation == Configuration.ORIENTATION_PORTRAIT) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { runCatching {
                compose.activity.resources.configuration.orientation != originalOrientation }.getOrDefault(false) }
            waitFor("study-card-body"); assertDraftText("study-card-body", exact)
            compose.runOnIdle { assertFalse(lock(f.note.id).request(true)); assertTrue(lock(f.note.id).hasDraft.value) }
            assertEquals(before, authorStamp(f.note.id))
        } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = originalRequest } }
        compose.waitUntil(15_000) { runCatching {
            compose.activity.resources.configuration.orientation == originalOrientation }.getOrDefault(false) }
        waitFor("study-card-body"); assertDraftText("study-card-body", exact)
        compose.runOnIdle { assertFalse(lock(f.note.id).request(true)); assertTrue(lock(f.note.id).hasDraft.value) }
        tap("study-save-card")
        compose.waitUntil(15_000) { var done = false
            compose.runOnIdle { done = study(f.note.id).editorState.value == null && !study(f.note.id).ui.value.busy && !lock(f.note.id).hasDraft.value }; done }
        assertEquals(exact, runBlocking { checkNotNull(probe.study().card(f.card)).body })
        assertEquals(2L, runBlocking { checkNotNull(probe.study().card(f.card)).revision })
        assertEquals(receipts + 1, count("SELECT COUNT(*) FROM study_receipts WHERE notebookId=?", f.note.id))
        val afterSave = authorStamp(f.note.id)
        setReadOnly(f, true)
        assertEquals("Completing one draft then entering read mode adds no second save", afterSave, authorStamp(f.note.id))
    }

    @Test fun unknownOriginalCommandRetriesItsReceiptUnderReadLock() {
        val f = seed(open = false)
        val original = StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card, expectedRevision = 1,
            title = "原确认标题", body = "已真实提交，模拟原保存回执未到达 UI。")
        runBlocking { app.study.submit(original) }
        val fields = arrayListOf(original.id, original.notebookId, original.action.name, original.cardId.orEmpty(), original.nodeId.orEmpty(),
            original.expectedRevision.toString(), original.parentId.orEmpty(), original.title, original.body, original.x.toString(), original.y.toString(),
            original.expectedGraph, original.mapId.orEmpty())
        val saved = SavedStateHandle(mapOf("study.command" to fields))
        lateinit var vm: StudyViewModel
        compose.runOnIdle {
            // Real saved original command + real committed receipt; no fabricated UI-success flag.
            vm = StudyViewModel(f.note.id, app.study, saved)
            compose.activity.viewModelStore.put("study-${f.note.id}", vm)
            compose.activity.viewModelStore.put("read-lock-${f.note.id}", BookReadLockViewModel(SavedStateHandle(mapOf("readLock.enabled" to true))))
        }
        openPaper(f.note, settled = false); tap("quick-study"); waitFor("study-retry")
        val before = authorStamp(f.note.id)
        compose.runOnIdle {
            assertSame(vm, study(f.note.id)); assertTrue(vm.ui.value.unknown)
            assertTrue(lock(f.note.id).readOnly.value)
            assertFalse("An unknown author operation blocks leaving read mode", lock(f.note.id).request(false))
            vm.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card, expectedRevision = 2,
                title = "new forbidden request", body = "must not replace the original identity"))
            assertEquals(fields, saved.get<ArrayList<String>>("study.command"))
        }
        assertEquals(before, authorStamp(f.note.id))
        compose.activityRule.scenario.recreate(); waitFor("study-retry")
        compose.runOnIdle { assertSame(vm, study(f.note.id)); assertEquals(fields, saved.get<ArrayList<String>>("study.command")) }
        tap("study-retry")
        compose.waitUntil(15_000) { var done = false
            compose.runOnIdle { done = !vm.ui.value.busy && !vm.ui.value.unknown && saved.get<ArrayList<String>>("study.command") == null }; done }
        assertEquals("Retry under read lock resolves the original receipt without a new author revision", before, authorStamp(f.note.id))
        assertEquals(2L, runBlocking { checkNotNull(probe.study().card(f.card)).revision })
        assertEquals(original.cardId, runBlocking { checkNotNull(probe.study().receipt(original.id)).resultId })
        compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value)
            vm.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card, expectedRevision = 2,
                title = "still forbidden", body = "retry permission must not authorize a new intent")) }
        compose.waitForIdle(); assertEquals(before, authorStamp(f.note.id))
    }
}
