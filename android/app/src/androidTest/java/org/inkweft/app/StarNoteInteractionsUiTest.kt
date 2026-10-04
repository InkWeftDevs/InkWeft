// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class StarNoteInteractionsUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun shot(name:String){
  val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
  java.io.File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
 private fun create():Note{
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("交互验收",false,PaperStyle.BLANK)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
  compose.singlePageEditor();ready();return note
 }
 private fun tap(tag:String){compose.revealAction(tag);val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick();compose.waitForIdle()}
 private inline fun<reified T:View> find():T?{
  val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
  while(queue.isNotEmpty()){val v=queue.removeFirst();if(v is T&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)queue.add(v.getChildAt(i))};return null
 }
 private fun saveExcerptComment(book:String,card:String,text:String){
  val repository=app.knowledge
  val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
  val evidenceDirectory=instrumentation.targetContext.getExternalFilesDir(null)
  var writer:KnowledgeViewModel?=null;var stage="open-editor";var cachedUi="not yet ready";var cachedIme="not inspected"
  val deadline=android.os.SystemClock.elapsedRealtime()+10_000
  fun await(condition:()->Boolean){
   val remaining=deadline-android.os.SystemClock.elapsedRealtime()
   check(remaining>0){"Comment's original 10-second budget expired at $stage"};compose.waitUntil(remaining,condition)
  }
  try{
   val open=compose.onNodeWithTag("excerpt-comment-$card")
   open.performScrollTo();await{runCatching{open.assertIsDisplayed().assertIsEnabled()}.isSuccess};open.performTouchInput{click()}
   stage="load-input"
   val input=compose.onNodeWithTag("excerpt-comment-input")
   await{runCatching{input.assertExists().assertIsEnabled()}.isSuccess}
   writer=compose.runOnIdle{ViewModelProvider(compose.activity)["card-presentation-$book-$card",KnowledgeViewModel::class.java]}
   val owner=checkNotNull(writer)
   input.performScrollTo();input.assertIsDisplayed().assertIsEnabled().performTextInput(text)
   input.assertTextContains(text,substring=false)
   cachedUi="INPUT_AFTER_TYPING\n"+input.printToString().take(1_000)
   stage="settle-ime"
   // This is the same real-window IME boundary used by the card-presentation palette regression.
   val roots=compose.runOnIdle{WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.isShown}}
   compose.runOnIdle{roots.forEach{root->root.findFocus()?.clearFocus();ViewCompat.getWindowInsetsController(root)?.hide(WindowInsetsCompat.Type.ime())}}
   await{compose.runOnIdle{
    val states=roots.map{ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())}
    cachedIme=states.toString();states.all{it==false}
   }}
   stage="save-once"
   input.assertTextContains(text,substring=false)
   val save=compose.onNodeWithTag("excerpt-comment-save")
   save.performScrollTo();await{runCatching{save.assertIsDisplayed().assertIsEnabled()}.isSuccess}
   val button=save.fetchSemanticsNode()
   assertTrue("Comment Save must be fully inside the visible scroll area",button.boundsInRoot.width>=button.size.width-1&&button.boundsInRoot.height>=button.size.height-1)
   cachedUi="READY_BEFORE_SAVE\n"+input.printToString().take(1_000)+"\n"+save.printToString().take(1_000)
   save.performTouchInput{click()}
   stage="persist-comment"
   await{
    val state=owner.ui.value
    check(!state.unknown&&state.rejectedOperation==null){"Comment not confirmed: ${state.message}"}
    check(state.busy||state.message==null||state.message=="已保存"){"Comment not accepted: ${state.message}"}
    // KnowledgeViewModel.rows only comes from Room's observe() collector, never from the local editor draft.
    state.rows.cardPresentations()[card]?.annotation==text
   }
   val remaining=deadline-android.os.SystemClock.elapsedRealtime();check(remaining>0){"Comment persistence read exceeded the original budget"}
   val persisted=runBlocking{withTimeout(remaining){repository.observeBook(book).first()}}
    .filter{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==card}.single()
   assertEquals("One save creates one presentation revision",1L,persisted.revision)
   assertEquals(text,(persisted.data() as KnowledgeData.CardPresentation).annotation)
   await{compose.onAllNodesWithTag("excerpt-comment-input").fetchSemanticsNodes().isEmpty()}
  }catch(error:Throwable){
   // Do not await a stuck Compose/Room pipeline while collecting the original failure.
   runCatching{
    val status=runCatching{
     val state=writer?.ui?.value
     "stage=$stage ime=$cachedIme loading=${state?.loading} readFailed=${state?.readFailed} busy=${state?.busy} unknown=${state?.unknown} pending=${writer?.pendingOperationId} completed=${state?.completedOperation} rejected=${state?.rejectedOperation} message=${state?.message} persisted=${state?.rows?.cardPresentations()?.get(card)?.annotation}"
    }.getOrElse{"writer diagnostics unavailable: ${it.javaClass.simpleName}"}
    val diagnostic=("EXCERPT_COMMENT_FAILURE $status\n$cachedUi").take(4_000)
    runCatching{println(diagnostic)}
    runCatching{java.io.File(checkNotNull(evidenceDirectory),"excerpt-comment-failure.txt").writeText(diagnostic)}
    runCatching{
     val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
     try{java.io.File(checkNotNull(evidenceDirectory),"excerpt-comment-failure.png").outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}
     finally{bitmap.recycle()}
    }.onFailure{runCatching{println("Comment screenshot unavailable: ${it.javaClass.simpleName}")}}
   }.onFailure{runCatching{println("Comment diagnostics unavailable: ${it.javaClass.simpleName}")}}
   throw error
  }
 }
 @Test fun firstTapSelectsSecondTapAnchorsWithoutDimAndCustomizationDoesNotMoveRows(){
  create();val bounds=compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
  tap("ink-select");compose.onNodeWithTag("selection-settings").assertDoesNotExist();compose.onNodeWithTag("selection-context-menu").assertDoesNotExist()
  tap("ink-select");compose.onNodeWithTag("selection-settings").assertIsDisplayed();shot("v36-lasso.png")
  compose.onNode(isDialog()).assertDoesNotExist();compose.onNodeWithContentDescription("关闭套索").performClick()
  assertEquals(bounds,compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot)
  tap("top-excerpt");compose.onNodeWithTag("excerpt-settings").assertDoesNotExist();compose.onNodeWithTag("excerpt-pen").assertDoesNotExist()
  tap("top-excerpt");compose.onNodeWithTag("excerpt-settings").assertIsDisplayed();compose.onNode(isDialog()).assertDoesNotExist()
  compose.onNodeWithContentDescription("关闭摘要笔").performClick();tap("toolbar-customize")
  val toggle=compose.onNodeWithTag("toolbar-visible-eraser");toggle.performScrollTo();val row=compose.onNodeWithTag("toolbar-row-eraser").fetchSemanticsNode().boundsInRoot
  toggle.performClick();assertEquals(row,compose.onNodeWithTag("toolbar-row-eraser").fetchSemanticsNode().boundsInRoot)
  toggle.performClick();assertEquals(row,compose.onNodeWithTag("toolbar-row-eraser").fetchSemanticsNode().boundsInRoot);tap("toolbar-done")
 }
 @Test fun excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource(){
  val note=create();compose.frameCanvasFixture()
  compose.runOnIdle{find<InkCanvasView>()!!.onStroke(InkStroke(id(),InkPen.BALLPOINT,0xff2464bb.toInt(),6f,InkTool.STYLUS,listOf(InkSample(200f,220f,0),InkSample(400f,250f,80))))}
  ready();compose.waitUntil(15000){runBlocking{app.inkRepository.read(note.id)}.strokes.size==1}
  compose.waitUntil(15000){var pending=true;compose.runOnIdle{pending=find<InkCanvasView>()!!.rasterPending};!pending}
  compose.selectInboxCapture()
  compose.runOnIdle{find<SelectionOverlayView>()!!.onRegion(InkRegion(listOf(EraserPoint(150f,180f),EraserPoint(450f,290f))))}
  compose.waitUntil(15000){compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty()};tap("capture-confirm")
  try{compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first()}.size==1}}catch(e:Throwable){
   compose.onRoot(useUnmergedTree=true).printToLog("ExcerptFailure")
   val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
   java.io.File(compose.activity.getExternalFilesDir(null),"excerpt-failure.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle();throw e
  };ready()
  val card=runBlocking{app.study.cards(note.id).first().single()}
  assertTrue(runBlocking{app.study.nodes(note.id).first()}.isEmpty())
  val source=runBlocking{app.study.source(card.id)}!!;assertTrue(InkPageFile.decode(source.snapshot).objects.single().image.isNotBlank())
  compose.onNodeWithTag("study-panel").assertDoesNotExist();compose.onNodeWithTag("excerpt-panel").assertIsDisplayed();shot("v36-excerpt.png")
  saveExcerptComment(note.id,card.id,"原文旁的备注");ready()
  tap("excerpt-menu-${card.id}");tap("excerpt-delete")
  compose.waitUntil(10000){runCatching{compose.onNodeWithTag("card-trash-confirm").assertIsEnabled()}.isSuccess}
  tap("card-trash-cancel");assertNull(runBlocking{app.study.cards(note.id).first().single()}.trashedAt)
  tap("excerpt-menu-${card.id}");tap("excerpt-delete")
  compose.waitUntil(10000){runCatching{compose.onNodeWithTag("card-trash-confirm").assertIsEnabled()}.isSuccess}
  tap("card-trash-confirm")
  compose.waitUntil(10000){runBlocking{app.study.cards(note.id).first().single()}.trashedAt!=null}
  assertEquals(1,runBlocking{app.inkRepository.read(note.id)}.strokes.size)
 }
 @Test fun inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk(){
  val note=create();compose.frameCanvasFixture()
  val ink=InkStroke(id(),InkPen.BALLPOINT,0xff2464bb.toInt(),6f,InkTool.STYLUS,listOf(InkSample(200f,220f,0),InkSample(400f,250f,80)))
  compose.runOnIdle{find<InkCanvasView>()!!.onStroke(ink)};ready()
  compose.waitUntil(15000){runBlocking{app.inkRepository.read(note.id)}.strokes.size==1}
  compose.waitUntil(15000){var pending=true;compose.runOnIdle{pending=find<InkCanvasView>()!!.rasterPending};!pending}
  compose.selectInboxCapture();compose.runOnIdle{find<SelectionOverlayView>()!!.onRegion(InkRegion(listOf(EraserPoint(150f,180f),EraserPoint(450f,290f))))}
  if(compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty())tap("capture-confirm")
  compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first()}.size==1};ready()
  val card=runBlocking{app.study.cards(note.id).first().single()};val original=runBlocking{app.study.source(card.id)}!!
  compose.onNodeWithContentDescription("关闭摘录").performClick()
  compose.runOnIdle{find<SelectionOverlayView>()!!.onTap(200f,220f)}
  tap("excerpt-inline-delete")
  compose.waitUntil(10000){runCatching{compose.onNodeWithTag("card-trash-confirm").assertIsEnabled()}.isSuccess}
  tap("card-trash-cancel");assertNull(runBlocking{app.study.cards(note.id).first().single()}.trashedAt)
  assertArrayEquals(original.snapshot,runBlocking{app.study.source(card.id)}!!.snapshot)
  tap("excerpt-inline-comment");compose.onNode(isDialog()).assertDoesNotExist()
  val inlineDraft="页面内备注"
  val inlineInput=compose.onNodeWithTag("excerpt-inline-input")
  inlineInput.performTextInput(inlineDraft);inlineInput.assertIsDisplayed().assertTextContains(inlineDraft,substring=false)
  val beforeRecreate=inlineInput.printToString().take(1_200)
  val (bookModel,pageModel,annotationModel)=compose.runOnIdle{val provider=ViewModelProvider(compose.activity)
   Triple(provider[NotebookViewModel::class.java],provider["book-${note.id}",BookPagesViewModel::class.java],
    provider["card-presentation-${note.id}-${card.id}",KnowledgeViewModel::class.java])}
  val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
  val evidenceDirectory=instrumentation.targetContext.getExternalFilesDir(null)
  var recreateStage="recreate";var lastInputCheck="not checked"
  try{
   compose.activityRule.scenario.recreate();recreateStage="await-original-page-and-inline-draft"
   // Saved draft identity/text survives; the page and source Flow still have to remount the actual editor.
   compose.waitUntil(10_000){
    val bookState=bookModel.ui.value;val pageState=pageModel.ui.value
    if(bookState.loading||bookState.readFailed||bookState.selectedId!=note.id||pageState.loading||
     pageState.error!=null||pageState.selectedId!=original.pageId)false
    else runCatching{
     compose.onNodeWithTag("ink-surface").assertIsDisplayed()
     compose.onNodeWithTag("excerpt-edit-overlay").assertIsDisplayed()
     compose.onNodeWithTag("excerpt-inline-input").assertIsDisplayed().assertIsEnabled().assertTextContains(inlineDraft,substring=false)
    }.onFailure{lastInputCheck=(it.message?:it.javaClass.simpleName).take(700)}.isSuccess
   }
  }catch(error:Throwable){
   // Cached models/text only: a restoration failure must not trigger another Compose or Room wait.
   runCatching{
    val state=annotationModel.ui.value
    val diagnostic=("INLINE_RECREATE_FAILURE stage=$recreateStage expectedBook=${note.id} expectedPage=${original.pageId} book=${bookModel.ui.value.selectedId} bookLoading=${bookModel.ui.value.loading} page=${pageModel.ui.value.selectedId} pageLoading=${pageModel.ui.value.loading} pageError=${pageModel.ui.value.error} annotationLoading=${state.loading} annotationReadFailed=${state.readFailed} busy=${state.busy} unknown=${state.unknown} message=${state.message}\nlastInputCheck=$lastInputCheck\nBEFORE_RECREATE\n$beforeRecreate").take(3_000)
    runCatching{println(diagnostic)}
    runCatching{java.io.File(checkNotNull(evidenceDirectory),"inline-recreate-failure.txt").writeText(diagnostic)}
    runCatching{
     val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
     try{java.io.File(checkNotNull(evidenceDirectory),"inline-recreate-failure.png").outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}
     finally{bitmap.recycle()}
    }.onFailure{runCatching{println("Inline restoration screenshot unavailable: ${it.javaClass.simpleName}")}}
   }.onFailure{runCatching{println("Inline restoration diagnostics unavailable: ${it.javaClass.simpleName}")}}
   throw error
  }
  compose.onNodeWithTag("excerpt-inline-input").assertTextContains(inlineDraft,substring=false);tap("excerpt-inline-save")
  compose.waitUntil(10000){runBlocking{app.knowledge.observeBook(note.id).first().cardPresentations()[card.id]?.annotation}=="页面内备注"};ready()
  tap("excerpt-resize")
  fun drag(){
   var start=androidx.compose.ui.geometry.Offset.Zero
   compose.runOnIdle{val v=find<ExcerptResizeOverlay>()!!;val b=v.bounds;val p=v.canvasView!!.snapshotViewport().worldToScreen(b.right,b.bottom,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());start=androidx.compose.ui.geometry.Offset(p.x.toFloat(),p.y.toFloat())}
   compose.onNodeWithTag("excerpt-edit-overlay").performTouchInput{swipe(start,start+androidx.compose.ui.geometry.Offset(90f,55f),400)}
  }
  fun resizeControls(){
   compose.onNodeWithTag("selection-context-menu").assertIsDisplayed()
   listOf("excerpt-resize-cancel","excerpt-resize-save").forEach{compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()}
  }
  fun touchResize(tag:String){resizeControls();compose.onNodeWithTag(tag).performTouchInput{click()};compose.waitForIdle()}
  fun assertOriginalSource(){
   val unchanged=runBlocking{app.study.source(card.id)}!!
   // Compare all source metadata separately from the snapshot's byte contents.
   assertEquals(original,unchanged.copy(snapshot=original.snapshot));assertArrayEquals(original.snapshot,unchanged.snapshot)
   assertEquals(card.revision,runBlocking{app.study.cards(note.id).first().single()}.revision)
   compose.onNodeWithTag("excerpt-resize").assertIsDisplayed().assertIsEnabled()
   compose.onNodeWithTag("excerpt-resize-save").assertDoesNotExist()
  }
  drag();touchResize("excerpt-resize-cancel");ready();assertOriginalSource()
  tap("excerpt-resize");drag();resizeControls()
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
  ready();assertOriginalSource()
  tap("excerpt-resize");drag();compose.activityRule.scenario.recreate();compose.waitForIdle()
  compose.waitUntil(5_000){runCatching{resizeControls()}.isSuccess}
  shot("v37-excerpt-resize.png");touchResize("excerpt-resize-save")
  compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first().single()}.revision==2L};ready()
  val resized=runBlocking{app.study.source(card.id)}!!;assertTrue(resized.right>original.right);assertTrue(resized.bottom>original.bottom)
  assertEquals(original.left,resized.left,0.0);assertEquals(original.top,resized.top,0.0)
  compose.onNodeWithContentDescription("取消摘录选择").performClick();compose.activityRule.scenario.recreate();ready()
  assertEquals("页面内备注",runBlocking{app.knowledge.observeBook(note.id).first().cardPresentations()[card.id]?.annotation});assertEquals(card.body,runBlocking{app.study.cards(note.id).first().single()}.body)
  assertEquals(resized.right,runBlocking{app.study.source(card.id)}!!.right,0.0)
  assertEquals(ink.samples,runBlocking{app.inkRepository.read(note.id)}.strokes.single().stroke.samples)
  assertEquals(1L,runBlocking{app.inkRepository.read(note.id)}.revision)
 }
 @Test fun pdfTextExcerptKeepsOriginalPictureAndSelectedText(){
  val prefs=app.getSharedPreferences("inkweft-excerpts",0)
  val original=listOf("text","to-map").associateWith{prefs.all[it] as? Boolean}
  try{
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val pdf=android.graphics.pdf.PdfDocument();val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,1414,1).create())
  page.canvas.drawText("Extract me",100f,160f,android.graphics.Paint().apply{textSize=36f;color=android.graphics.Color.BLACK})
  page.canvas.drawText("Outside",100f,900f,android.graphics.Paint().apply{textSize=36f})
  page.canvas.drawRect(100f,300f,500f,500f,android.graphics.Paint().apply{color=android.graphics.Color.BLUE});pdf.finishPage(page)
  val bytes=java.io.ByteArrayOutputStream().also(pdf::writeTo).toByteArray();pdf.close()
  val prepared=ContentTransfer.document("PDF摘录验收",PdfDocumentSource(bytes,1),ContentTransfer.hash(bytes))
  val note=runBlocking{app.libraryContent.import(ImportNotebook(id(),id(),prepared.sha256),prepared)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();ready();compose.frameCanvasFixture()
  compose.waitUntil(15000){var loaded=false;compose.runOnIdle{loaded=find<InkCanvasView>()!!.inputReady};loaded}
  tap("top-excerpt");tap("top-excerpt");tap("excerpt-region-mode");tap("capture-destination-inbox");compose.onNodeWithContentDescription("关闭摘要笔").performClick()
  // Cancel before the PDF worker returns: its old result must not resurrect the selection.
  compose.runOnIdle{find<SelectionOverlayView>()!!.let{overlay->
   overlay.onRegion(InkRegion(listOf(EraserPoint(50f,50f),EraserPoint(650f,600f))))
   overlay.onTap(800f,1100f)
  }}
  compose.waitForIdle();ready();compose.onNodeWithTag("capture-confirm").assertDoesNotExist()
  compose.runOnIdle{find<SelectionOverlayView>()!!.onRegion(InkRegion(listOf(EraserPoint(50f,50f),EraserPoint(650f,600f))))}
  compose.waitUntil(15000){compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty()};tap("capture-confirm")
  if(compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty())tap("capture-confirm")
  compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first()}.size==1};ready()
  val card=runBlocking{app.study.cards(note.id).first().single()};assertTrue(card.body.contains("Extract me"));assertFalse(card.body.contains("Outside"))
  val image=InkPageFile.decode(runBlocking{app.study.source(card.id)}!!.snapshot).objects.single().image
  val raw=java.util.Base64.getDecoder().decode(image);val bitmap=android.graphics.BitmapFactory.decodeByteArray(raw,0,raw.size)
  try{val color=bitmap.getPixel(bitmap.width/3,bitmap.height*2/3);assertTrue(android.graphics.Color.blue(color)>180);assertTrue(android.graphics.Color.red(color)<60)}finally{bitmap.recycle()}
  shot("v36-pdf-excerpt.png")
  }finally{prefs.edit().apply{original.forEach{(key,value)->if(value==null)remove(key)else putBoolean(key,value)}}.commit()}
 }
 @Test fun splitSwitchEditingUsesThePageShownInReferencePane(){
  val note=create();val second=runBlocking{app.pages.addAfter(note.id,note.id,id())}
  tap("tabs-list");tap("tabs-actions-${note.id}");tap("tab-split-horizontal")
  compose.onNodeWithText("下一页").performClick();tap("split-edit")
  compose.waitUntil(10000){var selected:String?=null;compose.runOnIdle{selected=ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].ui.value.selectedId};selected==second.id}
  assertEquals(second.id,runBlocking{app.workspaceRepository.get(note.id)}.selectedPageId)
 }
 @Test fun notebookListPinsActionsAndOffersBothSplitDirections(){
  val note=create();val notes=runBlocking{(1..14).map{app.workspaceRepository.create("分屏测试$it",false,PaperStyle.BLANK)}}
  compose.runOnIdle{val vm=ViewModelProvider(compose.activity)[NotebookViewModel::class.java];notes.forEach(vm::select);vm.select(note)};ready();tap("tabs-list")
  val fixed=compose.onNodeWithTag("tabs-hide").fetchSemanticsNode().boundsInRoot
  val targetIndex=compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.openIds.indexOf(notes.last().id)}
  assertTrue(targetIndex>=14)
  compose.onNodeWithTag("tabs-scroll").performScrollToIndex(targetIndex)
  compose.waitUntil(15000){compose.onAllNodesWithTag("tabs-actions-${notes.last().id}").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(fixed,compose.onNodeWithTag("tabs-hide").fetchSemanticsNode().boundsInRoot)
  tap("tabs-actions-${notes.last().id}");tap("tab-split-horizontal")
  compose.onNodeWithTag("reference-pane").assertIsDisplayed()
  val a=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot;val b=compose.onNodeWithTag("reference-pane").fetchSemanticsNode().boundsInRoot
  assertTrue(b.left>=a.right);tap("split-rotate")
  val c=compose.onNodeWithTag("reference-pane").fetchSemanticsNode().boundsInRoot;val d=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
  assertTrue(c.top>=d.bottom);shot("v36-split.png");tap("split-close");tap("tabs-list");tap("tabs-hide")
  compose.onNodeWithTag("notebook-tab-${note.id}").assertDoesNotExist();tap("tabs-list");tap("tabs-hide");compose.onNodeWithTag("notebook-tab-${note.id}").assertExists()
 }
}
