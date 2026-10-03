package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BranchReviewTest {
    private fun id() = UUID.randomUUID().toString()
    private fun node(card: String? = id(), parent: String? = null, title: String = "知识点") =
        MapSceneNode(id(), parent, card, title, "答案", 40.0, 80.0, 1, 1)
    private fun scene(nodes: List<MapSceneNode>) = MapScene(MapRef(id()), "完整导图", nodes, "a".repeat(64))
    private fun question(card: String, revision: Long = 1) = BranchReviewEntryRef(id(), 1, card, revision)
    private fun rejected(message: String, action: () -> Unit) {
        try { action(); fail("Expected " + message) } catch (e: IllegalArgumentException) { assertEquals(message, e.message) }
    }

    @Test fun branchTraversesFortyLevelsWithoutEmbedDepthTruncation() {
        val nodes = mutableListOf<MapSceneNode>()
        repeat(40) { depth -> nodes.add(node(parent = nodes.lastOrNull()?.id, title = "层 " + depth)) }
        val questions = nodes.map { question(it.cardId!!) }
        val plan = BranchReview.plan(scene(nodes), nodes.first().id, questions)
        assertEquals("层 0", plan.title)
        assertEquals(40, plan.cardCount)
        assertEquals(0, plan.withoutQuestionCount)
        assertEquals(questions.map { it.questionId }.toSet(), plan.entries.map { it.questionId }.toSet())
        assertTrue(plan.entries.any { it.cardId == nodes.last().cardId })
    }

    @Test fun repeatedCardPositionsKeepTwoQuestionsAndDoNotCountStructureAsCard() {
        val root = node(card = null, title = "结构分支")
        val first = node(parent = root.id)
        val repeated = node(card = first.cardId, parent = root.id)
        val withoutQuestion = node(parent = repeated.id)
        val outside = node()
        val sharedCard = checkNotNull(first.cardId)
        val firstQuestion = question(sharedCard)
        val secondQuestion = question(sharedCard)
        val outsideQuestion = question(outside.cardId!!)
        val plan = BranchReview.plan(scene(listOf(root, first, repeated, withoutQuestion, outside)), root.id,
            listOf(firstQuestion, secondQuestion, firstQuestion, outsideQuestion))
        assertEquals("结构分支", plan.title)
        assertEquals(2, plan.cardCount)
        assertEquals(1, plan.withoutQuestionCount)
        assertEquals(setOf(firstQuestion.questionId, secondQuestion.questionId), plan.entries.map { it.questionId }.toSet())
    }

    @Test fun missingBranchAndUnavailableMapNeverFallBackToWholeGraph() {
        val scene = scene(listOf(node()))
        rejected("BRANCH_REVIEW_BRANCH_UNAVAILABLE") { BranchReview.plan(scene, id(), emptyList()) }
        rejected("BRANCH_REVIEW_MAP_UNAVAILABLE") { BranchReview.plan(scene.copy(available = false), null, emptyList()) }
    }

    @Test fun conflictingQuestionIdentityAndCardRevisionAreRejected() {
        val node = node()
        val question = question(node.cardId!!)
        rejected("BRANCH_REVIEW_QUESTION_ID_REUSE") {
            BranchReview.plan(scene(listOf(node)), null, listOf(question, question.copy(questionRevision = 2)))
        }
        rejected("BRANCH_REVIEW_CARD_REVISION_MISMATCH") {
            BranchReview.plan(scene(listOf(node)), null, listOf(question.copy(cardRevision = 2)))
        }
    }

    @Test fun notebookIncludesUnplacedCardsAndCopiesCallerOwnedQueue() {
        val book = id()
        val placed = id()
        val unplaced = id()
        val noQuestion = id()
        val questions = mutableListOf(question(placed), question(unplaced))
        val plan = BranchReview.notebook(book, linkedMapOf(placed to 1L, unplaced to 1L, noQuestion to 2L), questions)
        questions.clear()
        assertEquals(MapRef(book), plan.ref)
        assertNull(plan.branchId)
        assertEquals("本笔记", plan.title)
        assertEquals(3, plan.cardCount)
        assertEquals(1, plan.withoutQuestionCount)
        assertEquals(2, plan.entries.size)
        val callerQueue = plan.entries.toMutableList()
        val copied = BranchReviewPlan(plan.ref, null, plan.title, plan.cardCount, plan.withoutQuestionCount, callerQueue)
        callerQueue.clear()
        assertEquals(2, copied.entries.size)
    }

    @Test fun fullMapDoesNotIncludeQuestionsForUnplacedCards() {
        val node = node()
        val placed = question(node.cardId!!)
        val unplaced = question(id())
        val plan = BranchReview.plan(scene(listOf(node)), null, listOf(placed, unplaced))
        assertEquals(1, plan.cardCount)
        assertEquals(listOf(placed), plan.entries)
    }

    @Test fun namedCollectionKeepsFullTitleAndIndependentQuestionsWithinMatchedCards() {
        val book = id()
        val title = "集".repeat(120)
        val matched = id()
        val noQuestion = id()
        val first = question(matched)
        val second = question(matched)
        val outside = question(id())
        val cards = linkedMapOf(matched to 1L, noQuestion to 2L)
        val questions = mutableListOf(first, second, first, outside)
        val plan = BranchReview.collection(book, title, cards, questions)
        cards.clear()
        questions.clear()
        assertEquals(MapRef(book), plan.ref)
        assertNull(plan.branchId)
        assertEquals(title, plan.title)
        assertEquals(2, plan.cardCount)
        assertEquals(1, plan.withoutQuestionCount)
        assertEquals(setOf(first.questionId, second.questionId), plan.entries.map { it.questionId }.toSet())
    }

    @Test fun emptyOrUnaskedCollectionNeverBorrowsQuestionsAndRetainsTitleLimit() {
        val book = id()
        val outside = question(id())
        val empty = BranchReview.collection(book, "空集合", emptyMap(), listOf(outside))
        assertEquals(0, empty.cardCount)
        assertEquals(0, empty.withoutQuestionCount)
        assertTrue(empty.entries.isEmpty())
        val unasked = BranchReview.collection(book, "尚未设题", mapOf(id() to 1L), listOf(outside))
        assertEquals(1, unasked.cardCount)
        assertEquals(1, unasked.withoutQuestionCount)
        assertTrue(unasked.entries.isEmpty())
        for (title in listOf(" ", "集".repeat(121))) {
            try {
                BranchReview.collection(book, title, emptyMap(), emptyList())
                fail("Expected invalid collection title")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun reviewOnlyUsesQuestionDefaultAndKeepsOtherManualStatesInAll() {
        val card = id()
        val defaultQuestion = question(card, 4)
        val inbox = question(card, 4)
        val understood = question(card, 4)
        val all = BranchReview.notebook(id(), mapOf(card to 4L), listOf(defaultQuestion, inbox, understood))
        val states = mutableMapOf(defaultQuestion.questionId to KnowledgeData.Question(card, "默认新题").state,
            inbox.questionId to ManualState.INBOX, understood.questionId to ManualState.UNDERSTOOD)
        assertEquals(ManualState.REVIEW, states[defaultQuestion.questionId])
        assertSame(all, BranchReview.select(all, ReviewQuestionScope.ALL, states))
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY, states)
        states.clear()
        assertEquals(listOf(defaultQuestion), selected.entries)
        assertEquals(ReviewQuestionScope.REVIEW_ONLY, selected.scope)
        assertEquals(3, selected.totalQuestionCount)
        assertEquals(1, selected.cardCount)
        assertEquals(0, selected.withoutQuestionCount)
        assertEquals(0, selected.otherStateOnlyCardCount)
        assertEquals(3, all.entries.size)
        assertEquals(ReviewQuestionScope.ALL, all.scope)
    }

    @Test fun stateScopeCountsRealUnaskedAndOtherStateOnlyCardsSeparately() {
        val mixed = id()
        val other = id()
        val unasked = id()
        val pending = question(mixed)
        val mixedUnderstood = question(mixed)
        val otherInbox = question(other)
        val otherUnderstood = question(other)
        val all = BranchReview.collection(id(), "状态范围", mapOf(mixed to 1L, other to 1L, unasked to 1L),
            listOf(pending, mixedUnderstood, otherInbox, otherUnderstood, pending))
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY, mapOf(
            pending.questionId to ManualState.REVIEW, mixedUnderstood.questionId to ManualState.UNDERSTOOD,
            otherInbox.questionId to ManualState.INBOX, otherUnderstood.questionId to ManualState.UNDERSTOOD))
        assertEquals(listOf(pending), selected.entries)
        assertEquals(3, selected.cardCount)
        assertEquals(4, selected.totalQuestionCount)
        assertEquals(1, selected.withoutQuestionCount)
        assertEquals("Two excluded questions on one card count as one other-state-only card", 1,
            selected.otherStateOnlyCardCount)
        assertEquals(4, all.entries.size)
        assertEquals(0, all.otherStateOnlyCardCount)
    }

    @Test fun noSelectedQuestionsDistinguishAllUnderstoodEmptyAndRealUnaskedRanges() {
        val card = id()
        val understood = question(card)
        val all = BranchReview.notebook(id(), mapOf(card to 1L), listOf(understood))
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY,
            mapOf(understood.questionId to ManualState.UNDERSTOOD))
        assertTrue(selected.entries.isEmpty())
        assertEquals(1, selected.totalQuestionCount)
        assertEquals(0, selected.withoutQuestionCount)
        assertEquals(1, selected.otherStateOnlyCardCount)
        val empty = BranchReview.select(BranchReview.collection(id(), "空范围", emptyMap(), emptyList()),
            ReviewQuestionScope.REVIEW_ONLY, emptyMap())
        assertEquals(0, empty.cardCount)
        assertEquals(0, empty.totalQuestionCount)
        assertEquals(0, empty.otherStateOnlyCardCount)
        val unasked = BranchReview.select(BranchReview.notebook(id(), mapOf(id() to 1L), emptyList()),
            ReviewQuestionScope.REVIEW_ONLY, emptyMap())
        assertEquals(1, unasked.cardCount)
        assertEquals(1, unasked.withoutQuestionCount)
        assertEquals(0, unasked.totalQuestionCount)
        assertEquals(0, unasked.otherStateOnlyCardCount)
    }

    @Test fun selectionRequiresExplicitStatesAndAnOriginalAllPlan() {
        val card = id()
        val pending = question(card)
        val all = BranchReview.notebook(id(), mapOf(card to 1L), listOf(pending))
        for (scope in ReviewQuestionScope.entries) {
            rejected("BRANCH_REVIEW_QUESTION_STATE_MISSING") { BranchReview.select(all, scope, emptyMap()) }
        }
        val states = mapOf(pending.questionId to ManualState.REVIEW)
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY, states)
        rejected("BRANCH_REVIEW_SCOPE_BASE_REQUIRED") {
            BranchReview.select(selected, ReviewQuestionScope.ALL, states)
        }
    }

    @Test fun stateSelectionCannotHideIdentityRevisionOrUnfilteredBudgetFailures() {
        val card = id()
        val first = question(card)
        val states = mapOf(first.questionId to ManualState.UNDERSTOOD)
        rejected("BRANCH_REVIEW_CARD_REVISION_MISMATCH") {
            BranchReview.select(BranchReview.notebook(id(), mapOf(card to 1L), listOf(first.copy(cardRevision = 2))),
                ReviewQuestionScope.REVIEW_ONLY, states)
        }
        rejected("BRANCH_REVIEW_QUESTION_ID_REUSE") {
            BranchReview.select(BranchReview.notebook(id(), mapOf(card to 1L), listOf(first, first.copy(questionRevision = 2))),
                ReviewQuestionScope.REVIEW_ONLY, states)
        }
        val tooManyQuestions = List(BranchReview.MAX_QUESTIONS + 1) { question(card) }
        rejected("BRANCH_REVIEW_BUDGET") {
            BranchReview.select(BranchReview.notebook(id(), mapOf(card to 1L), tooManyQuestions),
                ReviewQuestionScope.REVIEW_ONLY, tooManyQuestions.associate { it.questionId to ManualState.UNDERSTOOD })
        }
        val tooManyCards = (0..BranchReview.MAX_CARDS).associate { id() to 1L }
        rejected("BRANCH_REVIEW_BUDGET") {
            BranchReview.select(BranchReview.notebook(id(), tooManyCards, emptyList()),
                ReviewQuestionScope.REVIEW_ONLY, emptyMap())
        }
    }

    @Test fun planRejectsFalseStateCountsAndCopiesTheSelectedQueue() {
        val ref = MapRef(id())
        val entry = question(id())
        val source = mutableListOf(entry)
        val selected = BranchReviewPlan(ref, null, "仅待复习", 1, 0, source,
            ReviewQuestionScope.REVIEW_ONLY, 2, 0)
        source.clear()
        assertEquals(listOf(entry), selected.entries)
        val invalid = listOf<() -> BranchReviewPlan>(
            { BranchReviewPlan(ref, null, "全部", 2, 0, listOf(entry), ReviewQuestionScope.ALL, 2, 1) },
            { BranchReviewPlan(ref, null, "总题数少于本轮题", 1, 0, listOf(entry), ReviewQuestionScope.REVIEW_ONLY, 0, 0) },
            { BranchReviewPlan(ref, null, "漏计另一卡的题", 2, 0, listOf(entry), ReviewQuestionScope.REVIEW_ONLY, 1, 1) },
            { BranchReviewPlan(ref, null, "有题却未归类", 1, 0, emptyList(), ReviewQuestionScope.REVIEW_ONLY, 1, 0) },
            { BranchReviewPlan(ref, null, "真无题却有总题数", 1, 1, emptyList(), ReviewQuestionScope.REVIEW_ONLY, 1, 0) },
            { BranchReviewPlan(ref, null, "总题数超预算", 1, 0, listOf(entry), ReviewQuestionScope.REVIEW_ONLY,
                BranchReview.MAX_QUESTIONS + 1, 0) },
        )
        invalid.forEach { action ->
            try { action(); fail("Expected inconsistent state metadata to be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun currentCardKeepsIndependentQuestionsAndExcludesOtherCardsBeforeStateSelection() {
        val ref = MapRef(id(), id()); val card = id(); val node = id()
        val pending = question(card, 4); val understood = question(card, 4); val outside = question(id())
        val all = BranchReview.card(ref, card, 4, "当前知识卡", listOf(pending, understood, outside), node)
        assertEquals(ref, all.ref); assertEquals(node, all.branchId); assertEquals("当前知识卡", all.title)
        assertEquals(1, all.cardCount); assertEquals(0, all.withoutQuestionCount)
        assertEquals(setOf(pending, understood), all.entries.toSet())
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY,
            mapOf(pending.questionId to ManualState.REVIEW, understood.questionId to ManualState.UNDERSTOOD,
                outside.questionId to ManualState.REVIEW))
        assertEquals(listOf(pending), selected.entries)
        assertEquals(2, selected.totalQuestionCount); assertEquals(0, selected.otherStateOnlyCardCount)
    }

    @Test fun currentCardDistinguishesUnaskedAndOtherStatesAndRejectsStaleRevision() {
        val ref = MapRef(id()); val card = id()
        val understood = question(card, 2); val inbox = question(card, 2)
        val all = BranchReview.card(ref, card, 2, "未放置卡片", listOf(understood, inbox))
        assertNull(all.branchId)
        val selected = BranchReview.select(all, ReviewQuestionScope.REVIEW_ONLY,
            mapOf(understood.questionId to ManualState.UNDERSTOOD, inbox.questionId to ManualState.INBOX))
        assertTrue(selected.entries.isEmpty()); assertEquals(2, selected.totalQuestionCount)
        assertEquals(0, selected.withoutQuestionCount); assertEquals(1, selected.otherStateOnlyCardCount)
        val unasked = BranchReview.card(ref, card, 2, "真正无题", listOf(question(id())))
        assertEquals(1, unasked.cardCount); assertEquals(1, unasked.withoutQuestionCount); assertEquals(0, unasked.totalQuestionCount)
        rejected("BRANCH_REVIEW_CARD_REVISION_MISMATCH") {
            BranchReview.select(BranchReview.card(ref, card, 1, "旧版本", listOf(understood)),
                ReviewQuestionScope.REVIEW_ONLY, mapOf(understood.questionId to ManualState.UNDERSTOOD))
        }
    }
}
