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
    private fun saved(){compose.waitForSavedInk()}
    private fun open(note:Note){compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();saved()}
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun canvas():InkCanvasView{fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};return checkNotNull(find(compose.activity.window.decorView))}

    @Test fun highlighterQuickPresetsKeepTransparencyAndSurviveReopen(){
        ready();val note=runBlocking{app.workspaceRepository.create("荧光快捷项验收",false,PaperStyle.BLANK)};open(note)
        compose.onNodeWithTag("pen-kind-highlighter").performClick()
        val store=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+note.id)
        for(i in 0..4){
            compose.openCurrentPen();compose.revealAction("pen-color-$i");compose.onNodeWithTag("pen-color-$i").performScrollTo().performClick();compose.closePenSettings()
            compose.waitUntil(10_000){store.readColors()[2]==PenWidthStore.colors(2)[i]}
            assertEquals(0x66,store.readColors()[2] ushr 24)
        }
        for(i in 0..2){
            compose.openCurrentPen();compose.revealAction("width-preset-$i");compose.onNodeWithTag("width-preset-$i").performScrollTo().performClick();compose.closePenSettings()
            compose.waitUntil(10_000){store.read()[2]==PenWidthStore.presets(2)[i]}
        }
        shot("redesign-highlighter.png");compose.activityRule.scenario.recreate();saved()
        assertEquals(34f,store.read()[2],0f);assertEquals(PenWidthStore.colors(2)[4],store.readColors()[2])
        compose.openEditorAction("quick-finger")
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

    @Test fun beautySettingsStayCompactAndDoNotMovePaper(){
        ready();val note=runBlocking{app.workspaceRepository.create("美化设置验收",false,PaperStyle.GRID)};open(note)
        compose.onNodeWithTag("auto-beauty-toggle").performScrollTo().assertExists()
        val before=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
        compose.openBeautySettings();compose.onNodeWithTag("beauty-replace-font").performScrollTo().performClick()
        compose.onNodeWithTag("beauty-font-picker").assertIsDisplayed();compose.onNodeWithTag("beauty-select").performScrollTo().assertIsDisplayed()
        shot("redesign-font-panel.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle();assertEquals(before,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
        assertTrue(runBlocking{app.pageObjects.read(note.id).objects.isEmpty()})
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
        compose.assertCurrentPage("第 2 / 2 页")
        compose.onNodeWithTag("book-search").performClick();compose.onNodeWithTag("search-correct-page").performClick()
        compose.onNodeWithText("校对手写识别").assertIsDisplayed();try{compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("page-search-text").assertIsEnabled().assertTextContains("细胞",substring=true)}.isSuccess}}catch(t:Throwable){
            shot("redesign-correction-failure.png");println("CORRECTION_PAGE="+page+" ROW="+runBlocking{app.pages.searchText(page)})
            compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.forEach{i->println(compose.onAllNodes(isRoot(),useUnmergedTree=true)[i].printToString())};throw t
        }
    }
}
