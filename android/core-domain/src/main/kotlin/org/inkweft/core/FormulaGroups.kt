// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.*

/** Formula recognizers need the complete two-dimensional expression, including bars and scripts. */
object FormulaGroups {
    const val MAX_STROKES=256
    fun block(strokes:List<InkStroke>):HandwritingLine {
        val ink=strokes.filter{it.pen!=InkPen.HIGHLIGHTER}
        require(ink.size in 1..MAX_STROKES){"请框选一条完整公式，最多 256 笔"}
        return HandwritingLine(ink,ink.map{it.bounds()}.reduce{a,b->a.union(b)}.padded(5.0))
    }
    /** Conservative spatial components for settled automatic candidates; never split by baseline. */
    fun automatic(strokes:List<InkStroke>):List<HandwritingLine> {
        val ink=strokes.filter{it.pen!=InkPen.HIGHLIGHTER}
        require(ink.size<=MAX_STROKES*4){"请分段框选完整公式"}
        if(ink.isEmpty())return emptyList()
        val boxes=ink.map{it.bounds()}
        val heights=boxes.map{it.bottom-it.top}.sorted()
        val typical=heights[ceil(heights.lastIndex*.75).toInt()].coerceIn(12.0,100.0)
        val horizontal=max(80.0,typical*2.5);val vertical=max(16.0,typical*.9)
        val parent=IntArray(ink.size){it}
        fun root(index:Int):Int {var i=index;while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i]};return i}
        val active=mutableListOf<Int>()
        for(i in boxes.indices.sortedBy{boxes[it].left}){
            val b=boxes[i];active.removeAll{boxes[it].right+horizontal<b.left}
            for(j in active){
                val a=boxes[j]
                val dx=max(0.0,max(a.left-b.right,b.left-a.right))
                val dy=max(0.0,max(a.top-b.bottom,b.top-a.bottom))
                if(dx==0.0&&dy<=vertical||dy==0.0&&dx<=horizontal)parent[root(i)]=root(j)
            }
            active.add(i)
        }
        return ink.indices.groupBy{root(it)}.values.map{indices->
            val part=indices.map{ink[it]}
            HandwritingLine(part,indices.map{boxes[it]}.reduce{a,b->a.union(b)}.padded(5.0))
        }.sortedWith(compareBy<HandwritingLine>{it.bounds.top}.thenBy{it.bounds.left})
    }
}
