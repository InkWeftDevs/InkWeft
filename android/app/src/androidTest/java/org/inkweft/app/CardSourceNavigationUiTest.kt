// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** V57: real Activity/repositories/native canvases and physical UI touches.
 * Fixtures are synthetic author content. This is not real tablet/pen acceptance.
 */
class CardSourceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private fun id() = UUID.randomUUID().toString()
    private fun provider() = ViewModelProvider(compose.activity)
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    private fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]
    private fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]

    @After fun closeProbe() { probeDatabase?.close(); probeDatabase = null }

    private data class Fixture(
        val note: Note, val sourcePage: String, val otherPage: String, val mapId: String,
        val card: String, val node: String, val child: String, val otherCard: String,
        val otherNode: String, val noSourceCard: String, val noSourceNode: String,
        val originalStroke: InkStroke, val title: String, val body: String,
        val otherBody: String = "另一张卡片的正文：切换来源查看之后仍可正常阅读。",
        val noSourceBody: String = "手动建立的卡片没有来源，不应沿用上一张卡片的回原文按钮。",
    )

    private data class MapState(
        val mapId: String?, val tab: Int, val selected: String?, val viewport: MapViewport,
        val viewports: Map<String, MapViewport>, val collapsed: Map<String, List<String>>,
        val focused: Map<String, String?>, val selections: Map<String, String?>,
    )

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.revealAction(tag)
        if (tag == "quick-study" && compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            waitFor("toolbar-more")
            compose.onNodeWithTag("toolbar-more").performTouchInput { click() }
        }
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
    }

    /** Footer reachability is the contract: never scroll/reveal it for the test. */
    private fun tapFooter(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }

    private inline fun <reified T : View> native(preview: Boolean = false): T {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        WindowInspector.getGlobalWindowViews().filter { it !== compose.activity.window.decorView }.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is T && view.isShown && (view !is InkCanvasView || view.preview == preview && !view.embeddedPage)) return view
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        error("Visible native ${T::class.java.simpleName} (preview=$preview) missing")
    }

    private fun seed(longContent: Boolean = false): Fixture {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        val note = runBlocking { app.workspaceRepository.create("SN57 原文导航 " + id().take(6), false, PaperStyle.BLANK) }
        val title = if (longContent) "从样本空间到条件概率的完整理解：" + "每次计算都先核对已知条件和事件范围，保留推理、反例与复习问题。".repeat(3) else "条件概率 · 来源卡片"
        val body = if (longContent) (1..90).joinToString("\n\n") {
            "第${it}段：本段只用于真实长文滚动验收。先明确样本空间，再核对交集与条件范围；正文滚动不应带走底栏，也不应改变卡片或原文页身份。"
        } else "在已知条件下重新确定样本空间。回原文应打开第二页的具体摘录区域，保留导图当前视野。"
        val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(180f, 900f, 0), InkSample(280f, 910f, 50), InkSample(380f, 930f, 100)))
        val f = Fixture(note, id(), id(), id(), id(), id(), id(), id(), id(), id(), id(), stroke, title, body)
        assertTrue("Synthetic title must respect the real card limit", title.length <= 120)
        assertTrue("Synthetic body must respect the real card limit", body.length <= 20_000)
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            app.pages.addAfter(note.id, f.sourcePage, f.otherPage)
            app.pages.select(note.id, note.id)
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.sourcePage, 0, InkMutation.Add(stroke))))
            val otherStroke = InkStroke(id(), InkPen.PEN, 0xff198a4b.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(620f, 300f, 0), InkSample(800f, 340f, 100)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.otherPage, 0, InkMutation.Add(otherStroke))))
            app.pageObjects.save(f.sourcePage, 0, id(), listOf(PageObject(id(), PageObjectKind.TEXT,
                80f, 80f, 760f, 100f, text = "SN57 当前原页 · 第二页", fontSize = 28f)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition("原图 · 保留阅读上下文")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                title = f.title, body = f.body, mapId = f.mapId,
                source = StudySourceDraft(f.sourcePage, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = f.child,
                parentId = f.node, title = "可折叠下级主题", body = "保留折叠状态", mapId = f.mapId, x = 320.0, y = 210.0))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.otherCard, nodeId = f.otherNode,
                title = "另一张有来源的卡片", body = f.otherBody, mapId = f.mapId, x = 520.0, y = 410.0,
                source = StudySourceDraft(f.otherPage, 1, otherStroke.bounds(), listOf(otherStroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.noSourceCard, nodeId = f.noSourceNode,
                title = "手动卡片 · 无来源", body = f.noSourceBody, mapId = f.mapId, x = 40.0, y = 520.0))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = f.card, nodeId = id()))
            // Keep OCR/model availability out of these navigation assertions.
            for (page in app.pages.activePages(note.id)) {
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            }
        }
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false).commit()
        compose.runOnIdle { provider()[NotebookViewModel::class.java].select(note) }
        compose.singlePageEditor()
        compose.waitForSavedInk()
        compose.frameCanvasFixture()
        assertPage(f, note.id, 0)
        tap("quick-study")
        tap("study-map-picker")
        tap("study-map-${f.mapId}")
        tap("study-tab-2")
        waitFor("study-map")
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle { ready = study(note.id).mapId.value == f.mapId && !study(note.id).ui.value.loading &&
                runCatching { native<MindMapView>().nodeBounds(f.node) != null }.getOrDefault(false) }
            ready
        }
        tap("study-readonly")
        assertReadOnly(f)
        selectNode(f.node)
        tap("node-fold")
        compose.runOnIdle {
            assertTrue(f.node in study(f.note.id).collapsedByMap[f.mapId].orEmpty())
            assertNull(native<MindMapView>().nodeBounds(f.child))
        }
        return f
    }

    private fun selectNode(node: String) {
        var point = Offset.Zero
        compose.runOnIdle {
            val map = native<MindMapView>()
            assertTrue(map.focusNode(node))
            val bounds = checkNotNull(map.nodeBounds(node))
            point = Offset(bounds.centerX(), bounds.centerY())
        }
        compose.waitForIdle()
        // This action deliberately selects once; a quick repeated read-only
        // double tap opens details directly and covers the nearby menu.
        compose.onNodeWithTag("study-map").performTouchInput {
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click(point)
        }
        waitFor("node-actions")
        compose.runOnIdle { assertEquals(node, native<MindMapView>().selectedNodeId) }
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
    }

    private fun openBody(node: String, body: String) {
        try {
            selectNode(node)
            tap("node-more")
            tap("node-view-content")
            waitFor("card-full-body")
            compose.onNodeWithTag("card-full-body").assertTextEquals(body)
            compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        } catch (error: Throwable) {
            runCatching { screenshot("sn57-open-body-failure.png") }
            runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach {
                println(compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString())
            } }
            throw error
        }
    }

    private fun mapState(f: Fixture): MapState = compose.runOnIdle {
        val vm = study(f.note.id)
        val map = native<MindMapView>()
        assertEquals(vm.selectedByMap[f.mapId], map.selectedNodeId)
        MapState(vm.mapId.value, vm.lastTab, map.selectedNodeId, map.snapshotViewport(), vm.viewports.toMap(),
            vm.collapsedByMap.mapValues { it.value.toList() }, vm.focusedByMap.toMap(), vm.selectedByMap.toMap())
    }

    /** Compare retained graph state while source reading may hide the native
     * map, then restore the original window and keep the original native checks.
     */
    private fun restoreMapAfterSource(f: Fixture, expected: MapState) {
        compose.onNodeWithTag("study-window-source-return").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            val vm = study(f.note.id)
            assertEquals(expected.mapId, vm.mapId.value)
            assertEquals(expected.tab, vm.lastTab)
            assertEquals(expected.viewports, vm.viewports.toMap())
            assertEquals(expected.collapsed, vm.collapsedByMap.mapValues { it.value.toList() })
            assertEquals(expected.focused, vm.focusedByMap.toMap())
            assertEquals(expected.selections, vm.selectedByMap.toMap())
            assertTrue(lock(f.note.id).readOnly.value)
            assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<InkCanvasView>().allowInput)
        }
        if (compose.onAllNodesWithTag("study-map").fetchSemanticsNodes().isEmpty()) {
            val panel = compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(48f * compose.activity.resources.displayMetrics.density, panel.height, 2f)
        }
        tapFooter("study-window-source-return")
        waitFor("study-map")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = !study(f.note.id).ui.value.loading &&
                runCatching { native<MindMapView>().nodeBounds(f.node) != null }.getOrDefault(false) }
            ready
        }
        assertEquals(expected, mapState(f))
        assertReadOnly(f)
    }

    private fun assertReadOnly(f: Fixture) {
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite }
            ready
        }
        compose.runOnIdle {
            assertTrue(lock(f.note.id).readOnly.value)
            assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<MindMapView>().authorEditing)
            assertFalse(native<InkCanvasView>().allowInput)
        }
    }

    private fun assertPage(f: Fixture, page: String, visibleStrokes: Int) {
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle {
                ready = provider()[NotebookViewModel::class.java].ui.value.selectedId == f.note.id &&
                    pages(f.note.id).ui.value.selectedId == page && !ink(page).ui.value.loading &&
                    runCatching { val canvas = native<InkCanvasView>(); canvas.documentContentReady &&
                        !canvas.rasterPending && canvas.displayedStrokeCount == visibleStrokes }.getOrDefault(false)
            }
            ready && runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId == page }
        }
        compose.runOnIdle {
            assertEquals(f.note.id, provider()[NotebookViewModel::class.java].ui.value.selectedId)
            assertEquals(page, pages(f.note.id).ui.value.selectedId)
            assertEquals(visibleStrokes, native<InkCanvasView>().displayedStrokeCount)
        }
        assertEquals(page, runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId })
    }

    private fun assertFocus(source: StudySourceRow) {
        val bounds = CanvasBounds(source.left, source.top, source.right, source.bottom)
        compose.waitUntil(15_000) { var focused = false
            compose.runOnIdle {
                val canvas = native<InkCanvasView>()
                val viewport = canvas.snapshotViewport()
                focused = abs(viewport.centerX - (bounds.left + bounds.right) / 2) < .5 &&
                    abs(viewport.centerY - (bounds.top + bounds.bottom) / 2) < .5
            }
            focused
        }
        compose.runOnIdle {
            val canvas = native<InkCanvasView>()
            val density = canvas.resources.displayMetrics.density.toDouble()
            val expected = CanvasViewport.fit(bounds.padded(60.0), canvas.width / density, canvas.height / density)
            val actual = canvas.snapshotViewport()
            assertEquals(expected.centerX, actual.centerX, .5)
            assertEquals(expected.centerY, actual.centerY, .5)
            assertEquals(expected.zoom, actual.zoom, .01)
            val visible = actual.visible(canvas.width.toDouble(), canvas.height.toDouble(), density)
            assertTrue("The actual paper viewport must contain the captured region", visible.left <= bounds.left &&
                visible.top <= bounds.top && visible.right >= bounds.right && visible.bottom >= bounds.bottom)
        }
    }

    private fun source(f: Fixture) = runBlocking { checkNotNull(app.study.source(f.card)) }
    private fun snapshot(source: StudySourceRow) = sha(source.snapshot)
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

    /** Every author value/history/receipt remains exact. Page selection and canvas
     * viewport are legitimate browsing state and are asserted separately above.
     * Each fixture content/trash mutation finishes before its next baseline.
     */
    private fun authorStamp(book: String): List<String> = runBlocking {
        probe.withTransaction { buildList {
            for (sql in authorQueries) {
                add(sql)
                probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
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
        } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext() {
        val f = seed()
        val originalSource = source(f)
        val before = authorStamp(f.note.id)
        openBody(f.node, f.body)
        waitFor("card-source-section")
        compose.onNodeWithTag("card-source-section").assertTextEquals("查看来源")
        compose.onNodeWithTag("excerpt-preview-${f.card}").assertDoesNotExist()
        compose.onNodeWithTag("study-open-source").assertTextEquals("回原文")
        compose.onNodeWithTag("card-back").assertTextEquals("返回原节点")
        val context = mapState(f)
        tapFooter("study-open-source")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.onNodeWithTag("study-panel").assertIsDisplayed()
        assertPage(f, f.sourcePage, 1)
        assertFocus(originalSource)
        restoreMapAfterSource(f, context)
        assertReadOnly(f)
        assertEquals(context, mapState(f))
        assertEquals(before, authorStamp(f.note.id))
        screenshot("sn57-collapsed-source-return.png")

        openBody(f.otherNode, f.otherBody)
        waitFor("study-open-source")
        val otherSource = runBlocking { checkNotNull(app.study.source(f.otherCard)) }
        assertEquals(f.otherCard, otherSource.cardId)
        assertEquals(f.otherPage, otherSource.pageId)
        val otherContext = mapState(f)
        tapFooter("study-open-source")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        assertPage(f, f.otherPage, 1)
        assertFocus(otherSource)
        restoreMapAfterSource(f, otherContext)
        assertReadOnly(f)
        assertEquals(otherContext, mapState(f))
        assertEquals(before, authorStamp(f.note.id))
        // Source-only inspection sets inspectSource. A successful return must
        // clear that mode so another card's full body can still be opened.
        selectNode(f.node)
        tap("node-source")
        waitFor("excerpt-preview-${f.card}")
        compose.onNodeWithTag("card-full-body").assertDoesNotExist()
        val inspectContext = mapState(f)
        tapFooter("study-open-source")
        assertPage(f, f.sourcePage, 1)
        assertFocus(originalSource)
        restoreMapAfterSource(f, inspectContext)
        assertReadOnly(f)
        assertEquals(inspectContext, mapState(f))
        openBody(f.otherNode, f.otherBody)
        assertEquals(snapshot(originalSource), snapshot(source(f)))
        assertEquals(before, authorStamp(f.note.id))
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun assertFooterBounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithTag("study-card-details").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("$tag needs a real 48dp wide target", bounds.width >= 48f * density - 1f)
        assertTrue("$tag needs a real 48dp high target", bounds.height >= 48f * density - 1f)
        assertTrue("$tag must stay inside the card panel", bounds.left >= details.left - 1f && bounds.top >= details.top - 1f &&
            bounds.right <= details.right + 1f && bounds.bottom <= details.bottom + 1f)
        assertTrue("$tag must stay inside the 375dp window", bounds.left >= 0 && bounds.right <= 375f * density + 2f)
        return bounds
    }

    private fun bodyScroll() = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("study-card-details")), useUnmergedTree = true)
    private fun bodyScrollOffset(): Float = bodyScroll().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun narrowLargeFontKeepsFooterReachableAfterLongBodyScrollAndRecreation() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600")
            shell("wm density 320")
            shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val config = compose.activity.resources.configuration
                abs(config.screenWidthDp - 375) <= 4 && abs(config.fontScale - 1.6f) < .02f }
            val f = seed(longContent = true)
            val originalSource = source(f)
            val before = authorStamp(f.note.id)
            openBody(f.node, f.body)
            waitFor("study-open-source")
            compose.onNodeWithTag("card-source-section").assertTextEquals("查看来源")
            compose.onNodeWithText(f.title, useUnmergedTree = true).assertExists()
            val context = mapState(f)
            val sourceFooter = assertFooterBounds("study-open-source")
            val backFooter = assertFooterBounds("card-back")
            val scrollBefore = bodyScrollOffset()
            bodyScroll().performTouchInput { swipeUp(durationMillis = 350) }
            compose.waitForIdle()
            assertTrue("A real touch must scroll the long body", bodyScrollOffset() > scrollBefore + 1f)
            assertEquals(sourceFooter, assertFooterBounds("study-open-source"))
            assertEquals(backFooter, assertFooterBounds("card-back"))
            compose.onNodeWithTag("excerpt-preview-${f.card}").assertDoesNotExist()
            assertEquals(context, mapState(f))
            assertEquals(before, authorStamp(f.note.id))

            compose.activityRule.scenario.recreate()
            waitFor("study-card-details")
            waitFor("study-open-source")
            waitFor("card-full-body")
            compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
            compose.onNodeWithText(f.title, useUnmergedTree = true).assertExists()
            assertEquals(sourceFooter, assertFooterBounds("study-open-source"))
            assertEquals(backFooter, assertFooterBounds("card-back"))
            assertTrue("Recreation retains the body reading position", bodyScrollOffset() > scrollBefore + 1f)
            assertEquals(context, mapState(f))
            assertReadOnly(f)
            assertPage(f, f.note.id, 0)
            assertEquals(before, authorStamp(f.note.id))
            screenshot("sn57-narrow-fixed-footer.png")
            tapFooter("study-open-source")
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            assertPage(f, f.sourcePage, 1)
            assertFocus(originalSource)
            restoreMapAfterSource(f, context)
            assertReadOnly(f)
            assertEquals(context, mapState(f))
            assertEquals(before, authorStamp(f.note.id))
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale $oldFont" else "settings delete system font_scale")
        }
    }

    private fun showSource(f: Fixture) {
        waitFor("card-source-section")
        if (runCatching { compose.onNodeWithTag("card-source-section").assertTextEquals("查看来源") }.isSuccess) tap("card-source-section")
        waitFor("excerpt-preview-${f.card}")
        compose.onNodeWithTag("excerpt-preview-${f.card}").performScrollTo().assertIsDisplayed()
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { val preview = native<InkCanvasView>(preview = true)
                ready = preview.displayedStrokeCount == 1 && !preview.rasterPending }
            ready
        }
    }

    @Test fun missingAndRecycledSourcesStayScopedWhileStaleSnapshotReturnsToCurrentPaper() {
        val f = seed()
        val originalSource = source(f)
        val originalSnapshot = snapshot(originalSource)
        val initialStamp = authorStamp(f.note.id)
        openBody(f.node, f.body)
        waitFor("study-open-source")
        tapFooter("card-back")
        openBody(f.noSourceNode, f.noSourceBody)
        assertNull(runBlocking { app.study.source(f.noSourceCard) })
        compose.onNodeWithTag("study-open-source").assertDoesNotExist()
        compose.onNodeWithTag("card-source-section").assertDoesNotExist()
        compose.onNodeWithTag("excerpt-preview-${f.card}").assertDoesNotExist()
        assertEquals(initialStamp, authorStamp(f.note.id))
        tapFooter("card-back")

        // A deliberate repository edit supplies the changed-source fixture.
        // Its author write is completed before the navigation-only baseline.
        val currentStroke = InkStroke(id(), InkPen.PEN, 0xffba3737.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(190f, 915f, 0), InkSample(360f, 930f, 100)))
        runBlocking {
            assertEquals(InkCommitResult.Committed(2), app.inkRepository.save(CommitInk(id(), f.sourcePage, 1, InkMutation.Add(currentStroke))))
            assertTrue(app.pages.saveSearchText(f.sourcePage, 2, "", method = "MANUAL"))
        }
        val changedStamp = authorStamp(f.note.id)
        openBody(f.node, f.body)
        showSource(f)
        compose.onNodeWithText("来源页面已变化，下方保留摘录时快照。", useUnmergedTree = true).assertExists()
        assertEquals(originalSnapshot, snapshot(source(f)))
        assertEquals(f.originalStroke.id, InkPageFile.decode(source(f).snapshot).strokes.single().id)
        assertEquals(1L, source(f).inkRevision)
        val changedContext = mapState(f)
        tapFooter("study-open-source")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        assertPage(f, f.sourcePage, 2)
        assertFocus(originalSource)
        restoreMapAfterSource(f, changedContext)
        compose.runOnIdle { assertEquals(2L, ink(f.sourcePage).ui.value.revision) }
        assertTrue(runBlocking { app.inkRepository.read(f.sourcePage).strokes.any { it.stroke.id == currentStroke.id && it.visible } })
        assertReadOnly(f)
        assertEquals(changedContext, mapState(f))
        assertEquals(changedStamp, authorStamp(f.note.id))

        // Recycling is fixture preparation, not an operation performed by the
        // read-only UI. Keep an unrelated active page selected before failure.
        compose.runOnIdle { pages(f.note.id).select(f.note.id) }
        assertPage(f, f.note.id, 0)
        val trash = runBlocking {
            app.pages.edit(EditPage(id(), f.note.id, f.sourcePage, PageEditKind.TRASH,
                InsertPages.orderHash(app.pages.activePages(f.note.id).map { it.id }), 2,
                stayOnPageId = f.note.id))
        }
        assertTrue("Synthetic source recycle must really commit", trash is EditPageResult.Applied)
        compose.waitUntil(15_000) { var recycled = false
            compose.runOnIdle { recycled = pages(f.note.id).ui.value.pages.none { it.id == f.sourcePage } &&
                pages(f.note.id).ui.value.recycled.any { it.id == f.sourcePage } }
            recycled
        }
        val recycledStamp = authorStamp(f.note.id)
        openBody(f.node, f.body)
        showSource(f)
        val unavailableContext = mapState(f)
        val paperViewport = compose.runOnIdle { native<InkCanvasView>().snapshotViewport() }
        tapFooter("study-open-source")
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        showSource(f)
        assertPage(f, f.note.id, 0)
        assertReadOnly(f)
        assertEquals(paperViewport, compose.runOnIdle { native<InkCanvasView>().snapshotViewport() })
        assertEquals(unavailableContext, mapState(f))
        assertEquals(originalSnapshot, snapshot(source(f)))
        assertEquals(recycledStamp, authorStamp(f.note.id))
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        screenshot("sn57-unavailable-source.png")
        tapFooter("card-back")
        openBody(f.otherNode, f.otherBody)
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertDoesNotExist()
        val otherContext = mapState(f)
        val otherSource = runBlocking { checkNotNull(app.study.source(f.otherCard)) }
        tapFooter("study-open-source")
        assertPage(f, f.otherPage, 1)
        assertFocus(otherSource)
        restoreMapAfterSource(f, otherContext)
        assertReadOnly(f)
        assertEquals(otherContext, mapState(f))
        assertEquals(recycledStamp, authorStamp(f.note.id))
    }
}
