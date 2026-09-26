package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PaperTemplateUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(15000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun shot(name:String){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}}
    @Test fun createSearchesAndPersistsARealHabitPage(){
        ready();compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-paper-search").performScrollTo().performTextInput("打卡")
        // Finish IME viewport changes before scrolling a lazy-grid result into view.
        compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.forEach {
                it.findFocus()?.clearFocus()
                it.windowInsetsController?.hide(WindowInsets.Type.ime())
            }
        }
        compose.waitUntil(5000) {
            var imeVisible=false
            compose.runOnUiThread {
                imeVisible=WindowInspector.getGlobalWindowViews().any {
                    it.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true
                }
            }
            !imeVisible
        }
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToKey("HABIT")
        compose.onNodeWithTag("template-habit").assertIsDisplayed().performClick();compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(10000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        val note=runBlocking{app.repository.observeNotes().first()}.first()
        assertEquals(PaperStyle.HABIT.ordinal,runBlocking{app.pages.activePages(note.id)}.single().paper)
        compose.onNodeWithTag("fit-page").performScrollTo().performClick();shot("paper-habit-page.png")
    }
    @Test fun changingAndCancellingTemplatePreservesInkAndPageIdentity(){
        ready();val n=runBlocking{app.workspaceRepository.create("纸面验证",false,PaperStyle.GRID)}
        val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff111111.toInt(),3f,InkTool.STYLUS,listOf(InkSample(200f,300f,0,.5f),InkSample(600f,700f,50,.5f)))
        runBlocking{app.inkRepository.save(CommitInk(UUID.randomUUID().toString(),n.id,0,InkMutation.Add(stroke)))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
        compose.waitUntil(10000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        fun picker(){compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("change-paper").performClick()}
        picker();compose.onNodeWithTag("paper-search").performTextInput("会议")
        compose.onNodeWithTag("paper-option-meeting").performScrollTo().performClick();compose.onNodeWithTag("paper-picker-cancel").performClick()
        assertEquals(PaperStyle.GRID.ordinal,runBlocking{app.pages.activePages(n.id)}.single().paper)
        picker();compose.onNodeWithTag("paper-search").performTextInput("每日计划")
        compose.onNodeWithTag("paper-option-daily_planner").performScrollTo().performClick();compose.onNodeWithTag("paper-picker-confirm").performClick()
        compose.waitUntil(10000){runBlocking{app.pages.activePages(n.id)}.single().paper==PaperStyle.DAILY_PLANNER.ordinal}
        assertEquals(stroke.samples,runBlocking{app.inkRepository.read(n.id)}.strokes.single().stroke.samples)
        compose.waitUntil(5000){compose.onAllNodesWithTag("paper-picker").fetchSemanticsNodes().isEmpty()}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000){runCatching{compose.onNodeWithTag("fit-page").assertExists()}.isSuccess}
        compose.waitUntil(5000){compose.onAllNodesWithTag("paper-picker").fetchSemanticsNodes().isEmpty()}
        assertEquals(n.id,runBlocking{app.pages.activePages(n.id)}.single().id)
        compose.onNodeWithTag("fit-page").performScrollTo().performClick();shot("paper-daily-page.png")
    }
    @Test fun renderEveryTemplateWithTheProductionPainter(){
        val bounds=CanvasBounds(0.0,0.0,1000.0,1414.0)
        val atlas=Bitmap.createBitmap(2000,2828,Bitmap.Config.ARGB_8888);val canvas=Canvas(atlas);canvas.drawColor(Color.WHITE)
        try{PaperStyle.entries.forEachIndexed{i,s->
            val image=Bitmap.createBitmap(500,707,Bitmap.Config.ARGB_8888)
            try{val c=Canvas(image);c.drawColor(Color.WHITE);c.scale(.5f,.5f);PaperPainter.draw(c,PaperTemplates.guides(s,bounds,false,.5),.5)
                if(s.ordinal>=10){var nonWhite=0;for(y in 0 until 707 step 5)for(x in 0 until 500 step 5)if(image.getPixel(x,y)!=Color.WHITE)nonWhite++;assertTrue(s.name,nonWhite>100)}
                // Atlas contains the 14 new templates only, with two empty slots.
                if(i>=10)canvas.drawBitmap(image,((i-10)%4)*500f,((i-10)/4)*707f,null)
            }finally{image.recycle()}
        };File(compose.activity.getExternalFilesDir(null),"paper-template-atlas.png").outputStream().use{atlas.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{atlas.recycle()}
    }
}
