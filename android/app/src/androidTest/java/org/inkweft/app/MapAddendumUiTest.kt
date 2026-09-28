package org.inkweft.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
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

class MapAddendumUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun ready(){compose.waitUntil(20000){app.navigationReady.value};compose.waitForIdle()}
 private fun tap(tag:String){compose.revealAction(tag);val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick();compose.waitForIdle()}
 private inline fun<reified T:View> find():T{val q=java.util.ArrayDeque<View>();q.add(compose.activity.window.decorView);while(q.isNotEmpty()){val v=q.removeFirst();if(v is T&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)q.add(v.getChildAt(i))};error("Missing ${T::class.java}")}
 private fun vm(book:String)=ViewModelProvider(compose.activity)["study-$book",StudyViewModel::class.java]
 private fun shot(name:String){compose.waitForIdle();android.os.SystemClock.sleep(400);val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;java.io.File(app.getExternalFilesDir(null),"nfa-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private fun fixture():String {
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("条件概率 · 导图增补验收",false,PaperStyle.DOTS)}
  runBlocking{val text=listOf("第二章  条件概率与独立性","条件事件缩小了样本空间，分母应重新计算。","P(A | B) = P(A ∩ B) / P(B)","不放回取球：先确定第一次的结果，再计算第二次。","独立与互斥有不同含义，不能混用。","复习：核对条件、画出交集、计算概率。")
   app.pageObjects.save(note.id,0,id(),text.mapIndexed{i,t->PageObject(id(),PageObjectKind.TEXT,90f,100f+i*135,820f,100f,text=t,fontSize=if(i==0)32f else 24f,color=if(i==2)0xff286aca.toInt()else 0xff24262a.toInt())})
   val stroke=InkStroke(id(),InkPen.PEN,0xff286aca.toInt(),3f,InkTool.STYLUS,(0..25).map{InkSample(120f+it*20,355f+kotlin.math.sin(it*.5f)*7,it*8L)})
   app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(stroke)))
  }
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();ready();compose.frameCanvasFixture();return note.id
 }
 @Test fun closedMapDeliveryKeepsSourceAndUsesFrozenTarget(){
  val book=fixture();val a=id();val b=id();val branch=id()
  runBlocking{for((map,title)in listOf(a to "概率图",b to "另一张图"))app.knowledge.submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition(title)));app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=branch,title="条件概率",mapId=a))}
  tap("top-excerpt");compose.runOnIdle{find<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(90f,230f),EraserPoint(850f,400f))))};tap("capture-confirm")
  compose.onNodeWithTag("study-panel").assertDoesNotExist();tap("capture-map-$a");tap("capture-branch-$branch");shot("destination")
  // The target selector owns A even if the editing session switches to B.
  compose.runOnIdle{vm(book).selectMap(b)};tap("capture-send")
  compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.size==2};ready()
  compose.onNodeWithTag("study-panel").assertDoesNotExist();compose.onNodeWithTag("capture-result").assertIsDisplayed();assertTrue(runBlocking{app.study.nodes(book,b).first()}.isEmpty())
  val placed=runBlocking{app.study.nodes(book,a).first()}.single{it.id!=branch};assertEquals(branch,placed.parentId);assertEquals(1L,runBlocking{app.inkRepository.read(book).revision});shot("added-without-map")
  compose.onNodeWithText("撤销").performClick();compose.waitUntil(15000){runBlocking{app.study.nodes(book,a).first()}.single{it.id==placed.id}.removed}
  compose.runOnIdle{find<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(90f,230f),EraserPoint(850f,400f))))};tap("capture-confirm");tap("capture-last-destination")
  compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.size==3};compose.onNodeWithTag("study-panel").assertDoesNotExist()
 }
 @Test fun closedMapSearchListsRepeatedPositionsWithoutStartingOcr(){
  val book=fixture();val a=id();val b=id();val card=id();val first=id();val second=id()
  runBlocking{for(m in listOf(a,b))app.knowledge.submit(KnowledgeCommand(id(),book,m,0,KnowledgeData.MapDefinition(if(m==a)"概率图"else"复习图")));app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=first,title="概率公式",body="条件概率与样本空间",mapId=a));app.study.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=card,nodeId=second,mapId=b))}
  val before=runBlocking{app.pages.searchText(book)}
  tap("book-search");tap("book-map-search");compose.onNodeWithTag("map-content-query").performTextInput("样本空间");tap("map-search-all")
  compose.onNodeWithTag("map-hit-$a-$first").assertIsDisplayed();compose.onNodeWithTag("map-hit-$b-$second").assertIsDisplayed();compose.onNodeWithTag("study-panel").assertDoesNotExist();shot("search-closed")
  tap("map-hit-$b-$second");compose.onNodeWithTag("study-map").assertIsDisplayed();compose.runOnIdle{assertEquals(b,vm(book).mapId.value)};assertEquals(before,runBlocking{app.pages.searchText(book)})
 }
 @Test fun searchExpandsOnlyForNavigationAndRestoresOriginalView(){
  val book=fixture();val root=id();val child=id();val card=id()
  runBlocking{app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=root,title="总论"));app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=child,parentId=root,title="例题",body="不放回取球，条件概率",x=300.0,y=80.0))}
  tap("quick-study");tap("study-collapse-all");var original:MapViewport?=null
  compose.runOnIdle{original=find<MindMapView>().snapshotViewport();assertTrue(root in vm(book).collapsedByMap["main"].orEmpty())}
  tap("study-content-search");compose.onNodeWithTag("map-content-query").performTextInput("不放回")
  tap("map-hit-main-$child");compose.waitForIdle();shot("search-located")
  compose.runOnIdle{assertFalse(root in vm(book).collapsedByMap["main"].orEmpty());assertEquals(child,find<MindMapView>().selectedNodeId)}
  tap("study-content-search");compose.runOnIdle{assertTrue(root in vm(book).collapsedByMap["main"].orEmpty());assertEquals(original,find<MindMapView>().snapshotViewport())}
  assertEquals(2,runBlocking{app.study.nodes(book).first()}.size);assertEquals(1L,runBlocking{app.study.nodes(book).first()}.first().revision)
 }
 @Test fun windowModesKeepCanvasAndEmbeddedEditUsesSameWindow(){
  val book=fixture();val root=id()
  runBlocking{app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=root,title="条件概率",body="缩小样本空间"));repeat(3){i->app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),parentId=root,title=listOf("定义与公式","独立与互斥","不放回取球")[i],body="复习时先确认条件",x=300.0,y=40.0+i*128))}}
  tap("quick-overview");val directoryWidth=compose.onNodeWithTag("pages-directory-dialog").fetchSemanticsNode().boundsInRoot.width;tap("pages-directory-dialog-close")
  tap("quick-study");tap("quick-overview");assertEquals(directoryWidth,compose.onNodeWithTag("pages-directory-dialog").fetchSemanticsNode().boundsInRoot.width);tap("pages-directory-dialog-close");tap("study-window-minimize");val frame=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot;val canvas=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot;assertTrue(canvas.width*canvas.height/(frame.width*frame.height)>=.70f);shot("organize")
  tap("study-window-maximize");tap("study-window-mode-COLLECT");val collected=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
  tap("study-new-map");compose.onNodeWithTag("study-window-resize").assertIsDisplayed();compose.onNodeWithTag("study-window-resize").performTouchInput{swipe(center,center+Offset(50f,35f),400)}
  val resized=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot;assertTrue(resized.width>collected.width);compose.onNodeWithText("返回").performClick()
  tap("study-window-maximize");tap("study-window-mode-ORGANIZE");assertEquals(frame.size,compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot.size)
  tap("study-window-maximize");tap("study-window-mode-COLLECT");assertEquals(resized.size,compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot.size)
  tap("study-window-maximize");tap("study-window-mode-ORGANIZE");tap("study-insert-map");tap("map-insert-confirm");ready()
  compose.waitUntil(15000){runBlocking{app.pageObjects.read(book).objects}.any{it.kind==PageObjectKind.MAP}};shot("embedded-live")
  val embed=runBlocking{app.pageObjects.read(book).objects}.single{it.kind==PageObjectKind.MAP};assertEquals(MapEmbedPolicy.LIVE,embed.mapEmbed!!.policy)
  var paper:CanvasViewport?=null;compose.runOnIdle{paper=find<InkCanvasView>().snapshotViewport()}
  tap("object-edit-map");compose.onAllNodesWithTag("study-panel").assertCountEquals(1);compose.onNodeWithTag("study-map").assertIsDisplayed();tap("study-close")
  compose.runOnIdle{assertEquals(paper,find<InkCanvasView>().snapshotViewport())};assertEquals(1L,runBlocking{app.inkRepository.read(book).revision})
 }
}
