// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.inkweft.core.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import kotlin.math.roundToInt

/** Native attached canvas only: no notebook, repository writes, or preference edits. */
class ZoomRasterTest {
    @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
    private lateinit var view:InkCanvasView
    private fun line(x1:Float,x2:Float,y:Float)=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,
        Color.BLACK,12f,InkTool.STYLUS,listOf(InkSample(x1,y,0,.8f),InkSample(x2,y,40,.8f)),
        appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
    private val anchor=line(470f,530f,707f)
    private val edge=line(370f,390f,575f)

    private fun open(zoom:Double=1.0,embedded:Boolean=false){
        rule.scenario.onActivity{a->
            view=InkCanvasView(a).apply{embeddedPage=embedded}
            val height=if(embedded)424 else 300
            a.setContentView(FrameLayout(a).apply{addView(view,FrameLayout.LayoutParams(300,height))})
            view.configure(false,PaperStyle.BLANK,CanvasViewport(500.0,707.0,zoom/a.resources.displayMetrics.density))
            view.layout(0,0,300,height);view.showStrokes(listOf(anchor,edge))
            assertTrue("The fixture must exercise the asynchronous attached-view path",view.isAttachedToWindow)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{view.draw(Canvas(bitmap))}finally{bitmap.recycle()}
        }
        awaitFrame()
        rule.scenario.onActivity{assertInk("The initial anchor must be visible")}
    }
    private fun awaitFrame(){
        val deadline=System.nanoTime()+10_000_000_000L;var pending=true
        while(pending&&System.nanoTime()<deadline){
            rule.scenario.onActivity{pending=view.rasterPending}
            if(pending)Thread.sleep(10)
        }
        assertFalse("The replacement raster did not finish",pending)
    }
    private fun visible()=view.snapshotViewport().visible(view.width.toDouble(),view.height.toDouble(),view.resources.displayMetrics.density.toDouble())
    private fun assertInk(message:String,x:Double=500.0,y:Double=707.0,pending:Boolean=false){
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        try{
            view.draw(Canvas(bitmap))
            if(pending)assertTrue("This assertion must precede replacement-frame publication",view.rasterPending)
            val point=view.snapshotViewport().worldToScreen(x,y,view.width.toDouble(),view.height.toDouble(),view.resources.displayMetrics.density.toDouble())
            val cx=point.x.roundToInt();val cy=point.y.roundToInt();var dark=0
            for(py in (cy-8).coerceAtLeast(0)..(cy+8).coerceAtMost(bitmap.height-1))
                for(px in (cx-8).coerceAtLeast(0)..(cx+8).coerceAtMost(bitmap.width-1)){
                    val color=bitmap.getPixel(px,py)
                    if(Color.red(color)<235&&Color.green(color)<235&&Color.blue(color)<235)dark++
                }
            assertTrue(message,dark>0)
            assertEquals("Zoom changes view state, not the displayed author list",2,view.displayedStrokeCount)
        }finally{bitmap.recycle()}
    }
    @After fun release(){rule.scenario.onActivity{it.setContentView(FrameLayout(it));AsyncInkRaster.clearMemoryCache()}}

    @Test fun zoomInKeepsVisibleAnchorWhenEdgeStrokeLeaves(){
        open()
        // The worker cannot publish on Main during this callback, making the first-frame check deterministic.
        rule.scenario.onActivity{
            assertTrue(visible().intersects(edge.bounds()))
            view.zoomBy(2.0)
            assertFalse(visible().intersects(edge.bounds()))
            assertInk("Zooming in cleared ink that remains visible",pending=true)
        }
        awaitFrame();rule.scenario.onActivity{assertInk("Zoom-in replacement lost the anchor")}
    }
    @Test fun zoomOutKeepsVisibleAnchorWhenEdgeStrokeEnters(){
        open(zoom=2.0)
        rule.scenario.onActivity{
            assertFalse(visible().intersects(edge.bounds()))
            view.zoomBy(.5)
            assertTrue(visible().intersects(edge.bounds()))
            assertInk("Zooming out cleared ink that was already visible",pending=true)
        }
        awaitFrame();rule.scenario.onActivity{
            assertInk("Zoom-out replacement lost the anchor")
            assertInk("The newly visible edge stroke was not rendered",380.0,575.0)
        }
    }
    @Test fun embeddedResizeKeepsVisibleAnchorBeforeReplacementFrame(){
        open(embedded=true)
        rule.scenario.onActivity{
            assertTrue(view.embeddedPage);assertEquals(300,view.width)
            view.layoutParams=FrameLayout.LayoutParams(450,636)
            view.layout(0,0,450,636)
            assertEquals(450,view.width)
            assertInk("Resizing the same continuous-page canvas cleared its ink",pending=true)
        }
        awaitFrame();rule.scenario.onActivity{assertInk("Embedded-page replacement lost the anchor")}
    }
}
