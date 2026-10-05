// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import org.inkweft.core.*

/** Local author samples plus one target transform; never pre-transforms the saved samples. */
internal class AnnotationPainter {
    private val renderer=InkBrushes.renderer()
    private val meshes=object:LinkedHashMap<String,Pair<InkStroke,androidx.ink.strokes.Stroke>>(32,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Pair<InkStroke,androidx.ink.strokes.Stroke>>?)=size>128}
    private fun mesh(stroke:InkStroke):androidx.ink.strokes.Stroke {
        val old=meshes[stroke.id]
        if(old!=null&&(old.first===stroke||old.first.samples==stroke.samples&&old.first.pen==stroke.pen&&old.first.color==stroke.color&&old.first.width==stroke.width&&old.first.appearance==stroke.appearance))return old.second
        return InkBrushes.stroke(stroke).also{meshes[stroke.id]=stroke to it}
    }
    fun draw(canvas:Canvas,annotations:List<BoundAnnotation>,objects:List<PageObject>,viewport:Matrix,occurrences:Map<String,CanvasBounds> = emptyMap(),regions:List<AnnotationRegion> = emptyList()) {
        for(annotation in annotations){
            val frame=when(annotation.target.kind){
                AnnotationTargetKind.PAGE->AnnotationFrame(0.0,0.0)
                AnnotationTargetKind.PAGE_OBJECT->objects.firstOrNull{it.id==annotation.target.id}?.let{annotation.targetFrame(it.bounds())}
                AnnotationTargetKind.MAP_OCCURRENCE->occurrences[annotation.target.id]?.let(annotation::targetFrame)
                AnnotationTargetKind.WHITESPACE->null // Rendered only inside the expanded whitespace, with its local crop.
            }?:continue
            val region=regions.firstOrNull{it.target==annotation.target}
            if(region?.collapsed==true)continue
            val saved=canvas.save()
            if(region!=null){val target=targetBounds(region.target,objects,occurrences);if(target==null){canvas.restoreToCount(saved);continue};val crop=region.bounds(target);canvas.clipRect(crop.left.toFloat(),crop.top.toFloat(),crop.right.toFloat(),crop.bottom.toFloat())}
            draw(canvas,annotation.stroke,annotation.displayFrame(frame),viewport);canvas.restoreToCount(saved)
        }
    }
    private fun targetBounds(target:org.inkweft.core.AnnotationTarget,objects:List<PageObject>,occurrences:Map<String,CanvasBounds>)=when(target.kind){
        AnnotationTargetKind.PAGE_OBJECT->objects.firstOrNull{it.id==target.id}?.bounds()
        AnnotationTargetKind.MAP_OCCURRENCE->occurrences[target.id]
        else->null
    }
    fun regions(canvas:Canvas,regions:List<AnnotationRegion>,objects:List<PageObject>,occurrences:Map<String,CanvasBounds> = emptyMap()){
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=0xff607a72.toInt();strokeWidth=1f;textSize=16f}
        for(region in regions){val target=targetBounds(region.target,objects,occurrences)?:continue;val box=region.bounds(target)
            paint.style=android.graphics.Paint.Style.STROKE;canvas.drawRect(box.left.toFloat(),box.top.toFloat(),box.right.toFloat(),box.bottom.toFloat(),paint)
            if(region.collapsed){paint.style=android.graphics.Paint.Style.FILL;canvas.drawText("批注区已折叠",(box.left+6).toFloat(),(box.top+20*region.frame(target).scale).toFloat(),paint)}
        }
    }
    fun draw(canvas:Canvas,stroke:InkStroke,frame:AnnotationFrame,viewport:Matrix){
        val save=canvas.save();canvas.translate(frame.x.toFloat(),frame.y.toFloat());canvas.scale(frame.scale.toFloat(),frame.scale.toFloat())
        stroke.cuts.forEach{canvas.clipOutPath(VisibleInkGeometry.cutPath(it))}
        if(stroke.pen==InkPen.PENCIL)PencilRenderer.draw(canvas,stroke)else renderer.draw(canvas,mesh(stroke),viewport)
        canvas.restoreToCount(save)
    }
}
