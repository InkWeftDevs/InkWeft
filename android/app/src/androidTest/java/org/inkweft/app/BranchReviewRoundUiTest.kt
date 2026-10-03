// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import org.inkweft.core.*
import org.inkweft.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real MainActivity and its Room database; synthetic author commands and physical Compose touch input. */
class BranchReviewRoundUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val support by lazy { SelectAwaitTestSupport(compose) }
    private val app get() = support.app
    private val database get() = WorkspaceRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.workspaceRepository) as NoteDatabase
    private fun id() = UUID.randomUUID().toString()
    private data class Fixture(val source: SelectAwaitTestSupport.Fixture, val frozen: List<FrozenBranchReviewQuestion>)

    @Before fun captureSettings() = support.captureSettings()
    @After fun restoreSettings() { compose.mainClock.autoAdvance = true; support.closeAndRestoreSettings() }

    private fun seed(count: Int, state: ManualState = ManualState.REVIEW, longText: Boolean = false): Fixture {
        val source = support.seed()
        runBlocking {
            repeat(count) { index -> app.knowledge.submit(KnowledgeCommand(id(), source.note.id, id(), 0,
                KnowledgeData.Question(source.card, "RR67 独立问题 " + index +
                    (if (longText && index == count - 1) "\n" + "长问题条件仍须完整保留。".repeat(100) else ""), state))) }
        }
        return Fixture(source, runBlocking { app.branchReview.load(app.branchReview.prepareNotebook(source.note.id)) })
    }

    private fun start() {
        support.tap("quick-settings"); support.tap("settings-knowledge"); support.waitFor("knowledge-workspace")
        support.tap("knowledge-tab-4"); support.waitFor("notebook-review-panel")
        compose.onNodeWithTag("notebook-review-panel").performScrollToNode(hasTestTag("manual-review-start"))
        support.tap("manual-review-start")
        support.waitFor("review-question")
    }

    private fun question(f: Fixture, index: Int) {
        val prompt = (f.frozen[index].question.data() as KnowledgeData.Question).prompt
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("review-question").assertTextEquals(prompt) }.isSuccess }
        compose.waitForIdle()
        compose.onNodeWithTag("review-question").assertTextEquals(prompt)
    }

    private fun hidden() {
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-enter").assertDoesNotExist()
        compose.onNodeWithTag("review-card-title").assertDoesNotExist()
        compose.onNodeWithTag("review-source-canvas").assertDoesNotExist()
        compose.onNodeWithTag("recall-context-source-canvas").assertDoesNotExist()
        compose.onNodeWithTag("review-clues-hidden").assertExists()
    }

    private fun result(total: Int, understood: Int, review: Int, skipped: Int, rejected: Int = 0) {
        support.waitFor("branch-review-ended")
        compose.onNodeWithTag("branch-review-result-total").assertTextEquals("本轮共 $total 题")
        compose.onNodeWithTag("branch-review-result-understood").assertTextEquals("本次已理解 · $understood")
        compose.onNodeWithTag("branch-review-result-review").assertTextEquals("仍需复习 · $review")
        compose.onNodeWithTag("branch-review-result-skipped").assertTextEquals("未标记跳过 · ${skipped + rejected}")
        compose.onNodeWithTag("branch-review-result-rejected").assertTextEquals("其中普通跳过 $skipped · 未提交后跳过 $rejected")
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.onNodeWithTag("review-question").assertDoesNotExist()
    }

    private fun detailNode(tag: String): SemanticsNodeInteraction {
        support.waitFor("branch-review-details")
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("branch-review-details-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertExists()
        }.isSuccess }
        return compose.onNodeWithTag(tag)
    }
    private fun detailTap(tag: String) { detailNode(tag).assertIsDisplayed().performTouchInput { click() }; compose.waitForIdle() }

    private fun authors(f: Fixture) = support.authorStamp(f.source.note.id) to support.authorStamp(f.source.unrelated.id)
    private fun mark(state: ManualState) { support.tap("reveal-answer"); support.tap("branch-review-mark-" + state.name) }
    private fun blockedPending() {
        for (tag in listOf("branch-review-close", "branch-review-skip", "branch-review-mark-REVIEW", "branch-review-mark-UNDERSTOOD"))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("branch-review-ended").assertDoesNotExist()
        compose.onNodeWithTag("branch-review-retry-review").assertDoesNotExist()
    }

    @Test fun mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling() {
        val f = seed(4, longText = true); start(); question(f, 0); hidden()
        mark(ManualState.UNDERSTOOD); question(f, 1); hidden()
        mark(ManualState.REVIEW); question(f, 2); hidden()
        support.tap("branch-review-skip"); question(f, 3); hidden()
        val last = f.frozen[3].question
        runBlocking { app.knowledge.submit(KnowledgeCommand(id(), last.notebookId, last.id, last.revision,
            (last.data() as KnowledgeData.Question).copy(prompt = "外部已修改，旧题不得覆盖"))) }
        val beforeRejection = authors(f)
        mark(ManualState.REVIEW); support.waitFor("branch-review-conflict")
        question(f, 3)
        assertEquals(beforeRejection, authors(f))
        support.tap("branch-review-skip"); result(4, 1, 1, 1, 1)
        compose.activityRule.scenario.recreate(); result(4, 1, 1, 1, 1)
        val beforeDetails = authors(f)
        support.tap("branch-review-open-details")
        detailNode("branch-review-detail-${f.frozen.first().reference.questionId}")
        listOf("本次已理解", "仍需复习", "普通跳过 · 未标记", "未提交后跳过 · 未标记").forEachIndexed { index, label ->
            val questionId = f.frozen[index].reference.questionId
            detailNode("branch-review-detail-$questionId")
            compose.onNodeWithTag("branch-review-detail-outcome-$questionId").assertTextEquals("第 ${index + 1} 题 · $label")
            compose.onNodeWithTag("branch-review-detail-prompt-$questionId")
                .assertTextEquals((f.frozen[index].question.data() as KnowledgeData.Question).prompt)
        }
        compose.onNodeWithTag("branch-review-details-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("branch-review-details-close").assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasText(f.source.body) and hasAnyAncestor(hasTestTag("branch-review-details"))).assertDoesNotExist()
        detailTap("branch-review-details-review")
        compose.onNodeWithTag("branch-review-details-count").assertTextEquals("本范围 1 / 4 道题")
        compose.onNodeWithTag("branch-review-detail-${f.frozen[1].reference.questionId}").assertExists()
        compose.onNodeWithTag("branch-review-detail-${f.frozen[0].reference.questionId}").assertDoesNotExist()
        detailTap("branch-review-details-skipped")
        compose.activityRule.scenario.recreate()
        detailNode("branch-review-details-count").assertTextEquals("本范围 2 / 4 道题")
        support.tap("branch-review-details-close")
        result(4, 1, 1, 1, 1)
        support.tap("branch-review-open-details")
        detailNode("branch-review-details-skipped").assertIsSelected()
        compose.onNodeWithTag("branch-review-details-count").assertTextEquals("本范围 2 / 4 道题")
        support.tap("branch-review-details-close"); result(4, 1, 1, 1, 1)
        assertEquals(beforeDetails, authors(f))
        val extra = id()
        runBlocking { app.knowledge.submit(KnowledgeCommand(id(), f.source.note.id, extra, 0,
            KnowledgeData.Question(f.source.card, "后建问题不能进入本轮精确再练"))) }
        val beforeRetry = authors(f)
        support.tap("branch-review-retry-review"); question(f, 1); hidden()
        compose.onNodeWithTag("branch-review-retry-origin").assertTextContains("再练本轮仍需复习", substring = true)
        assertEquals(beforeRetry, authors(f))
        support.assertReadOnly(f.source)
        // The new explicit mark must use the causal qRev + 1, not the old or latest-search queue.
        mark(ManualState.UNDERSTOOD); result(1, 1, 0, 0)
        runBlocking {
            assertEquals(3L, checkNotNull(database.knowledge().get(f.frozen[1].reference.questionId)).revision)
            assertEquals(1L, checkNotNull(database.knowledge().get(extra)).revision)
        }
        compose.onNodeWithTag("branch-review-retry-review").assertIsNotEnabled()
        compose.onNodeWithTag("branch-review-retry-skipped").assertIsNotEnabled()
    }

    @Test fun missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution() {
        val f = seed(2)
        start(); support.tap("branch-review-skip"); question(f, 1)
        support.tap("branch-review-skip"); result(2, 0, 0, 2)
        val entry = f.frozen.last().reference
        val history = runBlocking { checkNotNull(database.knowledge().revision(entry.questionId, entry.questionRevision)) }
        var missing = true
        try {
            runBlocking { database.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=? AND revision=?",
                arrayOf<Any>(entry.questionId, entry.questionRevision)) }
            val beforeRead = authors(f)
            support.tap("branch-review-open-details"); detailNode("branch-review-details-error")
            f.frozen.forEach { compose.onNodeWithTag("branch-review-detail-${it.reference.questionId}").assertDoesNotExist() }
            assertEquals(beforeRead, authors(f))
            support.tap("branch-review-details-close"); result(2, 0, 0, 2)
            support.tap("branch-review-open-details"); detailNode("branch-review-details-error")
            runBlocking { database.knowledge().revision(history) }; missing = false
            val beforeRetry = authors(f)
            detailTap("branch-review-details-retry")
            detailNode("branch-review-detail-${entry.questionId}")
            compose.onNodeWithTag("branch-review-detail-prompt-${entry.questionId}")
                .assertTextEquals((f.frozen.last().question.data() as KnowledgeData.Question).prompt)
            detailTap("branch-review-details-review")
            detailNode("branch-review-details-empty").assertExists()
            support.tap("branch-review-details-close"); result(2, 0, 0, 2)
            assertEquals(beforeRetry, authors(f))
        } finally { if (missing) runBlocking { database.knowledge().revision(history) } }
    }

    @Test fun pureSkipWithIdenticalRefsStartsAtFirstQuestionWithNoOldPermissionAndRestoresItsOwnRound() {
        val f = seed(2, ManualState.UNDERSTOOD)
        val before = authors(f)
        start(); question(f, 0)
        support.tap("reveal-answer"); support.tap("branch-review-skip"); question(f, 1); hidden()
        support.tap("review-show-hint"); support.tap("branch-review-skip"); result(2, 0, 0, 2)
        compose.onNodeWithTag("branch-review-retry-review").assertIsNotEnabled()
        support.tap("branch-review-retry-skipped"); question(f, 0); hidden()
        compose.onNodeWithTag("branch-review-retry-origin").assertTextContains("再练本轮跳过", substring = true)
        compose.activityRule.scenario.recreate(); question(f, 0); hidden()
        support.tap("branch-review-skip"); question(f, 1); hidden()
        support.tap("branch-review-skip"); result(2, 0, 0, 2)
        support.tap("branch-review-retry-skipped"); question(f, 0); hidden()
        assertEquals(before, authors(f))
        support.assertReadOnly(f.source)
    }

    @Test fun unknownOriginalOperationSurvivesActivityAndWriterRecreationBeforeCountingExactlyOnce() {
        val f = seed(1)
        val fail = AtomicBoolean(true)
        val repository = KnowledgeRepository(database) { if (it == KnowledgeFault.BEFORE_RECEIPT && fail.get())
            throw IOException("Synthetic RR67 rollback before the original receipt") }
        var saved = SavedStateHandle()
        val key = "branch-review-" + f.source.note.id
        var owned: KnowledgeViewModel? = null
        try {
            compose.runOnIdle { owned = KnowledgeViewModel(repository, saved, app.resourcePacks);compose.activity.viewModelStore.put(key, checkNotNull(owned)) }
            start(); question(f, 0)
            val before = authors(f)
            mark(ManualState.REVIEW); support.waitFor("branch-review-retry"); blockedPending()
            val request = compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() }
            val payload = compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")).copyOf() }
            assertEquals(before, authors(f))
            compose.runOnIdle {
                saved = SavedStateHandle(saved.keys().associateWith { name -> when (val value = saved.get<Any?>(name)) {
                    is ByteArray -> value.copyOf()
                    is ArrayList<*> -> ArrayList(value)
                    else -> value
                } })
                owned = KnowledgeViewModel(repository, saved, app.resourcePacks)
                compose.activity.viewModelStore.put(key, checkNotNull(owned))
            }
            compose.activityRule.scenario.recreate(); support.waitFor("branch-review-retry"); question(f, 0); blockedPending()
            assertEquals(request, compose.runOnIdle { checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList() })
            assertArrayEquals(payload, compose.runOnIdle { checkNotNull(saved.get<ByteArray>("knowledge.payload")) })
            assertEquals(before, authors(f))
            fail.set(false); support.tap("branch-review-retry"); result(1, 0, 1, 0)
            val after = authors(f)
            compose.activityRule.scenario.recreate(); result(1, 0, 1, 0)
            val original = KnowledgeCommand(request[0], request[1], request[2], request[3].toLong(), KnowledgeCodec.decode(payload))
            assertEquals(KnowledgeOutcome.Success(original.id), runBlocking { app.knowledge.reviewOutcome(original, 1) })
            assertEquals(after, authors(f))
            runBlocking {
                assertNotNull(database.knowledge().receipt(request[0]))
                assertEquals(2L, checkNotNull(database.knowledge().get(original.id)).revision)
                assertNull(database.knowledge().revision(original.id, 3))
            }
        } finally {
            fail.set(false)
            compose.runOnIdle { if (compose.activity.viewModelStore.get(key) === owned)
                compose.activity.viewModelStore.put(key, KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks)) }
        }
    }

    @Test fun committedButUnverifiedResultStaysLockedAcrossRecreationAndRetriesReadOnlyProof() {
        val f = seed(1)
        val held = AtomicReference<KnowledgeRevisionRow?>()
        val removeOnce = AtomicBoolean(true)
        val repository = KnowledgeRepository(database) { stage ->
            if (stage == KnowledgeFault.AFTER_COMMIT && removeOnce.compareAndSet(true, false)) runBlocking {
                val entry = f.frozen.single().reference
                val history = checkNotNull(database.knowledge().revision(entry.questionId, entry.questionRevision + 1))
                held.set(history)
                database.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=? AND revision=?",
                    arrayOf<Any>(history.id, history.revision))
            }
        }
        val saved = SavedStateHandle()
        val key = "branch-review-" + f.source.note.id
        var owned: KnowledgeViewModel? = null
        try {
            compose.runOnIdle { owned = KnowledgeViewModel(repository, saved, app.resourcePacks);compose.activity.viewModelStore.put(key, checkNotNull(owned)) }
            start(); question(f, 0); mark(ManualState.UNDERSTOOD)
            support.waitFor("branch-review-confirm-retry"); blockedPending()
            val operation = compose.runOnIdle { checkNotNull(saved.get<String>("knowledge.completedOperation")) }
            assertNull(compose.runOnIdle { saved.get<ArrayList<String>>("knowledge.request") })
            val afterWrite = authors(f)
            compose.activityRule.scenario.recreate(); support.waitFor("branch-review-confirm-retry"); question(f, 0); blockedPending()
            assertEquals(operation, compose.runOnIdle { saved.get<String>("knowledge.completedOperation") })
            support.tap("branch-review-confirm-retry"); support.waitFor("branch-review-confirm-retry"); blockedPending()
            assertEquals(afterWrite, authors(f))
            runBlocking { database.knowledge().revision(checkNotNull(held.getAndSet(null))) }
            val beforeProof = authors(f)
            support.tap("branch-review-confirm-retry"); result(1, 1, 0, 0)
            assertEquals(beforeProof, authors(f))
            assertNull(compose.runOnIdle { saved.get<String>("knowledge.completedOperation") })
            runBlocking {
                assertEquals(f.frozen.single().reference.questionId, checkNotNull(database.knowledge().receipt(operation)).resultId)
                assertNull(database.knowledge().revision(f.frozen.single().reference.questionId, 3))
            }
        } finally {
            held.getAndSet(null)?.let { row -> runBlocking { database.knowledge().revision(row) } }
            compose.runOnIdle { if (compose.activity.viewModelStore.get(key) === owned)
                compose.activity.viewModelStore.put(key, KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks)) }
        }
    }

    @Test fun completedRoundRecoveryConsumesOnlyItsOwnOperationBeforeRetryAndClose() {
        val f = seed(1)
        start(); question(f, 0); mark(ManualState.REVIEW); result(1, 0, 1, 0)
        val frozen = f.frozen.single()
        val requested = (frozen.question.data() as KnowledgeData.Question).copy(state = ManualState.REVIEW)
        // Resolve the actual committed writer op, rather than guessing or borrowing a create receipt.
        val operation = runBlocking { database.withTransaction {
            val matches = database.openHelper.readableDatabase.query(
                "SELECT operationId, digest FROM knowledge_receipts WHERE notebookId=? AND resultId=?",
                arrayOf<Any>(f.source.note.id, frozen.reference.questionId)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val candidate = cursor.getString(0)
                        val command = KnowledgeCommand(candidate, f.source.note.id, frozen.reference.questionId,
                            frozen.reference.questionRevision, requested)
                        if (cursor.getString(1) == command.digest()) add(candidate)
                    }
                }
            }
            assertEquals("One exact committed review receipt", 1, matches.size)
            val command = KnowledgeCommand(matches.single(), f.source.note.id, frozen.reference.questionId,
                frozen.reference.questionRevision, requested)
            assertArrayEquals(command.payload, checkNotNull(database.knowledge().revision(
                frozen.reference.questionId, frozen.reference.questionRevision + 1)).payload)
            matches.single()
        } }
        val key = "branch-review-" + f.source.note.id
        var saved = SavedStateHandle()
        var owned: KnowledgeViewModel? = null
        var injectedOperation: String? = null
        fun restoreCompletion(questionId: String, operationId: String) {
            compose.runOnIdle {
                injectedOperation = operationId
                saved = SavedStateHandle(mapOf(
                    "knowledge.completed" to questionId,
                    "knowledge.completedOperation" to operationId))
                owned = KnowledgeViewModel(app.knowledge, saved, app.resourcePacks)
                compose.activity.viewModelStore.put(key, checkNotNull(owned))
            }
            // The actual completed Round remains in the Activity's normal saveable state.
            compose.activityRule.scenario.recreate()
        }
        try {
            val beforeRestore = authors(f)
            restoreCompletion(frozen.reference.questionId, operation)
            result(1, 0, 1, 0)
            compose.waitUntil(15_000) { compose.runOnIdle {
                saved.get<String>("knowledge.completedOperation") == null &&
                    checkNotNull(owned).ui.value.completedOperation == null
            } }
            assertNull(compose.runOnIdle { saved.get<String>("knowledge.completed") })
            assertEquals(beforeRestore, authors(f))
            compose.onNodeWithTag("branch-review-retry-review").assertIsEnabled()
            support.tap("branch-review-retry-review"); question(f, 0); hidden()
            compose.onNodeWithTag("branch-review-close").assertIsEnabled()
            support.tap("branch-review-close")
            compose.onNodeWithTag("manual-review").assertDoesNotExist()
            assertEquals(beforeRestore, authors(f))

            // A second real, completed round provides a separate fail-closed recovery case.
            support.waitFor("notebook-review-panel")
            compose.onNodeWithTag("notebook-review-panel").performScrollToNode(hasTestTag("manual-review-start"))
            support.tap("manual-review-start"); question(f, 0); hidden()
            support.tap("branch-review-skip"); result(1, 0, 0, 1)
            val unrelated = KnowledgeCommand(id(), f.source.note.id, id(), 0,
                KnowledgeData.Question(f.source.secondCard, "RR67 unrelated synthetic completion"))
            runBlocking {
                app.knowledge.submit(unrelated)
                val receipt = checkNotNull(database.knowledge().receipt(unrelated.operationId))
                assertEquals(unrelated.id, receipt.resultId)
                assertEquals(unrelated.digest(), receipt.digest)
            }
            val beforeUnrelatedRestore = authors(f)
            restoreCompletion(unrelated.id, unrelated.operationId)
            result(1, 0, 0, 1)
            compose.waitForIdle()
            assertEquals(unrelated.operationId, compose.runOnIdle { saved.get<String>("knowledge.completedOperation") })
            assertEquals(unrelated.id, compose.runOnIdle { saved.get<String>("knowledge.completed") })
            for (tag in listOf("branch-review-retry-review", "branch-review-retry-skipped", "branch-review-close")) {
                val target = compose.onNodeWithTag(tag)
                runCatching { target.performScrollTo() }
                target.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            }
            result(1, 0, 0, 1)
            assertEquals(unrelated.operationId, compose.runOnIdle { checkNotNull(owned).ui.value.completedOperation })
            assertEquals(beforeUnrelatedRestore, authors(f))

            // Cleanup acknowledges only the identity explicitly injected by this synthetic test.
            compose.runOnIdle { checkNotNull(owned).consumed(unrelated.operationId) }
            compose.waitUntil(15_000) { compose.runOnIdle { saved.get<String>("knowledge.completedOperation") == null } }
            support.tap("branch-review-close")
            compose.onNodeWithTag("manual-review").assertDoesNotExist()
            assertEquals(beforeUnrelatedRestore, authors(f))
        } finally {
            compose.runOnIdle {
                val current = owned
                if (current != null && compose.activity.viewModelStore.get(key) === current) {
                    injectedOperation?.let { current.consumed(it) }
                    val ui = current.ui.value
                    // Never discard an unexpected operation while restoring the normal writer.
                    if (current.pendingOperationId == null && !ui.busy && !ui.unknown &&
                        ui.completedOperation == null && ui.rejectedOperation == null)
                        compose.activity.viewModelStore.put(key, KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks))
                }
            }
        }
    }
}
