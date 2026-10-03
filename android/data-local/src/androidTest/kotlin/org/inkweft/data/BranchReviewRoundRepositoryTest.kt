// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BranchReviewRoundRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private data class Fixture(val book: String, val card: StudyCommand,
                               val questions: List<KnowledgeCommand>, val plan: BranchReviewPlan)

    private fun fixture(states: List<ManualState> = listOf(ManualState.REVIEW),
                        block: suspend (NoteDatabase, Fixture) -> Unit) = runBlocking {
        val name = "branch-review-round-" + id() + ".db"
        val db = NoteDatabase.open(context, name)
        try {
            val book = WorkspaceRepository(db).create("本轮因果验收", false, PaperStyle.DOTS).id
            val card = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(),
                title = "固定卡片", body = "本轮冻结答案")
            StudyRepository(db).submit(card)
            val questions = states.mapIndexed { index, state ->
                KnowledgeCommand(id(), book, id(), 0, KnowledgeData.Question(checkNotNull(card.cardId),
                    "固定独立问题 " + index, state)).also { KnowledgeRepository(db).submit(it) }
            }
            val plan = BranchReviewRepository(db).prepare(MapRef(book), card.nodeId)
            block(db, Fixture(book, card, questions, plan))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun command(f: Fixture, index: Int, state: ManualState): KnowledgeCommand {
        val reference = f.plan.entries[index]
        val original = f.questions.single { it.id == reference.questionId }.data as KnowledgeData.Question
        return KnowledgeCommand(id(), f.book, reference.questionId, reference.questionRevision, original.copy(state = state))
    }

    private suspend fun committed(db: NoteDatabase, f: Fixture, state: ManualState = ManualState.REVIEW): Pair<BranchReviewRound, KnowledgeCommand> {
        val command = command(f, 0, state)
        assertEquals(KnowledgeOutcome.Success(command.id), KnowledgeRepository(db).reviewOutcome(command, f.plan.entries[0].cardRevision))
        return BranchReviewRound(id(), f.plan).begin(0, command.operationId, state) to command
    }

    /** Private synthetic databases only; include every author table, history, receipt and book timestamp. */
    private suspend fun authorStamp(db: NoteDatabase): List<String> = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        buildList {
            for (table in tables) sql.query("SELECT * FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) add(table + ":" + (0 until cursor.columnCount).joinToString("|") { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_INTEGER -> "i:" + cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> "f:" + cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> "b:" + ContentTransfer.hash(cursor.getBlob(column))
                        else -> "s:" + ContentTransfer.hash(cursor.getString(column).toByteArray(Charsets.UTF_8))
                    }
                })
            }
        }.sorted()
    }

    private suspend fun rejectsWithoutWrites(db: NoteDatabase, message: String, action: suspend () -> Unit) {
        val before = authorStamp(db)
        try { action(); fail("Expected " + message) }
        catch (failure: IllegalArgumentException) { assertEquals(message, failure.message) }
        assertEquals(before, authorStamp(db))
    }

    private fun replaceHistory(db: NoteDatabase, row: KnowledgeRevisionRow) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE knowledge_revisions SET notebookId=?, payload=?, removed=? WHERE id=? AND revision=?",
            arrayOf(row.notebookId, row.payload, if (row.removed) 1 else 0, row.id, row.revision))
    }

    @Test fun exactReceiptsCountIndependentSameCardQuestionsOnceWithZeroReadSideWrites() = fixture(
        listOf(ManualState.REVIEW, ManualState.REVIEW)) { db, f ->
        val repository = BranchReviewRepository(db)
        val (pending, first) = committed(db, f, ManualState.UNDERSTOOD)
        val afterFirstWrite = authorStamp(db)
        var round = repository.confirmRoundResult(pending)
        assertEquals(1, round.counts.understood)
        assertSame(round, repository.confirmRoundResult(round))
        assertEquals(round.results, repository.confirmRoundResult(pending).results)
        assertEquals(afterFirstWrite, authorStamp(db))
        val second = command(f, 1, ManualState.REVIEW)
        round = round.begin(1, second.operationId, ManualState.REVIEW)
        assertEquals(KnowledgeOutcome.Success(second.id), KnowledgeRepository(db).reviewOutcome(second, 1))
        val afterSecondWrite = authorStamp(db)
        round = repository.confirmRoundResult(round)
        assertEquals(BranchReviewCounts(2, 1, 1, 0, 0), round.counts)
        assertEquals(listOf(first.operationId, second.operationId), round.results.map { it.operationId })
        val retry = repository.prepareRetry(round, BranchReviewRetrySelection.REVIEW)
        assertEquals(listOf(f.plan.entries[1].copy(questionRevision = 2)), retry.entries)
        assertEquals(1, retry.cardCount)
        assertEquals(1, retry.totalQuestionCount)
        assertEquals(f.plan.ref, retry.ref)
        assertEquals(f.plan.branchId, retry.branchId)
        assertEquals(afterSecondWrite, authorStamp(db))
    }

    @Test fun unknownRollbackCannotCountOrSkipUntilTheSameOperationActuallyCommits() = fixture { db, f ->
        val command = command(f, 0, ManualState.REVIEW)
        val pending = BranchReviewRound(id(), f.plan).begin(0, command.operationId, ManualState.REVIEW)
        val before = authorStamp(db)
        assertEquals(KnowledgeOutcome.Unknown, KnowledgeRepository(db) {
            if (it == KnowledgeFault.BEFORE_RECEIPT) error("Synthetic rollback")
        }.reviewOutcome(command, 1))
        assertEquals(before, authorStamp(db))
        val repository = BranchReviewRepository(db)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISSING") { repository.confirmRoundResult(pending) }
        assertEquals(command.operationId, pending.pending?.operationId)
        assertEquals(0, pending.index)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_PENDING_OPERATION") { pending.skip(0) }
        rejectsWithoutWrites(db, "BRANCH_REVIEW_ROUND_INCOMPLETE") {
            repository.prepareRetry(pending, BranchReviewRetrySelection.REVIEW)
        }
        assertEquals(KnowledgeOutcome.Success(command.id), KnowledgeRepository(db).reviewOutcome(command, 1))
        val committed = authorStamp(db)
        val confirmed = repository.confirmRoundResult(pending)
        assertEquals(1, confirmed.counts.review)
        assertEquals(KnowledgeOutcome.Success(command.id), KnowledgeRepository(db).reviewOutcome(command, 1))
        assertSame(confirmed, repository.confirmRoundResult(confirmed))
        assertEquals(committed, authorStamp(db))
    }

    @Test fun missingWrongOperationBookDigestResultOrRequestedStateNeverConfirms() = fixture { db, f ->
        val (pending, command) = committed(db, f)
        val repository = BranchReviewRepository(db)
        val wrongOperation = BranchReviewRound(pending.roundId, f.plan).begin(0, id(), ManualState.REVIEW)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISSING") { repository.confirmRoundResult(wrongOperation) }
        val createOperation = f.questions.single().operationId
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISMATCH") {
            repository.confirmRoundResult(BranchReviewRound(id(), f.plan).begin(0, createOperation, ManualState.REVIEW))
        }
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISMATCH") {
            repository.confirmRoundResult(BranchReviewRound(id(), f.plan).begin(0, command.operationId, ManualState.UNDERSTOOD))
        }
        val receipt = checkNotNull(db.knowledge().receipt(command.operationId))
        val foreign = WorkspaceRepository(db).create("另一笔记", false, PaperStyle.BLANK).id
        for (bad in listOf(receipt.copy(notebookId = foreign), receipt.copy(digest = "0".repeat(64)), receipt.copy(resultId = id()))) {
            db.openHelper.writableDatabase.execSQL("UPDATE knowledge_receipts SET notebookId=?, digest=?, resultId=? WHERE operationId=?",
                arrayOf(bad.notebookId, bad.digest, bad.resultId, bad.operationId))
            rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISMATCH") { repository.confirmRoundResult(pending) }
        }
        db.openHelper.writableDatabase.execSQL("UPDATE knowledge_receipts SET notebookId=?, digest=?, resultId=? WHERE operationId=?",
            arrayOf(receipt.notebookId, receipt.digest, receipt.resultId, receipt.operationId))
        assertEquals(1, repository.confirmRoundResult(pending).counts.review)
    }

    @Test fun causalRevisionMustExistAndMatchOnlyTheRequestedStateChange() = fixture { db, f ->
        val (pending, command) = committed(db, f)
        val repository = BranchReviewRepository(db)
        val next = checkNotNull(db.knowledge().revision(command.id, 2))
        val original = command.data as KnowledgeData.Question
        val foreign = WorkspaceRepository(db).create("外部笔记", false, PaperStyle.BLANK).id
        val mutations = listOf(next.copy(removed = true), next.copy(notebookId = foreign),
            next.copy(payload = KnowledgeCodec.encode(original.copy(prompt = "不是原题"))),
            next.copy(payload = KnowledgeCodec.encode(original.copy(cardId = id()))),
            next.copy(payload = KnowledgeCodec.encode(original.copy(state = ManualState.UNDERSTOOD))))
        for (bad in mutations) {
            replaceHistory(db, bad)
            rejectsWithoutWrites(db, "BRANCH_REVIEW_RESULT_REVISION_MISMATCH") { repository.confirmRoundResult(pending) }
        }
        replaceHistory(db, next)
        val confirmed = repository.confirmRoundResult(pending)
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=? AND revision=2", arrayOf(command.id))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RESULT_REVISION_MISSING") { repository.confirmRoundResult(pending) }
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RESULT_REVISION_MISSING") {
            repository.prepareRetry(confirmed, BranchReviewRetrySelection.REVIEW)
        }
        assertNotNull(db.knowledge().get(command.id))
        db.knowledge().revision(next)
        assertEquals(listOf(f.plan.entries.single().copy(questionRevision = 2)),
            repository.prepareRetry(confirmed, BranchReviewRetrySelection.REVIEW).entries)
    }

    @Test fun originalQuestionAndCardHistoryAreRequiredRatherThanCurrentRows() = fixture { db, f ->
        val (pending, command) = committed(db, f)
        val repository = BranchReviewRepository(db)
        val question = checkNotNull(db.knowledge().revision(command.id, 1))
        val original = KnowledgeCodec.decode(question.payload) as KnowledgeData.Question
        replaceHistory(db, question.copy(payload = KnowledgeCodec.encode(original.copy(prompt = "被改写的原题"))))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISMATCH") { repository.confirmRoundResult(pending) }
        replaceHistory(db, question.copy(payload = KnowledgeCodec.encode(original.copy(cardId = id()))))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_QUESTION_CARD_CHANGED") { repository.confirmRoundResult(pending) }
        replaceHistory(db, question)
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=? AND revision=1", arrayOf(command.id))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_QUESTION_REVISION_MISSING") { repository.confirmRoundResult(pending) }
        db.knowledge().revision(question)
        val card = checkNotNull(db.study().cardVersion(checkNotNull(f.card.cardId), 1))
        db.openHelper.writableDatabase.execSQL("DELETE FROM study_card_revisions WHERE cardId=? AND revision=1", arrayOf(card.cardId))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_CARD_REVISION_MISSING") { repository.confirmRoundResult(pending) }
        db.study().revision(card)
        val current = checkNotNull(db.study().card(card.cardId))
        val foreign = WorkspaceRepository(db).create("错误归属", false, PaperStyle.BLANK).id
        db.study().updateCard(current.copy(notebookId = foreign))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_CARD_SCOPE_CHANGED") { repository.confirmRoundResult(pending) }
        db.study().updateCard(current)
        assertEquals(1, repository.confirmRoundResult(pending).counts.review)
    }

    @Test fun laterQuestionCardEditsAndRecyclingNeverRebaseAnAlreadyCommittedResult() = fixture { db, f ->
        val (pending, command) = committed(db, f)
        val cardId = checkNotNull(f.card.cardId)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), f.book, command.id, 2,
            (command.data as KnowledgeData.Question).copy(prompt = "后来题干", state = ManualState.UNDERSTOOD)))
        StudyRepository(db).submit(StudyCommand(id(), f.book, StudyAction.EDIT, cardId = cardId,
            expectedRevision = 1, title = "后来卡片", body = "后来答案"))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), f.book, id(), 0,
            KnowledgeData.Question(cardId, "后建旁题不得混入")))
        StudyRepository(db).submit(StudyCommand(id(), f.book, StudyAction.REMOVE_NODE, nodeId = f.card.nodeId, expectedRevision = 1))
        StudyRepository(db).submit(StudyCommand(id(), f.book, StudyAction.TRASH_CARD, cardId = cardId, expectedRevision = 2))
        val before = authorStamp(db)
        val repository = BranchReviewRepository(db)
        val confirmed = repository.confirmRoundResult(pending)
        val retry = repository.prepareRetry(confirmed, BranchReviewRetrySelection.REVIEW)
        assertEquals(listOf(f.plan.entries.single().copy(questionRevision = 2)), retry.entries)
        val frozen = repository.load(retry).single()
        assertEquals("本轮冻结答案", frozen.card.body)
        assertEquals(command.data, frozen.question.data())
        assertEquals(f.plan.ref, retry.ref)
        assertEquals(f.plan.branchId, retry.branchId)
        assertEquals(before, authorStamp(db))
        val nextMark = KnowledgeCommand(id(), f.book, command.id, 2,
            (command.data as KnowledgeData.Question).copy(state = ManualState.UNDERSTOOD))
        assertTrue(KnowledgeRepository(db).reviewOutcome(nextMark, 1) is KnowledgeOutcome.Rejected)
        assertEquals(before, authorStamp(db))
    }

    @Test fun skippedUnderstoodQuestionRetainsAllFourRefsAndDoesNotUseGlobalStateFiltering() = fixture(
        listOf(ManualState.UNDERSTOOD)) { db, f ->
        val round = BranchReviewRound(id(), f.plan).skip(0)
        val original = f.questions.single()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), f.book, original.id, 1,
            (original.data as KnowledgeData.Question).copy(prompt = "更新题干", state = ManualState.INBOX)))
        StudyRepository(db).submit(StudyCommand(id(), f.book, StudyAction.EDIT, cardId = f.card.cardId,
            expectedRevision = 1, title = "新卡", body = "新答案"))
        val before = authorStamp(db)
        val repository = BranchReviewRepository(db)
        val retry = repository.prepareRetry(round, BranchReviewRetrySelection.SKIPPED)
        assertEquals(f.plan.entries, retry.entries)
        assertEquals(original.data, repository.load(retry).single().question.data())
        assertEquals("本轮冻结答案", repository.load(retry).single().card.body)
        assertEquals(before, authorStamp(db))
    }

    @Test fun aMissingSelectedHistoryRejectsTheEntireRetryWithoutDroppingThatQuestion() = fixture(
        listOf(ManualState.REVIEW, ManualState.REVIEW)) { db, f ->
        val round = BranchReviewRound(id(), f.plan).skip(0).skip(1)
        val missing = f.plan.entries[1]
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=? AND revision=?",
            arrayOf<Any>(missing.questionId, missing.questionRevision))
        assertNotNull(db.knowledge().get(missing.questionId))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_QUESTION_REVISION_MISSING") {
            BranchReviewRepository(db).prepareRetry(round, BranchReviewRetrySelection.SKIPPED)
        }
        assertEquals(2, round.results.size)
        assertEquals(f.plan.entries, round.plan.entries)
    }

    @Test fun completedReviewLedgerStillRequiresItsOriginalReceiptForRetry() = fixture { db, f ->
        val (pending, command) = committed(db, f)
        val repository = BranchReviewRepository(db)
        val round = repository.confirmRoundResult(pending)
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_receipts WHERE operationId=?", arrayOf(command.operationId))
        assertEquals(ManualState.REVIEW, (checkNotNull(db.knowledge().get(command.id)).data() as KnowledgeData.Question).state)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RECEIPT_MISSING") {
            repository.prepareRetry(round, BranchReviewRetrySelection.REVIEW)
        }
        assertEquals(1, round.counts.review)
    }

    @Test fun wrongFrozenNotebookCannotBorrowAValidReceiptOrAnswer() = fixture { db, f ->
        val (_, command) = committed(db, f)
        val foreign = WorkspaceRepository(db).create("另一范围", false, PaperStyle.BLANK).id
        val foreignPlan = BranchReviewPlan(MapRef(foreign), null, "错误范围", 1, 0, f.plan.entries)
        val pending = BranchReviewRound(id(), foreignPlan).begin(0, command.operationId, ManualState.REVIEW)
        val repository = BranchReviewRepository(db)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_QUESTION_SCOPE_CHANGED") { repository.confirmRoundResult(pending) }
        rejectsWithoutWrites(db, "BRANCH_REVIEW_QUESTION_SCOPE_CHANGED") {
            repository.prepareRetry(BranchReviewRound(id(), foreignPlan).skip(0), BranchReviewRetrySelection.SKIPPED)
        }
    }

    @Test fun rejectedSkipRetriesOriginalRefsButNeverPretendsToHaveAReceipt() = fixture { db, f ->
        val command = command(f, 0, ManualState.REVIEW)
        StudyRepository(db).submit(StudyCommand(id(), f.book, StudyAction.EDIT, cardId = f.card.cardId,
            expectedRevision = 1, title = "变更卡", body = "变更答案"))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.CONFLICT), KnowledgeRepository(db).reviewOutcome(command, 1))
        val rejected = BranchReviewRound(id(), f.plan).begin(0, command.operationId, ManualState.REVIEW).reject(command.operationId)
        val repository = BranchReviewRepository(db)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RESULT_CONFLICT") { repository.confirmRoundResult(rejected) }
        val round = rejected.skip(0)
        val before = authorStamp(db)
        assertEquals(f.plan.entries, repository.prepareRetry(round, BranchReviewRetrySelection.SKIPPED).entries)
        assertEquals(BranchReviewCounts(1, 0, 0, 0, 1), round.counts)
        assertNull(db.knowledge().receipt(command.operationId))
        assertEquals(before, authorStamp(db))
    }

    @Test fun noCandidateAndRecycledBookCannotStartANewRoundButOldReceiptStillCounts() = fixture { db, f ->
        val (pending, _) = committed(db, f, ManualState.UNDERSTOOD)
        val repository = BranchReviewRepository(db)
        val workspace = checkNotNull(db.workspace().get(f.book))
        assertTrue(WorkspaceRepository(db).organize(f.book, workspace.revision, workspace.folder, workspace.tags, workspace.favorite, true))
        val before = authorStamp(db)
        val confirmed = repository.confirmRoundResult(pending)
        assertEquals(1, confirmed.counts.understood)
        assertEquals(before, authorStamp(db))
        rejectsWithoutWrites(db, "BRANCH_REVIEW_RETRY_EMPTY") { repository.prepareRetry(confirmed, BranchReviewRetrySelection.REVIEW) }
        val skipped = BranchReviewRound(id(), f.plan).skip(0)
        rejectsWithoutWrites(db, "BRANCH_REVIEW_BOOK_UNAVAILABLE") { repository.prepareRetry(skipped, BranchReviewRetrySelection.SKIPPED) }
    }
}
