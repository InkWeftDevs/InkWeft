// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Resize only the source window, never its underlying ink or objects. */
enum class ExcerptHandle(val x:Int,val y:Int) {
    TOP_LEFT(-1,-1), TOP(0,-1), TOP_RIGHT(1,-1), RIGHT(1,0),
    BOTTOM_RIGHT(1,1), BOTTOM(0,1), BOTTOM_LEFT(-1,1), LEFT(-1,0), MOVE(0,0)
}
object ExcerptBounds {
    fun inside(b:CanvasBounds,limit:CanvasBounds):CanvasBounds? {
        val left=maxOf(b.left,limit.left);val top=maxOf(b.top,limit.top)
        val right=minOf(b.right,limit.right);val bottom=minOf(b.bottom,limit.bottom)
        return if(right>left&&bottom>top)CanvasBounds(left,top,right,bottom)else null
    }
    fun drag(b:CanvasBounds,handle:ExcerptHandle,dx:Double,dy:Double,limit:CanvasBounds,minSize:Double=8.0):CanvasBounds {
        require(dx.isFinite()&&dy.isFinite()&&minSize>0)
        require(b.left>=limit.left&&b.top>=limit.top&&b.right<=limit.right&&b.bottom<=limit.bottom)
        if(handle==ExcerptHandle.MOVE){
            val x=dx.coerceIn(limit.left-b.left,limit.right-b.right)
            val y=dy.coerceIn(limit.top-b.top,limit.bottom-b.bottom)
            return CanvasBounds(b.left+x,b.top+y,b.right+x,b.bottom+y)
        }
        val w=minOf(minSize,b.right-b.left);val h=minOf(minSize,b.bottom-b.top)
        return CanvasBounds(
            if(handle.x<0)(b.left+dx).coerceIn(limit.left,b.right-w)else b.left,
            if(handle.y<0)(b.top+dy).coerceIn(limit.top,b.bottom-h)else b.top,
            if(handle.x>0)(b.right+dx).coerceIn(b.left+w,limit.right)else b.right,
            if(handle.y>0)(b.bottom+dy).coerceIn(b.top+h,limit.bottom)else b.bottom)
    }
}
