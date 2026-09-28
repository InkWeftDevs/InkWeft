package org.inkweft.app
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
class WritingRepairUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun open():Note {compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()};val n=runBlocking{app.workspaceRepository.create("V28 操作复验",false,PaperStyle.BLANK)};compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();ready();return n}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value}}
 private fun objects(n:Note)=runBlocking{app.pageObjects.read(n.id).objects}
 private fun tap(tag:String){val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
 private fun point(x:Float,y:Float):Offset {var r=Offset.Zero;compose.runOnIdle{fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};val v=checkNotNull(find(compose.activity.window.decorView));val p=v.snapshotViewport().worldToScreen(x.toDouble(),y.toDouble(),v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());r=Offset(p.x.toFloat(),p.y.toFloat())};return r}
 @Test fun tableBordersSurviveClipping(){
  val bitmap=android.graphics.Bitmap.createBitmap(300,240,android.graphics.Bitmap.Config.ARGB_8888)
  val table=PageObject(java.util.UUID.randomUUID().toString(),PageObjectKind.SHAPE,20f,20f,240f,180f,color=android.graphics.Color.BLACK,lineWidth=8f,shape=ObjectShape.TABLE)
  val painter=PageObjectPainter()
  try {painter.draw(android.graphics.Canvas(bitmap),listOf(table),false,CanvasBounds(0.0,0.0,300.0,240.0))
   for((x,y) in listOf(24 to 100,256 to 100,120 to 24,120 to 196))assertTrue("Missing table edge at $x,$y",android.graphics.Color.alpha(bitmap.getPixel(x,y))>240)
  }finally{painter.clear();bitmap.recycle()}
 }
 @Test fun tapeDrawRevealReCoverMoveAndReopen(){
  val n=open();tap("object-tape");assertTrue(objects(n).isEmpty())
  val a=point(200f,250f);val b=point(650f,250f)
  compose.onNodeWithTag("tape-overlay").performTouchInput{swipe(a,b,200)}
  compose.waitUntil(10000){objects(n).size==1};ready();val tape=objects(n).single();assertTrue(tape.tapePoints.size>=2);assertTrue(tape.width>400);assertTrue("Drawn tape misses its centre: $tape",ObjectGeometry.hit(tape,425f,250f))
  compose.onNodeWithTag("tape-overlay").performTouchInput{click((a+b)/2f)};compose.waitUntil(10000){objects(n).single().revealed};ready()
  compose.onNodeWithTag("tape-overlay").performTouchInput{click((a+b)/2f)};compose.waitUntil(10000){!objects(n).single().revealed};ready()
  tap("ink-undo");compose.waitUntil(10000){objects(n).single().revealed};ready();tap("ink-redo");compose.waitUntil(10000){!objects(n).single().revealed};ready()
  tap("page-objects");compose.onNodeWithTag("object-overlay").performTouchInput{swipe((a+b)/2f,(a+b)/2f+Offset(0f,70f),200)}
  compose.waitUntil(10000){objects(n).single().y>tape.y+20};ready();val saved=objects(n).single()
  compose.activityRule.scenario.recreate();ready();assertEquals(saved,objects(n).single())
 }
 @Test fun everyShapeIsOneMovableResizableObject(){
  val n=open()
  for(kind in ObjectShape.entries){
   tap("object-shape");tap("shape-"+kind.name.lowercase());compose.waitUntil(10000){objects(n).isNotEmpty()};ready()
   val before=objects(n).single();assertEquals(kind,before.shape)
   val a=point(before.x+before.width/2,before.y+before.height/2)
   compose.onNodeWithTag("object-overlay").performTouchInput{swipe(a,a+Offset(80f,55f),200)}
   compose.waitUntil(10000){objects(n).single().x>before.x+20};ready();val moved=objects(n).single()
   tap("ink-undo");compose.waitUntil(10000){objects(n).single().x==before.x};ready();tap("ink-redo");compose.waitUntil(10000){objects(n).single().x==moved.x};ready()
   val handle=point(moved.x+moved.width,moved.y+moved.height)
   compose.onNodeWithTag("object-overlay").performTouchInput{swipe(handle,handle+Offset(40f,60f),200)}
   compose.waitUntil(10000){objects(n).single().width>moved.width+10};ready()
   tap("object-deselect");val current=objects(n).single();val p=point(current.x+current.width/2,current.y+current.height/2)
   compose.onNodeWithTag("ink-surface").performTouchInput{click(p)};compose.onNodeWithTag("object-delete").assertIsDisplayed()
   tap("object-delete");compose.waitUntil(10000){objects(n).isEmpty()};ready()
  }
 }
}
