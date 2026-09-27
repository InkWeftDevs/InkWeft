// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class EditorRedesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun saved(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已保存",substring=true)}.isSuccess}}
    private fun open(note:Note){compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};saved()}
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun canvas():InkCanvasView{fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};return checkNotNull(find(compose.activity.window.decorView))}

    @Test fun highlighterQuickPresetsKeepTransparencyAndSurviveReopen(){
        ready();val note=runBlocking{app.workspaceRepository.create("荧光快捷项验收",false,PaperStyle.BLANK)};open(note)
        compose.onNodeWithTag("ink-tool-2").performClick()
        val store=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+note.id)
        for(i in 0..4){
            compose.onNodeWithTag("quick-color-$i").performScrollTo().performClick()
            compose.waitUntil(10_000){store.readColors()[2]==PenWidthStore.colors(2)[i]}
            assertEquals(0x66,store.readColors()[2] ushr 24)
        }
        for(i in 0..2){
            compose.onNodeWithTag("quick-width-$i").performScrollTo().performClick()
            compose.waitUntil(10_000){store.read()[2]==PenWidthStore.presets(2)[i]}
        }
        shot("redesign-highlighter.png");compose.activityRule.scenario.recreate();saved()
        assertEquals(34f,store.read()[2],0f);assertEquals(PenWidthStore.colors(2)[4],store.readColors()[2])
        compose.onNodeWithTag("ink-more").performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.3f),Offset(width*.6f,height*.3f),300)}
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(note.id).strokes.size}==1}
        val stroke=runBlocking{app.inkRepository.read(note.id).strokes.single().stroke}
        assertEquals(InkPen.HIGHLIGHTER,stroke.pen);assertEquals(0x66,stroke.color ushr 24);assertEquals(34f,stroke.width,0f)
    }

    @Test fun importKeepsFileChoiceVisibleAndFormatHelpOptional(){
        ready();compose.onNodeWithText("导入文档 / 副本").performClick()
        compose.onNodeWithTag("choose-import-file").assertIsDisplayed();compose.onNodeWithTag("import-format-details").assertDoesNotExist();shot("redesign-import-default.png")
        compose.onNodeWithTag("import-format-help").performClick();compose.onNodeWithTag("import-format-details").assertIsDisplayed();compose.onNodeWithTag("choose-import-file").assertIsDisplayed();shot("redesign-import-expanded.png")
    }

    @Test fun beautyIsVisibleBeforeSelectionAndCancelKeepsInkAndPaperPosition(){
        ready();val note=runBlocking{app.workspaceRepository.create("界面验收 · 手写与美化",false,PaperStyle.GRID)}
        val stroke=InkStroke(id(),InkPen.PEN,0xff24342f.toInt(),4f,InkTool.STYLUS,listOf(InkSample(200f,600f,0),InkSample(400f,600f,30)))
        runBlocking{app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(stroke)))};open(note)
        compose.onNodeWithTag("ink-beauty").assertIsDisplayed();compose.onNodeWithTag("book-search").assertIsDisplayed()
        compose.onNodeWithTag("fit-page").performScrollTo().performClick();val before=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
        for(tag in listOf("ink-select","page-objects","ink-tool-3","ink-beauty")){
            compose.onNodeWithTag(tag).performClick();compose.waitForIdle()
            assertEquals(before,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithTag("beauty-mode-font").assertIsDisplayed();shot("redesign-beauty-entry.png")
        var a=Offset.Zero;var b=Offset.Zero
        compose.runOnIdle{val v=canvas();val d=v.resources.displayMetrics.density.toDouble();fun point(x:Double,y:Double):Offset{val p=v.snapshotViewport().worldToScreen(x,y,v.width.toDouble(),v.height.toDouble(),d);return Offset(p.x.toFloat(),p.y.toFloat())};a=point(170.0,560.0);b=point(440.0,640.0)}
        compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(a,b,300)}
        compose.onNodeWithTag("beauty-edit-text").performClick()
        compose.waitUntil(30_000){runCatching{compose.onNodeWithTag("beauty-recognized-text").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("font-WENKAI").assertIsDisplayed();shot("redesign-font-panel.png")
        compose.onNodeWithText("保留原样").performClick();saved()
        assertEquals(1L,runBlocking{app.inkRepository.read(note.id).revision});assertTrue(runBlocking{app.pageObjects.read(note.id).objects.isEmpty()})
        assertEquals(before,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun searchAutomaticallyPreparesTextAndResultOpensTheRightPage(){
        ready();val note=runBlocking{app.workspaceRepository.create("界面验收 · 查找笔记",false,PaperStyle.BLANK)}
        val page=id();runBlocking{app.pages.addAfter(note.id,note.id,page);assertTrue(app.pages.saveSearchText(page,0,"生物课程 细胞结构",method="MANUAL"))};open(note)
        compose.onNodeWithTag("book-search").performClick();compose.onNodeWithTag("book-search-query").performTextInput("细胞")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("book-search-hit-$page").assertIsDisplayed()}.isSuccess}
        // Blank page is prepared automatically, while a corrected page must not be overwritten.
        compose.waitUntil(15_000){runBlocking{app.pages.searchText(note.id)}!=null}
        assertEquals("MANUAL",runBlocking{app.pages.searchText(page)}!!.method)
        shot("redesign-search-results.png");compose.onNodeWithTag("book-search-hit-$page").performClick();saved()
        compose.onNodeWithTag("page-counter").assertTextContains("第 2 / 2 页")
        compose.onNodeWithTag("book-search").performClick();compose.onNodeWithTag("search-correct-page").performClick()
        compose.onNodeWithText("校对手写识别").assertIsDisplayed();try{compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("page-search-text").assertIsEnabled().assertTextContains("细胞",substring=true)}.isSuccess}}catch(t:Throwable){
            shot("redesign-correction-failure.png");println("CORRECTION_PAGE="+page+" ROW="+runBlocking{app.pages.searchText(page)})
            compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.forEach{i->println(compose.onAllNodes(isRoot(),useUnmergedTree=true)[i].printToString())};throw t
        }
    }
}
