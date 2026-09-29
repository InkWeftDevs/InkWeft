// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.*
import kotlin.math.*

/** Font substitution keeps local ink colour and pencil density, independently per grapheme. */
internal object BeautyAppearance {
    fun apply(g:TextGlyph,strokes:List<InkStroke>,box:CanvasBounds):TextGlyph {
        if(strokes.isEmpty())return g
        val cx=(box.left+box.right)/2;val cy=(box.top+box.bottom)/2
        val candidates=strokes.filter{it.bounds().intersects(box)}.ifEmpty{strokes}
        val source=candidates.minBy{stroke->stroke.samples.minOf{p->
            val dx=max(0.0,max(box.left-p.x,p.x-box.right));val dy=max(0.0,max(box.top-p.y,p.y-box.bottom))
            dx*dx+dy*dy+1e-6*((p.x-cx).pow(2)+(p.y-cy).pow(2))
        }}
        if(source.pen!=InkPen.PENCIL)return g.copy(color=source.color,grain=0f)
        val r=source.appearance.recipe
        val pressure=source.samples.map{sqrt(if(it.pressure<0).5f else it.pressure)}.average().toFloat()
        val density=(r.hardnessFactor*r.density*(.18f+.55f*pressure)).coerceIn(0f,1f)
        val alpha=((source.color ushr 24)*density).roundToInt().coerceIn(0,255)
        return g.copy(color=(alpha shl 24)or(source.color and 0xffffff),grain=r.grain)
    }
    /** Old font objects still retain source IDs. Restore appearance in memory without changing author data. */
    fun restore(o:PageObject,source:Map<String,InkStroke>):PageObject {
        if(o.sourceStrokeIds.isEmpty()||o.glyphs.none{it.color==null})return o
        val strokes=o.sourceStrokeIds.mapNotNull{source[it]};if(strokes.isEmpty())return o
        if(o.textRuns.isNotEmpty())return o.copy(glyphs=o.glyphs.map{g->
            if(g.color!=null)g else {
                val r=o.textRuns.first{g.start>=it.start&&g.end<=it.end}
                val local=r.sourceIds.mapNotNull{source[it]};val b=local.map{it.bounds()}.reduceOrNull{a,v->a.union(v)}
                if(b==null)g else {val center=b.left+(g.start-r.start+.5)/(r.end-r.start)*(b.right-b.left);apply(g,local,CanvasBounds(center-1,b.top,center+1,b.bottom))}
            }
        })
        val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
        val sx=(bounds.right-bounds.left)/o.width;val sy=(bounds.bottom-bounds.top)/o.height
        return o.copy(glyphs=o.glyphs.map{g->if(g.color!=null)g else apply(g,strokes,CanvasBounds(
            bounds.left+g.x*sx,bounds.top+g.y*sy,bounds.left+(g.x+g.width)*sx,bounds.top+(g.y+g.height)*sy))})
    }
}
