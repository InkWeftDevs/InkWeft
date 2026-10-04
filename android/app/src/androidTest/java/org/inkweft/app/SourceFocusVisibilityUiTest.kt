// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
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

/** Independent V59 draft: real Activity, repositories, frame gestures and native
 * canvases. A world region is projected to actual screen pixels, then checked
 * against both the measured paper and the actual study panel. Not yet executed;
 * real tablet/pen acceptance is separate. Device settings are restored in finally.
 */
class SourceFocusVisibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = provider()[NotebookViewModel::class.java]
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]
    private fun pages(book: String) = provider()["book-$book", BookPagesViewModel::class.java]
    private fun lock(book: String) = provider()["read-lock-$book", BookReadLockViewModel::class.java]
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]
    private val database get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private fun id() = UUID.randomUUID().toString()
    private lateinit var savedPreferences: Map<String, Map<String, *>>

    @Before fun captureSettings() {
        savedPreferences = listOf("inkweft-study-window", "inkweft-reading", "inkweft-learning")
            .associateWith { app.getSharedPreferences(it, 0).all }
    }

    @After fun restoreSettings() {
        // Dispose active book/map UI before restoring global test settings. The
        // shared app Room database belongs to the Application and is never closed.
        try {
            compose.runOnIdle { app.openKnowledgeTarget.value = null; notebook().back() }
            waitFor("new-note")
        } finally {
            if (::savedPreferences.isInitialized) savedPreferences.forEach { (name, values) ->
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

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    private fun configured(size: String, density: Int, widthDp: Int, font: Float, block: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size $size"); shell("wm density $density")
            shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - widthDp) <= 4 && abs(c.fontScale - font) < .02f }
            block()
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale $oldFont"
                else "settings delete system font_scale")
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        println("SF59Touch tag=$tag")
        compose.revealAction(tag)
        if (tag == "quick-study" && compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            waitFor("toolbar-more"); compose.onNodeWithTag("toolbar-more").performTouchInput { click() }
        }
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val target = compose.onNodeWithTag(tag)
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
    }

    /** The fixed source/return control must be reached by a real touch directly. */
    private fun tapVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun activityViews(): List<View> {
        val found = mutableListOf<View>()
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst(); found.add(view)
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        return found
    }

    private fun map() = activityViews().filterIsInstance<MindMapView>().single { it.isShown }
    private fun rawScreenRect(view: View): RectF {
        val p = IntArray(2); view.getLocationOnScreen(p)
        return RectF(p[0].toFloat(), p[1].toFloat(), (p[0] + view.width).toFloat(), (p[1] + view.height).toFloat())
    }

    private fun screenRect(tag: String): RectF {
        // All frame/paper/reference tags belong to the Activity's Compose root.
        // Add its screen origin, rather than comparing native screen pixels to
        // unadjusted boundsInRoot (which omits the status/window origin).
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val origin = compose.runOnIdle {
            val owner = activityViews().first { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" && it.isShown }
            IntArray(2).also { owner.getLocationOnScreen(it) }
        }
        return RectF(origin[0] + b.left, origin[1] + b.top, origin[0] + b.right, origin[1] + b.bottom)
    }

    private fun contains(outer: RectF, inner: RectF, tolerance: Float = 1f) =
        inner.left >= outer.left - tolerance && inner.top >= outer.top - tolerance &&
            inner.right <= outer.right + tolerance && inner.bottom <= outer.bottom + tolerance

    private fun closeRect(expected: RectF, actual: RectF, tolerance: Float = 2f) =
        abs(expected.left - actual.left) <= tolerance && abs(expected.top - actual.top) <= tolerance &&
            abs(expected.right - actual.right) <= tolerance && abs(expected.bottom - actual.bottom) <= tolerance

    private fun paper(tag: String, measured: RectF): InkCanvasView = activityViews().filterIsInstance<InkCanvasView>()
        .filter { it.isShown && !it.preview && !it.embeddedPage }
        .single { closeRect(measured, rawScreenRect(it), 3f) }

    private data class Fixture(val note: Note, val other: Note, val sourcePage: String,
        val mapId: String, val card: String, val node: String, val child: String,
        val anchor: String, val stroke: InkStroke, val title: String, val body: String)

    private fun seed(longText: Boolean = false): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("SF59 来源可见 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val other = runBlocking { app.workspaceRepository.create("SF59 分屏原资料 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(160f, 930f, 0), InkSample(520f, 1060f, 50), InkSample(800f, 960f, 100)))
        val title = if (longText) "长名来源卡：" + "条件、范围与原迹定位的完整阅读上下文。".repeat(4) else "回到第二页的真实摘录区域"
        val body = if (longText) (1..70).joinToString("\n\n") {
            "第${it}段：只用于真实大字长文验收。回原文后纸面必须可见；返回窗口还要恢复原来的图节点、视野与折叠状态。"
        } else "源区域位于第二页下方。浮窗、专注模式或分屏都不应遮住回跳后的原文。"
        val f = Fixture(note, other, id(), id(), id(), id(), id(), id(), stroke, title, body)
        assertTrue(title.length <= 120); assertTrue(body.length <= 20_000)
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), f.sourcePage, 0, InkMutation.Add(stroke))))
            val otherStroke = InkStroke(id(), InkPen.PEN, 0xff198a4b.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(120f, 300f, 0), InkSample(340f, 340f, 100)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), other.id, 0, InkMutation.Add(otherStroke))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition("原图与折叠上下文")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                mapId = f.mapId, title = title, body = body,
                source = StudySourceDraft(f.sourcePage, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = f.child,
                mapId = f.mapId, parentId = f.node, title = "保持收起的下级主题", body = "只浏览，不改结构", x = 330.0, y = 210.0))
            // A persisted, valid Anchor reaches the actual NotebookApp resolver
            // when the original frame is minimized and cannot expose a card.
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.anchor, 0,
                KnowledgeData.Anchor(f.sourcePage, 1, stroke.bounds(), listOf(stroke.id))))
            for (book in listOf(note.id, other.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            app.pages.select(note.id, note.id)
        }
        app.getSharedPreferences("inkweft-study-window", 0).edit()
            .putString("${note.id}-mode", "COLLECT").putFloat("${note.id}-x", .82f).putFloat("${note.id}-y", .3f)
            .putFloat("${note.id}-COLLECT-width", 420f).putFloat("${note.id}-COLLECT-height", 340f)
            .putFloat("${note.id}-ORGANIZE-width", 580f).putFloat("${note.id}-ORGANIZE-height", 420f).commit()
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false).commit()
        compose.runOnIdle { notebook().select(note) }
        compose.singlePageEditor(); compose.waitForSavedInk()
        waitFor("ink-surface")
        tap("quick-study"); tap("study-map-picker"); tap("study-map-${f.mapId}"); tap("study-tab-2")
        waitMap(f)
        compose.onNodeWithTag("study-readonly").assertDoesNotExist()
        if (!compose.runOnIdle { lock(note.id).readOnly.value }) tap("quick-readonly")
        assertReadOnly(f, nativeMap = true)
        selectNode(f.node); tap("node-fold")
        compose.runOnIdle { assertNull(map().nodeBounds(f.child)); assertTrue(f.node in study(note.id).collapsedByMap[f.mapId].orEmpty()) }
        return f
    }

    private fun waitMap(f: Fixture) {
        waitFor("study-map")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = study(f.note.id).mapId.value == f.mapId && !study(f.note.id).ui.value.loading &&
                runCatching { map().width > 0 && map().height > 0 && map().nodeBounds(f.node) != null }.getOrDefault(false) }
            ready
        }
    }

    private fun selectNode(node: String, frame: Boolean = true) {
        val point = compose.runOnIdle { val v = map(); if (frame) assertTrue(v.focusNode(node))
            val b = checkNotNull(v.nodeBounds(node))
            assertTrue("The real selected-node touch must be inside the native map", b.centerX() in 0f..v.width.toFloat() && b.centerY() in 0f..v.height.toFloat())
            Offset(b.centerX(), b.centerY()) }
        compose.onNodeWithTag("study-map").performTouchInput {
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1); click(point)
        }
        waitFor("node-actions")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(node, map().selectedNodeId) }
    }

    private fun openBody(f: Fixture, frame: Boolean = true) {
        selectNode(f.node, frame); tap("node-more"); tap("node-view-content")
        waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        waitFor("study-open-source")
    }

    private data class GraphState(val mapId: String?, val tab: Int, val selected: Map<String, String?>,
        val viewports: Map<String, MapViewport>, val collapsed: Map<String, List<String>>, val focused: Map<String, String?>)
    private fun graphState(f: Fixture) = compose.runOnIdle {
        val vm = study(f.note.id)
        GraphState(vm.mapId.value, vm.lastTab, vm.selectedByMap.toMap(), vm.viewports.toMap(),
            vm.collapsedByMap.mapValues { it.value.toList() }, vm.focusedByMap.toMap())
    }
    private data class WindowState(val rect: RectF, val preferences: Map<String, Any?>, val graph: GraphState,
        val viewport: MapViewport, val selected: String?)
    private fun framePrefs(f: Fixture): Map<String, Any?> = app.getSharedPreferences("inkweft-study-window", 0)
        .all.filterKeys { it.startsWith("${f.note.id}-") }.toMap()

    private fun windowState(f: Fixture): WindowState {
        val rect = screenRect("study-panel")
        val native = compose.runOnIdle { map().snapshotViewport() to map().selectedNodeId }
        return WindowState(rect, framePrefs(f), graphState(f), native.first, native.second)
    }

    private fun assertReadOnly(f: Fixture, nativeMap: Boolean) {
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = lock(f.note.id).readOnly.value && !lock(f.note.id).canWrite }; ready }
        compose.runOnIdle {
            assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
            // Source geometry is checked while visible above; after returning to
            // a narrow map, the retained editor must still reject author input.
            val document = InkCanvasView::class.java.getDeclaredField("documentId").apply { isAccessible = true }
            val selectedPage = pages(f.note.id).ui.value.selectedId
            val editor = activityViews().filterIsInstance<InkCanvasView>().single {
                !it.preview && !it.embeddedPage && document.get(it) == selectedPage }
            assertFalse(editor.allowInput)
            if (nativeMap) assertFalse(map().authorEditing)
        }
    }

    private data class SourceGeometry(val paper: RectF, val projected: RectF, val viewport: CanvasViewport,
        val expected: CanvasViewport, val ready: Boolean, val nativeMetrics: String)
    private fun sourceGeometry(source: StudySourceRow, measured: RectF): SourceGeometry = compose.runOnIdle {
        val canvas = paper("ink-surface", measured)
        val density = canvas.resources.displayMetrics.density.toDouble()
        val world = CanvasBounds(source.left, source.top, source.right, source.bottom)
        val viewport = canvas.snapshotViewport()
        val a = viewport.worldToScreen(world.left, world.top, canvas.width.toDouble(), canvas.height.toDouble(), density)
        val b = viewport.worldToScreen(world.right, world.bottom, canvas.width.toDouble(), canvas.height.toDouble(), density)
        val canvasRect = rawScreenRect(canvas)
        val fit = CanvasViewport.fit(world.padded(60.0), canvas.width / density, canvas.height / density)
        SourceGeometry(canvasRect, RectF(canvasRect.left + a.x.toFloat(), canvasRect.top + a.y.toFloat(),
            canvasRect.left + b.x.toFloat(), canvasRect.top + b.y.toFloat()), viewport,
            // These fixture books are finite paper; focusRegion calls transform, which constrains the fit.
            fit.constrainedToPaper(canvas.width / density, canvas.height / density),
            canvas.width > 0 && canvas.height > 0 && canvas.documentContentReady && !canvas.rasterPending && canvas.displayedStrokeCount == 1,
            "native=${canvas.width}x${canvas.height} density=$density rawFit=$fit " +
                "documentReady=${canvas.documentContentReady} rasterPending=${canvas.rasterPending} strokes=${canvas.displayedStrokeCount}")
    }

    private fun assertSourceVisible(f: Fixture, source: StudySourceRow, collapsed: Boolean) {
        var lastMetrics = "Source geometry not sampled"
        var lastMapMetrics = "Native map not sampled"
        var lastProbeFailure: Throwable? = null
        try {
            waitFor("ink-surface"); waitFor("study-window-source-return")
            compose.waitUntil(15_000) { runCatching {
                val panel = screenRect(if (collapsed) "document-toolbar" else "study-panel"); val measured = screenRect("ink-surface")
                val g = sourceGeometry(source, measured)
                val selection = compose.runOnIdle { Triple(notebook().ui.value.selectedId,
                    pages(f.note.id).ui.value.selectedId, ink(source.pageId).ui.value.loading) }
                lastMetrics = "${g.nativeMetrics} selected=$selection ready=${g.ready} paper=${g.paper} " +
                    "projected=${g.projected} panel=$panel actual=${g.viewport} expected=${g.expected} " +
                    "contained=${contains(g.paper, g.projected)} overlap=${RectF.intersects(g.projected, panel)}"
                selection.first == f.note.id && selection.second == source.pageId && !selection.third &&
                    g.ready && abs(g.viewport.centerX - g.expected.centerX) < .5 &&
                    abs(g.viewport.centerY - g.expected.centerY) < .5 && abs(g.viewport.zoom - g.expected.zoom) < .01 &&
                    contains(g.paper, g.projected) && !RectF.intersects(g.projected, panel)
            }.onFailure { lastProbeFailure = it }.getOrDefault(false) }
            val panel = screenRect(if (collapsed) "document-toolbar" else "study-panel"); val measured = screenRect("ink-surface")
            val g = sourceGeometry(source, measured)
            assertTrue("Actual source world bounds must lie inside the measured native paper: ${g.projected} in ${g.paper}", contains(g.paper, g.projected))
            assertFalse("Actual source screen bounds must not overlap the study panel", RectF.intersects(g.projected, panel))
            assertEquals(g.expected.centerX, g.viewport.centerX, .5); assertEquals(g.expected.centerY, g.viewport.centerY, .5)
            assertEquals("Refit must use the current native AndroidView size", g.expected.zoom, g.viewport.zoom, .01)
            if (collapsed) {
                compose.onNodeWithTag("study-panel").assertIsNotDisplayed()
                compose.onNodeWithTag("study-map").assertIsNotDisplayed()
                compose.onNodeWithTag("study-window-source-return").assertIsDisplayed().assertIsEnabled()
                val returnAction = screenRect("study-window-source-return")
                val minimum = 48f * compose.activity.resources.displayMetrics.density
                assertTrue("The return action keeps its full touch target", returnAction.width() >= minimum && returnAction.height() >= minimum)
                assertTrue("Narrow source return stays inside the document header", contains(panel, returnAction))
                assertTrue("Document source header stays above actual paper", g.paper.top >= panel.bottom - 1f)
            } else {
                waitMap(f)
                assertTrue("Wide source paper lane must be left of the temporary dock", g.paper.right <= panel.left + 1f)
                compose.waitUntil(15_000) { runCatching { compose.runOnIdle {
                    val native = map(); val frame = rawScreenRect(native)
                    val selected = native.selectedNodeId
                    val node = selected?.let(native::nodeBounds)?.let { RectF(it).apply { offset(frame.left, frame.top) } }
                    lastMapMetrics = "map=$frame selected=$selected node=$node viewport=${native.snapshotViewport()}"
                    selected == f.node && node != null && !node.isEmpty && contains(frame, node, tolerance = 0f)
                } }.onFailure { lastProbeFailure = it }.getOrDefault(false) }
                compose.runOnIdle {
                    val native = map(); val frame = rawScreenRect(native)
                    assertEquals(f.node, native.selectedNodeId)
                    val node = RectF(checkNotNull(native.nodeBounds(f.node))).apply { offset(frame.left, frame.top) }
                    assertTrue("Actual selected-node bounds must lie wholly inside the native map: $node in $frame",
                        !node.isEmpty && contains(frame, node, tolerance = 0f))
                }
            }
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            compose.onNodeWithTag("continuous-pages").assertDoesNotExist()
            assertEquals(source.pageId, runBlocking { app.workspaceRepository.get(f.note.id).selectedPageId })
            assertReadOnly(f, nativeMap = !collapsed)
        } catch (failure: Throwable) {
            val image = "sf59-source-visible-failure-${source.pageId.take(8)}-${System.nanoTime()}.png"
            android.util.Log.e("SF59SourceGeometry", "book=${f.note.id} page=${source.pageId} collapsed=$collapsed " +
                "world=${source.left},${source.top},${source.right},${source.bottom} $lastMetrics " +
                "$lastMapMetrics lastProbeFailure=$lastProbeFailure screenshot=$image", failure)
            runCatching { screenshot(image) }.onFailure { android.util.Log.e("SF59SourceGeometry", "Failure screenshot unavailable", it) }
            throw failure
        }
    }

    private fun restoreWindow(f: Fixture, expected: WindowState) {
        tapVisible("study-window-source-return")
        compose.onNodeWithTag("study-window-source-return").assertDoesNotExist()
        waitMap(f)
        compose.waitUntil(15_000) { closeRect(expected.rect, screenRect("study-panel")) }
        assertTrue("Original floating/docked frame is restored", closeRect(expected.rect, screenRect("study-panel")))
        assertEquals(expected.preferences, framePrefs(f)); assertEquals(expected.graph, graphState(f))
        compose.runOnIdle {
            assertEquals(expected.viewport, map().snapshotViewport()); assertEquals(expected.selected, map().selectedNodeId)
            assertNull(map().nodeBounds(f.child)); assertFalse(map().authorEditing)
        }
        assertReadOnly(f, nativeMap = true)
    }

    private fun chooseMode(mode: String) {
        tapVisible("study-window-maximize"); tapVisible("study-window-mode-$mode")
        compose.onNodeWithTag("study-window-mode-$mode").assertDoesNotExist()
    }

    private fun dragFrame(dxDp: Float, dyDp: Float) {
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("study-window-drag").assertIsDisplayed().performTouchInput {
            val start = center; down(start)
            for (step in 1..6) moveTo(start + Offset(dxDp * density * step / 6, dyDp * density * step / 6), 32)
            up()
        }
        compose.waitForIdle()
    }

    private fun resizeFrame(dxDp: Float, dyDp: Float) {
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("study-window-resize").assertIsDisplayed().performTouchInput {
            val start = center; down(start)
            for (step in 1..6) moveTo(start + Offset(dxDp * density * step / 6, dyDp * density * step / 6), 32)
            up()
        }
        compose.waitForIdle()
    }

    private fun panActualSourceThenReopenSameCard(f: Fixture, original: StudySourceRow) {
        val beforeRect = screenRect("ink-surface")
        val before = compose.runOnIdle { paper("ink-surface", beforeRect).snapshotViewport() }
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("ink-surface").assertIsDisplayed().performTouchInput {
            val start = Offset(width * .35f, height * .5f); down(start)
            for (step in 1..6) moveTo(start + Offset(72f * density * step / 6, 36f * density * step / 6), 32)
            up()
        }
        compose.waitUntil(15_000) { val measured = screenRect("ink-surface")
            compose.runOnIdle { val now = paper("ink-surface", measured).snapshotViewport()
                abs(now.centerX - before.centerX) > 1.0 || abs(now.centerY - before.centerY) > 1.0 } }
        // Select the existing visible node without the fixture's focusNode helper:
        // the repeated-source test must not manufacture a map-viewport change.
        openBody(f, frame = false)
        tapVisible("study-open-source")
        assertSourceVisible(f, original, collapsed = false)
    }

    private fun touchSourceHeaderSearchAndClose(f: Fixture, original: StudySourceRow) {
        val header = screenRect("document-more"); val bar = screenRect("study-panel")
        assertFalse("Shifted actual document-header action must not overlap the collapsed source bar", RectF.intersects(header, bar))
        tapVisible("document-more")
        compose.onNodeWithTag("document-more-menu").assertIsDisplayed()
        tapVisible("book-search")
        waitFor("book-search-query")
        compose.onNodeWithTag("document-more-menu").assertDoesNotExist()
        compose.onNodeWithTag("book-search-query").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭查找页内文字", useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertSourceVisible(f, original, collapsed = true)
    }

    private fun source(f: Fixture) = runBlocking { checkNotNull(app.study.source(f.card)) }
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
    private fun authorStamp(book: String): List<String> = runBlocking {
        val db = database
        db.withTransaction { buildList {
            for (sql in authorQueries) {
                add(sql)
                db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { c ->
                        when (cursor.getType(c)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> "int:" + cursor.getLong(c)
                            Cursor.FIELD_TYPE_FLOAT -> "float:" + cursor.getDouble(c)
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(c).size + ":" + sha(cursor.getBlob(c))
                            else -> "text:" + cursor.getString(c).length + ":" + cursor.getString(c)
                        }
                    })
                }
            }
        } }
    }
    private fun assertAuthors(f: Fixture, own: List<String>, other: List<String>, original: StudySourceRow) {
        assertEquals(own, authorStamp(f.note.id)); assertEquals(other, authorStamp(f.other.id))
        val current = source(f)
        assertEquals(original.pageId, current.pageId); assertEquals(original.inkRevision, current.inkRevision)
        assertEquals(original.strokeIds, current.strokeIds); assertEquals(sha(original.snapshot), sha(current.snapshot))
        assertEquals(f.stroke.id, InkPageFile.decode(current.snapshot).strokes.single().id)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val nativeFrame = CountDownLatch(1)
        val decor = compose.activity.window.decorView
        val draw = ViewTreeObserver.OnDrawListener { nativeFrame.countDown() }
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(draw); decor.invalidate() }
        try { assertTrue("Screenshot must follow an actual native draw", nativeFrame.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { decor.viewTreeObserver.removeOnDrawListener(draw) } }
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow() =
        configured("1440x2200", 160, 1440, 1f) {
            val f = seed(); val original = source(f)
            val own = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val untouched = screenRect("study-panel"); val prefBeforeMove = framePrefs(f)
            dragFrame(-140f, 70f)
            compose.waitUntil(15_000) { !closeRect(untouched, screenRect("study-panel")) }
            assertNotEquals(prefBeforeMove, framePrefs(f))
            val moved = screenRect("study-panel")
            resizeFrame(64f, 56f)
            compose.waitUntil(15_000) { val next = screenRect("study-panel")
                next.width() > moved.width() + 30f && next.height() > moved.height() + 30f }
            assertTrue(framePrefs(f).containsKey("${f.note.id}-COLLECT-width"))
            for (mode in listOf("COLLECT", "ORGANIZE", "FOCUS")) {
                chooseMode(mode); waitMap(f)
                assertEquals(mode, framePrefs(f)["${f.note.id}-mode"])
                openBody(f)
                val originalWindow = windowState(f)
                tapVisible("study-open-source")
                assertSourceVisible(f, original, collapsed = false)
                assertEquals(originalWindow.preferences, framePrefs(f)); assertEquals(originalWindow.graph, graphState(f))
                assertAuthors(f, own, other, original)
                if (mode == "ORGANIZE") {
                    panActualSourceThenReopenSameCard(f, original)
                    assertEquals(originalWindow.preferences, framePrefs(f)); assertEquals(originalWindow.graph, graphState(f))
                    assertAuthors(f, own, other, original)
                }
                if (mode == "FOCUS") {
                    // Simulate the retained original camera arriving after the temporary dock is already visible.
                    val originalMapViewport = originalWindow.graph.viewports.getValue(f.mapId)
                    assertEquals(originalWindow.viewport, originalMapViewport)
                    compose.runOnIdle { map().restoreViewport(originalMapViewport) }
                    assertSourceVisible(f, original, collapsed = false)
                    compose.runOnIdle { assertEquals(originalMapViewport, study(f.note.id).viewports[f.mapId]) }
                    assertEquals(originalWindow.preferences, framePrefs(f)); assertEquals(originalWindow.graph, graphState(f))
                    assertAuthors(f, own, other, original)
                    screenshot("sf59-focus-visible.png")
                }
                restoreWindow(f, originalWindow)
                assertAuthors(f, own, other, original)
            }
        }

    @Test fun originalRightDockAndOriginalMinimizedSurviveTemporarySourcePresentation() =
        configured("1440x2200", 160, 1440, 1f) {
            val f = seed(); chooseMode("ORGANIZE")
            tapVisible("study-window-maximize"); tapVisible("study-window-dock")
            waitMap(f); openBody(f)
            val dock = windowState(f); val original = source(f)
            val own = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            tapVisible("study-open-source")
            assertSourceVisible(f, original, collapsed = false)
            assertEquals(dock.preferences, framePrefs(f)); assertEquals(dock.graph, graphState(f))
            assertAuthors(f, own, other, original)
            restoreWindow(f, dock)
            // Return to original floating state, then deliberately minimize it.
            tapVisible("study-window-maximize"); tapVisible("study-window-dock")
            waitMap(f)
            val full = windowState(f)
            tapVisible("study-window-minimize")
            compose.onNodeWithTag("study-map").assertDoesNotExist()
            val minimized = screenRect("study-panel"); val prefs = framePrefs(f); val context = graphState(f)
            val beforePaper = screenRect("ink-surface")
            compose.runOnIdle { pages(f.note.id).select(f.note.id) }
            compose.waitUntil(15_000) { compose.runOnIdle { pages(f.note.id).ui.value.selectedId == f.note.id } }
            compose.runOnIdle { app.openKnowledgeTarget.value = TargetRef(TargetKind.ANCHOR, f.anchor) }
            assertSourceVisible(f, original, collapsed = true)
            assertEquals(56f * compose.activity.resources.displayMetrics.density,
                screenRect("ink-surface").top - beforePaper.top, 2f)
            touchSourceHeaderSearchAndClose(f, original)
            assertEquals(prefs, framePrefs(f)); assertEquals(context, graphState(f)); assertAuthors(f, own, other, original)
            screenshot("sf59-minimized-visible.png")
            tapVisible("study-window-source-return")
            compose.onNodeWithTag("study-map").assertDoesNotExist()
            compose.waitUntil(15_000) { closeRect(minimized, screenRect("study-panel")) }
            assertEquals(prefs, framePrefs(f)); assertEquals(context, graphState(f))
            tapVisible("study-window-minimize")
            waitMap(f)
            compose.waitUntil(15_000) { closeRect(full.rect, screenRect("study-panel")) }
            compose.runOnIdle { assertEquals(full.viewport, map().snapshotViewport()); assertEquals(full.selected, map().selectedNodeId) }
            assertEquals(full.preferences, framePrefs(f)); assertEquals(full.graph, graphState(f))
            assertReadOnly(f, nativeMap = true); assertAuthors(f, own, other, original)
        }

    @Test fun narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame() =
        configured("750x1600", 320, 375, 1.6f) {
            val f = seed(longText = true); chooseMode("FOCUS"); waitMap(f); openBody(f)
            val originalWindow = windowState(f); val original = source(f)
            val own = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val paperBefore = screenRect("ink-surface")
            tapVisible("study-open-source")
            assertSourceVisible(f, original, collapsed = true)
            val button = compose.onNodeWithTag("study-window-source-return").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue(button.width >= 48f * density - 1f); assertTrue(button.height >= 48f * density - 1f)
            assertEquals(56f * density, screenRect("ink-surface").top - paperBefore.top, 2f)
            assertEquals(originalWindow.preferences, framePrefs(f)); assertEquals(originalWindow.graph, graphState(f))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - 375) <= 4 && abs(c.fontScale - 1.6f) < .02f }
            assertSourceVisible(f, original, collapsed = true)
            assertEquals(originalWindow.preferences, framePrefs(f)); assertEquals(originalWindow.graph, graphState(f))
            assertEquals(56f * density, screenRect("ink-surface").top - paperBefore.top, 2f)
            assertAuthors(f, own, other, original)
            screenshot("sf59-narrow-recreated.png")
            restoreWindow(f, originalWindow)
            assertEquals("FOCUS", framePrefs(f)["${f.note.id}-mode"])
            assertAuthors(f, own, other, original)
        }

    private fun assertReference(f: Fixture) {
        waitFor("reference-canvas")
        compose.waitUntil(15_000) { runCatching { val measured = screenRect("reference-canvas")
            compose.runOnIdle { val canvas = paper("reference-canvas", measured)
                canvas.documentContentReady && !canvas.rasterPending && canvas.displayedStrokeCount == 1 } }.getOrDefault(false) }
        val reference = screenRect("reference-canvas")
        compose.runOnIdle {
            assertEquals(f.note.id, notebook().ui.value.selectedId)
            assertFalse(paper("reference-canvas", reference).allowInput)
            assertEquals(1L, ink(f.other.id).ui.value.revision)
            assertEquals(f.other.id, InkCanvasView::class.java.getDeclaredField("documentId")
                .apply { isAccessible = true }.get(paper("reference-canvas", reference)))
        }
        compose.onNode(hasText(f.other.title) and hasAnyAncestor(hasTestTag("reference-pane")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph() =
        configured("1200x2200", 160, 1200, 1f) {
            val f = seed(); val original = source(f)
            // Open the other real notebook as a tab, then retain it in the actual
            // NotebookReferencePane while the source notebook remains the editor.
            tapVisible("study-close")
            compose.runOnIdle { notebook().select(f.other) }
            compose.singlePageEditor(); compose.waitForSavedInk()
            compose.runOnIdle { notebook().select(f.note) }
            compose.singlePageEditor(); compose.waitForSavedInk()
            tap("tabs-list")
            compose.onNodeWithTag("tabs-filter").performTextReplacement(f.other.title)
            tap("tabs-actions-${f.other.id}"); tap("tab-split-horizontal")
            assertReference(f)
            tap("quick-study"); waitMap(f)
            openBody(f)
            val originalWindow = windowState(f)
            val own = authorStamp(f.note.id); val other = authorStamp(f.other.id)
            val originalFramePrefs = framePrefs(f)
            tapVisible("study-open-source")
            assertSourceVisible(f, original, collapsed = true)
            assertReference(f); assertEquals(originalWindow.graph, graphState(f)); assertEquals(originalFramePrefs, framePrefs(f))
            // Width is local to the editor: the 600dp left pane is collapsed even
            // though the Activity is wide. The 1200dp top pane uses a right dock.
            tap("split-rotate")
            assertSourceVisible(f, original, collapsed = false)
            assertReference(f); assertEquals(originalWindow.graph, graphState(f)); assertEquals(originalFramePrefs, framePrefs(f))
            assertAuthors(f, own, other, original)
            screenshot("sf59-split-visible.png")
            tap("split-rotate")
            assertSourceVisible(f, original, collapsed = true)
            assertReference(f)
            restoreWindow(f, originalWindow)
            assertReference(f); assertAuthors(f, own, other, original)
            tap("split-close")
            compose.onNodeWithTag("reference-pane").assertDoesNotExist()
        }
}
