package org.inkweft.app
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class CompactInkTest {
 private fun id()=UUID.randomUUID().toString()
 private fun line(pen:InkPen=InkPen.PENCIL)=InkStroke(id(),pen,0xff235cb7.toInt(),4f,InkTool.STYLUS,(0..80).map{InkSample(100f+it*3,200f+(if(it%2==0).3f else -.3f),it*8L,.2f+it*.005f,.3f,.5f)},appearance=StrokeAppearance(BrushRecipe(),42))
 @Test fun formulaPolishRetainsColorPressureWidthAndLayout(){
  PenKinds.writing.forEach{pen->val source=line(pen);val result=polishNewStroke(source,BeautyOptions(enabled=true,keepInk=true,inkStrength=1f))
   assertEquals(source.id,result.id);assertEquals(source.pen,result.pen);assertEquals(source.color,result.color);assertEquals(source.width,result.width);assertEquals(source.appearance,result.appearance)
   assertEquals(source.samples.first(),result.samples.first());assertEquals(source.samples.last(),result.samples.last())
   source.samples.zip(result.samples).forEach{(a,b)->assertEquals(a.pressure,b.pressure);assertEquals(a.tilt,b.tilt);assertEquals(a.orientation,b.orientation);assertEquals(a.elapsedMs,b.elapsedMs);assertTrue(kotlin.math.hypot(a.x-b.x,a.y-b.y)<=1.51f)}
  }
 }
 @Test fun offAndFontModesDoNotAlterNativeInk(){val s=line();assertSame(s,polishNewStroke(s,BeautyOptions()));assertSame(s,polishNewStroke(s,BeautyOptions(true,keepInk=false)))}
 @Test fun partialOverlapSelectsWholeVisibleStrokeButNotErasedGap(){
  val source=line(InkPen.BALLPOINT);val g=VisibleInkGeometry()
  val crossing=InkRegion(listOf(EraserPoint(170f,190f),EraserPoint(180f,210f)))
  assertTrue(g.selects(crossing,source))
  val cut=InkCut(id(),.01f,listOf(EraserPoint(160f,180f),EraserPoint(190f,220f)),InkCutShape.RECTANGLE)
  val erased=InkStroke(source.id,source.pen,source.color,source.width,source.tool,source.samples,false,listOf(cut),source.appearance)
  assertFalse(g.selects(crossing,erased))
  val polished=polishNewStroke(source,BeautyOptions(true));val masked=InkStroke(polished.id,polished.pen,polished.color,polished.width,polished.tool,polished.samples,false,listOf(cut),polished.appearance)
  assertFalse(g.selects(crossing,masked));assertNotNull(g.bounds(masked))
 }
 @Test fun reflectionAndScalingRetainCuts(){
  val s=line(InkPen.BALLPOINT).withCuts(listOf(InkCut(id(),.01f,listOf(EraserPoint(160f,180f),EraserPoint(190f,220f)),InkCutShape.RECTANGLE)))
  val changed=InkSelectionEdit.transform(listOf(s),1.1f,true).single()
  assertEquals(4.4f,changed.width,.001f);assertEquals(s.samples.map{it.pressure},changed.samples.map{it.pressure});assertEquals(1,changed.cuts.size)
  val box=changed.cuts.single().points;assertTrue(box[0].x<box[1].x);assertTrue(box[0].y<box[1].y)
  assertFalse(VisibleInkGeometry().selects(InkRegion(listOf(EraserPoint(box[0].x+1,box[0].y+1),EraserPoint(box[1].x-1,box[1].y-1))),changed))
 }
 @Test fun freeformLassoKeepsConcaveNotch(){
  val l=InkRegion(listOf(EraserPoint(80f,170f),EraserPoint(220f,170f),EraserPoint(220f,190f),EraserPoint(140f,190f),EraserPoint(140f,240f),EraserPoint(80f,240f)),false)
  assertTrue(l.contains(100.0,210.0));assertFalse(l.contains(190.0,215.0));assertTrue(VisibleInkGeometry().selects(l,line()))
 }
}
