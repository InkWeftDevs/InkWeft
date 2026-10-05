// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** beforeY is an immutable original-page anchor; height changes only the display crop. */
data class DocumentWhitespace(val id:String,val beforeY:Double,val height:Double=300.0,val collapsed:Boolean=false) {
    init { UUID.fromString(id);require(beforeY.isFinite()&&beforeY in 0.0..InkLimits.HEIGHT.toDouble());require(height.isFinite()&&height in 80.0..2000.0) }
    val shownHeight get()=if(collapsed)28.0 else height
}
sealed interface WhitespacePoint {
    data class Original(val point:CanvasPoint):WhitespacePoint
    data class Blank(val id:String,val local:CanvasPoint,val collapsed:Boolean):WhitespacePoint
}
/** Piecewise translation. No PDF bytes, source anchors or author samples are rewritten. */
class DocumentWhitespaceLayout(blanks:List<DocumentWhitespace>) {
    val blanks=blanks.sortedWith(compareBy<DocumentWhitespace>{it.beforeY}.thenBy{it.id})
    init { require(blanks.size<=MAX_BLANKS&&blanks.map{it.id}.distinct().size==blanks.size) }
    val height get()=InkLimits.HEIGHT+blanks.sumOf{it.shownHeight}
    fun originalToDisplay(point:CanvasPoint)=CanvasPoint(point.x,point.y+blanks.filter{it.beforeY<=point.y}.sumOf{it.shownHeight})
    fun blankTop(id:String):Double {
        val index=blanks.indexOfFirst{it.id==id};require(index>=0)
        return blanks[index].beforeY+blanks.take(index).sumOf{it.shownHeight}
    }
    fun blankToDisplay(id:String,local:CanvasPoint)=CanvasPoint(local.x,blankTop(id)+local.y)
    fun displayToAuthor(point:CanvasPoint):WhitespacePoint {
        var shift=0.0
        for(blank in blanks){
            val top=blank.beforeY+shift
            if(point.y<top)break
            if(point.y<top+blank.shownHeight)return WhitespacePoint.Blank(blank.id,CanvasPoint(point.x,point.y-top),blank.collapsed)
            shift+=blank.shownHeight
        }
        return WhitespacePoint.Original(CanvasPoint(point.x,point.y-shift))
    }
    /** Source segments are clipped at insertions; the lower segment owns the boundary. */
    fun sourceSegments():List<Pair<CanvasBounds,Double>> {
        var previous=0.0;var shift=0.0
        return buildList {
            for(blank in blanks){if(blank.beforeY>previous)add(CanvasBounds(0.0,previous,1000.0,blank.beforeY) to shift);previous=blank.beforeY;shift+=blank.shownHeight}
            if(previous<InkLimits.HEIGHT)add(CanvasBounds(0.0,previous,1000.0,InkLimits.HEIGHT.toDouble()) to shift)
        }
    }
    companion object { const val MAX_BLANKS=32 }
}
