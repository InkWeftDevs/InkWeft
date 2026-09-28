package org.inkweft.app
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
class IntegratedMapUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun tap(tag:String){if(tag in setOf("add-page","object-shape","page-objects","object-sticker","top-area-erase","object-camera")){compose.openEditorAction(tag);return};if(tag=="top-draw"){compose.openCurrentPen();return};val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
 private inline fun <reified T:View> find(v:View):T? {val queue=java.util.ArrayDeque<View>();queue.add(v);while(queue.isNotEmpty()){val current=queue.removeFirst();if(current is T&&current.isShown)return current;if(current is ViewGroup)for(i in 0 until current.childCount)queue.add(current.getChildAt(i))};return null}
 @Test fun selectedInkTargetsMapAndBranchThenSharesCardWithoutMovingSource(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("M1完整流程",false,PaperStyle.BLANK)}
  val stroke=InkStroke(id(),InkPen.PEN,0xff3159b8.toInt(),3f,InkTool.STYLUS,listOf(InkSample(200f,300f,0),InkSample(300f,320f,50)))
  val mapA=id();val mapB=id();val rootCard=id();val root=id()
  runBlocking{
   app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(stroke)))
   app.knowledge.submit(KnowledgeCommand(id(),note.id,mapA,0,KnowledgeData.MapDefinition("学习图")))
   app.knowledge.submit(KnowledgeCommand(id(),note.id,mapB,0,KnowledgeData.MapDefinition("复习图")))
   app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=rootCard,nodeId=root,title="章节一",mapId=mapA))
  }
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();ready()
  tap("ink-select");compose.runOnIdle{checkNotNull(find<SelectionOverlayView>(compose.activity.window.decorView)).onRegion(InkRegion(listOf(EraserPoint(150f,200f),EraserPoint(400f,400f))))}
  tap("selection-more");tap("selection-excerpt");compose.onNodeWithTag("study-panel").assertExists()
  tap("study-map-picker");tap("study-map-$mapA");compose.waitUntil(10000){runCatching{compose.onNodeWithTag("study-add-source").assertIsEnabled()}.isSuccess}
  tap("study-source-branch");tap("study-source-parent-$root");tap("study-add-source")
  fun cards()=runBlocking{app.study.cards(note.id).first()}
  compose.waitUntil(10000){cards().size==2};val card=cards().first{it.id!=rootCard}
  val source=runBlocking{app.study.source(card.id)}!!;assertEquals(note.id,source.pageId);assertEquals(stroke.id,InkPageFile.decode(source.snapshot).strokes.single().id)
  val placement=runBlocking{app.knowledge.observe().first()}.first{(it.data() as? KnowledgeData.MapOccurrence)?.cardId==card.id};assertEquals(root,(placement.data() as KnowledgeData.MapOccurrence).parentId)
  tap("study-tab-2");var viewport:MapViewport?=null
  compose.runOnIdle{val map=checkNotNull(find<MindMapView>(compose.activity.window.decorView));map.zoom(.7f);viewport=map.snapshotViewport()}
  tap("study-close");ready();tap("quick-study");compose.waitForIdle()
  compose.runOnIdle{val v=find<MindMapView>(compose.activity.window.decorView)
    if(v==null){val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;java.io.File(app.getExternalFilesDir(null),"map-reopen-failure.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle();println("MAP_TAB="+ViewModelProvider(compose.activity)["study-${note.id}",StudyViewModel::class.java].lastTab)}
    assertEquals(viewport,checkNotNull(v).snapshotViewport())}
  tap("study-tab-0");tap("study-card-${card.id}");compose.waitUntil(10000){compose.onAllNodesWithTag("study-open-source").fetchSemanticsNodes().isNotEmpty()};tap("study-open-source");ready()
  assertEquals(1L,runBlocking{app.inkRepository.read(note.id).revision})
  if(compose.onAllNodesWithTag("study-panel").fetchSemanticsNodes().isEmpty())tap("quick-study")
  tap("study-map-picker");tap("study-map-$mapB");compose.waitForIdle();tap("study-card-${card.id}");tap("study-reuse-card")
  compose.waitUntil(10000){runBlocking{app.study.nodes(note.id,mapB).first()}.size==1};assertEquals(2,cards().size)
  tap("study-card-${card.id}");tap("study-edit-card");compose.onNodeWithTag("study-card-title").performTextReplacement("共享理解");tap("study-save-card")
  compose.waitUntil(10000){cards().first{it.id==card.id}.title=="共享理解"}
  tap("study-map-picker");tap("study-map-$mapA");tap("study-tab-1");compose.onNodeWithText("共享理解").assertExists()
  val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
  java.io.File(app.getExternalFilesDir(null),"integrated-map-wide.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
 }
}
