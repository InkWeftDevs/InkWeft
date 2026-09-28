package org.inkweft.app
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class AsyncRasterTest {
 @Test fun backgroundRasterPreservesPixelsAndRejectsStaleWork(){
  val ins=InstrumentationRegistry.getInstrumentation();val actual=Bitmap.createBitmap(220,180,Bitmap.Config.ARGB_8888);val expected=Bitmap.createBitmap(220,180,Bitmap.Config.ARGB_8888)
  lateinit var raster:AsyncInkRaster
  ins.runOnMainSync{raster=AsyncInkRaster({})}
  val a=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,12f,InkTool.STYLUS,listOf(InkSample(20f,40f,0,.6f),InkSample(180f,60f,100,.6f)),appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
  val b=InkStroke(UUID.randomUUID().toString(),InkPen.HIGHLIGHTER,0x60ffff00,20f,InkTool.STYLUS,listOf(InkSample(80f,20f,0),InkSample(80f,140f,100)))
  fun check(items:List<InkStroke>,dx:Float=0f){
   val vp=CanvasViewport(110.0-dx,90.0,1.0)
   ins.runOnMainSync{raster.draw(Canvas(actual),220,180,vp,1.0,false,false,items)}
   val deadline=System.nanoTime()+10_000_000_000;var pending=true
   while(pending&&System.nanoTime()<deadline){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}}
   assertFalse(pending)
   ins.runOnMainSync{actual.eraseColor(Color.TRANSPARENT);raster.draw(Canvas(actual),220,180,vp,1.0,false,false,items)}
   expected.eraseColor(Color.TRANSPARENT);val c=Canvas(expected);val renderer=InkBrushes.renderer();val pencil=PencilTileRenderer();val matrix=Matrix().apply{setTranslate(dx,0f)}
   c.concat(matrix);c.clipRect(0f,0f,1000f,1414f)
   items.forEach{s->val save=c.save();s.cuts.forEach{c.clipOutPath(VisibleInkGeometry.cutPath(it))};if(s.pen==InkPen.PENCIL)pencil.draw(c,s)else renderer.draw(c,InkBrushes.stroke(s),matrix);c.restoreToCount(save)}
   val x=IntArray(220*180);val y=IntArray(x.size);actual.getPixels(x,0,220,0,0,220,180);expected.getPixels(y,0,220,0,0,220,180);assertArrayEquals(x,y)
  }
  try {check(listOf(a));check(listOf(a,b));val cut=a.withCuts(listOf(InkCut(UUID.randomUUID().toString(),9f,listOf(EraserPoint(75f,47f)))));check(listOf(cut,b));check(listOf(a,b),15f)
   ins.runOnMainSync{raster.draw(Canvas(actual),220,180,CanvasViewport(110.0,90.0,1.0),1.0,false,false,List(400){a})}
   check(emptyList());Thread.sleep(80);check(emptyList())
  }finally{ins.runOnMainSync{raster.clear()};actual.recycle();expected.recycle()}
 }
 @Test fun firstPreviewCoversBothEndsAndReopeningUsesCompleteMemoryCache(){
  val ins=InstrumentationRegistry.getInstrumentation();val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888)
  val vp=CanvasViewport(500.0,707.0,1.0)
  fun line(y:Float)=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,20f,InkTool.STYLUS,listOf(InkSample(50f,y,0,.7f),InkSample(950f,y,100,.7f)),appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
  val items=listOf(line(100f),line(1300f));var sawPreview=false;var partialContent=false;lateinit var raster:AsyncInkRaster
  fun region(y:Int):Boolean{for(j in y-20..y+20)for(x in 100..900 step 5)if(Color.alpha(bitmap.getPixel(x,j))>10)return true;return false}
  ins.runOnMainSync{AsyncInkRaster.clearMemoryCache();raster=AsyncInkRaster({
   bitmap.eraseColor(Color.TRANSPARENT);raster.draw(Canvas(bitmap),1000,1414,vp,1.0,false,false,items)
   if(raster.pending){sawPreview=true;if(!region(100)||!region(1300))partialContent=true}
  });raster.draw(Canvas(bitmap),1000,1414,vp,1.0,false,false,items)}
  val deadline=System.nanoTime()+20_000_000_000L;var pending=true
  while(pending&&System.nanoTime()<deadline){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}}
  assertFalse(pending);assertTrue(sawPreview);assertFalse("A partially drawn page reached the screen",partialContent)
  ins.runOnMainSync{raster.clear();val reopened=AsyncInkRaster({});bitmap.eraseColor(Color.TRANSPARENT);reopened.draw(Canvas(bitmap),1000,1414,vp,1.0,false,false,items);assertFalse("Reopening rebuilt an unchanged page",reopened.pending);assertTrue(region(100)&&region(1300));reopened.clear();AsyncInkRaster.clearMemoryCache()};bitmap.recycle()
 }
}
