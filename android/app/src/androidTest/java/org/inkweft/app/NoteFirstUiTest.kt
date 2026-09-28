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

class NoteFirstUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun ready(){compose.waitUntil(20000){app.navigationReady.value};compose.waitForIdle()}
 private inline fun<reified T:View> find():T {val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView);while(queue.isNotEmpty()){val v=queue.removeFirst();if(v is T&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)queue.add(v.getChildAt(i))};error("missing ${T::class.java}")}
 private fun tap(tag:String){val target=compose.onNodeWithTag(tag);runCatching{target.performScrollTo()};target.performClick();compose.waitForIdle()}
 private fun screenshot(name:String){compose.waitForIdle();android.os.SystemClock.sleep(350);val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;java.io.File(app.getExternalFilesDir(null),"nf-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
 private fun fixture():String {
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("概率论 · 条件概率",false,PaperStyle.DOTS)}
  runBlocking{
   val paragraphs=listOf("第二章  条件概率与独立性","01  条件概率的定义","在已知事件 B 发生的条件下，事件 A 发生的概率称为条件概率。注意限定后的样本空间。","P(A | B) = P(A ∩ B) / P(B)","02  独立与互斥的区别","独立：一个事件发生不改变另一个事件的概率。互斥：两个事件不能同时发生。","例题：从袋中连续取球，不放回时，第二次取球的概率依赖第一次的结果。","03  复习清单","□ 确认条件事件的概率大于零\n□ 画出事件的交集\n□ 检查独立性的适用条件")
   val objects=paragraphs.mapIndexed{i,t->PageObject(id(),PageObjectKind.TEXT,90f,110f+i*128,820f,120f,text=t,fontSize=if(i==0)32f else 23f,bold=i in setOf(0,1,4,7),color=if(i==3)0xff286aca.toInt()else 0xff24262a.toInt())}
   app.pageObjects.save(note.id,0,id(),objects)
   repeat(12){i->val y=325f+i*64;val s=InkStroke(id(),InkPen.PEN,if(i%2==0)0xff3159b8.toInt()else 0xffb34343.toInt(),2.5f,InkTool.STYLUS,(0..20).map{j->InkSample(110f+j*18,y+kotlin.math.sin(j*.4f)*4,j*8L)})
    app.inkRepository.save(CommitInk(id(),note.id,i.toLong(),InkMutation.Add(s)))
   }
  }
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();ready();compose.frameCanvasFixture();return note.id
 }
 @Test fun floatingFramePreservesPaperAndTemplateHasNoFakeCards(){
  val book=fixture();screenshot("writing")
  val paper=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
  var viewport:CanvasViewport?=null;compose.runOnIdle{viewport=find<InkCanvasView>().snapshotViewport()}
  tap("quick-study");tap("study-new-map");tap("map-template-3");compose.onNodeWithTag("study-new-map-title").performTextReplacement("条件概率梳理");tap("study-new-map-save");ready()
  compose.waitUntil(15000){runBlocking{app.knowledge.observe().first()}.any{(it.data() as? KnowledgeData.MapDefinition)?.title=="条件概率梳理"}}
  assertTrue(runBlocking{app.study.cards(book).first()}.isEmpty());assertEquals(paper,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
  compose.runOnIdle{assertEquals(viewport,find<InkCanvasView>().snapshotViewport())};screenshot("floating-map")
  val before=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
  compose.onNodeWithTag("study-window-drag").performTouchInput{swipe(center,center+Offset(-100f,40f),500)}
  val moved=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot;assertTrue(before!=moved)
  compose.onNodeWithTag("study-window-resize").performTouchInput{swipe(center,center+Offset(40f,35f),400)}
  assertEquals(paper,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
  tap("study-window-minimize");compose.onNodeWithTag("study-map").assertDoesNotExist();tap("study-window-minimize");compose.onNodeWithTag("study-map").assertIsDisplayed()
  compose.activityRule.scenario.recreate();ready();compose.onNodeWithTag("study-window-drag").assertIsDisplayed()
  compose.runOnIdle{assertEquals(viewport,find<InkCanvasView>().snapshotViewport())}
  tap("study-close");compose.openCurrentPen();screenshot("pen-basic");tap("pen-advanced");screenshot("pen-advanced");tap("pen-custom-open");compose.onNodeWithTag("pen-custom-color").performScrollTo().assertIsDisplayed();compose.closePenSettings()
  assertEquals(12L,runBlocking{app.inkRepository.read(book).revision})
 }
 @Test fun selectionPreviewMakesNoCardUntilExplicitAdd(){
  val book=fixture();tap("top-excerpt")
  compose.runOnIdle{find<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(100f,260f),EraserPoint(700f,400f))))}
  compose.waitForIdle();assertTrue(runBlocking{app.study.cards(book).first()}.isEmpty());compose.onNodeWithTag("study-add-source").assertIsDisplayed()
  screenshot("capture-ready");tap("study-add-source")
  compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.size==1};ready();tap("study-undo-capture")
  compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.single().trashedAt!=null}
  assertEquals(12L,runBlocking{app.inkRepository.read(book).revision})
 }
 @Test fun nativeDragShowsPreviewAndCommitsOneChild(){captureDrag(true)}
 @Test fun cancellingNativeDragKeepsSourceAndCreatesNothing(){captureDrag(false)}
 private fun captureDrag(commit:Boolean){
  val book=fixture();val parent=id()
  runBlocking{app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=parent,title="条件概率",x=40.0,y=80.0))}
  tap("quick-study");ready();tap("top-excerpt")
  compose.runOnIdle{find<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(100f,260f),EraserPoint(700f,400f))))}
  compose.waitForIdle();assertEquals(1,runBlocking{app.study.cards(book).first()}.size)
  var from=Offset.Zero;var to=Offset.Zero
  compose.runOnIdle{
   val source=find<SelectionOverlayView>();val pos=IntArray(2);source.getLocationOnScreen(pos)
   val p=source.canvasView!!.snapshotViewport().worldToScreen(250.0,330.0,source.width.toDouble(),source.height.toDouble(),source.resources.displayMetrics.density.toDouble());from=Offset(pos[0]+p.x.toFloat(),pos[1]+p.y.toFloat())
   val map=find<MindMapView>();map.getLocationOnScreen(pos);val vp=map.snapshotViewport();val d=map.resources.displayMetrics.density
   to=Offset(pos[0]+vp.x+148*vp.scale*d,pos[1]+vp.y+122*vp.scale*d)
  }
  val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  val down=android.os.SystemClock.uptimeMillis()
  fun send(action:Int,p:Offset){val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,p.x,p.y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(automation.injectInputEvent(e,true))}finally{e.recycle()}}
  send(android.view.MotionEvent.ACTION_DOWN,from)
  for(i in 1..24){android.os.SystemClock.sleep(20);send(android.view.MotionEvent.ACTION_MOVE,from+(to-from)*(i/24f))}
  android.os.SystemClock.sleep(180);send(android.view.MotionEvent.ACTION_MOVE,to)
  screenshot("drag-preview");assertEquals(1,runBlocking{app.study.cards(book).first()}.size)
  if(!commit)compose.runOnIdle{find<SelectionOverlayView>().cancelDragAndDrop()}
  send(android.view.MotionEvent.ACTION_UP,to)
  if(commit){compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.size==2};val nodes=runBlocking{app.study.nodes(book).first()};assertEquals(parent,nodes.single{it.id!=parent}.parentId)}
  else{compose.waitForIdle();assertEquals(1,runBlocking{app.study.cards(book).first()}.size);assertEquals(1,runBlocking{app.study.nodes(book).first()}.size)}
  assertEquals(12L,runBlocking{app.inkRepository.read(book).revision});screenshot("drag-saved")
 }

 private fun responsive(width:Int,font:Float){
  val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
  shell("wm size ${width*2}x1600");shell("wm density 320");shell("settings put system font_scale $font")
  android.os.SystemClock.sleep(800);compose.activityRule.scenario.recreate();val book=fixture()
  runBlocking{val root=id();app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=root,title="条件概率",x=40.0,y=100.0));app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),parentId=root,title="公式与方法",x=300.0,y=60.0));app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),parentId=root,title="独立与互斥",x=300.0,y=188.0))}
  listOf("ink-undo","ink-redo","top-draw","top-eraser","ink-select","top-excerpt","quick-study").forEach{tag->
   val b=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertTrue("$tag clipped horizontally",b.left>=0&&b.right<=width*2+1);assertTrue("$tag too small",b.width>=95&&b.height>=95)
  }
  screenshot("layout-$width-$font-writing");tap("quick-study");compose.onNodeWithTag("study-window-drag").assertIsDisplayed();screenshot("layout-$width-$font-map")
  tap("study-close");compose.openCurrentPen();compose.onNodeWithTag("pen-advanced").assertIsDisplayed();screenshot("layout-$width-$font-pen")
  tap("pen-advanced");compose.onNodeWithTag("width-preset-1").performScrollTo().performClick();screenshot("layout-$width-$font-advanced")
 }
 @Test fun narrow375AndLargeTextKeepCoreActionsOperable(){responsive(375,1.5f)}
 @Test fun tablet800Layout(){responsive(800,1f)}
 @Test fun tablet1180Layout(){responsive(1180,1f)}
 @Test fun tablet1366Layout(){responsive(1366,1f)}

 @Test fun personalTemplateIsAnonymousAndClosingWindowKeepsDraft(){
  val book=fixture();tap("quick-study");tap("study-new-map");tap("map-template-4");tap("study-new-map-save");ready()
  compose.waitUntil(15000){runBlocking{app.knowledge.observe().first()}.any{it.data() is KnowledgeData.MapDefinition}}
  tap("study-management");tap("study-save-template");compose.onNodeWithTag("map-template-title").performTextReplacement("复习结构");tap("save-map-template-confirm")
  compose.waitUntil(15000){runBlocking{app.knowledge.observe().first()}.any{(it.data() as? KnowledgeData.MapTemplate)?.title=="复习结构"}}
  val template=runBlocking{app.knowledge.observe().first()}.mapNotNull{it.data() as? KnowledgeData.MapTemplate}.single()
  assertTrue(template.nodes.all{it.title.startsWith("主题 ")});assertTrue(runBlocking{app.study.cards(book).first()}.isEmpty())
  tap("study-new-map");tap("map-template-6");tap("study-new-map-save");ready()
  compose.waitUntil(15000){runBlocking{app.knowledge.observe().first()}.count{it.data() is KnowledgeData.MapDefinition}==2}
  val definitions=runBlocking{app.knowledge.observe().first()}.mapNotNull{it.data() as? KnowledgeData.MapDefinition}
  assertTrue(definitions[0].structures.map{it.id}.intersect(definitions[1].structures.map{it.id}.toSet()).isEmpty())
  tap("study-management");tap("study-add-card");compose.onNodeWithTag("study-card-title").performTextInput("保留未保存标题")
  compose.onNodeWithTag("study-card-body").performTextInput("关闭浮窗仍保留草稿")
  tap("study-close");tap("quick-study");compose.onNodeWithTag("study-card-title").assertTextContains("保留未保存标题");compose.onNodeWithTag("study-card-body").assertTextContains("关闭浮窗仍保留草稿")
  assertTrue(runBlocking{app.study.cards(book).first()}.isEmpty());screenshot("draft-restored")
 }

 @Test fun stylusInputOutsideWindowReachesPaperThroughActualWindowTree(){
  val book=fixture();tap("quick-study");var point=Offset.Zero
  compose.runOnIdle{val v=find<InkCanvasView>();val location=IntArray(2);v.getLocationOnScreen(location);val p=v.snapshotViewport().worldToScreen(260.0,700.0,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());point=Offset(location[0]+p.x.toFloat(),location[1]+p.y.toFloat())}
  val window=compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot
  val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  val down=android.os.SystemClock.uptimeMillis()
  fun send(action:Int,dx:Float){val property=android.view.MotionEvent.PointerProperties().apply{id=0;toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS};val coords=android.view.MotionEvent.PointerCoords().apply{x=point.x+dx;y=point.y;pressure=.5f}
   val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,1,arrayOf(property),arrayOf(coords),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0);try{check(automation.injectInputEvent(event,true))}finally{event.recycle()}}
  send(android.view.MotionEvent.ACTION_DOWN,0f);android.os.SystemClock.sleep(40);send(android.view.MotionEvent.ACTION_MOVE,30f);send(android.view.MotionEvent.ACTION_UP,60f)
  compose.waitUntil(15000){runBlocking{app.inkRepository.read(book).revision}==13L}
  assertEquals(window,compose.onNodeWithTag("study-panel").fetchSemanticsNode().boundsInRoot);screenshot("write-outside-window")
 }

 @Test fun markerSelectionIsTemporaryAndInboxRequiresConfirmation(){
  val book=fixture();tap("top-excerpt");tap("top-excerpt");tap("excerpt-mark-mode");tap("capture-destination-inbox");compose.onNodeWithContentDescription("关闭摘要笔").performClick()
  var start=Offset.Zero;var end=Offset.Zero
  compose.runOnIdle{val v=find<SelectionOverlayView>();val vp=v.canvasView!!.snapshotViewport();val d=v.resources.displayMetrics.density.toDouble();val a=vp.worldToScreen(140.0,330.0,v.width.toDouble(),v.height.toDouble(),d);val b=vp.worldToScreen(650.0,345.0,v.width.toDouble(),v.height.toDouble(),d);start=Offset(a.x.toFloat(),a.y.toFloat());end=Offset(b.x.toFloat(),b.y.toFloat())}
  compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(start,end,500)}
  compose.waitUntil(15000){compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty()}
  assertTrue(runBlocking{app.study.cards(book).first()}.isEmpty());assertEquals(12L,runBlocking{app.inkRepository.read(book).revision})
  screenshot("marker-preview");tap("capture-confirm");compose.waitUntil(15000){runBlocking{app.study.cards(book).first()}.size==1}
  assertTrue(runBlocking{app.study.nodes(book).first()}.isEmpty());assertEquals(12L,runBlocking{app.inkRepository.read(book).revision})
 }

}

