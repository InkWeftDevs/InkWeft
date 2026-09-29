package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyTextTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun outlineKeepsHierarchyAndSharedOccurrencesWithOneBody(){
        val card=StudyTextCard(id(),"条件 [A]","独有正文")
        val root=StudyNode(id(),card.id,null,0.0,0.0)
        val child=StudyNode(id(),card.id,root.id,260.0,128.0)
        val text=StudyText.markdown("复习",listOf(card),listOf(child,root))
        assertTrue(text.contains("\n  - [条件 \\[A\\]]"))
        assertEquals(2,Regex("\\(#card-").findAll(text).count())
        assertEquals(1,Regex("独有正文").findAll(text).count())
    }
    @Test fun removedNodeIsExcludedAndUnplacedCardIsRetained(){
        val card=StudyTextCard(id(),"保留卡片","正文")
        val text=StudyText.markdown("笔记",listOf(card),listOf(StudyNode(id(),card.id,null,0.0,0.0,removed=true)))
        assertFalse(text.contains("## 大纲"));assertTrue(text.contains("正文"))
    }
    @Test fun searchMatchesBodyTitleAndCaseWithoutChangingCard(){
        val card=StudyTextCard(id(),"Bayes 公式","先验概率")
        assertTrue(StudyText.matches(card," bayes "));assertTrue(StudyText.matches(card,"先验"))
        assertFalse(StudyText.matches(card,"积分"));assertTrue(StudyText.matches(card," "))
    }
    @Test(expected=IllegalArgumentException::class) fun brokenCardReferenceCannotBeSilentlyExported(){
        StudyText.markdown("笔记",emptyList(),listOf(StudyNode(id(),id(),null,0.0,0.0)))
    }
}
