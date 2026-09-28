package org.inkweft.app
import android.graphics.*
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class FastPencilTest {
 @Test fun longLiveStrokeDoesNotRerasterizeOldTiles(){
  val id=UUID.randomUUID().toString();val points=(0 until 960).map{n->val row=n/80;val col=n%80;InkSample(70f+(if(row%2==0)col else 79-col)*10f,100f+row*85,n*4L,.7f)}
  val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);val times=mutableListOf<Double>()
  fun stroke(n:Int)=InkStroke(id,InkPen.PENCIL,Color.BLACK,8f,InkTool.STYLUS,points.take(n),appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
  for(n in 48..960 step 48){val start=System.nanoTime();PencilRenderer.draw(c,stroke(n));times+=(System.nanoTime()-start)/1e6}
  // Live preview uses world coordinates; paper commit changes only the domain flag.
  val live=InkStroke(id,InkPen.PENCIL,Color.BLACK,8f,InkTool.STYLUS,points.map{it.copy(world=true)},true,appearance=StrokeAppearance(BrushRecipe(),123,0f,0f))
  val before=PencilRenderer.tileBuilds;PencilRenderer.draw(c,live);PencilRenderer.draw(c,stroke(960));val misses=PencilRenderer.tileBuilds-before
  println("LIVE_PENCIL_GROWTH_MS=$times; REDRAW_TILE_BUILDS=$misses")
  bitmap.recycle();PencilRenderer.forget(setOf(id))
  assertEquals("Repainting one unchanged fast stroke rebuilt its tiles",0L,misses)
 }
}
