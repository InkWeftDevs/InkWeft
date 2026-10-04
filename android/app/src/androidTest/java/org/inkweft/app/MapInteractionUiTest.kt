package org.inkweft.app

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
 private fun tap(tag:String){
  compose.revealAction(tag)
  val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()}
  if(compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("study-list"))).fetchSemanticsNodes().isNotEmpty()){
   // Align with the outer list after nested scrolling or an inline-editor relayout.
   val list=compose.onNodeWithTag("study-list");val viewport=list.fetchSemanticsNode().boundsInRoot
   val target=n.fetchSemanticsNode();val top=target.positionInRoot.y;val bottom=top+target.size.height
   val dy=when{top<viewport.top->top-viewport.top;bottom>viewport.bottom->bottom-viewport.bottom;else->0f}
   if(dy!=0f)list.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,dy)}
  }
  n.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()
 }
 private fun map():MindMapView{val q=java.util.ArrayDeque<View>();q.add(compose.activity.window.decorView);while(q.isNotEmpty()){val v=q.removeFirst();if(v is MindMapView&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)q.add(v.getChildAt(i))};error("Map missing")}
 private fun vm(book:String)=ViewModelProvider(compose.activity)["study-$book",StudyViewModel::class.java]
 private fun shot(name:String){compose.waitForIdle();android.os.SystemClock.sleep(350);val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;java.io.File(app.getExternalFilesDir(null),"mui-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private data class Fixture(val book:String,val root:String,val card:String,val otherMap:String,val body:String)
 /** Lifecycle recreation is only the start: wait for the retained context and its real projection. */
 private fun recreateAndAwaitStudy(f:Fixture,anchor:String,text:String?,phase:String,outline:Boolean=false,unknown:Boolean=false){
  val originalVm=compose.runOnIdle{vm(f.book)}
  val notebookVm=compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java]}
  val expectedMap=compose.runOnIdle{originalVm.mapId.value}
  val expectedTab=if(outline)1 else 2
  compose.runOnIdle{assertEquals(expectedTab,originalVm.lastTab)}
  val evidenceDirectory=app.getExternalFilesDir(null)
  var observed="Awaiting restored study context: $phase"
  try{
   compose.activityRule.scenario.recreate()
   // Room/lifecycle reload and the outline's requestScrollToItem are not covered by Compose idle.
   // Observe the original draft in place; do not scroll, open an editor, or change any state.
   compose.waitUntil("Same retained note/map and original projection after $phase",10_000){
    val ready=compose.runOnIdle{
     val live=compose.activity.viewModelStore.get("study-${f.book}")
     val notebook=notebookVm.ui.value;val study=originalVm.ui.value
     observed="phase=$phase sameVm=${live===originalVm} book=${originalVm.book} selected=${notebook.selectedId} current=${notebook.current?.base?.id} notebookLoading=${notebook.loading} " +
      "map=${originalVm.mapId.value} tab=${originalVm.lastTab} graph=${study.graph?.ref} loading=${study.loading} readFailed=${study.readFailed} busy=${study.busy} unknown=${study.unknown} expectedAnchor=$anchor"
     live===originalVm&&originalVm.book==f.book&&notebook.selectedId==f.book&&notebook.current?.base?.id==f.book&&!notebook.loading&&
      originalVm.mapId.value==expectedMap&&originalVm.lastTab==expectedTab&&study.graph?.ref==MapRef(f.book,expectedMap)&&
      !study.loading&&!study.readFailed&&!study.busy&&study.unknown==unknown
    }
    val inputs=compose.onAllNodesWithTag("node-title-input",useUnmergedTree=true).fetchSemanticsNodes()
    val editors=compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes()
    val input=inputs.singleOrNull()
    val originalPosition=if(outline)hasAnyAncestor(hasTestTag("outline-title-$anchor")) and hasAnyAncestor(hasTestTag("outline-row-$anchor"))
     else hasAnyAncestor(hasTestTag("node-title-editor"))
    observed+=" editors=${editors.size} inputs=${inputs.size} text=${input?.config?.getOrNull(SemanticsProperties.EditableText)} bounds=${input?.boundsInRoot} originalPosition=${input?.let(originalPosition::matches)}"
    val mapReady=if(outline)true else {compose.runOnIdle{
     val canvas=runCatching{map()}.getOrNull();val graph=originalVm.ui.value.graph
     observed+=" canvasBook=${canvas?.captureBook} canvasMap=${canvas?.captureMapKey} canvasGraph=${canvas?.captureGraph} selectedNode=${canvas?.selectedNodeId}"
     canvas!=null&&canvas.isAttachedToWindow&&canvas.width>0&&canvas.height>0&&canvas.captureBook==f.book&&
      canvas.captureMapKey==(expectedMap?:"main")&&canvas.captureGraph==graph?.graphFingerprint&&canvas.nodeBounds(anchor)!=null&&
      (text==null||canvas.selectedNodeId==anchor&&originalVm.selectedByMap[expectedMap?:"main"]==anchor)
    }&&runCatching{compose.onNodeWithTag("study-map").assertIsDisplayed()}.isSuccess}
    val draftReady=if(text==null)inputs.isEmpty()&&editors.isEmpty() else editors.size==1&&input!=null&&originalPosition.matches(input)&&
     SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(text)).matches(input)&&
     runCatching{compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assertIsDisplayed()}.isSuccess
    ready&&mapReady&&draftReady
   }
  }catch(error:Throwable){
   // One fixed diagnostic pair per failing test; record cached state before a direct OS capture.
   runCatching{java.io.File(evidenceDirectory,"mui-outline-recreation-failure.txt").writeText(observed)}.onFailure(error::addSuppressed)
   runCatching{
    val image=checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try{java.io.File(evidenceDirectory,"mui-outline-recreation-failure.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
   }.onFailure(error::addSuppressed)
   error.addSuppressed(AssertionError(observed));throw error
  }
 }
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
  recreateAndAwaitStudy(f,f.root,"尚未提交的名称","map-rename");compose.onNodeWithTag("node-title-input").assertTextContains("尚未提交的名称")
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
 @Test fun outlineTitlesKeepSharedContentAndContinueAtTheSameParent(){
  val f=fixture();val occurrence=id()
  runBlocking{app.study.submit(StudyCommand(id(),f.book,StudyAction.REUSE,cardId=f.card,nodeId=occurrence,parentId=f.root,x=720.0,y=3600.0))}
  compose.waitUntil(10000){vm(f.book).ui.value.nodes.any{it.id==occurrence}}
  fun cards()=runBlocking{app.study.cards(f.book).first()}
  fun nodes()=runBlocking{app.study.nodes(f.book).first()}
  val beforeCards=cards();val beforeNodes=nodes();val beforeSource=runBlocking{app.study.source(f.card)}!!
  val blank=SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(""))
  tap("study-tab-1")
  // Recycle both ends of the long outline before changing selection again.
  // Detached row semantics must not observe the next selection update.
  repeat(2){
   tap("outline-actions-${f.root}")
   compose.onNodeWithTag("outline-row-${f.root}").assertIsSelected()
   compose.onAllNodesWithTag("outline-context-actions").assertCountEquals(1)
   tap("outline-actions-$occurrence")
   compose.onNodeWithTag("outline-row-$occurrence").assertIsSelected()
   compose.onAllNodesWithTag("outline-context-actions").assertCountEquals(1)
  }
  tap("outline-node-$occurrence")
  compose.onNodeWithTag("card-full-body").assertTextEquals(f.body);tap("card-back")
  tap("outline-rename-$occurrence")
  compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assert(hasAnyAncestor(hasTestTag("outline-title-$occurrence")))
   .assert(hasAnyAncestor(hasTestTag("outline-row-$occurrence")))
  compose.onNodeWithTag("outline-title-${f.root}",useUnmergedTree=true).assertDoesNotExist();compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
  compose.onNodeWithTag("study-card-body").assertDoesNotExist()
  compose.onNodeWithTag("node-title-input").performTextReplacement("重建后仍是草稿")
  // Confirm the edit reached Compose before crossing the Activity lifecycle boundary.
  compose.onNodeWithTag("node-title-input").assertTextContains("重建后仍是草稿")
  recreateAndAwaitStudy(f,occurrence,"重建后仍是草稿","outline-rename",outline=true)
  compose.onNodeWithTag("node-title-input").assertTextContains("重建后仍是草稿")
  compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assert(hasAnyAncestor(hasTestTag("outline-title-$occurrence")))
   .assert(hasAnyAncestor(hasTestTag("outline-row-$occurrence")))
  compose.onNodeWithTag("study-close").assertIsNotEnabled().performTouchInput{click()}
  compose.onNodeWithTag("node-title-input").assertTextContains("重建后仍是草稿");tap("node-title-cancel")
  assertEquals(beforeCards,cards());assertEquals(beforeNodes,nodes())
  tap("study-close");tap("quick-study");tap("study-tab-1")
  compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertDoesNotExist()
  tap("outline-rename-$occurrence");compose.onNodeWithTag("node-title-input").performTextReplacement("大纲共享标题");tap("node-title-save")
  compose.waitUntil(10000){cards().single{it.id==f.card}.title=="大纲共享标题"}
  compose.waitUntil(10000){compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
  val updated=cards().single{it.id==f.card};val original=beforeCards.single{it.id==f.card}
  assertEquals(original.copy(title="大纲共享标题",revision=original.revision+1),updated);assertEquals(beforeNodes,nodes())
  val afterSource=runBlocking{app.study.source(f.card)}!!
  assertEquals(beforeSource.copy(snapshot=afterSource.snapshot),afterSource);assertArrayEquals(beforeSource.snapshot,afterSource.snapshot)
  val shared=runBlocking{app.mapGraphs.observe(f.book).first()}.first{it.ref.mapId==f.otherMap}
  assertEquals(updated.title,shared.nodes.single().title);assertEquals(beforeCards.size,cards().size)

  tap("outline-child-$occurrence");compose.onNodeWithTag("node-title-input").performTextInput("大纲子主题")
  compose.onNodeWithTag("node-title-input").assertTextContains("大纲子主题")
  recreateAndAwaitStudy(f,occurrence,"大纲子主题","outline-child",outline=true);compose.onNodeWithTag("node-title-input").assertTextContains("大纲子主题")
  assertEquals(beforeNodes,nodes());assertEquals(beforeCards.size,cards().size);tap("node-title-save")
  compose.waitUntil(10000){nodes().size==beforeNodes.size+1}
  val child=nodes().single{it.id !in beforeNodes.map{n->n.id}};assertEquals(occurrence,child.parentId);assertEquals("大纲子主题",cards().single{it.id==child.cardId}.title)
  compose.waitUntil(10000){compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
  tap("outline-sibling-${child.id}");compose.onNodeWithTag("node-title-input").performTextInput("硬件 Enter 同级")
  // Native key injection must wait until the preceding text edit is applied by Compose.
  compose.onNodeWithTag("node-title-input").assertTextContains("硬件 Enter 同级").assertIsFocused()
  // Synthetic key dispatch covers repeat handling, not a real IME composition session.
  val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();val time=android.os.SystemClock.uptimeMillis()
  // Dispatch through Android to the focused input window.
  listOf(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0),KeyEvent(time,time+40,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,1),KeyEvent(time,time+80,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0),KeyEvent(time,time+100,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0)).forEach{instrumentation.sendKeySync(it)}
  compose.waitUntil(10000){nodes().size==beforeNodes.size+2}
  compose.waitUntil(10000){runCatching{compose.onNodeWithTag("node-title-input").assert(blank)}.isSuccess}
  compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
  val sibling=nodes().single{it.id!=child.id&&it.id !in beforeNodes.map{n->n.id}};assertEquals(child.parentId,sibling.parentId);assertEquals("硬件 Enter 同级",cards().single{it.id==sibling.cardId}.title)
  compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assert(hasAnyAncestor(hasTestTag("outline-title-${sibling.id}")))
   .assert(hasAnyAncestor(hasTestTag("outline-row-${sibling.id}")))
  val afterEnterCards=cards();val afterEnterNodes=nodes();tap("node-title-cancel")
  assertEquals(afterEnterCards,cards());assertEquals(afterEnterNodes,nodes())
  tap("outline-rename-${child.id}");compose.onNodeWithTag("node-title-input").performTextReplacement("软件 Next 子主题")
  compose.onNodeWithTag("node-title-input").performImeAction()
  compose.waitUntil(10000){cards().single{it.id==child.cardId}.title=="软件 Next 子主题"}
  compose.waitUntil(10000){runCatching{compose.onNodeWithTag("node-title-input").assert(blank)}.isSuccess}
  compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
  val afterNextCards=cards();tap("node-title-cancel")
  assertEquals(afterNextCards,cards());assertEquals(afterEnterNodes,nodes());assertEquals(beforeCards.size+2,cards().size)

  val independent=id();val structure=id();val definition=KnowledgeData.MapDefinition("大纲结构图",structures=listOf(MapStructure(structure,null,"结构标题",40.0,80.0)))
  runBlocking{app.knowledge.submit(KnowledgeCommand(id(),f.book,independent,0,definition))}
  compose.runOnIdle{vm(f.book).selectMap(independent)};compose.waitUntil(10000){vm(f.book).ui.value.nodes.any{it.id==structure}}
  tap("outline-rename-$structure");compose.onNodeWithTag("node-title-input").performTextReplacement("原位结构标题");tap("node-title-save")
  compose.waitUntil(10000){runBlocking{app.knowledge.observeBook(f.book).first()}.any{it.id==independent&&(it.data() as KnowledgeData.MapDefinition).structures.single().title=="原位结构标题"}}
  val renamed=runBlocking{app.knowledge.observeBook(f.book).first()}.single{it.id==independent}.data() as KnowledgeData.MapDefinition
  assertEquals(definition.copy(structures=listOf(definition.structures.single().copy(title="原位结构标题"))),renamed)
  compose.waitUntil(10000){compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
  assertEquals(afterNextCards,cards());assertEquals(afterEnterNodes,nodes())
 }
 @Test fun outlineEnterUnknownReceiptRetriesOneCommandBeforeOpeningOneSiblingDraft(){
  val f=fixture();tap("study-close");val database=NoteDatabase.open(app);val fail=java.util.concurrent.atomic.AtomicBoolean(true)
  val repository=StudyRepository(database){if(it==StudyFault.BEFORE_RECEIPT&&fail.get())throw java.io.IOException("test receipt unavailable")}
  val saved=androidx.lifecycle.SavedStateHandle();lateinit var originalVm:StudyViewModel
  fun cards()=runBlocking{database.study().cards(f.book)}
  fun nodes()=runBlocking{database.study().nodes(f.book)}
  fun receipts()=database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM study_receipts WHERE notebookId=?",arrayOf(f.book)).use{it.moveToFirst();it.getLong(0)}
  val beforeCards=cards();val beforeNodes=nodes();val beforeReceipts=receipts()
  compose.runOnIdle{originalVm=StudyViewModel(f.book,repository,saved);compose.activity.viewModelStore.put("study-${f.book}",originalVm)}
  val application=app
  try{
   tap("quick-study");tap("study-tab-1");tap("outline-child-${f.root}")
   compose.onNodeWithTag("node-title-input").performTextInput("回执核对后的唯一子主题");compose.onNodeWithTag("node-title-input").performImeAction()
   compose.waitUntil(10000){originalVm.ui.value.unknown}
   val command=compose.runOnIdle{ArrayList(checkNotNull(saved.get<ArrayList<String>>("study.command")))}
   assertEquals(StudyAction.CREATE.name,command[2]);assertEquals(f.root,command[6])
   compose.onNodeWithTag("node-title-input").assertTextContains("回执核对后的唯一子主题")
   compose.onNodeWithTag("node-title-cancel").assertIsNotEnabled();compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
   assertEquals(beforeCards,cards());assertEquals(beforeNodes,nodes());assertEquals(beforeReceipts,receipts())
   recreateAndAwaitStudy(f,f.root,"回执核对后的唯一子主题","unknown-recreate",outline=true,unknown=true)
   compose.runOnIdle{assertSame(originalVm,vm(f.book));assertEquals(command,saved.get<ArrayList<String>>("study.command"))}
   compose.onNodeWithTag("node-title-input").assertTextContains("回执核对后的唯一子主题")
   tap("node-title-save");compose.waitUntil(10000){!originalVm.ui.value.busy&&originalVm.ui.value.unknown}
   compose.runOnIdle{assertEquals(command,saved.get<ArrayList<String>>("study.command"))};assertEquals(beforeReceipts,receipts())
   fail.set(false);tap("node-title-save")
   compose.waitUntil(10000){!originalVm.ui.value.busy&&!originalVm.ui.value.unknown&&nodes().size==beforeNodes.size+1}
   val blank=SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(""))
   compose.waitUntil(10000){runCatching{compose.onNodeWithTag("node-title-input").assert(blank)}.isSuccess}
   compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
   compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assert(hasAnyAncestor(hasTestTag("outline-title-${command[4]}")))
    .assert(hasAnyAncestor(hasTestTag("outline-row-${command[4]}")))
   val created=nodes().single{it.id==command[4]};assertEquals(f.root,created.parentId);assertEquals(command[3],created.cardId)
   assertEquals(beforeCards.size+1,cards().size);val card=cards().single{it.id==created.cardId};assertEquals(1L,card.revision);assertEquals(command[7],card.title)
   assertEquals(beforeReceipts+1,receipts());assertEquals(created.cardId,runBlocking{database.study().receipt(command[0])}!!.resultId)
   compose.runOnIdle{assertNull(saved.get<ArrayList<String>>("study.command"))}
   val afterCards=cards();val afterNodes=nodes()
   recreateAndAwaitStudy(f,created.id,"","sibling-recreate",outline=true)
   compose.onNodeWithTag("node-title-input").assert(blank)
   compose.onNodeWithTag("node-title-input",useUnmergedTree=true).assert(hasAnyAncestor(hasTestTag("outline-title-${created.id}")))
    .assert(hasAnyAncestor(hasTestTag("outline-row-${created.id}")))
   compose.runOnIdle{assertSame(originalVm,vm(f.book));assertNull(saved.get<ArrayList<String>>("study.command"))}
   compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1);tap("node-title-cancel")
   compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertDoesNotExist()
   assertEquals(afterCards,cards());assertEquals(afterNodes,nodes());assertEquals(beforeReceipts+1,receipts())
  }finally{
   androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{
    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
     .filterIsInstance<MainActivity>().singleOrNull()?.viewModelStore?.put("study-${f.book}",StudyViewModel(f.book,application.study,androidx.lifecycle.SavedStateHandle()))
   }
   database.close()
  }
 }
 @Test fun narrowLargeTextKeepsActionsDraftAndCanvas(){
  val f=fixture();val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(c:String)=automation.executeShellCommand(c).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()}
  fun readOverride(c:String)=Regex("Override (?:size|density):\\s*(\\S+)").find(shell(c))?.groupValues?.get(1)
  val size=readOverride("wm size");val density=readOverride("wm density")
  val fontScale=shell("settings get system font_scale");val hardwareIme=shell("settings get secure show_ime_with_hard_keyboard")
  fun restoreSetting(namespace:String,key:String,value:String){shell(if(value=="null"||value.isBlank())"settings delete $namespace $key"else"settings put $namespace $key $value")}
  try{
   shell("wm size 750x1600");shell("wm density 320");shell("settings put system font_scale 1.6");shell("settings put secure show_ime_with_hard_keyboard 1")
   recreateAndAwaitStudy(f,f.root,null,"narrow-map");select(f.root);shot("narrow-selected")
   val actions=compose.onNodeWithTag("node-actions").fetchSemanticsNode().boundsInRoot;val canvas=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot
   assertTrue(actions.left>=canvas.left&&actions.right<=canvas.right)
   tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("窄窗中文草稿")
   compose.onNodeWithTag("node-title-input").performTouchInput{click()}
   var priorLayout:List<Any>?=null;var stableSince=0L
   compose.waitUntil(8000){
    val insets=compose.activity.window.decorView.rootWindowInsets
    val imeBottom=insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom?:0
    val mapBounds=compose.onNodeWithTag("study-map").fetchSemanticsNode().boundsInRoot
    val footer=listOf("node-title-input","node-title-cancel","node-title-save").map{compose.onAllNodesWithTag(it).fetchSemanticsNodes().singleOrNull()?:return@waitUntil false}
    val full=footer.map{n->androidx.compose.ui.geometry.Rect(n.positionInRoot.x,n.positionInRoot.y,n.positionInRoot.x+n.size.width,n.positionInRoot.y+n.size.height)}
    val clipped=footer.map{it.boundsInRoot};val layout=listOf(imeBottom,mapBounds)+full+clipped
    val now=android.os.SystemClock.uptimeMillis()
    if(layout!=priorLayout){priorLayout=layout;stableSince=now}
    val minimum=48*compose.activity.resources.displayMetrics.density
    insets?.isVisible(android.view.WindowInsets.Type.ime())==true&&imeBottom>150&&now-stableSince>=350&&full.indices.all{i->
     val b=full[i];val visible=clipped[i]
     b.width>=minimum&&b.height>=minimum&&b.left>=mapBounds.left&&b.top>=mapBounds.top&&b.right<=mapBounds.right&&b.bottom<=mapBounds.bottom&&
      kotlin.math.abs(b.left-visible.left)<1&&kotlin.math.abs(b.top-visible.top)<1&&kotlin.math.abs(b.right-visible.right)<1&&kotlin.math.abs(b.bottom-visible.bottom)<1
    }
   }
   compose.onNodeWithTag("node-title-save").assertIsDisplayed();compose.onNodeWithTag("node-title-cancel").assertIsDisplayed();shot("narrow-keyboard")
   recreateAndAwaitStudy(f,f.root,"窄窗中文草稿","narrow-draft");compose.onNodeWithTag("node-title-input").assertTextContains("窄窗中文草稿");tap("node-title-cancel")
  }finally{shell("wm size ${size?:"reset"}");shell("wm density ${density?:"reset"}");restoreSetting("system","font_scale",fontScale);restoreSetting("secure","show_ime_with_hard_keyboard",hardwareIme)}
 }
 @Test fun microMoveAndCancelledDragDoNotMoveNode(){
  val f=fixture();select(f.root);val before=runBlocking{app.study.nodes(f.book).first()}.first{it.id==f.root};var p=Offset.Zero
  compose.runOnIdle{val b=map().nodeBounds(f.root)!!;p=Offset(b.centerX(),b.centerY())}
  compose.onNodeWithTag("study-map").performTouchInput{advanceEventTime(500);down(p);moveTo(p+Offset(1f,1f));up()};compose.waitForIdle()
  assertEquals(before,runBlocking{app.study.nodes(f.book).first()}.first{it.id==f.root});compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertDoesNotExist()
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
   fail=false;tap("node-title-save");compose.waitUntil(10000){!vm(f.book).ui.value.unknown&&!vm(f.book).ui.value.busy};compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertDoesNotExist()
   val c=runBlocking{database.study().card(f.card)}!!;assertEquals(2L,c.revision);assertEquals("核对后完成的标题",c.title);assertEquals(f.body,c.body)
  }finally{compose.runOnIdle{compose.activity.viewModelStore.put("study-${f.book}",StudyViewModel(f.book,app.study,androidx.lifecycle.SavedStateHandle()))};database.close()}
 }
 @Test fun branchBadgeAndSourceAreSeparateTargets(){
  val f=fixture();var p=Offset.Zero
  compose.runOnIdle{map().focusNode(f.root);val b=map().nodeBounds(f.root)!!;p=Offset(b.right+12*app.resources.displayMetrics.density,b.centerY())}
  compose.onNodeWithTag("study-map").performTouchInput{click(p)};compose.waitForIdle();compose.runOnIdle{assertTrue(f.root in vm(f.book).collapsedByMap["main"].orEmpty())};compose.onNodeWithTag("study-card-details").assertDoesNotExist()
  select(f.root);tap("node-fold");compose.runOnIdle{assertFalse(f.root in vm(f.book).collapsedByMap["main"].orEmpty())};tap("node-source")
  compose.waitUntil(10000){compose.onAllNodesWithTag("study-open-source").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("study-open-source").assertIsDisplayed();compose.onNodeWithTag("card-full-body").assertDoesNotExist()
  val beforeCards=runBlocking{app.study.cards(f.book).first()};val beforeNodes=runBlocking{app.study.nodes(f.book).first()};val beforeSource=runBlocking{app.study.source(f.card)}!!
  tap("study-view-snapshot");compose.waitUntil(10000){compose.onAllNodesWithTag("study-snapshot-canvas").fetchSemanticsNodes().isNotEmpty()}
  tap("study-snapshot-zoom-in");tap("study-snapshot-zoom-out");tap("study-snapshot-fit")
  compose.onNodeWithTag("study-snapshot-canvas").performTouchInput{swipe(Offset(width*.4f,height*.4f),Offset(width*.6f,height*.6f))}
  tap("study-snapshot-close");compose.onNodeWithTag("study-card-details").assertIsDisplayed();compose.onNodeWithTag("study-snapshot-viewer").assertDoesNotExist()
  assertEquals(beforeCards,runBlocking{app.study.cards(f.book).first()});assertEquals(beforeNodes,runBlocking{app.study.nodes(f.book).first()})
  val afterSource=runBlocking{app.study.source(f.card)}!!;assertEquals(beforeSource.copy(snapshot=afterSource.snapshot),afterSource);assertArrayEquals(beforeSource.snapshot,afterSource.snapshot)
  tap("card-back")
 }

 @Test fun titleAndSummaryRenderInSeparateMeasuredAreas(){
  val text="条件发生后重新计算样本空间并核对事件概率".repeat(4)
  val node=MapSceneNode(id(),null,id(),text,"正文保持独立：重新确定样本空间，再核对分母。".repeat(4),20.0,20.0,1,1)
  val layout=MapNodeMetrics.measure(node.title,node.body,fontScale=1.6f)
  fun render(body:String)=Bitmap.createBitmap(280,(layout.height+40).toInt(),Bitmap.Config.ARGB_8888).also{b->val canvas=android.graphics.Canvas(b);canvas.drawColor(android.graphics.Color.WHITE);MapScenePainter.draw(canvas,listOf(node.copy(body=body)),fontScale=1.6f)}
  val a=render(node.body);val b=render("完全不同且很长的摘要".repeat(20))
  try{
   assertFalse("The summary is visible inside the content node",a.sameAs(b))
   assertTrue(layout.title.lineCount<=2);assertTrue(checkNotNull(layout.summary).lineCount<=3)
   assertTrue(layout.summaryTop>=layout.titleTop+layout.title.height+8)
   for(y in (20+layout.titleTop).toInt() until (20+layout.titleTop+layout.title.height).toInt())for(x in 34 until 238)assertEquals("Summary must not replace title pixels",a.getPixel(x,y),b.getPixel(x,y))
   java.io.File(app.getExternalFilesDir(null),"mui-title-layout.png").outputStream().use{a.compress(Bitmap.CompressFormat.PNG,100,it)}
  }finally{a.recycle();b.recycle()}
  val excerpt="摘录首行\n第二段也要显示"
  val derived=MapNodeMetrics.measure(excerpt.lineSequence().first().take(48),excerpt)
  assertNull(derived.summary);assertEquals(excerpt,derived.title.text.toString())
  assertTrue((0 until derived.title.lineCount).all{derived.title.getEllipsisCount(it)==0})
  val renamed=MapNodeMetrics.measure("自己概括的标题",excerpt)
  assertEquals("自己概括的标题",renamed.title.text.toString());assertEquals(excerpt,checkNotNull(renamed.summary).text.toString())
  val longExcerpt=(1..10).joinToString("\n"){"第${it}行：摘录内容"}
  val excerptTitle=longExcerpt.lineSequence().first()
  val compactExcerpt=MapNodeMetrics.measure(excerptTitle,longExcerpt)
  val expandedExcerpt=MapNodeMetrics.measure(excerptTitle,longExcerpt,expanded=true)
  assertNull(compactExcerpt.summary);assertNull(expandedExcerpt.summary)
  assertEquals(4,compactExcerpt.title.lineCount);assertEquals(8,expandedExcerpt.title.lineCount)
  assertTrue(compactExcerpt.title.getEllipsisCount(compactExcerpt.title.lineCount-1)>0)
  assertTrue(expandedExcerpt.title.getEllipsisCount(expandedExcerpt.title.lineCount-1)>0)
  assertTrue(compactExcerpt.canExpand);assertTrue(expandedExcerpt.height>compactExcerpt.height)
  val thumbnail=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
  try{
   thumbnail.eraseColor(android.graphics.Color.WHITE)
   android.graphics.Canvas(thumbnail).drawRect(20f,30f,100f,45f,android.graphics.Paint().apply{color=android.graphics.Color.BLACK})
   val original=thumbnail.copy(Bitmap.Config.ARGB_8888,false)
   try{assertEquals(android.graphics.Rect(18,28,102,47),previewContentBounds(thumbnail));assertTrue(thumbnail.sameAs(original))}finally{original.recycle()}
   thumbnail.setPixel(0,0,android.graphics.Color.BLUE);assertEquals(android.graphics.Rect(0,0,120,80),previewContentBounds(thumbnail))
   thumbnail.eraseColor(android.graphics.Color.TRANSPARENT)
   android.graphics.Canvas(thumbnail).drawRect(20f,30f,100f,45f,android.graphics.Paint().apply{color=android.graphics.Color.BLACK})
   assertEquals(android.graphics.Rect(18,28,102,47),previewContentBounds(thumbnail))
  }finally{thumbnail.recycle()}
 }

 @Test fun expandedExcerptUsesMeasuredBoundsAndNewPlacementKeepsGap(){
  compose.runOnIdle{
   val book=id();val parent=MapSceneNode(id(),null,null,"条件概率", "",40.0,80.0,1,1)
   val child=MapSceneNode(id(),parent.id,id(),"已知事件发生之后重新定义样本空间并检查每一步的条件".repeat(3),"正文摘要：条件改变后需要重新计算分母，保留例题推导与复习问题。".repeat(12),312.0,80.0,1,1,"原迹摘录")
   val source=MapSourceInfo("概率学习笔记 · 第 2 页")
   val compact=MapNodeMetrics.measure(child.title,child.body,source,1.6f)
   val expanded=MapNodeMetrics.measure(child.title,child.body,source,1.6f,expanded=true)
   assertTrue(expanded.height>compact.height);assertTrue(expanded.canExpand);assertNotNull(expanded.preview)
   val wide=MapNodeMetrics.measure("横向摘录","",MapSourceInfo("第 2 页",CanvasBounds(0.0,0.0,480.0,40.0)))
   val tall=MapNodeMetrics.measure("竖向摘录","",MapSourceInfo("第 2 页",CanvasBounds(0.0,0.0,40.0,480.0)))
   assertTrue(wide.width>MapNodeMetrics.WIDTH);assertTrue(checkNotNull(wide.preview).height()<=40f)
   assertTrue(checkNotNull(tall.preview).height()<=160f);assertTrue(wide.width>tall.width)
   val next=captureDestinationPosition(MapScene(MapRef(book),"复习",listOf(parent,child),"test"),parent.id,1.6f)
   assertTrue(next.y>=child.y+compact.height+32);assertTrue(next.x>=parent.x+MapNodeMetrics.WIDTH+40)
   val firstBox=android.graphics.RectF(child.x.toFloat(),child.y.toFloat(),child.x.toFloat()+compact.width,child.y.toFloat()+compact.height)
   val nextBox=android.graphics.RectF(next.x.toFloat(),next.y.toFloat(),next.x.toFloat()+compact.width,next.y.toFloat()+compact.height)
   assertFalse(android.graphics.RectF.intersects(firstBox,nextBox))
   val config=android.content.res.Configuration(app.resources.configuration).apply{fontScale=1.6f}
   val view=MindMapView(app.createConfigurationContext(config));view.layout(0,0,2000,2400)
   val rows=listOf(parent,child).map{StudyNodeRow(it.id,book,it.cardId?:it.id,it.parentId,it.x,it.y)}
   view.show(rows,listOf(StudyCardRow(parent.id,book,1,parent.title,parent.body),StudyCardRow(child.cardId!!,book,1,child.title,child.body)),sources=mapOf(child.cardId!! to source),structuralCardIds=setOf(parent.id))
   view.restoreViewport(MapViewport(1f,0f,0f))
   val density=view.resources.displayMetrics.density;val before=checkNotNull(view.nodeBounds(child.id))
   assertEquals(compact.height*density,before.height(),.01f)
   view.expandedNodeId=child.id;val after=checkNotNull(view.nodeBounds(child.id))
   assertEquals(expanded.height*density,after.height(),.01f);assertEquals(before.left,after.left,0f);assertEquals(before.top,after.top,0f)
   var selected:String?=null;view.onSelect={selected=it?.id}
   val at=android.os.SystemClock.uptimeMillis();val x=after.centerX();val y=after.bottom-8*density
   listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEachIndexed{i,action->android.view.MotionEvent.obtain(at,at+i*20,action,x,y,0).also{view.onTouchEvent(it);it.recycle()}}
   assertEquals(child.id,selected);assertTrue(view.canExpandNode(child.id))
   view.expandedNodeId=null;assertEquals(before,view.nodeBounds(child.id));assertEquals(312.0,rows[1].x,0.0);assertEquals(80.0,rows[1].y,0.0)
  }
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

