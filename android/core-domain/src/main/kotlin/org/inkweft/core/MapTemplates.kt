// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

data class MapStructure(val id:String,val parentId:String?,val title:String,val x:Double,val y:Double)
/** Deliberately incapable of carrying card IDs, sources, answers, file paths or review history. */
data class TemplateNode(val title:String,val parent:Int?,val x:Double,val y:Double)
object MapTemplates {
    fun arrange(nodes:List<StudyNode>,layout:String):Map<String,CanvasPoint>{
        val positions=StudyGraph.arrange(nodes)
        if(layout!="bilateral")return positions
        val live=nodes.filterNot{it.removed};val byId=live.associateBy{it.id}
        val rootChildren=live.filter{n->n.parentId?.let{byId[it]?.parentId==null}==true}.groupBy{it.parentId}
        val left=rootChildren.values.flatMap{children->children.filterIndexed{i,_->i%2==0}}.map{it.id}.toSet()
        return positions.mapValues{(id,p)->
            var branch=byId.getValue(id)
            while(branch.parentId?.let{byId[it]?.parentId!=null}==true)branch=byId.getValue(branch.parentId!!)
            if(branch.id in left)CanvasPoint(80.0-p.x,p.y)else p
        }
    }
    private fun skeleton(title:String,children:List<String>,layout:String="right")=KnowledgeData.MapTemplate(title,layout=layout,
        nodes=listOf(TemplateNode("中心主题",null,40.0,200.0))+children.mapIndexed{i,t->TemplateNode(t,0,if(layout=="bilateral"&&i%2==0)-240.0 else 300.0,80.0+i*128)})
    val builtins=listOf(KnowledgeData.MapTemplate("空白"),skeleton("右向树",listOf("主题一","主题二")),
        skeleton("双侧中心",listOf("主题一","主题二","主题三","主题四"),"bilateral"),
        skeleton("知识点梳理",listOf("定义与条件","公式与方法","例题与应用","易错对照")),
        skeleton("错题复盘",listOf("题干与条件","解题思路","错因","重做检查")),
        skeleton("考前章节总览",listOf("章节重点","知识联系","易错点","复习安排")))
    fun validate(layout:String,nodes:List<TemplateNode>){
        require(layout in setOf("right","bilateral"))
        require(nodes.size<=StudyGraph.MAX_NODES){"STUDY_NODE_BUDGET"}
        nodes.forEachIndexed{i,n->
            require(n.title.isNotBlank()&&n.title.length<=120&&n.x.isFinite()&&n.y.isFinite()&&n.x in -40000.0..40000.0&&n.y in -40000.0..40000.0)
            require(n.parent==null||n.parent in nodes.indices)
            require(n.parent!=i)
        }
        val resolved=mutableSetOf<Int>()
        nodes.indices.forEach{i->
            val seen=mutableSetOf<Int>();var p:Int?=i
            while(p!=null&&p !in resolved){require(seen.add(p)){"MAP_CYCLE"};p=nodes[p].parent}
            resolved.addAll(seen)
        }
    }
    fun instantiate(template:KnowledgeData.MapTemplate,title:String):KnowledgeData.MapDefinition{
        KnowledgeCodec.validate(template);val ids=template.nodes.map{UUID.randomUUID().toString()}
        return KnowledgeData.MapDefinition(title,template.layout,template.nodes.mapIndexed{i,n->MapStructure(ids[i],n.parent?.let{ids[it]},n.title,n.x,n.y)})
    }
    fun anonymize(title:String,layout:String,nodes:List<StudyNode>,titles:Map<String,String>,keepTitles:Boolean=false):KnowledgeData.MapTemplate{
        val active=nodes.filterNot{it.removed};val indices=active.mapIndexed{i,n->n.id to i}.toMap()
        return KnowledgeData.MapTemplate(title,layout=layout,nodes=active.mapIndexed{i,n->TemplateNode(if(keepTitles)titles[n.cardId].orEmpty().ifBlank{"主题 ${i+1}"}else"主题 ${i+1}",n.parentId?.let{indices.getValue(it)},n.x,n.y)}).also(KnowledgeCodec::validate)
    }
}
