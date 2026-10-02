// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * V63 C-only draft. Mounts the actual MainActivity and navigates through the
 * real saved-collection/card-property routes. No fake review, UI state setter,
 * separate Room connection, hidden semantics click or production test hook.
 */
class CollectionReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val support by lazy { SelectAwaitTestSupport(compose) }
    private val app get() = support.app
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = support.notebook()
    private fun pages(book: String) = support.pages(book)
    private fun lock(book: String) = support.lock(book)
    private fun knowledge(book: String) = provider()["knowledge-" + book, KnowledgeViewModel::class.java]
    private val database: NoteDatabase get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private fun id() = UUID.randomUUID().toString()
    private val savedPreferences = linkedMapOf<String, Map<String, Any?>>()

    @Before fun capturePreferences() {
        support.captureSettings()
        listOf("inkweft-editor", "inkweft-reading", "inkweft-study-window",
            "inkweft-learning", "inkweft-excerpts", "inkweft-pen-widths", "inkweft-selection")
            .forEach(::capturePreference)
        // Fixture isolation only. Preserve original hidden-tool and finger
        // preferences; the unrelated pen case cannot cover a real navigation tap.
        assertTrue(app.getSharedPreferences("inkweft-editor", 0).edit()
            .putBoolean("case-collapsed", true).commit())
        assertTrue(app.getSharedPreferences("inkweft-excerpts", 0).edit()
            .putBoolean("text", false).putBoolean("to-map", false).commit())
    }

    @After fun restorePreferences() {
        try {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        } finally {
            try { support.closeAndRestoreSettings() }
            finally {
                savedPreferences.forEach { (name, values) ->
                    val editor = app.getSharedPreferences(name, 0).edit().clear()
                    values.forEach { (key, value) -> putPreference(editor, key, value) }
                    assertTrue("Restore " + name, editor.commit())
                }
            }
        }
    }

    private fun capturePreference(name: String) {
        if (name !in savedPreferences) savedPreferences[name] = preferences(name)
    }
    private fun preferences(name: String): Map<String, Any?> =
        app.getSharedPreferences(name, 0).all.mapValues { (_, v) -> if (v is Set<*>) v.toSet() else v }
    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> error("Unsupported preference value " + value.javaClass.name)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun atDisplay(size: String, density: Int, width: Int, font: Float, block: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        val oldWidth = compose.activity.resources.configuration.screenWidthDp
        val oldScale = compose.activity.resources.configuration.fontScale
        try {
            shell("wm size $size"); shell("wm density $density"); shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate(); awaitConfiguration(width, font); block()
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
            shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont
                else "settings delete system font_scale")
            compose.activityRule.scenario.recreate(); awaitConfiguration(oldWidth, oldScale)
        }
    }
    private fun awaitConfiguration(width: Int, scale: Float) = compose.waitUntil(15_000) {
        val c = compose.activity.resources.configuration
        abs(c.screenWidthDp - width) <= 4 && abs(c.fontScale - scale) < .02f
    }

    private data class Fixture(
        val note: Note, val foreign: Note, val sourcePage: String,
        val cardA: String, val cardB: String, val cardC: String, val cardD: String, val outside: String,
        val andCollection: String, val orCollection: String, val emptyCollection: String,
        val propertyA: String, val questionPrefix: String, val prompts: List<String>, val answer: String,
        val andTitle: String = "条件概率集合（交集）", val orTitle: String = "标签或状态集合",
        val query: String = "Needle63",
    ) {
        fun questionId(ordinal: Int) = questionPrefix + ordinal.toString().padStart(12, '0')
    }

    private fun seed(longText: Boolean = false): Fixture {
        waitFor("new-note")
        val suffix = id().take(6)
        val note = runBlocking { app.workspaceRepository.create("CR63 本册 " + suffix, false, PaperStyle.BLANK) }
        val foreign = runBlocking { app.workspaceRepository.create("CR63 另一册 " + suffix, false, PaperStyle.BLANK) }
        val prompts = if (longText) listOf(
            "第一题开头必须可见\n" + (1..18).joinToString("\n") { "条件问题 " + it + "：请说明限定样本空间后，分母怎样改变。" },
            "第二题开头必须可见\n" + (1..18).joinToString("\n") { "应用问题 " + it + "：请比较条件概率与独立事件，核对每一步。" })
        else listOf("集合原题一", "集合原题二（本次已理解也保留）")
        val answer = "CR63-ANSWER 原始固定答案" + if (longText)
            "\n" + (1..32).joinToString("\n") { "答案段 " + it + "：先确定条件事件，再核对交集和分母，解释计算顺序。" } else ""
        val f = Fixture(note, foreign, id(), id(), id(), id(), id(), id(), id(), id(), id(),
            id(), id().take(24), prompts, answer)
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(520f, 980f, 0), InkSample(630f, 990f, 50), InkSample(740f, 1010f, 100)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(
                CommitInk(id(), f.sourcePage, 0, InkMutation.Add(stroke))))
            val noQuestionNode = id()
            val specs = listOf(
                Triple(f.cardA, "原文有两题", f.answer), Triple(f.cardB, "Needle63 未设题卡", "无题仍计入匹配卡片"),
                Triple(f.cardC, "只匹配标签", "卡片状态已理解，题目状态待整理"),
                Triple(f.cardD, "只匹配状态", "没有集合标签也可在或条件匹配"),
                Triple(f.outside, "同本默认属性范围外", "未保存属性默认 INBOX 和空标签"))
            specs.forEachIndexed { index, spec ->
                app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = spec.first,
                    nodeId = if (spec.first == f.cardB) noQuestionNode else id(), title = spec.second,
                    body = spec.third, x = 80.0 + index * 240.0, y = 120.0 + index * 160.0,
                    source = if (spec.first == f.cardA) StudySourceDraft(
                        f.sourcePage, 1, stroke.bounds(), listOf(stroke.id)) else null))
            }
            // B is active but has no occurrence and no question. A collection
            // matches card properties, independently of graph membership.
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REMOVE_NODE,
                nodeId = noQuestionNode, expectedRevision = 1))
            listOf(
                f.propertyA to KnowledgeData.Properties(f.cardA, ManualState.REVIEW, listOf("概率")),
                id() to KnowledgeData.Properties(f.cardB, ManualState.REVIEW, listOf("概率")),
                id() to KnowledgeData.Properties(f.cardC, ManualState.UNDERSTOOD, listOf("概率")),
                id() to KnowledgeData.Properties(f.cardD, ManualState.REVIEW, listOf("其他"))).forEach { (key, value) ->
                app.knowledge.submit(KnowledgeCommand(id(), note.id, key, 0, value))
            }
            listOf(
                Triple(f.cardA, f.prompts[0], ManualState.REVIEW),
                Triple(f.cardA, f.prompts[1], ManualState.UNDERSTOOD),
                Triple(f.cardC, "或条件标签题（INBOX）", ManualState.INBOX),
                Triple(f.cardD, "或条件状态题", ManualState.REVIEW),
                Triple(f.outside, "同本范围外问题", ManualState.REVIEW)).forEachIndexed { index, spec ->
                app.knowledge.submit(KnowledgeCommand(id(), note.id, f.questionId(index + 1), 0,
                    KnowledgeData.Question(spec.first, spec.second, spec.third)))
            }
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.andCollection, 0,
                KnowledgeData.Collection(f.andTitle, "概率", ManualState.REVIEW, matchAny = false)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.orCollection, 0,
                KnowledgeData.Collection(f.orTitle, "概率", ManualState.REVIEW, matchAny = true)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.emptyCollection, 0,
                KnowledgeData.Collection("没有匹配卡片", "不存在的标签")))
            val otherCard = id()
            app.study.submit(StudyCommand(id(), foreign.id, StudyAction.CREATE, cardId = otherCard,
                nodeId = id(), title = "原文有两题", body = "FOREIGN-ANSWER 另一册不应进入本轮"))
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, id(), 0,
                KnowledgeData.Properties(otherCard, ManualState.REVIEW, listOf("概率"))))
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, f.questionId(6), 0,
                KnowledgeData.Question(otherCard, "FOREIGN 问题不进入本册集合")))
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, id(), 0,
                KnowledgeData.Collection(f.andTitle, "概率", ManualState.REVIEW)))
            for (book in listOf(note.id, foreign.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            app.pages.select(note.id, note.id)
        }
        capturePreference("inkweft-pens-" + note.id); capturePreference("inkweft-pens-" + foreign.id)
        assertTrue(app.getSharedPreferences("inkweft-reading", 0).edit()
            .putBoolean("continuous-v20-" + note.id, false).commit())
        return f
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { exists(tag) }; compose.waitForIdle()
    }
    private fun tap(tag: String) {
        waitFor(tag)
        val target = compose.onNodeWithTag(tag)
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }
    private fun tapText(text: String) {
        val target = compose.onNodeWithText(text, useUnmergedTree = true)
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }
    private fun list() = compose.onNode(hasScrollToIndexAction() and
        hasAnyAncestor(hasTestTag("knowledge-workspace")))
    private fun showCollectionItem(tag: String) {
        waitFor("knowledge-workspace")
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasScrollToIndexAction() and
                hasAnyAncestor(hasTestTag("knowledge-workspace"))).fetchSemanticsNodes().size == 1
        }
        list().performScrollToNode(hasTestTag(tag))
        val target = compose.onNodeWithTag(tag)
        // The LazyColumn locates the item; a nested collection-chip Row still
        // needs its real horizontal scroll before the physical touch.
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed()
    }
    private fun chooseCollection(key: String) {
        showCollectionItem("collection-filter-" + key)
        tap("collection-filter-" + key)
        showCollectionItem("collection-review-scope")
    }
    private fun search(value: String) {
        showCollectionItem("collection-search")
        compose.onNodeWithTag("collection-search").performTouchInput { click() }
            .performTextReplacement(value)
        hideKeyboard()
    }
    private fun assertSearch(value: String) {
        showCollectionItem("collection-search")
        compose.onNodeWithTag("collection-search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }
    private fun assertCollection(f: Fixture, key: String = f.andCollection, value: String = f.query) {
        compose.onNodeWithTag("knowledge-tab-1").assertIsSelected()
        showCollectionItem("collection-filter-" + key)
        compose.onNodeWithTag("collection-filter-" + key).assertIsSelected()
        assertSearch(value)
    }
    private fun hideKeyboard() {
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window,
            compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
    }

    private fun roots() = WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }
    private fun children(root: View): List<View> = buildList {
        val queue = java.util.ArrayDeque<View>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst(); add(next)
            if (next is ViewGroup) repeat(next.childCount) { queue.add(next.getChildAt(it)) }
        }
    }
    private fun permittedViews(): List<View> {
        val allowed = roots().filter { it.isShown &&
            it.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val focused = allowed.filter { it.hasWindowFocus() }
        return (focused.ifEmpty { allowed }).flatMap(::children).filter { it.isShown }
    }
    private inline fun <reified T : View> native(): T = permittedViews().filterIsInstance<T>().single()
    private fun documentId(view: InkCanvasView) = InkCanvasView::class.java.getDeclaredField("documentId")
        .apply { isAccessible = true }.get(view) as String?
    private fun openPaper(f: Fixture) {
        compose.runOnIdle { notebook().select(f.note) }
        compose.singlePageEditor(); compose.waitForSavedInk()
        compose.waitUntil(15_000) { compose.runOnIdle {
            notebook().ui.value.selectedId == f.note.id && pages(f.note.id).ui.value.selectedId == f.note.id &&
                !provider()["ink-" + f.note.id, InkViewModel::class.java].ui.value.loading &&
                native<InkCanvasView>().documentContentReady && !native<InkCanvasView>().rasterPending
        } }
    }
    private fun enterReadOnly(f: Fixture) {
        tap("quick-settings"); waitFor("settings-readonly")
        compose.onNodeWithTag("settings-readonly").performScrollTo().assertIsDisplayed()
            .assertIsOff().assertIsEnabled().performTouchInput { click() }
        waitFor("reading-toolbar")
        compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<InkCanvasView>().allowInput) }
    }
    private fun openBookCollection(f: Fixture, collection: String = f.andCollection, readOnly: Boolean = true) {
        openPaper(f)
        if (readOnly) enterReadOnly(f)
        tap("quick-settings"); tap("settings-knowledge"); waitFor("knowledge-workspace")
        awaitCollectionRow(f, collection)
        tap("knowledge-tab-1"); chooseCollection(collection)
    }
    private fun awaitCollectionRow(f: Fixture, key: String, removed: Boolean = false) {
        compose.waitUntil(15_000) { compose.runOnIdle {
            knowledge(f.note.id).ui.value.rows.any {
                it.id == key && it.notebookId == f.note.id && it.removed == removed &&
                    it.data() is KnowledgeData.Collection
            }
        } }
        compose.waitForIdle()
    }
    private fun openLibraryCollection(f: Fixture) {
        // A genuine existing prefs fixture establishes the shortcut. The test
        // then physically opens the Library's saved COLLECTION route.
        // The earlier host-configuration regression deliberately hides widgets.
        // Use a real visible shortcut fixture; @After restores its exact original prefs.
        val configuration = app.learningStore.read()
        assertFalse(configuration.error)
        assertTrue(configuration.widgets.any { it.definition == "org.inkweft/shortcuts" })
        app.learningStore.configure(configuration.widgets.map {
            if (it.definition == "org.inkweft/shortcuts") it.copy(visible = true) else it
        })
        app.learningStore.shortcut(StableTargetRef(LearningTargetKind.COLLECTION,
            f.note.id, f.andCollection), true)
        if (exists("open-library-drawer")) tap("open-library-drawer")
        tapText("学习"); waitFor("learning-workbench")
        compose.onNodeWithTag("widget-shortcuts").performScrollTo()
        val target = hasTestTag("learning-target-" + f.andCollection) and
            hasAnyAncestor(hasTestTag("widget-shortcuts"))
        compose.onNode(target).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        waitFor("knowledge-workspace"); awaitCollectionRow(f, f.andCollection)
        showCollectionItem("collection-review-scope")
        compose.runOnIdle { assertNull("Library collection does not open paper", notebook().ui.value.selectedId) }
    }

    private fun preview(cards: Int, questions: Int, without: Int) {
        showCollectionItem("collection-review-start"); tap("collection-review-start")
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts").assertTextEquals(
            "$cards 张卡片 · $questions 道问题 · $without 张未设题卡片")
    }
    private fun assertHidden() {
        val own = hasAnyAncestor(hasTestTag("manual-review"))
        compose.onAllNodes(hasTestTag("review-answer") and own).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("review-card-title") and own).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("review-open-source") and own).assertCountEquals(0)
        compose.onAllNodes(hasText("CR63-ANSWER", substring = true) and own,
            useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue("Hidden source/map windows must not render in the permitted review window",
                permittedViews().none { it is InkCanvasView || it is MindMapView })
        }
    }
    private fun question(prompt: String, position: Int, total: Int) {
        waitFor("review-question")
        compose.onNodeWithTag("review-question").assertTextEquals(prompt)
        compose.onNodeWithText("手动回忆 · $position / $total", useUnmergedTree = true).assertIsDisplayed()
    }
    private fun closeReview() {
        tap("branch-review-close")
        compose.waitUntil(15_000) { !exists("manual-review") }
        waitFor("knowledge-workspace")
    }
    private fun assertNoRound() {
        compose.onNodeWithTag("manual-review").assertDoesNotExist()
        compose.onNodeWithTag("branch-review-counts").assertDoesNotExist()
        compose.onNodeWithTag("review-question").assertDoesNotExist()
    }

    private fun authors(f: Fixture) = support.authorStamp(f.note.id) to support.authorStamp(f.foreign.id)
    private fun assertAuthors(f: Fixture, expected: Pair<List<String>, List<String>>) {
        assertEquals(expected.first, support.authorStamp(f.note.id))
        assertEquals(expected.second, support.authorStamp(f.foreign.id))
    }
    private fun source(f: Fixture) = support.source(f.cardA)
    private fun row(key: String): KnowledgeRow = runBlocking { checkNotNull(database.knowledge().get(key)) }
    private fun sourceHash(f: Fixture) = support.sha(source(f).snapshot)
    private fun fingerprintSections(stamp: List<String>): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var key: String? = null
        for (line in stamp) {
            if (line.startsWith("SELECT ")) { key = line; result[line] = mutableListOf() }
            else checkNotNull(result[checkNotNull(key)]).add(line)
        }
        assertEquals("Existing shared fingerprint queries", 27, result.size)
        return result
    }
    private fun records(book: String): Map<String, String> = runBlocking {
        database.knowledge().forBook(book).associate { it.id to
            (it.revision.toString() + "|" + it.removed + "|" + support.sha(it.payload)) }
    }
    private fun count(table: String, book: String): Long = runBlocking {
        database.withTransaction {
            database.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("SELECT COUNT(*) FROM " + table + " WHERE notebookId=?", arrayOf(book)))
                .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
        }
    }
    private fun assertOneRating(f: Fixture, before: Pair<List<String>, List<String>>,
        beforeRecords: Map<String, String>, revisions: Long, receipts: Long, key: String) {
        val after = authors(f)
        assertEquals(before.second, after.second)
        val oldSections = fingerprintSections(before.first)
        val newSections = fingerprintSections(after.first)
        val allowed = setOf("SELECT * FROM notes WHERE id=?",
            "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
            "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
            "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId")
        oldSections.filterKeys { it !in allowed }.forEach { (sql, values) ->
            assertEquals("Explicit rating changed unrelated author section: " + sql, values, newSections[sql])
        }
        val afterRecords = records(f.note.id)
        assertEquals(beforeRecords - key, afterRecords - key)
        assertEquals(2L, row(key).revision)
        assertEquals(ManualState.UNDERSTOOD, (row(key).data() as KnowledgeData.Question).state)
        assertEquals(revisions + 1, count("knowledge_revisions", f.note.id))
        assertEquals(receipts + 1, count("knowledge_receipts", f.note.id))
        runBlocking { assertEquals(f.note, app.repository.read(f.note.id)) }
    }

    private fun fence() {
        runBlocking { withTimeout(15_000) {
            database.withTransaction { database.knowledge().all() }
        } }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Read-only queue observation on the actual Room-owned transaction executor.
     * It is sampled at zero while the gate is already held, then nonzero after
     * this UI action. It complements, rather than replaces, the pending UI and
     * post-release identity/no-late-round assertions. No queued task is mutated.
     */
    private fun transactionQueueDepth(): Int {
        val executor: Executor = database.transactionExecutor
        val fields = generateSequence(executor.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.toList()
        val field = fields.singleOrNull { java.util.Collection::class.java.isAssignableFrom(it.type) }
            ?: error("Cannot observe actual Room transaction executor queue: " +
                executor.javaClass.name + " fields=" + fields.map { it.name + ":" + it.type.name })
        return synchronized(executor) {
            field.isAccessible = true
            (field.get(executor) as Collection<*>).size
        }
    }
    private fun beginPreparationGate(label: String): SelectAwaitTestSupport.WriterGate {
        showCollectionItem("collection-review-start"); fence()
        val gate = support.WriterGate("CR63 " + label)
        try {
            gate.awaitHeld()
            compose.waitUntil(15_000) { transactionQueueDepth() == 0 }
            tap("collection-review-start")
            compose.onNodeWithTag("collection-review-start").assertTextEquals("正在读取…").assertIsNotEnabled()
            compose.waitUntil(15_000) { transactionQueueDepth() > 0 }
            assertNoRound()
            android.util.Log.i("InkWeft-CR63", label + ": same-app Room queue became nonempty after actual collection touch")
            return gate
        } catch (failure: Throwable) {
            gate.finish()
            throw failure
        }
    }

    private fun target48(tag: String, container: String) {
        val target = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val outer = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        val d = compose.activity.resources.displayMetrics.density
        assertTrue(tag + " actual touch width: " + target, target.width >= 48f * d - 1)
        assertTrue(tag + " actual touch height: " + target, target.height >= 48f * d - 1)
        assertTrue(tag + " is fully reachable inside actual panel: " + target + " / " + outer,
            target.left >= outer.left - 1 && target.top >= outer.top - 1 &&
                target.right <= outer.right + 1 && target.bottom <= outer.bottom + 1)
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val root = compose.runOnIdle { roots().first { it.hasWindowFocus() && it.isShown } }
        val latch = CountDownLatch(1)
        val draw = android.view.ViewTreeObserver.OnDrawListener { latch.countDown() }
        compose.runOnIdle { root.viewTreeObserver.addOnDrawListener(draw); root.invalidate() }
        try { assertTrue("Native screenshot follows actual draw", latch.await(5, TimeUnit.SECONDS)) }
        finally { compose.runOnIdle { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(draw) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun librarySavedCollectionKeepsAndScopeAndAllQuestionsDespiteCardSearch() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed()
            val before = authors(f); val snapshot = sourceHash(f)
            openLibraryCollection(f)
            chooseCollection(f.andCollection); search(f.query)
            showCollectionItem("collection-review-scope")
            compose.onNodeWithTag("collection-review-scope")
                .assertTextContains("搜索词只查卡片，不改变集合复习范围。", substring = true)
            compose.onNodeWithText("1 张卡片 · 筛选不复制内容", useUnmergedTree = true).assertExists()
            preview(2, 2, 1)
            assertHidden(); assertAuthors(f, before)
            shot("cr63-library-summary.png")
            tap("branch-review-start"); question(f.prompts[0], 1, 2); assertHidden()
            tap("branch-review-skip"); question(f.prompts[1], 2, 2); assertHidden()
            tap("branch-review-skip"); waitFor("branch-review-ended")
            assertAuthors(f, before); assertEquals(snapshot, sourceHash(f))
            closeReview(); assertCollection(f)
            compose.runOnIdle { assertNull(notebook().ui.value.selectedId) }
            assertAuthors(f, before)
        }

    @Test fun orCollectionUsesCardPropertiesAndReadOnlyRatingKeepsEveryQuestionState() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); openBookCollection(f, f.orCollection)
            search(f.query)
            val before = authors(f); val snapshot = sourceHash(f)
            val beforeRecords = records(f.note.id)
            val revisions = count("knowledge_revisions", f.note.id)
            val receipts = count("knowledge_receipts", f.note.id)
            preview(4, 4, 1); assertHidden(); assertAuthors(f, before)
            tap("branch-review-start"); question(f.prompts[0], 1, 4); assertHidden()
            tap("reveal-answer")
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("branch-review-mark-UNDERSTOOD"); question(f.prompts[1], 2, 4); assertHidden()
            assertOneRating(f, before, beforeRecords, revisions, receipts, f.questionId(1))
            val afterMark = authors(f)
            tap("branch-review-skip"); question("或条件标签题（INBOX）", 3, 4); assertHidden()
            tap("branch-review-skip"); question("或条件状态题", 4, 4); assertHidden()
            tap("branch-review-skip"); waitFor("branch-review-ended")
            closeReview(); assertCollection(f, f.orCollection)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
                assertEquals(f.note.id, notebook().ui.value.selectedId)
                assertEquals(f.note.id, pages(f.note.id).ui.value.selectedId) }
            assertAuthors(f, afterMark); assertEquals(snapshot, sourceHash(f))
        }

    @Test fun fixedCollectionQuestionAnswerAndPermissionsSurviveEditsSourceReturnAndRecreation() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); openBookCollection(f); search(f.query)
            preview(2, 2, 1); tap("branch-review-start"); question(f.prompts[0], 1, 2); assertHidden()
            val originalSnapshot = sourceHash(f)
            runBlocking {
                app.study.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.cardA,
                    expectedRevision = 1, title = "后续新卡标题", body = "后续新答案不替换固定版本"))
                val oldQuestion = checkNotNull(database.knowledge().get(f.questionId(1)))
                app.knowledge.submit(KnowledgeCommand(id(), f.note.id, oldQuestion.id, oldQuestion.revision,
                    (oldQuestion.data() as KnowledgeData.Question).copy(prompt = "后续新题不替换固定题")))
                val oldProperty = checkNotNull(database.knowledge().get(f.propertyA))
                app.knowledge.submit(KnowledgeCommand(id(), f.note.id, oldProperty.id, oldProperty.revision,
                    (oldProperty.data() as KnowledgeData.Properties).copy(state = ManualState.UNDERSTOOD)))
            }
            val afterFixtureEdit = authors(f)
            compose.activityRule.scenario.recreate()
            question(f.prompts[0], 1, 2); assertHidden()
            tap("reveal-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("review-open-source"); waitFor("review-source-canvas")
            compose.waitUntil(15_000) { compose.runOnIdle { native<InkCanvasView>().let {
                documentId(it) == f.sourcePage && it.documentContentReady && !it.rasterPending &&
                    it.displayedStrokeCount == 1 && !it.allowInput && !it.fingerWrites
            } } }
            compose.onNodeWithText(f.note.title + " · 第 2 页", useUnmergedTree = true).assertIsDisplayed()
            val sourceView = compose.runOnIdle { native<InkCanvasView>() }
            assertAuthors(f, afterFixtureEdit); assertEquals(originalSnapshot, sourceHash(f))
            shot("cr63-frozen-source.png")
            compose.activityRule.scenario.recreate(); waitFor("review-source-canvas")
            compose.waitUntil(15_000) { compose.runOnIdle { native<InkCanvasView>().let {
                documentId(it) == f.sourcePage && it.documentContentReady && !it.rasterPending && !it.allowInput
            } } }
            tap("return-to-review"); question(f.prompts[0], 1, 2)
            compose.runOnIdle { assertFalse(sourceView.isAttachedToWindow) }
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("branch-review-mark-UNDERSTOOD"); waitFor("branch-review-conflict")
            question(f.prompts[0], 1, 2)
            compose.onNodeWithTag("branch-review-mark-UNDERSTOOD").assertIsNotEnabled()
            assertAuthors(f, afterFixtureEdit)
            tap("branch-review-skip"); question(f.prompts[1], 2, 2); assertHidden()
            closeReview(); assertCollection(f)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value)
                assertEquals(f.note.id, pages(f.note.id).ui.value.selectedId) }
            preview(1, 0, 1)
            compose.onNodeWithTag("branch-review-start").assertIsNotEnabled()
            assertAuthors(f, afterFixtureEdit); assertEquals(originalSnapshot, sourceHash(f))
            closeReview(); assertCollection(f)
        }

    @Test fun pendingPreparationCancellationStaleRevisionAndRemovedCollectionNeverFallBack() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); openBookCollection(f); search(f.query)
            val original = row(f.andCollection)
            val staleGate = beginPreparationGate("stale-revision")
            try { assertNoRound() }
            finally {
                staleGate.finish {
                    app.knowledge.submit(KnowledgeCommand(id(), f.note.id, original.id, original.revision,
                        (original.data() as KnowledgeData.Collection).copy(tag = "没有匹配的新标签")))
                }
            }
            fence(); assertNoRound(); assertCollection(f)
            val staleBaseline = authors(f)
            preview(0, 0, 0)
            compose.onNodeWithTag("branch-review-start").assertIsNotEnabled()
            closeReview(); assertAuthors(f, staleBaseline)
            runBlocking {
                val live = checkNotNull(database.knowledge().get(f.andCollection))
                app.knowledge.submit(KnowledgeCommand(id(), f.note.id, live.id, live.revision,
                    (live.data() as KnowledgeData.Collection).copy(tag = "概率")))
            }
            compose.waitUntil(15_000) { compose.runOnIdle {
                knowledge(f.note.id).ui.value.rows.any { it.id == f.andCollection && it.revision == 3L }
            } }
            val beforeCancel = authors(f)
            val tabGate = beginPreparationGate("switch-tab")
            try { tap("knowledge-tab-0"); compose.onNodeWithTag("knowledge-tab-0").assertIsSelected() }
            finally { tabGate.finish() }
            fence(); assertNoRound(); assertAuthors(f, beforeCancel)
            tap("knowledge-tab-1"); assertCollection(f)
            val recreateGate = beginPreparationGate("recreate")
            try { compose.activityRule.scenario.recreate(); assertNoRound() }
            finally { recreateGate.finish() }
            fence(); assertCollection(f)
            showCollectionItem("collection-review-start")
            compose.onNodeWithTag("collection-review-start").assertIsEnabled()
            assertNoRound(); assertAuthors(f, beforeCancel)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value) }

            runBlocking {
                val live = checkNotNull(database.knowledge().get(f.andCollection))
                app.knowledge.submit(KnowledgeCommand(id(), f.note.id, live.id, live.revision, live.data(), true))
            }
            val removedBaseline = authors(f)
            awaitCollectionRow(f, f.andCollection, removed = true)
            showCollectionItem("collection-review-unavailable")
            compose.onNodeWithTag("collection-review-unavailable")
                .assertTextContains("未改为全部摘要", substring = true)
            compose.onNodeWithTag("collection-review-start").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            showCollectionItem("collection-review-unavailable"); assertSearch(f.query)
            assertNoRound(); assertAuthors(f, removedBaseline)
            showCollectionItem("collection-filter-all"); tap("collection-filter-all")
            compose.onNodeWithTag("collection-review-start").assertDoesNotExist()
            compose.onNodeWithTag("collection-review-unavailable").assertDoesNotExist()
            assertSearch(f.query)
            compose.onNodeWithText("1 张卡片 · 筛选不复制内容", useUnmergedTree = true).assertExists()
            assertAuthors(f, removedBaseline)
        }

    @Test fun narrow375LargeTextKeepsCollectionAndFixedRoundReachableAfterRecreation() =
        atDisplay("750x1600", 320, 375, 1.65f) {
            val f = seed(longText = true); openBookCollection(f); search(f.query)
            val before = authors(f); val snapshot = sourceHash(f)
            showCollectionItem("collection-filter-" + f.andCollection)
            target48("collection-filter-" + f.andCollection, "knowledge-workspace")
            showCollectionItem("collection-review-start")
            target48("collection-review-start", "knowledge-workspace")
            preview(2, 2, 1)
            runCatching { compose.onNodeWithTag("branch-review-start").performScrollTo() }
            target48("branch-review-start", "manual-review")
            tap("branch-review-start"); question(f.prompts[0], 1, 2); assertHidden()
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            question(f.prompts[0], 1, 2); assertHidden()
            runCatching { compose.onNodeWithTag("reveal-answer").performScrollTo() }
            target48("reveal-answer", "manual-review")
            tap("reveal-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            question(f.prompts[0], 1, 2)
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("branch-review-skip"); question(f.prompts[1], 2, 2); assertHidden()
            val questionBounds = compose.onNodeWithTag("review-question").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("manual-review").fetchSemanticsNode().boundsInRoot
            assertTrue("The next question starts at its visible top", questionBounds.top >= panel.top &&
                questionBounds.top < panel.top + panel.height / 2)
            assertAuthors(f, before); assertEquals(snapshot, sourceHash(f))
            shot("cr63-narrow-recreated.png")
            closeReview(); assertCollection(f)
            showCollectionItem("collection-review-start")
            target48("collection-review-start", "knowledge-workspace")
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            assertCollection(f)
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value)
                assertFalse(lock(f.note.id).canWrite); assertEquals(f.note.id, pages(f.note.id).ui.value.selectedId) }
            assertAuthors(f, before); assertEquals(snapshot, sourceHash(f))
        }
}
