package org.inkweft.app

import android.graphics.*
import org.inkweft.core.*
import kotlin.math.*

/** Disposable viewport ink, separate from document data and the live stroke.
 * Append-only writing paints only the new strokes. Edits/zoom rebuild once.
 * At most 4M pixels (16 MiB) per attached page; detached pages release it. */
internal class InkPageRaster {
    private var bitmap:Bitmap?=null
    private var key:Any?=null
    private var painted=emptyList<InkStroke>()
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private fun sameInk(a:InkStroke,b:InkStroke)=a===b || (a.id==b.id&&a.pen==b.pen&&a.color==b.color&&a.width==b.width&&a.world==b.world&&a.appearance==b.appearance&&a.cuts.size==b.cuts.size&&a.cuts.indices.all{i->sameCut(a.cuts[i],b.cuts[i])}&&a.samples==b.samples)
    private fun sameCut(a:InkCut,b:InkCut)=a===b||(a.id==b.id&&a.radius==b.radius&&a.shape==b.shape&&a.points==b.points)
    fun clear(){bitmap?.recycle();bitmap=null;painted=emptyList();key=null}
    fun draw(target:Canvas,width:Int,height:Int,identity:Any,strokes:List<InkStroke>,render:(Canvas,InkStroke)->Unit){
        if(width<=0||height<=0)return
        val scale=min(1.0,sqrt(4_000_000.0/(width.toDouble()*height))).toFloat()
        val w=max(1,(width*scale).roundToInt());val h=max(1,(height*scale).roundToInt())
        if(bitmap?.width!=w||bitmap?.height!=h){clear();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)}
        val b=checkNotNull(bitmap)
        if(key!=identity||painted.size>strokes.size||painted.indices.any{!sameInk(painted[it],strokes[it])}){
            b.eraseColor(Color.TRANSPARENT);painted=emptyList();key=identity
        }
        if(painted.size<strokes.size){
            val canvas=Canvas(b);canvas.scale(w.toFloat()/width,h.toFloat()/height)
            strokes.drop(painted.size).forEach{render(canvas,it)}
        }
        painted=strokes
        target.drawBitmap(b,null,Rect(0,0,width,height),paint)
    }
}
