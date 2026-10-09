package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class TabletFeedbackUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun shot(name:String){compose.waitForIdle();val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
 private fun find(v:android.view.View,kind:Class<*>):android.view.View?{if(kind.isInstance(v)&&v.isShown)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),kind)?.let{return it};return null}
 private inline fun <reified T:android.view.View> view():T=checkNotNull(find(compose.activity.window.decorView,T::class.java)) as T
 @Test fun heldStylusKeepsActualToolbarPixelsWhileDisablingTools(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("V78 连写图标",false,PaperStyle.BLANK)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
  compose.singlePageEditor();compose.waitForSavedInk()
  fun pixels():IntArray{val p=compose.onNodeWithTag("editor-toolbar").captureToImage().toPixelMap();return IntArray(p.width*p.height){i->p[i%p.width,i/p.width].toArgb()}}
  val before=pixels()
  repeat(3){
   compose.runOnIdle{
    val canvas=view<InkCanvasView>();assertTrue(canvas.inputReady);val now=android.os.SystemClock.uptimeMillis()
    for(i in 0..1){
     val e=android.view.MotionEvent.obtain(now,now+i*40,if(i==0)android.view.MotionEvent.ACTION_DOWN else android.view.MotionEvent.ACTION_MOVE,1,arrayOf(android.view.MotionEvent.PointerProperties().apply{id=0;toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS}),arrayOf(android.view.MotionEvent.PointerCoords().apply{x=canvas.width/2f+i*20;y=canvas.height/2f+i*15;pressure=.7f}),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
     try{canvas.onTouchEvent(e)}finally{e.recycle()}
    }
   }
   compose.onNodeWithTag("top-draw").assertIsNotEnabled()
   assertArrayEquals("Gesture gating must not flash the actual editor toolbar",before,pixels())
   compose.runOnIdle{view<InkCanvasView>().cancelGesture()};compose.waitForSavedInk()
  }
  assertTrue(runBlocking{app.inkRepository.read(note.id).strokes.isEmpty()})
  shot("v78-toolbar-after-held-stylus.png")
 }
 @Test fun smoothingKeepsSelectedInkAndStylusDragDoesNotWrite(){
  val old=BeautyStore(app).read()
  try{
   BeautyStore(app).save(BeautyOptions(keepInk=false))
   compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
   val note=runBlocking{app.workspaceRepository.create("V78 润色后移动",false,PaperStyle.RULED)}
   val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff2255aa.toInt(),4f,InkTool.STYLUS,listOf(InkSample(200f,300f,0),InkSample(225f,305f,20),InkSample(250f,320f,40)))
   runBlocking{app.inkRepository.save(CommitInk(UUID.randomUUID().toString(),note.id,0,InkMutation.Add(stroke)))}
   compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
   compose.singlePageEditor();compose.waitForSavedInk();compose.frameCanvasFixture();compose.openBeautySettings()
   compose.onNodeWithText("笔形润色").performScrollTo().performClick()
   compose.runOnIdle{view<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(180f,280f),EraserPoint(270f,340f))))}
   compose.onNodeWithTag("beautify-dialog").assertIsDisplayed()
   compose.waitUntil(10000){runCatching{compose.onNodeWithTag("apply-beautify").assertIsEnabled()}.isSuccess}
   compose.onNodeWithTag("apply-beautify").performClick();compose.waitForSavedInk()
   compose.onNodeWithTag("ink-select").assertIsOn()
   compose.waitUntil(10000){var selected=false;compose.runOnIdle{selected=view<SelectionOverlayView>().selected.isNotEmpty()};selected}
   val before=runBlocking{InkSession(app.inkRepository.read(note.id)).visibleDraft().single()}
   compose.runOnIdle{
    val canvas=view<InkCanvasView>();val overlay=view<SelectionOverlayView>();val vp=canvas.snapshotViewport();val b=before.bounds()
    val p=vp.worldToScreen((b.left+b.right)/2,(b.top+b.bottom)/2,canvas.width.toDouble(),canvas.height.toDouble(),canvas.resources.displayMetrics.density.toDouble())
    val now=android.os.SystemClock.uptimeMillis()
    listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP).forEachIndexed{i,action->
     val e=android.view.MotionEvent.obtain(now,now+i*40,action,1,arrayOf(android.view.MotionEvent.PointerProperties().apply{id=0;toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS}),arrayOf(android.view.MotionEvent.PointerCoords().apply{x=p.x.toFloat()+if(i==0)0f else 60f;y=p.y.toFloat()+if(i==0)0f else 40f;pressure=1f}),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
     try{overlay.onTouchEvent(e)}finally{e.recycle()}
    }
   }
   compose.waitForSavedInk()
   val page=runBlocking{app.inkRepository.read(note.id)};val moved=InkSession(page).visibleDraft().single()
   assertTrue(moved.samples.first().x>before.samples.first().x+5);assertEquals(3,page.strokes.size)
   compose.onNodeWithTag("ink-select").assertIsOn();shot("v78-smoothed-ink-moved.png")
  }finally{BeautyStore(app).save(old)}
 }
 @Test fun selectionRuleAndShapeAreIndependentAndEraserShowsSize(){
  val old=SelectionStore(app).read();val oldEraser=EraserSettingsStore(app).read()
  try{
   SelectionStore(app).save(SelectionOptions());EraserSettingsStore(app).save(EraserSettings())
   compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
   val note=runBlocking{app.workspaceRepository.create("V78 选区与橡皮",false,PaperStyle.RULED)}
   compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
   compose.singlePageEditor();compose.waitForSavedInk()
   compose.onNodeWithTag("editor-paper-divider").assertExists()
   compose.onNodeWithTag("ink-select").performClick();compose.onNodeWithTag("ink-select").performClick()
   compose.onNodeWithTag("lasso-precise").performClick();compose.onNodeWithTag("lasso-free").assertIsSelected()
   compose.onNodeWithTag("lasso-rectangle").performClick();compose.onNodeWithTag("lasso-precise").assertIsSelected()
   compose.onNodeWithTag("lasso-touch").performClick();compose.onNodeWithTag("lasso-rectangle").assertIsSelected()
   assertFalse(SelectionStore(app).read().precise);assertFalse(SelectionStore(app).read().freehand)
   shot("v78-selection-settings.png")
   compose.onNodeWithContentDescription("关闭套索").performClick()
   compose.onNodeWithTag("top-eraser").performClick();compose.onNodeWithTag("top-eraser").performClick()
   compose.onNodeWithTag("eraser-size-preview").performScrollTo().assertIsDisplayed();shot("v78-eraser-preview.png")
   compose.onNodeWithContentDescription("关闭橡皮").performClick();shot("v78-toolbar-divider.png")
  }finally{SelectionStore(app).save(old);EraserSettingsStore(app).save(oldEraser)}
 }
}
