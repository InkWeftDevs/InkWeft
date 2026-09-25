// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.roundToInt

/** Actual renderer tests to run on Android; defining them is not device evidence. */
class LocalEraserRenderTest {
    @Test fun clippingRemovesOnlyCenterNotWhitePaintOrEntireStroke(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val context=InstrumentationRegistry.getInstrumentation().targetContext;val density=context.resources.displayMetrics.density
            val v=InkCanvasView(context);v.configure(false,PaperStyle.GRID,CanvasViewport(100.0,120.0,1.0));v.layout(0,0,600,400)
            val s=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,12f,InkTool.TOUCH,listOf(InkSample(50f,120f,0),InkSample(150f,120f,100)))
            val cut=InkCut(UUID.randomUUID().toString(),14f,listOf(EraserPoint(100f,120f)))
            val paper=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);val before=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);val after=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
            try{
                v.showStrokes(emptyList());v.draw(Canvas(paper));check(paper.getPixel(300,200)!=Color.WHITE)
                v.showStrokes(listOf(s));v.draw(Canvas(before));assertTrue(Color.red(before.getPixel(300,200))<180)
                v.showStrokes(listOf(s.withCuts(listOf(cut))));v.draw(Canvas(after))
                assertEquals("grid line beneath erased ink must remain, not a white overpaint",paper.getPixel(300,200),after.getPixel(300,200))
                assertTrue(Color.red(after.getPixel((300-30*density).roundToInt(),200))<180)
                assertTrue(Color.red(after.getPixel((300+30*density).roundToInt(),200))<180)
                // No author point was moved by display clipping.
                assertEquals(50f,s.samples.first().x,0f)
            }finally{paper.recycle();before.recycle();after.recycle()}
        }
    }
    @Test fun hoveringCursorUsesVisibleScreenDiameterAcrossZoom(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val v=InkCanvasView(context);v.configure(true,PaperStyle.BLANK,CanvasViewport(0.0,0.0,1.0));v.layout(0,0,600,400)
            v.eraseMode=true;v.eraserDiameterDp=28f
            val radius=14*context.resources.displayMetrics.density
            val time=SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,MotionEvent.ACTION_HOVER_MOVE,300f,200f,0)
            v.onHoverEvent(e);e.recycle()
            fun darkBounds():IntRange {
                val image=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
                try{v.draw(Canvas(image));val xs=(0 until 600).filter{Color.red(image.getPixel(it,200))<190};assertTrue(xs.isNotEmpty());return xs.first()..xs.last()}finally{image.recycle()}
            }
            val first=darkBounds();v.zoomBy(2.0)
            val e2=MotionEvent.obtain(time,time+10,MotionEvent.ACTION_HOVER_MOVE,300f,200f,0);v.onHoverEvent(e2);e2.recycle()
            val second=darkBounds();assertEquals(first,second)
            assertTrue(kotlin.math.abs((first.last-first.first)-2*radius)<5)
        }
    }
}
