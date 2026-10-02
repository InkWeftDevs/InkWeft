// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.BranchReview
import org.inkweft.core.BranchReviewEntryRef
import org.inkweft.core.BranchReviewPlan
import org.inkweft.core.KnowledgeData
import org.inkweft.core.KnowledgeQueries
import org.inkweft.core.MapRef
import org.inkweft.core.ManualState
import org.inkweft.core.ReviewQuestionScope

data class FrozenBranchReviewQuestion(
    val reference: BranchReviewEntryRef,
    val question: KnowledgeRow,
    val card: StudyCardRevisionRow,
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
        val cards = mutableMapOf<String, StudyCardRevisionRow>()
        plan.entries.map { reference ->
            val revision = requireNotNull(db.knowledge().revision(reference.questionId, reference.questionRevision)) {
                "BRANCH_REVIEW_QUESTION_REVISION_MISSING"
            }
            require(revision.notebookId == plan.ref.notebookId && !revision.removed) {
                "BRANCH_REVIEW_QUESTION_SCOPE_CHANGED"
            }
            val question = KnowledgeRow(revision.id, revision.notebookId, revision.revision, revision.payload.copyOf(), revision.removed)
            val data = question.data() as? KnowledgeData.Question
            require(data?.cardId == reference.cardId) { "BRANCH_REVIEW_QUESTION_CARD_CHANGED" }
            val card = cards[reference.cardId] ?: run {
                val currentCard = requireNotNull(db.study().card(reference.cardId)) {
                    "BRANCH_REVIEW_CARD_UNAVAILABLE"
                }
                require(currentCard.notebookId == plan.ref.notebookId) { "BRANCH_REVIEW_CARD_SCOPE_CHANGED" }
                val frozen = requireNotNull(db.study().cardVersion(reference.cardId, reference.cardRevision)) {
                    "BRANCH_REVIEW_CARD_REVISION_MISSING"
                }
                require(frozen.trashedAt == null) { "BRANCH_REVIEW_CARD_REVISION_UNAVAILABLE" }
                frozen.also { cards[reference.cardId] = it }
            }
            // Recycling after entry does not erase the frozen answer. Guarded marking checks current activity/revision.
            FrozenBranchReviewQuestion(reference, question, card)
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
