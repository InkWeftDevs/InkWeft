// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Real MainActivity, saved card properties and the application's own Room instance.
 * The only injection is the existing KnowledgeFault repository boundary. All notes,
 * questions, handwriting and source snapshots below are synthetic test fixtures.
 * No isolated Compose content, fake UI state or second SQLite writer is used.
 */
class QuestionMaintenanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val database get() = KnowledgeRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.knowledge) as NoteDatabase
    private val preferences = linkedMapOf<String, Map<String, Any?>>()
    private fun id() = UUID.randomUUID().toString()

    @Before fun captureOwnedPreferences() {
        listOf("inkweft-editor", "inkweft-reading").forEach { name ->
            preferences[name] = app.getSharedPreferences(name, 0).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }
        assertTrue(app.getSharedPreferences("inkweft-editor", 0).edit()
            .putBoolean("case-collapsed", true).commit())
    }

    @After fun restoreOwnedPreferences() {
        try { hideKeyboard() }
        finally {
            preferences.forEach { (name, values) ->
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
            else -> error("Unexpected preference ${value.javaClass.name}")
        }
    }

    private data class Fixture(
        val note: Note,
        val foreign: Note,
        val card: String,
        val cardTitle: String,
        val answer: String,
        val first: String,
        val second: String,
        val other: String,
        val foreignQuestion: String,
        val firstPrompt: String,
        val secondPrompt: String,
    )

    private fun seed(longText: Boolean = false): Fixture {
        waitFor("new-note")
        val note = runBlocking {
            app.workspaceRepository.create("题目维护 " + id().take(6), false, PaperStyle.RULED)
        }
        val foreign = runBlocking {
            app.workspaceRepository.create("另一册题目 " + id().take(6), false, PaperStyle.BLANK)
        }
        val title = if (longText) "同一摘要卡 · 两道长问题分别维护"
            else "同卡两道独立回忆题"
        val firstPrompt = if (longText) "第一题原文开头\n" +
            (1..32).joinToString("\n") { "条件问题 $it：请说明样本空间与条件事件的分母，再核对推导过程。" }
            else "原题一：为什么先限定样本空间？"
        val secondPrompt = if (longText) "第二题移除时必须核对的开头\n" +
            (1..30).joinToString("\n") { "应用问题 $it：请解释条件概率与独立事件，保留原卡的答案和来源。" }
            else "原题二：独立事件与条件概率有什么区别？"
        assertTrue(firstPrompt.length <= 2000 && secondPrompt.length <= 2000 && title.length <= 120)
        val f = Fixture(note, foreign, id(), title, "作者摘要 QM64-ANSWER：只维护问题，不重写答案。",
            id(), id(), id(), id(), firstPrompt, secondPrompt)
        runBlocking {
            val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(100f, 400f, 0), InkSample(240f, 420f, 30)))
            assertEquals(InkCommitResult.Committed(1), app.inkRepository.save(
                CommitInk(id(), note.id, 0, InkMutation.Add(stroke))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE,
                cardId = f.card, nodeId = id(), title = f.cardTitle, body = f.answer,
                source = StudySourceDraft(note.id, 1, stroke.bounds(), listOf(stroke.id))))
            val otherCard = id()
            val foreignCard = id()
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE,
                cardId = otherCard, nodeId = id(), title = "同册另一卡", body = "同册范围外摘要"))
            app.study.submit(StudyCommand(id(), foreign.id, StudyAction.CREATE,
                cardId = foreignCard, nodeId = id(), title = f.cardTitle, body = "外册同名卡答案"))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, id(), 0,
                KnowledgeData.Properties(f.card, ManualState.INBOX, listOf("原标签"))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.first, 0,
                KnowledgeData.Question(f.card, f.firstPrompt, ManualState.UNDERSTOOD)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.second, 0,
                KnowledgeData.Question(f.card, f.secondPrompt, ManualState.REVIEW)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, f.other, 0,
                KnowledgeData.Question(otherCard, "同册另一卡的问题", ManualState.INBOX)))
            // The same prompt in another book makes identity errors observable.
            app.knowledge.submit(KnowledgeCommand(id(), foreign.id, f.foreignQuestion, 0,
                KnowledgeData.Question(foreignCard, f.firstPrompt, ManualState.UNDERSTOOD)))
            for (book in listOf(note.id, foreign.id)) for (page in app.pages.activePages(book)) {
                assertTrue(app.pages.saveSearchText(page.id, app.pages.inkRevision(page.id), "", method = "MANUAL"))
            }
        }
        return f
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { exists(tag) }
        compose.waitForIdle()
    }
    private fun waitGone(tag: String) {
        compose.waitUntil(15_000) { !exists(tag) }
        compose.waitForIdle()
    }
    private fun tap(tag: String) {
        compose.revealAction(tag)
        waitFor(tag)
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        compose.waitUntil(15_000) { runCatching { node.assertIsEnabled() }.isSuccess }
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }
    private fun replace(tag: String, text: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().assertIsEnabled().performTextReplacement(text)
        compose.waitForIdle()
    }
    private fun assertDraft(tag: String, text: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(text)))
    }

    /** Enter the existing library review route, then actual card properties. */
    private fun openProperties(f: Fixture, reader: Boolean = false) {
        if (!reader) {
            if (exists("open-library-drawer")) tap("open-library-drawer")
            compose.onNodeWithText("复习", useUnmergedTree = true).performScrollTo().performClick()
            val target = hasText(f.note.title) and hasAnyAncestor(isDialog())
            compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(target).performScrollTo().performClick()
        }
        waitFor("knowledge-tab-4")
        tap("knowledge-tab-4")
        val target = hasText((if (reader) "查看属性 · " else "添加问题 · ") + f.cardTitle)
        compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(target).performScrollTo().assertIsDisplayed().performClick()
        waitFor("card-properties-dialog")
        waitFor("question-row-" + f.first)
        waitFor("question-row-" + f.second)
    }

    private fun edit(question: String) {
        tap("question-edit-" + question)
        waitFor("question-edit-dialog")
    }
    private fun remove(question: String) {
        tap("question-remove-" + question)
        waitFor("question-remove-dialog")
    }
    private fun rows(question: String) = runBlocking { checkNotNull(database.knowledge().get(question)) }
    private fun receipts(question: String): Set<String> = runBlocking {
        database.withTransaction {
            database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT operationId FROM knowledge_receipts WHERE resultId=?", arrayOf(question))).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private data class Allowed(val book: String, val question: String, val operation: String? = null)

    /** Dynamically fingerprints every non-SQLite application table, including
     * histories, source snapshots, ink, objects, graph nodes and foreign books.
     * For a successful maintenance command only its exact current question row,
     * exact new revision 2, exact new receipt and this book's updatedAt may differ.
     * Those omitted rows are checked field-for-field by assertCommit below.
     */
    private fun authorStamp(allowed: Allowed? = null): List<String> = runBlocking {
        database.withTransaction {
            val sql = database.openHelper.readableDatabase
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(tables.containsAll(listOf("knowledge_records", "knowledge_revisions", "knowledge_receipts",
                "study_cards", "study_card_revisions", "study_sources", "study_nodes", "ink_strokes", "notes")))
            buildList {
                for (table in tables) {
                    val escaped = table.replace("\"", "\"\"")
                    sql.query("SELECT * FROM \"$escaped\"").use { cursor ->
                        val columns = cursor.columnNames
                        val idIndex = cursor.getColumnIndex("id")
                        val operationIndex = cursor.getColumnIndex("operationId")
                        val revisionIndex = cursor.getColumnIndex("revision")
                        val contents = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            val target = allowed != null && idIndex >= 0 && cursor.getString(idIndex) == allowed.question
                            if (target && table == "knowledge_records") continue
                            if (target && table == "knowledge_revisions" && cursor.getLong(revisionIndex) == 2L) continue
                            if (allowed?.operation != null && table == "knowledge_receipts" &&
                                cursor.getString(operationIndex) == allowed.operation) continue
                            contents += columns.indices.joinToString("|") { index ->
                                val value = if (allowed != null && table == "notes" && idIndex >= 0 &&
                                    cursor.getString(idIndex) == allowed.book && columns[index] == "updatedAt") "allowed-touch"
                                else when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> "null"
                                    Cursor.FIELD_TYPE_INTEGER -> "i:" + cursor.getLong(index)
                                    Cursor.FIELD_TYPE_FLOAT -> "f:" + cursor.getDouble(index)
                                    Cursor.FIELD_TYPE_BLOB -> "b:" + sha(cursor.getBlob(index))
                                    else -> "s:" + cursor.getString(index).let { it.length.toString() + ":" + it }
                                }
                                columns[index] + "=" + value
                            }
                        }
                        add(table + "|columns=" + columns.joinToString(",") + "|rows=" + contents.size)
                        addAll(contents.sorted().map { table + "|" + it })
                    }
                }
            }
        }
    }

    private fun assertCommit(f: Fixture, question: String, before: List<String>, receiptIds: Set<String>,
        expected: KnowledgeData.Question, removed: Boolean = false, expectedOperation: String? = null): String {
        val newReceipts = receipts(question) - receiptIds
        assertEquals("Only one maintenance receipt may be added", 1, newReceipts.size)
        val operation = newReceipts.single()
        if (expectedOperation != null) assertEquals(expectedOperation, operation)
        val command = KnowledgeCommand(operation, f.note.id, question, 1, expected, removed)
        runBlocking {
            val current = checkNotNull(database.knowledge().get(question))
            assertEquals(question, current.id)
            assertEquals(f.note.id, current.notebookId)
            assertEquals(2L, current.revision)
            assertEquals(removed, current.removed)
            assertEquals(expected, current.data())
            assertArrayEquals(command.payload, current.payload)
            val history = database.knowledge().revisions(question)
            assertEquals(listOf(2L, 1L), history.map { it.revision })
            val next = checkNotNull(database.knowledge().revision(question, 2))
            assertEquals(f.note.id, next.notebookId)
            assertEquals(removed, next.removed)
            assertArrayEquals(command.payload, next.payload)
            val receipt = checkNotNull(database.knowledge().receipt(operation))
            assertEquals(f.note.id, receipt.notebookId)
            assertEquals(question, receipt.resultId)
            assertEquals(command.digest(), receipt.digest)
            assertEquals(1L, checkNotNull(database.study().card(f.card)).revision)
            assertEquals(f.answer, checkNotNull(database.study().card(f.card)).body)
        }
        assertEquals("Only the exact command's allowed rows may change", before,
            authorStamp(Allowed(f.note.id, question, operation)))
        return operation
    }

    private fun roots(): List<View> = WindowInspector.getGlobalWindowViews()
        .filter { it.isAttachedToWindow && it.isShown }
    private fun hideKeyboard() {
        compose.runOnIdle {
            roots().forEach { ViewCompat.getWindowInsetsController(it)?.hide(WindowInsetsCompat.Type.ime()) }
        }
        compose.waitForIdle()
    }
    private fun keyboardVisible() = compose.runOnIdle {
        roots().filter { it.hasWindowFocus() }.any {
            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private val tagsDraft = " 未保存标签，父层草稿 "
    private val aliasDraft = " 未提交别名 QM64 "
    private val questionDraft = " 另一道尚未添加的独立题\n原草稿不得因维护旧题而清空。 "
    private fun fillParentDrafts() {
        replace("card-properties-tags", tagsDraft)
        replace("card-properties-alias", aliasDraft)
        replace("card-properties-question", questionDraft)
        hideKeyboard()
    }
    private fun assertParentDrafts() {
        waitFor("card-properties-dialog")
        assertDraft("card-properties-tags", tagsDraft)
        assertDraft("card-properties-alias", aliasDraft)
        assertDraft("card-properties-question", questionDraft)
    }

    private fun fullyVisible48(tag: String, container: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        val semantics = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        val window = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        val minimum = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("$tag must be at least 48dp wide", semantics.size.width + 1f >= minimum)
        assertTrue("$tag must be at least 48dp high", semantics.size.height + 1f >= minimum)
        assertTrue("$tag must not be clipped", bounds.width + 1f >= semantics.size.width &&
            bounds.height + 1f >= semantics.size.height)
        assertTrue("$tag must fit its actual dialog", bounds.left >= window.left - 1f &&
            bounds.right <= window.right + 1f && bounds.top >= window.top - 1f && bounds.bottom <= window.bottom + 1f)
    }

    private fun shot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(app.getExternalFilesDir(null), "question-maintenance-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    private fun checked(name: String, action: () -> Unit) {
        try { action() }
        catch (failure: Throwable) {
            runCatching { shot("failure-$name") }.onFailure { failure.addSuppressed(it) }
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("QM64-$name") }
            throw failure
        }
    }

    @Test fun editingOneUnderstoodQuestionPreservesIdentityStateOriginalHistoryAndOtherAuthors() = checked("edit-one") {
        val f = seed()
        openProperties(f)
        val old = rows(f.first).data() as KnowledgeData.Question
        assertEquals(ManualState.UNDERSTOOD, old.state)
        val before = authorStamp(Allowed(f.note.id, f.first))
        val receiptIds = receipts(f.first)
        edit(f.first)
        assertDraft("question-edit-prompt", f.firstPrompt)
        val raw = "  改题一：先明确条件事件\n再说明样本空间的变化。  "
        replace("question-edit-prompt", raw)
        hideKeyboard()
        tap("question-edit-save")
        waitGone("question-edit-dialog")
        compose.onNodeWithTag("card-properties-dialog").assertExists()
        compose.onNodeWithTag("question-prompt-" + f.first).assertTextEquals(raw)
        compose.onNodeWithTag("question-state-" + f.first)
            .assertTextEquals("手工状态：已理解 · 修订 2")
        assertCommit(f, f.first, before, receiptIds, old.copy(prompt = raw))
        shot("edit-one-saved")
    }

    @Test fun confirmedRemovalSoftRemovesOnlyThatQuestionAndCancelWritesNothing() = checked("remove-one") {
        val f = seed()
        openProperties(f)
        val old = rows(f.first).data() as KnowledgeData.Question
        val unchanged = authorStamp()
        remove(f.first)
        compose.onNodeWithTag("question-remove-prompt").assertTextEquals(f.firstPrompt)
        compose.onNodeWithText("只移除这道题，摘要卡、答案和来源保留。已经开始的回忆仍可显示原题。")
            .assertExists()
        tap("question-remove-cancel")
        waitGone("question-remove-dialog")
        assertEquals(unchanged, authorStamp())
        val before = authorStamp(Allowed(f.note.id, f.first))
        val receiptIds = receipts(f.first)
        remove(f.first)
        tap("question-remove-confirm")
        waitGone("question-remove-dialog")
        waitGone("question-row-" + f.first)
        compose.onNodeWithTag("question-row-" + f.second).assertExists()
        compose.onNodeWithTag("question-prompt-" + f.second).assertTextEquals(f.secondPrompt)
        assertCommit(f, f.first, before, receiptIds, old, removed = true)
        shot("remove-one-saved")
    }

    @Test fun childSuccessAndActivityRecreationKeepAllUnsavedParentPropertyDrafts() = checked("parent-drafts") {
        val f = seed()
        openProperties(f)
        fillParentDrafts()
        val old = rows(f.first).data() as KnowledgeData.Question
        val before = authorStamp(Allowed(f.note.id, f.first))
        val receiptIds = receipts(f.first)
        edit(f.first)
        val raw = " 修改已有题而保留父层的三份独立草稿 "
        replace("question-edit-prompt", raw)
        hideKeyboard()
        tap("question-edit-save")
        waitGone("question-edit-dialog")
        assertParentDrafts()
        assertCommit(f, f.first, before, receiptIds, old.copy(prompt = raw))
        val after = authorStamp()
        compose.activityRule.scenario.recreate()
        assertParentDrafts()
        compose.onNodeWithTag("question-prompt-" + f.first).assertTextEquals(raw)
        assertEquals("Recreation must not submit tags, alias or a new question", after, authorStamp())
        shot("parent-drafts-recreated")
    }

    @Test fun longQuestionSearchKeepsDraftsAndIdentityAcrossRecreationAndEditing() = checked("question-search") {
        val f = seed()
        val target = id()
        val old = KnowledgeData.Question(f.card, "NeEdLe：需要独立维护的目标问题", ManualState.REVIEW)
        runBlocking {
            repeat(18) { index -> app.knowledge.submit(KnowledgeCommand(id(), f.note.id, id(), 0,
                KnowledgeData.Question(f.card, "长列表第 $index 题：" + "只作为其他题目保留。".repeat(60)))) }
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, target, 0, old))
        }
        openProperties(f)
        fillParentDrafts()
        val unchanged = authorStamp()
        replace("card-question-search", " needle ")
        hideKeyboard()
        compose.onNodeWithTag("card-question-search-count").assertTextEquals("显示 1 / 21 道已保存问题")
        compose.onNodeWithTag("question-row-$target").assertExists()
        compose.onNodeWithTag("question-row-${f.first}").assertDoesNotExist()
        compose.onNodeWithTag("question-row-${f.foreignQuestion}").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        assertParentDrafts()
        assertDraft("card-question-search", " needle ")
        compose.onNodeWithTag("question-row-$target").assertExists()
        assertEquals("Searching and rebuilding must not write author data", unchanged, authorStamp())
        replace("card-question-search", "没有这道题")
        hideKeyboard()
        compose.onNodeWithTag("card-question-search-empty").assertExists()
        tap("card-question-search-clear")
        compose.onNodeWithTag("card-question-search-count").assertTextEquals("显示 21 / 21 道已保存问题")
        replace("card-question-search", "needle")
        hideKeyboard()
        val before = authorStamp(Allowed(f.note.id, target))
        val receiptIds = receipts(target)
        edit(target)
        val changed = "改题后已不再含搜索词"
        replace("question-edit-prompt", changed)
        hideKeyboard()
        tap("question-edit-save")
        waitGone("question-edit-dialog")
        assertParentDrafts()
        assertDraft("card-question-search", "needle")
        compose.onNodeWithTag("card-question-search-count").assertTextEquals("显示 0 / 21 道已保存问题")
        compose.onNodeWithTag("question-row-$target").assertDoesNotExist()
        assertCommit(f, target, before, receiptIds, old.copy(prompt = changed))
        tap("card-question-search-clear")
        compose.onNodeWithTag("question-prompt-$target").assertTextEquals(changed)
        shot("question-search-edited")
    }

    @Test fun concurrentVersionChangeRejectsCapturedBaseAndKeepsExactRawDraftAcrossRecreation() = checked("conflict") {
        val f = seed()
        openProperties(f)
        fillParentDrafts()
        edit(f.first)
        val raw = "  本地冲突草稿\n保留前后空格与换行，不覆盖新版本。  "
        replace("question-edit-prompt", raw)
        hideKeyboard()
        val newer = (rows(f.first).data() as KnowledgeData.Question).copy(prompt = "另一真实操作已经保存的新题")
        runBlocking { app.knowledge.submit(KnowledgeCommand(id(), f.note.id, f.first, 1, newer)) }
        waitFor("question-edit-conflict")
        val beforeReject = authorStamp()
        val receiptIds = receipts(f.first)
        tap("question-edit-save")
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("未提交", substring = true) and
                hasAnyAncestor(hasTestTag("question-edit-dialog"))).fetchSemanticsNodes().isNotEmpty()
        }
        assertDraft("question-edit-prompt", raw)
        assertParentDrafts()
        assertEquals(newer, rows(f.first).data())
        assertEquals(2L, rows(f.first).revision)
        assertEquals(receiptIds, receipts(f.first))
        assertEquals(beforeReject, authorStamp())
        compose.activityRule.scenario.recreate()
        waitFor("question-edit-dialog")
        waitFor("question-edit-conflict")
        assertDraft("question-edit-prompt", raw)
        assertParentDrafts()
        assertEquals(beforeReject, authorStamp())
        shot("conflict-recreated")
        tap("question-edit-cancel")
        waitGone("question-edit-dialog")
        assertParentDrafts()
        assertEquals(beforeReject, authorStamp())
    }

    @Test fun unknownOriginalEditReconstructsPendingCommandAndRetriesExactlyOnce() = checked("unknown-retry") {
        val f = seed()
        val failBeforeReceipt = AtomicBoolean(true)
        var saved = SavedStateHandle()
        val repository = KnowledgeRepository(database) { stage ->
            if (stage == KnowledgeFault.BEFORE_RECEIPT && failBeforeReceipt.get()) {
                throw IOException("Synthetic failure before the question-maintenance receipt")
            }
        }
        val key = "knowledge-" + f.note.id
        var owned: KnowledgeViewModel? = null
        try {
            compose.runOnIdle {
                assertNull("The fresh book must not already own a knowledge VM", compose.activity.viewModelStore.get(key))
                owned = KnowledgeViewModel(repository, saved, app.resourcePacks)
                compose.activity.viewModelStore.put(key, checkNotNull(owned))
            }
            openProperties(f)
            fillParentDrafts()
            val old = rows(f.first).data() as KnowledgeData.Question
            val unchanged = authorStamp()
            val before = authorStamp(Allowed(f.note.id, f.first))
            val receiptIds = receipts(f.first)
            edit(f.first)
            val raw = "  结果未知的原题稿\n重建后只核对这份原操作。  "
            replace("question-edit-prompt", raw)
            hideKeyboard()
            tap("question-edit-save")
            waitFor("question-edit-retry")
            compose.onNodeWithTag("question-edit-prompt").assertIsNotEnabled()
            listOf("question-edit-save", "question-edit-cancel", "card-properties-save", "card-properties-cancel")
                .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
            assertDraft("question-edit-prompt", raw)
            assertParentDrafts()
            assertEquals("The failed actual transaction must fully roll back", unchanged, authorStamp())
            val request = compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() }
            val payload = compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")).copyOf() }
            assertEquals(listOf(f.note.id, f.first, "1", "false"), request.drop(1))
            val operation = request[0]
            assertEquals(old.copy(prompt = raw), KnowledgeCodec.decode(payload))
            runBlocking { assertNull(database.knowledge().receipt(operation)) }
            // Reconstruct a real VM from its serialized pending command, then
            // restore actual ancestor/child Compose saved state by recreation.
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
            compose.activityRule.scenario.recreate()
            waitFor("question-edit-retry")
            assertDraft("question-edit-prompt", raw)
            assertParentDrafts()
            compose.onNodeWithTag("question-edit-cancel").assertIsNotEnabled()
            compose.onNodeWithTag("card-properties-cancel").assertIsNotEnabled()
            assertEquals(request, compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() })
            assertArrayEquals(payload, compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")) })
            assertEquals(unchanged, authorStamp())
            shot("unknown-reconstructed")
            failBeforeReceipt.set(false)
            tap("question-edit-retry")
            waitGone("question-edit-dialog")
            assertParentDrafts()
            assertCommit(f, f.first, before, receiptIds, old.copy(prompt = raw), expectedOperation = operation)
            compose.runOnIdle { assertNull(saved.get<ArrayList<String>>("knowledge.request")) }
            val after = authorStamp()
            val original = KnowledgeCommand(operation, f.note.id, f.first, 1, KnowledgeCodec.decode(payload))
            assertEquals(KnowledgeOutcome.Success(f.first), runBlocking { app.knowledge.outcome(original) })
            assertEquals("Replaying the exact original receipt must not add another revision", after, authorStamp())
            shot("unknown-original-retried")
        } finally {
            failBeforeReceipt.set(false)
            compose.runOnIdle {
                if (compose.activity.viewModelStore.get(key) === owned) {
                    compose.activity.viewModelStore.put(key,
                        KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks))
                }
            }
        }
    }

    /** Deterministic VM submission-success-before-UI-consumption boundary. The
     * actual Activity stays in the library, so no KnowledgeWorkspace consumes
     * the result. This verifies the real saved VM protocol, not an artificially
     * paused Compose frame or a claim of complete operating-system process death.
     */
    @Test fun realCommittedSuccessRestoresUnconsumedIdentityWithoutAnotherRevisionOrReceipt() = checked("success-unconsumed") {
        val f = seed()
        val old = rows(f.first)
        val question = old.data() as KnowledgeData.Question
        assertEquals(1L, old.revision)
        assertEquals(ManualState.UNDERSTOOD, question.state)
        val expected = question.copy(prompt = "  已提交但界面尚未消费的原题稿\n恢复完成身份，不重新保存。  ")
        val before = authorStamp(Allowed(f.note.id, f.first))
        val receiptIds = receipts(f.first)
        val key = "question-maintenance-unconsumed-" + f.note.id
        val saved = SavedStateHandle()
        var owned: KnowledgeViewModel? = null
        fun mount(state: SavedStateHandle): KnowledgeViewModel = compose.runOnIdle {
            KnowledgeViewModel(app.knowledge, state, app.resourcePacks).also {
                compose.activity.viewModelStore.put(key, it)
                owned = it
            }
        }
        fun copyState(state: SavedStateHandle): SavedStateHandle = compose.runOnIdle {
            SavedStateHandle(state.keys().associateWith { savedKey ->
                when (val value = state.get<Any?>(savedKey)) {
                    is ByteArray -> value.copyOf()
                    is ArrayList<*> -> ArrayList(value)
                    else -> value
                }
            })
        }
        try {
            compose.runOnIdle { assertNull(compose.activity.viewModelStore.get(key)) }
            val actor = mount(saved)
            val operation = compose.runOnIdle {
                checkNotNull(actor.submit(f.note.id, expected, old))
            }
            compose.waitUntil(15_000) {
                compose.runOnIdle {
                    val state = actor.ui.value
                    !state.busy && !state.unknown && state.completed == f.first && state.completedOperation == operation
                }
            }
            compose.runOnIdle {
                assertNull(actor.pendingOperationId)
                assertNull(saved.get<ArrayList<String>>("knowledge.request"))
                assertEquals(f.first, saved.get<String>("knowledge.completed"))
                assertEquals(operation, saved.get<String>("knowledge.completedOperation"))
            }
            assertCommit(f, f.first, before, receiptIds, expected, expectedOperation = operation)
            val afterCommit = authorStamp()
            val restoredSaved = copyState(saved)
            val restored = mount(restoredSaved)
            compose.runOnIdle {
                assertEquals(f.first, restored.ui.value.completed)
                assertEquals(operation, restored.ui.value.completedOperation)
                assertFalse(restored.ui.value.busy)
                assertFalse(restored.ui.value.unknown)
                assertNull(restored.pendingOperationId)
                restored.retry()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(f.first, restored.ui.value.completed)
                assertEquals(operation, restored.ui.value.completedOperation)
                assertFalse(restored.ui.value.busy)
                assertFalse(restored.ui.value.unknown)
                assertEquals(f.first, restoredSaved.get<String>("knowledge.completed"))
                assertEquals(operation, restoredSaved.get<String>("knowledge.completedOperation"))
            }
            assertEquals("Restoring and retrying an already completed command must not write any author row",
                afterCommit, authorStamp())
            assertEquals(receiptIds + operation, receipts(f.first))
            compose.runOnIdle {
                restored.consumed()
                assertNull(restored.ui.value.completed)
                assertNull(restored.ui.value.completedOperation)
                assertNull(restoredSaved.get<String>("knowledge.completed"))
                assertNull(restoredSaved.get<String>("knowledge.completedOperation"))
            }
            val consumedSaved = copyState(restoredSaved)
            val afterConsumption = mount(consumedSaved)
            compose.runOnIdle {
                assertNull(afterConsumption.ui.value.completed)
                assertNull(afterConsumption.ui.value.completedOperation)
                assertNull(afterConsumption.pendingOperationId)
                assertFalse(afterConsumption.ui.value.unknown)
                afterConsumption.retry()
            }
            compose.waitForIdle()
            assertEquals("Consuming the result and restoring again must not resurrect it or commit twice",
                afterCommit, authorStamp())
            runBlocking {
                assertEquals(listOf(2L, 1L), database.knowledge().revisions(f.first).map { it.revision })
                assertEquals(expected, checkNotNull(database.knowledge().get(f.first)).data())
            }
        } finally {
            compose.runOnIdle {
                if (compose.activity.viewModelStore.get(key) === owned) {
                    compose.activity.viewModelStore.put(key,
                        KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks))
                }
            }
        }
    }

    @Test fun actualReadOnlyEntryShowsSavedQuestionsButCannotEditRemoveOrSaveAuthors() = checked("readonly") {
        val f = seed()
        compose.runOnIdle { ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(f.note) }
        compose.singlePageEditor()
        compose.waitForSavedInk()
        tap("quick-settings")
        tap("settings-readonly")
        waitFor("reading-toolbar")
        compose.runOnIdle {
            val locks = ViewModelProvider(compose.activity, BookReadLockViewModel.Factory())
            assertTrue(locks["read-lock-" + f.note.id, BookReadLockViewModel::class.java].readOnly.value)
        }
        tap("quick-settings")
        tap("settings-knowledge")
        openProperties(f, reader = true)
        val before = authorStamp()
        listOf(f.first to f.firstPrompt, f.second to f.secondPrompt).forEach { (question, prompt) ->
            val original = compose.onNodeWithTag("question-prompt-" + question)
            runCatching { original.performScrollTo() }
            original.assertIsDisplayed().assertTextEquals(prompt)
            val edit = compose.onNodeWithTag("question-edit-" + question)
            runCatching { edit.performScrollTo() }
            edit.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("question-edit-dialog").assertDoesNotExist()
            val remove = compose.onNodeWithTag("question-remove-" + question)
            runCatching { remove.performScrollTo() }
            remove.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("question-remove-dialog").assertDoesNotExist()
        }
        listOf("card-properties-tags", "card-properties-alias", "card-properties-question",
            "card-question-add", "card-properties-save").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        replace("card-question-search", "样本空间")
        hideKeyboard()
        compose.onNodeWithTag("card-question-search-count").assertTextEquals("显示 1 / 2 道已保存问题")
        compose.onNodeWithTag("question-row-" + f.second).assertDoesNotExist()
        tap("card-question-search-clear")
        compose.onNodeWithTag("question-row-" + f.second).assertExists()
        assertEquals(before, authorStamp())
        shot("readonly-questions")
        tap("card-properties-cancel")
        waitGone("card-properties-dialog")
        assertEquals(before, authorStamp())
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }

    @Test fun narrow375Font165UsesPhysical48dpActionsWithActualKeyboardAndLongQuestion() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        val oldHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("wm size 750x1600")
            shell("wm density 320")
            shell("settings put system font_scale 1.65")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val c = compose.activity.resources.configuration
                abs(c.screenWidthDp - 375) <= 4 && abs(c.fontScale - 1.65f) < .02f
            }
            checked("375-font1.65-keyboard") {
                val f = seed(longText = true)
                openProperties(f)
                val old = rows(f.first).data() as KnowledgeData.Question
                val before = authorStamp(Allowed(f.note.id, f.first))
                val receiptIds = receipts(f.first)
                fullyVisible48("question-edit-" + f.first, "card-properties-dialog")
                tap("question-edit-" + f.first)
                waitFor("question-edit-dialog")
                val field = compose.onNodeWithTag("question-edit-prompt")
                runCatching { field.performScrollTo() }
                field.assertIsDisplayed().performTouchInput { click() }
                compose.waitUntil(15_000) { keyboardVisible() }
                val raw = "  窄屏原文开头\n" + f.firstPrompt + "\n键盘显示时仍能实际保存。  "
                field.performTextReplacement(raw)
                assertDraft("question-edit-prompt", raw)
                fullyVisible48("question-edit-cancel", "question-edit-dialog")
                fullyVisible48("question-edit-save", "question-edit-dialog")
                assertTrue("The actual IME must remain visible for the action test", keyboardVisible())
                shot("375-font1.65-edit-keyboard")
                compose.onNodeWithTag("question-edit-save").performTouchInput { click() }
                waitGone("question-edit-dialog")
                waitFor("card-properties-dialog")
                assertCommit(f, f.first, before, receiptIds, old.copy(prompt = raw))
                val unchanged = authorStamp()
                fullyVisible48("question-remove-" + f.second, "card-properties-dialog")
                tap("question-remove-" + f.second)
                waitFor("question-remove-dialog")
                compose.onNodeWithTag("question-remove-prompt").assertTextEquals(f.secondPrompt)
                fullyVisible48("question-remove-confirm", "question-remove-dialog")
                fullyVisible48("question-remove-cancel", "question-remove-dialog")
                shot("375-font1.65-remove-confirm")
                compose.onNodeWithTag("question-remove-cancel").performTouchInput { click() }
                waitGone("question-remove-dialog")
                assertEquals(unchanged, authorStamp())
                val secondBefore = authorStamp(Allowed(f.note.id, f.second))
                val secondReceipts = receipts(f.second)
                val second = rows(f.second).data() as KnowledgeData.Question
                tap("question-remove-" + f.second)
                waitFor("question-remove-dialog")
                fullyVisible48("question-remove-confirm", "question-remove-dialog")
                compose.onNodeWithTag("question-remove-confirm").performTouchInput { click() }
                waitGone("question-remove-dialog")
                waitGone("question-row-" + f.second)
                assertCommit(f, f.second, secondBefore, secondReceipts, second, removed = true)
                compose.onNodeWithTag("question-prompt-" + f.first).assertTextEquals(raw)
            }
        } finally {
            try { hideKeyboard() }
            finally {
                shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
                shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
                shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont
                    else "settings delete system font_scale")
                shell(if (oldHardKeyboard.matches(Regex("""[0-9]+""")))
                    "settings put secure show_ime_with_hard_keyboard " + oldHardKeyboard
                    else "settings delete secure show_ime_with_hard_keyboard")
            }
        }
    }
}
