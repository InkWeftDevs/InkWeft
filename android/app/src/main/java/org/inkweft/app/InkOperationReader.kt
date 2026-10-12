// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.inkweft.core.*
import org.inkweft.data.*

internal data class InkOperationRead(val header:AuthoringPageExport,val ids:List<String>,val strokes:List<InkStroke>)

/** Display coverage is independent of an edit. Scan candidates in bounded batches and
 * retain only matching identities (eraser) or a bounded selection (transform preview). */
internal object InkOperationReader {
    const val MAX_SELECTION_POINTS=131_072
    data class ExcerptTextInk(val strokes:List<InkStroke>,val overBudget:Boolean)
    private class ExcerptBudget:RuntimeException(null,null,false,false)
    /** Image capture has no transform-selection limit. Only optional OCR retains geometry. */
    suspend fun excerptText(repository:InkRepository,page:String,revision:Long,configuration:Long,region:InkRegion):ExcerptTextInk {
        val authoring=repository.authoringRepository;val header=authoring.exportHeader(page)
        check(header.authoring.inkRevision==revision&&header.authoring.revision==configuration){"EXPORT_REVISION_CHANGED"}
        val suppressed=header.objects.flatMap{it.sourceStrokeIds}.toHashSet()
        val selected=ArrayList<InkStroke>();var points=0L;var overBudget=false;val geometry=VisibleInkGeometry()
        try{
            repository.visitVisible(page,revision,region.bounds,configuration){batch->
                for(stroke in batch){
                    currentCoroutineContext().ensureActive()
                    if(stroke.id in suppressed||stroke.pen==InkPen.HIGHLIGHTER||!geometry.selects(region,stroke,true))continue
                    points+=stroke.samples.size+stroke.cuts.sumOf{it.points.size.toLong()}
                    if(selected.size>=256||points>65_536)throw ExcerptBudget()
                    selected.add(stroke)
                }
                geometry.clear()
            }
        }catch(_:ExcerptBudget){selected.clear();overBudget=true}finally{geometry.clear()}
        authoring.checkExportHeads(page,header.authoring)
        return ExcerptTextInk(selected,overBudget)
    }
    suspend fun read(repository:InkRepository,page:String,revision:Long,bounds:CanvasBounds?,writableOnly:Boolean,
        geometry:Boolean,lastOnly:Boolean=false,readInk:Boolean=true,match:(InkStroke,VisibleInkGeometry)->Boolean):InkOperationRead {
        val authoring=repository.authoringRepository
        val header=authoring.exportHeader(page)
        check(header.authoring.inkRevision==revision){"INK_WINDOW_REVISION_CHANGED"}
        val suppressed=header.objects.flatMap{it.sourceStrokeIds}.toHashSet()
        val ids=ArrayList<String>();val selected=ArrayList<InkStroke>();var points=0L
        val painted=VisibleInkGeometry()
        try{
            if(readInk)repository.visitVisible(page,revision,bounds,header.authoring.revision,writableOnly=writableOnly){batch->
                for(stroke in batch){
                    currentCoroutineContext().ensureActive()
                    if(stroke.id in suppressed||!match(stroke,painted))continue
                    if(lastOnly){ids.clear();selected.clear();points=0}
                    require(ids.size<if(geometry)InkSelectionEdit.MAX_SELECTED else InkLimits.MAX_MUTATION_STROKES){"INK_OPERATION_BUDGET"}
                    ids.add(stroke.id)
                    if(geometry){
                        points+=stroke.samples.size+stroke.cuts.sumOf{it.points.size.toLong()}
                        require(points<=MAX_SELECTION_POINTS){"INK_OPERATION_BUDGET"}
                        selected.add(stroke)
                    }
                }
                painted.clear()
            }
            authoring.checkExportHeads(page,header.authoring)
            return InkOperationRead(header,ids,selected)
        }finally{painted.clear()}
    }
}
