// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** Stable occurrence IDs, never card IDs. Shared card notes remain CardPresentation.annotation. */
enum class AnnotationTargetKind { PAGE, WHITESPACE, PAGE_OBJECT, MAP_OCCURRENCE }
data class AnnotationTarget(val kind:AnnotationTargetKind,val id:String) { init { UUID.fromString(id) } }
data class AnnotationFrame(val x:Double,val y:Double,val scale:Double=1.0) {
    init { require(listOf(x,y,scale).all{it.isFinite()});require(kotlin.math.abs(x)<=1e12&&kotlin.math.abs(y)<=1e12&&scale in .000001..1_000_000.0) }
    fun compose(local:AnnotationFrame)=AnnotationFrame(x+local.x*scale,y+local.y*scale,scale*local.scale)
    fun inverse()=AnnotationFrame(-x/scale,-y/scale,1/scale)
    fun project(local:CanvasPoint)=CanvasPoint(x+local.x*scale,y+local.y*scale)
    fun local(point:CanvasPoint)=CanvasPoint((point.x-x)/scale,(point.y-y)/scale)
}
data class BoundAnnotation(val stroke:InkStroke,val target:AnnotationTarget,val referenceWidth:Double=1.0,val localFrame:AnnotationFrame=AnnotationFrame(0.0,0.0)) {
    init { require(stroke.world){"ANNOTATION_REQUIRES_LOCAL_COORDINATES"};require(referenceWidth.isFinite()&&referenceWidth in 1.0..4000.0) }
    fun targetFrame(bounds:CanvasBounds)=AnnotationFrame(bounds.right+24.0,bounds.top,(bounds.right-bounds.left)/referenceWidth)
    /** Only the rendering projection changes as a target moves/resizes. */
    fun displayBounds(frame:AnnotationFrame):CanvasBounds {
        val effective=displayFrame(frame);val bounds=stroke.bounds()
        val a=effective.project(CanvasPoint(bounds.left,bounds.top));val b=effective.project(CanvasPoint(bounds.right,bounds.bottom))
        return CanvasBounds(a.x,a.y,b.x,b.y)
    }
    fun displayFrame(frame:AnnotationFrame)=frame.compose(localFrame)
    fun projected(frame:AnnotationFrame):InkStroke=transform(stroke,displayFrame(frame))
    fun rebound(previousFrame:AnnotationFrame,nextTarget:AnnotationTarget,nextFrame:AnnotationFrame,nextReferenceWidth:Double):BoundAnnotation {
        return copy(target=nextTarget,referenceWidth=nextReferenceWidth,localFrame=nextFrame.inverse().compose(displayFrame(previousFrame)))
    }
    /** Freeze current canvas position once, then the page identity has an identity frame. */
    fun detached(frame:AnnotationFrame,pageId:String)=copy(target=AnnotationTarget(AnnotationTargetKind.PAGE,pageId),referenceWidth=1.0,localFrame=displayFrame(frame))
    companion object {
        private fun transform(stroke:InkStroke,frame:AnnotationFrame):InkStroke {
            fun p(x:Float,y:Float)=frame.project(CanvasPoint(x.toDouble(),y.toDouble()))
            fun sample(s:InkSample)=p(s.x,s.y).let{s.copy(x=it.x.toFloat(),y=it.y.toFloat(),world=true)}
            val origin=p(stroke.appearance.originX,stroke.appearance.originY)
            return InkStroke(stroke.id,stroke.pen,stroke.color,(stroke.width*frame.scale).toFloat(),stroke.tool,stroke.samples.map(::sample),true,
                stroke.cuts.map{cut->InkCut(cut.id,(cut.radius*frame.scale).toFloat(),cut.points.map{p(it.x,it.y).let{v->EraserPoint(v.x.toFloat(),v.y.toFloat())}},cut.shape)},
                stroke.appearance.copy(originX=origin.x.toFloat(),originY=origin.y.toFloat(),leading=stroke.appearance.leading?.let(::sample),trailing=stroke.appearance.trailing?.let(::sample)))
        }
    }
}

/** Crop and collapse belong to one stable appearance; neither rewrites nor deletes overflow ink. */
data class AnnotationRegion(val target:AnnotationTarget,val width:Double=300.0,val height:Double=240.0,val collapsed:Boolean=false,val referenceWidth:Double=280.0) {
    init {
        require(target.kind in listOf(AnnotationTargetKind.PAGE_OBJECT,AnnotationTargetKind.MAP_OCCURRENCE))
        require(width.isFinite()&&height.isFinite()&&width in 80.0..2000.0&&height in 80.0..2000.0)
        require(referenceWidth.isFinite()&&referenceWidth in 1.0..4000.0)
    }
    fun frame(targetBounds:CanvasBounds)=AnnotationFrame(targetBounds.right+24.0,targetBounds.top,(targetBounds.right-targetBounds.left)/referenceWidth)
    fun bounds(targetBounds:CanvasBounds):CanvasBounds {val frame=frame(targetBounds);val a=frame.project(CanvasPoint(0.0,0.0));val b=frame.project(CanvasPoint(width,if(collapsed)28.0 else height));return CanvasBounds(a.x,a.y,b.x,b.y)}
    companion object { const val MAX_REGIONS=512 }
}
