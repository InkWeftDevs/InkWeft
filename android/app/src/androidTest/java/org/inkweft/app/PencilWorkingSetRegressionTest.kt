// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PencilWorkingSetRegressionTest {
    private fun stroke(points:List<InkSample>)=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,2f,InkTool.STYLUS,
        points,appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))

    @Test fun moreThan128ShortPencilsRedrawWithoutRebuildingTilesAndKeepIdenticalPixels(){
        val renderer=PencilTileRenderer();val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val strokes=List(160){i->val x=40f+i%16*55f;val y=60f+i/16*70f
            stroke(listOf(InkSample(x,y,0,.4f),InkSample(x+12,y+6,20,.6f)))}
        fun pixels()=IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}
        try{
            strokes.forEach{renderer.draw(canvas,it)};val expected=pixels();val builds=renderer.tileBuilds
            bitmap.eraseColor(Color.TRANSPARENT);strokes.forEach{renderer.draw(canvas,it)}
            assertEquals("An unchanged 160-stroke page must retain its graphite working set",builds,renderer.tileBuilds)
            assertArrayEquals(expected,pixels());assertEquals(160,renderer.retainedSources)
        }finally{renderer.clear();bitmap.recycle()}
    }

    @Test fun longStrokeSourcesStayWithinSampleBudgetAndEvictedInkCanRebuild(){
        val renderer=PencilTileRenderer();val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val points=List(InkLimits.MAX_POINTS){i->InkSample(40f+i%8*.1f,40f+i%4*.1f,i.toLong(),.5f)}
        val strokes=List(9){stroke(points)}
        try{
            strokes.forEach{renderer.draw(canvas,it);assertTrue(renderer.retainedSourceSamples<=65_536)}
            assertEquals(8,renderer.retainedSources);assertEquals(65_536,renderer.retainedSourceSamples)
            val before=renderer.tileBuilds;renderer.draw(canvas,strokes.first())
            assertTrue("Evicted derived tiles must rebuild from original samples",renderer.tileBuilds>before)
            assertTrue(renderer.retainedSourceSamples<=65_536)
            renderer.clear();assertEquals(0,renderer.retainedSources);assertEquals(0,renderer.retainedSourceSamples)
        }finally{renderer.clear();bitmap.recycle()}
    }
}
