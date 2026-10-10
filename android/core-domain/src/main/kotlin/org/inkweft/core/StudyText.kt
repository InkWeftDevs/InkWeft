// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

data class StudyTextCard(val id:String,val title:String,val body:String,val annotation:String="")

/** Text export preserves shared-card occurrences without duplicating authoritative content. */
object StudyText {
    fun matches(card:StudyTextCard,query:String):Boolean = query.trim().let {
        it.isEmpty() || card.title.contains(it,ignoreCase=true) || card.body.contains(it,ignoreCase=true) || card.annotation.contains(it,ignoreCase=true)
    }
    private fun inline(text:String)=text.replace('\n',' ').replace('\r',' ')
        .replace("\\","\\\\").replace("[","\\[").replace("]","\\]")
        .replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("#","\\#")
    fun markdown(title:String,cards:List<StudyTextCard>,nodes:List<StudyNode>,groups:List<KnowledgeData.MapSummaryGroup> = emptyList()):String {
        StudyGraph.validate(nodes)
        val byId=cards.associateBy{it.id}
        require(byId.size==cards.size)
        val active=nodes.filter{!it.removed}
        require(active.all{it.cardId in byId})
        val children=active.groupBy{it.parentId}
        MapSummaries.validate(nodes,StudyOrganization.canonicalOrder(nodes,active.map{it.id}),groups)
        return buildString {
            append("# ").append(inline(title)).append("\n\n")
            if(active.isNotEmpty()){
                append("## 大纲\n\n")
                val pending=ArrayDeque<Pair<StudyNode,Int>>()
                children[null].orEmpty().asReversed().forEach{pending.addLast(it to 0)}
                while(pending.isNotEmpty()){
                    val (n,depth)=pending.removeLast()
                    append("  ".repeat(depth)).append("- [").append(inline(byId.getValue(n.cardId).title))
                        .append("](#card-").append(n.cardId).append(")\n")
                    children[n.id].orEmpty().asReversed().forEach{pending.addLast(it to depth+1)}
                }
                append('\n')
            }
            if(groups.isNotEmpty()){
                val byNode=active.associateBy{it.id};append("## 括号归纳\n\n")
                groups.forEach{group->
                    append("- **").append(inline(group.label)).append("**：")
                    append(group.memberIds.joinToString("、"){id->val card=byId.getValue(byNode.getValue(id).cardId);"[${inline(card.title)}](#card-${card.id})"}).append('\n')
                }
                append('\n')
            }
            append("## 摘要卡\n\n")
            cards.forEach{c->
                append("<a id=\"card-").append(c.id).append("\"></a>\n\n### ")
                    .append(inline(c.title)).append("\n\n").append(c.body).append("\n\n")
                if(c.annotation.isNotBlank())append("#### 个人注释\n\n").append(c.annotation).append("\n\n")
            }
        }
    }
}
