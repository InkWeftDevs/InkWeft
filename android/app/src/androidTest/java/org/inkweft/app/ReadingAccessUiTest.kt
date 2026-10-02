// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** V62 C-only draft. Real MainActivity, repositories, Room queue and native
 * views. Synthetic fixture ink and lost-UI-receipt simulation are identified
 * explicitly; they do not represent physical handwriting or a reproduced
 * storage failure. This author has not built or executed these four tests.
 */
class ReadingAccessUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val support by lazy { SelectAwaitTestSupport(compose) }
    private val app get() = support.app
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = support.notebook()
    private fun pages(book: String) = support.pages(book)
    private fun lock(book: String) = support.lock(book)
    private fun study(book: String) = support.study(book)
    private fun ink(page: String) = provider()["ink-$page", InkViewModel::class.java]
    private fun id() = UUID.randomUUID().toString()
    private val saved = linkedMapOf<String, Map<String, Any?>>()

    @Before fun captureDefaultWritingPreferences() {
        support.captureSettings()
        listOf("inkweft-editor", "inkweft-reading", "inkweft-study-window", "inkweft-learning",
            "inkweft-excerpts", "inkweft-pen-widths", "inkweft-selection").forEach(::capturePreference)
        // Keep the actual defaultHidden, especially readonly. Only collapse the
        // unrelated pen case so it cannot obscure the native input fixture.
        app.getSharedPreferences("inkweft-editor", 0).edit().clear().putBoolean("case-collapsed", true).commit()
        app.getSharedPreferences("inkweft-excerpts", 0).edit().putBoolean("text", false).putBoolean("to-map", false).commit()
    }
    @After fun restoreOwnedSettings() {
        try {
            compose.runOnIdle { views().filterIsInstance<InkCanvasView>().forEach { it.cancelGesture() } }
            if (exists("reading-more-menu")) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
            }
            if (exists("exit-fullscreen")) tap("exit-fullscreen")
        } finally {
            try { support.closeAndRestoreSettings() }
            finally { saved.forEach { (name, values) ->
                val editor = app.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> putPreference(editor, key, value) }
                assertTrue("Restore $name", editor.commit())
            } }
        }
    }
    private fun preference(name: String): Map<String, Any?> = app.getSharedPreferences(name, 0).all
        .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    private fun capturePreference(name: String) { if (name !in saved) saved[name] = preference(name) }
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
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun configured(size: String, density: Int, widthDp: Int, font: Float, block: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        val oldWidth = compose.activity.resources.configuration.screenWidthDp
        val oldScale = compose.activity.resources.configuration.fontScale
        try {
            shell("wm size $size"); shell("wm density $density"); shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate(); awaitConfiguration(widthDp, font); block()
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale $oldFont" else "settings delete system font_scale")
            compose.activityRule.scenario.recreate(); awaitConfiguration(oldWidth, oldScale)
        }
    }
    private fun awaitConfiguration(width: Int, font: Float) = compose.waitUntil(15_000) {
        val c = compose.activity.resources.configuration
        abs(c.screenWidthDp - width) <= 4 && abs(c.fontScale - font) < .02f
    }

    private data class Fixture(val note: Note, val reference: Note, val sourcePage: String, val thirdPage: String,
        val mapId: String, val card: String, val node: String, val body: String = "原始来源正文，搜索关闭后仍显示同一页原迹。")
    private fun seed(): Fixture {
        waitFor("new-note")
        val note = runBlocking { app.workspaceRepository.create("RA62 阅读入口 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val reference = runBlocking { app.workspaceRepository.create("RA62 参考原页 " + id().take(6), false, PaperStyle.BLANK, NotebookCover.FOREST) }
        val f = Fixture(note, reference, id(), id(), id(), id(), id())
        val source = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
            listOf(InkSample(520f, 980f, 0), InkSample(740f, 1010f, 100)))
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage); app.pages.addAfter(note.id, f.sourcePage, f.thirdPage)
            for ((page, stroke) in listOf(note.id to InkStroke(id(), InkPen.PEN, 0xff4a4a4a.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(170f, 600f, 0), InkSample(330f, 630f, 100))), f.sourcePage to source,
                f.thirdPage to InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
                    listOf(InkSample(200f, 350f, 0), InkSample(420f, 380f, 100))),
                reference.id to InkStroke(id(), InkPen.PEN, 0xff198a4b.toInt(), 4f, InkTool.STYLUS,
                    listOf(InkSample(160f, 450f, 0), InkSample(380f, 480f, 100)))))
                assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(CommitInk(id(), page, 0, InkMutation.Add(stroke))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.mapId, 0, KnowledgeData.MapDefinition("阅读来源图")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.card, nodeId = f.node,
                title = "第 2 页原文", body = f.body, mapId = f.mapId,
                source = StudySourceDraft(f.sourcePage, 1, source.bounds(), listOf(source.id))))
            for (book in listOf(note.id, reference.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "alpha 人工校对资料", method = "MANUAL"))
            app.pages.select(note.id, note.id)
        }
        capturePreference("inkweft-pen-widths-book-${note.id}")
        capturePreference("inkweft-pen-widths-book-${reference.id}")
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false)
            .putBoolean("continuous-v20-${reference.id}", false).commit()
        app.getSharedPreferences("inkweft-study-window", 0).edit().putString("${note.id}-mode", "FOCUS").commit()
        compose.runOnIdle { notebook().select(note) }
        compose.singlePageEditor(); compose.waitForSavedInk(); assertSelected(f, note.id)
        return f
    }
    private fun writingPreferences(f: Fixture) = listOf("inkweft-editor", "inkweft-pen-widths",
        "inkweft-pen-widths-book-${f.note.id}", "inkweft-pen-widths-book-${f.reference.id}", "inkweft-excerpts")
        .associateWith(::preference)
    private fun assertAuthors(f: Fixture, own: List<String>, reference: List<String>) {
        assertEquals(own, support.authorStamp(f.note.id)); assertEquals(reference, support.authorStamp(f.reference.id))
    }
    private fun graph(book: String): SelectAwaitTestSupport.GraphState = compose.runOnIdle {
        val vm = study(book)
        SelectAwaitTestSupport.GraphState(vm.mapId.value, vm.lastTab, vm.viewports.toMap(),
            vm.collapsedByMap.mapValues { it.value.toList() }, vm.selectedByMap.toMap(), vm.focusedByMap.toMap())
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) {
        try { compose.waitUntil(15_000) { exists(tag) }; compose.waitForIdle() }
        catch (failure: Throwable) {
            if (tag == "reading-toolbar") {
                compose.runOnIdle {
                    val book = checkNotNull(notebook().ui.value.selectedId)
                    val page = checkNotNull(pages(book).ui.value.selectedId)
                    val read = lock(book)
                    val guards = BookReadLockViewModel::class.java.getDeclaredField("guards").apply { isAccessible = true }.get(read)
                    val objects = provider()["objects-$page", PageObjectViewModel::class.java]
                    println("RA62EntryDiagnostic mode=${read.readOnly.value} reason=${read.reason} guards=$guards navigation=${app.navigationReady.value}")
                    println("RA62EntryDiagnostic ink=${ink(page).ui.value} objects=${objects.ui.value} authorActive=${objects.authorOperationActive}")
                }
                compose.onRoot(useUnmergedTree = true).printToLog("RA62EntryTree")
                shot("ra62-diagnostic-reading-entry.png")
            }
            throw failure
        }
    }
    private fun tap(tag: String, scroll: Boolean = false) {
        println("RA62Touch tag=$tag")
        waitFor(tag); val target = compose.onNodeWithTag(tag)
        if (scroll) target.performScrollTo()
        target.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }; compose.waitForIdle()
    }
    private fun closeSearch() {
        compose.onNodeWithContentDescription("关闭查找页内文字", useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle(); compose.onNodeWithTag("book-search-panel").assertDoesNotExist(); hideKeyboard()
    }
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        awaitImeHidden()
    }
    private fun awaitImeHidden() = compose.waitUntil(15_000) { compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true } }
    private fun views(): List<View> {
        val result = mutableListOf<View>(); val queue = java.util.ArrayDeque<View>(); queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) { val view = queue.removeFirst(); result.add(view)
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) } }
        return result
    }
    private inline fun <reified T : View> native() = views().filterIsInstance<T>().single { it.isShown }
    private fun rawScreenRect(view: View): RectF {
        val p = IntArray(2); view.getLocationOnScreen(p)
        return RectF(p[0].toFloat(), p[1].toFloat(), (p[0] + view.width).toFloat(), (p[1] + view.height).toFloat())
    }
    // These tags are in the Activity root. Popup menu geometry is compared only
    // within its own root, without assuming the Activity origin for a Popup.
    private fun screenRect(tag: String): RectF {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val origin = compose.runOnIdle {
            val root = views().first { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" && it.isShown }
            IntArray(2).also { root.getLocationOnScreen(it) }
        }
        return RectF(origin[0] + b.left, origin[1] + b.top, origin[0] + b.right, origin[1] + b.bottom)
    }
    private fun closeRect(a: RectF, b: RectF) = abs(a.left - b.left) <= 3f && abs(a.top - b.top) <= 3f &&
        abs(a.right - b.right) <= 3f && abs(a.bottom - b.bottom) <= 3f
    private fun paper(measured: RectF) = views().filterIsInstance<InkCanvasView>().single {
        it.isShown && !it.preview && !it.embeddedPage && closeRect(measured, rawScreenRect(it)) }
    private fun assertSelected(f: Fixture, page: String) {
        waitFor("ink-surface")
        compose.waitUntil(15_000) {
            val measured = screenRect("ink-surface")
            compose.runOnIdle { val p = paper(measured)
                notebook().ui.value.selectedId == f.note.id && pages(f.note.id).ui.value.selectedId == page &&
                    !ink(page).ui.value.loading && p.documentContentReady && !p.rasterPending &&
                    InkCanvasView::class.java.getDeclaredField("documentId").apply { isAccessible = true }.get(p) == page }
        }
        compose.runOnIdle { assertEquals(page, pages(f.note.id).ui.value.selectedId) }
    }
    private fun assertMode(f: Fixture, readOnly: Boolean, ready: Boolean = true) {
        compose.waitUntil(15_000) { compose.runOnIdle { lock(f.note.id).readOnly.value == readOnly && lock(f.note.id).canWrite != readOnly } }
        compose.onNodeWithTag(if (readOnly) "reading-toolbar" else "editor-toolbar").assertIsDisplayed()
        compose.onNodeWithTag(if (readOnly) "editor-toolbar" else "reading-toolbar").assertDoesNotExist()
        if (readOnly || ready) {
            val measured = screenRect("ink-surface")
            compose.runOnIdle { assertEquals(!readOnly, paper(measured).allowInput) }
        }
    }
    private fun settingsSwitch() {
        waitFor("settings-readonly")
        compose.onNodeWithTag("settings-readonly").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
    private fun enterReadingFromDefaultSettings(f: Fixture) {
        tap("quick-settings"); settingsSwitch(); compose.onNodeWithTag("settings-readonly").assertIsOff()
        tap("settings-readonly"); waitFor("reading-toolbar"); assertMode(f, true)
        compose.onNodeWithTag("document-settings-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings-readonly-reason").assertDoesNotExist()
        compose.onNodeWithTag("reading-more-menu").assertDoesNotExist(); awaitImeHidden()
    }
    private fun assertRejected(f: Fixture) {
        compose.onNodeWithTag("document-settings-dialog").assertIsDisplayed()
        compose.onNodeWithTag("settings-readonly").assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("settings-readonly-reason").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(lock(f.note.id).reason.isNotBlank()) }
        assertMode(f, false, ready = false)
    }
    private fun openReadingMore(fullscreen: Boolean) {
        tap("toolbar-more"); waitFor("reading-more-menu")
        val outer = compose.onNodeWithTag("reading-more-menu").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("reading-search", "reading-overview")) {
            if (!fullscreen) compose.onNodeWithTag(tag).assertDoesNotExist()
            else {
                val r = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                val density = compose.activity.resources.displayMetrics.density
                assertTrue("$tag actual target is at least 48dp: $r", r.width >= 48f * density - 1 && r.height >= 48f * density - 1)
                assertTrue("$tag is wholly reachable inside its real Popup", r.left >= outer.left - 1 && r.right <= outer.right + 1 && r.top >= outer.top - 1 && r.bottom <= outer.bottom + 1)
            }
        }
    }
    private fun assertFullscreen(f: Fixture) {
        compose.onNodeWithTag("exit-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("book-search").assertDoesNotExist(); assertMode(f, true)
        assertEquals(1, compose.onAllNodesWithTag("toolbar-more").fetchSemanticsNodes().size)
    }
    private fun result(page: String) {
        waitFor("book-search-results")
        compose.onNodeWithTag("book-search-results").performScrollToNode(hasTestTag("book-search-hit-$page"))
        compose.onNodeWithTag("book-search-hit-$page").assertIsDisplayed().assertIsEnabled()
    }
    private fun assertQuery(text: String) = compose.onNodeWithTag("book-search-query")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    private fun assertReference(f: Fixture) {
        waitFor("reference-canvas")
        compose.waitUntil(15_000) { val measured = screenRect("reference-canvas")
            compose.runOnIdle { val canvas = paper(measured)
                canvas.documentContentReady && !canvas.rasterPending && canvas.displayedStrokeCount == 1 && !canvas.allowInput &&
                    InkCanvasView::class.java.getDeclaredField("documentId").apply { isAccessible = true }.get(canvas) == f.reference.id } }
        compose.onNode(hasText(f.reference.title) and hasAnyAncestor(hasTestTag("reference-pane")), useUnmergedTree = true).assertIsDisplayed()
    }
    private fun motion(view: View, down: Long, time: Long, action: Int, point: Offset, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = .5f; size = .1f })
        val event = MotionEvent.obtain(down, time, action, 1, properties, coordinates, 0, 0, 1f, 1f, -1, 0,
            if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { if (view is InkCanvasView) view.onTouchEvent(event) else view.dispatchTouchEvent(event) }
        finally { event.recycle() }
    }
    private fun clickSwitchAtActualScreenBounds(bounds: RectF) {
        // Must run in the same Main turn as a real VM queue publication. Dispatch
        // actual finger DOWN/UP through decor hit-testing; never call a slot or
        // semantics action, nor yield to Compose before the live handler checks.
        val decor = compose.activity.window.decorView; val origin = IntArray(2); decor.getLocationOnScreen(origin)
        val point = Offset(bounds.centerX() - origin[0], bounds.centerY() - origin[1]); val now = SystemClock.uptimeMillis()
        motion(decor, now, now, MotionEvent.ACTION_DOWN, point, MotionEvent.TOOL_TYPE_FINGER)
        motion(decor, now, now + 20, MotionEvent.ACTION_UP, point, MotionEvent.TOOL_TYPE_FINGER)
    }
    private fun worldPoint(canvas: InkCanvasView, x: Double, y: Double): Offset {
        val p = canvas.snapshotViewport().worldToScreen(x, y, canvas.width.toDouble(), canvas.height.toDouble(), canvas.resources.displayMetrics.density.toDouble())
        return Offset(p.x.toFloat(), p.y.toFloat())
    }
    private fun shot(name: String) {
        compose.waitForIdle(); val instrumentation = InstrumentationRegistry.getInstrumentation(); instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1); val decor = compose.activity.window.decorView
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(listener); decor.invalidate() }
        try { assertTrue("Screenshot follows actual native draw", latch.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.waitForIdleSync(); val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun defaultSettingsEntryPreservesHiddenWritingToolsPreferencesAndRecreation() =
        configured("1200x1920", 160, 1200, 1f) {
            val f = seed(); val writing = writingPreferences(f)
            val own = support.authorStamp(f.note.id); val reference = support.authorStamp(f.reference.id)
            assertTrue("The fixture keeps readonly hidden by actual defaults", "readonly" in EditorToolOrder.defaultHidden)
            assertNull(app.getSharedPreferences("inkweft-editor", 0).getStringSet("toolbar-hidden-v32", null))
            tap("toolbar-more"); compose.onNodeWithTag("quick-readonly").assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle()
            tap("quick-settings"); tap("document-page-number", scroll = true)
            compose.onNodeWithTag("document-page-number").performTextReplacement("1")
            settingsSwitch(); val switch = compose.onNodeWithTag("settings-readonly").fetchSemanticsNode().boundsInRoot
            assertTrue(switch.width >= 48f * compose.activity.resources.displayMetrics.density - 1)
            tap("settings-readonly"); waitFor("reading-toolbar"); assertMode(f, true); awaitImeHidden()
            compose.onNodeWithTag("document-settings-dialog").assertDoesNotExist()
            compose.onNodeWithTag("settings-readonly-reason").assertDoesNotExist()
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
            compose.activityRule.scenario.recreate(); awaitConfiguration(1200, 1f)
            waitFor("reading-toolbar"); assertSelected(f, f.note.id); assertMode(f, true)
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            openReadingMore(fullscreen = false); tap("quick-fullscreen"); waitFor("exit-fullscreen")
            openReadingMore(fullscreen = true); tap("quick-fullscreen"); waitFor("book-search")
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference); shot("ra62-default-reading.png")
            tap("quick-settings"); settingsSwitch(); compose.onNodeWithTag("settings-readonly").assertIsOn()
            tap("settings-readonly"); waitFor("editor-toolbar"); assertMode(f, false)
            compose.onNodeWithTag("document-settings-dialog").assertDoesNotExist()
            tap("toolbar-more"); compose.onNodeWithTag("quick-readonly").assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle()
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
        }

    @Test fun openSettingsLiveGateRejectsQueuedOriginalReceiptHeldNativeInputAndRealPreviewDraft() =
        configured("1200x1920", 160, 1200, 1f) {
            val f = seed(); tap("quick-settings"); settingsSwitch()
            val switch = screenRect("settings-readonly")
            // Fixture-only original author command: canonical group preparation
            // publishes a genuine queue. Touch immediately, before composition
            // can update its previous ready closure/SideEffect guards.
            val command = compose.runOnIdle {
                val prepared = ink(f.note.id).prepareGroup(listOf(InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
                    listOf(InkSample(600f, 260f, 0), InkSample(780f, 290f, 100)))))
                assertTrue(ink(f.note.id).ui.value.queued > 0)
                clickSwitchAtActualScreenBounds(switch); prepared
            }
            waitFor("settings-readonly-reason"); assertRejected(f)
            // Commit the real original receipt as seed data BEFORE the baseline.
            // Unknown below means its UI receipt was synthetically lost, not an
            // actual reproduced SQLite failure and not a fabricated InkUi flag.
            runBlocking {
                assertEquals(InkCommitResult.Committed(2), app.inkRepository.save(command))
                assertTrue(app.pages.saveSearchText(f.note.id, 2, "alpha 人工校对资料", method = "MANUAL"))
            }
            val own = support.authorStamp(f.note.id); val reference = support.authorStamp(f.reference.id)
            assertTrue("The author baseline contains the real original receipt identity", own.any { it.contains(command.commandId) })
            val writing = writingPreferences(f)
            compose.runOnIdle { ink(f.note.id).completeGroup(command, InkCommitResult.Unknown) }
            compose.waitUntil(15_000) { compose.runOnIdle { ink(f.note.id).ui.value.blocked == InkCommitResult.Unknown } }
            tap("settings-readonly"); assertRejected(f); assertAuthors(f, own, reference)
            val gate = support.WriterGate("RA62 original receipt retry")
            try {
                gate.awaitHeld(); compose.runOnIdle { ink(f.note.id).retry() }
                compose.waitUntil(15_000) { compose.runOnIdle { ink(f.note.id).ui.value.queued > 0 && ink(f.note.id).ui.value.blocked == null } }
                tap("settings-readonly"); assertRejected(f)
                // Author stamps must not queue behind this held transaction.
            } finally { gate.finish() }
            compose.waitUntil(15_000) { compose.runOnIdle { val ui = ink(f.note.id).ui.value
                ui.revision == 2L && ui.queued == 0 && ui.blocked == null && !ui.processing } }
            val history = compose.runOnIdle { ink(f.note.id).ui.value.canUndo to ink(f.note.id).ui.value.canRedo }
            assertEquals(true to false, history)
            assertAuthors(f, own, reference)
            assertEquals(2L, runBlocking { app.inkRepository.read(f.note.id).revision })
            // Fewer than 128 samples: no checkpoint. Cancel never submits ink.
            val paperRect = screenRect("ink-surface")
            val down = SystemClock.uptimeMillis()
            compose.runOnIdle { val canvas = paper(paperRect); assertTrue(canvas.allowInput)
                motion(canvas, down, down, MotionEvent.ACTION_DOWN, worldPoint(canvas, 170.0, 600.0))
                motion(canvas, down, down + 20, MotionEvent.ACTION_MOVE, worldPoint(canvas, 210.0, 620.0)) }
            try { tap("settings-readonly"); assertRejected(f) }
            finally { compose.runOnIdle { val canvas = paper(paperRect)
                motion(canvas, down, down + 40, MotionEvent.ACTION_CANCEL, worldPoint(canvas, 210.0, 620.0)) } }
            compose.waitUntil(15_000) { app.navigationReady.value }; assertAuthors(f, own, reference)
            // Actual non-modal capture preview, with no OCR and no Save command.
            tap("top-excerpt"); waitFor("selection-overlay")
            val selectionPaper = screenRect("ink-surface")
            val points = compose.runOnIdle { val canvas = paper(selectionPaper)
                worldPoint(canvas, 140.0, 570.0) to worldPoint(canvas, 360.0, 660.0) }
            compose.onNodeWithTag("selection-overlay").performTouchInput { swipe(points.first, points.second, 260) }
            waitFor("capture-confirm"); compose.waitUntil(15_000) { compose.runOnIdle { lock(f.note.id).hasDraft.value } }
            val draft = compose.runOnIdle { checkNotNull(native<SelectionOverlayView>().region).bounds }
            tap("settings-readonly"); assertRejected(f)
            compose.onNodeWithTag("capture-confirm").assertIsDisplayed()
            compose.runOnIdle { assertEquals(draft, native<SelectionOverlayView>().region?.bounds); assertTrue(lock(f.note.id).hasDraft.value) }
            assertAuthors(f, own, reference); shot("ra62-gate-draft-rejected.png")
            compose.onNode(hasText("取消") and hasAnyAncestor(hasTestTag("selection-context-menu")), useUnmergedTree = true)
                .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(15_000) { compose.runOnIdle { !lock(f.note.id).hasDraft.value } }
            compose.onNodeWithTag("capture-confirm").assertDoesNotExist()
            tap("settings-readonly"); waitFor("reading-toolbar"); assertMode(f, true)
            compose.onNodeWithTag("document-settings-dialog").assertDoesNotExist()
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
            tap("exit-readonly"); waitFor("editor-toolbar"); assertMode(f, false, ready = false)
            // The user's excerpt tool stays selected. Its real author overlay,
            // rather than pen input on the underlying paper, resumes writing.
            waitFor("selection-overlay")
            compose.runOnIdle { assertTrue(native<SelectionOverlayView>().enabledInput)
                assertEquals(history, ink(f.note.id).ui.value.canUndo to ink(f.note.id).ui.value.canRedo) }
            assertAuthors(f, own, reference)
        }

    @Test fun narrowSplitFullscreenSearchAndOverviewRemainReachableAndRetainLockReferenceAndPage() =
        configured("750x1600", 320, 375, 1.6f) {
            val f = seed(); enterReadingFromDefaultSettings(f)
            compose.runOnIdle { notebook().select(f.reference) }; compose.singlePageEditor(); compose.waitForSavedInk()
            compose.runOnIdle { notebook().select(f.note) }; compose.singlePageEditor(); compose.waitForSavedInk()
            waitFor("reading-toolbar"); tap("tabs-list")
            compose.onNodeWithTag("tabs-filter").performTextReplacement(f.reference.title)
            tap("tabs-actions-${f.reference.id}"); tap("tab-split-horizontal"); hideKeyboard()
            assertReference(f); assertMode(f, true)
            val density = compose.activity.resources.displayMetrics.density
            val lane = compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
            assertEquals(186.5f, lane.width / density, 3f)
            val own = support.authorStamp(f.note.id); val reference = support.authorStamp(f.reference.id); val writing = writingPreferences(f)
            openReadingMore(fullscreen = false); tap("quick-fullscreen"); waitFor("exit-fullscreen")
            assertFullscreen(f); assertReference(f); openReadingMore(fullscreen = true)
            assertAuthors(f, own, reference); shot("ra62-narrow-split-menu.png")
            tap("reading-search"); waitFor("book-search-query")
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            compose.onNodeWithTag("book-search-query").performTextReplacement("alpha")
            result(f.sourcePage); closeSearch(); assertFullscreen(f); assertReference(f)
            openReadingMore(fullscreen = true); tap("reading-search"); waitFor("book-search-query"); assertQuery("alpha")
            result(f.sourcePage); tap("book-search-hit-${f.sourcePage}")
            compose.onNodeWithTag("book-search-panel").assertDoesNotExist(); hideKeyboard()
            assertSelected(f, f.sourcePage); assertFullscreen(f); assertReference(f)
            openReadingMore(fullscreen = true); tap("reading-overview"); waitFor("pages-directory-dialog")
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-3")); tap("jump-page-3")
            assertSelected(f, f.thirdPage); compose.onNodeWithTag("pages-directory-dialog").assertIsDisplayed()
            tap("pages-directory-dialog-close"); assertFullscreen(f); assertReference(f)
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.6f)
            waitFor("reading-toolbar"); assertSelected(f, f.thirdPage); assertFullscreen(f); assertReference(f)
            compose.onNodeWithTag("reading-more-menu").assertDoesNotExist()
            openReadingMore(fullscreen = true); tap("quick-fullscreen"); waitFor("book-search")
            openReadingMore(fullscreen = false)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle()
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
            tap("exit-readonly"); waitFor("editor-toolbar"); assertMode(f, false); assertReference(f)
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference); tap("split-close")
        }

    private fun openSourceBody(f: Fixture) {
        tap("quick-study"); tap("study-map-picker"); tap("study-map-${f.mapId}"); waitFor("study-map")
        compose.waitUntil(15_000) { compose.runOnIdle { !study(f.note.id).ui.value.loading && native<MindMapView>().nodeBounds(f.node) != null } }
        val point = compose.runOnIdle { val map = native<MindMapView>(); assertTrue(map.focusNode(f.node))
            val bounds = checkNotNull(map.nodeBounds(f.node)); Offset(bounds.centerX(), bounds.centerY()) }
        compose.onNodeWithTag("study-map").performTouchInput { advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1); click(point) }
        waitFor("node-actions"); compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        tap("node-more"); tap("node-view-content"); waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
    }
    private fun assertSourceVisible(f: Fixture, source: StudySourceRow) {
        assertSelected(f, source.pageId); waitFor("study-window-source-return")
        var diagnostics = "No geometry sampled"
        compose.waitUntil(15_000) {
            val measured = screenRect("ink-surface"); val panel = screenRect("study-panel")
            compose.runOnIdle {
                val canvas = paper(measured); val viewport = canvas.snapshotViewport(); val d = canvas.resources.displayMetrics.density.toDouble()
                val world = CanvasBounds(source.left, source.top, source.right, source.bottom)
                val a = viewport.worldToScreen(world.left, world.top, canvas.width.toDouble(), canvas.height.toDouble(), d)
                val b = viewport.worldToScreen(world.right, world.bottom, canvas.width.toDouble(), canvas.height.toDouble(), d)
                val rect = rawScreenRect(canvas)
                val projected = RectF(rect.left + a.x.toFloat(), rect.top + a.y.toFloat(), rect.left + b.x.toFloat(), rect.top + b.y.toFloat())
                val expected = CanvasViewport.fit(world.padded(60.0), canvas.width / d, canvas.height / d).constrainedToPaper(canvas.width / d, canvas.height / d)
                diagnostics = "page=${pages(f.note.id).ui.value.selectedId} native=$rect source=$projected panel=$panel actual=$viewport expected=$expected"
                val fits = projected.left >= rect.left - 1 && projected.top >= rect.top - 1 && projected.right <= rect.right + 1 && projected.bottom <= rect.bottom + 1
                fits && !RectF.intersects(projected, panel) && abs(viewport.centerX - expected.centerX) < .5 &&
                    abs(viewport.centerY - expected.centerY) < .5 && abs(viewport.zoom - expected.zoom) < .01
            }
        }
        println("RA62SourceVisible $diagnostics"); assertMode(f, true)
    }
    @Test fun fullscreenSearchCloseKeepsActualSourceBoundsSnapshotAndGraphContextVisible() =
        configured("1200x1920", 160, 1200, 1f) {
            val f = seed(); enterReadingFromDefaultSettings(f); openSourceBody(f)
            val source = support.source(f.card); val snapshot = support.sha(source.snapshot)
            val own = support.authorStamp(f.note.id); val reference = support.authorStamp(f.reference.id); val writing = writingPreferences(f)
            val originalGraph = graph(f.note.id)
            val frame = preference("inkweft-study-window")
            tap("study-open-source"); assertSourceVisible(f, source)
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            openReadingMore(fullscreen = false); tap("quick-fullscreen"); waitFor("exit-fullscreen")
            assertSourceVisible(f, source); assertFullscreen(f)
            val measured = screenRect("ink-surface"); val before = compose.runOnIdle { paper(measured).snapshotViewport() }
            openReadingMore(fullscreen = true); tap("reading-search"); waitFor("book-search-query")
            compose.onNodeWithTag("book-search-query").performTextReplacement("alpha"); result(f.sourcePage); closeSearch()
            assertFullscreen(f); assertSourceVisible(f, source)
            val afterRect = screenRect("ink-surface"); val after = compose.runOnIdle { paper(afterRect).snapshotViewport() }
            assertEquals(before.centerX, after.centerX, .5); assertEquals(before.centerY, after.centerY, .5); assertEquals(before.zoom, after.zoom, .01)
            openReadingMore(fullscreen = true); tap("reading-search"); waitFor("book-search-query"); assertQuery("alpha"); closeSearch()
            assertSourceVisible(f, source); assertFullscreen(f)
            assertEquals(snapshot, support.sha(support.source(f.card).snapshot))
            assertEquals(originalGraph, graph(f.note.id))
            compose.runOnIdle { assertEquals(f.node, native<MindMapView>().selectedNodeId) }
            assertEquals(frame, preference("inkweft-study-window")); assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
            shot("ra62-source-search-closed.png")
            openReadingMore(fullscreen = true); tap("quick-fullscreen"); waitFor("book-search")
            tap("study-window-source-return"); tap("study-close")
            assertSelected(f, f.sourcePage); assertMode(f, true)
            tap("exit-readonly"); waitFor("editor-toolbar"); assertMode(f, false)
            assertEquals(writing, writingPreferences(f)); assertAuthors(f, own, reference)
        }
}
