// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.pow

class CardPresentationTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun annotationAndIndependentPresetsRoundTripWithoutInferringLegacyBody(){
        val value=KnowledgeData.CardPresentation(id(),"汉\u0000字😀".repeat(1000),CardTint.GREEN,CardTint.ROSE)
        assertEquals(value,KnowledgeCodec.decode(KnowledgeCodec.encode(value)))
        assertEquals("",KnowledgeData.CardPresentation(value.cardId).annotation)
        val longest=value.copy(annotation="汉".repeat(CardPresentationRules.MAX_ANNOTATION))
        assertTrue(KnowledgeCodec.encode(longest).size<=KnowledgeCodec.MAX_BYTES)
        assertEquals(longest,KnowledgeCodec.decode(KnowledgeCodec.encode(longest)))
    }
    @Test(expected=IllegalArgumentException::class) fun overLimitAnnotationFailsBeforeAnyCommand(){
        KnowledgeCommand(id(),id(),id(),0,KnowledgeData.CardPresentation(id(),"汉".repeat(CardPresentationRules.MAX_ANNOTATION+1)))
    }
    @Test fun annotationSearchReturnsOriginalCardAndEachActualOccurrence(){
        val book=id();val card=id();val first=id();val second=id();val map=MapRef(book)
        val nodes=listOf(first,second).map{MapSceneNode(it,null,card,"固定标题","既有摘录和旧备注",0.0,0.0,1,1)}
        val scene=MapScene(map,"主图",nodes,"graph")
        val hits=MapSearch.find(listOf(scene),"独立理解",map,false,mapOf(card to "我的独立理解"))
        assertEquals(setOf(first,second),hits.map{it.nodeId}.toSet());assertTrue(hits.all{it.cardId==card&&it.matchedField=="个人注释"})
        assertTrue(MapSearch.find(listOf(scene),"独立理解",map,false).isEmpty())
        assertTrue(StudyText.matches(StudyTextCard(card,"固定标题","既有摘录和旧备注","我的独立理解"),"独立理解"))
        val markdown=StudyText.markdown("笔记",listOf(StudyTextCard(card,"固定标题","旧正文","新注释")),emptyList())
        assertTrue(markdown.contains("旧正文"));assertTrue(markdown.contains("个人注释\n\n新注释"))
    }
    @Test fun allPresetsRemainOpaqueAndHighContrastWithCardText(){
        fun light(color:Int):Double {
            fun part(shift:Int):Double{val c=((color ushr shift)and 255)/255.0;return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
            return .2126*part(16)+.7152*part(8)+.0722*part(0)
        }
        CardTint.entries.mapNotNull{it.argb}.forEach{color->
            assertEquals(255,color ushr 24);assertTrue((light(color)+.05)/(light(0xff20242d.toInt())+.05)>7.0)
        }
    }
}
