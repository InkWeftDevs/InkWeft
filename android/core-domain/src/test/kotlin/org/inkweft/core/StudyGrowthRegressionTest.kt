// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyGrowthRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun nodes(deep:Boolean):List<StudyNode>{
        val ids=List(StudyGraph.MAX_NODES){id()};val card=id()
        return ids.mapIndexed{i,n->StudyNode(n,card,if(deep&&i>0)ids[i-1]else null,40.0,80.0)}
    }
    @Test fun maximumWideAndDeepMapsCanArrangeSaveAndReopenWithoutOverlap(){
        for(deep in listOf(false,true)){
            val nodes=nodes(deep);StudyGraph.validate(nodes)
            assertEquals(nodes.size,StudyGraph.arrange(nodes).size)
            val order=nodes.map{it.id};val state=StudyGraphState(MapRef(id(),null),nodes,order)
            assertEquals(order,StudyOrganization.canonicalOrder(nodes,order))
            val sizes=order.associateWith{StudyNodeSize(300.0,180.0)}
            val plan=StudyOrganization.arrange(state,sizes)
            val decoded=StudyOrganization.decode(StudyOrganization.encode(plan));assertEquals(plan,decoded)
            val arranged=StudyOrganization.apply(state,decoded)
            assertEquals(nodes.map{it.id}.toSet(),arranged.nodes.map{it.id}.toSet())
            arranged.nodes.forEach{n->assertTrue(n.x>=-40000&&n.x+300<=40000&&n.y>=-40000&&n.y+180<=40000)}
            val boxes=arranged.nodes.map{CanvasBounds(it.x,it.y,it.x+300,it.y+180)}
            boxes.indices.forEach{i->for(j in 0 until i)assertFalse(boxes[i].intersects(boxes[j]))}
        }
    }
    @Test fun largeDefinitionsTemplatesOrdersAndAnchorsRoundTrip(){
        val nodes=nodes(true)
        val structures=nodes.map{MapStructure(it.id,it.parentId,"汉".repeat(120),it.x,it.y)}
        val values=listOf<KnowledgeData>(KnowledgeData.MapDefinition("大图",structures=structures),
            KnowledgeData.MapTemplate("模板",nodes=nodes.mapIndexed{i,n->TemplateNode("汉".repeat(120),if(i==0)null else i-1,n.x,n.y)}),
            KnowledgeData.MapOrder(null,nodes.map{it.id}),
            KnowledgeData.Anchor(id(),1,CanvasBounds(0.0,0.0,500.0,500.0),nodes.map{it.id}))
        values.forEach{assertEquals(it,KnowledgeCodec.decode(KnowledgeCodec.encode(it)))}
        val scene=MapScene(MapRef(id(),id()),"大图",nodes.map{MapSceneNode(it.id,it.parentId,it.cardId,"主题","",it.x,it.y,1,1)},"a".repeat(64))
        val embed=MapEmbed(scene.ref,policy=MapEmbedPolicy.PINNED,snapshot=scene)
        assertEquals(embed,MapEmbedCodec.decode(MapEmbedCodec.encode(embed)))
        assertEquals(StudyCapacity.MAX_CARDS_PER_NOTEBOOK,StudyGraphState(MapRef(id(),null),emptyList(),emptyList(),
            cardVersions=List(StudyCapacity.MAX_CARDS_PER_NOTEBOOK){StudyCardVersion(id(),1)}).let{StudyOrganization.fingerprint(it);it.cardVersions.size})
    }
    @Test fun cyclesInRemovedHistoryRemainRejectedAndSmallLayoutKeepsOldCoordinates(){
        val a=StudyNode(id(),id(),null,0.0,0.0);val b=StudyNode(id(),id(),a.id,0.0,0.0)
        assertThrows(IllegalArgumentException::class.java){StudyGraph.validate(listOf(a.copy(parentId=b.id,removed=true),b.copy(removed=true)))}
        val coordinates=StudyGraph.arrange(listOf(a,b));assertEquals(CanvasPoint(40.0,128.0),coordinates[a.id]);assertEquals(CanvasPoint(300.0,128.0),coordinates[b.id])
    }
}
