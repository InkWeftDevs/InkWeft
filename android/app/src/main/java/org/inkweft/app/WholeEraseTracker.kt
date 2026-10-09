// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.*

/** Visit only the new swept segment at each display frame, retaining exact painted-ink hits. */
internal class WholeEraseTracker(private val strokes:List<InkStroke>,private val radius:Float){
    private val geometry=VisibleInkGeometry()
    private var processed=0
    val ids=linkedSetOf<String>()
    fun update(samples:List<InkSample>){
        if(samples.size<=processed)return
        val next=samples.subList((processed-1).coerceAtLeast(0),samples.size)
        val bounds=VisibleInkGeometry.eraserBounds(next,radius)
        val mask=VisibleInkGeometry.sweptPath(next.map{EraserPoint(it.x,it.y)},radius)
        strokes.forEach{if(it.id !in ids&&geometry.hits(it,mask,bounds))ids.add(it.id)}
        processed=samples.size
    }
}
