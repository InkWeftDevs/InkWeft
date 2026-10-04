package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MapTemplatesTest {
    @Test fun sixTemplatesRoundTripAndInstantiateIndependentIdentities(){
        assertEquals(6,MapTemplates.builtins.size)
        MapTemplates.builtins.forEach{t->
            assertEquals(t,KnowledgeCodec.decode(KnowledgeCodec.encode(t)))
            val a=MapTemplates.instantiate(t,"学习图");val b=MapTemplates.instantiate(t,"学习图")
            assertEquals(a,KnowledgeCodec.decode(KnowledgeCodec.encode(a)))
            assertTrue(a.structures.map{it.id}.intersect(b.structures.map{it.id}.toSet()).isEmpty())
            assertTrue(a.structures.all{it.parentId==null||a.structures.any{n->n.id==it.parentId}})
        }
    }
    @Test fun personalTemplateRemovesCardIdentityAndPrivateTitlesByDefault(){
        val a=UUID.randomUUID().toString();val card=UUID.randomUUID().toString()
        val template=MapTemplates.anonymize("结构","right",listOf(StudyNode(a,card,null,1.0,2.0)),mapOf(card to "私人资料"))
        assertEquals("主题 1",template.nodes.single().title)
        val bytes=KnowledgeCodec.encode(template).toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(a));assertFalse(bytes.contains(card));assertEquals(1.0,template.nodes.single().x,0.0)
    }
    @Test fun rejectCyclesBudgetNonFiniteAndTrailingPayload(){
        fun rejected(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}}
        rejected{MapTemplates.validate("right",listOf(TemplateNode("a",1,0.0,0.0),TemplateNode("b",0,0.0,0.0)))}
        MapTemplates.validate("right",List(128){TemplateNode("a",null,0.0,0.0)})
        try{MapTemplates.validate("right",List(129){TemplateNode("a",null,0.0,0.0)});fail("must reject")}
        catch(e:IllegalArgumentException){assertEquals("STUDY_NODE_BUDGET",e.message)}
        rejected{MapTemplates.validate("right",listOf(TemplateNode("a",null,Double.NaN,0.0)))}
        rejected{KnowledgeCodec.decode(KnowledgeCodec.encode(MapTemplates.builtins[0])+byteArrayOf(0))}
        rejected{KnowledgeCodec.validate(MapTemplates.builtins[0].copy(version=2))}
    }
    @Test fun bilateralArrangeKeepsDescendantsOnTheirBranchSide(){
        val def=MapTemplates.instantiate(MapTemplates.builtins[2],"双侧")
        val nodes=def.structures.map{StudyNode(it.id,it.id,it.parentId,it.x,it.y)}
        val first=nodes.first{it.parentId!=null};val child=UUID.randomUUID().toString()
        val all=nodes+StudyNode(child,child,first.id,0.0,0.0)
        val arranged=MapTemplates.arrange(all,"bilateral")
        assertTrue(arranged.values.any{it.x<40});assertTrue(arranged.values.any{it.x>40})
        assertEquals(arranged[first.id]!!.x<40,arranged[child]!!.x<40)
        assertEquals(StudyGraph.arrange(all),MapTemplates.arrange(all,"right"))
    }

}
