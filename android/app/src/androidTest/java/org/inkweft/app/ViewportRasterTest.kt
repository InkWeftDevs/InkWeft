// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Owned synthetic ink only. These checks require an Android runtime. */
class ViewportRasterTest {
    @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private var raster:AsyncInkRaster?=null
    private fun stroke(x:Float=500f,y:Float=707f,pen:InkPen=InkPen.PENCIL)=InkStroke(
        UUID.randomUUID().toString(),pen,Color.BLACK,3f,InkTool.STYLUS,
        listOf(InkSample(x-20,y,0,.8f),InkSample(x+20,y,40,.8f)),
        appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
    private fun field(value:Any,name:String):Any?=value.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(value)
    private fun frame(value:AsyncInkRaster)=field(value,"frame")?.let{field(it,"bitmap") as Bitmap}
    private fun awaitRaster(value:AsyncInkRaster){
        val deadline=System.nanoTime()+15_000_000_000L
        var pending=true
        while(pending&&System.nanoTime()<deadline){instrumentation.runOnMainSync{pending=value.pending};if(pending)Thread.sleep(10)}
        assertFalse("The full-resolution replacement must finish",pending)
    }
    @After fun release(){rule.scenario.onActivity{raster?.clear();it.setContentView(FrameLayout(it));AsyncInkRaster.clearMemoryCache()}}

    @Test fun finalFrameKeepsNativePixelsAboveTheOldWholePageBudget(){
        val target=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try {
            val ink=listOf(stroke(pen=InkPen.PEN))
            instrumentation.runOnMainSync{
                raster=AsyncInkRaster({})
                raster!!.draw(Canvas(target),2100,2100,CanvasViewport(),1.0,false,false,ink)
            }
            awaitRaster(raster!!)
            instrumentation.runOnMainSync{
                val bitmap=checkNotNull(frame(raster!!))
                assertEquals(2100,bitmap.width);assertEquals(2100,bitmap.height)
            }
        }finally{target.recycle()}
    }

    @Test fun zoomNeverPublishesACoarsePencilFrameOverRetainedInk(){
        val target=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val widths=mutableListOf<Int>()
        try {
            val ink=listOf(stroke());val viewport=CanvasViewport(500.0,707.0,1.0)
            instrumentation.runOnMainSync{
                raster=AsyncInkRaster({frame(raster!!)?.let{widths+=it.width}})
                raster!!.draw(Canvas(target),1000,1000,viewport,1.0,false,false,ink)
            }
            awaitRaster(raster!!)
            instrumentation.runOnMainSync{
                assertEquals(1000,checkNotNull(frame(raster!!)).width)
                widths.clear()
                raster!!.draw(Canvas(target),1000,1000,viewport.copy(zoom=2.0),1.0,false,false,ink)
                assertNotNull("A stable old frame must remain available",field(raster!!,"fallback"))
            }
            awaitRaster(raster!!)
            instrumentation.runOnMainSync{
                assertTrue("Expected at least one replacement publication",widths.isNotEmpty())
                assertTrue("A 220k preview downgraded the retained frame: $widths",widths.all{it==1000})
            }
        }finally{target.recycle()}
    }

    @Test fun enlargedEmbeddedPageRendersOnlyItsWindowAndTracksParentScroll(){
        lateinit var view:InkCanvasView;lateinit var window:FrameLayout;lateinit var pageViewport:CanvasViewport
        val target=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
        fun visible():Rect=Rect().also{assertTrue(view.getLocalVisibleRect(it))}
        fun drawWindow(){
            val area=visible();target.eraseColor(Color.WHITE)
            val canvas=Canvas(target);canvas.translate(-area.left.toFloat(),-area.top.toFloat());view.draw(canvas)
        }
        fun assertCenterInk(){
            drawWindow();var dark=0
            for(y in 140..160)for(x in 140..160)if(Color.red(target.getPixel(x,y))<235)dark++
            assertTrue("The visible world anchor must survive cropping",dark>0)
        }
        try {
            rule.scenario.onActivity{activity->
                view=InkCanvasView(activity).apply{embeddedPage=true}
                window=FrameLayout(activity).apply{clipChildren=true;addView(view,FrameLayout.LayoutParams(3000,4242))}
                activity.setContentView(FrameLayout(activity).apply{addView(window,FrameLayout.LayoutParams(300,300))})
                window.layout(0,0,300,300);view.layout(0,0,3000,4242)
                view.configure(false,PaperStyle.BLANK,null);view.showStrokes(listOf(stroke(),stroke(700f,900f)))
                pageViewport=view.snapshotViewport()
                window.scrollTo(1350,1971);drawWindow()
                raster=field(view,"asyncRaster") as AsyncInkRaster
            }
            awaitRaster(raster!!)
            rule.scenario.onActivity{
                val area=visible();val bitmap=checkNotNull(frame(raster!!))
                assertEquals(300,area.width());assertEquals(300,area.height())
                assertEquals(area.width(),bitmap.width);assertEquals(area.height(),bitmap.height)
                assertCenterInk()
                assertThrows(IllegalStateException::class.java){view.excerptPreview(CanvasBounds(50.0,50.0,70.0,70.0))}
                window.scrollTo(1950,2550)
                view.viewTreeObserver.dispatchOnPreDraw();drawWindow()
                assertTrue("Parent-only scrolling must request a new region",raster!!.pending)
                assertEquals(2,view.displayedStrokeCount)
                assertEquals("Cropping must not change page/input coordinates",pageViewport,view.snapshotViewport())
            }
            awaitRaster(raster!!)
            rule.scenario.onActivity{assertCenterInk();assertEquals(300,checkNotNull(frame(raster!!)).width)}
        }finally{target.recycle()}
    }

    @Test fun graphiteCanSampleBelowHalfAnAuthorUnitAtDeepZoom(){
        val bitmap=Bitmap.createBitmap(512,128,Bitmap.Config.ARGB_8888)
        val coarse=PencilTileRenderer(.5f,false);val fine=PencilTileRenderer(.125f,false)
        try {
            val ink=stroke(32f,8f)
            val canvas=Canvas(bitmap).apply{scale(8f,8f)}
            coarse.draw(canvas,ink);fine.draw(canvas,ink)
            assertTrue("Deep zoom must not clamp back to the old 0.5-unit material grid",fine.tileBuilds>coarse.tileBuilds)
        }finally{coarse.clear();fine.clear();bitmap.recycle()}
    }
}
