package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class KnowledgeTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun originalPaperOrdinalsRemainReadable(){assertEquals(listOf("BLANK","RULED","GRID","DOTS"),PaperStyle.entries.take(4).map{it.name})}
    @Test fun cornellDefinesRealCueColumnAndSummary(){val lines=PaperTemplates.guides(PaperStyle.CORNELL,CanvasBounds(0.0,0.0,1000.0,1414.0),false,.7).lines
        assertTrue(lines.any{it.x1==260f&&it.x2==260f&&it.y2==1120f});assertTrue(lines.any{it.y1==1120f&&it.y2==1120f})}
    @Test fun wideCornellAndBlankVariantAreDistinct(){val bounds=CanvasBounds(0.0,0.0,1000.0,1414.0)
        assertTrue(PaperTemplates.guides(PaperStyle.CORNELL_WIDE,bounds,false,.7).lines.any{it.x1==340f&&it.x2==340f})
        assertEquals(2,PaperTemplates.guides(PaperStyle.CORNELL_BLANK,bounds,false,.7).lines.size)}
    @Test fun farAwayBoardGuidesAreBounded(){val g=PaperTemplates.guides(PaperStyle.DOTS,CanvasBounds(-1000000.0,-1000000.0,1000000.0,1000000.0),true,.02);assertTrue(g.dots.size<=4096)}
    @Test fun everyKnowledgeKindRoundTrips(){val card=id();val ref=TargetRef(TargetKind.CARD,card)
        val values=listOf(KnowledgeData.PageMark(id(),"章节",false,2),KnowledgeData.PageMark(id(),"重点",true),KnowledgeData.Anchor(id(),3,CanvasBounds(2.0,3.0,30.0,40.0),listOf(id())),KnowledgeData.Link(ref,TargetRef(TargetKind.CARD,id()),RelationKind.CONTRAST,2),
            KnowledgeData.Properties(card,ManualState.REVIEW,listOf("数学")),KnowledgeData.Collection("待整理","数学",ManualState.INBOX,true),KnowledgeData.Question(card,"解释什么是条件概率？"),KnowledgeData.Placement(card,-25.0,80.0),KnowledgeData.Alias(card,"贝叶斯"),
            KnowledgeData.MapDefinition("第二张图"),KnowledgeData.MapOccurrence(id(),card,null,40.0,80.0),KnowledgeData.Decoration(id(),id()))
        values.forEach{assertEquals(it,KnowledgeCodec.decode(KnowledgeCodec.encode(it)))}}
    @Test(expected=IllegalArgumentException::class) fun trailingDataIsRejected(){KnowledgeCodec.decode(KnowledgeCodec.encode(KnowledgeData.Collection("复习"))+byteArrayOf(0))}
    @Test(expected=IllegalArgumentException::class) fun invalidPlacementRejected(){KnowledgeCodec.encode(KnowledgeData.Placement(id(),Double.NaN,0.0))}
    @Test(expected=IllegalArgumentException::class) fun cannotPinNotebookAsCardRevision(){KnowledgeCodec.encode(KnowledgeData.Link(TargetRef(TargetKind.PAGE,id()),TargetRef(TargetKind.NOTE,id()),pinnedRevision=3))}
    @Test fun commandFreezesCallerOwnedLists(){val tags=mutableListOf("数学");val c=KnowledgeCommand(id(),id(),id(),0,KnowledgeData.Properties(id(),tags=tags));val digest=c.digest();tags.clear();c.payload.fill(0);assertEquals(digest,c.digest());assertEquals(listOf("数学"),(c.data as KnowledgeData.Properties).tags)}
    @Test fun commandCreationReportsMapCapacityWithoutRelabelingMalformedData(){
        val structures=List(StudyGraph.MAX_NODES+1){MapStructure(id(),null,"主题",0.0,0.0)}
        fun command(data:KnowledgeData)=KnowledgeCommand(id(),id(),id(),0,data)
        fun reason(data:KnowledgeData):String?{try{command(data);fail("must reject")}catch(e:IllegalArgumentException){return e.message};return null}
        command(KnowledgeData.MapDefinition("图",structures=structures.take(StudyGraph.MAX_NODES)))
        command(KnowledgeData.MapOrder(null,structures.take(StudyGraph.MAX_NODES).map{it.id}))
        assertEquals("STUDY_NODE_BUDGET",reason(KnowledgeData.MapDefinition("图",structures=structures)))
        assertEquals("STUDY_NODE_BUDGET",reason(KnowledgeData.MapOrder(null,structures.map{it.id})))
        assertFalse(reason(KnowledgeData.MapDefinition("图",layout="invalid")).orEmpty().startsWith("STUDY_NODE_"))
        assertFalse(reason(KnowledgeData.MapOrder(null,listOf(structures[0].id,structures[0].id))).orEmpty().startsWith("STUDY_NODE_"))
    }
    @Test fun operationReuseWithOtherPayloadChangesDigest(){val op=id();val book=id();val target=id();assertNotEquals(KnowledgeCommand(op,book,target,0,KnowledgeData.Collection("甲")).digest(),KnowledgeCommand(op,book,target,0,KnowledgeData.Collection("乙")).digest())}
    @Test fun collectionAndOrAreTypedAndDoNotCreateData(){val p=KnowledgeData.Properties(id(),ManualState.INBOX,listOf("数学"));assertFalse(KnowledgeQueries.matches(KnowledgeData.Collection("集合","数学",ManualState.REVIEW),p));assertTrue(KnowledgeQueries.matches(KnowledgeData.Collection("集合","数学",ManualState.REVIEW,true),p));assertEquals(ManualState.INBOX,p.state)}
    @Test fun backlinksDoNotNeedAnInverseAuthorEdge(){val a=TargetRef(TargetKind.CARD,id());val b=TargetRef(TargetKind.CARD,id());val link=KnowledgeData.Link(a,b);val graph=KnowledgeQueries.graph(b,listOf(link));assertEquals(listOf(link),graph.edges);assertEquals(setOf(a,b),graph.nodes)}
    @Test fun graphDepthAndBudgetAreExplicit(){val a=TargetRef(TargetKind.CARD,id());val b=TargetRef(TargetKind.CARD,id());val c=TargetRef(TargetKind.CARD,id());val links=listOf(KnowledgeData.Link(a,b),KnowledgeData.Link(b,c))
        assertFalse(c in KnowledgeQueries.graph(a,links,1).nodes);assertTrue(c in KnowledgeQueries.graph(a,links,2).nodes)
        val dense=(1..200).map{KnowledgeData.Link(a,TargetRef(TargetKind.CARD,id()))};val g=KnowledgeQueries.graph(a,dense);assertTrue(g.truncated);assertEquals(100,g.nodes.size);assertTrue(g.edges.size<=200)}
    @Test fun cycleExpansionAndDeepChainsStop(){val a=TargetRef(TargetKind.CARD,id());assertFalse(KnowledgeQueries.canExpand(a,listOf(a),1));assertFalse(KnowledgeQueries.canExpand(a,List(4){TargetRef(TargetKind.CARD,id())},4));assertFalse(KnowledgeQueries.canExpand(a,emptyList(),100))}
    @Test fun styledRelationsRoundTripAndDefaultsKeepTheLegacyWireTag(){
        val link=KnowledgeData.Link(TargetRef(TargetKind.CARD,id()),TargetRef(TargetKind.CARD,id()),RelationKind.SUMMARY,3)
        java.io.DataInputStream(java.io.ByteArrayInputStream(KnowledgeCodec.encode(link))).use{it.readInt();assertEquals("LINK",it.readUTF())}
        for(style in RelationLineStyle.entries)for(direction in RelationDirection.entries)for(visible in listOf(true,false)){
            val edited=link.copy(lineStyle=style,direction=direction,annotation="由两段原文归纳\n仅改变关系显示",visible=visible)
            assertEquals(edited,KnowledgeCodec.decode(KnowledgeCodec.encode(edited)))
            assertTrue(link.sameMeaning(edited));assertFalse(link.sameMeaning(edited.copy(target=TargetRef(TargetKind.CARD,id()))))
        }
        assertThrows(IllegalArgumentException::class.java){KnowledgeCodec.encode(link.copy(annotation="字".repeat(2001)))}
        val operation=id();val book=id();val record=id()
        assertNotEquals(KnowledgeCommand(operation,book,record,1,link).digest(),KnowledgeCommand(operation,book,record,1,link.copy(visible=false)).digest())
    }

}
