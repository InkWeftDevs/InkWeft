// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VisibleEditingTest {
    private fun id()=UUID.randomUUID().toString()
    private fun InkStroke.copy(samples:List<InkSample> = this.samples,cuts:List<InkCut> = this.cuts)=InkStroke(id,pen,color,width,tool,samples,world,cuts)
    private fun line(pen:InkPen=InkPen.BALLPOINT,p:Float=-1f,width:Float=8f)=InkStroke(id(),pen,Color.BLACK,width,InkTool.STYLUS,
        (0..80).map{InkSample(100f+it*3,200f,it*8L,p)})
    private fun region(left:Float=80f,right:Float=360f)=InkRegion(listOf(EraserPoint(left,180f),EraserPoint(right,220f)))
    @Test fun fullyErasedAndInvisibleHalfAreNotSelected(){
        val geometry=VisibleInkGeometry();val source=line()
        val full=source.copy(cuts=listOf(InkCut(id(),20f,listOf(EraserPoint(80f,200f),EraserPoint(360f,200f)))))
        assertFalse(geometry.selects(region(),full));assertNull(geometry.bounds(full))
        val half=source.copy(cuts=listOf(InkCut(id(),.01f,listOf(EraserPoint(80f,180f),EraserPoint(230f,220f)),InkCutShape.RECTANGLE)))
        assertFalse(geometry.selects(region(80f,220f),half))
        assertTrue(geometry.selects(region(229f,360f),half))
        val copied=InkSelectionEdit.copy(listOf(half),100f,0f).single()
        assertTrue(geometry.bounds(copied)!!.left>=329f)
    }
    @Test fun hiddenAndMaskedBeautifiedGlyphsAreNotSelected(){
        TextStyles.initialize(ApplicationProvider.getApplicationContext())
        val geometry=VisibleInkGeometry()
        val text=PageObject(id(),PageObjectKind.TEXT,x=100f,y=180f,width=200f,height=40f,text="HI",glyphs=listOf(TextGlyph(0,1,0f,0f,30f,40f),TextGlyph(1,2,40f,0f,20f,40f)))
        assertTrue(geometry.selects(region(),text))
        assertFalse(geometry.selects(region(),text.copy(glyphs=text.glyphs.map{it.copy(hidden=true)},sourceStrokeIds=listOf(id()))))
        val masked=text.copy(erasures=listOf(TextErasePath(0,2,60f,listOf(TextErasePoint(30f,20f)))))
        assertFalse(geometry.selects(region(),masked))
    }
    @Test fun sameColorWidthAndTrajectoryHaveDifferentPenOutlines(){
        val geometry=VisibleInkGeometry()
        fun height(pen:InkPen,pressure:Float)=geometry.bounds(line(pen,pressure))!!.let{it.bottom-it.top}
        assertEquals(height(InkPen.BALLPOINT,.1f),height(InkPen.BALLPOINT,.8f),.01)
        assertTrue(height(InkPen.BRUSH,.8f)>height(InkPen.BRUSH,.1f)*1.8)
        assertTrue(height(InkPen.PEN,.8f)>height(InkPen.PEN,.1f)*1.2)
        assertTrue(height(InkPen.BRUSH,.147f)>3.0)
        val noPressure=PenKinds.writing.map{geometry.path(line(it)).let{p->val values=FloatArray(p.approximate(.1f).size);p.approximate(.1f).copyInto(values);values.toList()}}
        assertEquals(PenKinds.writing.size,noPressure.distinct().size)
        val image=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=22f}
        PenKinds.writing.forEachIndexed{i,pen->
            canvas.drawText(PenKinds.title(pen),20f,50f+i*110f,paint)
            val wave=line(pen).copy(samples=(0..160).map{n->InkSample(180f+n*3,55f+i*110f+20f*kotlin.math.sin(n*.06f),n*8L,.02f+.78f*n/160)})
            canvas.drawPath(geometry.path(wave),paint)
        }
        java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null),"v24-pen-comparison.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
