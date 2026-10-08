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
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class FormulaEditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun capture(name:String){
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(app.filesDir,"formula-test").apply{mkdirs()}.resolve(name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun tapFormula(o:PageObject){
        var point=Offset.Zero
        compose.runOnIdle{
            fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
            val view=checkNotNull(find(compose.activity.window.decorView));val p=view.snapshotViewport().worldToScreen((o.x+o.width/2).toDouble(),(o.y+o.height/2).toDouble(),view.width.toDouble(),view.height.toDouble(),view.resources.displayMetrics.density.toDouble())
            point=Offset(p.x.toFloat(),p.y.toFloat())
        }
        compose.onNodeWithTag("ink-surface").performTouchInput{click(point)}
        compose.onNodeWithTag("object-edit-formula").assertIsDisplayed().performClick()
    }
    @Test fun formulaModeAndDirectEditingSurviveReopen(){
        val old=BeautyStore(app).read()
        try{
            compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
            val note=runBlocking{app.workspaceRepository.create("公式编辑界面回归",false,PaperStyle.BLANK)}
            val formula=PageObject(id(),PageObjectKind.FORMULA,x=100f,y=400f,width=340f,height=180f,fontSize=28f,text="\\frac{1}{x^2}=\\alpha")
            runBlocking{app.pageObjects.save(note.id,0,id(),listOf(formula))}
            compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
            compose.singlePageEditor();compose.waitForSavedInk()
            compose.onNodeWithTag("toolbar-more").performClick();compose.onNodeWithTag("quick-beauty").performScrollTo().performClick()
            compose.onNodeWithTag("beauty-formula").performScrollTo().performClick().assertIsSelected()
            assertTrue(BeautyStore(app).read().formula);capture("formula-mode.png")
            compose.onNodeWithTag("beauty-close").performClick();compose.frameCanvasFixture()
            tapFormula(formula)
            compose.onNodeWithTag("formula-preview").assertIsDisplayed();capture("formula-editor-before.png")
            val corrected="\\frac{1}{\\sin\\alpha}=\\csc\\alpha"
            compose.onNodeWithTag("formula-source").performTextReplacement(corrected)
            compose.onNodeWithTag("formula-save").assertIsEnabled().performClick()
            compose.waitUntil(15_000){runBlocking{app.pageObjects.read(note.id).objects.single().text==corrected}&&app.navigationReady.value}
            compose.activityRule.scenario.recreate();compose.waitForSavedInk();compose.frameCanvasFixture()
            val saved=runBlocking{app.pageObjects.read(note.id).objects.single()};assertEquals(corrected,saved.text)
            tapFormula(saved);compose.onNodeWithTag("formula-source").assertTextContains(corrected);compose.onNodeWithTag("formula-preview").assertIsDisplayed();capture("formula-editor-reopened.png")
            assertTrue(BeautyStore(app).read().formula);assertTrue(runBlocking{app.inkRepository.read(note.id).strokes.isEmpty()})
        }finally{BeautyStore(app).save(old)}
    }
}
