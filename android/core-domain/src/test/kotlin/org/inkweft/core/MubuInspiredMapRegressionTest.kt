// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Original fixtures for InkWeft contracts; no reference application code. */
class MubuInspiredMapRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun node(parent:StudyNode?=null)=StudyNode(id(),id(),parent?.id,0.0,0.0)
    private fun graph(nodes:List<StudyNode>)=StudyGraphState(MapRef(id(),null),nodes,StudyOrganization.canonicalOrder(nodes,nodes.map{it.id}))
    private fun heights(nodes:List<StudyNode>,values:List<Double>)=nodes.mapIndexed{i,n->n.id to StudyNodeSize(200.0,values[i])}.toMap()
    private fun assertNoOverlap(plan:StudyOrganizationPlan,sizes:Map<String,StudyNodeSize>){
        plan.after.placements.forEachIndexed{i,a->plan.after.placements.drop(i+1).forEach{b->
            val sa=sizes.getValue(a.nodeId);val sb=sizes.getValue(b.nodeId)
            assertTrue(a.x+sa.width<=b.x||b.x+sb.width<=a.x||a.y+sa.height<=b.y||b.y+sb.height<=a.y)
        }}
    }
    @Test fun maximumDeepOutlineKeepsDepthCountsCollapseAndFocus(){
        val nodes=buildList<StudyNode>{repeat(StudyGraph.MAX_NODES){add(node(lastOrNull()))}}
        val full=StudyOutline.project(nodes);assertEquals(nodes.map{it.id},full.rows.map{it.node.id})
        full.rows.forEachIndexed{i,row->assertEquals(i,row.depth);assertEquals(nodes.lastIndex-i,row.descendants)}
        val collapsed=StudyOutline.project(nodes,setOf(nodes.first().id));assertEquals(1,collapsed.rows.size);assertEquals(1023,collapsed.rows.single().descendants)
        val focused=StudyOutline.project(nodes,setOf(nodes.first().id),nodes[512].id)
        assertEquals(nodes.drop(512).map{it.id},focused.rows.map{it.node.id});assertEquals(nodes.take(513).map{it.id},focused.path.map{it.id});assertEquals(0,focused.rows.first().depth)
        // Traversal does not require incoming storage order to be parent-first.
        val reversed=StudyOutline.project(nodes.reversed());assertEquals(full,reversed)
    }
    @Test fun outlineCountsRemainIndependentOfVisibleRowsAndSharedCards(){
        val root=node();val a=node(root);val b=node(root);val aa=node(a).copy(cardId=b.cardId);val bb=node(b).copy(cardId=root.cardId)
        val nodes=listOf(root,a,aa,b,bb);val before=nodes.toList()
        val folded=StudyOutline.project(nodes,setOf(a.id,b.id))
        assertEquals(listOf(4,1,1),folded.rows.map{it.descendants});assertEquals(listOf(root.id,a.id,b.id),folded.rows.map{it.node.id})
        assertEquals(before,nodes);assertEquals(1,StudyOutline.project(nodes,focusId=b.id).rows.first().descendants)
    }
    @Test fun malformedCyclesStayBoundedWithoutAuthorWrites(){
        val a=node();val b=node(a);val nodes=listOf(a.copy(parentId=b.id),b)
        val result=StudyOutline.project(nodes,focusId=a.id)
        assertEquals(setOf(a.id,b.id),result.rows.map{it.node.id}.toSet());assertEquals(2,result.path.size);assertTrue(result.rows.all{it.descendants in 0..1})
    }
    @Test fun unequalSubtreesUseContiguousHeightBalancedSides(){
        val root=node();val kids=List(4){node(root)};val nodes=listOf(root)+kids;val state=graph(nodes)
        val sizes=heights(nodes,listOf(80.0,80.0,900.0,80.0,900.0));val plan=StudyOrganization.arrange(state,sizes,"bilateral")
        val byId=plan.after.placements.associateBy{it.nodeId};val rootX=byId.getValue(root.id).x
        assertEquals(listOf(true,true,false,false),kids.map{byId.getValue(it.id).x<rootX})
        val extent=plan.after.placements.maxOf{it.y+sizes.getValue(it.nodeId).height}-plan.after.placements.minOf{it.y}
        assertEquals(1028.0,extent,0.00001);assertNoOverlap(plan,sizes)
        val after=StudyOrganization.apply(state,plan)
        assertEquals(state.orderedNodeIds,after.orderedNodeIds);assertEquals(state.nodes.associate{it.id to it.parentId},after.nodes.associate{it.id to it.parentId})
        assertEquals(state.nodes.map{it.copy(revision=1)},StudyOrganization.apply(after,StudyOrganization.undo(after,plan)).nodes.map{it.copy(revision=1)})
    }
    @Test fun splitMeasuresWholeBranchesRatherThanOnlyTheirRootCards(){
        val root=node();val kids=List(4){node(root)};val leafA=node(kids[1]);val leafB=node(kids[3])
        val nodes=listOf(root,kids[0],kids[1],leafA,kids[2],kids[3],leafB);val state=graph(nodes)
        val sizes=heights(nodes,listOf(80.0,80.0,80.0,900.0,80.0,80.0,900.0));val plan=StudyOrganization.arrange(state,sizes,"bilateral")
        val positions=plan.after.placements.associateBy{it.nodeId};val x=positions.getValue(root.id).x
        assertEquals(listOf(true,true,false,false),kids.map{positions.getValue(it.id).x<x})
        assertTrue(positions.getValue(leafA.id).x<positions.getValue(kids[1].id).x)
        assertTrue(positions.getValue(leafB.id).x>positions.getValue(kids[3].id).x);assertNoOverlap(plan,sizes)
    }
    @Test fun evenSizeTieRetainsContiguousOrderAndSingleBranchSide(){
        for(count in 0..5){
            val root=node();val kids=List(count){node(root)};val nodes=listOf(root)+kids;val sizes=heights(nodes,List(nodes.size){100.0});val state=graph(nodes)
            val one=StudyOrganization.arrange(state,sizes,"bilateral");val two=StudyOrganization.arrange(state,sizes,"bilateral");assertEquals(one,two)
            val positions=one.after.placements.associateBy{it.nodeId};val x=positions.getValue(root.id).x
            val left=kids.map{positions.getValue(it.id).x<x};assertEquals(List((count+1)/2){true}+List(count/2){false},left);assertNoOverlap(one,sizes)
        }
    }
    @Test fun localLayoutKeepsOtherBranchesAndCanUndo(){
        val root=node();val selected=node(root);val kids=List(4){node(selected)};val other=node(root)
        val nodes=(listOf(root,selected)+kids+other).mapIndexed{i,n->n.copy(x=100.0+i*400,y=200.0+i*50)};val state=graph(nodes)
        val sizes=heights(nodes,listOf(80.0,80.0,80.0,900.0,80.0,900.0,80.0));val plan=StudyOrganization.arrangeSelection(state,setOf(selected.id),sizes,"bilateral")
        val after=StudyOrganization.apply(state,plan);assertEquals(nodes.first(),after.nodes.first());assertEquals(nodes.last(),after.nodes.last())
        assertEquals(nodes.map{it.copy(revision=1)},StudyOrganization.apply(after,StudyOrganization.undo(after,plan)).nodes.map{it.copy(revision=1)})
    }
    @Test fun multiRootAndFractionalNodeSizesStayDisjointAndBounded(){
        val roots=List(3){node()};val nodes=roots.flatMap{root->listOf(root)+List(7){node(root)}};val state=graph(nodes)
        val sizes=heights(nodes,List(nodes.size){i->if(i%2==0)800.25 else 81.75});val plan=StudyOrganization.arrange(state,sizes,"bilateral")
        assertNoOverlap(plan,sizes);plan.after.placements.forEach{assertTrue(it.x>=-40_000&&it.y>=-40_000&&it.x+200<=40_000&&it.y+sizes.getValue(it.nodeId).height<=40_000)}
    }
}
