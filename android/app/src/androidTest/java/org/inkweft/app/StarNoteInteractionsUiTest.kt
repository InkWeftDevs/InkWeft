// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
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
  tap("excerpt-comment-${card.id}");compose.onNodeWithTag("excerpt-comment-input").performTextInput("原文旁的备注");tap("excerpt-comment-save")
  compose.waitUntil(10000){runBlocking{app.study.cards(note.id).first().single()}.body=="原文旁的备注"};ready()
  tap("excerpt-menu-${card.id}");tap("excerpt-delete")
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
  tap("excerpt-inline-comment");compose.onNode(isDialog()).assertDoesNotExist()
  compose.onNodeWithTag("excerpt-inline-input").performTextInput("页面内备注");compose.activityRule.scenario.recreate();compose.waitForIdle()
  compose.onNodeWithTag("excerpt-inline-input").assertTextContains("页面内备注");tap("excerpt-inline-save")
  compose.waitUntil(10000){runBlocking{app.study.cards(note.id).first().single()}.body=="页面内备注"};ready()
  tap("excerpt-resize")
  fun drag(){
   var start=androidx.compose.ui.geometry.Offset.Zero
   compose.runOnIdle{val v=find<ExcerptResizeOverlay>()!!;val b=v.bounds;val p=v.canvasView!!.snapshotViewport().worldToScreen(b.right,b.bottom,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());start=androidx.compose.ui.geometry.Offset(p.x.toFloat(),p.y.toFloat())}
   compose.onNodeWithTag("excerpt-edit-overlay").performTouchInput{swipe(start,start+androidx.compose.ui.geometry.Offset(90f,55f),400)}
  }
  drag();tap("excerpt-resize-cancel");ready()
  assertEquals(original.right,runBlocking{app.study.source(card.id)}!!.right,0.0)
  tap("excerpt-resize");drag();compose.activityRule.scenario.recreate();compose.waitForIdle();shot("v37-excerpt-resize.png");tap("excerpt-resize-save")
  compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first().single()}.revision==3L};ready()
  val resized=runBlocking{app.study.source(card.id)}!!;assertTrue(resized.right>original.right);assertTrue(resized.bottom>original.bottom)
  assertEquals(original.left,resized.left,0.0);assertEquals(original.top,resized.top,0.0)
  compose.onNodeWithContentDescription("取消摘录选择").performClick();compose.activityRule.scenario.recreate();ready()
  assertEquals("页面内备注",runBlocking{app.study.cards(note.id).first().single()}.body)
  assertEquals(resized.right,runBlocking{app.study.source(card.id)}!!.right,0.0)
  assertEquals(ink.samples,runBlocking{app.inkRepository.read(note.id)}.strokes.single().stroke.samples)
  assertEquals(1L,runBlocking{app.inkRepository.read(note.id)}.revision)
 }
 @Test fun pdfTextExcerptKeepsOriginalPictureAndSelectedText(){
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
  tap("top-excerpt");tap("top-excerpt");tap("excerpt-text-mode");tap("capture-destination-inbox");compose.onNodeWithContentDescription("关闭摘要笔").performClick()
  compose.runOnIdle{find<SelectionOverlayView>()!!.onRegion(InkRegion(listOf(EraserPoint(50f,50f),EraserPoint(650f,600f))))}
  compose.waitUntil(15000){compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty()};tap("capture-confirm")
  if(compose.onAllNodesWithTag("capture-confirm").fetchSemanticsNodes().isNotEmpty())tap("capture-confirm")
  compose.waitUntil(15000){runBlocking{app.study.cards(note.id).first()}.size==1};ready()
  val card=runBlocking{app.study.cards(note.id).first().single()};assertTrue(card.body.contains("Extract me"));assertFalse(card.body.contains("Outside"))
  val image=InkPageFile.decode(runBlocking{app.study.source(card.id)}!!.snapshot).objects.single().image
  val raw=java.util.Base64.getDecoder().decode(image);val bitmap=android.graphics.BitmapFactory.decodeByteArray(raw,0,raw.size)
  try{val color=bitmap.getPixel(bitmap.width/3,bitmap.height*2/3);assertTrue(android.graphics.Color.blue(color)>180);assertTrue(android.graphics.Color.red(color)<60)}finally{bitmap.recycle()}
  shot("v36-pdf-excerpt.png")
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
