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

class MapInteractionUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun tap(tag:String){compose.revealAction(tag);val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performTouchInput{click()};compose.waitForIdle()}
 private fun map():MindMapView{val q=java.util.ArrayDeque<View>();q.add(compose.activity.window.decorView);while(q.isNotEmpty()){val v=q.removeFirst();if(v is MindMapView&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)q.add(v.getChildAt(i))};error("Map missing")}
 private fun vm(book:String)=ViewModelProvider(compose.activity)["study-$book",StudyViewModel::class.java]
 private fun shot(name:String){compose.waitForIdle();android.os.SystemClock.sleep(350);val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;java.io.File(app.getExternalFilesDir(null),"mui-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private data class Fixture(val book:String,val root:String,val card:String,val otherMap:String,val body:String)
 private fun fixture():Fixture{
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("学习笔记 · 导图交互验收",false,PaperStyle.RULED)};val root=id();val card=id();val other=id();val body=(1..24).joinToString("\n"){"第${it}段：条件概率需要先确定样本空间，再计算交集；记清独立和互斥的区别。"}
  runBlocking{
   app.pageObjects.save(note.id,0,id(),listOf(PageObject(id(),PageObjectKind.TEXT,70f,80f,830f,220f,text="第二章 条件概率与独立性\nP(A | B) = P(A ∩ B) / P(B)\n先确定条件，再分析样本空间。",fontSize=28f)))
   val stroke=InkStroke(id(),InkPen.PEN,0xff286aca.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,380f,0),InkSample(420f,380f,100)))
   app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(stroke)))
   app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=card,nodeId=root,title="条件概率",body=body,source=StudySourceDraft(note.id,1,stroke.bounds(),listOf(stroke.id))))
   repeat(23){i->app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=id(),nodeId=id(),parentId=root,title=when(i%4){0->"独立事件";1->"从实验结果理解样本空间范围";2->"条件改变后的分母需要重新计算并检查事件是否能够同时发生";else->"已知事件发生之后重新定义样本空间，并通过树状分支记录每一步的条件及其对应概率，最后比较独立事件与互斥事件在计算中的区别"},body="概念、例题与复习问题 ${i+1}",x=300.0+(i%3)*260,y=40.0+(i/3)*128))}
   app.knowledge.submit(KnowledgeCommand(id(),note.id,other,0,KnowledgeData.MapDefinition("共享复习图")))
   app.study.submit(StudyCommand(id(),note.id,StudyAction.REUSE,mapId=other,cardId=card,nodeId=id()))
  }
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();compose.frameCanvasFixture();tap("quick-study");return Fixture(note.id,root,card,other,body)
 }
 private fun select(node:String,double:Boolean=false){var p=Offset.Zero;compose.runOnIdle{map().focusNode(node);val b=map().nodeBounds(node)!!;p=Offset(b.centerX(),b.centerY())};compose.waitForIdle();compose.onNodeWithTag("study-map").performTouchInput{if(double)doubleClick(p)else click(p)};compose.waitForIdle()}
 @Test fun selectionRenameReadingAndMapMenusKeepTheirScope(){
  val f=fixture();shot("unselected-24");select(f.root)
  compose.onNodeWithTag("study-card-details").assertDoesNotExist();compose.onNodeWithTag("node-actions").assertIsDisplayed();shot("selected")
  tap("node-rename");compose.onNodeWithTag("study-map").assertIsDisplayed();compose.onNodeWithTag("node-title-input").performTextReplacement("条件概率与样本空间");shot("rename-keyboard")
  tap("node-title-save");compose.waitUntil(10000){runBlocking{app.study.cards(f.book).first()}.first{it.id==f.card}.title=="条件概率与样本空间"}
  val updated=runBlocking{app.study.cards(f.book).first()}.first{it.id==f.card};assertEquals(f.body,updated.body);assertNotNull(runBlocking{app.study.source(f.card)})
  val shared=runBlocking{app.mapGraphs.observe(f.book).first()}.first{it.ref.mapId==f.otherMap};assertEquals(updated.title,shared.nodes.single().title)
  tap("node-more");shot("node-menu");compose.onNodeWithTag("node-remove").assertIsNotEnabled();tap("node-view-content");compose.onNodeWithTag("card-full-body").assertTextEquals(f.body);shot("long-content");tap("card-back")
  tap("study-management");compose.onNodeWithTag("study-map").assertExists();shot("map-menu");compose.onNodeWithTag("study-tab-2").performScrollTo().performClick()
  select(f.root,true);compose.onNodeWithTag("node-title-editor").assertIsDisplayed();compose.onNodeWithTag("study-map").performTouchInput{click(Offset(10f,10f))};compose.onNodeWithTag("node-title-editor").assertIsDisplayed();tap("node-title-cancel")
  compose.onNodeWithTag("study-map").performTouchInput{click(Offset(10f,10f))};compose.onNodeWithTag("node-actions").assertDoesNotExist()
 }
 @Test fun provisionalChildAndSiblingCancelWithoutOrphans(){
  val f=fixture();select(f.root);tap("node-add-child");compose.onNodeWithTag("study-card-body").assertDoesNotExist();compose.onNodeWithTag("node-title-input").performTextInput("临时分支");tap("node-title-cancel")
  assertEquals(24,runBlocking{app.study.cards(f.book).first().size});assertEquals(24,runBlocking{app.study.nodes(f.book).first().size})
  tap("node-add-child");compose.onNodeWithTag("node-title-input").performTextInput("不放回取球");tap("node-title-save")
  compose.waitUntil(10000){runBlocking{app.study.cards(f.book).first().size}==25};val child=runBlocking{app.study.nodes(f.book).first()}.single{n->runBlocking{app.study.cards(f.book).first()}.any{it.id==n.cardId&&it.title=="不放回取球"}};assertEquals(f.root,child.parentId)
  select(child.id);tap("node-add-sibling");compose.onNodeWithTag("node-title-input").performTextInput("放回取球");tap("node-title-save");compose.waitUntil(10000){runBlocking{app.study.nodes(f.book).first().size}==26};assertEquals(25,runBlocking{app.study.nodes(f.book).first()}.count{it.parentId==f.root})
 }
 @Test fun conflictAndRecreationRetainFrozenTitleDraft(){
  val f=fixture();select(f.root);tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("尚未提交的名称");compose.waitForIdle();compose.onNodeWithTag("node-title-input").assertTextContains("尚未提交的名称")
  compose.activityRule.scenario.recreate();compose.waitForIdle();compose.onNodeWithTag("node-title-input").assertTextContains("尚未提交的名称")
  runBlocking{val c=app.study.cards(f.book).first().first{it.id==f.card};app.study.submit(StudyCommand(id(),f.book,StudyAction.EDIT,cardId=c.id,expectedRevision=c.revision,title="另一处更新",body=f.body+"\n新的正文"))}
  tap("node-title-save");compose.waitUntil(10000){compose.onAllNodesWithTag("node-title-error").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("node-title-input").assertTextContains("尚未提交的名称");assertEquals("另一处更新",runBlocking{app.study.cards(f.book).first()}.first{it.id==f.card}.title)
  shot("conflict-draft");tap("node-title-cancel")
 }
 @Test fun structureRenameDoesNotCreateCardOrChangePlacement(){
  val f=fixture();val m=id();val n=id();val definition=KnowledgeData.MapDefinition("结构图",structures=listOf(MapStructure(n,null,"待修改结构",40.0,80.0)))
  runBlocking{app.knowledge.submit(KnowledgeCommand(id(),f.book,m,0,definition))};compose.runOnIdle{vm(f.book).selectMap(m)};compose.waitUntil(10000){vm(f.book).ui.value.nodes.any{it.id==n}};select(n);tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("新的结构标题");tap("node-title-save")
  compose.waitUntil(10000){runBlocking{app.knowledge.observeBook(f.book).first()}.any{r->r.id==m&&(r.data() as KnowledgeData.MapDefinition).structures.first().title=="新的结构标题"}}
  val updated=runBlocking{app.knowledge.observeBook(f.book).first()}.first{it.id==m}.data() as KnowledgeData.MapDefinition
  assertEquals(definition.structures.single().copy(title="新的结构标题"),updated.structures.single());assertEquals(24,runBlocking{app.study.cards(f.book).first().size})
 }
 @Test fun narrowLargeTextKeepsActionsDraftAndCanvas(){
  val f=fixture();val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(c:String){automation.executeShellCommand(c).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
  try{
   shell("wm size 750x1600");shell("wm density 320");shell("settings put system font_scale 1.5");shell("settings put secure show_ime_with_hard_keyboard 1")
   compose.activityRule.scenario.recreate();compose.waitForIdle();select(f.root);shot("narrow-selected")
   val actions=compose.onNodeWithTag("node-actions").fetchSemanticsNode().boundsInRoot;val canvas=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot
   assertTrue(actions.left>=canvas.left&&actions.right<=canvas.right)
   tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("窄窗中文草稿");compose.onNodeWithTag("node-title-save").assertIsDisplayed();shot("narrow-keyboard")
   compose.activityRule.scenario.recreate();compose.waitForIdle();compose.onNodeWithTag("node-title-input").assertTextContains("窄窗中文草稿");tap("node-title-cancel")
  }finally{shell("wm size 1920x1200");shell("wm density 240");shell("settings put system font_scale 1.0");shell("settings put secure show_ime_with_hard_keyboard 0")}
 }
 @Test fun microMoveAndCancelledDragDoNotMoveNode(){
  val f=fixture();select(f.root);val before=runBlocking{app.study.nodes(f.book).first()}.first{it.id==f.root};var p=Offset.Zero
  compose.runOnIdle{val b=map().nodeBounds(f.root)!!;p=Offset(b.centerX(),b.centerY())}
  compose.onNodeWithTag("study-map").performTouchInput{advanceEventTime(500);down(p);moveTo(p+Offset(1f,1f));up()};compose.waitForIdle()
  assertEquals(before,runBlocking{app.study.nodes(f.book).first()}.first{it.id==f.root});compose.onNodeWithTag("node-title-editor").assertDoesNotExist()
  compose.onNodeWithTag("study-map").performTouchInput{down(p);moveTo(p+Offset(60f,20f));cancel()};compose.waitForIdle()
  assertEquals(before,runBlocking{app.study.nodes(f.book).first()}.first{it.id==f.root})
 }

 @Test fun unknownSaveKeepsDraftAndRetriesOriginalOperation(){
  val f=fixture();tap("study-close");val database=NoteDatabase.open(app);var fail=true
  val repository=StudyRepository(database){if(it==StudyFault.BEFORE_RECEIPT&&fail)throw java.io.IOException("test receipt unavailable")}
  compose.runOnIdle{compose.activity.viewModelStore.put("study-${f.book}",StudyViewModel(f.book,repository,androidx.lifecycle.SavedStateHandle()))}
  try{
   tap("quick-study");select(f.root);tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("核对后完成的标题");tap("node-title-save")
   compose.waitUntil(10000){vm(f.book).ui.value.unknown};compose.onNodeWithTag("node-title-input").assertTextContains("核对后完成的标题");compose.onNodeWithTag("node-title-cancel").assertIsNotEnabled();shot("unknown-draft")
   fail=false;tap("node-title-save");compose.waitUntil(10000){!vm(f.book).ui.value.unknown&&!vm(f.book).ui.value.busy};compose.onNodeWithTag("node-title-editor").assertDoesNotExist()
   val c=runBlocking{database.study().card(f.card)}!!;assertEquals(2L,c.revision);assertEquals("核对后完成的标题",c.title);assertEquals(f.body,c.body)
  }finally{compose.runOnIdle{compose.activity.viewModelStore.put("study-${f.book}",StudyViewModel(f.book,app.study,androidx.lifecycle.SavedStateHandle()))};database.close()}
 }
 @Test fun branchBadgeAndSourceAreSeparateTargets(){
  val f=fixture();var p=Offset.Zero
  compose.runOnIdle{map().focusNode(f.root);val b=map().nodeBounds(f.root)!!;p=Offset(b.right+12*app.resources.displayMetrics.density,b.centerY())}
  compose.onNodeWithTag("study-map").performTouchInput{click(p)};compose.waitForIdle();compose.runOnIdle{assertTrue(f.root in vm(f.book).collapsedByMap["main"].orEmpty())};compose.onNodeWithTag("study-card-details").assertDoesNotExist()
  select(f.root);tap("node-fold");compose.runOnIdle{assertFalse(f.root in vm(f.book).collapsedByMap["main"].orEmpty())};tap("node-source")
  compose.waitUntil(10000){compose.onAllNodesWithTag("study-open-source").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("study-open-source").assertIsDisplayed();compose.onNodeWithTag("card-full-body").assertDoesNotExist();tap("card-back")
 }

 @Test fun titleRenderingNeverSubstitutesBodyForSecondLine(){
  val text="条件发生后重新计算样本空间并核对事件概率".repeat(4)
  val nodes=listOf(4,12,24,60).mapIndexed{i,n->MapSceneNode(id(),null,null,text.take(n),"不应混入标题的正文",20.0,20.0+i*104,1,1)}
  fun render(items:List<MapSceneNode>)=Bitmap.createBitmap(480,500,Bitmap.Config.ARGB_8888).also{b->val canvas=android.graphics.Canvas(b);canvas.drawColor(android.graphics.Color.WHITE);MapScenePainter.draw(canvas,items,fontScale=1.5f)}
  val a=render(nodes);val b=render(nodes.map{it.copy(body="完全不同且很长的摘要".repeat(20))});assertTrue("Only titles belong inside nodes",a.sameAs(b))
  java.io.File(app.getExternalFilesDir(null),"mui-title-layout.png").outputStream().use{a.compress(Bitmap.CompressFormat.PNG,100,it)};a.recycle();b.recycle()
 }

 @Test fun edgeAffordancesAvoidTitlesAndPrimaryActions(){
  val f=fixture();tap("study-fit-readable")
  var p=Offset.Zero;compose.runOnIdle{val b=map().nodeBounds(f.root)!!;p=Offset(b.centerX(),b.centerY())};compose.onNodeWithTag("study-map").performTouchInput{click(p)};compose.waitForIdle()
  val canvas=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot;val toolbar=compose.onNodeWithTag("node-actions").fetchSemanticsNode().boundsInRoot
  val nodeRects=mutableListOf<android.graphics.RectF>();compose.runOnIdle{vm(f.book).ui.value.nodes.filter{!it.removed}.forEach{n->map().nodeBounds(n.id)?.let{nodeRects+=android.graphics.RectF(it).apply{offset(canvas.left,canvas.top)}}}}
  for(tag in listOf("node-source","node-fold"))for(n in compose.onAllNodesWithTag(tag).fetchSemanticsNodes()){
   val b=n.boundsInRoot;val actual=android.graphics.RectF(b.left,b.top,b.right,b.bottom)
   assertTrue(b.left>=canvas.left&&b.right<=canvas.right);assertFalse(nodeRects.any{android.graphics.RectF.intersects(it,actual)});assertFalse(b.overlaps(toolbar))
  }
  shot("edge-affordances")
 }

}

