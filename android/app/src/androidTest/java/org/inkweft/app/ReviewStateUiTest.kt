// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.WindowCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Real MainActivity, author commands and application Room. All data is synthetic.
 * Uses the existing transaction-fault boundary only for the unknown-receipt case.
 * No isolated Compose content, fake UI state, second connection or hidden click.
 * C-only candidate: compilation, execution and native screenshots are root-owned.
 */
class ReviewStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val support by lazy { SelectAwaitTestSupport(compose) }
    private val app get() = support.app
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = support.notebook()
    private fun pages(book: String) = support.pages(book)
    private fun lock(book: String) = support.lock(book)
    private fun study(book: String) = support.study(book)
    private val database: NoteDatabase get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private val preferences = linkedMapOf<String, Map<String, Any?>>()
    private fun id() = UUID.randomUUID().toString()

    @Before fun capturePreferences() {
        support.captureSettings()
        listOf("inkweft-editor", "inkweft-reading", "inkweft-study-window",
            "inkweft-learning", "inkweft-excerpts", "inkweft-pen-widths", "inkweft-selection")
            .forEach(::capturePreference)
        assertTrue(app.getSharedPreferences("inkweft-editor", 0).edit()
            .putBoolean("case-collapsed", true).commit())
        assertTrue(app.getSharedPreferences("inkweft-excerpts", 0).edit()
            .putBoolean("text", false).putBoolean("to-map", false).commit())
    }

    @After fun restorePreferences() {
        try { hideKeyboard(); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
        finally {
            try { support.closeAndRestoreSettings() }
            finally {
                preferences.forEach { (name, values) ->
                    val editor = app.getSharedPreferences(name, 0).edit().clear()
                    values.forEach { (key, value) -> putPreference(editor, key, value) }
                    assertTrue("Restore " + name, editor.commit())
                }
            }
        }
    }

    private fun capturePreference(name: String) {
        if (name !in preferences) preferences[name] = app.getSharedPreferences(name, 0).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
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
            else -> error("Unsupported preference " + value.javaClass.name)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun atDisplay(size: String, density: Int, width: Int, font: Float, block: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        val oldWidth = compose.activity.resources.configuration.screenWidthDp
        val oldScale = compose.activity.resources.configuration.fontScale
        try {
            shell("wm size " + size); shell("wm density " + density); shell("settings put system font_scale " + font)
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
        val note: Note, val foreign: Note, val sourcePage: String, val map: String, val otherMap: String,
        val branch: String, val cardA: String, val cardB: String, val cardC: String, val cardD: String,
        val cardE: String, val outside: String, val nodeA: String, val nodeB: String, val nodeC: String,
        val collection: String, val questionPrefix: String, val prompts: List<String>, val answer: String,
        val collectionTitle: String = "题目状态独立于卡片属性的集合",
        val query: String = "Needle65",
    ) {
        fun questionId(ordinal: Int) = questionPrefix + ordinal.toString().padStart(12, '0')
    }

    private fun seed(longText: Boolean = false): Fixture {
        waitFor("new-note")
        val suffix = id().take(6)
        val note = runBlocking { app.workspaceRepository.create("RS65 本册 " + suffix, false, PaperStyle.BLANK) }
        val foreign = runBlocking { app.workspaceRepository.create("RS65 另一册 " + suffix, false, PaperStyle.BLANK) }
        val prompts = listOf(
            "原题一 REVIEW" + if (longText) "\n" + (1..17).joinToString("\n") {
                "条件问题 " + it + "：限定样本空间以后，请说明分母、交集与计算顺序。" } else "",
            "同卡原题二 UNDERSTOOD", "其他卡原题三 UNDERSTOOD",
            "其他卡原题四 INBOX", "未放入图的默认待复习题", "同本其他图的范围外题")
        val answer = "RS65-ANSWER 固定原答案" + if (longText) "\n" + (1..30).joinToString("\n") {
            "答案段 " + it + "：先限定条件事件，再核对交集与分母，逐步解释计算过程。" } else ""
        val f = Fixture(note, foreign, id(), id(), id(), id(), id(), id(), id(), id(), id(), id(),
            id(), id(), id(), id(), id().take(24), prompts, answer)
        runBlocking {
            app.pages.addAfter(note.id, note.id, f.sourcePage)
            val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 4f, InkTool.STYLUS,
                listOf(InkSample(520f, 980f, 0), InkSample(630f, 990f, 50), InkSample(740f, 1010f, 100)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(
                CommitInk(id(), f.sourcePage, 0, InkMutation.Add(stroke))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.map, 0,
                KnowledgeData.MapDefinition("RS65 目标图", structures = listOf(
                    MapStructure(f.branch, null, "RS65-BRANCH 答案标题线索", 40.0, 80.0)))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.otherMap, 0,
                KnowledgeData.MapDefinition("RS65 其他图")))
            listOf(
                Triple(f.cardA, f.nodeA, "同卡有 REVIEW 与 UNDERSTOOD"),
                Triple(f.cardB, f.nodeB, "Needle65 真未设题卡"),
                Triple(f.cardC, f.nodeC, "只有 UNDERSTOOD 的卡")).forEachIndexed { index, spec ->
                app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = spec.first,
                    nodeId = spec.second, parentId = f.branch, title = spec.third,
                    body = if (spec.first == f.cardA) f.answer else "独立卡片的原正文",
                    x = 300.0, y = 80.0 + index * 160.0, mapId = f.map,
                    source = if (spec.first == f.cardA) StudySourceDraft(
                        f.sourcePage, 1, stroke.bounds(), listOf(stroke.id)) else null))
            }
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = f.cardA,
                nodeId = id(), parentId = f.branch, x = 570.0, y = 80.0, mapId = f.map))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.cardD,
                nodeId = id(), title = "只有 INBOX 的卡", body = "待整理问题不能假装未设题",
                x = 300.0, y = 720.0, mapId = f.map))
            val unplacedNode = id()
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.cardE,
                nodeId = unplacedNode, title = "未放入图的默认题卡", body = "不在图中也属于本册与保存集合",
                mapId = f.otherMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REMOVE_NODE,
                nodeId = unplacedNode, expectedRevision = 1, mapId = f.otherMap))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = f.outside,
                nodeId = id(), title = "同本其他图卡", body = "图和集合的范围外", mapId = f.otherMap))
            listOf(
                f.cardA to ManualState.UNDERSTOOD, f.cardB to ManualState.REVIEW,
                f.cardC to ManualState.REVIEW, f.cardD to ManualState.REVIEW,
                f.cardE to ManualState.INBOX).forEach { (card, state) ->
                app.knowledge.submit(KnowledgeCommand(id(), note.id, id(), 0,
                    KnowledgeData.Properties(card, state, listOf("概率"))))
            }
            listOf(
                f.cardA to ManualState.REVIEW, f.cardA to ManualState.UNDERSTOOD,
                f.cardC to ManualState.UNDERSTOOD, f.cardD to ManualState.INBOX,
                f.cardE to ManualState.REVIEW, f.outside to ManualState.REVIEW).forEachIndexed { index, spec ->
                val q = if (index == 4) KnowledgeData.Question(spec.first, f.prompts[index])
                    else KnowledgeData.Question(spec.first, f.prompts[index], spec.second)
                app.knowledge.submit(KnowledgeCommand(id(), note.id, f.questionId(index + 1), 0, q))
            }
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.collection, 0,
                KnowledgeData.Collection(f.collectionTitle, "概率")))
            val foreignCard = id()
            app.study.submit(StudyCommand(id(), foreign.id, StudyAction.CREATE, cardId = foreignCard,
                nodeId = id(), title = "同名题卡", body = "FOREIGN-RS65-ANSWER"))
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, id(), 0,
                KnowledgeData.Properties(foreignCard, ManualState.REVIEW, listOf("概率"))))
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, f.questionId(7), 0,
                KnowledgeData.Question(foreignCard, "FOREIGN-RS65 问题")))
            for (book in listOf(note.id, foreign.id)) for (page in app.pages.activePages(book))
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            app.pages.select(note.id, note.id)
            assertEquals(6, database.study().cards(note.id).count { it.trashedAt == null })
            assertEquals(ManualState.REVIEW, (checkNotNull(database.knowledge()
                .get(f.questionId(5))).data() as KnowledgeData.Question).state)
        }
        capturePreference("inkweft-pens-" + note.id); capturePreference("inkweft-pens-" + foreign.id)
        assertTrue(app.getSharedPreferences("inkweft-reading", 0).edit()
            .putBoolean("continuous-v20-" + note.id, false).commit())
        assertTrue(app.getSharedPreferences("inkweft-study-window", 0).edit()
            .putString(note.id + "-mode", "FOCUS").commit())
        return f
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { exists(tag) }; compose.waitForIdle() }
    private fun tap(tag: String) {
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
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
    private fun hideKeyboard() {
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window,
            compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
    }
    private fun showKnowledgeItem(tag: String) {
        waitFor("knowledge-workspace")
        compose.waitUntil(15_000) { compose.onAllNodes(hasScrollToIndexAction() and
            hasAnyAncestor(hasTestTag("knowledge-workspace"))).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("knowledge-workspace")))
            .performScrollToNode(hasTestTag(tag))
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun chooseCollection(f: Fixture) {
        tap("knowledge-tab-1")
        showKnowledgeItem("collection-filter-" + f.collection); tap("collection-filter-" + f.collection)
        showKnowledgeItem("collection-review-scope")
    }
    private fun search(f: Fixture) {
        showKnowledgeItem("collection-search")
        val field = compose.onNodeWithTag("collection-search")
        field.assertIsDisplayed().performTouchInput { click() }
        field.performTextInput(f.query)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(f.query)))
        hideKeyboard()
    }
    private fun assertSearch(f: Fixture) {
        showKnowledgeItem("collection-search")
        compose.onNodeWithTag("collection-search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(f.query)))
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
                native<InkCanvasView>().documentContentReady && !native<InkCanvasView>().rasterPending
        } }
        tap("quick-settings"); waitFor("settings-readonly")
        compose.onNodeWithTag("settings-readonly").performScrollTo().assertIsDisplayed()
            .assertIsOff().assertIsEnabled().performTouchInput { click() }
        waitFor("reading-toolbar")
        compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
            assertFalse(native<InkCanvasView>().allowInput) }
    }
    private fun openKnowledge() {
        tap("quick-settings"); tap("settings-knowledge"); waitFor("knowledge-workspace")
        val book = compose.runOnIdle { checkNotNull(notebook().ui.value.selectedId) }
        compose.waitUntil(15_000) { compose.runOnIdle {
            val vm = provider()["knowledge-" + book, KnowledgeViewModel::class.java]
            vm.ui.value.rows.any { it.notebookId == book && !it.removed && it.data() is KnowledgeData.Question } &&
                vm.ui.value.cards.any { it.notebookId == book && it.trashedAt == null }
        } }
    }
    private fun closeKnowledge() {
        tapText("返回笔记"); compose.waitUntil(15_000) { !exists("knowledge-workspace") }
        waitFor("reading-toolbar")
    }
    private fun openMap(f: Fixture, mapId: String = f.map) {
        tap("quick-study"); waitFor("study-map-picker")
        tap("study-map-picker"); tap("study-map-" + mapId)
        if (!exists("study-map")) {
            tap("study-management"); tap("map-menu-group-0"); tap("study-tab-2")
        }
        waitMap(f, mapId)
    }
    private fun waitMap(f: Fixture, mapId: String = f.map) {
        waitFor("study-map")
        compose.waitUntil(15_000) { compose.runOnIdle {
            study(f.note.id).mapId.value == mapId && !study(f.note.id).ui.value.loading &&
                runCatching { native<MindMapView>().width > 0 }.getOrDefault(false)
        } }
    }
    private fun selectNode(node: String) {
        compose.waitUntil(15_000) { compose.runOnIdle {
            runCatching { native<MindMapView>().nodeBounds(node) != null }.getOrDefault(false)
        } }
        val point = compose.runOnIdle {
            val map = native<MindMapView>(); assertTrue(map.focusNode(node))
            val r = checkNotNull(map.nodeBounds(node)); Offset(r.centerX(), r.centerY())
        }
        compose.onNodeWithTag("study-map").performTouchInput {
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1); click(point)
        }
        waitFor("node-actions")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(node, native<MindMapView>().selectedNodeId) }
    }
    private fun mapMenu() {
        tap("study-management"); tap("map-menu-group-1"); waitFor("study-review-map")
    }
    private fun branchMenu(f: Fixture) {
        selectNode(f.branch); tap("node-more"); waitFor("node-review-branch")
    }

    private enum class Entry(val prefix: String, val start: String) {
        WHOLE("notebook", "manual-review-start"), COLLECTION("collection", "collection-review-start"),
        MAP("map", "study-review-map"), BRANCH("branch", "node-review-branch")
    }
    private fun scopeTag(entry: Entry, mode: ReviewQuestionScope) =
        entry.prefix + "-review-scope-" + if (mode == ReviewQuestionScope.ALL) "all" else "review"
    private fun revealScope(entry: Entry, mode: ReviewQuestionScope) {
        if (entry == Entry.COLLECTION || entry == Entry.WHOLE) showKnowledgeItem(scopeTag(entry, mode))
        else { waitFor(scopeTag(entry, mode)); runCatching { compose.onNodeWithTag(scopeTag(entry, mode)).performScrollTo() } }
    }
    private fun chooseScope(entry: Entry, mode: ReviewQuestionScope) {
        revealScope(entry, mode); tap(scopeTag(entry, mode))
        compose.onNodeWithTag(scopeTag(entry, mode)).assertIsSelected()
    }
    private fun start(entry: Entry) {
        if (entry == Entry.COLLECTION || entry == Entry.WHOLE) showKnowledgeItem(entry.start)
        tap(entry.start)
    }
    private data class Counts(val cards: Int, val total: Int, val round: Int, val unasked: Int, val other: Int)
    private fun summary(expected: Counts, mode: ReviewQuestionScope, collectionTitle: String? = null) {
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts").assertTextEquals(
            expected.cards.toString() + " 张卡片 · " + expected.round + " 道问题 · " + expected.unasked + " 张未设题卡片")
        compose.onNodeWithTag("branch-review-filter").assertTextContains(
            if (mode == ReviewQuestionScope.ALL) "全部" else "待复习", substring = true)
        listOf(
            "branch-review-total-questions" to expected.total,
            "branch-review-other-state-cards" to expected.other).forEach { (tag, count) ->
            val text = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString(" ") { it.text }
            assertEquals("Exact displayed count " + tag + ": " + text,
                listOf(count), Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList())
        }
        if (collectionTitle != null) compose.onNodeWithTag("branch-review-scope").assertTextEquals(collectionTitle)
        else compose.onNodeWithTag("branch-review-scope").assertDoesNotExist()
        assertHidden()
    }
    private fun question(f: Fixture, ordinal: Int, position: Int, total: Int) {
        waitFor("review-question")
        compose.onNodeWithTag("review-question").assertTextEquals(f.prompts[ordinal - 1])
        compose.onNodeWithText("手动回忆 · " + position + " / " + total, useUnmergedTree = true).assertIsDisplayed()
    }
    private fun assertHidden() {
        val own = hasAnyAncestor(hasTestTag("manual-review"))
        listOf("review-answer", "review-card-title", "review-open-source").forEach {
            compose.onAllNodes(hasTestTag(it) and own).assertCountEquals(0)
        }
        listOf("RS65-ANSWER", "RS65-BRANCH", "FOREIGN-RS65").forEach {
            compose.onAllNodes(hasText(it, substring = true) and own, useUnmergedTree = true).assertCountEquals(0)
        }
        compose.runOnIdle { assertTrue("Recall masks actual author windows",
            permittedViews().none { it is InkCanvasView || it is MindMapView }) }
    }
    private fun iterate(f: Fixture, ordinals: List<Int>) {
        ordinals.forEachIndexed { index, ordinal ->
            question(f, ordinal, index + 1, ordinals.size); assertHidden(); tap("branch-review-skip")
        }
        waitFor("branch-review-ended")
        compose.onNodeWithTag("review-question").assertDoesNotExist()
    }
    private fun closeReview() {
        tap("branch-review-close"); compose.waitUntil(15_000) { !exists("manual-review") }
    }
    private fun assertNoRound() {
        listOf("manual-review", "branch-review-counts", "review-question").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }
    private fun authors(f: Fixture) = support.authorStamp(f.note.id) to support.authorStamp(f.foreign.id)
    private fun assertAuthors(f: Fixture, before: Pair<List<String>, List<String>>) {
        assertEquals(before.first, support.authorStamp(f.note.id))
        assertEquals(before.second, support.authorStamp(f.foreign.id))
    }
    private fun sourceHash(f: Fixture) = support.sha(support.source(f.cardA).snapshot)
    private fun row(key: String) = runBlocking { checkNotNull(database.knowledge().get(key)) }
    private fun count(table: String, book: String): Long = runBlocking { database.withTransaction {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM " + table + " WHERE notebookId=?", arrayOf(book))).use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
    } }
    private fun records(book: String): Map<String, String> = runBlocking {
        database.knowledge().forBook(book).associate {
            it.id to (it.revision.toString() + "|" + it.removed + "|" + support.sha(it.payload))
        }
    }
    private fun sections(stamp: List<String>): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var sql: String? = null
        stamp.forEach { line ->
            if (line.startsWith("SELECT ")) { sql = line; result[line] = mutableListOf() }
            else checkNotNull(result[checkNotNull(sql)]).add(line)
        }
        assertEquals("Existing author fingerprint groups", 27, result.size)
        return result
    }
    private data class RatingBaseline(
        val authors: Pair<List<String>, List<String>>, val records: Map<String, String>,
        val revisions: Long, val receipts: Long, val note: NoteRow,
    )
    private fun ratingBaseline(f: Fixture) = RatingBaseline(authors(f), records(f.note.id),
        count("knowledge_revisions", f.note.id), count("knowledge_receipts", f.note.id),
        runBlocking { checkNotNull(database.notes().note(f.note.id)) })
    private fun assertOneRating(f: Fixture, before: RatingBaseline, ordinal: Int, operation: String? = null) {
        val key = f.questionId(ordinal); val after = authors(f)
        assertEquals(before.authors.second, after.second)
        val allowed = setOf("SELECT * FROM notes WHERE id=?",
            "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
            "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
            "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId")
        val newSections = sections(after.first)
        sections(before.authors.first).filterKeys { it !in allowed }.forEach { (sql, values) ->
            assertEquals("Rating changed unrelated author data " + sql, values, newSections[sql])
        }
        assertEquals(before.records - key, records(f.note.id) - key)
        assertEquals(2L, row(key).revision)
        assertEquals(KnowledgeData.Question(if (ordinal <= 2) f.cardA else f.cardE,
            f.prompts[ordinal - 1], ManualState.UNDERSTOOD), row(key).data())
        assertEquals(before.revisions + 1, count("knowledge_revisions", f.note.id))
        assertEquals(before.receipts + 1, count("knowledge_receipts", f.note.id))
        runBlocking {
            val current = checkNotNull(database.notes().note(f.note.id))
            assertEquals("Only the existing note touch is allowed", before.note.copy(updatedAt = current.updatedAt), current)
            assertNull(database.knowledge().revision(key, 3))
            operation?.let { assertEquals(key, checkNotNull(database.knowledge().receipt(it)).resultId) }
        }
    }
    private fun fence() {
        runBlocking { withTimeout(15_000) { database.withTransaction { database.knowledge().all() } } }
        compose.waitForIdle(); instrumentation.waitForIdleSync()
    }
    private fun transactionQueueDepth(): Int {
        val executor: Executor = database.transactionExecutor
        val fields = generateSequence(executor.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.toList()
        val field = fields.singleOrNull { java.util.Collection::class.java.isAssignableFrom(it.type) }
            ?: error("Actual Room transaction queue cannot be observed: " + executor.javaClass.name)
        return synchronized(executor) { field.isAccessible = true; (field.get(executor) as Collection<*>).size }
    }
    private fun beginGate(entry: Entry, label: String): SelectAwaitTestSupport.WriterGate {
        if (entry == Entry.COLLECTION || entry == Entry.WHOLE) showKnowledgeItem(entry.start)
        fence()
        val gate = support.WriterGate("RS65 " + label)
        try {
            gate.awaitHeld(); compose.waitUntil(15_000) { transactionQueueDepth() == 0 }
            start(entry)
            compose.waitUntil(15_000) { transactionQueueDepth() > 0 }
            if (entry == Entry.WHOLE || entry == Entry.COLLECTION) {
                compose.onNodeWithTag(entry.start).assertTextEquals("正在读取…").assertIsNotEnabled()
            } else waitFor("study-review-preparing")
            assertNoRound()
            android.util.Log.i("InkWeft-RS65", label + ": actual start queued behind same-app Room transaction")
            return gate
        } catch (failure: Throwable) { gate.finish(); throw failure }
    }
    private fun target48(tag: String, container: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        val r = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val outer = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        val d = compose.activity.resources.displayMetrics.density
        assertTrue(tag + " width " + r, r.width >= 48 * d - 1)
        assertTrue(tag + " height " + r, r.height >= 48 * d - 1)
        assertTrue(tag + " must be fully inside " + outer + ": " + r,
            r.left >= outer.left - 1 && r.top >= outer.top - 1 &&
                r.right <= outer.right + 1 && r.bottom <= outer.bottom + 1)
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val root = compose.runOnIdle { roots().first { it.isShown && it.hasWindowFocus() } }
        val latch = CountDownLatch(1)
        val draw = android.view.ViewTreeObserver.OnDrawListener { latch.countDown() }
        compose.runOnIdle { root.viewTreeObserver.addOnDrawListener(draw); root.invalidate() }
        try { assertTrue("Native draw before screenshot", latch.await(5, TimeUnit.SECONDS)) }
        finally { compose.runOnIdle { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(draw) } }
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun fourRealEntriesKeepAllDefaultAndSelectQuestionStateWithoutChangingCardScope() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); val before = authors(f); val source = sourceHash(f)
            openPaper(f); openKnowledge(); tap("knowledge-tab-4")
            compose.onNodeWithTag(scopeTag(Entry.WHOLE, ReviewQuestionScope.ALL)).assertIsSelected()
            start(Entry.WHOLE)
            compose.onNodeWithTag("branch-review-counts").assertDoesNotExist()
            iterate(f, listOf(1, 2, 3, 4, 5, 6)); closeReview()
            compose.onNodeWithTag("knowledge-tab-4").assertIsSelected()
            chooseScope(Entry.WHOLE, ReviewQuestionScope.REVIEW_ONLY); start(Entry.WHOLE)
            summary(Counts(6, 6, 3, 1, 2), ReviewQuestionScope.REVIEW_ONLY)
            tap("branch-review-start"); iterate(f, listOf(1, 5, 6)); closeReview()

            chooseCollection(f); search(f)
            showKnowledgeItem("collection-review-scope")
            compose.onNodeWithTag("collection-review-scope")
                .assertTextContains("搜索词只查卡片，不改变集合复习范围。", substring = true)
            compose.onNodeWithText("1 张卡片 · 筛选不复制内容", useUnmergedTree = true).assertExists()
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.ALL); start(Entry.COLLECTION)
            summary(Counts(5, 5, 5, 1, 0), ReviewQuestionScope.ALL, f.collectionTitle)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3, 4, 5)); closeReview()
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY); start(Entry.COLLECTION)
            summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            tap("branch-review-start"); iterate(f, listOf(1, 5)); closeReview()
            assertSearch(f); assertAuthors(f, before); closeKnowledge()

            openMap(f); mapMenu()
            compose.onNodeWithTag(scopeTag(Entry.MAP, ReviewQuestionScope.ALL)).assertIsSelected()
            start(Entry.MAP); summary(Counts(4, 4, 4, 1, 0), ReviewQuestionScope.ALL)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3, 4)); closeReview()
            mapMenu(); chooseScope(Entry.MAP, ReviewQuestionScope.REVIEW_ONLY); start(Entry.MAP)
            summary(Counts(4, 4, 1, 1, 2), ReviewQuestionScope.REVIEW_ONLY)
            tap("branch-review-start"); iterate(f, listOf(1)); closeReview()

            branchMenu(f); tap("node-menu-fold")
            compose.runOnIdle { assertNull("Folded child stays hidden in the actual map",
                native<MindMapView>().nodeBounds(f.nodeA)) }
            branchMenu(f); chooseScope(Entry.BRANCH, ReviewQuestionScope.ALL); start(Entry.BRANCH)
            summary(Counts(3, 3, 3, 1, 0), ReviewQuestionScope.ALL)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3)); closeReview()
            branchMenu(f); chooseScope(Entry.BRANCH, ReviewQuestionScope.REVIEW_ONLY); start(Entry.BRANCH)
            summary(Counts(3, 3, 1, 1, 1), ReviewQuestionScope.REVIEW_ONLY)
            assertAuthors(f, before); assertEquals(source, sourceHash(f))
            shot("rs65-four-entry-counts.png")
            tap("branch-review-start"); iterate(f, listOf(1)); closeReview()
            compose.runOnIdle {
                assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
                assertEquals(f.note.id, pages(f.note.id).ui.value.selectedId)
                assertNull(native<MindMapView>().nodeBounds(f.nodeA))
            }
            assertAuthors(f, before); assertEquals(source, sourceHash(f))
        }

    @Test fun fixedRoundSurvivesRatingRecreationAndSourceReturnThenNextReviewRoundShrinks() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); openPaper(f); openKnowledge(); chooseCollection(f); search(f)
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
            val before = ratingBaseline(f); val source = sourceHash(f)
            start(Entry.COLLECTION); summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            tap("branch-review-start"); question(f, 1, 1, 2); assertHidden()
            compose.activityRule.scenario.recreate(); question(f, 1, 1, 2); assertHidden()
            tap("reveal-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("review-open-source"); waitFor("review-source-canvas")
            compose.waitUntil(15_000) { compose.runOnIdle { native<InkCanvasView>().let {
                documentId(it) == f.sourcePage && it.documentContentReady && !it.rasterPending &&
                    it.displayedStrokeCount == 1 && !it.allowInput && !it.fingerWrites
            } } }
            compose.onNodeWithText(f.note.title + " · 第 2 页", useUnmergedTree = true).assertIsDisplayed()
            assertAuthors(f, before.authors); assertEquals(source, sourceHash(f))
            shot("rs65-frozen-source.png")
            compose.activityRule.scenario.recreate(); waitFor("review-source-canvas")
            compose.waitUntil(15_000) { compose.runOnIdle { native<InkCanvasView>().let {
                documentId(it) == f.sourcePage && it.documentContentReady && !it.rasterPending && !it.allowInput
            } } }
            tap("return-to-review"); question(f, 1, 1, 2)
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("branch-review-mark-UNDERSTOOD"); question(f, 5, 2, 2); assertHidden()
            assertOneRating(f, before, 1)
            val afterMark = authors(f)
            compose.activityRule.scenario.recreate(); question(f, 5, 2, 2); assertHidden()
            assertAuthors(f, afterMark)
            tap("branch-review-skip"); waitFor("branch-review-ended"); closeReview()
            assertSearch(f)
            revealScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
            compose.onNodeWithTag(scopeTag(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)).assertIsSelected()
            start(Entry.COLLECTION); summary(Counts(5, 5, 1, 1, 3), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            tap("branch-review-start"); question(f, 5, 1, 1); assertHidden()
            tap("branch-review-skip"); waitFor("branch-review-ended"); closeReview()
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.ALL); start(Entry.COLLECTION)
            summary(Counts(5, 5, 5, 1, 0), ReviewQuestionScope.ALL, f.collectionTitle)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3, 4, 5)); closeReview()
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY); start(Entry.COLLECTION)
            summary(Counts(5, 5, 1, 1, 3), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            tap("branch-review-start"); question(f, 5, 1, 1); assertHidden()
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite) }
            assertAuthors(f, afterMark); assertEquals(source, sourceHash(f)); closeReview()
            closeKnowledge(); openMap(f); branchMenu(f)
            chooseScope(Entry.BRANCH, ReviewQuestionScope.REVIEW_ONLY); start(Entry.BRANCH)
            summary(Counts(3, 3, 0, 1, 2), ReviewQuestionScope.REVIEW_ONLY)
            compose.onNodeWithTag("branch-review-empty-filter").assertExists()
            compose.onNodeWithTag("branch-review-start").assertIsNotEnabled()
            assertAuthors(f, afterMark); closeReview()
            branchMenu(f); chooseScope(Entry.BRANCH, ReviewQuestionScope.ALL); start(Entry.BRANCH)
            summary(Counts(3, 3, 3, 1, 0), ReviewQuestionScope.ALL)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3)); closeReview()
            assertAuthors(f, afterMark); assertEquals(source, sourceHash(f))
        }

    @Test fun queuedPreparationCancelsOnScopeTabMapAndRecreationWithoutLateRounds() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed(); openPaper(f); openKnowledge(); chooseCollection(f)
            val before = authors(f); val source = sourceHash(f)
            chooseScope(Entry.COLLECTION, ReviewQuestionScope.ALL)
            val replace = beginGate(Entry.COLLECTION, "old ALL replaced by REVIEW")
            try {
                chooseScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
                start(Entry.COLLECTION)
                compose.onNodeWithTag(Entry.COLLECTION.start).assertTextEquals("正在读取…").assertIsNotEnabled()
                assertNoRound()
            } finally { replace.finish() }
            fence(); summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            closeReview(); assertAuthors(f, before)

            tap("knowledge-tab-4")
            chooseScope(Entry.WHOLE, ReviewQuestionScope.REVIEW_ONLY)
            val tab = beginGate(Entry.WHOLE, "whole tab cancellation")
            try { tap("knowledge-tab-1"); assertNoRound() }
            finally { tab.finish() }
            fence(); assertNoRound(); compose.onNodeWithTag("knowledge-tab-1").assertIsSelected()
            assertAuthors(f, before)

            tap("knowledge-tab-4")
            val rotation = beginGate(Entry.WHOLE, "whole pending Activity recreation")
            try { compose.activityRule.scenario.recreate(); waitFor("knowledge-workspace"); assertNoRound() }
            finally { rotation.finish() }
            fence(); assertNoRound()
            compose.onNodeWithTag("knowledge-tab-4").assertIsSelected()
            compose.onNodeWithTag(scopeTag(Entry.WHOLE, ReviewQuestionScope.REVIEW_ONLY)).assertIsSelected()
            start(Entry.WHOLE); summary(Counts(6, 6, 3, 1, 2), ReviewQuestionScope.REVIEW_ONLY)
            closeReview(); assertAuthors(f, before); closeKnowledge()

            openMap(f); mapMenu(); chooseScope(Entry.MAP, ReviewQuestionScope.REVIEW_ONLY)
            val map = beginGate(Entry.MAP, "map changed while queued")
            try {
                tap("study-map-picker"); tap("study-map-" + f.otherMap)
                compose.waitUntil(15_000) { compose.runOnIdle { study(f.note.id).mapId.value == f.otherMap } }
                assertNoRound()
            } finally { map.finish() }
            fence(); waitMap(f, f.otherMap); assertNoRound(); assertAuthors(f, before)
            mapMenu(); start(Entry.MAP)
            summary(Counts(1, 1, 1, 0, 0), ReviewQuestionScope.REVIEW_ONLY)
            tap("branch-review-start"); iterate(f, listOf(6)); closeReview()
            tap("study-map-picker"); tap("study-map-" + f.map); waitMap(f)
            branchMenu(f); chooseScope(Entry.BRANCH, ReviewQuestionScope.REVIEW_ONLY)
            val branch = beginGate(Entry.BRANCH, "branch scope cancellation")
            try {
                branchMenu(f); chooseScope(Entry.BRANCH, ReviewQuestionScope.ALL)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle()
                assertNoRound()
            } finally { branch.finish() }
            fence(); waitMap(f); assertNoRound()
            compose.onNodeWithTag("study-review-preparing").assertDoesNotExist()
            branchMenu(f)
            compose.onNodeWithTag(scopeTag(Entry.BRANCH, ReviewQuestionScope.ALL)).assertIsSelected()
            start(Entry.BRANCH); summary(Counts(3, 3, 3, 1, 0), ReviewQuestionScope.ALL)
            tap("branch-review-start"); iterate(f, listOf(1, 2, 3)); closeReview()
            mapMenu(); chooseScope(Entry.MAP, ReviewQuestionScope.REVIEW_ONLY)
            val minimized = beginGate(Entry.MAP, "root minimizes queued map")
            try { tap("study-window-minimize"); assertNoRound() }
            finally { minimized.finish() }
            fence(); assertNoRound(); assertAuthors(f, before)
            tap("study-window-minimize"); waitMap(f)
            mapMenu(); start(Entry.MAP)
            summary(Counts(4, 4, 1, 1, 2), ReviewQuestionScope.REVIEW_ONLY); closeReview()
            mapMenu()
            val mode = beginGate(Entry.MAP, "root changes mode while queued")
            try {
                tap("study-window-maximize"); tap("study-window-mode-ORGANIZE")
                assertNoRound()
            } finally { mode.finish() }
            fence(); assertNoRound(); assertAuthors(f, before)
            tap("study-window-maximize"); tap("study-window-mode-FOCUS"); waitMap(f)
            mapMenu()
            val closed = beginGate(Entry.MAP, "root closes queued map")
            try { tap("study-close"); waitFor("reading-toolbar"); assertNoRound() }
            finally { closed.finish() }
            fence(); assertNoRound(); compose.onNodeWithTag("study-panel").assertDoesNotExist()
            assertAuthors(f, before)
            openMap(f); mapMenu(); chooseScope(Entry.MAP, ReviewQuestionScope.REVIEW_ONLY); start(Entry.MAP)
            summary(Counts(4, 4, 1, 1, 2), ReviewQuestionScope.REVIEW_ONLY)
            tap("branch-review-start"); iterate(f, listOf(1)); closeReview()
            assertAuthors(f, before); assertEquals(source, sourceHash(f))
        }

    @Test fun unknownOriginalRatingRestoresExactReceiptAndCannotStartAReplacementRound() =
        atDisplay("1200x1920", 160, 1200, 1f) {
            val f = seed()
            val failBeforeReceipt = AtomicBoolean(true)
            val repository = KnowledgeRepository(database) { stage ->
                if (stage == KnowledgeFault.BEFORE_RECEIPT && failBeforeReceipt.get())
                    throw IOException("Synthetic rollback before RS65 original review receipt")
            }
            var saved = SavedStateHandle()
            val key = "branch-review-" + f.note.id
            var owned: KnowledgeViewModel? = null
            try {
                compose.runOnIdle {
                    assertNull("Fresh book owns no previous review writer", compose.activity.viewModelStore.get(key))
                    owned = KnowledgeViewModel(repository, saved, app.resourcePacks)
                    compose.activity.viewModelStore.put(key, checkNotNull(owned))
                }
                openPaper(f); openKnowledge(); chooseCollection(f)
                chooseScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
                start(Entry.COLLECTION); summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
                tap("branch-review-start"); question(f, 1, 1, 2)
                tap("reveal-answer")
                val before = ratingBaseline(f); val source = sourceHash(f)
                tap("branch-review-mark-UNDERSTOOD"); waitFor("branch-review-retry")
                question(f, 1, 1, 2); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
                listOf("branch-review-close", "branch-review-skip", "branch-review-mark-REVIEW",
                    "branch-review-mark-UNDERSTOOD", "review-open-source").forEach {
                    compose.onNodeWithTag(it).assertIsNotEnabled()
                }
                assertAuthors(f, before.authors)
                val request = compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() }
                val payload = compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")).copyOf() }
                val operation = request[0]
                assertEquals(listOf(f.note.id, f.questionId(1), "1", "false"), request.drop(1))
                assertEquals(KnowledgeData.Question(f.cardA, f.prompts[0], ManualState.UNDERSTOOD), KnowledgeCodec.decode(payload))
                assertEquals(1L, compose.runOnIdle { saved.get<Long>("knowledge.reviewCardRevision") })
                runBlocking { assertNull(database.knowledge().receipt(operation)) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle()
                question(f, 1, 1, 2); waitFor("branch-review-retry")
                // A real VM is rebuilt from its serialized pending command. This
                // does not claim full operating-system process-death coverage.
                compose.runOnIdle {
                    assertSame(owned, compose.activity.viewModelStore.get(key))
                    saved = SavedStateHandle(saved.keys().associateWith { savedKey ->
                        when (val value = saved.get<Any?>(savedKey)) {
                            is ByteArray -> value.copyOf()
                            is ArrayList<*> -> ArrayList(value)
                            else -> value
                        }
                    })
                    owned = KnowledgeViewModel(repository, saved, app.resourcePacks)
                    compose.activity.viewModelStore.put(key, checkNotNull(owned))
                }
                compose.activityRule.scenario.recreate(); waitFor("branch-review-retry")
                question(f, 1, 1, 2); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
                listOf("branch-review-close", "branch-review-skip", "review-open-source").forEach {
                    compose.onNodeWithTag(it).assertIsNotEnabled()
                }
                assertEquals(request, compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() })
                assertArrayEquals(payload, compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")) })
                assertEquals(1L, compose.runOnIdle { saved.get<Long>("knowledge.reviewCardRevision") })
                assertAuthors(f, before.authors); assertEquals(source, sourceHash(f))
                shot("rs65-unknown-restored.png")
                failBeforeReceipt.set(false); tap("branch-review-retry")
                question(f, 5, 2, 2); assertHidden(); assertOneRating(f, before, 1, operation)
                compose.runOnIdle { assertNull(saved.get<ArrayList<String>>("knowledge.request")) }
                val committed = authors(f)
                val original = KnowledgeCommand(operation, f.note.id, f.questionId(1), 1, KnowledgeCodec.decode(payload))
                assertEquals(KnowledgeOutcome.Success(f.questionId(1)), runBlocking { app.knowledge.reviewOutcome(original, 1) })
                assertAuthors(f, committed)
                tap("branch-review-skip"); waitFor("branch-review-ended"); closeReview()
                revealScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
                compose.onNodeWithTag(scopeTag(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)).assertIsSelected()
                start(Entry.COLLECTION); summary(Counts(5, 5, 1, 1, 3), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
                tap("branch-review-start"); question(f, 5, 1, 1); assertHidden()
                assertAuthors(f, committed); assertEquals(source, sourceHash(f)); closeReview()
            } finally {
                failBeforeReceipt.set(false)
                compose.runOnIdle {
                    if (compose.activity.viewModelStore.get(key) === owned)
                        compose.activity.viewModelStore.put(key, KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks))
                }
            }
        }

    @Test fun narrow375Font165KeepsScopeSummaryAndFixedReadonlyRoundReachable() =
        atDisplay("750x1600", 320, 375, 1.65f) {
            val f = seed(longText = true); openPaper(f); openKnowledge(); chooseCollection(f); search(f)
            val before = authors(f); val source = sourceHash(f)
            listOf(ReviewQuestionScope.ALL, ReviewQuestionScope.REVIEW_ONLY).forEach { mode ->
                revealScope(Entry.COLLECTION, mode)
                target48(scopeTag(Entry.COLLECTION, mode), "knowledge-workspace")
                chooseScope(Entry.COLLECTION, mode)
            }
            showKnowledgeItem(Entry.COLLECTION.start); target48(Entry.COLLECTION.start, "knowledge-workspace")
            start(Entry.COLLECTION); summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            summary(Counts(5, 5, 2, 1, 2), ReviewQuestionScope.REVIEW_ONLY, f.collectionTitle)
            target48("branch-review-start", "manual-review"); tap("branch-review-start")
            question(f, 1, 1, 2); assertHidden()
            target48("reveal-answer", "manual-review"); tap("reveal-answer")
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            question(f, 1, 1, 2); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            listOf("review-open-source", "branch-review-mark-UNDERSTOOD", "branch-review-mark-REVIEW",
                "branch-review-skip").forEach { target48(it, "manual-review") }
            tap("branch-review-skip"); question(f, 5, 2, 2); assertHidden()
            val questionRect = compose.onNodeWithTag("review-question").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("manual-review").fetchSemanticsNode().boundsInRoot
            assertTrue("Next question opens at visible top", questionRect.top >= panel.top &&
                questionRect.top < panel.top + panel.height / 2)
            assertAuthors(f, before); assertEquals(source, sourceHash(f)); closeReview()
            assertSearch(f)
            revealScope(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)
            compose.onNodeWithTag(scopeTag(Entry.COLLECTION, ReviewQuestionScope.REVIEW_ONLY)).assertIsSelected()
            closeKnowledge(); openMap(f); mapMenu()
            listOf(ReviewQuestionScope.ALL, ReviewQuestionScope.REVIEW_ONLY).forEach { mode ->
                target48(scopeTag(Entry.MAP, mode), "map-menu"); chooseScope(Entry.MAP, mode)
            }
            target48(Entry.MAP.start, "map-menu"); start(Entry.MAP)
            summary(Counts(4, 4, 1, 1, 2), ReviewQuestionScope.REVIEW_ONLY); closeReview()
            branchMenu(f)
            listOf(ReviewQuestionScope.ALL, ReviewQuestionScope.REVIEW_ONLY).forEach { mode ->
                target48(scopeTag(Entry.BRANCH, mode), "node-menu"); chooseScope(Entry.BRANCH, mode)
            }
            target48(Entry.BRANCH.start, "node-menu"); start(Entry.BRANCH)
            summary(Counts(3, 3, 1, 1, 1), ReviewQuestionScope.REVIEW_ONLY)
            compose.activityRule.scenario.recreate(); awaitConfiguration(375, 1.65f)
            summary(Counts(3, 3, 1, 1, 1), ReviewQuestionScope.REVIEW_ONLY)
            tap("branch-review-start"); question(f, 1, 1, 1); assertHidden()
            assertAuthors(f, before); assertEquals(source, sourceHash(f))
            compose.runOnIdle { assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite) }
            shot("rs65-narrow-recreated.png")
            tap("branch-review-skip"); waitFor("branch-review-ended"); closeReview()
            tap("study-close"); waitFor("reading-toolbar")
            compose.runOnIdle {
                assertTrue(lock(f.note.id).readOnly.value); assertFalse(lock(f.note.id).canWrite)
                assertFalse(native<InkCanvasView>().allowInput)
            }
            openKnowledge(); tap("knowledge-tab-4")
            listOf(ReviewQuestionScope.ALL, ReviewQuestionScope.REVIEW_ONLY).forEach { mode ->
                revealScope(Entry.WHOLE, mode)
                target48(scopeTag(Entry.WHOLE, mode), "notebook-review-panel"); chooseScope(Entry.WHOLE, mode)
            }
            showKnowledgeItem(Entry.WHOLE.start); target48(Entry.WHOLE.start, "notebook-review-panel"); start(Entry.WHOLE)
            summary(Counts(6, 6, 3, 1, 2), ReviewQuestionScope.REVIEW_ONLY); closeReview()
            val card = hasText("查看属性 · 同卡有 REVIEW 与 UNDERSTOOD")
            compose.onNode(hasScrollToIndexAction() and hasTestTag("notebook-review-panel"))
                .performScrollToNode(card)
            compose.onNode(card).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            waitFor("card-properties-dialog")
            compose.onNodeWithTag("card-question-add").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("question-edit-" + f.questionId(1)).performScrollTo()
                .assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("question-edit-dialog").assertDoesNotExist()
            compose.onNodeWithTag("question-remove-" + f.questionId(2)).performScrollTo().assertIsNotEnabled()
            tap("card-properties-cancel")
            assertAuthors(f, before); assertEquals(source, sourceHash(f))
        }
}
