// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
import kotlin.math.abs

/** V58 awaitable Library source regression.
 * Real Activity/repositories/native map/paper. Slow navigation is real SQLite
 * application Room transaction queue; there is no fake navigation model or replacement callback.
 * All content is synthetic. Real tablet/pen acceptance remains untested.
 */
class LibrarySourceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: (org.inkweft.data.WorkspaceRepository::class.java
        .getDeclaredField("db").apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase)
        .also { probeDatabase = it }
    private lateinit var oldLearningPreferences: Map<String, *>
    private fun id() = UUID.randomUUID().toString()
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = provider()[NotebookViewModel::class.java]
    private fun workspace() = provider()[WorkspaceViewModel::class.java]
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    private fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]
    private fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]

    @Before fun prepareLearningWidgets() {
        val preferences = app.getSharedPreferences("inkweft-learning", 0)
        oldLearningPreferences = preferences.all
        preferences.edit().clear().commit()
        app.learningStore.configure(LearningWidgets.defaults())
    }

    @After fun restoreLearningPreferencesAndCloseProbe() {
        if (::oldLearningPreferences.isInitialized) {
            val editor = app.getSharedPreferences("inkweft-learning", 0).edit().clear()
            oldLearningPreferences.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
        probeDatabase = null // The app owns this shared database; never close it here.
    }

    private data class Fixture(
        val note: Note, val unrelated: Note, val sourcePage: String, val secondPage: String,
        val mapId: String, val mapTitle: String, val card: String, val node: String,
        val child: String, val secondCard: String, val secondNode: String, val stroke: InkStroke,
        val body: String = "从资料库学习页阅读这张卡片。回原文要打开保存的第二页和摘录区域，并继续保持阅读锁。",
        val secondBody: String = "另一张卡片有自己的第三页来源；旧请求不能关闭或覆盖这张详情。",
    )

    private data class SessionState(
        val mapId: String?, val tab: Int, val viewports: Map<String, MapViewport>,
        val collapsed: Map<String, List<String>>, val focused: Map<String, String?>,
        val selected: Map<String, String?>,
    )

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.revealAction(tag)
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
    }

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
        error("Visible native ${T::class.java.simpleName} missing")
    }

    private fun seed(): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("LS58 学习回源 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val unrelated = runBlocking { app.workspaceRepository.create("LS58 另一份资料 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(180f, 900f, 0), InkSample(280f, 910f, 50), InkSample(380f, 930f, 100)))
        val f = Fixture(note, unrelated, id(), id(), id(), "学习回源图 " + id().take(8), id(), id(), id(), id(), id(), stroke)
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            app.pages.addAfter(note.id, f.sourcePage, f.secondPage)
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.sourcePage, 0, InkMutation.Add(stroke))))
            val secondStroke = InkStroke(id(), InkPen.PEN, 0xff198a4b.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(600f, 300f, 0), InkSample(820f, 340f, 100)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.secondPage, 0, InkMutation.Add(secondStroke))))
            app.pageObjects.save(f.sourcePage, 0, id(), listOf(PageObject(id(), PageObjectKind.TEXT,
                80f, 80f, 760f, 100f, text = "第二页 · 当前原资料", fontSize = 28f)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition(f.mapTitle)))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                title = "第一张来源卡", body = f.body, mapId = f.mapId,
                source = StudySourceDraft(f.sourcePage, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = f.child,
                parentId = f.node, title = "保留折叠主题", body = "只浏览，不改图结构", mapId = f.mapId, x = 320.0, y = 210.0))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.secondCard, nodeId = f.secondNode,
                title = "第二张来源卡", body = f.secondBody, mapId = f.mapId, x = 500.0, y = 480.0,
                source = StudySourceDraft(f.secondPage, 1, secondStroke.bounds(), listOf(secondStroke.id))))
            for (book in listOf(note.id, unrelated.id)) for (page in app.pages.activePages(book)) {
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            }
            app.pages.select(note.id, note.id)
        }
        // Warm the real retained BookPagesViewModel at page 1. Returning from
        // learning must override that earlier selection and exit continuous mode.
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", true).commit()
        compose.runOnIdle { notebook().select(note) }
        waitFor("continuous-pages")
        compose.waitUntil(15_000) { app.navigationReady.value }
        compose.runOnIdle { assertEquals(note.id, pages(note.id).ui.value.selectedId) }
        tap("back-library")
        waitFor("new-note")
        openLearningMap(f)
        if (!compose.runOnIdle { lock(note.id).readOnly.value }) tap("study-mode-read")
        assertReadOnly(f, paperVisible = false)
        selectNode(f.node)
        tap("node-fold")
        compose.runOnIdle {
            assertTrue(f.node in study(note.id).collapsedByMap[f.mapId].orEmpty())
            assertNull(native<MindMapView>().nodeBounds(f.child))
        }
        return f
    }

    private fun openLearningMap(f: Fixture) {
        if (compose.onAllNodesWithTag("learning-workbench").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
            compose.onNodeWithText("学习", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        }
        waitFor("learning-workbench")
        // Re-entry changes recent-row heights while the directory reloads. A lazy
        // descendant may exist before it is placed; position its owning item by
        // the actual widget key before reading/touching the descendant's bounds.
        val mapsKey = app.learningStore.read().widgets.single {
            it.definition == "org.inkweft/maps" && it.visible
        }.id
        val widgets = compose.onNode(hasScrollToKeyAction() and hasAnyAncestor(hasTestTag("learning-workbench")))
        widgets.assertIsDisplayed().performScrollToKey(mapsKey)
        waitFor("learning-map-search")
        compose.onNodeWithTag("learning-map-search").assertIsDisplayed().performTextReplacement(f.mapTitle)
        val target = hasTestTag("learning-target-${f.mapId}") and hasAnyAncestor(hasTestTag("widget-maps"))
        compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        widgets.performScrollToKey(mapsKey)
        compose.onNode(target).performScrollTo().assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        waitFor("study-map")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = study(f.note.id).mapId.value == f.mapId && !study(f.note.id).ui.value.loading &&
                runCatching { native<MindMapView>().nodeBounds(f.node) != null }.getOrDefault(false) }
            ready
        }
        assertInLibrary(f)
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
        compose.onNodeWithTag("study-map").performTouchInput {
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click(point)
        }
        waitFor("node-actions")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(node, native<MindMapView>().selectedNodeId) }
    }

    private fun openBody(node: String, body: String, awaitSource: Boolean = true) {
        selectNode(node)
        tap("node-more")
        tap("node-view-content")
        waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        if (awaitSource) waitFor("study-open-source")
    }

    private fun session(f: Fixture): SessionState = compose.runOnIdle {
        val vm = study(f.note.id)
        SessionState(vm.mapId.value, vm.lastTab, vm.viewports.toMap(), vm.collapsedByMap.mapValues { it.value.toList() },
            vm.focusedByMap.toMap(), vm.selectedByMap.toMap())
    }

    /** Safe while the writer gate is held: these are loaded Activity UI states. */
    private fun assertInLibrary(f: Fixture) {
        compose.runOnIdle {
            assertNull(notebook().ui.value.selectedId)
            assertFalse(workspace().pendingPageNavigation.value.containsKey(f.note.id))
            assertNull(workspace().focusAnchor.value)
        }
        compose.onAllNodesWithTag("ink-surface").assertCountEquals(0)
        compose.onAllNodesWithTag("continuous-pages").assertCountEquals(0)
    }

    private fun assertReadOnly(f: Fixture, paperVisible: Boolean) {
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite }
            ready
        }
        compose.runOnIdle {
            assertTrue(lock(f.note.id).readOnly.value)
            assertFalse(lock(f.note.id).canWrite)
            if (paperVisible) assertFalse(native<InkCanvasView>().allowInput)
            else assertFalse(native<MindMapView>().authorEditing)
        }
    }

    private fun assertPaper(f: Fixture, page: String, source: StudySourceRow, count: Int = 1) {
        waitFor("ink-surface")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle {
                ready = notebook().ui.value.selectedId == f.note.id && pages(f.note.id).ui.value.selectedId == page &&
                    !ink(page).ui.value.loading && runCatching { val canvas = native<InkCanvasView>()
                        canvas.documentContentReady && !canvas.rasterPending && canvas.displayedStrokeCount == count &&
                            abs(canvas.snapshotViewport().centerX - (source.left + source.right) / 2) < .5 &&
                            abs(canvas.snapshotViewport().centerY - (source.top + source.bottom) / 2) < .5 }.getOrDefault(false)
            }
            ready
        }
        compose.onNodeWithTag("continuous-pages").assertDoesNotExist()
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(f.note.id, notebook().ui.value.selectedId)
            assertEquals(page, pages(f.note.id).ui.value.selectedId)
            assertFalse(workspace().pendingPageNavigation.value.containsKey(f.note.id))
            assertNull(workspace().focusAnchor.value)
            val canvas = native<InkCanvasView>()
            val density = canvas.resources.displayMetrics.density.toDouble()
            val bounds = CanvasBounds(source.left, source.top, source.right, source.bottom)
            val expected = CanvasViewport.fit(bounds.padded(60.0), canvas.width / density, canvas.height / density)
            assertEquals(expected.centerX, canvas.snapshotViewport().centerX, .5)
            assertEquals(expected.centerY, canvas.snapshotViewport().centerY, .5)
            assertEquals(expected.zoom, canvas.snapshotViewport().zoom, .01)
            val visible = canvas.snapshotViewport().visible(canvas.width.toDouble(), canvas.height.toDouble(), density)
            assertTrue(visible.left <= bounds.left && visible.top <= bounds.top && visible.right >= bounds.right && visible.bottom >= bounds.bottom)
        }
        assertEquals(page, runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId })
        assertFalse(app.getSharedPreferences("inkweft-reading", 0).getBoolean("continuous-v20-${f.note.id}", true))
        assertReadOnly(f, paperVisible = true)
    }

    private fun source(card: String) = runBlocking { checkNotNull(app.study.source(card)) }
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

    /** Author state/history/receipts remain exact; selection and viewport are
     * legitimate browsing state. Capture/read this only outside a writer gate.
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

    private fun assertNoAuthorChanges(f: Fixture, before: List<String>, unrelatedBefore: List<String>) {
        assertEquals(before, authorStamp(f.note.id))
        assertEquals(unrelatedBefore, authorStamp(f.unrelated.id))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun learningSourceAwaitsRealPageSelectionThenFocusesOriginalBoundsWithReadLock() {
        val f = seed()
        val original = source(f.card)
        val before = authorStamp(f.note.id)
        val unrelatedBefore = authorStamp(f.unrelated.id)
        openBody(f.node, f.body)
        compose.onNodeWithTag("card-source-section").assertTextEquals("查看来源")
        compose.onNodeWithTag("excerpt-preview-${f.card}").assertDoesNotExist()
        val context = session(f)
        val viewport = compose.runOnIdle { native<MindMapView>().snapshotViewport() }
        tapFooter("study-open-source")
        assertPaper(f, f.sourcePage, original)
        assertEquals(context, session(f))
        assertEquals(sha(original.snapshot), sha(source(f.card).snapshot))
        assertNoAuthorChanges(f, before, unrelatedBefore)
        screenshot("ls58-exact-source.png")

        tap("back-library")
        waitFor("new-note")
        openLearningMap(f)
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        assertEquals(context, session(f))
        compose.runOnIdle { assertEquals(viewport, native<MindMapView>().snapshotViewport()) }
        assertReadOnly(f, paperVisible = false)
        openBody(f.secondNode, f.secondBody)
        assertNoAuthorChanges(f, before, unrelatedBefore)
    }

    @Test fun recycledLibrarySourceRetainsCurrentCardSnapshotAndMapWithoutNavigating() {
        val f = seed()
        val original = source(f.card)
        val result = runBlocking { app.pages.edit(EditPage(id(), f.note.id, f.sourcePage, PageEditKind.TRASH,
            InsertPages.orderHash(app.pages.activePages(f.note.id).map { it.id }), 1, stayOnPageId = f.note.id)) }
        assertTrue(result is EditPageResult.Applied)
        compose.waitUntil(15_000) { var removed = false
            compose.runOnIdle { removed = pages(f.note.id).ui.value.pages.none { it.id == f.sourcePage } }
            removed
        }
        val before = authorStamp(f.note.id)
        val unrelatedBefore = authorStamp(f.unrelated.id)
        val selected = runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId }
        openBody(f.node, f.body)
        val context = session(f)
        val viewport = compose.runOnIdle { native<MindMapView>().snapshotViewport() }
        tapFooter("study-open-source")
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(
            "来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertExists() }.isSuccess }
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().assertIsEnabled()
        assertInLibrary(f)
        assertReadOnly(f, paperVisible = false)
        assertEquals(context, session(f))
        compose.runOnIdle { assertEquals(viewport, native<MindMapView>().snapshotViewport()) }
        assertEquals(selected, runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId })
        tap("card-source-section")
        waitFor("excerpt-preview-${f.card}")
        compose.onNodeWithTag("excerpt-preview-${f.card}").performScrollTo().assertIsDisplayed()
        compose.waitUntil(15_000) { var visible = false
            compose.runOnIdle { visible = native<InkCanvasView>(preview = true).displayedStrokeCount == 1 }
            visible
        }
        assertEquals(f.stroke.id, InkPageFile.decode(source(f.card).snapshot).strokes.single().id)
        assertEquals(sha(original.snapshot), sha(source(f.card).snapshot))
        assertNoAuthorChanges(f, before, unrelatedBefore)
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        screenshot("ls58-recycled-source.png")
        tapFooter("card-back")
        openBody(f.secondNode, f.secondBody)
        assertNoAuthorChanges(f, before, unrelatedBefore)
    }

    /** Holding a real WAL writer transaction blocks the real repository select.
     * The transaction performs no author mutation. Every use releases/joins it
     * before any transactional DB assertion or test cleanup can run.
     */
    private inner class WriterGate(private val label: String) {
        val entered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()
        private val completed = CompletableDeferred<Unit>()
        private val database = probe
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val job = scope.launch {
            try {
                database.withTransaction {
                    android.util.Log.i("InkWeft-LS58", "$label: actual app transaction held")
                    entered.complete(Unit); release.await()
                }
                completed.complete(Unit)
            } catch (error: Throwable) {
                if (!entered.isCompleted) entered.completeExceptionally(error)
                completed.completeExceptionally(error)
            }
        }

        fun awaitHeld() {
            compose.waitUntil(15_000) { entered.isCompleted }
            runBlocking { entered.await() }
        }

        fun finish() {
            release.complete(Unit)
            try { runBlocking { withTimeout(15_000) { completed.await(); job.join() } } }
            finally { scope.cancel() }
            android.util.Log.i("InkWeft-LS58", "$label: actual app transaction completed")
        }
    }

    // Observe Room's existing serial transaction queue without issuing another
    // database read behind the held writer (same probe used by collection tests).
    private fun transactionQueueDepth(): Int {
        val executor = probe.transactionExecutor
        val field = generateSequence(executor.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .single { java.util.Collection::class.java.isAssignableFrom(it.type) }
        return synchronized(executor) {
            field.isAccessible = true
            (field.get(executor) as Collection<*>).size
        }
    }

    private fun beginHeldNavigation(label: String): WriterGate {
        val gate = WriterGate(label)
        var queueBefore = "not sampled"
        try {
            gate.awaitHeld()
            queueBefore = transactionQueueDepth().toString()
            tapFooter("study-open-source")
            compose.waitUntil("$label: the touched source request remains pending behind Room", 10_000) {
                runCatching { compose.onNodeWithTag("study-open-source")
                    .assertTextEquals("正在定位…").assertIsNotEnabled() }.isSuccess
            }
            compose.onNodeWithTag("study-card-details").assertIsDisplayed()
            compose.onNodeWithTag("card-back").assertIsDisplayed().assertIsEnabled()
            return gate
        } catch (error: Throwable) {
            // Attach synthetic-fixture diagnostics to the original failure. stdout
            // goes to Android logcat and is absent from am instrument's result.
            val diagnostic = StringBuilder("LS58 $label: Room queue before touch=$queueBefore")
            runCatching { diagnostic.append("\nRoom queue at failure=${transactionQueueDepth()}") }.onFailure(error::addSuppressed)
            runCatching { screenshot("ls58-$label-navigation-failure.png") }.onFailure(error::addSuppressed)
            runCatching { compose.runOnIdle { diagnostic.append("\nselected=${notebook().ui.value.selectedId}, " +
                "pending=${workspace().pendingPageNavigation.value}, error=${workspace().error.value}") } }.onFailure(error::addSuppressed)
            runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach {
                diagnostic.append("\n").append(compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString())
            } }.onFailure(error::addSuppressed)
            error.addSuppressed(AssertionError(diagnostic.toString()))
            runCatching { gate.finish() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun settleReleasedNavigation(f: Fixture) {
        // The actual app's transaction queue is a fence behind the released
        // navigation transaction. Keep DB assertions out of the held interval.
        runBlocking { withTimeout(15_000) { app.workspaceRepository.get(f.note.id) } }
        compose.waitForIdle()
    }

    private fun awaitRecreatedStudyLayout(f: Fixture) {
        waitFor("study-map")
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle {
                ready = notebook().ui.value.selectedId == null && study(f.note.id).mapId.value == f.mapId &&
                    !study(f.note.id).ui.value.loading && lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite &&
                    runCatching { val map = native<MindMapView>(); map.width > 0 && map.height > 0 &&
                        !map.authorEditing && map.nodeBounds(f.secondNode) != null }.getOrDefault(false)
            }
            ready
        }
        compose.waitForIdle()
    }

    @Test fun closingOrRecreatingPendingLibrarySourceCannotJumpOrCloseAnotherCard() {
        val f = seed()
        val before = authorStamp(f.note.id)
        val unrelatedBefore = authorStamp(f.unrelated.id)
        openBody(f.node, f.body)
        val firstGate = beginHeldNavigation("close-card")
        try {
            assertInLibrary(f)
            tapFooter("card-back")
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            // The replacement card's sources() uses this same Room transaction
            // queue. Its body can open now; its source must reload after release.
            openBody(f.secondNode, f.secondBody, awaitSource = false)
            compose.onNodeWithTag("study-open-source").assertDoesNotExist()
            assertInLibrary(f)
        } finally { firstGate.finish() }
        settleReleasedNavigation(f)
        waitFor("study-open-source")
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        compose.onNodeWithTag("study-open-source").assertIsEnabled()
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertDoesNotExist()
        assertInLibrary(f)
        assertReadOnly(f, paperVisible = false)
        assertNoAuthorChanges(f, before, unrelatedBefore)

        val secondContext = session(f)
        val secondGate = beginHeldNavigation("recreate")
        try {
            compose.activityRule.scenario.recreate()
            waitFor("study-card-details")
            waitFor("card-full-body")
            awaitRecreatedStudyLayout(f)
            compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
            // Recreated transient source state must not reuse the old request;
            // its new database read cannot finish while this gate is still held.
            compose.onNodeWithTag("study-open-source").assertDoesNotExist()
            assertInLibrary(f)
        } finally { secondGate.finish() }
        settleReleasedNavigation(f)
        waitFor("study-open-source")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertDoesNotExist()
        assertInLibrary(f)
        assertReadOnly(f, paperVisible = false)
        assertEquals(secondContext, session(f))
        assertNoAuthorChanges(f, before, unrelatedBefore)

        val closeGate = beginHeldNavigation("close-window")
        try {
            tap("study-close")
            waitFor("learning-workbench")
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            assertInLibrary(f)
        } finally { closeGate.finish() }
        settleReleasedNavigation(f)
        compose.onNodeWithTag("learning-workbench").assertIsDisplayed()
        assertInLibrary(f)
        assertNoAuthorChanges(f, before, unrelatedBefore)
        openLearningMap(f)
        if (compose.onAllNodesWithTag("study-card-details").fetchSemanticsNodes().isNotEmpty()) tapFooter("card-back")
        openBody(f.secondNode, f.secondBody)
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(secondContext, session(f))
        assertReadOnly(f, paperVisible = false)
        screenshot("ls58-cancel-recreate.png")

        // A fresh touch after cancellation must still work for the current card.
        val currentSource = source(f.secondCard)
        tapFooter("study-open-source")
        assertPaper(f, f.secondPage, currentSource)
        assertEquals(secondContext, session(f))
        assertNoAuthorChanges(f, before, unrelatedBefore)
    }
}
