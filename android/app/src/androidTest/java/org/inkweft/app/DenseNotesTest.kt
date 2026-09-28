package org.inkweft.app
import android.graphics.*
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
class DenseNotesTest {
 @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
 @Test fun densePageDoesNotGenerateGraphiteOnUiThread(){
  val strokes=(0 until 1200).map{i->InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,5f,InkTool.STYLUS,(0..12).map{j->InkSample(50f+(i%25)*36+j*2,70f+(i/25)*26+(kotlin.math.sin(j*.5)*7).toFloat(),j*8L,.6f)},appearance=StrokeAppearance(BrushRecipe(),i.toLong(),0f,0f))}
  var view:InkCanvasView?=null;var count=0L;var elapsed=0.0
  rule.scenario.onActivity { a->
   val v=InkCanvasView(a);view=v;a.setContentView(FrameLayout(a).apply{addView(v,FrameLayout.LayoutParams(1000,1414))})
   v.configure(false,PaperStyle.BLANK,CanvasViewport(500.0,707.0,1.0/a.resources.displayMetrics.density));v.layout(0,0,1000,1414);v.showStrokes(strokes)
   val b=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);val before=PencilRenderer.tileBuilds;val start=System.nanoTime();v.draw(Canvas(b));elapsed=(System.nanoTime()-start)/1e6;count=PencilRenderer.tileBuilds-before;b.recycle()
  }
  println("DENSE_1200_FIRST_UI_MS=$elapsed; MAIN_GRAPHITE_TILES=$count")
  assertEquals("Opening dense ink generated graphite on the UI thread",0L,count)
  val deadline=System.nanoTime()+30_000_000_000L
  var pending=true
  while(pending&&System.nanoTime()<deadline){Thread.sleep(20);rule.scenario.onActivity{pending=view!!.rasterPending}}
  assertFalse("Dense page did not finish rendering",pending)
  rule.scenario.onActivity { a->val b=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);view!!.draw(Canvas(b));var countInk=0;for(y in 0 until 1414 step 3)for(x in 0 until 1000 step 3)if(Color.red(b.getPixel(x,y))<240)countInk++;assertTrue("Finished page is blank",countInk>1000);b.recycle();a.setContentView(FrameLayout(a)) }
 }
}
