// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Real MainActivity/Room/physical input; three synthetic, author-preserving motion cases. */
class LocalMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val support by lazy { SelectAwaitTestSupport(compose) }
    private val app get() = support.app
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val preferenceNames = listOf("inkweft-editor", "inkweft-excerpts", "inkweft-tape")
    private var preferences = emptyMap<String, Map<String, *>>()
    private var display: Triple<String?, String?, String>? = null
    private var oldScale: String? = null
    private var oldAccessibilityFlags: Int? = null
    private fun id() = UUID.randomUUID().toString()

    @Before fun captureOwnedState() {
        val info = automation.serviceInfo
        oldAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        support.captureSettings()
        preferences = preferenceNames.associateWith { app.getSharedPreferences(it, 0).all.toMap() }
        display = Triple(Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1),
            Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1),
            shell("settings get system font_scale"))
        oldScale = shell("settings get global animator_duration_scale")
    }

    @After fun restoreOwnedState() {
        compose.mainClock.autoAdvance = true
        try { support.closeAndRestoreSettings() }
        finally {
            oldAccessibilityFlags?.let { flags ->
                val info = automation.serviceInfo
                info.flags = flags
                automation.serviceInfo = info
            }
            preferences.forEach { (name, values) ->
                val edit = app.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> preference(edit, key, value) }
                assertTrue(edit.commit())
            }
            oldScale?.let { shell(if (it == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale " + it) }
            display?.let { (size, density, font) ->
                shell(if (size == null) "wm size reset" else "wm size " + size)
                shell(if (density == null) "wm density reset" else "wm density " + density)
                shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale " + font)
                compose.activityRule.scenario.recreate()
                compose.waitForIdle()
            }
        }
    }

    private fun preference(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> edit.remove(key)
            else -> error("Unexpected preference type")
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun configure(width: Int, narrow: Boolean) {
        compose.mainClock.autoAdvance = true
        shell("wm size " + if (narrow) "750x1600" else "1440x2200")
        shell("wm density " + if (narrow) "320" else "160")
        shell("settings put system font_scale " + if (narrow) "1.65" else "1")
        shell("settings put global animator_duration_scale 1")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) {
            val config = compose.activity.resources.configuration
            abs(config.screenWidthDp - width) <= 4 &&
                abs(config.fontScale - if (narrow) 1.65f else 1f) < .02f
        }
        support.waitFor("new-note")
    }

    private fun views(): List<View> {
        val queue = java.util.ArrayDeque<View>()
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach(queue::add)
        return buildList {
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst(); add(view)
                if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
            }
        }
    }

    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    /** Match the actual semantics owner, including a separate CardInspector/Dialog window. */
    private fun screenRect(tag: String): RectF =
        screenRect(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    private fun screenRect(node: SemanticsNode): RectF {
        var root = node
        while (root.parent != null) root = checkNotNull(root.parent)
        val rootId = root.id
        val origin = main {
            val owner = views().first { view ->
                if (view.javaClass.name != "androidx.compose.ui.platform.AndroidComposeView" || !view.isShown) false
                else runCatching {
                    val semantics = view.javaClass.getMethod("getSemanticsOwner").invoke(view)
                    val ownerRoot = semantics.javaClass.getMethod("getUnmergedRootSemanticsNode").invoke(semantics) as SemanticsNode
                    ownerRoot.id == rootId
                }.getOrDefault(false)
            }
            IntArray(2).also(owner::getLocationOnScreen)
        }
        val bounds = node.boundsInRoot
        return RectF(origin[0] + bounds.left, origin[1] + bounds.top,
            origin[0] + bounds.right, origin[1] + bounds.bottom)
    }

    private fun prepareTouch(tag: String): Offset {
        compose.revealAction(tag)
        for (list in listOf("notebook-review-panel", "excerpt-list")) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(list).fetchSemanticsNodes().isNotEmpty()) {
                assertTrue("Prepare lazy-list targets before pausing the animation clock: " + tag,
                    compose.mainClock.autoAdvance)
                compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
            }
        }
        val target = compose.onNodeWithTag(tag)
        if (compose.mainClock.autoAdvance) runCatching { target.performScrollTo() }
        else assertTrue("Prepare the complete touch target before pausing the animation clock: " + tag,
            completeTargetVisible(tag))
        target.assertIsDisplayed().assertIsEnabled()
        val node = target.fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        val (touchBounds, clips) = clippedTouchBounds(node)
        val diagnostic = tag + " visual=" + node.boundsInRoot + " visualSize=" + node.size + " layout=" + node.layoutInfo.width + "x" +
            node.layoutInfo.height + " rawTouch=" + node.touchBoundsInRoot + " clippedTouch=" + touchBounds +
            " clips=" + clips + " density=" + density + " font=" + compose.activity.resources.configuration.fontScale
        android.util.Log.i("InkWeft-LM66", "Actual touch geometry " + diagnostic)
        assertTrue("Actual 48dp touch target " + diagnostic, touchBounds.width >= 47.5f * density &&
            touchBounds.height >= 47.5f * density)
        assertTrue("Target fully inside its actual clip " + diagnostic,
            node.boundsInRoot.width >= node.size.width - 1 &&
                node.boundsInRoot.height >= node.size.height - 1)
        return touchBounds.center - node.boundsInRoot.topLeft
    }

    /** Public minimum-touch geometry can expand past a clipping parent. Intersect
     * its real scrolling viewport, owning root and explicitly clipped study frame. */
    private fun clippedTouchBounds(node: SemanticsNode): Pair<Rect, String> {
        var bounds = node.touchBoundsInRoot
        val clips = mutableListOf<String>()
        var ancestor = node.parent
        while (ancestor != null) {
            val current = ancestor
            val tag = current.config.getOrNull(SemanticsProperties.TestTag)
            if (current.parent == null ||
                current.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                current.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null || tag == "study-panel") {
                bounds = bounds.intersect(current.boundsInRoot)
                clips += (tag ?: if (current.parent == null) "owner-root" else "scroll-viewport") +
                    ":" + current.boundsInRoot
            }
            ancestor = current.parent
        }
        assertTrue("Actual owning root must be part of touch clip", clips.isNotEmpty())
        return bounds to clips.joinToString(";")
    }

    private fun touch(tag: String) {
        val point = prepareTouch(tag)
        compose.onNodeWithTag(tag).performTouchInput { click(point) }
    }

    private fun completeTargetVisible(tag: String): Boolean = runCatching {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        node.boundsInRoot.width >= node.size.width - 1 &&
            node.boundsInRoot.height >= node.size.height - 1
    }.getOrDefault(false)

    private fun tick(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); drawFrame() }
    }

    private fun drawFrame() {
        instrumentation.waitForIdleSync()
        val roots = main { WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0
        } }
        assertTrue("Actual visible window required", roots.isNotEmpty())
        val frame = CountDownLatch(roots.size)
        val listeners = roots.associateWith {
            var delivered = false
            ViewTreeObserver.OnDrawListener { if (!delivered) { delivered = true; frame.countDown() } }
        }
        instrumentation.runOnMainSync { listeners.forEach { (root, listener) ->
            root.viewTreeObserver.addOnDrawListener(listener); root.invalidate()
        } }
        try { assertTrue("Actual native draw required", frame.await(5, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { listeners.forEach { (root, listener) ->
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener)
        } } }
        instrumentation.waitForIdleSync()
    }

    private fun awaitFrames(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            assertTrue("Real UI condition did not arrive", SystemClock.uptimeMillis() < deadline)
            tick()
        }
    }

    private fun capture(tag: String): Bitmap {
        drawFrame()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("Capture must contain the complete actual layout " + tag,
            node.boundsInRoot.width >= node.size.width - 1 &&
                node.boundsInRoot.height >= node.size.height - 1)
        val rect = screenRect(tag)
        val whole = checkNotNull(automation.takeScreenshot())
        try {
            val left = floor(rect.left).toInt(); val top = floor(rect.top).toInt()
            val right = ceil(rect.right).toInt(); val bottom = ceil(rect.bottom).toInt()
            assertTrue("Capture must use the actual fully visible region " + tag,
                left >= 0 && top >= 0 && right <= whole.width && bottom <= whole.height)
            return Bitmap.createBitmap(whole, left, top, right - left, bottom - top)
        } finally { whole.recycle() }
    }

    private fun captureSourceCaption(): Pair<String, Bitmap> {
        fun firstText(node: SemanticsNode): SemanticsNode? =
            if (!node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) node
            else node.children.firstNotNullOfOrNull { child -> firstText(child) }
        drawFrame()
        val group = compose.onNodeWithTag("card-source-content", useUnmergedTree = true).fetchSemanticsNode()
        val caption = checkNotNull(firstText(group))
        val bounds = caption.boundsInRoot
        val diagnostic = "caption bounds=" + bounds + " size=" + caption.size +
            " groupBounds=" + group.boundsInRoot + " groupSize=" + group.size
        assertTrue("Real source caption must be fully visible: " + diagnostic,
            caption.size.width > 0 && caption.size.height > 0 &&
                bounds.width >= caption.size.width - 1 && bounds.height >= caption.size.height - 1)
        val rect = screenRect(caption)
        val image = checkNotNull(automation.takeScreenshot())
        try {
            val left = floor(rect.left).toInt(); val top = floor(rect.top).toInt()
            val right = ceil(rect.right).toInt(); val bottom = ceil(rect.bottom).toInt()
            val screenDiagnostic = diagnostic + " screen=" + rect + " screenshot=" + image.width + "x" + image.height
            android.util.Log.i("InkWeft-LM66", "Actual source caption capture " + screenDiagnostic)
            assertTrue("Caption crop must contain its complete actual screen region: " + screenDiagnostic,
                rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite() &&
                    left >= 0 && top >= 0 && right > left && bottom > top &&
                    right <= image.width && bottom <= image.height)
            val text = caption.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("\n") { it.text }
            // Sample only the actual text node; the asynchronous thumbnail is a separate control.
            return text to Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        } finally { image.recycle() }
    }

    private fun digest(bitmap: Bitmap): String {
        val values = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteBuffer.allocate(values.size * 4)
        values.forEach { bytes.putInt(it) }
        return support.sha(bytes.array())
    }

    private fun dark(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 600) count++
        }
        return count
    }

    private fun shot(name: String) {
        drawFrame()
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    private fun seedPositions(): SelectAwaitTestSupport.Fixture {
        val original = support.seed()
        val longBody = original.body + "\n" + (1..16).joinToString("\n") {
            "第 " + it + " 段合成说明：收起来源与返回原节点不应等待视觉，原迹与读锁保持。"
        }
        val f = original.copy(body = longBody)
        runBlocking {
            val card = app.study.cards(f.note.id).first().single { it.id == f.card }
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, mapId = f.mapId,
                cardId = f.card, expectedRevision = card.revision,
                title = "LM66 长来源标题：引用位置与原迹不会因为进入淡入而改变作者身份", body = longBody))
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.REUSE, mapId = f.mapId,
                cardId = f.card, nodeId = id(), x = 610.0, y = 720.0))
        }
        compose.waitForIdle()
        return f
    }

    private data class ReviewFixture(val f: SelectAwaitTestSupport.Fixture,
        val questions: List<FrozenBranchReviewQuestion>)

    private fun seedReview(): ReviewFixture {
        val f = support.seed()
        runBlocking {
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, id(), 0,
                KnowledgeData.Question(f.card, "LM66 第一问题：定位前必须先完成哪一步？")))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, id(), 0,
                KnowledgeData.Question(f.secondCard, "LM66 第二问题：旧来源请求应如何取消？")))
        }
        return ReviewFixture(f, runBlocking {
            app.branchReview.load(app.branchReview.prepareNotebook(f.note.id))
        })
    }

    private fun startReview() {
        support.tap("quick-settings"); support.tap("settings-knowledge")
        support.waitFor("knowledge-workspace")
        support.tap("knowledge-tab-4")
        showWholeReviewStart()
        touch("manual-review-start"); support.waitFor("review-question")
    }

    private fun showWholeReviewStart() {
        support.waitFor("notebook-review-panel")
        compose.onNodeWithTag("notebook-review-panel").performScrollToNode(hasTestTag("manual-review-start"))
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("manual-review-start").assertIsEnabled()
        }.isSuccess }
    }

    private fun semanticText(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.children.flatMap(::semanticText)

    private fun semanticTags(node: SemanticsNode): List<String> =
        listOfNotNull(node.config.getOrNull(SemanticsProperties.TestTag)) + node.children.flatMap(::semanticTags)

    @Suppress("DEPRECATION")
    private fun platformText(): List<String> {
        val result = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == app.packageName && node.isVisibleToUser) {
                listOf(node.text, node.contentDescription, node.hintText, node.paneTitle,
                    node.tooltipText, node.stateDescription).filterNotNull().forEach { result += it.toString() }
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let { child ->
                try { walk(child) } finally { child.recycle() }
            } }
        }
        automation.windows.forEach { window -> window.root?.let {
            try { walk(it) } finally { it.recycle() }
        } }
        return result
    }

    private fun hidden(question: FrozenBranchReviewQuestion, all: List<FrozenBranchReviewQuestion>) {
        val prompt = (question.question.data() as KnowledgeData.Question).prompt
        compose.onNodeWithTag("review-question").assertTextEquals(prompt)
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-enter").assertDoesNotExist()
        val semantics = semanticText(compose.onNodeWithTag("manual-review", useUnmergedTree = true).fetchSemanticsNode())
        var platform = emptyList<String>()
        // Compose semantics can appear before the OS accessibility window/cache is updated.
        compose.waitUntil(5_000) {
            platform = platformText()
            platform.any { it.contains(prompt) }
        }
        assertTrue("OS positive control must contain the actual current prompt", platform.any { it.contains(prompt) })
        all.forEach { q ->
            assertFalse("Frozen answer leaked in review semantics", semantics.any { it.contains(q.card.body) })
            assertFalse("Frozen answer leaked in actual OS window", platform.any { it.contains(q.card.body) })
        }
    }

    private fun geometry(source: StudySourceRow, panel: Boolean): CanvasViewport = main {
        val canvas = support.native<InkCanvasView>()
        val density = canvas.resources.displayMetrics.density.toDouble()
        val viewport = canvas.snapshotViewport()
        val bounds = CanvasBounds(source.left, source.top, source.right, source.bottom)
        val expected = CanvasViewport.fit(bounds.padded(60.0), canvas.width / density, canvas.height / density)
            .constrainedToPaper(canvas.width / density, canvas.height / density)
        assertEquals(expected.centerX, viewport.centerX, .5)
        assertEquals(expected.centerY, viewport.centerY, .5)
        assertEquals(expected.zoom, viewport.zoom, .01)
        val a = viewport.worldToScreen(bounds.left, bounds.top, canvas.width.toDouble(), canvas.height.toDouble(), density)
        val b = viewport.worldToScreen(bounds.right, bounds.bottom, canvas.width.toDouble(), canvas.height.toDouble(), density)
        assertTrue("Full real source projection inside native clip",
            a.x >= 0 && a.y >= 0 && b.x <= canvas.width && b.y <= canvas.height)
        assertTrue("Actual saved ink is present, not a blank test surface", canvas.displayedStrokeCount > 0)
        assertFalse(canvas.allowInput)
        viewport
    }.also {
        if (panel) {
            val paper = screenRect("ink-surface")
            val sourceRect = main {
                val canvas = support.native<InkCanvasView>()
                val a = it.worldToScreen(source.left, source.top, canvas.width.toDouble(), canvas.height.toDouble(),
                    canvas.resources.displayMetrics.density.toDouble())
                val b = it.worldToScreen(source.right, source.bottom, canvas.width.toDouble(), canvas.height.toDouble(),
                    canvas.resources.displayMetrics.density.toDouble())
                RectF(paper.left + a.x.toFloat(), paper.top + a.y.toFloat(),
                    paper.left + b.x.toFloat(), paper.top + b.y.toFloat())
            }
            assertFalse("Source must not be obscured by real study panel",
                RectF.intersects(sourceRect, screenRect("study-panel")))
        }
    }

    private fun nativeReady() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!main { support.native<InkCanvasView>().let { it.documentContentReady && !it.rasterPending } }) {
            assertTrue("Real native content must finish", SystemClock.uptimeMillis() < deadline)
            drawFrame()
        }
    }

    private fun jpeg(source: StudySourceRow): ByteArray = main {
        support.native<InkCanvasView>().excerptPreview(CanvasBounds(source.left, source.top, source.right, source.bottom))
    }

    /** Standard Compose policy, resolved against cached 1.10.5 public JVM signatures.
     * Restores the TestRule's factory in withFactory's own finally; no production hook. */
    private fun withActualLifecycleRecomposer(block: () -> Unit) {
        val factoryClass = Class.forName("androidx.compose.ui.platform.WindowRecomposerFactory")
        val companion = factoryClass.getField("Companion").get(null)
        val lifecycle = companion.javaClass.getMethod("getLifecycleAware").invoke(companion)
        val policyClass = Class.forName("androidx.compose.ui.platform.WindowRecomposerPolicy")
        val policy = policyClass.getField("INSTANCE").get(null)
        val method = policyClass.getMethod("withFactory", factoryClass, Class.forName("kotlin.jvm.functions.Function0"))
        try { method.invoke(policy, lifecycle, block) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    /** Read existing view-tree context only: this getter never creates a recomposer. */
    private fun actualScale(): Pair<String, Float> = main {
        val lookup = Class.forName("androidx.compose.ui.platform.WindowRecomposer_androidKt")
            .getMethod("getCompositionContext", View::class.java)
        var view: View = support.native<InkCanvasView>()
        assertTrue("Actual native canvas must be attached to its window", view.isAttachedToWindow)
        var nativeComposition: Any? = null
        // AndroidViewHolder legitimately carries a child CompositionContext. Compose 1.10.5
        // installs the window Recomposer on contentChild, immediately below android.R.id.content.
        while (true) {
            if (nativeComposition == null) nativeComposition = lookup.invoke(null, view)
            val parent = view.parent as? View ?: break
            if (parent.id == android.R.id.content) break
            view = parent
        }
        val native = checkNotNull(nativeComposition) { "Actual native view-tree CompositionContext missing" }
        val window = checkNotNull(lookup.invoke(null, view)) { "Installed window CompositionContext missing" }
        fun scale(composition: Any): MotionDurationScale {
            val context = composition.javaClass.getMethod("getEffectCoroutineContext").invoke(composition) as CoroutineContext
            return checkNotNull(context[MotionDurationScale]) { "Actual context has no MotionDurationScale" }
        }
        val nativeScale = scale(native)
        val windowScale = scale(window)
        assertSame("Native child must inherit the installed window's actual MotionDurationScale", windowScale, nativeScale)
        assertEquals("Native and window effect scales must match", windowScale.scaleFactor, nativeScale.scaleFactor, 0f)
        window.javaClass.name to nativeScale.scaleFactor
    }

    @Test fun cardSectionsEnterOnceAndDisposeCollapsedSourceAtTheNextRealFrame() {
        configure(1440, false)
        val f = seedPositions()
        val source = support.source(f.card)
        val own = support.authorStamp(f.note.id); val other = support.authorStamp(f.unrelated.id)
        support.openBody(f)
        prepareTouch("card-positions")
        compose.mainClock.autoAdvance = false
        touch("card-positions"); tick(3)
        val early = capture("card-reference-content")
        assertEquals("Two real saved occurrences", 2, semanticTags(compose.onNodeWithTag(
            "card-reference-content", useUnmergedTree = true).fetchSemanticsNode())
            .distinct().count { it.startsWith("study-position-") })
        touch("card-positions"); tick()
        compose.onNodeWithTag("card-reference-content").assertDoesNotExist()
        compose.onNodeWithTag("study-position-" + f.node).assertDoesNotExist()
        touch("card-positions"); tick(12)
        val opaque = capture("card-reference-content")
        try {
            assertTrue("Real enter frame must precede the opaque endpoint", dark(early) < dark(opaque))
            assertNotEquals(digest(early), digest(opaque))
        } finally { early.recycle(); opaque.recycle() }
        touch("card-source-section"); tick()
        awaitFrames { compose.onAllNodesWithTag("excerpt-preview-" + f.card).fetchSemanticsNodes().isNotEmpty() }
        val oldPreview = main { views().filterIsInstance<InkCanvasView>().first { it.preview && it.isShown } }
        fun previewField(name: String): Any? = InkCanvasView::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(oldPreview)
        main {
            assertTrue("Release control must contain actual saved ink", oldPreview.displayedStrokeCount > 0)
            assertFalse("Release control must retain ink bounds", (previewField("bounds") as Map<*, *>).isEmpty())
            // View-only object control: the saved source and author fixture stay unchanged.
            oldPreview.showObjects(listOf(PageObject(id(), PageObjectKind.TEXT, text = "LM66 release control")))
            for (name in listOf("objects", "appearanceObjects")) {
                assertFalse("Release control must retain " + name, (previewField(name) as List<*>).isEmpty())
            }
        }
        touch("card-source-section"); tick()
        compose.onNodeWithTag("card-source-content").assertDoesNotExist()
        compose.onNodeWithTag("excerpt-preview-" + f.card).assertDoesNotExist()
        assertFalse("Closed source preview must detach immediately", main { oldPreview.isAttachedToWindow })
        main {
            assertEquals("Released preview must drop its saved ink", 0, oldPreview.displayedStrokeCount)
            for (name in listOf("objects", "appearanceObjects")) {
                assertTrue("Released preview must drop " + name, (previewField(name) as List<*>).isEmpty())
            }
            for (name in listOf("bounds", "meshes")) {
                assertTrue("Released preview must clear its " + name, (previewField(name) as Map<*, *>).isEmpty())
            }
            assertFalse("Released preview must not retain pending raster work", oldPreview.rasterPending)
        }
        support.assertAuthors(f, own, other, source)
        touch("card-source-section"); tick(3)
        touch("card-back"); tick()
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        support.openBody(f, second = true)
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
        compose.onNodeWithTag("excerpt-preview-" + f.card).assertDoesNotExist()
        support.tap("card-back")
        support.openBody(f)
        compose.mainClock.autoAdvance = false
        // Saved sections are already visible when the same card is reopened.
        tick(2)
        compose.onNodeWithTag("card-reference-content").assertExists()
        compose.onNodeWithTag("card-source-content").assertExists()
        compose.mainClock.autoAdvance = true
        shell("wm size 750x1600"); shell("wm density 320"); shell("settings put system font_scale 1.65")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { val c = compose.activity.resources.configuration
            abs(c.screenWidthDp - 375) <= 4 && abs(c.fontScale - 1.65f) < .02f }
        support.waitFor("card-source-content")
        compose.onNodeWithTag("card-source-content").performScrollTo()
        prepareTouch("card-source-section"); prepareTouch("study-open-source"); prepareTouch("card-back")
        compose.onNodeWithTag("card-source-content").performScrollTo()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        // Keep the long-body narrow-window reachability checks above. Use the same card's
        // real source-only entry for first-frame restoration: scrolling after recreation
        // would advance the same clock as the fade and could hide an unwanted restart.
        touch("card-back")
        support.tap("node-more"); support.tap("node-view-source")
        support.waitFor("card-source-content")
        if (compose.onAllNodesWithTag("card-reference-content").fetchSemanticsNodes().isNotEmpty()) {
            touch("card-positions")
        }
        compose.onNodeWithTag("card-source-content").performScrollTo()
        val (captionBeforeRestore, beforeRestore) = captureSourceCaption()
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.recreate()
        awaitFrames { compose.onAllNodesWithTag("card-source-content").fetchSemanticsNodes().isNotEmpty() }
        tick()
        val (captionRestored, restored) = captureSourceCaption()
        try {
            assertEquals("Compare the same real source caption across recreation", captionBeforeRestore, captionRestored)
            assertEquals("Restored caption keeps the same actual crop width", beforeRestore.width, restored.width)
            assertEquals("Restored caption keeps the same actual crop height", beforeRestore.height, restored.height)
            assertTrue("Restored saved source must not restart as transparent",
                dark(restored) >= dark(beforeRestore) * .9)
        } finally { beforeRestore.recycle(); restored.recycle() }
        awaitFrames { compose.onAllNodesWithTag("excerpt-preview-" + f.card).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Successful source capture requires actual nonempty native preview", main {
            views().filterIsInstance<InkCanvasView>().first { it.preview && it.isShown }.displayedStrokeCount > 0
        })
        compose.mainClock.autoAdvance = true
        touch("card-back"); support.openBody(f)
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        support.assertReadOnly(f); support.assertAuthors(f, own, other, source)
        shot("lm66-card-sections.png")
        touch("card-back"); tick()
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
    }

    @Test fun answerEnterCannotRetainPreviousQuestionCluesAcrossSkipHideAndExit() {
        configure(375, true)
        val review = seedReview(); val f = review.f
        assertEquals(2, review.questions.size)
        val own = support.authorStamp(f.note.id); val other = support.authorStamp(f.unrelated.id)
        startReview()
        hidden(review.questions[0], review.questions)
        prepareTouch("reveal-answer")
        compose.mainClock.autoAdvance = false
        touch("reveal-answer"); tick(3)
        compose.onNodeWithTag("review-answer").assertTextEquals(review.questions[0].card.body)
        val early = capture("review-answer-enter")
        touch("branch-review-skip"); tick()
        hidden(review.questions[1], review.questions)
        touch("review-show-hint"); tick()
        touch("recall-context-tab-source")
        awaitFrames { compose.onAllNodesWithTag("recall-context-source-canvas").fetchSemanticsNodes().isNotEmpty() }
        val raw = main { views().filterIsInstance<InkCanvasView>().first {
            it.contentDescription?.toString()?.startsWith("本题当前来源页") == true
        } }
        assertTrue("Positive raw native clue control", main { raw.displayedStrokeCount > 0 })
        touch("review-hide-hint"); tick()
        compose.onNodeWithTag("recall-context-source-canvas").assertDoesNotExist()
        compose.onNodeWithTag("recall-context-source-placeholder").assertExists()
        assertFalse("Old raw native view must detach on hide, without a fade exit", main { raw.isAttachedToWindow })
        main {
            assertEquals(0, raw.displayedStrokeCount)
            assertTrue((InkCanvasView::class.java.getDeclaredField("objects").apply {
                isAccessible = true
            }.get(raw) as List<*>).isEmpty())
            assertNull(InkCanvasView::class.java.getDeclaredField("documentId").apply {
                isAccessible = true
            }.get(raw))
        }
        hidden(review.questions[1], review.questions)
        touch("reveal-answer"); tick(3)
        val secondEarly = capture("review-answer-enter")
        tick(12)
        compose.onNodeWithTag("review-answer").assertTextEquals(review.questions[1].card.body)
        val opaque = capture("review-answer-enter")
        try { assertTrue("Compare the same actual fixed answer at early and opaque frames",
            dark(secondEarly) < dark(opaque)) }
        finally { early.recycle(); secondEarly.recycle(); opaque.recycle() }
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.recreate(); support.waitFor("review-answer")
        compose.onNodeWithTag("review-question").assertTextEquals(
            (review.questions[1].question.data() as KnowledgeData.Question).prompt)
        compose.onNodeWithTag("review-answer").assertTextEquals(review.questions[1].card.body)
        assertTrue("Current saved visible answer is accessible", platformText().any {
            it.contains(review.questions[1].card.body)
        })
        support.assertAuthors(f, own, other)
        shot("lm66-answer.png")
        // Exit removes the old Dialog; the unmasked ancestor may legitimately show its own body.
        touch("branch-review-close")
        compose.onNodeWithTag("manual-review").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-enter").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        // Re-enter through the real existing tab; no replacement private review state.
        showWholeReviewStart()
        touch("manual-review-start"); support.waitFor("review-question")
        hidden(review.questions[0], review.questions)
        compose.mainClock.autoAdvance = false
        touch("reveal-answer"); tick(3); touch("branch-review-close"); tick()
        compose.onNodeWithTag("manual-review").assertDoesNotExist()
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        support.assertAuthors(f, own, other)
    }

    @Test fun latestPassiveSourceHighlightKeepsExactPaperAndNativeCaptureAtSystemZeroScale() {
        configure(1440, false)
        val f = support.seed()
        val excerptCard = id()
        val secondSource = support.source(f.secondCard)
        runBlocking {
            val ink = InkSession(app.inkRepository.read(f.secondPage)).visibleDraft()
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.CREATE_EXCERPT,
                cardId = excerptCard, title = "LM66 独立摘录回源", body = "只用于合成原迹呈现验收",
                source = StudySourceDraft(f.secondPage, 1,
                    CanvasBounds(secondSource.left, secondSource.top, secondSource.right, secondSource.bottom),
                    ink.map { it.id })))
        }
        val source = support.source(f.card)
        val own = support.authorStamp(f.note.id); val other = support.authorStamp(f.unrelated.id)
        support.openBody(f)
        val graph = support.graph(f)
        val originalMap = main { support.native<MindMapView>().snapshotViewport() }
        compose.mainClock.autoAdvance = false
        touch("study-open-source")
        awaitFrames { compose.onAllNodesWithTag("source-focus-highlight").fetchSemanticsNodes().isNotEmpty() }
        // Hold the Compose pulse while actual native raster work completes.
        nativeReady()
        val viewport = geometry(source, panel = true)
        assertEquals(f.sourcePage, main { support.pages(f.note.id).ui.value.selectedId })
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        assertEquals(graph, support.graph(f))
        val withPulse = jpeg(source)
        val visiblePulse = capture("ink-surface")
        shot("lm66-source-highlight.png")
        tick(18)
        compose.onNodeWithTag("source-focus-highlight").assertDoesNotExist()
        assertEquals(viewport, main { support.native<InkCanvasView>().snapshotViewport() })
        assertArrayEquals("Compose sibling decoration must never enter native excerpt JPEG", withPulse, jpeg(source))
        val noPulse = capture("ink-surface")
        try {
            assertNotEquals("Actual native-window paper pixels must contain a real transient pulse",
                digest(visiblePulse), digest(noPulse))
        } finally { visiblePulse.recycle(); noPulse.recycle() }
        touch("study-window-source-return"); tick()
        assertEquals(graph, support.graph(f))
        assertEquals(originalMap, main { support.native<MindMapView>().snapshotViewport() })
        compose.mainClock.autoAdvance = true
        support.openBody(f); compose.mainClock.autoAdvance = false
        touch("study-open-source")
        awaitFrames { compose.onAllNodesWithTag("source-focus-highlight").fetchSemanticsNodes().isNotEmpty() }
        val beforePan = main { support.native<InkCanvasView>().snapshotViewport() }
        compose.onNodeWithTag("ink-surface").performTouchInput {
            val start = Offset(width * .35f, height * .5f); down(start)
            for (step in 1..5) moveTo(start + Offset(72f * step / 5, 0f), 32)
            up()
        }
        tick()
        val afterPan = main { support.native<InkCanvasView>().snapshotViewport() }
        assertTrue("Real purely horizontal touch must reach native paper", abs(afterPan.centerX - beforePan.centerX) > 1)
        compose.onNodeWithTag("source-focus-highlight").assertDoesNotExist()
        touch("study-window-source-return"); tick()
        compose.mainClock.autoAdvance = true
        support.openBody(f, second = true); compose.mainClock.autoAdvance = false
        touch("study-open-source")
        awaitFrames { compose.onAllNodesWithTag("source-focus-highlight").fetchSemanticsNodes().isNotEmpty() }
        nativeReady(); geometry(secondSource, panel = true)
        assertEquals(f.secondPage, main { support.pages(f.note.id).ui.value.selectedId })
        touch("study-window-source-return"); tick()
        // Original FOCUS frame travel is still in progress with the test clock paused.
        // Wait for the real complete 48dp close target, then send an actual touch.
        awaitFrames { completeTargetVisible("study-close") }
        touch("study-close"); tick()
        compose.onNodeWithTag("study-panel").assertDoesNotExist()
        // This real path consumes one-shot focusRegion to null: pulse must survive that effect exit.
        compose.mainClock.autoAdvance = true
        support.tap("read-excerpts"); support.waitFor("excerpt-list")
        compose.onNodeWithTag("excerpt-list").performScrollToNode(hasTestTag("excerpt-item-" + excerptCard))
        support.waitFor("excerpt-preview-" + excerptCard)
        compose.mainClock.autoAdvance = false
        touch("excerpt-preview-" + excerptCard)
        awaitFrames { compose.onAllNodesWithTag("source-focus-highlight").fetchSemanticsNodes().isNotEmpty() }
        tick(2)
        compose.onNodeWithTag("source-focus-highlight").assertExists()
        compose.onNodeWithTag("excerpt-panel").assertDoesNotExist()
        nativeReady(); geometry(secondSource, panel = false)
        tick(18)
        compose.onNodeWithTag("source-focus-highlight").assertDoesNotExist()
        support.assertAuthors(f, own, other, source)

        // Use the real lifecycle factory, not the TestRule's substitute recomposer.
        compose.mainClock.autoAdvance = true
        shell("settings put global animator_duration_scale 0")
        assertEquals("0", shell("settings get global animator_duration_scale").toFloat().toInt().toString())
        withActualLifecycleRecomposer {
            shell("wm size 750x1600"); shell("wm density 320"); shell("settings put system font_scale 1.65")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - 375) <= 4 && abs(c.fontScale - 1.65f) < .02f &&
                    runCatching { actualScale().second == 0f }.getOrDefault(false)
            }
            val observed = actualScale()
            assertEquals("androidx.compose.runtime.Recomposer", observed.first)
            assertEquals(0f, observed.second, 0f)
            assertEquals(0f, Settings.Global.getFloat(app.contentResolver, "animator_duration_scale", -1f), 0f)
            File(app.getExternalFilesDir(null), "lm66-zero-scale-context.json").writeText(
                org.json.JSONObject().put("actualRecomposer", observed.first).put("effectScale", observed.second)
                    .put("settingsScale", Settings.Global.getFloat(app.contentResolver, "animator_duration_scale", -1f))
                    .put("productionHook", false).put("syntheticFixture", true).toString(), Charsets.UTF_8)
            support.tap("read-excerpts")
            compose.onNodeWithTag("excerpt-list").performScrollToNode(hasTestTag("excerpt-item-" + excerptCard))
            support.waitFor("excerpt-preview-" + excerptCard)
            touch("excerpt-preview-" + excerptCard)
            support.assertCurrent(f, f.secondPage); nativeReady(); drawFrame(); drawFrame()
            geometry(secondSource, panel = false)
            compose.onNodeWithTag("source-focus-highlight").assertDoesNotExist()
            support.assertReadOnly(f)
            compose.activityRule.scenario.recreate()
            support.waitFor("ink-surface"); nativeReady(); drawFrame()
            assertEquals(0f, actualScale().second, 0f)
            compose.onNodeWithTag("source-focus-highlight").assertDoesNotExist()
            support.assertAuthors(f, own, other, source)
            shot("lm66-zero-scale-restored.png")
        }
    }
}
