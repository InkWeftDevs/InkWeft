package org.inkweft.app

import android.graphics.*
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class SelectionPerformanceTest {
    @Test fun repeatedPageDrawKeepsPencilRasterWorkBounded() {
        val strokes=(0 until 120).map { i ->
            InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,8f,InkTool.STYLUS,
                (0..24).map { n -> InkSample(60f+(i%6)*145+n*4f,60f+(i/6)*55+(kotlin.math.sin(n*.3)*14).toFloat(),n*8L,.5f) },
                appearance=StrokeAppearance(BrushRecipe(),i.toLong(),0f,0f))
        }
        val b=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        val cache=InkPageRaster();var rasterized=0
        fun draw(items:List<InkStroke>){cache.draw(c,1000,1414,"viewport",items){canvas,s->rasterized++;PencilRenderer.draw(canvas,s)}}
        val times=(0..3).map { val start=System.nanoTime();draw(strokes);(System.nanoTime()-start)/1e6 }
        assertEquals("Stable page must not rerasterize old strokes",120,rasterized)
        draw(strokes+InkSelectionEdit.copy(listOf(strokes.first()),2f,0f));assertEquals(121,rasterized)
        val erased=strokes.mapIndexed{i,s->if(i==0)s.withCuts(listOf(InkCut(UUID.randomUUID().toString(),3f,listOf(EraserPoint(70f,60f)))))else s}
        draw(erased);assertEquals(241,rasterized)
        draw(erased.map{InkStrokeCodec.decode(InkStrokeCodec.encode(it))})
        assertEquals("Saving/reloading an unchanged erased stroke invalidated the entire page",241,rasterized)
        cache.clear()
        println("PENCIL_PAGE_120_MS=$times")
        b.recycle();PencilRenderer.forget(strokes.map{it.id}.toSet())
        assertTrue("Repeated page draw still rerasterizes: $times",times.drop(1).sorted()[1]<times.first()*.25)
    }
    @Test fun cachedInkMatchesDirectPixelsAfterAppendEraseAndViewportChange(){
        val a=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,12f,InkTool.STYLUS,
            listOf(InkSample(20f,40f,0,.6f),InkSample(180f,60f,100,.6f)),appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
        val b=InkStroke(UUID.randomUUID().toString(),InkPen.HIGHLIGHTER,0x60ffff00,20f,InkTool.STYLUS,
            listOf(InkSample(80f,20f,0),InkSample(80f,140f,100)))
        val cached=Bitmap.createBitmap(220,180,Bitmap.Config.ARGB_8888);val expected=Bitmap.createBitmap(220,180,Bitmap.Config.ARGB_8888)
        val renderer=InkBrushes.renderer();val cache=InkPageRaster()
        fun render(c:Canvas,s:InkStroke,dx:Float){val save=c.save();c.translate(dx,0f);s.cuts.forEach{c.clipOutPath(VisibleInkGeometry.cutPath(it))};
            if(s.pen==InkPen.PENCIL)PencilRenderer.draw(c,s)else renderer.draw(c,InkBrushes.stroke(s),Matrix().apply{setTranslate(dx,0f)});c.restoreToCount(save)}
        fun check(items:List<InkStroke>,dx:Float=0f){
            cached.eraseColor(Color.TRANSPARENT);expected.eraseColor(Color.TRANSPARENT)
            cache.draw(Canvas(cached),220,180,dx,items){c,s->render(c,s,dx)}
            items.forEach{render(Canvas(expected),it,dx)}
            val x=IntArray(220*180);val y=IntArray(x.size);cached.getPixels(x,0,220,0,0,220,180);expected.getPixels(y,0,220,0,0,220,180)
            assertArrayEquals(x,y)
        }
        check(listOf(a));check(listOf(a,b));check(listOf(a,b))
        val erased=a.withCuts(listOf(InkCut(UUID.randomUUID().toString(),9f,listOf(EraserPoint(75f,47f)))))
        check(listOf(erased,b));check(listOf(a,b));check(listOf(a,b),15f)
        cache.clear();cached.recycle();expected.recycle();PencilRenderer.forget(setOf(a.id,b.id))
    }

}
