// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardTransformTest {
    private fun id()=UUID.randomUUID().toString()
    private fun card(body:String="原文\n含 空格 ",annotation:String="我的注释"):CardTransformCard {
        val id=id();return CardTransformCard(id,3,"标题",body,KnowledgeData.CardPresentation(id,annotation,CardTint.BLUE,CardTint.ROSE),listOf(StudySourceVersionRef(id(),2)))
    }
    private fun plan(kind:CardTransformKind,cards:List<CardTransformCard>,targets:List<CardTransformTarget>)=CardTransformPlan(id(),id(),kind,"a".repeat(64),cards,targets)
    @Test fun mergeKeepsExactTextAllAnnotationsOrderedSourceVersionsAndFirstColors(){
        val a=card();val b=card("第二张\n原样", "另一注释")
        val target=CardTransforms.mergeTarget(listOf(a,b),"合并")
        val p=plan(CardTransformKind.MERGE,listOf(a,b),listOf(target));val copy=CardTransformCodec.decode(CardTransformCodec.encode(p))
        assertEquals(a.body+"\n\n"+b.body,target.body);assertEquals("我的注释\n\n另一注释",target.annotation)
        assertEquals(a.sources+b.sources,target.sources);assertEquals(CardTint.BLUE,target.cardColor);assertEquals(CardTint.ROSE,target.titleBarColor)
        assertNotEquals(a.id,target.id);assertEquals(p.digest(),copy.digest());assertEquals(p.inputs,copy.inputs);assertEquals(p.targets,copy.targets)
    }
    @Test fun splitPreservesWhitespaceAndExplicitAnnotationSourceDestinations(){
        val a=card();val targets=CardTransforms.splitTargets(a,3).mapIndexed{i,t->if(i==0)t.copy(annotation="",sources=emptyList())else t}
        val p=plan(CardTransformKind.SPLIT,listOf(a),targets)
        assertEquals(a.body,p.targets.joinToString(""){it.body});assertEquals(a.presentation.annotation,p.targets[1].annotation)
        assertThrows(IllegalArgumentException::class.java){plan(CardTransformKind.SPLIT,listOf(a),targets.map{it.copy(sources=emptyList())})}
        assertThrows(IllegalArgumentException::class.java){plan(CardTransformKind.SPLIT,listOf(a),targets.map{it.copy(annotation="")})}
        assertThrows(IllegalArgumentException::class.java){plan(CardTransformKind.SPLIT,listOf(a),targets.map{it.copy(body=it.body.trim())})}
    }
    @Test fun summaryUsesExistingSemanticLinkAndRequiresUserWrittenBody(){
        val cards=listOf(card(),card());val target=CardTransforms.mergeTarget(cards,"总结").copy(body="我的归纳")
        plan(CardTransformKind.SUMMARY,cards,listOf(target))
        val link=KnowledgeData.Link(TargetRef(TargetKind.CARD,cards.first().id),TargetRef(TargetKind.CARD,target.id),RelationKind.SUMMARY)
        assertEquals(link,KnowledgeCodec.decode(KnowledgeCodec.encode(link)))
        assertThrows(IllegalArgumentException::class.java){plan(CardTransformKind.SUMMARY,cards,listOf(target.copy(body=" ")))}
    }
    @Test fun oversizedMergeAndInvalidSplitRejectWithoutTruncation(){
        val cards=listOf(card("a".repeat(10_000)),card("b".repeat(10_000)))
        assertThrows(IllegalArgumentException::class.java){plan(CardTransformKind.MERGE,cards,listOf(CardTransforms.mergeTarget(cards,"太长")))}
        val emoji=card("甲😀乙")
        assertThrows(IllegalArgumentException::class.java){CardTransforms.splitTargets(emoji,2)}
        assertEquals(emoji.body,CardTransforms.splitTargets(emoji,3).joinToString(""){it.body})
        assertThrows(IllegalArgumentException::class.java){CardTransformCodec.decode(byteArrayOf(1,2,3,4))}
    }
    @Test fun referencesRejectDuplicatesAndKeepIndependentStableIdentity(){
        val ref=StudySourceVersionRef(id(),4)
        assertEquals(listOf(ref),StudySourceRefs.decode(StudySourceRefs.encode(listOf(ref))))
        assertThrows(IllegalArgumentException::class.java){StudySourceRefs.encode(listOf(ref,ref))}
        assertThrows(IllegalArgumentException::class.java){StudySourceRefs.decode("${ref.sourceId}@0")}
        assertThrows(IllegalArgumentException::class.java){StudySourceRefs.decode("${ref.sourceId}@04")}
    }
    @Test fun malformedUtf8PlanCannotSilentlyReplaceOriginalText(){
        val cards=listOf(card("unique-content-a"),card("unique-content-b"))
        val bytes=CardTransformCodec.encode(plan(CardTransformKind.MERGE,cards,listOf(CardTransforms.mergeTarget(cards,"合并"))))
        val marker="unique-content-a".toByteArray();val index=(0..bytes.size-marker.size).first{offset->marker.indices.all{bytes[offset+it]==marker[it]}}
        bytes[index]=0xff.toByte()
        assertThrows(java.nio.charset.CharacterCodingException::class.java){CardTransformCodec.decode(bytes)}
    }

    @Test fun summaryPlacementRoundTripsWithoutChangingLegacyPlanBytes(){
        val cards=listOf(card(),card());val target=CardTransforms.mergeTarget(cards,"归纳").copy(body="我的总结")
        val legacy=plan(CardTransformKind.SUMMARY,cards,listOf(target));val oldBytes=CardTransformCodec.encode(legacy)
        assertArrayEquals(oldBytes,CardTransformCodec.encode(CardTransformCodec.decode(oldBytes)))
        val placement=CardTransformPlacement(id(),id(),id(),40.0,300.0,"b".repeat(64))
        val current=CardTransformPlan(legacy.operationId,legacy.notebookId,legacy.kind,legacy.expectedFingerprint,cards,listOf(target),placement)
        assertEquals(placement,CardTransformCodec.decode(CardTransformCodec.encode(current)).summaryPlacement)
        assertNotEquals(legacy.digest(),current.digest())
    }

}
