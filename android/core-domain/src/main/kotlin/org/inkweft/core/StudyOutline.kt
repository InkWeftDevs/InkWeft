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
        fun count(n:StudyNode,seen:MutableSet<String>):Int =
            if(!seen.add(n.id))0 else children[n.id].orEmpty().sumOf{1+count(it,seen)}
        val rows=mutableListOf<Row>()
        val visited=mutableSetOf<String>()
        fun walk(n:StudyNode,depth:Int){
            if(!visited.add(n.id))return
            rows+=Row(n,depth,count(n,mutableSetOf()))
            if(n.id !in collapsed)children[n.id].orEmpty().forEach{walk(it,depth+1)}
        }
        (focus?.let{listOf(it)}?:children[null].orEmpty()).forEach{walk(it,0)}
        return Projection(rows,path.asReversed())
    }
}
