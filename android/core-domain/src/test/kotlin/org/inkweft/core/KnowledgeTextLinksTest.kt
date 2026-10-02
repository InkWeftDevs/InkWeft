package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class KnowledgeTextLinksTest {
    private fun id() = UUID.randomUUID().toString()
    private fun target(vararg terms: String, available: Boolean = true) = KnowledgeTextTarget(
        id(), 1, TargetRef(TargetKind.CARD, id()), RelationKind.REFERENCE, null, "已确认知识 · 笔记", terms.toList(), available)

    @Test fun onlyExplicitTermsMatchLiteralPunctuationWithoutChangingOriginalText() {
        val text = "未确认提及 [[新知识]]；x[y] 和 C++ 只按字面匹配。xy ccc"
        val dictionary = listOf(target("x[y]", "C++"))
        val spans = KnowledgeTextLinks.spans(text, dictionary)
        assertEquals(listOf("x[y]", "C++"), spans.map { text.substring(it.start, it.end) })
        assertTrue(KnowledgeTextLinks.spans(text, emptyList()).isEmpty())
        assertEquals("未确认提及 [[新知识]]；x[y] 和 C++ 只按字面匹配。xy ccc", text)
    }

    @Test fun firstPositionWinsCrossOverlapAndLongestTermWinsAtThatPosition() {
        val short = target("甲乙")
        val long = target("乙丙丁")
        assertEquals(listOf(KnowledgeTextSpan(0, 2, listOf(short.linkId))),
            KnowledgeTextLinks.spans("甲乙丙丁", listOf(short, long)))
        val nested = target("甲乙丙丁")
        assertEquals(listOf(KnowledgeTextSpan(0, 4, listOf(nested.linkId))),
            KnowledgeTextLinks.spans("甲乙丙丁", listOf(short, nested)))
    }

    @Test fun asciiWordsIgnoreCaseButNeverMatchInsideAnotherAsciiWord() {
        val text = "Work WORK work cowork worker _work work2 2work 中文work中文"
        val term = target("work")
        assertEquals(listOf(0, 5, 10, text.lastIndexOf("work")),
            KnowledgeTextLinks.spans(text, listOf(term)).map { it.start })
        assertTrue(KnowledgeTextLinks.spans("a_work work_ aWork work9", listOf(term)).isEmpty())
    }

    @Test fun equalNamesKeepEveryLinkAndRepeatedAliasSpellingDoesNotDuplicateIt() {
        val one = target("共享", "SHARED", "shared")
        val two = target("共享", "Shared")
        val spans = KnowledgeTextLinks.spans("共享 shared", listOf(two, one))
        assertEquals(2, spans.size)
        spans.forEach { assertEquals(setOf(one.linkId, two.linkId), it.linkIds.toSet()) }
    }

    @Test fun utf16OffsetsKeepEmojiAndCombiningMarksIntactWithoutNormalization() {
        val text = "🧠前😀算法和Cafe\u0301"
        val terms = target("😀算法", "Cafe\u0301", "Café")
        val spans = KnowledgeTextLinks.spans(text, listOf(terms))
        assertEquals(listOf(3 to 7, 8 to 13), spans.map { it.start to it.end })
        assertEquals(listOf("😀算法", "Cafe\u0301"), spans.map { text.substring(it.start, it.end) })
        assertTrue(KnowledgeTextLinks.spans("😀", listOf(target("\uD83D", "\uDE00"))).isEmpty())
    }

    @Test fun archivedConfirmedTargetCanStillExposeItsOriginalLinkIdentity() {
        val archived = target("历史标题", available = false)
        assertEquals(listOf(KnowledgeTextSpan(0, 4, listOf(archived.linkId))),
            KnowledgeTextLinks.spans("历史标题", listOf(archived)))
    }

    @Test fun targetTermAndTextBudgetsDisableInlineWithoutDroppingCallerAssociations() {
        val manyTargets = List(KnowledgeTextLinks.MAX_TARGETS + 1) { target("词") }
        assertTrue(KnowledgeTextLinks.spans("词", manyTargets).isEmpty())
        assertEquals(KnowledgeTextLinks.MAX_TARGETS + 1, manyTargets.size)
        val manyTerms = listOf(target(*Array(KnowledgeTextLinks.MAX_TERMS + 1) { "词" }))
        assertTrue(KnowledgeTextLinks.spans("词", manyTerms).isEmpty())
        assertTrue(KnowledgeTextLinks.spans("词".repeat(KnowledgeTextLinks.MAX_TEXT + 1), listOf(target("词"))).isEmpty())
    }

    @Test fun spanBudgetReturnsNoPartialCoverageAtOverflow() {
        val term = target("词")
        assertEquals(KnowledgeTextLinks.MAX_SPANS,
            KnowledgeTextLinks.spans("词 ".repeat(KnowledgeTextLinks.MAX_SPANS), listOf(term)).size)
        assertTrue(KnowledgeTextLinks.spans("词 ".repeat(KnowledgeTextLinks.MAX_SPANS + 1), listOf(term)).isEmpty())
    }
}
