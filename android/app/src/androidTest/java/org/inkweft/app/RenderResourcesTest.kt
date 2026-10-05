package org.inkweft.app
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class RenderResourcesTest {
 @Test fun budgetPressureDefersDerivedRasterAndRecoversWithoutReopening(){
  val ins=InstrumentationRegistry.getInstrumentation();val held=Any();val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
  val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLUE,3f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(150f,60f,40)))
  lateinit var raster:AsyncInkRaster;var errors=0;var complete=false
  val vp=CanvasViewport(100.0,100.0,1.0)
  try{
   RenderResources.track(held,RenderResources.BUDGET,"fixture","budget-test",RenderResources.Role.ACTIVE)
   ins.runOnMainSync{raster=AsyncInkRaster({},{errors++});raster.draw(Canvas(bitmap),200,200,vp,1.0,false,false,listOf(stroke))}
   var pending=true;val deadline=System.nanoTime()+10_000_000_000L
   while(pending&&System.nanoTime()<deadline){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}}
   assertFalse(pending);ins.runOnMainSync{assertFalse(raster.contains(stroke.id));assertFalse("An idle deferred job is not a complete drawn frame",raster.frameReady)};assertEquals(0,errors)
   RenderResources.release(held,"budget-test")
   while(!complete&&System.nanoTime()<deadline){Thread.sleep(20);ins.runOnMainSync{raster.draw(Canvas(bitmap),200,200,vp,1.0,false,false,listOf(stroke));complete=raster.contains(stroke.id)&&!raster.pending}}
   assertTrue("Deferred raster did not recover",complete);ins.runOnMainSync{assertTrue("Recovery must draw the complete current frame",raster.frameReady)};assertEquals(0,errors)
  }finally{RenderResources.release(held,"budget-test");ins.runOnMainSync{raster.clear()};bitmap.recycle()}
 }
 @Test fun allocationsAreNotDoubleCountedAndPinnedFrameSurvivesTrim(){
  val resource=Any();val before=RenderResources.snapshot().getValue("totalBytes")
  RenderResources.track(resource,4096,"fixture","cache-fixture",RenderResources.Role.CACHE)
  RenderResources.track(resource,4096,"fixture","view-fixture",RenderResources.Role.ACTIVE)
  assertEquals(before+4096,RenderResources.snapshot().getValue("totalBytes"))
  RenderResources.release(resource,"cache-fixture");assertEquals(before+4096,RenderResources.snapshot().getValue("totalBytes"));RenderResources.release(resource,"view-fixture")
  RenderResources.track(resource,RenderResources.BUDGET,"fixture","budget-fixture",RenderResources.Role.ACTIVE)
  try{assertThrows(RenderBudgetBusy::class.java){RenderResources.admit(1)};RenderResources.admit(1,live=true)}finally{RenderResources.release(resource,"budget-fixture")}
  val ins=InstrumentationRegistry.getInstrumentation();val b=Bitmap.createBitmap(220,180,Bitmap.Config.ARGB_8888)
  val s=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLUE,5f,InkTool.STYLUS,listOf(InkSample(20f,40f,0),InkSample(180f,60f,30)))
  lateinit var raster:AsyncInkRaster;val vp=CanvasViewport(110.0,90.0,1.0)
  ins.runOnMainSync{raster=AsyncInkRaster({});raster.draw(Canvas(b),220,180,vp,1.0,false,false,listOf(s))}
  var pending=true;val end=System.nanoTime()+10_000_000_000L
  while(pending&&System.nanoTime()<end){Thread.sleep(10);ins.runOnMainSync{pending=raster.pending}};assertFalse(pending)
  ins.runOnMainSync{raster.draw(Canvas(b),220,180,vp,1.0,false,false,listOf(s));val beforePixels=IntArray(39600);b.getPixels(beforePixels,0,220,0,0,220,180)
   RenderResources.trim();assertTrue(RenderResources.snapshot().getValue("activeBytes")>=b.allocationByteCount)
   b.eraseColor(Color.TRANSPARENT);raster.draw(Canvas(b),220,180,vp,1.0,false,false,listOf(s));assertFalse(raster.pending)
   val after=IntArray(39600);b.getPixels(after,0,220,0,0,220,180);assertArrayEquals(beforePixels,after);raster.clear()};b.recycle()
 }
 @Test fun replacementAdmissionDoesNotSubtractAnotherOwnersBitmap(){
  val old=Any();val held=Any();val bytes=8L*1024*1024
  try{
   RenderResources.trim()
   val baseline=RenderResources.snapshot().getValue("totalBytes");val free=2L*1024*1024
   assertTrue(baseline+bytes+free<RenderResources.BUDGET)
   RenderResources.track(old,bytes,"fixture","replacement-view",RenderResources.Role.ACTIVE)
   RenderResources.track(old,bytes,"fixture","replacement-cache",RenderResources.Role.CACHE)
   RenderResources.track(held,RenderResources.BUDGET-baseline-bytes-free,"fixture","replacement-pressure",RenderResources.Role.ACTIVE)
   val busy=assertThrows(RenderBudgetBusy::class.java){RenderResources.admit(bytes)}
   val before=RenderResources.snapshot().getValue("totalBytes")
   assertFalse(RenderResources.canReplace(old,"replacement-view",busy))
   assertEquals(before,RenderResources.snapshot().getValue("totalBytes"))
   RenderResources.release(old,"replacement-cache")
   assertTrue(RenderResources.canReplace(old,"replacement-view",busy))
   assertEquals("Admission checks do not release the still-visible bitmap",before,RenderResources.snapshot().getValue("totalBytes"))
   assertFalse("Unknown admission sizes cannot authorize dropping a frame",RenderResources.canReplace(old,"replacement-view",RenderBudgetBusy()))
  }finally{RenderResources.release(old,"replacement-view");RenderResources.release(old,"replacement-cache");RenderResources.release(held,"replacement-pressure")}
 }
 @Test fun immutableEqualityMemoDoesNotHideNewErasures(){
  val a=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,3f,InkTool.STYLUS,listOf(InkSample(30f,30f,0),InkSample(80f,40f,10)))
  val b=InkStrokeCodec.decode(InkStrokeCodec.encode(a));val cache=InkEqualityCache();var comparisons=0
  repeat(50){assertTrue(cache.same(a,b){comparisons++;a.samples==b.samples&&a.cuts.size==b.cuts.size})};assertEquals(1,comparisons)
  val erased=b.withCuts(listOf(InkCut(UUID.randomUUID().toString(),4f,listOf(EraserPoint(50f,35f)))))
  assertFalse(cache.same(a,erased){a.cuts.size==erased.cuts.size})
 }
}
