// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.inkweft.core.InkStroke

/** Optional decoded geometry, never an authority for visibility, ordering or revision.
 * One raster worker owns a cache; its renderer reserves MAX_BYTES in the render budget. */
class InkRasterCache internal constructor(internal val repository:InkRepository) {
    companion object { const val MAX_BYTES=1024L*1024; private const val MAX_STROKES=512 }
    private data class Entry(val spatialKey:Long,val stroke:InkStroke,val bytes:Long)
    private val entries=LinkedHashMap<String,Entry>(128,.75f,true)
    private var page:String?=null;private var revision=-1L;private var epoch=-1L
    var retainedBytes=0L;private set
    var hits=0;private set
    internal fun prepare(page:String,revision:Long,epoch:Long,allowRevisionAdvance:Boolean=false){
        if(this.page==page&&this.epoch==epoch&&(this.revision==revision||allowRevisionAdvance&&this.revision<revision)){
            // Unchanged layers can skip several append revisions without visiting geometry.
            // Each hit still requires the current spatial identity; any mask change clears all.
            this.revision=revision;return
        }
        entries.clear();retainedBytes=0;this.page=page;this.revision=revision;this.epoch=epoch
    }
    internal fun get(id:String,spatialKey:Long):InkStroke? = entries[id]?.takeIf{it.spatialKey==spatialKey}?.stroke
    internal fun recordHits(count:Int){hits+=count}
    internal fun put(stroke:InkStroke,spatialKey:Long){
        // Count masks per stroke even when shared. These conservative logical weights
        // also bound object/list overhead and large one-stroke erase histories.
        val bytes=512L+stroke.samples.size*64L+stroke.cuts.sumOf{256L+it.points.size*48L}
        entries.remove(stroke.id)?.let{retainedBytes-=it.bytes}
        if(bytes>MAX_BYTES)return
        val oldest=entries.entries.iterator()
        while(entries.size>=MAX_STROKES||retainedBytes+bytes>MAX_BYTES){val entry=oldest.next();retainedBytes-=entry.value.bytes;oldest.remove()}
        entries[stroke.id]=Entry(spatialKey,stroke,bytes);retainedBytes+=bytes
    }
}
