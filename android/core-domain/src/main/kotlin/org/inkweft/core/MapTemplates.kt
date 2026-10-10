// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

data class MapStructure(val id:String,val parentId:String?,val title:String,val x:Double,val y:Double)
/** Deliberately incapable of carrying card IDs, sources, answers, file paths or review history. */
data class TemplateNode(val title:String,val parent:Int?,val x:Double,val y:Double)
object MapTemplates {
    fun arrange(nodes:List<StudyNode>,layout:String):Map<String,CanvasPoint>{
        val order=StudyOrganization.canonicalOrder(nodes,nodes.filterNot{it.removed}.map{it.id})
        val state=StudyGraphState(MapRef(UUID(0,0).toString()),nodes,order)
        return StudyOrganization.arrange(state,order.associateWith{StudyNodeSize(232.0,64.0)},layout)
            .after.placements.associate{it.nodeId to CanvasPoint(it.x,it.y)}
    }
    private fun skeleton(title:String,children:List<String>,layout:String="right")=nested(title,layout,
        listOf("中心主题" to null)+children.map{it to 0})
    private fun nested(title:String,layout:String,topics:List<Pair<String,Int?>>):KnowledgeData.MapTemplate{
        val ids=topics.indices.map{UUID(0,it.toLong()+1).toString()}
        val nodes=topics.mapIndexed{i,(_,parent)->StudyNode(ids[i],ids[i],parent?.let{ids[it]},0.0,0.0)}
        val positions=arrange(nodes,layout)
        return KnowledgeData.MapTemplate(title,layout=layout,nodes=topics.mapIndexed{i,(name,parent)->
            positions.getValue(ids[i]).let{TemplateNode(name,parent,it.x,it.y)}})
    }
    // The first six choices keep their stable UI indices; new, original outlines follow.
    val builtins=listOf(KnowledgeData.MapTemplate("空白"),skeleton("右向树",listOf("主题一","主题二")),
        skeleton("双侧中心",listOf("主题一","主题二","主题三","主题四"),"bilateral"),
        nested("知识点梳理","right",listOf("知识点" to null,"定义与条件" to 0,"适用条件" to 1,"反例与边界" to 1,
            "公式与方法" to 0,"推导依据" to 4,"使用步骤" to 4,"例题与应用" to 0,"迁移练习" to 7,"易错对照" to 0,"自测问题" to 9)),
        nested("错题复盘","right",listOf("一道错题" to null,"题干与条件" to 0,"已知与目标" to 1,"解题思路" to 0,"关键转折" to 3,
            "错因" to 0,"概念误用" to 5,"计算或审题" to 5,"重做检查" to 0,"独立重做" to 8,"同类变式" to 8)),
        nested("考前章节总览","bilateral",listOf("章节" to null,"章节重点" to 0,"概念与条件" to 1,"知识联系" to 0,"前置章节" to 3,
            "易错点" to 0,"典型反例" to 5,"复习安排" to 0,"待复习" to 7,"自测与回顾" to 7)),
        skeleton("左向树",listOf("主题一","主题二"),"left"),
        nested("概念对比","bilateral",listOf("比较主题" to null,"概念甲" to 0,"定义与适用条件" to 1,"代表例子" to 1,
            "概念乙" to 0,"定义与适用条件" to 4,"代表例子" to 4,"共同点" to 0,"区别与选择" to 0,"判别练习" to 8)),
        nested("阅读提炼","right",listOf("书籍或文章" to null,"核心问题" to 0,"作者主张" to 0,"论据与出处" to 2,
            "我的疑问" to 0,"待核实依据" to 4,"联系与应用" to 0,"下一步行动" to 6)),
        nested("公式推导","right",listOf("目标公式" to null,"前提与符号" to 0,"定义域与单位" to 1,"推导链" to 0,
            "起点与依据" to 3,"关键步骤" to 3,"边界检验" to 0,"特殊情形" to 6,"应用题" to 0,"易错条件" to 8)),
        nested("问题分析","bilateral",listOf("待解决问题" to null,"现象与目标" to 0,"可验证指标" to 1,"原因假设" to 0,
            "证据与反证" to 3,"方案比较" to 0,"收益与代价" to 5,"验证计划" to 0,"结果与下一步" to 7)),
        nested("项目拆解","organization",listOf("项目目标" to null,"范围与交付" to 0,"验收条件" to 1,"任务分解" to 0,
            "待办" to 3,"进行中" to 3,"依赖与风险" to 0,"应对措施" to 6,"里程碑" to 0,"复盘" to 8)))
    fun category(template:KnowledgeData.MapTemplate):String=when(template.title){
        "空白","右向树","双侧中心","左向树"->"基础结构"
        "知识点梳理","错题复盘","考前章节总览","概念对比","公式推导"->"学习复习"
        "阅读提炼","问题分析","项目拆解"->"阅读与规划"
        else->"我的模板"
    }
    fun description(template:KnowledgeData.MapTemplate):String=when(template.title){
        "知识点梳理"->"把定义、适用边界、方法和自测放在同一张图里"
        "错题复盘"->"从条件到错因，再用重做和变式确认掌握"
        "考前章节总览"->"双侧整理重点、联系、易错点和复习安排"
        "概念对比"->"并列比较两个概念的条件、例子和选择依据"
        "阅读提炼"->"分开作者主张、证据、自己的疑问与应用"
        "公式推导"->"按前提、依据、步骤和边界检验组织推导"
        "问题分析"->"把假设、证据、方案代价和验证结果串起来"
        "项目拆解"->"用组织图拆分交付、任务、依赖与里程碑"
        else->"${MapLayouts.label(template.layout)} · ${template.nodes.size} 个结构主题"
    }
    fun validate(layout:String,nodes:List<TemplateNode>){
        require(layout in MapLayouts.supported)
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
