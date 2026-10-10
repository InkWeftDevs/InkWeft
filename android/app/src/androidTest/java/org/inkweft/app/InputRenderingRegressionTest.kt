// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic consumer tests; these do not certify any physical pen's MotionRanges. */
class InputRenderingRegressionTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun coords(x:Float,y:Float)=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=.5f;setAxisValue(MotionEvent.AXIS_TILT,.45f)}
    private fun event(action:Int,time:Long,x:Float,y:Float)=MotionEvent.obtain(1000,time,action,1,
        arrayOf(MotionEvent.PointerProperties().apply{id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}),
        arrayOf(coords(x,y)),0,0,1f,1f,-1,0,InputDevice.SOURCE_STYLUS,0)

    @Test fun penUpConsumesItsEntireHistoryBeforeCommitWithoutInventingHardwareAxes(){
        ins.runOnMainSync{
            val view=InkCanvasView(ins.targetContext).apply{configure(false,PaperStyle.BLANK,null);layout(0,0,600,848);allowInput=true}
            var saved:InkStroke?=null;var axes:Pair<Boolean,Boolean>?=null
            view.onStroke={saved=it};view.onAxes={pressure,tilt->axes=pressure to tilt}
            val down=event(MotionEvent.ACTION_DOWN,1000,60f,60f)
            val up=event(MotionEvent.ACTION_MOVE,1020,80f,65f)
            try{
                up.addBatch(1040,arrayOf(coords(100f,80f)),0);up.addBatch(1060,arrayOf(coords(130f,110f)),0)
                assertEquals(2,up.historySize);up.action=MotionEvent.ACTION_UP
                assertTrue(view.onTouchEvent(down));assertTrue(view.onTouchEvent(up))
                val result=checkNotNull(saved);assertEquals(listOf(0L,20L,40L,60L),result.samples.map{it.elapsedMs})
                assertEquals(false to false,axes)
                assertTrue(result.samples.all{it.pressure==-1f&&it.tilt==-1f})
                assertTrue(result.samples.zipWithNext().all{(a,b)->a.x<b.x})
            }finally{down.recycle();up.recycle();view.cancelGesture();saved?.let{PencilRenderer.forget(setOf(it.id))}}
        }
    }

    @Test fun canceledTerminalHistoryCannotBecomeAnAuthorStroke(){
        ins.runOnMainSync{
            val view=InkCanvasView(ins.targetContext).apply{configure(false,PaperStyle.BLANK,null);layout(0,0,600,848);allowInput=true}
            var committed=0;view.onStroke={committed++}
            val down=event(MotionEvent.ACTION_DOWN,1000,60f,60f);val cancel=event(MotionEvent.ACTION_MOVE,1020,80f,80f)
            try{
                cancel.addBatch(1040,arrayOf(coords(100f,100f)),0);cancel.action=MotionEvent.ACTION_CANCEL
                view.onTouchEvent(down);view.onTouchEvent(cancel);assertEquals(0,committed)
            }finally{down.recycle();cancel.recycle();view.cancelGesture()}
        }
    }

    @Test fun stationaryPressureChangesReopenThroughNativeInkWithoutLosingAuthorReadings(){
        val points=listOf(InkSample(20f,30f,0,.2f),InkSample(20f,30f,0,.9f),InkSample(100f,60f,20,0f))
        val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,4f,InkTool.STYLUS,points)
        val bytes=InkStrokeCodec.encode(stroke)
        assertTrue(InkBrushes.stroke(stroke).shape.getRenderGroupCount()>0)
        assertEquals(points,InkStrokeCodec.decode(bytes).samples)
        assertArrayEquals(bytes,InkStrokeCodec.encode(stroke))
    }

    @Test fun sameIdInteriorPencilChangesMatchFreshRasterPixels(){
        val cached=PencilTileRenderer();val fresh=PencilTileRenderer()
        val actual=Bitmap.createBitmap(180,140,Bitmap.Config.ARGB_8888);val expected=Bitmap.createBitmap(180,140,Bitmap.Config.ARGB_8888)
        val id=UUID.randomUUID().toString();val appearance=StrokeAppearance(BrushRecipe(),123,0f,0f)
        val points=listOf(InkSample(20f,50f,0,.2f,0f),InkSample(80f,50f,10,.2f,0f),InkSample(140f,50f,20,.2f,0f))
        fun stroke(middle:InkSample)=InkStroke(id,InkPen.PENCIL,Color.BLACK,12f,InkTool.STYLUS,listOf(points[0],middle,points[2]),appearance=appearance)
        fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}
        try{
            cached.draw(Canvas(actual),stroke(points[1]));val baseline=pixels(actual)
            for(middle in listOf(points[1].copy(y=70f),points[1].copy(pressure=.9f),points[1].copy(tilt=1f))){
                actual.eraseColor(Color.TRANSPARENT);expected.eraseColor(Color.TRANSPARENT);fresh.clear()
                val next=stroke(middle);cached.draw(Canvas(actual),next);fresh.draw(Canvas(expected),next)
                assertFalse(baseline.contentEquals(pixels(expected)))
                assertArrayEquals(pixels(expected),pixels(actual))
            }
        }finally{cached.clear();fresh.clear();actual.recycle();expected.recycle()}
    }

    @Test fun stationaryPencilUpdateUsesTheLastReadingRatherThanAccumulatingOldPressure(){
        val cached=PencilTileRenderer();val fresh=PencilTileRenderer()
        val actual=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);val expected=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        val id=UUID.randomUUID().toString();val appearance=StrokeAppearance(BrushRecipe(),123,0f,0f)
        val high=InkSample(50f,50f,0,.9f);val low=high.copy(pressure=.2f)
        fun stroke(samples:List<InkSample>)=InkStroke(id,InkPen.PENCIL,Color.BLACK,12f,InkTool.STYLUS,samples,appearance=appearance)
        fun pixels(bitmap:Bitmap)=IntArray(100*100).also{bitmap.getPixels(it,0,100,0,0,100,100)}
        try{
            cached.draw(Canvas(actual),stroke(listOf(high)));val initial=pixels(actual)
            actual.eraseColor(Color.TRANSPARENT);cached.draw(Canvas(actual),stroke(listOf(high,low)))
            fresh.draw(Canvas(expected),stroke(listOf(low)))
            assertFalse(initial.contentEquals(pixels(expected)))
            assertArrayEquals(pixels(expected),pixels(actual))
        }finally{cached.clear();fresh.clear();actual.recycle();expected.recycle()}
    }
}
