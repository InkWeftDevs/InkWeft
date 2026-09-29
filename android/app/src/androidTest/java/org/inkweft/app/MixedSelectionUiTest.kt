package org.inkweft.app
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class MixedSelectionUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun tap(tag:String){compose.revealAction(tag);if(tag in setOf("add-page","object-shape","page-objects","object-sticker","top-area-erase","object-camera")){compose.openEditorAction(tag);return};if(tag=="top-draw"){compose.openCurrentPen();return};val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick()}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
 private fun overlay(v:View):SelectionOverlayView?{if(v is SelectionOverlayView&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)overlay(v.getChildAt(i))?.let{return it};return null}
 @Test fun circleEraserHonoursHighlighterOnly(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  EraserSettingsStore(app).save(EraserSettings(onlyHighlighter=true))
  val n=runBlocking{app.workspaceRepository.create("V34 荧光圈擦",false,PaperStyle.BLANK)}
  val pen=InkStroke(id(),InkPen.PEN,0xff3159b8.toInt(),4f,InkTool.STYLUS,listOf(InkSample(200f,300f,0),InkSample(350f,380f,40)))
  val high=InkStroke(id(),InkPen.HIGHLIGHTER,0x80ffee00.toInt(),20f,InkTool.STYLUS,pen.samples)
  runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(pen)));app.inkRepository.save(CommitInk(id(),n.id,1,InkMutation.Add(high)))}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();ready()
  tap("ink-tool-3");tap("ink-tool-3");tap("eraser-circle")
  compose.runOnIdle{checkNotNull(overlay(compose.activity.window.decorView)).onRegion(InkRegion(listOf(EraserPoint(150f,180f),EraserPoint(490f,490f))))}
  compose.waitUntil(10000){runBlocking{app.inkRepository.read(n.id).revision}==3L};ready()
  val result=runBlocking{InkSession(app.inkRepository.read(n.id)).visibleDraft()};assertTrue(result.first{it.id==pen.id}.cuts.isEmpty());assertEquals(1,result.first{it.id==high.id}.cuts.size)
  EraserSettingsStore(app).save(EraserSettings())
 }
 @Test fun mixedCopyDeleteUndoAndFilterSettings(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  SelectionStore(app).save(SelectionOptions());EraserSettingsStore(app).save(EraserSettings())
  val n=runBlocking{app.workspaceRepository.create("V34 混合选区验收",false,PaperStyle.BLANK)}
  val source=InkStroke(id(),InkPen.PEN,0xff3159b8.toInt(),4f,InkTool.STYLUS,listOf(InkSample(200f,300f,0),InkSample(350f,380f,40)))
  val shape=PageObject(id(),PageObjectKind.SHAPE,240f,240f,160f,160f,lineWidth=3f)
  val tape=PageObject(id(),PageObjectKind.TAPE,300f,420f,160f,40f)
  runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(source)));app.pageObjects.save(n.id,0,id(),listOf(shape,tape))}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();ready()
  tap("ink-select");tap("ink-select");tap("lasso-precise");compose.onNodeWithText("完整圈住才选中").assertExists();tap("lasso-free");tap("lasso-filter-TAPE");assertFalse(SelectionType.TAPE in SelectionStore(app).read().types);tap("lasso-filter-TAPE")
  compose.onNodeWithContentDescription("关闭套索").performClick()
  compose.runOnIdle{checkNotNull(overlay(compose.activity.window.decorView)).onRegion(InkRegion(listOf(EraserPoint(150f,180f),EraserPoint(490f,490f))))}
  compose.onNodeWithText("3 项").assertExists();tap("mixed-more");tap("mixed-enlarge")
  compose.waitUntil(10000){runBlocking{app.pageObjects.read(n.id).objects.first().width}>175f};ready();compose.onNodeWithText("3 项").assertExists()
  tap("ink-undo");compose.waitUntil(10000){runBlocking{app.pageObjects.read(n.id).objects.first().width}==160f};ready()
  tap("ink-select");tap("selection-all");compose.onNodeWithText("3 项").assertExists();tap("mixed-copy")
  fun ink()=runBlocking{InkSession(app.inkRepository.read(n.id)).visibleDraft()}
  fun objects()=runBlocking{app.pageObjects.read(n.id).objects}
  compose.waitUntil(10000){ink().size==2&&objects().size==4};ready();compose.onNodeWithText("3 项").assertExists()
  tap("mixed-delete");compose.waitUntil(10000){ink().size==1&&objects().size==2};ready()
  tap("ink-undo");compose.waitUntil(10000){ink().size==2&&objects().size==4};ready()
  tap("ink-undo");compose.waitUntil(10000){ink().size==1&&objects().size==2};ready()
  tap("ink-select");tap("lasso-filter-TAPE");compose.onNodeWithContentDescription("关闭套索").performClick()
  tap("ink-select");tap("selection-all");compose.onNodeWithText("2 项").assertExists();tap("mixed-dismiss")
  tap("ink-select");tap("lasso-filter-TAPE");tap("lasso-rectangle");assertFalse(SelectionStore(app).read().freehand)
  compose.onNodeWithContentDescription("关闭套索").performClick()
  tap("ink-tool-3");tap("ink-select");tap("ink-select");compose.onNodeWithTag("lasso-rectangle").assertIsSelected()
  tap("lasso-free");compose.onNodeWithContentDescription("关闭套索").performClick()
  tap("ink-tool-3");tap("ink-tool-3");tap("eraser-advanced");tap("erase-tape-only");assertTrue(EraserSettingsStore(app).read().onlyTape);tap("eraser-basic");compose.onNodeWithTag("eraser-local").assertIsNotEnabled();tap("eraser-advanced");tap("erase-highlighter-only");assertFalse(EraserSettingsStore(app).read().onlyTape);tap("erase-highlighter-only")
  compose.onNodeWithContentDescription("关闭橡皮").performClick()
 }
}
