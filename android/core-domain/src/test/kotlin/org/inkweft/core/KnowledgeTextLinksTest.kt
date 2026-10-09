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

    @Test fun unicodeCaseAndOverlapsMatchThePreviousLiteralContract(){
        val dictionary=listOf(target("work","共享","共享知识","C++","Σ","İ","K","𐐀"),target("共享","σ","i","k","𐐨"))
        val texts=listOf("WORK worker 共享知识共享 C++ σ Σ ς İ i ı K k 𐐀𐐨", "xKx xİx 共享知识", "🧠共享𐐨 C++")
        texts.forEach{assertEquals(reference(it,dictionary),KnowledgeTextLinks.spans(it,dictionary))}
        val random=java.util.Random(42)
        val fragments=listOf("work","WORK","_work","共享知识","共享","C++","甲乙","😀","σ","𐐨"," ","x")
        repeat(100){val text=List(60){fragments[random.nextInt(fragments.size)]}.joinToString("");assertEquals(reference(text,dictionary),KnowledgeTextLinks.spans(text,dictionary))}
    }
    @Test fun expandedDictionaryFindsLateTargetsAndRejectsExcessiveTrieMemory(){
        val dictionary=List(KnowledgeTextLinks.MAX_TARGETS){target("知识${it}条","alias${it}")}
        val text="知识511条 alias511"
        assertEquals(reference(text,dictionary),KnowledgeTextLinks.spans(text,dictionary))
        val excessive=target("长".repeat(KnowledgeTextLinks.MAX_DICTIONARY_CHARS+1))
        assertTrue(KnowledgeTextLinks.spans("长",listOf(excessive)).isEmpty())
    }
    /** Independent scan keeps Unicode/boundary/overlap behavior comparable to the original implementation. */
    private fun reference(text:String,targets:List<KnowledgeTextTarget>):List<KnowledgeTextSpan>{
        val terms=mutableListOf<Pair<String,MutableSet<String>>>()
        targets.forEach{t->t.terms.filter{it.isNotBlank()}.forEach{value->
            val old=terms.firstOrNull{it.first.equals(value,ignoreCase=true)}
            if(old==null)terms.add(value to mutableSetOf(t.linkId))else old.second.add(t.linkId)
        }}
        val ordered=terms.sortedWith(compareByDescending<Pair<String,MutableSet<String>>>{it.first.length}.thenBy{it.first})
        fun word(c:Char)=c in 'a'..'z'||c in 'A'..'Z'||c in '0'..'9'||c=='_'
        val result=mutableListOf<KnowledgeTextSpan>();var start=0
        while(start<text.length){
            val term=ordered.firstOrNull{(value,_)->val end=start+value.length
                end<=text.length&&(end==text.length||!(text[end-1].isHighSurrogate()&&text[end].isLowSurrogate()))&&
                    (!word(value.first())||start==0||!word(text[start-1]))&&(!word(value.last())||end==text.length||!word(text[end]))&&
                    text.regionMatches(start,value,0,value.length,ignoreCase=true)
            }
            if(term==null)start+=Character.charCount(text.codePointAt(start))else{val end=start+term.first.length;result.add(KnowledgeTextSpan(start,end,term.second.sorted()));start=end}
        }
        return result
    }
}
