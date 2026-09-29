package org.inkweft.app
import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class LiveBeautyEraseTest {
 @Test fun partialEraseAppearsBeforeUpAndCancelRestoresPixels(){
  val ins=InstrumentationRegistry.getInstrumentation()
  ins.runOnMainSync {
   val context=ins.targetContext
   val v=InkCanvasView(context);v.configure(false,PaperStyle.BLANK,CanvasViewport(150.0,150.0,1.0/context.resources.displayMetrics.density));v.layout(0,0,300,300)
   val o=PageObject(UUID.randomUUID().toString(),PageObjectKind.TEXT,60f,80f,180f,80f,text="田田",fontSize=60f,glyphs=listOf(TextGlyph(0,1,0f,0f,60f,60f),TextGlyph(1,2,100f,0f,60f,60f)))
   v.showObjects(listOf(o));v.allowInput=true;v.eraseMode=true;v.eraserWhole=false;v.eraserDiameterDp=10f/context.resources.displayMetrics.density
   fun pixels():IntArray{val b=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));return IntArray(90000).also{b.getPixels(it,0,300,0,0,300,300);b.recycle()}}
   val before=pixels();val now=SystemClock.uptimeMillis()
   fun event(action:Int,x:Float,y:Float,t:Long){val properties=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val coords=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=.5f};val e=MotionEvent.obtain(now,now+t,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);v.dispatchTouchEvent(e);e.recycle()}
   event(MotionEvent.ACTION_DOWN,88f,75f,0);event(MotionEvent.ACTION_MOVE,88f,150f,80)
   val live=pixels();var removed=0
   for(y in 82..136)for(x in 85..90){val i=y*300+x;if(Color.red(before[i])<180){assertEquals(Color.WHITE,live[i]);removed++}}
   assertTrue("No character pixels erased until pointer up",removed>0)
   for(y in 80..140)for(x in 160..220)assertEquals(before[y*300+x],live[y*300+x])
   event(MotionEvent.ACTION_CANCEL,88f,150f,100);assertArrayEquals(before,pixels())
  }
 }
}
