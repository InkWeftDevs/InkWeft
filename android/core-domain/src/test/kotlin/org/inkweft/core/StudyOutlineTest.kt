package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyOutlineTest {
    private fun node(parent:StudyNode?=null,card:String=UUID.randomUUID().toString())=
        StudyNode(UUID.randomUUID().toString(),card,parent?.id,0.0,0.0)

    @Test fun collapseHidesDescendantsWithoutChangingSharedCardsOrExport(){
        val root=node();val child=node(root);val leaf=node(child,root.cardId);val other=node()
        val nodes=listOf(root,child,leaf,other)
        val result=StudyOutline.project(nodes,setOf(root.id))
        assertEquals(listOf(root.id,other.id),result.rows.map{it.node.id})
        assertEquals(2,result.rows.first().descendants)
        assertEquals(listOf(0,1,2,0),StudyOutline.project(nodes).rows.map{it.depth})
        val cards=nodes.distinctBy{it.cardId}.map{StudyTextCard(it.cardId,it.id,"保留正文")}
        val export=StudyText.markdown("完整文档",cards,nodes)
        assertTrue(export.contains(child.id));assertEquals(4,Regex("\\(#card-").findAll(export).count())
    }

    @Test fun focusRebasesDepthAndIncludesAncestorsEvenWhenAncestorCollapsed(){
        val root=node();val child=node(root);val leaf=node(child);val other=node()
        val result=StudyOutline.project(listOf(root,child,leaf,other),setOf(root.id),child.id)
        assertEquals(listOf(child.id,leaf.id),result.rows.map{it.node.id})
        assertEquals(listOf(0,1),result.rows.map{it.depth})
        assertEquals(listOf(root.id,child.id),result.path.map{it.id})
    }

    @Test fun missingOrRemovedFocusFallsBackToAllAndStaleCollapseIdsAreHarmless(){
        val root=node();val old=node().copy(removed=true)
        assertEquals(listOf(root.id),StudyOutline.project(listOf(root,old),setOf(old.id),old.id).rows.map{it.node.id})
        assertTrue(StudyOutline.project(listOf(root),focusId="missing").path.isEmpty())
        assertTrue(StudyOutline.project(emptyList()).rows.isEmpty())
    }

    @Test fun identicalCardOccurrencesCanBeCollapsedIndependently(){
        val a=node();val b=node(card=a.cardId);val ac=node(a);val bc=node(b)
        assertEquals(listOf(a.id,b.id,bc.id),StudyOutline.project(listOf(a,b,ac,bc),setOf(a.id)).rows.map{it.node.id})
    }
}
