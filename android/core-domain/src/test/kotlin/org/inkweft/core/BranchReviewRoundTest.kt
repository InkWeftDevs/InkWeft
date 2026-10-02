// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BranchReviewRoundTest {
    private fun id() = UUID.randomUUID().toString()
    private fun plan(count: Int = 4, scope: ReviewQuestionScope = ReviewQuestionScope.ALL): BranchReviewPlan {
        val cards = listOf(id(), id())
        val entries = (0 until count).map { BranchReviewEntryRef(id(), it + 2L, cards[it % 2], 7) }
        return BranchReviewPlan(MapRef(id(), id()), id(), "本轮固定范围", entries.map { it.cardId }.distinct().size,
            0, entries, scope, entries.size)
    }
    private fun rejected(message: String, action: () -> Unit) {
        try { action(); fail("Expected " + message) }
        catch (failure: IllegalArgumentException) { assertEquals(message, failure.message) }
    }

    @Test fun mixedResultsAreExclusiveAndUseIndependentOriginalQuestionIndices() {
        val plan = plan()
        val understood = id(); val review = id(); val conflict = id()
        var round = BranchReviewRound(roundId = id(), plan = plan)
        round = round.begin(0, understood, ManualState.UNDERSTOOD).confirm(understood)
        round = round.begin(1, review, ManualState.REVIEW).confirm(review)
        round = round.skip(2)
        round = round.begin(3, conflict, ManualState.REVIEW).reject(conflict)
        assertFalse(round.complete)
        assertEquals(1, round.counts.remaining)
        round = round.skip(3)
        assertTrue(round.complete)
        assertEquals(BranchReviewCounts(4, 1, 1, 1, 1), round.counts)
        assertEquals(4, round.counts.finished)
        assertEquals(2, round.counts.unmarked)
        assertEquals(0, round.counts.remaining)
        assertEquals(listOf(0, 1, 2, 3), round.results.map { it.index })
        assertEquals(listOf(understood, review, null, conflict), round.results.map { it.operationId })
        assertEquals(listOf(1), round.retryResults(BranchReviewRetrySelection.REVIEW).map { it.index })
        assertEquals(listOf(2, 3), round.retryResults(BranchReviewRetrySelection.SKIPPED).map { it.index })
        assertEquals(plan.entries[0].cardId, plan.entries[2].cardId)
    }

    @Test fun pendingUnknownKeepsTheExactOperationAndCannotAdvanceOrGenerateRetry() {
        val operation = id()
        val round = BranchReviewRound(roundId = id(), plan = plan()).begin(0, operation, ManualState.REVIEW)
        assertSame(round, round.begin(0, operation, ManualState.REVIEW))
        assertEquals(0, round.index)
        assertEquals(4, round.counts.remaining)
        rejected("BRANCH_REVIEW_PENDING_OPERATION") { round.skip(0) }
        rejected("BRANCH_REVIEW_PENDING_OPERATION") { round.begin(0, id(), ManualState.REVIEW) }
        rejected("BRANCH_REVIEW_PENDING_OPERATION") { round.begin(0, operation, ManualState.UNDERSTOOD) }
        rejected("BRANCH_REVIEW_PENDING_OPERATION") { round.confirm(id()) }
        rejected("BRANCH_REVIEW_PENDING_OPERATION") { round.reject(id()) }
        rejected("BRANCH_REVIEW_ROUND_INCOMPLETE") { round.retryResults(BranchReviewRetrySelection.REVIEW) }
        assertEquals(operation, round.pending?.operationId)
    }

    @Test fun onlyExplicitReviewStatesAndTheCurrentIndexCanBeginAMark() {
        val round = BranchReviewRound(roundId = id(), plan = plan())
        rejected("BRANCH_REVIEW_MARK_STATE") { round.begin(0, id(), ManualState.INBOX) }
        rejected("BRANCH_REVIEW_PENDING_INDEX") { round.begin(1, id(), ManualState.REVIEW) }
        val operation = id()
        val complete = BranchReviewRound(roundId = id(), plan = plan(1))
            .begin(0, operation, ManualState.UNDERSTOOD).confirm(operation)
        rejected("BRANCH_REVIEW_PENDING_INDEX") { complete.begin(1, id(), ManualState.REVIEW) }
        rejected("BRANCH_REVIEW_RESULT_CONFLICT") { complete.reject(operation) }
    }

    @Test fun duplicateCompletionCannotConsumeTheNextPendingOperation() {
        val operation = id(); val next = id()
        val once = BranchReviewRound(roundId = id(), plan = plan()).begin(0, operation, ManualState.UNDERSTOOD).confirm(operation)
        assertSame(once, once.confirm(operation))
        val pendingNext = once.begin(1, next, ManualState.REVIEW)
        assertSame(pendingNext, pendingNext.confirm(operation))
        assertEquals(next, pendingNext.pending?.operationId)
        assertEquals(1, pendingNext.counts.understood)
        rejected("BRANCH_REVIEW_OPERATION_REUSE") { once.begin(1, operation, ManualState.REVIEW) }
    }

    @Test fun duplicateSkipDoesNotSkipTheNextQuestionOrOverwriteAConfirmedResult() {
        val skipped = BranchReviewRound(roundId = id(), plan = plan()).skip(0)
        assertSame(skipped, skipped.skip(0))
        val operation = id()
        val marked = skipped.begin(1, operation, ManualState.REVIEW).confirm(operation)
        assertSame(marked, marked.skip(1))
        assertEquals(2, marked.index)
        assertEquals(1, marked.counts.skipped)
        assertEquals(1, marked.counts.review)
        rejected("BRANCH_REVIEW_SKIP_INDEX") { marked.skip(3) }
        rejected("BRANCH_REVIEW_SKIP_INDEX") { marked.skip(-1) }
    }

    @Test fun rejectedSkipRemainsUnmarkedAndRejectionNotificationsAreIdempotent() {
        val operation = id()
        val denied = BranchReviewRound(roundId = id(), plan = plan()).begin(0, operation, ManualState.UNDERSTOOD).reject(operation)
        assertSame(denied, denied.reject(operation))
        assertSame(denied, denied.begin(0, operation, ManualState.UNDERSTOOD))
        assertEquals(0, denied.counts.finished)
        rejected("BRANCH_REVIEW_RESULT_CONFLICT") { denied.confirm(operation) }
        val skipped = denied.skip(0)
        assertSame(skipped, skipped.reject(operation))
        assertSame(skipped, skipped.skip(0))
        assertEquals(1, skipped.counts.rejectedSkipped)
        assertEquals(0, skipped.counts.understood)
        assertEquals(0, skipped.counts.review)
        rejected("BRANCH_REVIEW_RESULT_CONFLICT") { skipped.confirm(operation) }
    }

    @Test fun retryReviewOnlyAcceptsExactCausalRevisionAndPreservesAnswerRevision() {
        val plan = plan(2)
        val operation = id()
        val round = BranchReviewRound(roundId = id(), plan = plan).begin(0, operation, ManualState.REVIEW).confirm(operation).skip(1)
        val next = plan.entries[0].copy(questionRevision = plan.entries[0].questionRevision + 1)
        val retry = round.retryPlan(BranchReviewRetrySelection.REVIEW, listOf(next))
        assertEquals(listOf(next), retry.entries)
        assertEquals(plan.entries[0].cardRevision, retry.entries.single().cardRevision)
        assertEquals(plan.ref, retry.ref)
        assertEquals(plan.branchId, retry.branchId)
        assertEquals(plan.title, retry.title)
        assertEquals(1, retry.cardCount)
        assertEquals(1, retry.totalQuestionCount)
        assertEquals(0, retry.withoutQuestionCount)
        assertEquals(0, retry.otherStateOnlyCardCount)
        for (bad in listOf(plan.entries[0], next.copy(questionRevision = next.questionRevision + 1),
            next.copy(cardRevision = next.cardRevision + 1), next.copy(questionId = id()), next.copy(cardId = id()))) {
            rejected("BRANCH_REVIEW_RETRY_REFERENCES") { round.retryPlan(BranchReviewRetrySelection.REVIEW, listOf(bad)) }
        }
        rejected("BRANCH_REVIEW_RETRY_REFERENCES") { round.retryPlan(BranchReviewRetrySelection.REVIEW, emptyList()) }
        rejected("BRANCH_REVIEW_RETRY_REFERENCES") { round.retryPlan(BranchReviewRetrySelection.REVIEW, listOf(next, plan.entries[1])) }
    }

    @Test fun retrySkippedUsesExactFrozenRefsAndRecomputesFilteredPlanCounts() {
        val base = plan(4)
        val filtered = BranchReviewPlan(base.ref, base.branchId, base.title, 4, 1, base.entries,
            ReviewQuestionScope.REVIEW_ONLY, 9, 1)
        val operation = id(); val rejectedOperation = id()
        val round = BranchReviewRound(roundId = id(), plan = filtered).skip(0)
            .begin(1, operation, ManualState.UNDERSTOOD).confirm(operation)
            .begin(2, rejectedOperation, ManualState.REVIEW).reject(rejectedOperation).skip(2).skip(3)
        val exact = listOf(filtered.entries[0], filtered.entries[2], filtered.entries[3])
        val retry = round.retryPlan(BranchReviewRetrySelection.SKIPPED, exact)
        assertEquals(exact, retry.entries)
        assertEquals(ReviewQuestionScope.REVIEW_ONLY, retry.scope)
        assertEquals(2, retry.cardCount)
        assertEquals(3, retry.totalQuestionCount)
        assertEquals(0, retry.withoutQuestionCount)
        assertEquals(0, retry.otherStateOnlyCardCount)
        rejected("BRANCH_REVIEW_RETRY_REFERENCES") { round.retryPlan(BranchReviewRetrySelection.SKIPPED, exact.reversed()) }
        rejected("BRANCH_REVIEW_RETRY_REFERENCES") {
            round.retryPlan(BranchReviewRetrySelection.SKIPPED, exact.map { it.copy(questionRevision = it.questionRevision + 1) })
        }
    }

    @Test fun pureSkipRetryWithIdenticalRefsStartsANewEmptyRoundIdentity() {
        val original = BranchReviewRound(roundId = id(), plan = plan(2))
        val ended = original.skip(0).skip(1)
        val next = BranchReviewRound(roundId = id(), plan = ended.retryPlan(BranchReviewRetrySelection.SKIPPED, original.plan.entries))
        assertNotEquals(original.roundId, next.roundId)
        assertEquals(original.plan.entries, next.plan.entries)
        assertEquals(0, next.index)
        assertFalse(next.complete)
        assertNull(next.pending)
        assertTrue(next.results.isEmpty())
        assertEquals(2, next.counts.remaining)
    }

    @Test fun zeroCandidatesCannotStartRetryAndAnEmptyRoundDoesNotInventResults() {
        val empty = BranchReviewRound(roundId = id(), plan = plan(0))
        assertTrue(empty.complete)
        assertEquals(BranchReviewCounts(0, 0, 0, 0, 0), empty.counts)
        assertTrue(empty.retryResults(BranchReviewRetrySelection.REVIEW).isEmpty())
        rejected("BRANCH_REVIEW_RETRY_EMPTY") { empty.retryPlan(BranchReviewRetrySelection.SKIPPED, emptyList()) }
        val skipped = BranchReviewRound(roundId = id(), plan = plan(1)).skip(0)
        rejected("BRANCH_REVIEW_RETRY_EMPTY") { skipped.retryPlan(BranchReviewRetrySelection.REVIEW, emptyList()) }
    }

    @Test fun restoringBoundedStateCopiesCallerListsAndKeepsPendingIdentity() {
        val plan = plan()
        val results = mutableListOf(BranchReviewResult(0, BranchReviewResultKind.SKIPPED))
        val pending = BranchReviewPending(1, id(), ManualState.REVIEW)
        val roundId = id()
        val restored = BranchReviewRound(roundId, plan, results, pending)
        results.clear()
        assertEquals(1, restored.index)
        assertEquals(roundId, restored.roundId)
        assertEquals(pending, restored.pending)
        try { (restored.results as MutableList<BranchReviewResult>).clear(); fail("Expected immutable results") }
        catch (_: UnsupportedOperationException) { }
        val again = BranchReviewRound(restored.roundId, restored.plan, restored.results, restored.pending)
        assertEquals(restored.results, again.results)
        assertEquals(restored.pending, again.pending)
        assertEquals(restored.roundId, again.roundId)
    }

    @Test fun malformedRestoredLedgerCannotDuplicateOrMoveOutcomes() {
        val plan = plan(2); val operation = id()
        rejected("BRANCH_REVIEW_RESULT_ORDER") {
            BranchReviewRound(roundId = id(), plan = plan, results = listOf(BranchReviewResult(1, BranchReviewResultKind.SKIPPED)))
        }
        rejected("BRANCH_REVIEW_OPERATION_REUSE") {
            BranchReviewRound(roundId = id(), plan = plan, results = listOf(
                BranchReviewResult(0, BranchReviewResultKind.CONFIRMED_REVIEW, operation),
                BranchReviewResult(1, BranchReviewResultKind.CONFIRMED_UNDERSTOOD, operation)))
        }
        rejected("BRANCH_REVIEW_PENDING_INDEX") {
            BranchReviewRound(roundId = id(), plan = plan, pending = BranchReviewPending(1, operation, ManualState.REVIEW))
        }
        rejected("BRANCH_REVIEW_RESULT_BUDGET") {
            BranchReviewRound(roundId = id(), plan = plan, results = (0..2).map { BranchReviewResult(it, BranchReviewResultKind.SKIPPED) })
        }
        rejected("BRANCH_REVIEW_OPERATION_REUSE") {
            BranchReviewRound(roundId = id(), plan = plan, results = listOf(BranchReviewResult(0, BranchReviewResultKind.CONFIRMED_REVIEW, operation)),
                pending = BranchReviewPending(1, operation, ManualState.REVIEW))
        }
    }

    @Test fun revisionLimitCannotOverflowIntoAnInventedCausalVersion() {
        val base = plan(1)
        val exhausted = BranchReviewPlan(base.ref, base.branchId, base.title, 1, 0,
            listOf(base.entries[0].copy(questionRevision = Long.MAX_VALUE)))
        val round = BranchReviewRound(roundId = id(), plan = exhausted)
        rejected("BRANCH_REVIEW_REVISION_LIMIT") { round.begin(0, id(), ManualState.REVIEW) }
        val skipped = round.skip(0)
        assertEquals(exhausted.entries, skipped.retryPlan(BranchReviewRetrySelection.SKIPPED, exhausted.entries).entries)
    }

    @Test fun maximumRoundBudgetRemainsBoundedAndCountsCannotBecomeImpossible() {
        val plan = plan(BranchReview.MAX_QUESTIONS)
        val results = plan.entries.indices.map { BranchReviewResult(it, BranchReviewResultKind.SKIPPED) }
        val round = BranchReviewRound(id(), plan, results)
        assertTrue(round.complete)
        assertEquals(BranchReview.MAX_QUESTIONS, round.counts.skipped)
        assertEquals(0, round.counts.remaining)
        assertEquals(plan.entries, round.retryPlan(BranchReviewRetrySelection.SKIPPED, plan.entries).entries)
        rejected("BRANCH_REVIEW_RESULT_COUNTS") { BranchReviewCounts(2, 1, 1, 1, 0) }
        rejected("BRANCH_REVIEW_RESULT_COUNTS") { BranchReviewCounts(2, -1, 0, 0, 0) }
        rejected("BRANCH_REVIEW_RESULT_COUNTS") { BranchReviewCounts(BranchReview.MAX_QUESTIONS + 1, 0, 0, 0, 0) }
    }
}
