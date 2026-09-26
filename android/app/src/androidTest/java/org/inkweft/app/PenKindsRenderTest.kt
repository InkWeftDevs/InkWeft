// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PenKindsRenderTest {
    private fun path(pen:InkPen,pressure:Float=.4f)=InkStroke(UUID.randomUUID().toString(),pen,
        if(pen==InkPen.HIGHLIGHTER)0x66000000 else Color.BLACK,22f,InkTool.STYLUS,
        (0..60).map{InkSample(30f+it*4,70f+25*kotlin.math.sin(it/6f),it*12L,(pressure*2*it/60f).coerceIn(0f,1f))})
    private fun pixels(stroke:InkStroke):IntArray {
        val bitmap=Bitmap.createBitmap(320,140,Bitmap.Config.ARGB_8888)
        return try{CanvasStrokeRenderer.create().draw(Canvas(bitmap),InkBrushes.stroke(stroke),Matrix())
            IntArray(320*140).also{bitmap.getPixels(it,0,320,0,0,320,140)}}finally{bitmap.recycle()}
    }
    @Test fun actualNativeBrushesDifferAndUniformPensIgnorePressure(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val ball=pixels(path(InkPen.BALLPOINT,.1f));assertArrayEquals(ball,pixels(path(InkPen.BALLPOINT,.9f)))
            val fountain=pixels(path(InkPen.PEN));val brush=pixels(path(InkPen.BRUSH));assertFalse("calligraphy must differ from fountain at the same nominal width",fountain.contentEquals(brush))
            val light=pixels(path(InkPen.BRUSH,.1f)).count{Color.alpha(it)>100}
            val heavy=pixels(path(InkPen.BRUSH,.9f)).count{Color.alpha(it)>100}
            assertTrue("calligraphy must visibly respond to pressure",heavy>light*2)
            assertFalse(ball.contentEquals(pixels(path(InkPen.MARKER))))
            val high=pixels(path(InkPen.HIGHLIGHTER));assertTrue(high.maxOf{Color.alpha(it)} in 1..150)
            assertEquals(255,pixels(path(InkPen.MARKER)).maxOf{Color.alpha(it)})
            InkPen.entries.forEach{pen->val original=path(pen);assertArrayEquals(pixels(original),pixels(InkStrokeCodec.decode(InkStrokeCodec.encode(original))))}
        }
    }
    @Test fun saveFiveBrushComparisonFromNativeRenderer(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val bitmap=Bitmap.createBitmap(700,550,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=22f}
            val renderer=CanvasStrokeRenderer.create()
            try{(PenKinds.writing+InkPen.HIGHLIGHTER).forEachIndexed{i,pen->
                canvas.drawText(PenKinds.title(pen),20f,55f+i*100,paint)
                val s=InkStroke(UUID.randomUUID().toString(),pen,if(pen==InkPen.HIGHLIGHTER)0x66d1a200 else 0xff15533f.toInt(),18f,InkTool.STYLUS,
                    (0..100).map{n->val t=n/100f;InkSample(160+500*t,50+i*100+18*kotlin.math.sin(t*12),n*8L,.08f+.92f*kotlin.math.sin(t*Math.PI).toFloat())})
                renderer.draw(canvas,InkBrushes.stroke(s),Matrix())
            };File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"five-pen-types.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        }
    }
}
