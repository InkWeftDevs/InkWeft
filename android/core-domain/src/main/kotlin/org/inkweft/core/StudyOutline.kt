// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** A navigation projection only; hidden nodes retain their cards, parents and export content. */
object StudyOutline {
    data class Row(val node:StudyNode,val depth:Int,val descendants:Int)
    data class Projection(val rows:List<Row>,val path:List<StudyNode>)

    fun project(nodes:List<StudyNode>,collapsed:Set<String> = emptySet(),focusId:String?=null):Projection {
        val active=nodes.filterNot{it.removed}
        val byId=active.associateBy{it.id}
        val children=active.groupBy{it.parentId}
        val focus=byId[focusId]
        val path=mutableListOf<StudyNode>()
        var ancestor=focus
        val ancestors=mutableSetOf<String>()
        while(ancestor!=null&&ancestors.add(ancestor.id)){path+=ancestor;ancestor=byId[ancestor.parentId]}
        // Compute each subtree once, including folded descendants. An explicit
        // postorder keeps deep outlines off the call stack and avoids per-row walks.
        val descendants=mutableMapOf<String,Int>()
        val entered=mutableSetOf<String>()
        for(start in active){
            if(start.id in entered)continue
            val pending=ArrayDeque<Pair<StudyNode,Boolean>>()
            pending.addLast(start to false)
            while(pending.isNotEmpty()){
                val (node,expanded)=pending.removeLast()
                if(expanded){
                    descendants[node.id]=children[node.id].orEmpty().sumOf{child->
                        descendants[child.id]?.let{1+it}?:0
                    }
                }else if(entered.add(node.id)){
                    pending.addLast(node to true)
                    children[node.id].orEmpty().asReversed().forEach{child->
                        if(child.id !in entered)pending.addLast(child to false)
                    }
                }
            }
        }
        val rows=mutableListOf<Row>()
        val visited=mutableSetOf<String>()
        val pending=ArrayDeque<Pair<StudyNode,Int>>()
        (focus?.let{listOf(it)}?:children[null].orEmpty()).asReversed().forEach{pending.addLast(it to 0)}
        while(pending.isNotEmpty()){
            val (node,depth)=pending.removeLast()
            if(!visited.add(node.id))continue
            rows+=Row(node,depth,descendants[node.id]?:0)
            if(node.id !in collapsed)children[node.id].orEmpty().asReversed().forEach{pending.addLast(it to depth+1)}
        }
        return Projection(rows,path.asReversed())
    }
}
