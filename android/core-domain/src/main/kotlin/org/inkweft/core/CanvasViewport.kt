// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.max
import kotlin.math.min

/** A board has no paper edge, but finite coordinates/resource budgets still apply. */
object BoardLimits { const val WORLD = 1_000_000f }
// Persisted ordinals: append only; old readers reject unknown templates explicitly.
enum class PaperStyle { BLANK, RULED, GRID, DOTS, CORNELL, CORNELL_WIDE, CORNELL_BLANK, MISTAKES, DERIVATION, DAILY, CORNELL_GRID, DAILY_PLANNER, WEEKLY, MONTHLY, HABIT, MEETING, PROJECT, READING, VOCABULARY, EXPENSE, MEAL, FITNESS, MUSIC, HANDWRITING }

data class CanvasPoint(val x: Double, val y: Double)
data class CanvasBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(listOf(left,top,right,bottom).all { it.isFinite() }); require(right>=left && bottom>=top) }
    fun intersects(other: CanvasBounds) = right>=other.left && left<=other.right && bottom>=other.top && top<=other.bottom
    fun union(other: CanvasBounds) = CanvasBounds(min(left,other.left),min(top,other.top),max(right,other.right),max(bottom,other.bottom))
    fun padded(n: Double) = CanvasBounds(left-n,top-n,right+n,bottom+n)
}

/** zoom is dp / author-unit; a resize never rewrites author coordinates. */
data class CanvasViewport(val centerX: Double=500.0, val centerY: Double=707.0, val zoom: Double=.7) {
    init {
        require(centerX.isFinite() && centerY.isFinite() && zoom.isFinite())
        require(centerX in -BoardLimits.WORLD.toDouble()..BoardLimits.WORLD.toDouble())
        require(centerY in -BoardLimits.WORLD.toDouble()..BoardLimits.WORLD.toDouble())
        require(zoom in .02..8.0)
    }
    fun screenToWorld(x: Double,y: Double,widthPx: Double,heightPx: Double,density: Double): CanvasPoint {
        require(density.isFinite() && density>0)
        return CanvasPoint(centerX+(x-widthPx/2)/(zoom*density),centerY+(y-heightPx/2)/(zoom*density))
    }
    fun worldToScreen(x: Double,y: Double,widthPx: Double,heightPx: Double,density: Double) =
        CanvasPoint((x-centerX)*zoom*density+widthPx/2,(y-centerY)*zoom*density+heightPx/2)
    fun pan(dxPx: Double,dyPx: Double,density: Double): CanvasViewport = safe(centerX-dxPx/(zoom*density),centerY-dyPx/(zoom*density),zoom)
    fun zoomAt(ratio: Double,x: Double,y: Double,w: Double,h: Double,density: Double): CanvasViewport {
        require(ratio.isFinite() && ratio>0)
        val anchor=screenToWorld(x,y,w,h,density);val next=(zoom*ratio).coerceIn(.02,8.0)
        return safe(anchor.x-(x-w/2)/(next*density),anchor.y-(y-h/2)/(next*density),next)
    }
    fun visible(w: Double,h: Double,density: Double): CanvasBounds {
        val a=screenToWorld(0.0,0.0,w,h,density);val b=screenToWorld(w,h,w,h,density)
        return CanvasBounds(a.x,a.y,b.x,b.y)
    }
    /** Keep the reading origin and scale when surrounding panels change the visible area. */
    fun resizedFrom(oldWidthDp:Double,oldHeightDp:Double,widthDp:Double,heightDp:Double):CanvasViewport =
        safe(centerX+(widthDp-oldWidthDp)/(2*zoom),centerY+(heightDp-oldHeightDp)/(2*zoom),zoom)
    /** A finite sheet stays reachable; empty space cannot be panned indefinitely. */
    fun constrainedToPaper(wDp:Double,hDp:Double):CanvasViewport {
        if(wDp<=0||hDp<=0)return this
        val z=zoom.coerceAtLeast(min(wDp/1000.0,hDp/1414.0).coerceIn(.02,8.0))
        fun center(value:Double,extent:Double,screen:Double):Double {
            val half=screen/(2*z)
            return if(half*2>=extent)extent/2 else value.coerceIn(half,extent-half)
        }
        return safe(center(centerX,1000.0,wDp),center(centerY,1414.0,hDp),z)
    }
    companion object {
        fun safe(x: Double,y: Double,z: Double): CanvasViewport {
            require(x.isFinite() && y.isFinite() && z.isFinite())
            val limit=BoardLimits.WORLD.toDouble()
            return CanvasViewport(x.coerceIn(-limit,limit),y.coerceIn(-limit,limit),z.coerceIn(.02,8.0))
        }
        fun fit(bounds: CanvasBounds,wDp: Double,hDp: Double): CanvasViewport {
            val z=min(max(1.0,wDp-40)/max(80.0,bounds.right-bounds.left),max(1.0,hDp-40)/max(80.0,bounds.bottom-bounds.top)).coerceIn(.02,8.0)
            return safe((bounds.left+bounds.right)/2,(bounds.top+bounds.bottom)/2,z)
        }
        /** First opening only: a narrow sheet starts at readable scale, with its leading edge visible. */
        fun readingStart(wDp:Double,hDp:Double):CanvasViewport {
            val fitted=pageWidth(wDp,hDp)
            if(fitted.zoom>=.8)return fitted
            return safe(wDp/(2*.8),hDp/(2*.8),.8)
        }
        fun pageWidth(wDp: Double,hDp: Double): CanvasViewport {
            val z=(max(1.0,wDp-40)/InkLimits.WIDTH).coerceIn(.02,8.0)
            return safe(500.0,max(0.0,(hDp/2-20)/z),z)
        }
    }
}

fun InkStroke.bounds(): CanvasBounds {
    val pad=coverageRadius().toDouble()
    return CanvasBounds(samples.minOf { it.x }.toDouble()-pad,samples.minOf { it.y }.toDouble()-pad,
        samples.maxOf { it.x }.toDouble()+pad,samples.maxOf { it.y }.toDouble()+pad)
}
