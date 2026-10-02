// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.Collections
import java.util.UUID

enum class BranchReviewResultKind { CONFIRMED_UNDERSTOOD, CONFIRMED_REVIEW, SKIPPED, REJECTED_SKIPPED }
enum class BranchReviewRetrySelection { REVIEW, SKIPPED }

/** Only the original plan index and exact writer operation belong to the bounded ledger. */
data class BranchReviewResult(val index: Int, val kind: BranchReviewResultKind, val operationId: String? = null) {
    init {
        require(index in 0 until BranchReview.MAX_QUESTIONS)
        require((operationId == null) == (kind == BranchReviewResultKind.SKIPPED))
        operationId?.let(UUID::fromString)
    }
}

/** Pending includes busy, unknown and receipt-verification work; rejection alone permits skipping. */
data class BranchReviewPending(val index: Int, val operationId: String, val state: ManualState, val rejected: Boolean = false) {
    init {
        require(index in 0 until BranchReview.MAX_QUESTIONS)
        UUID.fromString(operationId)
        require(state == ManualState.UNDERSTOOD || state == ManualState.REVIEW) { "BRANCH_REVIEW_MARK_STATE" }
    }
}

data class BranchReviewCounts(
    val total: Int, val understood: Int, val review: Int, val skipped: Int, val rejectedSkipped: Int,
) {
    init {
        require(total in 0..BranchReview.MAX_QUESTIONS &&
            listOf(understood, review, skipped, rejectedSkipped).all { it in 0..total } &&
            understood + review + skipped + rejectedSkipped <= total) { "BRANCH_REVIEW_RESULT_COUNTS" }
    }
    val unmarked: Int get() = skipped + rejectedSkipped
    val finished: Int get() = understood + review + unmarked
    val remaining: Int get() = total - finished
}

/** Session-only immutable state. No answer bodies, current-state search or durable score storage. */
class BranchReviewRound(
    val roundId: String,
    val plan: BranchReviewPlan,
    results: List<BranchReviewResult> = emptyList(),
    val pending: BranchReviewPending? = null,
) {
    val results: List<BranchReviewResult> = Collections.unmodifiableList(ArrayList(results))
    val index: Int get() = results.size
    val complete: Boolean get() = index == plan.entries.size && pending == null
    val counts: BranchReviewCounts get() = BranchReviewCounts(plan.entries.size,
        results.count { it.kind == BranchReviewResultKind.CONFIRMED_UNDERSTOOD },
        results.count { it.kind == BranchReviewResultKind.CONFIRMED_REVIEW },
        results.count { it.kind == BranchReviewResultKind.SKIPPED },
        results.count { it.kind == BranchReviewResultKind.REJECTED_SKIPPED })

    init {
        UUID.fromString(roundId)
        require(this.results.size <= plan.entries.size) { "BRANCH_REVIEW_RESULT_BUDGET" }
        require(this.results.withIndex().all { (index, result) -> result.index == index }) {
            "BRANCH_REVIEW_RESULT_ORDER"
        }
        val operations = this.results.mapNotNull { it.operationId }
        require(operations.distinct().size == operations.size) { "BRANCH_REVIEW_OPERATION_REUSE" }
        require(this.results.all { it.kind == BranchReviewResultKind.SKIPPED ||
            plan.entries[it.index].questionRevision < Long.MAX_VALUE }) { "BRANCH_REVIEW_REVISION_LIMIT" }
        pending?.let {
            require(it.index == index && it.index < plan.entries.size) { "BRANCH_REVIEW_PENDING_INDEX" }
            require(it.operationId !in operations) { "BRANCH_REVIEW_OPERATION_REUSE" }
            require(plan.entries[it.index].questionRevision < Long.MAX_VALUE) { "BRANCH_REVIEW_REVISION_LIMIT" }
        }
    }

    /** Register in the same UI event that submits this exact writer operation, before consuming it. */
    fun begin(index: Int, operationId: String, state: ManualState): BranchReviewRound {
        val next = BranchReviewPending(index, operationId, state)
        require(index == this.index && index < plan.entries.size) { "BRANCH_REVIEW_PENDING_INDEX" }
        if (pending != null) {
            require(pending.index == next.index && pending.operationId == next.operationId && pending.state == next.state) {
                "BRANCH_REVIEW_PENDING_OPERATION"
            }
            return this
        }
        return BranchReviewRound(roundId, plan, results, next)
    }

    /** Call only after repository verification of the original command, receipt and exact qRev + 1 history. */
    fun confirm(operationId: String): BranchReviewRound {
        results.find { it.operationId == operationId }?.let {
            require(it.kind == BranchReviewResultKind.CONFIRMED_UNDERSTOOD ||
                it.kind == BranchReviewResultKind.CONFIRMED_REVIEW) { "BRANCH_REVIEW_RESULT_CONFLICT" }
            return this
        }
        val active = requirePending(operationId)
        require(!active.rejected) { "BRANCH_REVIEW_RESULT_CONFLICT" }
        val kind = if (active.state == ManualState.UNDERSTOOD) BranchReviewResultKind.CONFIRMED_UNDERSTOOD
            else BranchReviewResultKind.CONFIRMED_REVIEW
        return BranchReviewRound(roundId, plan, results + BranchReviewResult(index, kind, operationId))
    }

    /** Rejection is not a result until the user explicitly skips; unknown never calls this method. */
    fun reject(operationId: String): BranchReviewRound {
        results.find { it.operationId == operationId }?.let {
            require(it.kind == BranchReviewResultKind.REJECTED_SKIPPED) { "BRANCH_REVIEW_RESULT_CONFLICT" }
            return this
        }
        val active = requirePending(operationId)
        if (active.rejected) return this
        return BranchReviewRound(roundId, plan, results, active.copy(rejected = true))
    }

    /** An old callback cannot skip the next question or overwrite its finalized result. */
    fun skip(index: Int): BranchReviewRound {
        require(index in plan.entries.indices && index <= this.index) { "BRANCH_REVIEW_SKIP_INDEX" }
        if (index < this.index) return this
        require(pending == null || pending.rejected) { "BRANCH_REVIEW_PENDING_OPERATION" }
        val result = pending?.let { BranchReviewResult(index, BranchReviewResultKind.REJECTED_SKIPPED, it.operationId) }
            ?: BranchReviewResult(index, BranchReviewResultKind.SKIPPED)
        return BranchReviewRound(roundId, plan, results + result)
    }

    fun retryResults(selection: BranchReviewRetrySelection): List<BranchReviewResult> {
        require(complete) { "BRANCH_REVIEW_ROUND_INCOMPLETE" }
        return results.filter { result -> when (selection) {
            BranchReviewRetrySelection.REVIEW -> result.kind == BranchReviewResultKind.CONFIRMED_REVIEW
            BranchReviewRetrySelection.SKIPPED -> result.kind == BranchReviewResultKind.SKIPPED ||
                result.kind == BranchReviewResultKind.REJECTED_SKIPPED
        } }
    }

    /** Verify every selected history/receipt in one read-only transaction first. Scope is provenance,
     * not a fresh global-state filter; even an originally understood skipped question remains selected. */
    fun retryPlan(selection: BranchReviewRetrySelection, verifiedEntries: List<BranchReviewEntryRef>): BranchReviewPlan {
        val selected = retryResults(selection)
        require(selected.isNotEmpty()) { "BRANCH_REVIEW_RETRY_EMPTY" }
        val expected = selected.map { result ->
            val original = plan.entries[result.index]
            if (result.kind == BranchReviewResultKind.CONFIRMED_REVIEW)
                original.copy(questionRevision = original.questionRevision + 1) else original
        }
        require(verifiedEntries == expected) { "BRANCH_REVIEW_RETRY_REFERENCES" }
        return BranchReviewPlan(plan.ref, plan.branchId, plan.title,
            expected.map { it.cardId }.distinct().size, 0, expected, plan.scope, expected.size, 0)
    }

    private fun requirePending(operationId: String): BranchReviewPending {
        val active = requireNotNull(pending) { "BRANCH_REVIEW_PENDING_OPERATION" }
        require(active.operationId == operationId) { "BRANCH_REVIEW_PENDING_OPERATION" }
        return active
    }
}
