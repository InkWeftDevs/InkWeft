// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID

enum class ReviewQuestionScope { ALL, REVIEW_ONLY }

/** A question identity and the exact card content selected when a session starts. */
data class BranchReviewEntryRef(
    val questionId: String,
    val questionRevision: Long,
    val cardId: String,
    val cardRevision: Long,
) {
    init {
        UUID.fromString(questionId)
        UUID.fromString(cardId)
        require(questionRevision > 0 && cardRevision > 0)
    }
}

/** References only: answers and prompts are read from their immutable revisions. */
class BranchReviewPlan(
    val ref: MapRef,
    val branchId: String?,
    val title: String,
    val cardCount: Int,
    val withoutQuestionCount: Int,
    entries: List<BranchReviewEntryRef>,
    val scope: ReviewQuestionScope = ReviewQuestionScope.ALL,
    val totalQuestionCount: Int = entries.size,
    val otherStateOnlyCardCount: Int = 0,
) {
    val entries: List<BranchReviewEntryRef> = Collections.unmodifiableList(ArrayList(entries))

    init {
        branchId?.let(UUID::fromString)
        require(title.isNotBlank() && title.length <= 120)
        require(cardCount in 0..BranchReview.MAX_CARDS)
        require(withoutQuestionCount in 0..cardCount)
        require(totalQuestionCount in this.entries.size..BranchReview.MAX_QUESTIONS)
        require(this.entries.map { it.questionId }.distinct().size == this.entries.size)
        require(this.entries.groupBy { it.cardId }.values.all { refs ->
            refs.map { it.cardRevision }.distinct().size == 1
        })
        val askedCards = cardCount - withoutQuestionCount
        require(otherStateOnlyCardCount in 0..askedCards)
        require(askedCards == this.entries.map { it.cardId }.distinct().size + otherStateOnlyCardCount)
        require(totalQuestionCount >= this.entries.size + otherStateOnlyCardCount)
        require((totalQuestionCount == 0) == (askedCards == 0))
        if (scope == ReviewQuestionScope.ALL) {
            require(totalQuestionCount == this.entries.size && otherStateOnlyCardCount == 0)
        }
    }
}

object BranchReview {
    const val MAX_CARDS = 200
    const val MAX_QUESTIONS = 2000

    /** Selects questions only after the complete author range and identities have been checked. */
    fun select(
        all: BranchReviewPlan,
        scope: ReviewQuestionScope,
        questionStates: Map<String, ManualState>,
    ): BranchReviewPlan {
        require(all.scope == ReviewQuestionScope.ALL && all.totalQuestionCount == all.entries.size &&
            all.otherStateOnlyCardCount == 0) { "BRANCH_REVIEW_SCOPE_BASE_REQUIRED" }
        require(all.entries.all { it.questionId in questionStates }) { "BRANCH_REVIEW_QUESTION_STATE_MISSING" }
        if (scope == ReviewQuestionScope.ALL) return all
        val selected = all.entries.filter { questionStates.getValue(it.questionId) == ManualState.REVIEW }
        val otherStateOnly = all.entries.map { it.cardId }.distinct().size - selected.map { it.cardId }.distinct().size
        return BranchReviewPlan(all.ref, all.branchId, all.title, all.cardCount, all.withoutQuestionCount,
            selected, scope, all.totalQuestionCount, otherStateOnly)
    }

    /** Traverses author hierarchy, independently of folding, viewport and embed depth. */
    fun plan(
        scene: MapScene,
        branchId: String?,
        questions: List<BranchReviewEntryRef>,
    ): BranchReviewPlan {
        require(scene.available) { "BRANCH_REVIEW_MAP_UNAVAILABLE" }
        StudyGraph.validate(scene.nodes.map {
            StudyNode(it.id, it.cardId ?: it.id, it.parentId, it.x, it.y, it.revision)
        })
        val selected = if (branchId == null) scene.nodes else {
            val root = requireNotNull(scene.nodes.find { it.id == branchId }) {
                "BRANCH_REVIEW_BRANCH_UNAVAILABLE"
            }
            val children = scene.nodes.groupBy { it.parentId }
            val queue = ArrayDeque<MapSceneNode>()
            val visited = mutableSetOf<String>()
            val descendants = mutableListOf<MapSceneNode>()
            queue.addLast(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!visited.add(node.id)) continue
                require(visited.size <= StudyGraph.MAX_NODES) { "BRANCH_REVIEW_NODE_BUDGET" }
                descendants.add(node)
                children[node.id].orEmpty().forEach { queue.addLast(it) }
            }
            descendants
        }
        val cards = linkedMapOf<String, Long>()
        selected.forEach { node ->
            node.cardId?.let { cardId ->
                val previous = cards.put(cardId, node.contentRevision)
                require(previous == null || previous == node.contentRevision) {
                    "BRANCH_REVIEW_CARD_REVISION_MISMATCH"
                }
            }
        }
        val title = branchId?.let { id -> selected.first { it.id == id }.title } ?: scene.title
        return prepare(scene.ref, branchId, title, cards, questions)
    }

    /** Whole-notebook recall includes active cards with no current map occurrence. */
    fun notebook(
        book: String,
        cards: Map<String, Long>,
        questions: List<BranchReviewEntryRef>,
    ): BranchReviewPlan = prepare(MapRef(book), null, "本笔记", cards, questions)

    /** Named collection recall uses its matched cards, including those not placed on a map. */
    fun collection(
        book: String,
        title: String,
        cards: Map<String, Long>,
        questions: List<BranchReviewEntryRef>,
    ): BranchReviewPlan = prepare(MapRef(book), null, title, cards, questions)

    private fun prepare(
        ref: MapRef,
        branchId: String?,
        title: String,
        cards: Map<String, Long>,
        questions: List<BranchReviewEntryRef>,
    ): BranchReviewPlan {
        require(cards.size <= MAX_CARDS && questions.size <= MAX_QUESTIONS) {
            "BRANCH_REVIEW_BUDGET"
        }
        cards.forEach { (id, revision) -> UUID.fromString(id); require(revision > 0) }
        val unique = linkedMapOf<String, BranchReviewEntryRef>()
        questions.forEach { question ->
            val revision = cards[question.cardId] ?: return@forEach
            require(revision == question.cardRevision) { "BRANCH_REVIEW_CARD_REVISION_MISMATCH" }
            val previous = unique.put(question.questionId, question)
            require(previous == null || previous == question) { "BRANCH_REVIEW_QUESTION_ID_REUSE" }
        }
        val entries = unique.values.sortedBy { it.questionId }
        val askedCards = entries.map { it.cardId }.toSet()
        return BranchReviewPlan(ref, branchId, title, cards.size, cards.size - askedCards.size, entries)
    }
}
