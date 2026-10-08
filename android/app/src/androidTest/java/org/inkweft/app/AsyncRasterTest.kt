package org.inkweft.app
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class AsyncRasterTest {
 @Test fun editedFallbackPreservesOverlappingPencilAndHighlighterPixels(){
  val ins=InstrumentationRegistry.getInstrumentation();val actual=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);val expected=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
  val vp=CanvasViewport(150.0,150.0,1.0);lateinit var raster:AsyncInkRaster
  fun stroke(pen:InkPen,color:Int,y:Float)=InkStroke(UUID.randomUUID().toString(),pen,color,20f,InkTool.STYLUS,listOf(InkSample(40f,y,0,.7f),InkSample(260f,y+25f,100,.7f)),appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
  val pencil=stroke(InkPen.PENCIL,Color.BLACK,100f);val marker=stroke(InkPen.HIGHLIGHTER,0x60ffff00,110f);val unaffected=stroke(InkPen.PEN,Color.BLUE,220f)
  fun draw(items:List<InkStroke>){actual.eraseColor(Color.TRANSPARENT);raster.draw(Canvas(actual),300,300,vp,1.0,false,false,items)}
  ins.runOnMainSync{AsyncInkRaster.clearMemoryCache();raster=AsyncInkRaster({});draw(listOf(pencil,marker,unaffected))}
  val deadline=System.nanoTime()+10_000_000_000L;var pending=true
  while(pending&&System.nanoTime()<deadline){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}}
  try{
   assertFalse(pending)
   ins.runOnMainSync{
    val cut=marker.withCuts(listOf(InkCut(UUID.randomUUID().toString(),25f,listOf(EraserPoint(150f,123f)))))
    for(items in listOf(listOf(pencil,cut,unaffected),listOf(pencil,unaffected),listOf(unaffected))){
     draw(items);expected.eraseColor(Color.TRANSPARENT)
     val c=Canvas(expected);val matrix=Matrix();val renderer=InkBrushes.renderer();val pencilRenderer=PencilTileRenderer(.5f,false)
     try{items.forEach{s->val saved=c.save();s.cuts.forEach{c.clipOutPath(VisibleInkGeometry.cutPath(it))};if(s.pen==InkPen.PENCIL)pencilRenderer.draw(c,s)else renderer.draw(c,InkBrushes.stroke(s),matrix);c.restoreToCount(saved)}}finally{pencilRenderer.clear()}
     val a=IntArray(90_000);val b=IntArray(a.size);actual.getPixels(a,0,300,0,0,300,300);expected.getPixels(b,0,300,0,0,300,300)
     assertArrayEquals("The pending replacement must match current cuts and stacking order",b,a)
    }
   }
  }finally{ins.runOnMainSync{raster.clear();AsyncInkRaster.clearMemoryCache()};actual.recycle();expected.recycle()}
 }
 @Test fun eraseKeepsUnaffectedInkVisibleBeforeReplacementFrameCompletes(){
  val ins=InstrumentationRegistry.getInstrumentation();val bitmap=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
  val vp=CanvasViewport(150.0,150.0,1.0)
  fun line(y:Float)=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,8f,InkTool.STYLUS,listOf(InkSample(30f,y,0),InkSample(270f,y,100)))
  val first=line(90f);val second=line(210f);lateinit var raster:AsyncInkRaster
  fun draw(strokes:List<InkStroke>){bitmap.eraseColor(Color.TRANSPARENT);raster.draw(Canvas(bitmap),300,300,vp,1.0,false,false,strokes)}
  ins.runOnMainSync{AsyncInkRaster.clearMemoryCache();raster=AsyncInkRaster({});draw(listOf(first,second))}
  val deadline=System.nanoTime()+10_000_000_000L;var pending=true
  while(pending&&System.nanoTime()<deadline){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}}
  try{
   assertFalse(pending)
   ins.runOnMainSync{
    draw(listOf(first,second));assertTrue(Color.alpha(bitmap.getPixel(150,210))>200)
    val erased=first.withCuts(listOf(InkCut(UUID.randomUUID().toString(),16f,listOf(EraserPoint(150f,90f)))))
    draw(listOf(erased,second))
    assertTrue("Unchanged line disappeared while the partial-erase frame was rebuilding",Color.alpha(bitmap.getPixel(150,210))>200)
    assertTrue("The uncut end must remain visible",Color.alpha(bitmap.getPixel(50,90))>200)
    assertEquals("Erased ink must not reappear in the retained frame",0,Color.alpha(bitmap.getPixel(150,90)))
    draw(listOf(second))
    assertTrue("Whole-stroke erase must not blank unrelated ink",Color.alpha(bitmap.getPixel(150,210))>200)
    assertEquals(0,Color.alpha(bitmap.getPixel(50,90)))
   }
  }finally{ins.runOnMainSync{raster.clear();AsyncInkRaster.clearMemoryCache()};bitmap.recycle()}
 }
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
