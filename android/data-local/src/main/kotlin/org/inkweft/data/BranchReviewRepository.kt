// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.BranchReview
import org.inkweft.core.BranchReviewEntryRef
import org.inkweft.core.BranchReviewPlan
import org.inkweft.core.BranchReviewRound
import org.inkweft.core.BranchReviewResultKind
import org.inkweft.core.BranchReviewRetrySelection
import org.inkweft.core.KnowledgeCommand
import org.inkweft.core.KnowledgeData
import org.inkweft.core.KnowledgeQueries
import org.inkweft.core.MapRef
import org.inkweft.core.ManualState
import org.inkweft.core.ReviewQuestionScope

data class FrozenBranchReviewQuestion(
    val reference: BranchReviewEntryRef,
    val question: KnowledgeRow,
    val card: StudyCardRevisionRow,
    val sources: FrozenStudySources = FrozenStudySources(emptyList(),emptyList(),false),
)

/** Read-only session preparation. Marking still uses the existing guarded command/receipt path. */
class BranchReviewRepository(private val db: NoteDatabase) {
    suspend fun prepare(ref: MapRef, branchId: String?, scope: ReviewQuestionScope = ReviewQuestionScope.ALL): BranchReviewPlan = db.withTransaction {
        requireAvailableBook(ref.notebookId)
        val scene = requireNotNull(MapGraphAccess(db).read(ref.notebookId).find { it.ref == ref }) {
            "BRANCH_REVIEW_MAP_UNAVAILABLE"
        }
        val cards = activeCards(ref.notebookId)
        val questions = questionReferences(db.knowledge().forBook(ref.notebookId), cards)
        BranchReview.select(BranchReview.plan(scene, branchId, questions.map { it.first }), scope,
            questions.associate { it.first.questionId to it.second })
    }

    suspend fun prepareNotebook(book: String, scope: ReviewQuestionScope = ReviewQuestionScope.ALL): BranchReviewPlan = db.withTransaction {
        requireAvailableBook(book)
        val cards = activeCards(book)
        val questions = questionReferences(db.knowledge().forBook(book), cards)
        BranchReview.select(BranchReview.notebook(book, cards.mapValues { it.value.revision }, questions.map { it.first }), scope,
            questions.associate { it.first.questionId to it.second })
    }

    suspend fun prepareCard(ref: MapRef, cardId: String, expectedCardRevision: Long,
                            nodeId: String? = null, scope: ReviewQuestionScope = ReviewQuestionScope.ALL): BranchReviewPlan = db.withTransaction {
        requireAvailableBook(ref.notebookId)
        ref.mapId?.let { mapId ->
            val map = db.knowledge().get(mapId)
            require(map != null && map.notebookId == ref.notebookId && !map.removed && map.data() is KnowledgeData.MapDefinition) {
                "BRANCH_REVIEW_MAP_UNAVAILABLE"
            }
        }
        val card = requireNotNull(db.study().card(cardId)) { "BRANCH_REVIEW_CARD_UNAVAILABLE" }
        require(card.notebookId == ref.notebookId && card.trashedAt == null) { "BRANCH_REVIEW_CARD_UNAVAILABLE" }
        require(card.revision == expectedCardRevision) { "BRANCH_REVIEW_CARD_VERSION_CHANGED" }
        nodeId?.let { id ->
            val matches = if (ref.mapId == null) {
                db.study().node(id)?.let { it.notebookId == ref.notebookId && !it.removed && it.cardId == card.id } == true
            } else {
                val row = db.knowledge().get(id)
                val node = row?.data() as? KnowledgeData.MapOccurrence
                row != null && row.notebookId == ref.notebookId && !row.removed && node != null && node.mapId == ref.mapId && node.cardId == card.id
            }
            require(matches) { "BRANCH_REVIEW_NODE_UNAVAILABLE" }
        }
        val rows = db.knowledge().forBook(ref.notebookId).filter {
            !it.removed && (it.data() as? KnowledgeData.Question)?.cardId == card.id
        }
        val questions = questionReferences(rows, mapOf(card.id to card))
        BranchReview.select(BranchReview.card(ref, card.id, card.revision, card.title, questions.map { it.first }, nodeId),
            scope, questions.associate { it.first.questionId to it.second })
    }

    suspend fun prepareCollection(book: String, collectionId: String, expectedRevision: Long, scope: ReviewQuestionScope = ReviewQuestionScope.ALL): BranchReviewPlan = db.withTransaction {
        requireAvailableBook(book)
        val row = requireNotNull(db.knowledge().get(collectionId)) { "BRANCH_REVIEW_COLLECTION_UNAVAILABLE" }
        require(row.notebookId == book && !row.removed) { "BRANCH_REVIEW_COLLECTION_UNAVAILABLE" }
        val collection = requireNotNull(row.data() as? KnowledgeData.Collection) { "BRANCH_REVIEW_COLLECTION_UNAVAILABLE" }
        require(row.revision == expectedRevision) { "BRANCH_REVIEW_COLLECTION_VERSION_CHANGED" }
        val rows = db.knowledge().forBook(book)
        val properties = rows.filter { !it.removed }
            .mapNotNull { it.data() as? KnowledgeData.Properties }.associateBy { it.cardId }
        val cards = activeCards(book).filterValues { card ->
            KnowledgeQueries.matches(collection, properties[card.id] ?: KnowledgeData.Properties(card.id))
        }
        val questions = questionReferences(rows, cards)
        BranchReview.select(BranchReview.collection(book, collection.title, cards.mapValues { it.value.revision }, questions.map { it.first }), scope,
            questions.associate { it.first.questionId to it.second })
    }

    suspend fun load(plan: BranchReviewPlan): List<FrozenBranchReviewQuestion> = db.withTransaction {
        loadReferences(plan.ref.notebookId, plan.entries)
    }

    /** A saved writer success is not a score until its original receipt and causal history agree. */
    suspend fun confirmRoundResult(round: BranchReviewRound): BranchReviewRound = db.withTransaction {
        val pending = round.pending ?: return@withTransaction round
        require(!pending.rejected) { "BRANCH_REVIEW_RESULT_CONFLICT" }
        verifyMark(round.plan.ref.notebookId, round.plan.entries[pending.index], pending.operationId, pending.state)
        round.confirm(pending.operationId)
    }

    /** Select only this completed round's exact outcomes; never search current questions as substitutes. */
    suspend fun prepareRetry(round: BranchReviewRound, selection: BranchReviewRetrySelection): BranchReviewPlan = db.withTransaction {
        val selected = round.retryResults(selection)
        require(selected.isNotEmpty()) { "BRANCH_REVIEW_RETRY_EMPTY" }
        requireAvailableBook(round.plan.ref.notebookId)
        val entries = selected.map { result ->
            val original = round.plan.entries[result.index]
            if (result.kind == BranchReviewResultKind.CONFIRMED_REVIEW)
                verifyMark(round.plan.ref.notebookId, original, requireNotNull(result.operationId), ManualState.REVIEW)
            else original
        }
        val plan = round.retryPlan(selection, entries)
        loadReferences(plan.ref.notebookId, plan.entries)
        plan
    }

    private suspend fun verifyMark(book: String, reference: BranchReviewEntryRef,
                                   operationId: String, state: ManualState): BranchReviewEntryRef {
        require(state == ManualState.UNDERSTOOD || state == ManualState.REVIEW) { "BRANCH_REVIEW_MARK_STATE" }
        val original = loadReferences(book, listOf(reference)).single()
        val question = original.question.data() as KnowledgeData.Question
        val command = KnowledgeCommand(operationId, book, reference.questionId, reference.questionRevision,
            question.copy(state = state))
        val receipt = requireNotNull(db.knowledge().receipt(operationId)) { "BRANCH_REVIEW_RECEIPT_MISSING" }
        require(receipt.notebookId == book && receipt.digest == command.digest() && receipt.resultId == reference.questionId) {
            "BRANCH_REVIEW_RECEIPT_MISMATCH"
        }
        val next = requireNotNull(db.knowledge().revision(reference.questionId, reference.questionRevision + 1)) {
            "BRANCH_REVIEW_RESULT_REVISION_MISSING"
        }
        require(next.notebookId == book && !next.removed && next.payload.contentEquals(command.payload)) {
            "BRANCH_REVIEW_RESULT_REVISION_MISMATCH"
        }
        // Later author edits or recycling do not invalidate an already committed, exact receipt.
        return reference.copy(questionRevision = reference.questionRevision + 1)
    }

    private suspend fun loadReferences(book: String, entries: List<BranchReviewEntryRef>): List<FrozenBranchReviewQuestion> {
        val cards = mutableMapOf<Pair<String,Long>, StudyCardRevisionRow>()
        val sources = mutableMapOf<Pair<String,Long>, FrozenStudySources>()
        val sourceRows = mutableMapOf<org.inkweft.core.StudySourceVersionRef, StudySourceRevisionRow>()
        return entries.map { reference ->
            val revision = requireNotNull(db.knowledge().revision(reference.questionId, reference.questionRevision)) {
                "BRANCH_REVIEW_QUESTION_REVISION_MISSING"
            }
            require(revision.notebookId == book && !revision.removed) {
                "BRANCH_REVIEW_QUESTION_SCOPE_CHANGED"
            }
            val question = KnowledgeRow(revision.id, revision.notebookId, revision.revision, revision.payload.copyOf(), revision.removed)
            val data = question.data() as? KnowledgeData.Question
            require(data?.cardId == reference.cardId) { "BRANCH_REVIEW_QUESTION_CARD_CHANGED" }
            val card = cards[reference.cardId to reference.cardRevision] ?: run {
                val currentCard = requireNotNull(db.study().card(reference.cardId)) {
                    "BRANCH_REVIEW_CARD_UNAVAILABLE"
                }
                require(currentCard.notebookId == book) { "BRANCH_REVIEW_CARD_SCOPE_CHANGED" }
                val frozen = requireNotNull(db.study().cardVersion(reference.cardId, reference.cardRevision)) {
                    "BRANCH_REVIEW_CARD_REVISION_MISSING"
                }
                require(frozen.trashedAt == null) { "BRANCH_REVIEW_CARD_REVISION_UNAVAILABLE" }
                frozen.also { cards[reference.cardId to reference.cardRevision] = it }
            }
            // Recycling after entry does not erase the frozen answer. Guarded marking checks current activity/revision.
            val sourceKey=reference.cardId to reference.cardRevision
            val frozenSources=sources[sourceKey]?:StudySourceVersions(db).read(reference.cardId,reference.cardRevision,sourceRows).also{sources[sourceKey]=it}
            FrozenBranchReviewQuestion(reference, question, card, frozenSources)
        }
    }

    private suspend fun requireAvailableBook(book: String) {
        val workspace = db.workspace().get(book)
        require(db.notes().note(book) != null && workspace != null && workspace.trashedAt == null) {
            "BRANCH_REVIEW_BOOK_UNAVAILABLE"
        }
    }

    private suspend fun activeCards(book: String) = db.study().cards(book)
        .filter { it.trashedAt == null }.associateBy { it.id }

    private fun questionReferences(rows: List<KnowledgeRow>, cards: Map<String, StudyCardRow>): List<Pair<BranchReviewEntryRef, ManualState>> =
        rows.filter { !it.removed }.mapNotNull { row ->
            val question = row.data() as? KnowledgeData.Question ?: return@mapNotNull null
            val card = cards[question.cardId] ?: return@mapNotNull null
            BranchReviewEntryRef(row.id, row.revision, card.id, card.revision) to question.state
        }
}
