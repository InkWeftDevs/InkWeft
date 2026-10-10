// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

class MapCompletionRegressionTest {
    private fun id(n:Int)=UUID(0,n.toLong()).toString()
    private fun state(children:Int=4):StudyGraphState{
        val nodes=listOf(StudyNode(id(1),id(101),null,40.0,80.0))+List(children){i->StudyNode(id(i+2),id(i+102),id(1),300.0,80.0+i*120)}
        return StudyGraphState(MapRef(id(900)),nodes,nodes.map{it.id},orderRevision=1)
    }
    private fun group(state:StudyGraphState,members:Set<String>,label:String="归纳")=StudySummaryGroup(id(800),1,MapSummaries.selection(state,members,label))
    private fun sizes(state:StudyGraphState)=state.orderedNodeIds.associateWith{StudyNodeSize(232.0,64.0)}
    private fun rejects(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}}
    private fun logical(state:StudyGraphState)=state.nodes.map{it.copy(revision=1)}
    private fun noOverlap(plan:StudyOrganizationPlan,sizes:Map<String,StudyNodeSize>){
        plan.after.placements.forEachIndexed{i,a->plan.after.placements.drop(i+1).forEach{b->
            val sa=sizes.getValue(a.nodeId);val sb=sizes.getValue(b.nodeId)
            assertFalse("${a.nodeId}/${b.nodeId}",a.x<b.x+sb.width&&a.x+sa.width>b.x&&a.y<b.y+sb.height&&a.y+sa.height>b.y)
        }}
    }
    @Test fun leftLayoutKeepsWholeBranchesOnLeftAndUndoRestoresLayout(){
        val base=state();val p=StudyOrganization.arrange(base,sizes(base),"left");val applied=StudyOrganization.apply(base,p)
        val root=applied.nodes.first();assertTrue(applied.nodes.drop(1).all{it.x+232<root.x})
        assertEquals("left",applied.layout);assertEquals(base.orderRevision+1,applied.orderRevision)
        val undo=StudyOrganization.undo(applied,p);val restored=StudyOrganization.apply(applied,undo)
        assertEquals("right",restored.layout);assertEquals(logical(base),logical(restored));assertEquals(base.orderedNodeIds,restored.orderedNodeIds)
        val redo=StudyOrganization.undo(restored,undo);assertEquals("left",StudyOrganization.apply(restored,redo).layout)
    }
    @Test fun organizationUsesActualWidthAndHeightAndFitsDeepWideTrees(){
        val base=state(15);val sizes=base.orderedNodeIds.mapIndexed{i,id->id to StudyNodeSize(180.0+i*17,70.0+i*25)}.toMap()
        val p=StudyOrganization.arrange(base,sizes,"organization");noOverlap(p,sizes)
        val root=p.after.placements.first();assertTrue(p.after.placements.drop(1).all{it.y>=root.y+sizes.getValue(root.nodeId).height+96})
        val deep=List(StudyGraph.MAX_NODES){i->StudyNode(id(i+1),id(i+1),if(i==0)null else id(i),0.0,0.0)}
        val s=StudyGraphState(base.ref,deep,deep.map{it.id})
        val measured=deep.associate{it.id to StudyNodeSize(180.0,110.0)}
        val large=StudyOrganization.arrange(s,measured,"organization");noOverlap(large,measured)
        assertTrue(large.after.placements.all{it.x in -40000.0..40000.0&&it.y in -40000.0..40000.0})
        assertEquals("organization",StudyOrganization.apply(s,large).layout)
    }
    @Test fun layoutOnlyChangeIsTransactionalAndVersionedEvenForOneNode(){
        val raw=state(0);val base=raw.copy(nodes=raw.nodes.map{it.copy(x=80.0,y=40.0)});val p=StudyOrganization.arrange(base,sizes(base),"organization")
        assertEquals(p.before,p.after);assertNotEquals(p.expectedGraph,p.expectedAfterGraph)
        val applied=StudyOrganization.apply(base,p);assertEquals(base.orderRevision+1,applied.orderRevision)
        rejects{StudyOrganization.apply(base.copy(layout="left"),p)}
    }
    @Test fun arrangementWireFormatPreservesOldPlansAndRoundTripsLayoutUndo(){
        val base=state();val old=StudyOrganization.move(base,id(2),450.0,90.0)
        val bytes=StudyOrganization.encode(old);assertEquals(0x31,bytes[3].toInt());assertArrayEquals(bytes,StudyOrganization.encode(StudyOrganization.decode(bytes)))
        val modern=StudyOrganization.arrange(base,sizes(base),"organization")
        val encoded=StudyOrganization.encode(modern);assertEquals(0x32,encoded[3].toInt());assertEquals(modern,StudyOrganization.decode(encoded))
        rejects{StudyOrganization.decode(encoded+byteArrayOf(0))}
    }
    @Test fun legacyOrderBytesAndNewLayoutBytesAreUnambiguous(){
        val old=KnowledgeData.MapOrder(null,listOf(id(1)))
        val legacy=ByteArrayOutputStream().also{DataOutputStream(it).use{d->d.writeInt(0x49574b31);d.writeUTF("MAP_ORDER_V1");d.writeUTF("");d.writeInt(1);d.writeUTF(id(1))}}.toByteArray()
        assertArrayEquals(legacy,KnowledgeCodec.encode(old));assertEquals(old,KnowledgeCodec.decode(legacy))
        MapLayouts.supported.forEach{layout->val next=old.copy(layout=layout);assertEquals(next,KnowledgeCodec.decode(KnowledgeCodec.encode(next)))}
        rejects{KnowledgeCodec.encode(old.copy(layout="unknown"))}
    }
    @Test fun templatesHaveNestedLearningContentAndNoOverlapOrPrivateIdentity(){
        assertEquals(12,MapTemplates.builtins.size)
        MapTemplates.builtins.forEach{t->KnowledgeCodec.validate(t)
            val d=MapTemplates.instantiate(t,"新图");val nodes=d.structures.map{StudyNode(it.id,it.id,it.parentId,it.x,it.y)}
            val state=StudyGraphState(MapRef(id(900)),nodes,StudyOrganization.canonicalOrder(nodes,nodes.map{it.id}))
            val plan=StudyOrganization.arrange(state,sizes(state),t.layout);noOverlap(plan,sizes(state))
            assertEquals(d,KnowledgeCodec.decode(KnowledgeCodec.encode(d)))
        }
        MapTemplates.builtins.filter{MapTemplates.category(it)!="基础结构"}.forEach{t->assertTrue(t.nodes.any{n->n.parent?.let{t.nodes[it].parent!=null}==true})}
        val project=MapTemplates.builtins.single{it.title=="项目拆解"};assertEquals("organization",project.layout)
        assertTrue(project.nodes.any{it.title=="验收条件"});assertTrue(MapTemplates.builtins.single{it.title=="公式推导"}.nodes.any{it.title=="定义域与单位"})
    }
    @Test fun summarySelectionUsesOccurrenceIdentitiesAndRequiresContiguousNonRootPeers(){
        val base=state();val g=MapSummaries.selection(base,setOf(id(3),id(2)),"结论")
        assertEquals(listOf(id(2),id(3)),g.memberIds);assertEquals(g,KnowledgeCodec.decode(KnowledgeCodec.encode(g)))
        rejects{MapSummaries.selection(base,setOf(id(2),id(4)),"隔开")}
        rejects{MapSummaries.selection(base,setOf(id(1),id(2)),"根主题")}
        rejects{MapSummaries.selection(base,setOf(id(2)),"单项")}
        val shared=base.copy(nodes=base.nodes.map{it.copy(cardId=id(101))})
        assertEquals(g.memberIds,MapSummaries.selection(shared,setOf(id(2),id(3)),"重复卡").memberIds)
    }
    @Test fun summaryRejectsOverlapMissingMembersAndUnsafeStructuralChanges(){
        val base=state();val g=group(base,setOf(id(2),id(3)));val s=base.copy(summaryGroups=listOf(g))
        rejects{MapSummaries.selection(s,setOf(id(3),id(4)),"重叠")}
        rejects{StudyOrganization.plan(s,id(3),StudyOrganizationAction.INDENT)}
        rejects{StudyOrganization.plan(s,id(3),StudyOrganizationAction.DOWN)}
        rejects{StudyOrganization.fingerprint(s.copy(nodes=s.nodes.map{if(it.id==id(2))it.copy(removed=true)else it},orderedNodeIds=s.orderedNodeIds-id(2)))}
        val moved=StudyOrganization.reparentSelection(s,setOf(id(2),id(3)),id(5))
        assertEquals(id(5),StudyOrganization.apply(s,moved).nodes.first{it.id==id(2)}.parentId)
    }
    @Test fun summaryRevisionAndMembershipInvalidateFrozenLayoutAndUndo(){
        val base=state();val g=group(base,setOf(id(2),id(3)));val s=base.copy(summaryGroups=listOf(g));val p=StudyOrganization.arrange(s,sizes(s),"bilateral")
        assertNotEquals(StudyOrganization.fingerprint(base),StudyOrganization.fingerprint(s))
        rejects{StudyOrganization.apply(s.copy(summaryGroups=listOf(g.copy(revision=2,data=g.data.copy(label="新结论")))),p)}
        val applied=StudyOrganization.apply(s,p)
        rejects{StudyOrganization.undo(applied.copy(summaryGroups=emptyList()),p)}
    }
    @Test fun bilateralSplitNeverCutsAWholeSummaryAndOneFullGroupStaysTogether(){
        val base=state();val all=base.copy(summaryGroups=listOf(group(base,setOf(id(2),id(3),id(4),id(5)))))
        val p=StudyOrganization.arrange(all,sizes(all),"bilateral");val root=p.after.placements.first()
        assertTrue(p.after.placements.drop(1).all{it.x<root.x})
        val two=base.copy(summaryGroups=listOf(group(base,setOf(id(3),id(4)))))
        val arrangement=StudyOrganization.arrange(two,sizes(two),"bilateral").after.placements.associateBy{it.nodeId}
        assertEquals(arrangement.getValue(id(3)).x<arrangement.getValue(id(1)).x,arrangement.getValue(id(4)).x<arrangement.getValue(id(1)).x)
        assertEquals(base.orderedNodeIds,StudyOrganization.apply(two,StudyOrganization.arrange(two,sizes(two),"bilateral")).orderedNodeIds)
    }
    @Test fun fixedMapSceneRoundTripsLayoutGroupsAndFiltersPartialBranch(){
        val base=state();val g=group(base,setOf(id(2),id(3))).data
        val nodes=base.nodes.map{MapSceneNode(it.id,it.parentId,it.cardId,"主题","正文",it.x,it.y,1,1)}
        val scene=MapScene(base.ref,"图",nodes,"a".repeat(64),layout="organization",summaryGroups=listOf(g))
        val embed=MapEmbed(base.ref,policy=MapEmbedPolicy.PINNED,snapshot=scene)
        assertEquals(embed,MapEmbedCodec.decode(MapEmbedCodec.encode(embed)))
        assertTrue(scene.branch(id(2)).summaryGroups.isEmpty());assertEquals(scene.summaryGroups,scene.branch(id(1)).summaryGroups)
        assertNotEquals(scene.signature(),scene.copy(layout="left").signature())
        rejects{MapEmbedCodec.decode(MapEmbedCodec.encode(embed)+byteArrayOf(0))}
    }
    @Test fun exportSizingBoundsExtremeWorldAndIncludesNegativeOrigins(){
        listOf(CanvasBounds(-40000.0,-40000.0,40000.0,40000.0),CanvasBounds(-200.0,10.0,500.0,100.0),CanvasBounds(-40000.0,-1.0,40000.0,1.0)).forEach{b->
            val g=MapExportSizing.fit(b);assertTrue(g.width<=4096&&g.height<=4096&&g.width.toLong()*g.height<=MapExportSizing.MAX_PIXELS)
            assertEquals(32.0,b.left*g.scale+g.translateX,1e-6);assertEquals(32.0,b.top*g.scale+g.translateY,1e-6)
            assertTrue(b.right*g.scale+g.translateX<=g.width-32+1e-6);assertTrue(b.bottom*g.scale+g.translateY<=g.height-32+1e-6)
        }
        rejects{MapExportSizing.fit(CanvasBounds(0.0,0.0,10.0,10.0),padding=64,maxEdge=128)}
    }
    @Test fun markdownKeepsSummaryMembersAndSharedBodyOnceAndHandlesDeepOutline(){
        val base=state();val group=MapSummaries.selection(base,setOf(id(2),id(3)),"归纳[条件]")
        val cards=base.nodes.map{StudyTextCard(it.cardId,"主题","正文-${it.cardId}")}
        val text=StudyText.markdown("图",cards,base.nodes,listOf(group))
        assertTrue(text.contains("## 括号归纳"));assertTrue(text.contains("归纳\\[条件\\]"))
        cards.forEach{assertEquals(1,Regex(Regex.escape(it.body)).findAll(text).count())}
        val chain=List(1024){i->StudyNode(id(i+1),id(101),if(i==0)null else id(i),0.0,0.0)}
        val deep=StudyText.markdown("深链",listOf(StudyTextCard(id(101),"共享主题","只出现一次")),chain)
        assertEquals(1024,deep.lineSequence().count{it.trimStart().startsWith("- [")})
        assertEquals(1,Regex("只出现一次").findAll(deep).count())
    }

    @Test fun extremeGroupedLayoutRejectsWrappingAndPreservesAuthorState(){
        val base=state();val grouped=base.copy(summaryGroups=listOf(group(base,setOf(id(2),id(3),id(4),id(5)))))
        val huge=base.orderedNodeIds.associateWith{StudyNodeSize(232.0,80000.0)}
        val stamp=StudyOrganization.fingerprint(grouped)
        rejects{StudyOrganization.arrange(grouped,huge,"right")}
        assertEquals(stamp,StudyOrganization.fingerprint(grouped))
        assertNotNull(StudyOrganization.arrange(base,huge,"right"))
    }

}
