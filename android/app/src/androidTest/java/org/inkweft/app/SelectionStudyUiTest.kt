// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic author content. Native selection, rendering and shared UI, not a design mock. */
class SelectionStudyUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun awaitBeauty(){
        var last:Throwable?=null
        try{compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("apply-beautify").assertIsDisplayed().assertIsEnabled()}.onFailure{last=it}.isSuccess}}
        catch(error:Throwable){
            println("BEAUTIFY_CONTROL_FAILURE: $last")
            runCatching{shot("beautify-failure.png")}
            compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.forEach{i->runCatching{println(compose.onAllNodes(isRoot(),useUnmergedTree=true)[i].printToString())}}
            throw error
        }
    }
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun saved(n:Int){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true).assertTextContains("$n 笔",substring=true)}.isSuccess}}
    private fun seed():Pair<Note,InkStroke>{
        ready();val n=runBlocking{app.workspaceRepository.create("学习整合-${id().take(6)}",false,PaperStyle.BLANK)}
        val s=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(200f,600f,0,.5f),InkSample(300f,600.6f,30,.5f),InkSample(400f,600f,60,.5f),InkSample(600f,600f,100,.5f)))
        runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(s)))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};saved(1)
        compose.onNodeWithTag("fit-page").performScrollTo().performClick();return n to s
    }
    private fun nativeCanvas():InkCanvasView{
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun select(left:Double=170.0,top:Double=570.0,right:Double=630.0,bottom:Double=635.0){
        compose.onNodeWithTag("ink-select").performScrollTo().performClick();compose.waitForIdle()
        var a=Offset.Zero;var b=Offset.Zero
        compose.runOnIdle{val v=nativeCanvas();val vp=v.snapshotViewport();val d=v.resources.displayMetrics.density.toDouble();val p=vp.worldToScreen(left,top,v.width.toDouble(),v.height.toDouble(),d);val q=vp.worldToScreen(right,bottom,v.width.toDouble(),v.height.toDouble(),d);a=Offset(p.x.toFloat(),p.y.toFloat());b=Offset(q.x.toFloat(),q.y.toFloat())}
        compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(a,b,300)};compose.waitForIdle()
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun addCard(title:String,body:String){compose.onNodeWithTag("study-card-title").performTextInput(title);compose.onNodeWithTag("study-card-body").performTextInput(body);compose.onNodeWithTag("study-save-card").performClick();compose.waitUntil(15_000){compose.onAllNodesWithTag("study-card-editor").fetchSemanticsNodes().isEmpty()}}
    @Test fun regionEraseCutsOnlyInsideAndUndoRestoresOnRealCanvas(){
        val(n,s)=seed();select(380.0,570.0,420.0,640.0)
        compose.onNodeWithTag("selection-erase-inside").performScrollTo().assertIsEnabled().performClick();saved(1)
        val result=runBlocking{app.inkRepository.read(n.id)};val cut=result.cuts.single().selection.cut
        assertEquals(InkCutShape.RECTANGLE,cut.shape);assertEquals(s.samples,result.strokes.single().stroke.samples)
        compose.runOnIdle{
            val v=InkCanvasView(compose.activity);v.configure(false,PaperStyle.BLANK,null);v.layout(0,0,800,1000);v.showStrokes(InkSession(result).visibleDraft())
            val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);try{v.draw(Canvas(image));val d=v.resources.displayMetrics.density.toDouble();val p=v.snapshotViewport().worldToScreen(400.0,600.0,800.0,1000.0,d);val c=image.getPixel(p.x.toInt(),p.y.toInt());assertTrue(Color.red(c)>220&&Color.green(c)>220&&Color.blue(c)>220)}finally{image.recycle()}
        }
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();saved(1);compose.waitUntil(10_000){runBlocking{app.inkRepository.read(n.id).revision}==3L};assertTrue(InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().cuts.isEmpty())
    }
    @Test fun beautifyPreviewCancelThenApplyIsReversible(){
        val(n,s)=seed();select();compose.onNodeWithTag("selection-beautify").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("beautify-dialog").assertIsDisplayed();awaitBeauty();shot("beautify-preview.png")
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick();assertEquals(1L,runBlocking{app.inkRepository.read(n.id).revision})
        compose.onNodeWithTag("selection-beautify").performScrollTo().performClick();awaitBeauty()
        compose.onNodeWithTag("beautify-strength").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.2f)}
        compose.onNodeWithTag("beautify-strength").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.8f)}
        awaitBeauty();compose.onNodeWithTag("apply-beautify").performClick();saved(1)
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(n.id).revision}==2L}
        assertEquals(InkSelectionEdit.beautify(listOf(s),.8f).single().samples,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().samples)
        assertEquals(2,runBlocking{app.inkRepository.read(n.id).strokes.size});compose.onNodeWithTag("ink-undo").performScrollTo().performClick();saved(1)
        assertEquals(s.samples,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().samples)
    }
    @Test fun selectionDuplicateThenDeleteDoesNotChangeOriginal(){
        val(n,s)=seed();select();shot("selection-actions.png");compose.onNodeWithTag("selection-copy").performScrollTo().performClick();saved(2)
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("selection-delete").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("selection-delete").performScrollTo().performClick();saved(1)
        assertEquals(s.id,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().id)
    }
    @Test fun excerptCreatesSharedCardAndReturnsToSource(){
        val(n,s)=seed();select();compose.onNodeWithTag("selection-excerpt").performScrollTo().performClick()
        addCard("拉格朗日中值定理","先核对连续与可导条件")
        val card=runBlocking{app.study.cards(n.id).first()}.single();val snapshot=runBlocking{app.study.source(card.id)}!!
        assertEquals(s.id,InkPageFile.decode(snapshot.snapshot).strokes.single().id)
        compose.onNodeWithTag("study-card-${card.id}").performClick();compose.onNodeWithTag("study-open-source").performScrollTo().performClick();saved(1)
        compose.onAllNodesWithTag("study-card-details").assertCountEquals(0);assertEquals(n.id,snapshot.pageId)
    }
    @Test fun outlineAndMapReuseSingleEditableCard(){
        val(n,_)=seed();compose.onNodeWithTag("study-open").performClick();compose.onNodeWithTag("study-add-card").performClick();addCard("条件概率","按定义推导")
        val card=runBlocking{app.study.cards(n.id).first()}.single()
        compose.onNodeWithTag("study-card-${card.id}").performClick();compose.onNodeWithTag("study-reuse-card").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.study.nodes(n.id).first().size}==2}
        compose.onNodeWithTag("study-tab-1").performClick();val nodes=runBlocking{app.study.nodes(n.id).first()}
        compose.onNodeWithTag("outline-node-${nodes.first().id}").performClick();compose.onNodeWithTag("study-edit-card").performScrollTo().performClick()
        compose.onNodeWithTag("study-card-title").performTextReplacement("概率公式整理");compose.onNodeWithTag("study-save-card").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("study-card-editor").fetchSemanticsNodes().isEmpty()}
        assertEquals("概率公式整理",runBlocking{app.study.cards(n.id).first().single().title});assertEquals(2,runBlocking{app.study.nodes(n.id).first().count{it.cardId==card.id}})
        shot("study-outline.png");compose.onNodeWithTag("study-tab-2").performClick();compose.onNodeWithTag("study-map").assertIsDisplayed();shot("study-mindmap.png")
    }
    @Test fun childThemeAndRemovingLeafKeepsCard(){
        val(n,_)=seed();compose.onNodeWithTag("study-open").performClick();compose.onNodeWithTag("study-add-card").performClick();addCard("总论","根节点")
        val root=runBlocking{app.study.nodes(n.id).first()}.single();compose.onNodeWithTag("study-tab-1").performClick();compose.onNodeWithTag("outline-node-${root.id}").performClick();compose.onNodeWithTag("study-add-child").performScrollTo().performClick();addCard("必要条件","检查假设")
        val child=runBlocking{app.study.nodes(n.id).first()}.single{it.parentId==root.id}
        compose.onNodeWithTag("outline-node-${child.id}").performClick();compose.onNodeWithTag("study-remove-node").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.study.nodes(n.id).first().single{it.id==child.id}.removed}}
        assertEquals(2,runBlocking{app.study.cards(n.id).first().size})
    }
    @Test fun cardSearchFindsBodyAndClearRestoresCards(){
        val(n,_)=seed();compose.onNodeWithTag("study-open").performClick()
        compose.onNodeWithTag("study-add-card").performClick();addCard("概率","先验条件")
        compose.onNodeWithTag("study-add-card").performClick();addCard("微积分","连续可导")
        val cards=runBlocking{app.study.cards(n.id).first()}
        val probability=cards.single{it.title=="概率"};val calculus=cards.single{it.title=="微积分"}
        compose.onNodeWithTag("study-search").performTextInput("先验")
        compose.onNodeWithTag("study-card-${probability.id}").assertExists()
        compose.onNodeWithTag("study-card-${calculus.id}").assertDoesNotExist()
        compose.onNodeWithTag("study-search").performTextReplacement("未命中")
        compose.onNodeWithText("没有匹配的摘要卡").assertExists()
        compose.onNodeWithTag("study-search").performTextClearance()
        compose.onNodeWithTag("study-card-${calculus.id}").assertExists()
        assertEquals(2,runBlocking{app.study.cards(n.id).first().size})
    }
    @Test fun mindMapPaintStaysInsideItsViewport(){
        compose.runOnIdle{
            val v=MindMapView(compose.activity);v.layout(0,0,300,200)
            val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
            try{
                bitmap.eraseColor(Color.MAGENTA);val canvas=Canvas(bitmap);canvas.translate(50f,50f);v.draw(canvas)
                assertEquals("Map must not cover controls above it",Color.MAGENTA,bitmap.getPixel(100,25))
                assertEquals("Map must not cover controls below it",Color.MAGENTA,bitmap.getPixel(100,275))
                assertEquals(Color.MAGENTA,bitmap.getPixel(25,100))
                assertEquals(Color.MAGENTA,bitmap.getPixel(375,100))
                assertNotEquals(Color.MAGENTA,bitmap.getPixel(100,100))
            }finally{bitmap.recycle()}
        }
    }
    @Test fun previewFailureIsExplicitAndCanRetry()=runBlocking{
        var fail=true
        val preview=BeautifyPreview(emptyList()){_,_->if(fail)throw IllegalStateException("synthetic failure")else emptyList()}
        preview.compute(.5f);assertTrue(preview.state.value.error);assertNull(preview.state.value.strokes)
        fail=false;preview.compute(.5f);assertFalse(preview.state.value.error);assertNotNull(preview.state.value.strokes)
    }
    @Test fun latePreviewCannotReplaceNewerStrength()=runBlocking{
        val release=CompletableDeferred<Unit>()
        val preview=BeautifyPreview(emptyList()){_,strength->if(strength==.2f)release.await();emptyList()}
        val old=launch{preview.compute(.2f)};yield()
        preview.compute(.8f);release.complete(Unit);old.join()
        assertEquals(.8f,preview.state.value.strength);assertNotNull(preview.state.value.strokes)
    }
}
