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
        val typical=heights[heights.size/2].coerceIn(12.0,100.0)
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
        require(groups.size<=100){"HANDWRITING_LINE_BUDGET"}
        return groups.indices.sortedBy{boxes[it].top}.map{i->HandwritingLine(groups[i].toList(),boxes[i].padded(5.0))}
    }
}
