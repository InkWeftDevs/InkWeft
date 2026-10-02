// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.*

data class HandwritingLine(val strokes:List<InkStroke>,val bounds:CanvasBounds)

/** Uses author coordinates, never screen zoom. Recognition remains a derived, reviewable result. */
object HandwritingLines {
    fun split(strokes:List<InkStroke>):List<HandwritingLine> {
        val writing=strokes.filter{it.pen!=InkPen.HIGHLIGHTER}
        require(writing.size<=InkLimits.MAX_STROKES)
        if(writing.isEmpty())return emptyList()
        val heights=writing.map{(it.bounds().bottom-it.bounds().top).coerceAtLeast(2.0)}.sorted()
        // Short horizontal strokes dominate many Han characters; the median is not a line height.
        val typical=heights[ceil(heights.lastIndex*.75).toInt()].coerceIn(12.0,100.0)
        val groups=mutableListOf<MutableList<InkStroke>>()
        val boxes=mutableListOf<CanvasBounds>()
        for(stroke in writing.sortedBy{(it.bounds().top+it.bounds().bottom)/2}) {
            val b=stroke.bounds();val center=(b.top+b.bottom)/2
            val index=boxes.indices.filter{i->
                val g=boxes[i];val overlap=min(b.bottom,g.bottom)-max(b.top,g.top)
                overlap>=min(b.bottom-b.top,g.bottom-g.top)*.25||abs(center-(g.top+g.bottom)/2)<typical*.55
            }.minByOrNull{i->abs(center-(boxes[i].top+boxes[i].bottom)/2)}
            if(index==null){groups.add(mutableListOf(stroke));boxes.add(b)}
            else {groups[index].add(stroke);boxes[index]=boxes[index].union(b)}
        }
        // A dot above a tall character may have been encountered before its main strokes.
        // Attach only small isolated marks with horizontal overlap and a close vertical gap.
        for(i in groups.indices){
            if(groups[i].isEmpty())continue
            val b=boxes[i];val h=b.bottom-b.top;val w=b.right-b.left
            if(h>typical*.65||w>typical*.8)continue
            val target=groups.indices.filter{j->j!=i&&groups[j].isNotEmpty()&&boxes[j].bottom-boxes[j].top>=typical}.filter{j->
                val g=boxes[j];val gap=max(0.0,max(g.top-b.bottom,b.top-g.bottom))
                b.right>=g.left-typical*.5&&b.left<=g.right+typical*.5&&gap<=typical*.65
            }.minByOrNull{j->abs((boxes[j].top+boxes[j].bottom-b.top-b.bottom)/2)}
            if(target!=null){groups[target].addAll(groups[i]);boxes[target]=boxes[target].union(b);groups[i].clear()}
        }
        require(groups.size<=100){"HANDWRITING_LINE_BUDGET"}
        val lines=groups.indices.filter{groups[it].isNotEmpty()}.sortedBy{boxes[it].top}.flatMap{i->
            val parts=mutableListOf<MutableList<InkStroke>>();var right=Double.NEGATIVE_INFINITY
            for(s in groups[i].sortedBy{it.bounds().left}){
                val b=s.bounds()
                if(parts.isEmpty()||b.left-right>max(80.0,typical*2.5))parts.add(mutableListOf())
                parts.last().add(s);right=max(right,b.right)
            }
            parts.map{p->HandwritingLine(p,p.map{it.bounds()}.reduce{a,b->a.union(b)}.padded(5.0))}
        }
        require(lines.size<=100){"HANDWRITING_LINE_BUDGET"}
        return lines
    }
}
