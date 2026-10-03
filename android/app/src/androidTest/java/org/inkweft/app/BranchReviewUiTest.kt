package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
import java.util.concurrent.atomic.AtomicBoolean

/** Uses real map, library and card-property routes; never mounts an isolated review dialog. */
class BranchReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private fun id() = UUID.randomUUID().toString()

    @After fun closeProbe() {
        probeDatabase?.close()
        probeDatabase = null
    }

    private data class Fixture(
        val note: Note,
        val map: String,
        val branch: String,
        val card: String,
        val child: String,
        val questionPrefix: String,
        val prompts: List<String>,
        val answer: String,
    ) {
        fun questionId(ordinal: Int) = questionPrefix + ordinal.toString().padStart(12, '0')
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.revealAction(tag)
        waitFor(tag)
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun fixture(longText: Boolean = false, open: Boolean = true): Fixture {
        waitFor("new-note")
        val note = runBlocking {
            app.workspaceRepository.create("分支回忆验收 " + id().take(6), false, PaperStyle.RULED)
        }
        val map = id()
        val otherMap = id()
        val branch = id()
        val outsideBranch = id()
        val card = id()
        val child = id()
        val prefix = id().take(24)
        val prompts = if (longText) listOf(
            "第一题开头必须可见\n" + (1..30).joinToString("\n") { "条件问题 " + it + "：先限定样本空间，再说明分母为何变化。" },
            "第二题开头必须可见\n" + (1..30).joinToString("\n") { "应用问题 " + it + "：请逐步解释条件概率与独立事件的区别。" },
        ) else listOf("冻结原题一", "冻结原题二")
        val answer = if (longText) "答案暗号 BRANCH-ANSWER\n" +
            (1..48).joinToString("\n") { "答案段 " + it + "：应先确定条件事件，检查交集与分母，再通过树状分支核对每一步。" }
        else "冻结答案暗号 BRANCH-ANSWER"
        runBlocking {
            val stroke = InkStroke(id(), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(100f, 400f, 0), InkSample(220f, 420f, 30)))
            app.inkRepository.save(CommitInk(id(), note.id, 0, InkMutation.Add(stroke)))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, map, 0,
                KnowledgeData.MapDefinition("目标学习图", structures = listOf(
                    MapStructure(branch, null, "目标结构分支", 40.0, 80.0),
                    MapStructure(outsideBranch, null, "范围外分支", 40.0, 600.0),
                ))))
            app.knowledge.submit(KnowledgeCommand(id(), note.id, otherMap, 0,
                KnowledgeData.MapDefinition("其他图")))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = card,
                nodeId = child, parentId = branch, title = "同一知识卡", body = answer,
                x = 300.0, y = 80.0, mapId = map,
                source = StudySourceDraft(note.id, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = card,
                nodeId = id(), parentId = branch, x = 300.0, y = 208.0, mapId = map))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(),
                nodeId = id(), parentId = branch, title = "未设题卡片", body = "尚无问题",
                x = 300.0, y = 336.0, mapId = map))
            val outsideCard = id()
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = outsideCard,
                nodeId = id(), parentId = outsideBranch, title = "其他分支卡", body = "范围外答案",
                x = 300.0, y = 600.0, mapId = map))
            val otherCard = id()
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = otherCard,
                nodeId = id(), title = "其他图卡", body = "其他图答案", mapId = otherMap))
            listOf(card to prompts[0], card to prompts[1],
                outsideCard to "范围外问题", otherCard to "其他图问题").forEachIndexed { index, (owner, prompt) ->
                app.knowledge.submit(KnowledgeCommand(id(), note.id,
                    prefix + (index + 1).toString().padStart(12, '0'), 0,
                    KnowledgeData.Question(owner, prompt)))
            }
        }
        val result = Fixture(note, map, branch, card, child, prefix, prompts, answer)
        if (open) {
            compose.runOnIdle { ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note) }
            compose.singlePageEditor()
            compose.waitForSavedInk()
            tap("quick-study")
            tap("study-map-picker")
            tap("study-map-" + map)
            waitFor("study-map")
        }
        return result
    }

    private fun map(): MindMapView {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is MindMapView && current.isShown) return current
            if (current is ViewGroup) repeat(current.childCount) { queue.add(current.getChildAt(it)) }
        }
        error("Visible native map is missing")
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
    }

    private fun openBranch(f: Fixture, folded: Boolean = false) {
        select(f.branch)
        if (folded) {
            tap("node-more")
            tap("node-menu-fold")
            compose.runOnIdle { assertNull("Fixture descendant must be visibly folded", map().nodeBounds(f.child)) }
        }
        tap("node-more")
        tap("node-review-branch")
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts")
            .assertTextEquals("2 张卡片 · 2 道问题 · 1 张未设题卡片")
    }

    private fun assertHidden() {
        compose.onAllNodes(hasTestTag("review-answer") and hasAnyAncestor(hasTestTag("manual-review")))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("BRANCH-ANSWER", substring = true) and
            hasAnyAncestor(hasTestTag("manual-review")), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("review-open-source") and hasAnyAncestor(hasTestTag("manual-review")))
            .assertCountEquals(0)
    }

    private fun assertQuestion(prompt: String, position: Int, total: Int) {
        waitFor("review-question")
        compose.onNodeWithTag("review-question").assertTextEquals(prompt)
        compose.onNodeWithText("手动回忆 · " + position + " / " + total, useUnmergedTree = true).assertExists()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** Checks author records and revision/receipt counts, rather than only visible review state. */
    private fun authorStamp(book: String): List<String> = runBlocking {
        probe.withTransaction {
            buildList {
                probe.study().cards(book).forEach { add("card:" + it) }
                probe.study().nodes(book).forEach { add("node:" + it) }
                probe.knowledge().forBook(book).forEach {
                    add("knowledge:" + it.id + ":" + it.revision + ":" + it.removed + ":" + sha(it.payload))
                }
                probe.study().sourceIds(book).sorted().forEach { id ->
                    val source = checkNotNull(probe.study().source(id))
                    add("source:" + listOf(source.cardId, source.pageId, source.inkRevision, source.left,
                        source.top, source.right, source.bottom, source.strokeIds, sha(source.snapshot)).joinToString("|"))
                }
                add("ink-page:" + probe.ink().page(book))
                probe.ink().strokes(book).forEach {
                    add("ink:" + it.id + ":" + it.visible + ":" + it.createdRevision + ":" + sha(it.payload))
                }
                val queries = listOf(
                    "SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?",
                    "SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?",
                    "SELECT COUNT(*) FROM study_receipts WHERE notebookId=?",
                    "SELECT COUNT(*) FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=?",
                    "SELECT COUNT(*) FROM ink_receipts WHERE noteId=?",
                )
                queries.forEachIndexed { index, sql ->
                    probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
                        check(cursor.moveToFirst())
                        add("audit-" + index + ":" + cursor.getLong(0))
                    }
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(app.getExternalFilesDir(null), "branch-review-" + name + ".png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun foldedStructureBranchDeduplicatesCardsButKeepsTheirIndependentQuestions() {
        val f = fixture()
        val before = authorStamp(f.note.id)
        openBranch(f, folded = true)
        assertEquals(before, authorStamp(f.note.id))
        tap("branch-review-start")
        assertQuestion(f.prompts[0], 1, 2)
        assertHidden()
        shot("folded-hidden")
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 2)
        assertHidden()
        tap("branch-review-skip")
        waitFor("branch-review-ended")
        assertEquals("Preparing, starting and skipping must not write author records", before, authorStamp(f.note.id))
    }

    @Test fun frozenQuestionAndAnswerSurviveEditsRecreationAndSourceReturnWithoutRating() {
        val f = fixture()
        openBranch(f)
        tap("branch-review-start")
        assertQuestion(f.prompts[0], 1, 2)
        assertHidden()
        runBlocking {
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card,
                expectedRevision = 1, title = "后续新标题", body = "后续新答案不应替换本轮"))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, f.questionId(1), 1,
                KnowledgeData.Question(f.card, "后续新题不应替换本轮")))
            app.knowledge.submit(KnowledgeCommand(id(), f.note.id, f.questionId(5), 0,
                KnowledgeData.Question(f.card, "后续新增题不应进入本轮")))
        }
        val afterEdit = authorStamp(f.note.id)
        compose.activityRule.scenario.recreate()
        assertQuestion(f.prompts[0], 1, 2)
        assertHidden()
        tap("reveal-answer")
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        tap("review-open-source")
        waitFor("review-source-canvas")
        shot("source")
        compose.activityRule.scenario.recreate()
        waitFor("review-source-canvas")
        tap("return-to-review")
        assertQuestion(f.prompts[0], 1, 2)
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        assertEquals("Reveal and read-only source return must not submit a rating", afterEdit, authorStamp(f.note.id))
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 2)
        assertHidden()
        tap("branch-review-skip")
        waitFor("branch-review-ended")
        assertEquals(afterEdit, authorStamp(f.note.id))
    }

    @Test fun currentCardReviewKeepsItsQuestionsAndReturnsToTheSameScrolledDetailsAfterSourceAndRecreation() {
        val f = fixture(longText = true)
        val before = authorStamp(f.note.id)
        select(f.child)
        tap("node-more")
        tap("node-view-content")
        tap("card-positions")
        compose.onNodeWithTag("card-reference-content").assertExists()
        fun inspectorScroll(): Float = compose.onAllNodes(hasScrollAction() and
            hasAnyAncestor(hasTestTag("study-card-details")), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() }.single()
        val originalScroll = inspectorScroll()
        assertTrue("The fixture exercises retained inspector scroll", originalScroll > 0f)
        val originalViewport = compose.runOnIdle { map().snapshotViewport() }
        tap("card-review")
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts").assertTextEquals("1 张卡片 · 2 道问题 · 0 张未设题卡片")
        compose.onNodeWithTag("branch-review-scope").assertTextEquals("同一知识卡")
        compose.activityRule.scenario.recreate()
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts").assertTextEquals("1 张卡片 · 2 道问题 · 0 张未设题卡片")
        compose.onNodeWithTag("branch-review-scope").assertTextEquals("同一知识卡")
        tap("branch-review-start")
        assertQuestion(f.prompts[0], 1, 2)
        assertHidden()
        tap("reveal-answer")
        tap("review-open-source")
        waitFor("review-source-canvas")
        compose.activityRule.scenario.recreate()
        waitFor("review-source-canvas")
        tap("return-to-review")
        assertQuestion(f.prompts[0], 1, 2)
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 2)
        tap("branch-review-skip")
        waitFor("branch-review-ended")
        tap("branch-review-close")
        waitFor("study-card-details")
        compose.onNodeWithTag("card-reference-content").assertExists()
        assertEquals(originalScroll, inspectorScroll(), 1f)
        compose.runOnIdle {
            assertEquals(f.note.id, ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
            val study = ViewModelProvider(compose.activity)["study-${f.note.id}", StudyViewModel::class.java]
            assertEquals(f.map, study.mapId.value)
            assertEquals(f.child, study.selectedByMap[f.map])
            assertEquals(originalViewport, map().snapshotViewport())
        }
        tap("card-back")
        tap("node-more")
        tap("node-review-card")
        waitFor("branch-review-counts")
        compose.onNodeWithTag("branch-review-counts").assertTextEquals("1 张卡片 · 2 道问题 · 0 张未设题卡片")
        tap("branch-review-close")
        compose.runOnIdle { assertEquals(f.child, map().selectedNodeId); assertEquals(originalViewport, map().snapshotViewport()) }
        assertEquals("Both current-card entries and source return are read-only until a rating is submitted", before, authorStamp(f.note.id))
    }

    @Test fun sourceOnlyCardShowsItsSavedExcerptAfterRevealWithoutWritingAResult() {
        val f = fixture()
        runBlocking {
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card,
                expectedRevision = 1, title = "原迹答案卡", body = ""))
        }
        val before = authorStamp(f.note.id)
        compose.waitUntil(15_000) {
            compose.runOnIdle { ViewModelProvider(compose.activity)["study-${f.note.id}", StudyViewModel::class.java]
                .ui.value.cards.any { it.id == f.card && it.revision == 2L } }
        }
        select(f.child)
        tap("node-more")
        tap("node-review-card")
        tap("branch-review-start")
        assertQuestion(f.prompts[0], 1, 2)
        assertHidden()
        val explanation = "此卡未填写文字答案；请在下方「摘录」查看保存原迹，或打开来源核对。"
        compose.onAllNodesWithText(explanation).assertCountEquals(0)
        compose.onAllNodesWithTag("recall-context-excerpt-canvas").assertCountEquals(0)
        tap("reveal-answer")
        compose.onNodeWithTag("review-answer").assertTextEquals(explanation)
        runCatching { compose.onNodeWithTag("recall-context").performScrollTo() }
        tap("recall-context-tab-excerpt")
        waitFor("recall-context-excerpt-canvas")
        compose.onNodeWithTag("recall-context-excerpt-canvas").performScrollTo().assertIsDisplayed()
        tap("branch-review-close")
        assertEquals(before, authorStamp(f.note.id))
    }

    @Test fun changedAnswerRejectsExplicitMarkWithoutOverwritingOrAdvancingThenAllowsSkip() {
        val f = fixture()
        openBranch(f)
        tap("branch-review-start")
        tap("reveal-answer")
        runBlocking {
            app.study.submit(StudyCommand(id(), f.note.id, StudyAction.EDIT, cardId = f.card,
                expectedRevision = 1, title = "同一知识卡", body = "评分前已修改的答案"))
        }
        val beforeMark = authorStamp(f.note.id)
        tap("branch-review-mark-UNDERSTOOD")
        waitFor("branch-review-conflict")
        assertQuestion(f.prompts[0], 1, 2)
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        compose.onNodeWithTag("branch-review-mark-UNDERSTOOD").assertIsNotEnabled()
        assertEquals(beforeMark, authorStamp(f.note.id))
        runBlocking {
            val question = checkNotNull(probe.knowledge().get(f.questionId(1)))
            assertEquals(1L, question.revision)
            assertEquals(ManualState.REVIEW, (question.data() as KnowledgeData.Question).state)
            assertEquals("评分前已修改的答案", checkNotNull(probe.study().card(f.card)).body)
        }
        shot("conflict")
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 2)
        assertHidden()
        assertEquals(beforeMark, authorStamp(f.note.id))
    }

    @Test fun unknownMarkSurvivesRecreationAndRetriesOriginalOperationBeforeAdvancingOnce() {
        val f = fixture()
        val faultDatabase = NoteDatabase.open(app)
        val failBeforeReceipt = AtomicBoolean(true)
        val saved = SavedStateHandle()
        val key = "branch-review-" + f.note.id
        val store = compose.activity.viewModelStore
        var previous: ViewModel? = null
        try {
            compose.runOnIdle {
                previous = store.get(key)
                store.put(key, KnowledgeViewModel(KnowledgeRepository(faultDatabase) { stage ->
                    if (stage == KnowledgeFault.BEFORE_RECEIPT && failBeforeReceipt.get()) {
                        throw java.io.IOException("Synthetic failure before the rating receipt")
                    }
                }, saved, app.resourcePacks))
            }
            val before = authorStamp(f.note.id)
            fun receipts() = runBlocking {
                faultDatabase.withTransaction {
                    faultDatabase.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                        "SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?", arrayOf(f.note.id))).use {
                        check(it.moveToFirst())
                        it.getLong(0)
                    }
                }
            }
            val receiptCount = receipts()
            openBranch(f)
            tap("branch-review-start")
            tap("reveal-answer")
            waitFor("review-open-source")
            tap("branch-review-mark-UNDERSTOOD")
            waitFor("branch-review-retry")
            assertQuestion(f.prompts[0], 1, 2)
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            listOf("branch-review-close", "branch-review-skip", "branch-review-mark-REVIEW",
                "branch-review-mark-UNDERSTOOD", "review-open-source").forEach {
                compose.onNodeWithTag(it).assertIsNotEnabled()
            }
            assertEquals(before, authorStamp(f.note.id))
            val request = checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList()
            val operation = request[0]
            assertEquals(f.questionId(1), request[2])
            assertEquals(1L, saved.get<Long>("knowledge.reviewCardRevision"))
            runBlocking { assertNull(faultDatabase.knowledge().receipt(operation)) }
            compose.activityRule.scenario.recreate()
            assertQuestion(f.prompts[0], 1, 2)
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            compose.onNodeWithTag("branch-review-close").assertIsNotEnabled()
            compose.onNodeWithTag("branch-review-skip").assertIsNotEnabled()
            assertEquals(request, checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList())
            failBeforeReceipt.set(false)
            tap("branch-review-retry")
            assertQuestion(f.prompts[1], 2, 2)
            assertHidden()
            runBlocking {
                val committed = checkNotNull(faultDatabase.knowledge().get(f.questionId(1)))
                assertEquals(2L, committed.revision)
                assertEquals(ManualState.UNDERSTOOD, (committed.data() as KnowledgeData.Question).state)
                assertEquals(f.questionId(1), checkNotNull(faultDatabase.knowledge().receipt(operation)).resultId)
                assertNull(faultDatabase.knowledge().revision(f.questionId(1), 3))
                assertEquals(1L, checkNotNull(faultDatabase.knowledge().get(f.questionId(2))).revision)
                assertEquals(1L, checkNotNull(faultDatabase.study().card(f.card)).revision)
                assertEquals(1L, checkNotNull(faultDatabase.ink().page(f.note.id)).revision)
            }
            assertEquals(receiptCount + 1, receipts())
            assertNull(saved.get<ArrayList<String>>("knowledge.request"))
            shot("unknown-retried-next-hidden")
            tap("branch-review-close")
        } finally {
            try {
                compose.runOnIdle {
                    store.put(key, previous ?: KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks))
                }
            } finally {
                faultDatabase.close()
            }
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }

    @Test fun narrow375LargeTextScrollsToEveryActionAndNextQuestionStartsAtTopHidden() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600")
            shell("wm density 320")
            shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val config = compose.activity.resources.configuration
                kotlin.math.abs(config.screenWidthDp - 375) <= 4 && kotlin.math.abs(config.fontScale - 1.6f) < .02f
            }
            val f = fixture(longText = true)
            openBranch(f)
            tap("branch-review-start")
            assertQuestion(f.prompts[0], 1, 2)
            assertHidden()
            tap("reveal-answer")
            compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            val before = authorStamp(f.note.id)
            tap("review-open-source")
            waitFor("review-source-canvas")
            tap("return-to-review")
            listOf("branch-review-mark-REVIEW", "branch-review-mark-UNDERSTOOD", "branch-review-skip").forEach { tag ->
                val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                val button = node.fetchSemanticsNode().boundsInRoot
                val dialog = compose.onNodeWithTag("manual-review").fetchSemanticsNode().boundsInRoot
                assertTrue(tag + " must be fully inside the visible dialog", button.top >= dialog.top &&
                    button.bottom <= dialog.bottom && button.left >= dialog.left && button.right <= dialog.right)
            }
            val scrollMatcher = hasScrollAction() and hasAnyAncestor(hasTestTag("manual-review")) and hasAnyDescendant(hasTestTag("review-question"))
            val priorScroll = compose.onNode(scrollMatcher).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Long answer must actually scroll before skipping", priorScroll > 0f)
            shot("375-font1.6-actions")
            tap("branch-review-skip")
            assertQuestion(f.prompts[1], 2, 2)
            assertHidden()
            val nextScroll = compose.onNode(scrollMatcher).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("Next question must show its opening, rather than inherit the previous answer offset", 0f, nextScroll, .5f)
            compose.onNodeWithTag("review-question").assertIsDisplayed()
            compose.onNodeWithTag("branch-review-close").assertIsDisplayed().assertIsEnabled()
            assertEquals(before, authorStamp(f.note.id))
            shot("375-font1.6-next-hidden")
            tap("branch-review-close")
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
            shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont
                else "settings delete system font_scale")
        }
    }

    private fun startNotebookReview(f: Fixture) {
        tap("knowledge-tab-4")
        tap("manual-review-start")
        assertQuestion(f.prompts[0], 1, 4)
        assertHidden()
    }

    @Test fun libraryReviewAncestorRecreatesSameFixedQuestionAndRevealState() {
        val f = fixture(open = false)
        val before = authorStamp(f.note.id)
        if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
        compose.onNodeWithText("复习", useUnmergedTree = true).performScrollTo().performClick()
        val target = hasText(f.note.title) and hasAnyAncestor(isDialog())
        compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(target).performScrollTo().performClick()
        startNotebookReview(f)
        tap("reveal-answer")
        compose.activityRule.scenario.recreate()
        assertQuestion(f.prompts[0], 1, 4)
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        shot("library-recreated")
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 4)
        assertHidden()
        assertEquals(before, authorStamp(f.note.id))
    }

    @Test fun bookKnowledgeAndCardPropertiesAncestorsRestoreWholeNotebookRecall() {
        val f = fixture()
        val before = authorStamp(f.note.id)
        tap("study-close")
        tap("quick-settings")
        tap("settings-knowledge")
        startNotebookReview(f)
        compose.activityRule.scenario.recreate()
        assertQuestion(f.prompts[0], 1, 4)
        assertHidden()
        tap("branch-review-close")
        compose.onNodeWithText("返回笔记", useUnmergedTree = true).performClick()
        tap("quick-study")
        waitFor("study-map")
        select(f.child)
        tap("node-more")
        tap("node-view-content")
        tap("card-properties")
        startNotebookReview(f)
        tap("branch-review-skip")
        assertQuestion(f.prompts[1], 2, 4)
        tap("reveal-answer")
        compose.activityRule.scenario.recreate()
        assertQuestion(f.prompts[1], 2, 4)
        compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        assertEquals(before, authorStamp(f.note.id))
        shot("card-properties-recreated")
    }
}
