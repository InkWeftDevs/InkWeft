// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.RectF
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** V60 draft support, extracted from the real V57/V58/V59 fixtures and checks.
 * One application Room database and its actual transaction queue are used.
 * No separate connection, fake repository, retry, database close or private UI
 * state injection. The sole reflected VM field is the existing Mutex, observed
 * to prove the await has entered the production selection path before cancel.
 */
internal class SelectAwaitTestSupport(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
) {
    val app get() = compose.activity.application as InkWeftApplication
    private fun provider() = ViewModelProvider(compose.activity)
    fun notebook() = provider()[NotebookViewModel::class.java]
    fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]
    fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]
    private val database get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private val heldGates = AtomicInteger(0)
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableListOf<Deferred<Boolean>>()
    private var savedPreferences: Map<String, Map<String, *>> = emptyMap()
    private fun id() = UUID.randomUUID().toString()

    fun captureSettings() {
        savedPreferences = listOf("inkweft-study-window", "inkweft-reading", "inkweft-learning")
            .associateWith { app.getSharedPreferences(it, 0).all }
    }

    fun closeAndRestoreSettings() {
        requestScope.cancel()
        runBlocking { withTimeout(15_000) { requests.forEach { it.join() } } }
        try {
            check(heldGates.get() == 0) { "Every owned Room gate must be released and joined before cleanup" }
            compose.runOnIdle { app.openKnowledgeTarget.value = null; notebook().back() }
            waitFor("new-note")
        } finally {
            savedPreferences.forEach { (name, values) ->
                val editor = app.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> putPreference(editor, key, value) }
                assertTrue("Restore $name", editor.commit())
            }
        }
    }

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

    data class Fixture(
        val note: Note, val unrelated: Note, val sourcePage: String, val secondPage: String,
        val mapId: String, val card: String, val node: String, val child: String,
        val secondCard: String, val secondNode: String,
        val body: String = "第一张来源卡：必须等第二页实际选中，才关闭详情并聚焦原迹。",
        val secondBody: String = "第二张来源卡：旧请求不能关闭这张新详情或产生过期错误。",
    )

    fun seed(): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("SA60 原文等待 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val unrelated = runBlocking { app.workspaceRepository.create("SA60 隔离资料 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val f = Fixture(note, unrelated, id(), id(), id(), id(), id(), id(), id(), id())
        val firstInk = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(180f, 900f, 0), InkSample(280f, 910f, 50), InkSample(380f, 930f, 100)))
        val secondInk = InkStroke(id(), InkPen.PEN, 0xff198a4b.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(600f, 300f, 0), InkSample(820f, 340f, 100)))
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            app.pages.addAfter(note.id, f.sourcePage, f.secondPage)
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.sourcePage, 0, InkMutation.Add(firstInk))))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.secondPage, 0, InkMutation.Add(secondInk))))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), unrelated.id, 0, InkMutation.Add(
                InkStroke(id(), InkPen.PEN, 0xff4a4a4a.toInt(), 4f, InkTool.STYLUS,
                    listOf(InkSample(120f, 300f, 0), InkSample(340f, 340f, 100)))))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition("原迹等待图")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                title = "等真实第二页", body = f.body, mapId = f.mapId,
                source = StudySourceDraft(f.sourcePage, 1, firstInk.bounds(), listOf(firstInk.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = f.child,
                title = "保留折叠子节点", body = "只有夹具建立此结构", parentId = f.node, mapId = f.mapId, x = 320.0, y = 210.0))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.secondCard, nodeId = f.secondNode,
                title = "第三页来源卡", body = f.secondBody, mapId = f.mapId, x = 500.0, y = 480.0,
                source = StudySourceDraft(f.secondPage, 1, secondInk.bounds(), listOf(secondInk.id))))
            for (book in listOf(note.id, unrelated.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            app.pages.select(note.id, note.id)
        }
        app.getSharedPreferences("inkweft-study-window", 0).edit().putString("${note.id}-mode", "FOCUS").commit()
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false).commit()
        compose.runOnIdle { notebook().select(note) }
        // Room loading runs outside Compose's virtual clock. Wait for the actual
        // editor and its write gate before asking Espresso to settle the UI.
        compose.waitUntil("single-page editor data and authoring are ready", 60_000) {
            compose.runOnUiThread {
                runCatching {
                    native<InkCanvasView>()
                    !ink(note.id).ui.value.loading &&
                        provider()["authoring-${note.id}", PageAuthoringViewModel::class.java].ui.value.ready &&
                        app.navigationReady.value
                }.getOrDefault(false)
            }
        }
        compose.singlePageEditor(); compose.waitForSavedInk()
        tap("quick-study"); tap("study-map-picker"); tap("study-map-${f.mapId}"); tap("study-tab-2")
        waitMap(f)
        if (!compose.runOnIdle { lock(note.id).readOnly.value }) tap("quick-readonly")
        assertReadOnly(f)
        selectNode(f.node); tap("node-fold")
        compose.runOnIdle { assertNull(native<MindMapView>().nodeBounds(f.child)) }
        return f
    }

    fun waitFor(tag: String) {
        compose.waitUntil(conditionDescription = "UI tag '$tag' exists", timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    fun tap(tag: String) {
        compose.revealAction(tag)
        if (tag == "quick-study" && compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            waitFor("toolbar-more"); compose.onNodeWithTag("toolbar-more").performTouchInput { click() }
        }
        waitFor(tag)
        val target = compose.onNodeWithTag(tag)
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }
    fun tapFooter(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }
    inline fun <reified T : View> native(): T {
        val queue = java.util.ArrayDeque<View>(); queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is T && view.isShown && (view !is InkCanvasView || !view.preview && !view.embeddedPage)) return view
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        error("Visible native ${T::class.java.simpleName} missing")
    }
    fun waitMap(f: Fixture) {
        waitFor("study-map")
        compose.waitUntil(15_000) { compose.runOnIdle {
            study(f.note.id).mapId.value == f.mapId && !study(f.note.id).ui.value.loading &&
                runCatching { native<MindMapView>().width > 0 && native<MindMapView>().nodeBounds(f.node) != null }.getOrDefault(false)
        } }
    }
    private fun selectNode(node: String) {
        val point = compose.runOnIdle {
            val map = native<MindMapView>(); assertTrue(map.focusNode(node))
            val bounds = checkNotNull(map.nodeBounds(node)); Offset(bounds.centerX(), bounds.centerY())
        }
        compose.onNodeWithTag("study-map").performTouchInput {
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1); click(point)
        }
        waitFor("node-actions")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(node, native<MindMapView>().selectedNodeId) }
    }
    fun openBody(f: Fixture, second: Boolean = false) {
        waitMap(f); selectNode(if (second) f.secondNode else f.node)
        tap("node-more"); tap("node-view-content"); waitFor("card-full-body"); waitFor("study-open-source")
        compose.onNodeWithTag("card-full-body").assertTextEquals(if (second) f.secondBody else f.body)
    }
    fun openSource(f: Fixture) {
        waitMap(f); selectNode(f.node)
        tap("node-more"); tap("node-view-source"); waitFor("card-source-content")
    }
    fun source(card: String): StudySourceRow = runBlocking { checkNotNull(app.study.source(card)) }

    data class GraphState(val map: String?, val tab: Int, val viewports: Map<String, MapViewport>,
        val collapsed: Map<String, List<String>>, val selected: Map<String, String?>, val focused: Map<String, String?>)
    fun graph(f: Fixture): GraphState = compose.runOnIdle { val vm = study(f.note.id)
        GraphState(vm.mapId.value, vm.lastTab, vm.viewports.toMap(), vm.collapsedByMap.mapValues { it.value.toList() },
            vm.selectedByMap.toMap(), vm.focusedByMap.toMap()) }
    fun paperViewport(): CanvasViewport = compose.runOnIdle { native<InkCanvasView>().snapshotViewport() }
    fun assertCurrent(f: Fixture, page: String) {
        compose.waitUntil(15_000) { compose.runOnIdle { notebook().ui.value.selectedId == f.note.id &&
            pages(f.note.id).ui.value.selectedId == page && !ink(page).ui.value.loading } }
        compose.runOnIdle { assertEquals(f.note.id, notebook().ui.value.selectedId); assertEquals(page, pages(f.note.id).ui.value.selectedId) }
    }
    fun assertReadOnly(f: Fixture) {
        compose.waitUntil(15_000) { compose.runOnIdle { lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite } }
        compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<InkCanvasView>().allowInput) }
    }
    fun assertNoRevealOrError(f: Fixture, page: String) {
        assertCurrent(f, page)
        compose.onNodeWithTag("study-window-source-return").assertDoesNotExist()
        compose.onNodeWithText("来源页已回收或不可用；原迹快照仍保留。", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(pages(f.note.id).ui.value.error); assertFalse(pages(f.note.id).ui.value.busy) }
        assertReadOnly(f)
    }

    private fun rect(view: View): RectF {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return RectF(xy[0].toFloat(), xy[1].toFloat(), (xy[0] + view.width).toFloat(), (xy[1] + view.height).toFloat())
    }
    private fun panelRect(): RectF? {
        val panels = compose.onAllNodesWithTag("study-panel").fetchSemanticsNodes()
        if (panels.isEmpty()) {
            compose.onNodeWithTag("study-window-source-return").assertIsDisplayed()
            compose.onNodeWithTag("ink-surface").assertIsDisplayed()
            return null
        }
        check(panels.size == 1) { "Expected one visible study panel, found ${panels.size}" }
        compose.onNodeWithTag("study-panel").assertIsDisplayed()
        val local = panels.single().boundsInRoot
        val origin = compose.runOnIdle {
            val queue = java.util.ArrayDeque<View>(); queue.add(compose.activity.window.decorView)
            var owner: View? = null
            while (queue.isNotEmpty() && owner == null) {
                val view = queue.removeFirst()
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" && view.isShown) owner = view
                else if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
            }
            IntArray(2).also { checkNotNull(owner).getLocationOnScreen(it) }
        }
        return RectF(origin[0] + local.left, origin[1] + local.top, origin[0] + local.right, origin[1] + local.bottom)
    }
    fun assertFocusedSource(f: Fixture, source: StudySourceRow) {
        waitFor("ink-surface"); waitFor("study-window-source-return")
        var last = "not sampled"
        compose.waitUntil(15_000) { runCatching {
            val panel = panelRect()
            compose.runOnIdle {
                val canvas = native<InkCanvasView>(); val viewport = canvas.snapshotViewport()
                val density = canvas.resources.displayMetrics.density.toDouble()
                val bounds = CanvasBounds(source.left, source.top, source.right, source.bottom)
                val expected = CanvasViewport.fit(bounds.padded(60.0), canvas.width / density, canvas.height / density)
                    .constrainedToPaper(canvas.width / density, canvas.height / density)
                val paper = rect(canvas)
                val a = viewport.worldToScreen(bounds.left, bounds.top, canvas.width.toDouble(), canvas.height.toDouble(), density)
                val b = viewport.worldToScreen(bounds.right, bounds.bottom, canvas.width.toDouble(), canvas.height.toDouble(), density)
                val projected = RectF(paper.left + a.x.toFloat(), paper.top + a.y.toFloat(), paper.left + b.x.toFloat(), paper.top + b.y.toFloat())
                last = "selected=${pages(f.note.id).ui.value.selectedId} viewport=$viewport expected=$expected paper=$paper source=$projected panel=$panel"
                pages(f.note.id).ui.value.selectedId == source.pageId && !ink(source.pageId).ui.value.loading &&
                    canvas.documentContentReady && !canvas.rasterPending && canvas.displayedStrokeCount == 1 &&
                    abs(viewport.centerX - expected.centerX) < .5 && abs(viewport.centerY - expected.centerY) < .5 &&
                    abs(viewport.zoom - expected.zoom) < .01 && paper.contains(projected) &&
                    (panel == null || !RectF.intersects(projected, panel))
            }
        }.getOrDefault(false) }
        assertCurrent(f, source.pageId)
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.onNodeWithTag("continuous-pages").assertDoesNotExist()
        assertEquals(last, source.pageId, runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId })
        assertReadOnly(f)
    }

    private fun selectionMutex(book: String): Mutex = BookPagesViewModel::class.java.getDeclaredField("selectionLock")
        .apply { isAccessible = true }.get(pages(book)) as Mutex
    fun awaitSelectionHeld(f: Fixture) {
        compose.waitUntil(15_000) { compose.runOnIdle { selectionMutex(f.note.id).isLocked } }
    }
    fun awaitSelectionReleased(f: Fixture) {
        compose.waitUntil(15_000) { compose.runOnIdle { !selectionMutex(f.note.id).isLocked } }
    }
    fun startAwait(f: Fixture, target: String): Deferred<Boolean> = compose.runOnIdle {
        // The production API has an explicit Main caller contract.
        requestScope.async(start = CoroutineStart.UNDISPATCHED) { pages(f.note.id).selectAwait(target) }
            .also { requests.add(it) }
    }

    inner class WriterGate(private val label: String) {
        private val entered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<suspend () -> Unit>()
        private val completed = CompletableDeferred<Unit>()
        private val db = database
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val job = scope.launch {
            heldGates.incrementAndGet()
            try {
                db.withTransaction {
                    android.util.Log.i("InkWeft-SA60", "$label: same app Room transaction held")
                    entered.complete(Unit)
                    release.await().invoke()
                }
                completed.complete(Unit)
            } catch (error: Throwable) {
                if (!entered.isCompleted) entered.completeExceptionally(error)
                completed.completeExceptionally(error)
            } finally { heldGates.decrementAndGet() }
        }
        fun awaitHeld() {
            compose.waitUntil(15_000) { entered.isCompleted }
            runBlocking { entered.await() }
        }
        fun finish(beforeCommit: suspend () -> Unit = {}) {
            release.complete(beforeCommit)
            try { runBlocking { withTimeout(15_000) { completed.await(); job.join() } } }
            finally { scope.cancel() }
            android.util.Log.i("InkWeft-SA60", "$label: held transaction completed and joined")
        }
    }
    fun beginSourceGate(f: Fixture, label: String): WriterGate {
        val gate = WriterGate(label)
        try {
            gate.awaitHeld(); tapFooter("study-open-source")
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("study-open-source").assertIsNotEnabled() }.isSuccess }
            awaitSelectionHeld(f)
            compose.onNodeWithTag("study-card-details").assertIsDisplayed()
            compose.onNodeWithTag("card-back").assertIsDisplayed().assertIsEnabled()
            assertCurrent(f, f.note.id); assertReadOnly(f)
            compose.onNodeWithTag("study-window-source-return").assertDoesNotExist()
            return gate
        } catch (failure: Throwable) { gate.finish(); throw failure }
    }
    fun settle(f: Fixture) {
        check(heldGates.get() == 0) { "Release/join the gate before fencing the app's transaction queue" }
        awaitSelectionReleased(f)
        runBlocking { withTimeout(15_000) { database.withTransaction { database.workspace().get(f.note.id) } } }
        compose.waitForIdle()
    }
    fun awaitResult(request: Deferred<Boolean>): Boolean = runBlocking { withTimeout(15_000) { request.await() } }
    fun assertCanceled(request: Deferred<Boolean>) {
        runBlocking { withTimeout(15_000) { request.join() } }
        assertTrue("Canceled/superseded await must not return a stale success or failure", request.isCancelled)
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
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
    /** Call normally only outside a gate. A fixture's beforeCommit callback may
     * capture its expected author baseline INSIDE the same already-owned Room
     * transaction, after real recycling and before the queued navigation runs.
     */
    suspend fun stampInOwnedTransaction(book: String): List<String> = database.withTransaction { buildList {
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
    } }
    fun authorStamp(book: String): List<String> {
        check(heldGates.get() == 0) { "Do not queue an author assertion behind a held gate" }
        return runBlocking { withTimeout(15_000) { stampInOwnedTransaction(book) } }
    }
    fun assertAuthors(f: Fixture, expected: List<String>, unrelatedExpected: List<String>, savedSource: StudySourceRow? = null) {
        assertEquals(expected, authorStamp(f.note.id)); assertEquals(unrelatedExpected, authorStamp(f.unrelated.id))
        savedSource?.let { assertEquals(sha(it.snapshot), sha(source(it.cardId).snapshot)) }
    }
    suspend fun recycleSourcePage(f: Fixture): EditPageResult = app.pages.edit(EditPage(id(), f.note.id, f.sourcePage,
        PageEditKind.TRASH, InsertPages.orderHash(app.pages.activePages(f.note.id).map { it.id }), 1,
        stayOnPageId = f.note.id))
    suspend fun recycleBook(f: Fixture) {
        val row = app.workspaceRepository.get(f.note.id)
        assertTrue(app.workspaceRepository.organize(f.note.id, row.revision, row.folder, row.tags, row.favorite, true))
    }
}
