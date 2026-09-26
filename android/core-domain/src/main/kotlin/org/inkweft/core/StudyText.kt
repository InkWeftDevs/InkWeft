// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

data class StudyTextCard(val id:String,val title:String,val body:String)

/** Text export preserves shared-card occurrences without duplicating authoritative content. */
object StudyText {
    fun matches(card:StudyTextCard,query:String):Boolean = query.trim().let {
        it.isEmpty() || card.title.contains(it,ignoreCase=true) || card.body.contains(it,ignoreCase=true)
    }
    private fun inline(text:String)=text.replace('\n',' ').replace('\r',' ')
        .replace("\\","\\\\").replace("[","\\[").replace("]","\\]")
        .replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("#","\\#")
    fun markdown(title:String,cards:List<StudyTextCard>,nodes:List<StudyNode>):String {
        StudyGraph.validate(nodes)
        val byId=cards.associateBy{it.id}
        require(byId.size==cards.size)
        val active=nodes.filter{!it.removed}
        require(active.all{it.cardId in byId})
        val children=active.groupBy{it.parentId}
        return buildString {
            append("# ").append(inline(title)).append("\n\n")
            if(active.isNotEmpty()){
                append("## 大纲\n\n")
                fun visit(parent:String?,depth:Int){
                    children[parent].orEmpty().sortedBy{it.id}.forEach{n->
                        append("  ".repeat(depth)).append("- [").append(inline(byId.getValue(n.cardId).title))
                            .append("](#card-").append(n.cardId).append(")\n")
                        visit(n.id,depth+1)
                    }
                }
                visit(null,0);append('\n')
            }
            append("## 摘要卡\n\n")
            cards.forEach{c->
                append("<a id=\"card-").append(c.id).append("\"></a>\n\n### ")
                    .append(inline(c.title)).append("\n\n").append(c.body).append("\n\n")
            }
        }
    }
}
