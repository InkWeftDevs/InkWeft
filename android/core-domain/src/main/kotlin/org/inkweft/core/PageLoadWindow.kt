// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Visible pages, one neighbour each way, and the selected author page. Never the whole book. */
object PageLoadWindow {
    fun indices(count:Int,visible:List<Int>,selected:Int):Set<Int> {
        if(count==0)return emptySet()
        require(count>0&&selected in 0 until count&&visible.all{it in 0 until count})
        val anchor=visible.ifEmpty{listOf(selected)}
        val first=(anchor.min()-1).coerceAtLeast(0);val last=(anchor.max()+1).coerceAtMost(count-1)
        return (first..last).toMutableSet().also{it+=selected}
    }
}
