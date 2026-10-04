// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyOrganizationTest {
    private fun id()=UUID.randomUUID().toString()
    private fun node(parent:StudyNode?=null,x:Double=40.0,y:Double=80.0)=StudyNode(id(),id(),parent?.id,x,y)
    private fun state(nodes:List<StudyNode>,order:List<String> = StudyOrganization.legacyOrder(nodes))=
        StudyGraphState(MapRef(id()),nodes,order,cardVersions=nodes.distinctBy{it.cardId}.map{StudyCardVersion(it.cardId,1)})
    private fun reject(block:()->Unit){try{block();fail("invalid graph accepted")}catch(_:IllegalArgumentException){}}
    private fun apply(s:StudyGraphState,n:StudyNode,a:StudyOrganizationAction)=StudyOrganization.apply(s,StudyOrganization.plan(s,n.id,a))

    @Test fun firstMoveFreezesLegacyOrderBeforeGeometryAndReopenKeepsIt(){
        val a=node(y=300.0);val b=node(y=100.0);val child=node(a,y=0.0)
        val initial=state(listOf(child,a,b));assertEquals(listOf(b.id,a.id,child.id),initial.orderedNodeIds)
        val plan=StudyOrganization.move(initial,b.id,20.0,900.0)
        val moved=StudyOrganization.apply(initial,plan)
        assertEquals(initial.orderedNodeIds,moved.orderedNodeIds);assertEquals(1L,moved.orderRevision)
        val record=KnowledgeCodec.decode(KnowledgeCodec.encode(KnowledgeData.MapOrder(null,moved.orderedNodeIds))) as KnowledgeData.MapOrder
        val reopened=moved.copy(nodes=moved.nodes.reversed(),orderedNodeIds=record.orderedNodeIds)
        assertEquals(StudyOrganization.fingerprint(moved),StudyOrganization.fingerprint(reopened))
        assertEquals(initial.orderedNodeIds,StudyOutline.project(reopened.orderedNodeIds.map{key->reopened.nodes.first{it.id==key}}).rows.map{it.node.id})
        val next=StudyOrganization.apply(reopened,StudyOrganization.move(reopened,b.id,50.0,-300.0))
        assertEquals(1L,next.orderRevision);assertEquals(initial.orderedNodeIds,next.orderedNodeIds)
        assertEquals(3L,next.nodes.first{it.id==b.id}.revision)
    }

    @Test fun movingSiblingsMovesWholeSubtreesAndIndentOutdentKeepAuthorGeometry(){
        val root=node();val a=node(root);val ac=node(a);val b=node(root);val bc=node(b);val c=node(root)
        val initial=state(listOf(root,a,ac,b,bc,c),listOf(root.id,a.id,ac.id,b.id,bc.id,c.id)).copy(orderRevision=2)
        val up=apply(initial,b,StudyOrganizationAction.UP)
        assertEquals(listOf(root.id,b.id,bc.id,a.id,ac.id,c.id),up.orderedNodeIds)
        assertEquals(initial.nodes.associateBy{it.id},up.nodes.associateBy{it.id})
        val down=apply(up,b,StudyOrganizationAction.DOWN)
        assertEquals(initial.orderedNodeIds,down.orderedNodeIds)
        val indented=apply(down,b,StudyOrganizationAction.INDENT)
        assertEquals(a.id,indented.nodes.first{it.id==b.id}.parentId)
        assertEquals(listOf(root.id,a.id,ac.id,b.id,bc.id,c.id),indented.orderedNodeIds)
        assertEquals(b.id,indented.nodes.first{it.id==bc.id}.parentId)
        val outdented=apply(indented,b,StudyOrganizationAction.OUTDENT)
        assertEquals(initial.orderedNodeIds,outdented.orderedNodeIds)
        assertEquals(root.id,outdented.nodes.first{it.id==b.id}.parentId)
        assertEquals(initial.nodes.map{it.id to (it.x to it.y)}.toMap(),outdented.nodes.map{it.id to (it.x to it.y)}.toMap())
        reject{StudyOrganization.plan(initial,a.id,StudyOrganizationAction.UP)}
        reject{StudyOrganization.plan(initial,c.id,StudyOrganizationAction.DOWN)}
    }

    @Test fun mixedStructureOccurrenceReparentUpdatesOneDefinitionAndUndoCannotResetVersions(){
        val structure=node().let{it.copy(cardId=it.id,revision=5)}
        val occurrence=node(structure)
        val other=node().let{it.copy(cardId=it.id,revision=5)}
        val initial=state(listOf(structure,occurrence,other),listOf(structure.id,occurrence.id,other.id)).copy(
            ref=MapRef(id(),id()),orderRevision=3,definitionRevision=5,structuralNodeIds=setOf(structure.id,other.id))
        val definition=KnowledgeData.MapDefinition("混合层级",structures=listOf(
            MapStructure(structure.id,null,"中心",0.0,0.0),MapStructure(other.id,occurrence.id,"结构子项",20.0,40.0)))
        assertEquals(definition,KnowledgeCodec.decode(KnowledgeCodec.encode(definition)))
        val plan=StudyOrganization.reparent(initial,other.id,occurrence.id)
        val applied=StudyOrganization.apply(initial,plan)
        assertEquals(6L,applied.definitionRevision)
        assertTrue(applied.nodes.filter{it.id in initial.structuralNodeIds}.all{it.revision==6L})
        assertEquals(1L,applied.nodes.first{it.id==occurrence.id}.revision)
        assertEquals(3L,applied.orderRevision)
        val undo=StudyOrganization.undo(applied,plan);val restored=StudyOrganization.apply(applied,undo)
        assertEquals(initial.nodes.map{it.id to it.parentId}.toMap(),restored.nodes.map{it.id to it.parentId}.toMap())
        assertEquals(7L,restored.definitionRevision);assertNotEquals(plan.expectedGraph,StudyOrganization.fingerprint(restored))
        reject{StudyOrganization.undo(restored,plan)}
        reject{StudyOrganization.reparent(initial,structure.id,occurrence.id)}
        reject{StudyOrganization.reparent(initial,other.id,id())}
        reject{StudyOrganization.reparent(initial,other.id,null,id())}
    }

    @Test fun reparentBeforeSiblingKeepsDescendantsAndRemovedRows(){
        val a=node();val b=node();val bc=node(b);val c=node();val removed=node().copy(removed=true)
        val initial=state(listOf(a,b,bc,c,removed),listOf(a.id,b.id,bc.id,c.id)).copy(orderRevision=4)
        val p=StudyOrganization.reparent(initial,b.id,null,a.id);val changed=StudyOrganization.apply(initial,p)
        assertEquals(listOf(b.id,bc.id,a.id,c.id),changed.orderedNodeIds)
        assertEquals(removed,changed.nodes.last());assertEquals(5L,changed.orderRevision)
        assertEquals(initial.nodes.associateBy{it.id},changed.nodes.associateBy{it.id})
        reject{StudyOrganization.reparent(initial,b.id,a.id,bc.id)}
    }

    @Test fun layoutUsesEveryMeasuredNodeAndNeverOverlapsForRightOrBilateralTrees(){
        val root=node();val left=node(root);val leaf=node(left);val right=node(root);val other=node()
        val initial=state(listOf(root,left,leaf,right,other),listOf(root.id,left.id,leaf.id,right.id,other.id)).copy(orderRevision=8)
        val sizes=mapOf(root.id to StudyNodeSize(290.0,560.0),left.id to StudyNodeSize(420.0,1040.0),
            leaf.id to StudyNodeSize(700.0,860.0),right.id to StudyNodeSize(360.0,700.0),other.id to StudyNodeSize(900.0,1400.0))
        for(layout in listOf("right","bilateral")){
            val plan=StudyOrganization.arrange(initial,sizes,layout);val arranged=StudyOrganization.apply(initial,plan)
            assertEquals(initial.orderedNodeIds,arranged.orderedNodeIds);assertEquals(8L,arranged.orderRevision)
            assertEquals(initial.nodes.map{it.id to it.parentId}.toMap(),arranged.nodes.map{it.id to it.parentId}.toMap())
            val positions=plan.after.placements
            positions.forEachIndexed{i,a->positions.drop(i+1).forEach{b->
                val sa=sizes.getValue(a.nodeId);val sb=sizes.getValue(b.nodeId)
                assertTrue("$layout: ${a.nodeId} overlaps ${b.nodeId}",a.x+sa.width<=b.x||b.x+sb.width<=a.x||a.y+sa.height<=b.y||b.y+sb.height<=a.y)
            }}
            assertTrue(positions.all{it.x>=-40_000&&it.y>=-40_000&&it.x+sizes.getValue(it.nodeId).width<=40_000&&it.y+sizes.getValue(it.nodeId).height<=40_000})
        }
        reject{StudyOrganization.arrange(initial,sizes-left.id)}
        reject{StudyOrganization.arrange(initial,sizes+(left.id to StudyNodeSize(Double.NaN,100.0)))}
        reject{StudyOrganization.arrange(initial,sizes.mapValues{StudyNodeSize(50_000.0,50_000.0)})}
    }

    @Test fun previewBindsRelevantCardRevisionDeletionAndGraphMetadata(){
        val a=node();val b=node(a);val initial=state(listOf(a,b),listOf(a.id,b.id)).copy(orderRevision=3)
        val plan=StudyOrganization.move(initial,b.id,500.0,800.0)
        val edited=initial.copy(cardVersions=initial.cardVersions.map{if(it.cardId==b.cardId)it.copy(revision=2)else it})
        val trashed=initial.copy(cardVersions=initial.cardVersions.map{if(it.cardId==b.cardId)it.copy(trashedAt=123)else it})
        reject{StudyOrganization.apply(edited,plan)};reject{StudyOrganization.apply(trashed,plan)}
        reject{StudyOrganization.apply(initial.copy(orderRevision=4),plan)}
        val unrelated=initial.copy(cardVersions=initial.cardVersions+StudyCardVersion(id(),1))
        assertEquals(StudyOrganization.fingerprint(initial),StudyOrganization.fingerprint(unrelated))
        val applied=StudyOrganization.apply(initial,plan)
        assertEquals(plan.expectedAfterGraph,StudyOrganization.fingerprint(applied))
        val away=StudyOrganization.apply(applied,StudyOrganization.move(applied,b.id,700.0,900.0))
        val back=StudyOrganization.apply(away,StudyOrganization.move(away,b.id,500.0,800.0))
        assertEquals(applied.nodes.map{it.id to (it.x to it.y)},back.nodes.map{it.id to (it.x to it.y)})
        reject{StudyOrganization.undo(back,plan)}
        reject{StudyOrganization.apply(applied,plan)}
        reject{StudyOrganization.apply(initial,plan.copy(expectedAfterGraph="0".repeat(64)))}
    }

    @Test fun planCodecAndCommandFreezeRetryPayloadAndBindSiblingAnchor(){
        val a=node();val b=node();val initial=state(listOf(a,b),listOf(a.id,b.id));val plan=StudyOrganization.plan(initial,b.id,StudyOrganizationAction.UP)
        assertEquals(plan,StudyOrganization.decode(StudyOrganization.encode(plan)))
        val callerOrder=plan.after.orderedNodeIds.toMutableList();val command=plan.copy(after=plan.after.copy(orderedNodeIds=callerOrder)).command(id())
        val digest=command.digest();callerOrder.clear()
        StudyOrganization.encode(checkNotNull(command.organization)).fill(0)
        assertEquals(digest,command.digest());assertEquals(plan,command.organization)
        reject{StudyOrganization.decode(StudyOrganization.encode(plan)+byteArrayOf(0))}
        reject{StudyOrganization.decode(ByteArray(StudyOrganization.MAX_BYTES+1))}
        val op=id();val card=id();val node=id()
        val first=StudyCommand(op,initial.ref.notebookId,StudyAction.CREATE,card,node,title="同级",afterNodeId=a.id)
        val second=StudyCommand(op,initial.ref.notebookId,StudyAction.CREATE,card,node,title="同级",afterNodeId=b.id)
        assertNotEquals(first.digest(),second.digest())
        reject{StudyCommand(id(),initial.ref.notebookId,StudyAction.MOVE,nodeId=a.id,expectedRevision=1,afterNodeId=b.id)}
        reject{StudyCommand(id(),initial.ref.notebookId,StudyAction.ORGANIZE)}
    }

    @Test fun mapOrderIsBoundedAndCanonicalExportUsesTheAuthorSequence(){
        val a=node();val b=node();val child=node(b);val nodes=listOf(b,child,a)
        val order=nodes.map{it.id};val mapOrder=KnowledgeData.MapOrder(id(),order)
        assertEquals(mapOrder,KnowledgeCodec.decode(KnowledgeCodec.encode(mapOrder)))
        reject{KnowledgeCodec.encode(mapOrder.copy(orderedNodeIds=listOf(a.id,a.id)))}
        reject{KnowledgeCodec.encode(mapOrder.copy(orderedNodeIds=List(129){id()}))}
        reject{StudyOrganization.canonicalOrder(nodes,listOf(b.id,child.id))}
        val cards=listOf(StudyTextCard(a.cardId,"末项",""),StudyTextCard(b.cardId,"首项",""),StudyTextCard(child.cardId,"子项",""))
        val text=StudyText.markdown("顺序",cards,nodes)
        assertTrue(text.indexOf("- [首项]")<text.indexOf("- [子项]"));assertTrue(text.indexOf("- [子项]")<text.indexOf("- [末项]"))
        val template=MapTemplates.anonymize("顺序","right",nodes,cards.associate{it.id to it.title},true)
        assertEquals(listOf("首项","子项","末项"),template.nodes.map{it.title});assertEquals(0,template.nodes[1].parent)
    }
    @Test fun selectedBranchesMoveOnceKeepContentAndCanUndoThenRedo(){
        val root=node(x=50.0,y=100.0);val child=node(root,150.0,220.0);val leaf=node(child,250.0,340.0);val other=node(x=800.0,y=900.0)
        val initial=state(listOf(root,child,leaf,other),listOf(root.id,child.id,leaf.id,other.id))
        val plan=StudyOrganization.moveSelection(initial,setOf(root.id,child.id),70.0,-40.0)
        val moved=StudyOrganization.apply(initial,plan)
        for(before in listOf(root,child,leaf)){
            val after=moved.nodes.first{it.id==before.id}
            assertEquals(before.x+70,after.x,0.0);assertEquals(before.y-40,after.y,0.0)
            assertEquals(before.cardId,after.cardId);assertEquals(before.parentId,after.parentId)
        }
        assertEquals(other,moved.nodes.first{it.id==other.id});assertEquals(initial.orderedNodeIds,moved.orderedNodeIds)
        val inverse=StudyOrganization.undo(moved,plan);val undone=StudyOrganization.apply(moved,inverse)
        assertEquals(initial.nodes.map{it.x to it.y},undone.nodes.map{it.x to it.y})
        val redone=StudyOrganization.apply(undone,StudyOrganization.undo(undone,inverse))
        assertEquals(moved.nodes.map{it.x to it.y},redone.nodes.map{it.x to it.y})
        reject{StudyOrganization.move(initial,root.id,40000.0,0.0)}
        assertEquals(50.0,initial.nodes.first().x,0.0)
    }

}
