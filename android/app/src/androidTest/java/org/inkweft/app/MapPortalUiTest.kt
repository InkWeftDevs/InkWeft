package org.inkweft.app

import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import java.security.MessageDigest
import java.util.UUID

/** Uses the actual notebook, native map, node menu and author repository; no isolated Dialog host. */
class MapPortalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private fun id() = UUID.randomUUID().toString()
    private var mapEvidence = ""
    private var lastMapObservation = ""
    private var mapFailureCaptured = false
    private var actionSerial = 0
    private var lastAction = "No map action yet"
    private fun recordMapEvidence(value: String) {
        if (value != lastMapObservation) {
            lastMapObservation = value
            mapEvidence = (mapEvidence + "\n" + value).takeLast(48_000)
        }
    }
    private fun captureMapFailure(error: Throwable) {
        if (mapFailureCaptured) return
        mapFailureCaptured = true
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            runCatching { java.io.File(directory, "bp55-map-state-failure.txt").writeText(mapEvidence) }.onFailure(error::addSuppressed)
            runCatching {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { java.io.File(directory, "bp55-map-state-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }.onFailure(error::addSuppressed)
        }.onFailure(error::addSuppressed)
        error.addSuppressed(AssertionError(mapEvidence))
    }

    @After fun closeProbe() {
        probeDatabase?.close()
        probeDatabase = null
    }

    private data class Fixture(
        val note: Note,
        val page: String,
        val sourceMap: String,
        val sourceNode: String,
        val child: String,
        val repeatedNode: String,
        val sharedCard: String,
        val targetMap: String,
        val targetNode: String,
        val targetTitle: String,
        val otherMap: String,
        val otherNode: String,
        val otherTitle: String,
        val mainNode: String,
        val foreignMap: String,
    )

    private data class OriginView(
        val book: String,
        val page: String,
        val map: String?,
        val selected: String?,
        val viewport: MapViewport,
        val collapsed: List<String>,
        val focus: String?,
    )

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String, physical: Boolean = false) {
        compose.revealAction(tag)
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed()
        lastAction = "action=${++actionSerial} tag=$tag physical=$physical enabled=true bounds=${node.fetchSemanticsNode().boundsInRoot}"
        recordMapEvidence(lastAction)
        if (physical) node.performTouchInput { click() } else node.performClick()
        compose.waitForIdle()
    }

    private fun nativeMaps(): List<MindMapView> {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        WindowInspector.getGlobalWindowViews().filter { it !== compose.activity.window.decorView }.forEach(queue::add)
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        val result = mutableListOf<MindMapView>()
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (!visited.add(view)) continue
            if (view is MindMapView) result.add(view)
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        return result
    }

    // Preserve the existing first-visible selection until evidence establishes an ownership bug.
    private fun map(): MindMapView = nativeMaps().firstOrNull { it.isShown }
        ?: error("Visible native map is missing")

    /** Read-only, on the UI thread. Record every candidate rather than assuming the first is ours. */
    private fun mapState(f: Fixture, expectedMap: String?, expectedNode: String, phase: String): Boolean {
        val vm = study(f.note.id); val state = vm.ui.value
        val views = nativeMaps(); val first = views.firstOrNull { it.isShown }
        val mapMatches = vm.mapId.value == expectedMap
        val loaded = !state.loading
        val firstHasNode = runCatching { first?.nodeBounds(expectedNode) != null }.getOrDefault(false)
        val candidates = views.map { view ->
            val screen = IntArray(2); view.getLocationOnScreen(screen)
            val local = android.graphics.Rect(); val visible = view.getLocalVisibleRect(local)
            val root = view.rootView
            val rendered = runCatching {
                @Suppress("UNCHECKED_CAST")
                (MindMapView::class.java.getDeclaredField("nodes").apply { isAccessible = true }.get(view) as List<StudyNodeRow>)
            }.getOrNull()
            "view=${System.identityHashCode(view)} first=${view === first} root=${System.identityHashCode(root)} " +
                "shown=${view.isShown} attached=${view.isAttachedToWindow} focus=${root.hasWindowFocus()} laidOut=${view.isLaidOut} layoutRequested=${view.isLayoutRequested} " +
                "size=${view.width}x${view.height} screen=${screen.toList()} localVisible=$visible/$local " +
                "book=${view.captureBook} map=${view.captureMapKey} graph=${view.captureGraph} viewport=${view.snapshotViewport()} " +
                "renderedCount=${rendered?.size} renderedIds=${rendered?.take(12)?.map { it.id }} targetInRendered=${rendered?.any { it.id == expectedNode }} " +
                "movementCount=${view.movementNodes.size} targetBounds=${runCatching { view.nodeBounds(expectedNode) }.getOrNull()} " +
                "ownerMatches=${view.captureBook == f.note.id && view.captureMapKey == (expectedMap ?: "main")}"
        }
        recordMapEvidence("phase=$phase $lastAction activity=${System.identityHashCode(compose.activity)} vm=${System.identityHashCode(vm)} " +
            "expectedBook=${f.note.id} expectedMap=$expectedMap expectedNode=$expectedNode mapMatches=$mapMatches loaded=$loaded firstHasNode=$firstHasNode " +
            "book=${vm.book} map=${vm.mapId.value} tab=${vm.lastTab} loading=${state.loading} readFailed=${state.readFailed} busy=${state.busy} unknown=${state.unknown} " +
            "graphRef=${state.graph?.ref} graphHash=${state.graph?.graphFingerprint} graphNodes=${state.graph?.nodes?.size} uiNodes=${state.nodes.size} uiTarget=${state.nodes.any { it.id == expectedNode }} " +
            "selected=${vm.selectedByMap[expectedMap ?: "main"]} focus=${vm.focusedByMap[expectedMap ?: "main"]} collapsed=${vm.collapsedByMap[expectedMap ?: "main"]} " +
            "nativeViews=${views.size}\n" + candidates.joinToString("\n"))
        // The original three-part condition and 15-second budget remain unchanged.
        return mapMatches && loaded && firstHasNode
    }

    private fun study(book: String) =
        ViewModelProvider(compose.activity)["study-" + book, StudyViewModel::class.java]

    private fun pages(book: String) =
        ViewModelProvider(compose.activity)["book-" + book, BookPagesViewModel::class.java]

    private fun waitMap(f: Fixture, mapId: String?, node: String, inLibrary: Boolean = false) {
        try {
            compose.runOnIdle { mapState(f, mapId, node, "before-map-tag") }
            waitFor("study-map")
            compose.waitUntil(15_000) {
                compose.runOnIdle { mapState(f, mapId, node, "wait-map") }
            }
            if (inLibrary) {
                compose.onAllNodesWithTag("study-panel").assertCountEquals(0)
                compose.onAllNodes(isDialog()).assertCountEquals(1)
            } else compose.onAllNodesWithTag("study-panel").assertCountEquals(1)
            compose.onAllNodesWithTag("study-map").assertCountEquals(1)
            compose.runOnIdle {
                assertEquals(if (inLibrary) null else f.note.id,
                    ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
                assertEquals("Map navigation must retain the exact document page", f.page, pages(f.note.id).ui.value.selectedId)
            }
            if (inLibrary) compose.onNodeWithTag("ink-surface").assertDoesNotExist()
        } catch (error: Throwable) { captureMapFailure(error); throw error }
    }

    private fun chooseMap(f: Fixture, mapId: String?, node: String) {
        try {
            compose.runOnIdle { mapState(f, mapId, node, "before-picker") }
            tap("study-map-picker")
            compose.runOnIdle { mapState(f, mapId, node, "before-map-choice") }
            tap("study-map-" + (mapId ?: "main"))
            compose.runOnIdle { mapState(f, mapId, node, "after-map-choice") }
            waitMap(f, mapId, node)
        } catch (error: Throwable) { captureMapFailure(error); throw error }
    }

    private fun select(node: String) {
        compose.waitUntil(15_000) {
            var present = false
            compose.runOnIdle { present = runCatching { map().nodeBounds(node) != null }.getOrDefault(false) }
            present
        }
        var point = Offset.Zero
        compose.runOnIdle {
            map().focusNode(node)
            val bounds = checkNotNull(map().nodeBounds(node))
            point = Offset(bounds.centerX(), bounds.centerY())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput { click(point) }
        waitFor("node-actions")
        compose.runOnIdle { assertEquals(node, map().selectedNodeId) }
    }

    private fun fixture(longTitles: Boolean = false): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("跨图入口验收 " + id().take(6), false, PaperStyle.RULED) }
        val sourceMap = id()
        val sourceNode = id()
        val child = id()
        val repeatedNode = id()
        val card = id()
        val targetMap = id()
        val targetNode = id()
        val otherMap = id()
        val otherNode = id()
        val mainNode = id()
        val foreignMap = id()
        val targetTitle = if (longTitles) "目标概率图：" + "逐步核对条件概率与独立性的推导过程，".repeat(5) else "条件概率目标图"
        val otherTitle = "同卡另一个展示图"
        val secondPage = runBlocking {
            app.pages.ensureFirst(note.id)
            app.pages.addAfter(note.id, note.id, id()).also { app.pages.select(note.id, it.id) }
        }
        runBlocking {
            app.pageObjects.save(secondPage.id, 0, id(), listOf(PageObject(id(), PageObjectKind.TEXT,
                90f, 90f, 820f, 150f, text = "保留第二页，不随跨图入口跳页。", fontSize = 28f)))
            val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(100f, 320f, 0), InkSample(430f, 320f, 100)))
            app.inkRepository.save(CommitInk(id(), secondPage.id, 0, InkMutation.Add(stroke)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, sourceMap, 0, KnowledgeData.MapDefinition("原图 · 保留视角")))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, targetMap, 0,
                KnowledgeData.MapDefinition(targetTitle, structures = listOf(MapStructure(targetNode, null, "目标结构主题", 40.0, 80.0)))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, otherMap, 0, KnowledgeData.MapDefinition(otherTitle)))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = card, nodeId = sourceNode,
                title = "原主题：条件概率", body = "同一张摘要卡在多处展示，入口只属于所选的这个节点。",
                source = StudySourceDraft(secondPage.id, 1, stroke.bounds(), listOf(stroke.id)),
                x = 40.0, y = 80.0, mapId = sourceMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = child,
                parentId = sourceNode, title = "收起后仍属于原分支", body = "折叠状态必须保留。",
                x = 320.0, y = 190.0, mapId = sourceMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = card, nodeId = repeatedNode,
                x = 370.0, y = 460.0, mapId = sourceMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = card, nodeId = otherNode,
                x = 40.0, y = 80.0, mapId = otherMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = mainNode,
                title = "固定主图主题", body = "目标失效时不能悄悄退回这张主图。"))
            val foreign = app.workspaceRepository.create("其他笔记 · 不可作为目标 " + id().take(6), false, PaperStyle.DOTS)
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, foreignMap, 0, KnowledgeData.MapDefinition("另一份笔记的图")))
        }
        val f = Fixture(note, secondPage.id, sourceMap, sourceNode, child, repeatedNode, card,
            targetMap, targetNode, targetTitle, otherMap, otherNode, otherTitle, mainNode, foreignMap)
        compose.runOnIdle { ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note) }
        compose.singlePageEditor()
        compose.waitForSavedInk()
        compose.frameCanvasFixture()
        tap("quick-study")
        chooseMap(f, sourceMap, sourceNode)
        select(sourceNode)
        return f
    }

    /** Fold and focus through actual node actions, then change zoom using native two-pointer input. */
    private fun branchView(f: Fixture): OriginView {
        tap("node-more")
        tap("node-menu-fold")
        tap("node-more")
        val focus = compose.onNode(hasText("聚焦此分支") and hasAnyAncestor(hasTestTag("node-menu")))
        runCatching { focus.performScrollTo() }
        focus.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput {
            val y = height * .32f
            val x = width * .5f
            down(0, Offset(x - width * .15f, y))
            down(1, Offset(x + width * .15f, y))
            for (i in 1..6) {
                val distance = width * (.15f + i * .007f)
                moveTo(0, Offset(x - distance, y), 16)
                moveTo(1, Offset(x + distance, y), 16)
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(f.sourceNode in study(f.note.id).collapsedByMap[f.sourceMap].orEmpty())
            assertEquals(f.sourceNode, study(f.note.id).focusedByMap[f.sourceMap])
            assertNull("Collapsed descendant must be absent from the native map", map().nodeBounds(f.child))
            assertNull("Focus must exclude the second occurrence", map().nodeBounds(f.repeatedNode))
        }
        return snapshot(f)
    }

    private fun snapshot(f: Fixture): OriginView {
        lateinit var result: OriginView
        compose.runOnIdle {
            val vm = study(f.note.id)
            val key = vm.mapId.value ?: "main"
            result = OriginView(f.note.id, checkNotNull(pages(f.note.id).ui.value.selectedId), vm.mapId.value,
                map().selectedNodeId, map().snapshotViewport(), vm.collapsedByMap[key].orEmpty().toList(), vm.focusedByMap[key])
            assertEquals(result.selected, vm.selectedByMap[key])
        }
        return result
    }

    private fun assertOrigin(f: Fixture, original: OriginView, inLibrary: Boolean = false) {
        waitFor("study-map")
        compose.waitUntil(15_000) {
            runCatching { snapshot(f) == original }.getOrDefault(false)
        }
        assertEquals("Return must restore page, map, selected node, viewport, collapsed nodes and focus", original, snapshot(f))
        compose.runOnIdle {
            assertEquals(if (inLibrary) null else original.book,
                ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
        }
        if (inLibrary) {
            compose.onAllNodesWithTag("study-panel").assertCountEquals(0)
            compose.onAllNodes(isDialog()).assertCountEquals(1)
        } else compose.onAllNodesWithTag("study-panel").assertCountEquals(1)
        compose.onAllNodesWithTag("study-map").assertCountEquals(1)
        if (inLibrary) compose.onNodeWithTag("ink-surface").assertDoesNotExist()
    }

    private fun openManager(physical: Boolean = false) {
        tap("node-more", physical)
        tap("node-map-portals", physical)
        waitFor("map-portal-manager")
    }

    private fun closeManager(physical: Boolean = false) {
        tap("map-portal-close-manager", physical)
        compose.waitUntil(15_000) {
            listOf("map-portal-manager", "map-portal-preview", "map-portal-destination-picker")
                .all { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isEmpty() }
        }
        compose.onNodeWithTag("map-portal-manager").assertDoesNotExist()
        compose.onNodeWithTag("map-portal-preview").assertDoesNotExist()
    }

    private fun closePreviewAndManager(physical: Boolean = false) {
        tap("map-portal-close-preview", physical)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("map-portal-preview").fetchSemanticsNodes().isEmpty() }
        waitFor("map-portal-manager")
        closeManager(physical)
    }

    private fun preview(rowId: String, title: String, physical: Boolean = false) {
        tap("map-portal-item-" + rowId, physical)
        waitFor("map-portal-preview")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("map-portal-preview-target").assertTextContains(title) }.isSuccess &&
                runCatching { compose.onNodeWithTag("map-portal-open").assertIsEnabled() }.isSuccess
        }
        compose.onAllNodesWithTag("map-portal-preview").assertCountEquals(1)
        compose.onNodeWithTag("map-portal-preview-target").assertTextContains(title)
    }

    private fun seedPortal(f: Fixture, sourceMap: String? = f.sourceMap,
        sourceNode: String = f.sourceNode, targetMap: String? = f.targetMap, targetBranchId: String? = null): String {
        val rowId = id()
        runBlocking { app.knowledge.submit(KnowledgeCommand(id(), f.note.id, rowId, 0,
            KnowledgeData.MapPortal(sourceMap, sourceNode, targetMap, targetBranchId))) }
        return rowId
    }

    private fun portals(book: String): List<KnowledgeRow> = runBlocking {
        probe.knowledge().forBook(book).filter { it.data() is KnowledgeData.MapPortal }
    }

    private val portalItems get() = SemanticsMatcher("persistent map portal items") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("map-portal-item-") == true
    }

    private fun count(sql: String, argument: String): Long = runBlocking {
        probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(argument))).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private val contentQueries = listOf(
        "SELECT * FROM study_cards WHERE notebookId=? ORDER BY id",
        "SELECT r.* FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=? ORDER BY r.cardId,r.revision",
        "SELECT * FROM study_nodes WHERE notebookId=? ORDER BY id",
        "SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE c.notebookId=? ORDER BY s.cardId",
        "SELECT * FROM study_receipts WHERE notebookId=? ORDER BY id",
        "SELECT id,notebookId,position,world,paper,createdAfterId,trashedAt FROM notebook_pages WHERE notebookId=? ORDER BY id",
        "SELECT i.* FROM ink_pages i JOIN notebook_pages p ON p.id=i.noteId WHERE p.notebookId=? ORDER BY i.noteId",
        "SELECT s.* FROM ink_strokes s JOIN notebook_pages p ON p.id=s.noteId WHERE p.notebookId=? ORDER BY s.id",
        "SELECT r.* FROM ink_receipts r JOIN notebook_pages p ON p.id=r.noteId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT o.* FROM page_objects o JOIN notebook_pages p ON p.id=o.pageId WHERE p.notebookId=? ORDER BY o.pageId",
        "SELECT r.* FROM object_receipts r JOIN notebook_pages p ON p.id=r.pageId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT s.* FROM page_search_text s JOIN notebook_pages p ON p.id=s.pageId WHERE p.notebookId=? ORDER BY s.pageId",
    )

    /** Includes payloads, tombstones, exact history and receipt identities; excludes only view preferences. */
    private fun authorStamp(book: String): List<String> = rowsStamp(book, contentQueries + listOf(
        "SELECT * FROM notes WHERE id=?",
        "SELECT * FROM note_revisions WHERE noteId=? ORDER BY revision",
        "SELECT * FROM command_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
        "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId",
    ))

    /** Author relation saves can touch the notebook timestamp; page, ink, card and tree data cannot change. */
    private fun contentStamp(book: String) = rowsStamp(book, contentQueries)

    private fun rowsStamp(book: String, queries: List<String>): List<String> = runBlocking {
        probe.withTransaction {
            buildList {
                queries.forEach { sql ->
                    add(sql)
                    probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
                        while (cursor.moveToNext()) {
                            add((0 until cursor.columnCount).joinToString("|") { column ->
                                when (cursor.getType(column)) {
                                    Cursor.FIELD_TYPE_NULL -> "null"
                                    Cursor.FIELD_TYPE_BLOB -> "blob:" + sha(cursor.getBlob(column))
                                    Cursor.FIELD_TYPE_INTEGER -> "int:" + cursor.getLong(column)
                                    Cursor.FIELD_TYPE_FLOAT -> "float:" + cursor.getDouble(column)
                                    else -> "text:" + cursor.getString(column).length + ":" + cursor.getString(column)
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            java.io.File(app.getExternalFilesDir(null), "bp55-"+name+".png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun specifiedBranchCancelCreateReadNavigateRotateAndReturnKeepAuthorContent() {
        val f = fixture()
        val descendant = id()
        val outside = id()
        runBlocking {
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.CREATE, cardId=id(), nodeId=descendant,
                parentId=f.targetNode, title="目标分支内子主题", body="这里只属于目标分支。", x=310.0, y=180.0, mapId=f.targetMap))
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.CREATE, cardId=id(), nodeId=outside,
                title="分支之外的独立根主题", body="打开分支不应把它纳入。", x=40.0, y=470.0, mapId=f.targetMap))
        }
        val original = branchView(f)
        val before = authorStamp(f.note.id)
        val originalContent = contentStamp(f.note.id)
        openManager()
        tap("map-portal-create")
        tap("map-portal-destination-" + f.targetMap)
        tap("map-portal-target-branch")
        compose.onNodeWithTag("map-portal-save").assertIsNotEnabled()
        tap("map-portal-branch-" + f.targetNode)
        compose.activityRule.scenario.recreate()
        waitFor("map-portal-destination-picker")
        compose.onNodeWithTag("map-portal-branch-" + f.targetNode).assertTextContains("✓ 目标结构主题")
        compose.onNodeWithTag("map-portal-save").assertIsEnabled()
        shot("branch-picker")
        tap("map-portal-cancel-create")
        waitFor("map-portal-manager")
        assertEquals("Choosing a branch and cancelling must not write author data", before, authorStamp(f.note.id))
        tap("map-portal-create")
        tap("map-portal-destination-" + f.targetMap)
        tap("map-portal-target-branch")
        tap("map-portal-branch-" + f.targetNode)
        tap("map-portal-save")
        compose.waitUntil(15_000) { portals(f.note.id).count { !it.removed } == 1 }
        val entry = portals(f.note.id).single()
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.targetMap, f.targetNode), entry.data())
        assertEquals(originalContent, contentStamp(f.note.id))
        val afterSave = authorStamp(f.note.id)
        closeManager()
        tap("quick-readonly")
        openManager()
        compose.onNodeWithTag("map-portal-create").assertIsNotEnabled()
        preview(entry.id, f.targetTitle)
        compose.onNodeWithTag("map-portal-preview-branch").assertTextEquals("目标结构主题")
        compose.onNodeWithTag("map-portal-open").assertTextEquals("打开分支")
        shot("branch-preview")
        tap("map-portal-open")
        waitMap(f, f.targetMap, f.targetNode)
        compose.onNodeWithTag("quick-readonly").assertIsSelected()
        compose.runOnIdle {
            assertEquals(f.targetNode, study(f.note.id).focusedByMap[f.targetMap])
            assertEquals(f.targetNode, map().selectedNodeId)
            assertNotNull(map().nodeBounds(descendant))
            assertNull(map().nodeBounds(outside))
        }
        shot("focused-branch")
        compose.activityRule.scenario.recreate()
        waitMap(f, f.targetMap, f.targetNode)
        compose.runOnIdle { assertEquals(f.targetNode, study(f.note.id).focusedByMap[f.targetMap]) }
        tap("study-focus-all")
        compose.runOnIdle { assertNotNull(map().nodeBounds(outside)) }
        tap("map-portal-back")
        assertOrigin(f, original)
        assertEquals("Branch opening, rotation, all-topics and returning only change view state", afterSave, authorStamp(f.note.id))
    }

    @Test fun missingBranchNeverOpensSameNamedReplacementAndRelationCanBeRemoved() {
        val f = fixture()
        val original = snapshot(f)
        val entry = seedPortal(f, targetBranchId=f.targetNode)
        openManager()
        preview(entry, f.targetTitle)
        val replacement = id()
        runBlocking {
            val row = probe.knowledge().get(f.targetMap)!!
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, f.targetMap, row.revision,
                KnowledgeData.MapDefinition(f.targetTitle, structures=listOf(MapStructure(replacement, null, "目标结构主题", 40.0, 80.0)))))
        }
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("map-portal-open").assertIsNotEnabled() }.isSuccess &&
                compose.onAllNodesWithTag("map-portal-preview-unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map-portal-preview-branch").assertTextEquals("目标分支已移除或不可用")
        shot("unavailable-branch")
        val before = authorStamp(f.note.id)
        val content = contentStamp(f.note.id)
        compose.onNodeWithTag("map-portal-open").performTouchInput { click() }
        compose.runOnIdle { assertEquals(f.sourceMap, study(f.note.id).mapId.value) }
        assertEquals(before, authorStamp(f.note.id))
        tap("map-portal-remove")
        tap("map-portal-confirm-remove")
        compose.waitUntil(15_000) { portals(f.note.id).single().removed }
        val removed = portals(f.note.id).single()
        assertEquals(f.targetNode, (removed.data() as KnowledgeData.MapPortal).targetBranchId)
        assertEquals(2L, removed.revision)
        assertEquals(content, contentStamp(f.note.id))
        closeManager()
        assertOrigin(f, original)
        assertNotEquals(replacement, (removed.data() as KnowledgeData.MapPortal).targetBranchId)
    }

    @Test fun branchRemovedAfterOpeningStaysUnavailableUntilExplicitAllTopics() {
        val f=fixture()
        val original=snapshot(f)
        val entry=seedPortal(f,targetBranchId=f.targetNode)
        openManager();preview(entry,f.targetTitle);tap("map-portal-open")
        waitMap(f,f.targetMap,f.targetNode)
        val replacement=id()
        runBlocking {
            val row=probe.knowledge().get(f.targetMap)!!
            app.knowledge.submit(KnowledgeCommand(id(),f.note.id,f.targetMap,row.revision,
                KnowledgeData.MapDefinition(f.targetTitle,structures=listOf(MapStructure(replacement,null,"目标结构主题",40.0,80.0)))))
        }
        waitFor("map-portal-branch-unavailable")
        val before=authorStamp(f.note.id)
        compose.runOnIdle {
            assertEquals(f.targetMap,study(f.note.id).mapId.value)
            assertEquals(f.targetNode,study(f.note.id).focusedByMap[f.targetMap])
            assertNull(map().nodeBounds(replacement))
            assertFalse(map().authorEditing)
        }
        shot("opened-branch-removed")
        compose.activityRule.scenario.recreate()
        waitFor("map-portal-branch-unavailable")
        compose.runOnIdle { assertNull(map().nodeBounds(replacement)) }
        tap("study-focus-all")
        compose.onNodeWithTag("map-portal-branch-unavailable").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(map().nodeBounds(replacement)) }
        assertEquals(before,authorStamp(f.note.id))
        tap("map-portal-back");assertOrigin(f,original)
        assertEquals(before,authorStamp(f.note.id))
    }

    @Test fun nestedReturnKeepsAnUnavailableBranchInsteadOfExpandingItsMap() {
        val f=fixture()
        val original=snapshot(f)
        val first=seedPortal(f,targetBranchId=f.targetNode)
        val second=seedPortal(f,sourceMap=f.targetMap,sourceNode=f.targetNode,
            targetMap=f.otherMap,targetBranchId=f.otherNode)
        openManager();preview(first,f.targetTitle);tap("map-portal-open")
        waitMap(f,f.targetMap,f.targetNode);select(f.targetNode)
        openManager();preview(second,f.otherTitle);tap("map-portal-open")
        waitMap(f,f.otherMap,f.otherNode)
        val replacement=id()
        runBlocking {
            val row=probe.knowledge().get(f.targetMap)!!
            app.knowledge.submit(KnowledgeCommand(id(),f.note.id,f.targetMap,row.revision,
                KnowledgeData.MapDefinition(f.targetTitle,structures=listOf(MapStructure(replacement,null,"同名替代主题",40.0,80.0)))))
        }
        val before=authorStamp(f.note.id)
        val departedMap=compose.runOnIdle{map()}
        tap("map-portal-back");waitFor("map-portal-branch-unavailable")
        compose.runOnIdle {
            assertFalse("Returning must release the departed map view",departedMap.isAttachedToWindow)
            assertNotSame("Each map keeps its own native view ownership",departedMap,map())
            assertTrue("The returned map must be attached",map().isAttachedToWindow)
            assertEquals(f.targetMap,study(f.note.id).mapId.value)
            assertEquals(f.targetNode,study(f.note.id).focusedByMap[f.targetMap])
            assertNull(map().nodeBounds(replacement))
        }
        tap("map-portal-back");assertOrigin(f,original)
        assertEquals(before,authorStamp(f.note.id))
    }

    @Test fun createCancelSaveAndReturnRestoreExactDocumentAndBranchView() {
        val f = fixture()
        val original = branchView(f)
        val before = authorStamp(f.note.id)
        val originalContent = contentStamp(f.note.id)
        val receipts = count("SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", f.note.id)
        val revisions = count("SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?", f.note.id)
        openManager()
        compose.onAllNodes(portalItems).assertCountEquals(0)
        tap("map-portal-create")
        waitFor("map-portal-destination-picker")
        compose.onNodeWithTag("map-portal-destination-" + f.sourceMap).assertDoesNotExist()
        compose.onNodeWithTag("map-portal-destination-" + f.foreignMap).assertDoesNotExist()
        tap("map-portal-destination-" + f.targetMap)
        tap("map-portal-cancel-create")
        waitFor("map-portal-manager")
        compose.onNodeWithTag("map-portal-destination-picker").assertDoesNotExist()
        compose.onAllNodes(portalItems).assertCountEquals(0)
        assertEquals("Choosing then cancelling must not write a draft, revision or receipt", before, authorStamp(f.note.id))
        closeManager()
        assertOrigin(f, original)

        openManager()
        tap("map-portal-create")
        tap("map-portal-destination-" + f.targetMap)
        tap("map-portal-save")
        compose.waitUntil(15_000) { portals(f.note.id).count { !it.removed } == 1 }
        val row = portals(f.note.id).single()
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.targetMap), row.data())
        assertEquals(1L, row.revision)
        assertEquals(receipts + 1, count("SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", f.note.id))
        assertEquals(revisions + 1, count("SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?", f.note.id))
        assertEquals(1L, count("SELECT COUNT(*) FROM knowledge_receipts WHERE resultId=?", row.id))
        assertEquals(originalContent, contentStamp(f.note.id))
        val afterSave = authorStamp(f.note.id)
        preview(row.id, f.targetTitle)
        tap("map-portal-open")
        waitMap(f, f.targetMap, f.targetNode)
        compose.onNodeWithTag("map-portal-manager").assertDoesNotExist()
        compose.onNodeWithTag("map-portal-preview").assertDoesNotExist()
        waitFor("map-portal-back")
        assertEquals("Opening must not create cards, commands or receipts", afterSave, authorStamp(f.note.id))
        tap("map-portal-back")
        assertOrigin(f, original)
        assertEquals("Returning must not rewrite author state", afterSave, authorStamp(f.note.id))

        openManager()
        preview(row.id, f.targetTitle)
        tap("map-portal-remove")
        tap("map-portal-confirm-remove")
        compose.waitUntil(15_000) { portals(f.note.id).single().removed }
        waitFor("map-portal-manager")
        compose.onNodeWithTag("map-portal-preview").assertDoesNotExist()
        compose.onAllNodes(portalItems).assertCountEquals(0)
        val removed = portals(f.note.id).single()
        assertEquals(row.data(), removed.data())
        assertEquals(2L, removed.revision)
        assertEquals(receipts + 2, count("SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", f.note.id))
        assertEquals(revisions + 2, count("SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?", f.note.id))
        assertEquals(2L, count("SELECT COUNT(*) FROM knowledge_receipts WHERE resultId=?", row.id))
        assertEquals("Removing the relation must retain source, target, cards, ink and page objects", originalContent, contentStamp(f.note.id))
        runBlocking {
            listOf(f.sourceMap, f.targetMap).forEach {
                val definition = checkNotNull(probe.knowledge().get(it))
                assertFalse(definition.removed)
                assertEquals(1L, definition.revision)
            }
        }
        closeManager()
        assertOrigin(f, original)
    }

    @Test fun occurrenceScopeAndRenamedOrRecycledTargetNeverRedirectToMainMap() {
        val f = fixture()
        val portal = seedPortal(f)
        openManager()
        compose.onAllNodes(portalItems).assertCountEquals(1)
        compose.onNodeWithTag("map-portal-item-" + portal).assertExists()
        closeManager()
        select(f.repeatedNode)
        openManager()
        compose.onAllNodes(portalItems).assertCountEquals(0)
        closeManager()
        chooseMap(f, f.otherMap, f.otherNode)
        select(f.otherNode)
        openManager()
        compose.onAllNodes(portalItems).assertCountEquals(0)
        closeManager()
        chooseMap(f, f.sourceMap, f.sourceNode)
        select(f.sourceNode)
        runBlocking {
            val occurrences = probe.knowledge().forBook(f.note.id).mapNotNull { it.data() as? KnowledgeData.MapOccurrence }
            assertEquals(3, occurrences.count { it.cardId == f.sharedCard })
            val row = checkNotNull(probe.knowledge().get(f.targetMap))
            val definition = row.data() as KnowledgeData.MapDefinition
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, row.id, row.revision, definition.copy(title = "已改名但身份不变")))
        }
        val afterRename = authorStamp(f.note.id)
        openManager()
        preview(portal, "已改名但身份不变")
        tap("map-portal-open")
        waitMap(f, f.targetMap, f.targetNode)
        tap("map-portal-back")
        waitMap(f, f.sourceMap, f.sourceNode)
        assertEquals(afterRename, authorStamp(f.note.id))
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(f.targetMap))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, row.id, row.revision, row.data(), removed = true))
        }
        val afterRecycle = authorStamp(f.note.id)
        openManager()
        tap("map-portal-item-" + portal)
        waitFor("map-portal-preview-unavailable")
        compose.onNodeWithTag("map-portal-open").assertIsNotEnabled()
        compose.onNodeWithTag("map-portal-open").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(f.sourceMap, study(f.note.id).mapId.value)
            assertEquals(f.page, pages(f.note.id).ui.value.selectedId)
        }
        closePreviewAndManager()
        waitMap(f, f.sourceMap, f.sourceNode)
        assertEquals(afterRecycle, authorStamp(f.note.id))
        val stored = portals(f.note.id).single()
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.targetMap), stored.data())
        assertEquals(1L, stored.revision)
        assertFalse(stored.removed)

        // An explicitly chosen main map is persisted as null; the unavailable UUID never becomes null.
        val original = snapshot(f)
        openManager()
        tap("map-portal-create")
        tap("map-portal-destination-main")
        tap("map-portal-save")
        compose.waitUntil(15_000) { portals(f.note.id).count { !it.removed } == 2 }
        val mainPortal = portals(f.note.id).single { (it.data() as KnowledgeData.MapPortal).targetMapId == null }
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, null), mainPortal.data())
        assertEquals(1L, mainPortal.revision)
        val afterMainSave = authorStamp(f.note.id)
        preview(mainPortal.id, "主图")
        tap("map-portal-open")
        waitMap(f, null, f.mainNode)
        tap("map-portal-back")
        assertOrigin(f, original)
        assertEquals(afterMainSave, authorStamp(f.note.id))
        assertEquals(f.targetMap, (portals(f.note.id).single { it.id == portal }.data() as KnowledgeData.MapPortal).targetMapId)
    }

    /** Either observation has already disabled Open, or its explicit click must reject the captured revision. */
    private fun assertCapturedOpenRejected(f: Fixture, original: OriginView, expectedAuthor: List<String>) {
        val open = compose.onNodeWithTag("map-portal-open")
        if (runCatching { open.assertIsEnabled() }.isSuccess) open.performClick()
        waitFor("map-portal-preview-error")
        compose.onNodeWithTag("map-portal-open").assertIsNotEnabled()
        compose.onAllNodesWithTag("map-portal-back").assertCountEquals(0)
        assertOrigin(f, original)
        assertEquals("A stale preview must not overwrite or submit a replacement operation", expectedAuthor, authorStamp(f.note.id))
    }

    @Test fun capturedRevisionRejectsRetargetAndRemovalWithoutWrongNavigationOrWrites() {
        val f = fixture()
        val original = snapshot(f)
        val portal = seedPortal(f)
        openManager()
        preview(portal, f.targetTitle)
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(portal))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, portal, row.revision,
                KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.otherMap)))
        }
        val afterRetarget = authorStamp(f.note.id)
        assertCapturedOpenRejected(f, original, afterRetarget)
        compose.onAllNodes(hasTestTag("map-portal-preview-target") and hasText(f.otherTitle, substring = true))
            .assertCountEquals(0)
        closePreviewAndManager()
        openManager()
        preview(portal, f.otherTitle)
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(portal))
            assertEquals(2L, row.revision)
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, portal, row.revision, row.data(), removed = true))
        }
        val afterRemoval = authorStamp(f.note.id)
        assertCapturedOpenRejected(f, original, afterRemoval)
        closePreviewAndManager()
        assertOrigin(f, original)
        val removed = portals(f.note.id).single()
        assertEquals(3L, removed.revision)
        assertTrue(removed.removed)
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.otherMap), removed.data())
        assertEquals(3L, count("SELECT COUNT(*) FROM knowledge_revisions WHERE id=?", portal))
        assertEquals(3L, count("SELECT COUNT(*) FROM knowledge_receipts WHERE resultId=?", portal))
    }

    @Test fun activityRecreationKeepsDraftPreviewAndReturnIdentityWithoutExtraAuthorCommands() {
        val f = fixture()
        val original = branchView(f)
        val before = authorStamp(f.note.id)
        openManager()
        tap("map-portal-create")
        tap("map-portal-destination-" + f.targetMap)
        compose.activityRule.scenario.recreate()
        waitFor("map-portal-destination-picker")
        compose.onNodeWithTag("map-portal-save").assertIsEnabled()
        assertEquals("A restored draft must remain unsaved", before, authorStamp(f.note.id))
        tap("map-portal-save")
        compose.waitUntil(15_000) { portals(f.note.id).count { !it.removed } == 1 }
        val portal = portals(f.note.id).single()
        assertEquals(KnowledgeData.MapPortal(f.sourceMap, f.sourceNode, f.targetMap), portal.data())
        assertEquals(1L, portal.revision)
        val afterSave = authorStamp(f.note.id)
        preview(portal.id, f.targetTitle)
        compose.activityRule.scenario.recreate()
        waitFor("map-portal-preview-target")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("map-portal-preview-target").assertTextContains(f.targetTitle) }.isSuccess &&
                runCatching { compose.onNodeWithTag("map-portal-open").assertIsEnabled() }.isSuccess
        }
        assertEquals(afterSave, authorStamp(f.note.id))
        closePreviewAndManager()
        assertOrigin(f, original)
        openManager()
        preview(portal.id, f.targetTitle)
        tap("map-portal-open")
        waitMap(f, f.targetMap, f.targetNode)
        compose.activityRule.scenario.recreate()
        waitMap(f, f.targetMap, f.targetNode)
        waitFor("map-portal-back")
        tap("map-portal-back")
        assertOrigin(f, original)
        assertEquals("Recreation must not create another portal or receipt", afterSave, authorStamp(f.note.id))
        assertEquals(1L, count("SELECT COUNT(*) FROM knowledge_receipts WHERE resultId=?", portal.id))
    }

    @Test fun libraryLearningMapRecreationKeepsPortalTargetAndReturnsOriginalView() {
        val f = fixture()
        val portal = seedPortal(f)
        val entryTitle = "跨图重建入口 " + f.sourceMap.take(8)
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(f.sourceMap))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, row.id, row.revision,
                (row.data() as KnowledgeData.MapDefinition).copy(title = entryTitle)))
        }
        tap("study-close")
        tap("back-library")
        waitFor("new-note")
        if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
        compose.onNodeWithText("学习", useUnmergedTree = true).performScrollTo().performClick()
        waitFor("learning-workbench")
        compose.openLearningMapTarget(f.sourceMap,entryTitle)
        waitMap(f, f.sourceMap, f.sourceNode, inLibrary = true)
        compose.onNodeWithTag("learning-workbench").assertDoesNotExist()
        select(f.sourceNode)
        val original = branchView(f)
        val before = authorStamp(f.note.id)
        openManager()
        preview(portal, f.targetTitle)
        tap("map-portal-open")
        waitMap(f, f.targetMap, f.targetNode, inLibrary = true)
        waitFor("map-portal-back")
        compose.activityRule.scenario.recreate()
        waitMap(f, f.targetMap, f.targetNode, inLibrary = true)
        waitFor("map-portal-back")
        assertEquals("Rebuilding the real Library ancestor must not replay its initial source-map request",
            before, authorStamp(f.note.id))
        tap("map-portal-back")
        assertOrigin(f, original, inLibrary = true)
        compose.onNodeWithTag("map-portal-back").assertDoesNotExist()
        assertEquals(before, authorStamp(f.note.id))
        tap("study-close")
        waitFor("learning-workbench")
        compose.onNodeWithTag("widget-maps").performScrollTo()
        compose.onNodeWithTag("learning-map-search").assertTextContains(entryTitle)
        compose.runOnIdle {
            assertNull(ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
            assertEquals(f.page, pages(f.note.id).ui.value.selectedId)
        }
        assertEquals("Opening, recreating, returning and closing Library navigation must remain read-only",
            before, authorStamp(f.note.id))
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun fullyVisible(tag: String, container: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        val semantics = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        val control = semantics.boundsInRoot
        val window = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        assertTrue(tag + " must not be clipped by a scrolling parent",
            control.width >= semantics.size.width - 1 && control.height >= semantics.size.height - 1)
        assertTrue(tag + " must be fully visible inside its window", control.left >= window.left &&
            control.right <= window.right && control.top >= window.top && control.bottom <= window.bottom)
    }

    @Test fun narrow375LargeTextKeepsSpecifiedBranchSelectionAndReturnUsable() {
        val oldSize=Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity=Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont=shell("settings get system font_scale")
        try {
            shell("wm size 750x1600");shell("wm density 320");shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c=compose.activity.resources.configuration
                kotlin.math.abs(c.screenWidthDp-375)<=4 && kotlin.math.abs(c.fontScale-1.6f)<.02f }
            val f=fixture(longTitles=true)
            val title="目标分支："+"逐步核对条件概率与独立性，".repeat(4)+"末尾完整名称"
            runBlocking {
                val row=probe.knowledge().get(f.targetMap)!!
                val definition=row.data() as KnowledgeData.MapDefinition
                app.knowledge.submit(KnowledgeCommand(id(),f.note.id,f.targetMap,row.revision,
                    definition.copy(structures=definition.structures.map { it.copy(title=title) })))
            }
            val original=snapshot(f)
            val content=contentStamp(f.note.id)
            openManager(physical=true);tap("map-portal-create",physical=true)
            tap("map-portal-destination-"+f.targetMap,physical=true)
            fullyVisible("map-portal-target-branch","map-portal-destination-picker")
            tap("map-portal-target-branch",physical=true)
            compose.onNodeWithTag("map-portal-save").assertIsNotEnabled()
            fullyVisible("map-portal-branch-"+f.targetNode,"map-portal-destination-picker")
            tap("map-portal-branch-"+f.targetNode,physical=true)
            compose.onNodeWithTag("map-portal-branch-"+f.targetNode).assertTextEquals("✓ "+title)
            shot("narrow-large-type-picker")
            fullyVisible("map-portal-save","map-portal-destination-picker")
            tap("map-portal-save",physical=true)
            compose.waitUntil(15_000) { portals(f.note.id).count { !it.removed }==1 }
            val entry=portals(f.note.id).single()
            assertEquals(f.targetNode,(entry.data() as KnowledgeData.MapPortal).targetBranchId)
            assertEquals(content,contentStamp(f.note.id))
            val afterSave=authorStamp(f.note.id)
            preview(entry.id,f.targetTitle,physical=true)
            fullyVisible("map-portal-preview-branch","map-portal-preview")
            fullyVisible("map-portal-open","map-portal-preview")
            shot("narrow-large-type-preview")
            tap("map-portal-open",physical=true);waitMap(f,f.targetMap,f.targetNode)
            compose.runOnIdle { assertEquals(f.targetNode,study(f.note.id).focusedByMap[f.targetMap]) }
            fullyVisible("map-portal-back","study-panel")
            tap("map-portal-back",physical=true);assertOrigin(f,original)
            assertEquals(afterSave,authorStamp(f.note.id))
        } finally {
            shell(if(oldSize==null)"wm size reset"else"wm size "+oldSize)
            shell(if(oldDensity==null)"wm density reset"else"wm density "+oldDensity)
            shell(if(oldFont.matches(Regex("""[0-9.]+""")))"settings put system font_scale "+oldFont else"settings delete system font_scale")
        }
    }

    @Test fun narrow375LargeTextKeepsControlsReachableAndReciprocalPortalsFinite() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600")
            shell("wm density 320")
            shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val configuration = compose.activity.resources.configuration
                kotlin.math.abs(configuration.screenWidthDp - 375) <= 4 && kotlin.math.abs(configuration.fontScale - 1.6f) < .02f
            }
            val f = fixture(longTitles = true)
            val forward = seedPortal(f)
            val backward = seedPortal(f, f.targetMap, f.targetNode, f.sourceMap)
            val original = snapshot(f)
            val before = authorStamp(f.note.id)
            openManager(physical = true)
            fullyVisible("map-portal-create", "map-portal-manager")
            tap("map-portal-create", physical = true)
            fullyVisible("map-portal-destination-main", "map-portal-destination-picker")
            tap("map-portal-destination-main", physical = true)
            fullyVisible("map-portal-save", "map-portal-destination-picker")
            fullyVisible("map-portal-cancel-create", "map-portal-destination-picker")
            tap("map-portal-cancel-create", physical = true)
            fullyVisible("map-portal-close-manager", "map-portal-manager")
            preview(forward, f.targetTitle, physical = true)
            fullyVisible("map-portal-open", "map-portal-preview")
            fullyVisible("map-portal-remove", "map-portal-preview")
            fullyVisible("map-portal-close-preview", "map-portal-preview")
            closePreviewAndManager(physical = true)
            assertOrigin(f, original)
            openManager(physical = true)
            preview(forward, f.targetTitle, physical = true)
            tap("map-portal-open", physical = true)
            waitMap(f, f.targetMap, f.targetNode)
            fullyVisible("map-portal-back", "study-panel")
            select(f.targetNode)
            val targetView = snapshot(f)
            openManager(physical = true)
            preview(backward, "原图 · 保留视角", physical = true)
            compose.onAllNodes(hasTestTag("map-portal-item-" + forward) and hasAnyAncestor(hasTestTag("map-portal-preview")))
                .assertCountEquals(0)
            fullyVisible("map-portal-open", "map-portal-preview")
            tap("map-portal-open", physical = true)
            waitMap(f, f.sourceMap, f.sourceNode)
            compose.onAllNodesWithTag("map-portal-preview").assertCountEquals(0)
            fullyVisible("map-portal-back", "study-panel")
            tap("map-portal-back", physical = true)
            assertOrigin(f, targetView)
            tap("map-portal-back", physical = true)
            assertOrigin(f, original)
            compose.onAllNodesWithTag("map-portal-back").assertCountEquals(0)
            assertEquals("A → B → A must use finite view history and never write author records", before, authorStamp(f.note.id))
            assertEquals(2, portals(f.note.id).count { !it.removed })
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
            shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont
                else "settings delete system font_scale")
        }
    }
}
