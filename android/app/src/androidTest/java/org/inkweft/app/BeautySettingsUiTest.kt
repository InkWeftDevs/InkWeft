package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class BeautySettingsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private var old:BeautyOptions?=null
    @Before fun prepare(){old=BeautyStore(app).read();BeautyStore(app).save(BeautyOptions(keepInk=false))}
    @After fun restore(){old?.let{BeautyStore(app).save(it)}}
    private fun id()=UUID.randomUUID().toString()
    @Test fun clickOpensCardWithoutTogglingAndParametersPersist(){
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("美化参数卡验收",false,PaperStyle.BLANK)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor()
        val before=compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot
        compose.openBeautySettings();compose.onNodeWithTag("beauty-enabled").assertIsOff()
        compose.onNodeWithTag("beauty-font-size").assertIsNotEnabled()
        compose.onNodeWithTag("beauty-enabled").performClick()
        compose.onNodeWithTag("beauty-font-picker").performClick();compose.onNodeWithTag("font-SERIF").performClick()
        compose.onNodeWithTag("beauty-dynamic-bold").performClick()
        compose.onNodeWithTag("beauty-language-picker").performClick();compose.onNodeWithTag("beauty-language-ENGLISH").performClick()
        compose.onNodeWithTag("beauty-arrange").performClick()
        compose.onNodeWithTag("beauty-font-size").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(36f)}
        compose.onNodeWithTag("beauty-line-spacing").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(1.5f)}
        compose.onNodeWithTag("beauty-snap").assertDoesNotExist()
        compose.onNodeWithTag("beauty-close").performScrollTo().performClick()
        assertEquals(before,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
        val saved=BeautyStore(app).read();assertTrue(saved.enabled&&saved.bold&&!saved.preserveLayout)
        assertEquals(TextFont.SERIF,saved.font);assertEquals(BeautyLanguage.ENGLISH,saved.language)
        assertEquals(36f,saved.size);assertEquals(1.5f,saved.spacing);assertEquals(.5f,saved.snap)
        compose.activityRule.scenario.recreate();compose.waitForIdle();compose.openBeautySettings()
        compose.onNodeWithTag("beauty-enabled").assertIsOn();assertEquals(saved,BeautyStore(app).read())
        compose.onNodeWithTag("beauty-close").performScrollTo().performClick()
        assertTrue(runBlocking{app.inkRepository.read(note.id).strokes.isEmpty()})
    }
    @Test fun fontEntryRemainsReachableFromSavedInkMode(){
        BeautyStore(app).save(BeautyOptions(enabled=true,keepInk=true,font=TextFont.SERIF))
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("美化字体入口回归",false,PaperStyle.BLANK)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.openBeautySettings()
        compose.onNodeWithTag("beauty-font-picker").assertIsDisplayed().performTouchInput{click()}
        compose.onNodeWithTag("font-WENKAI").performTouchInput{click()}
        compose.waitForIdle()
        val saved=BeautyStore(app).read();assertTrue(saved.enabled);assertFalse(saved.keepInk);assertEquals(TextFont.WENKAI,saved.font)
        compose.onNodeWithTag("beauty-replace-font").assertIsSelected()
        compose.onNodeWithTag("beauty-keep-ink").performClick()
        compose.onNodeWithTag("beauty-font-picker").assertIsDisplayed()
        compose.onNodeWithTag("beauty-close").performClick()
        compose.activityRule.scenario.recreate();compose.waitForIdle();compose.openBeautySettings()
        compose.onNodeWithTag("beauty-font-picker").assertIsDisplayed();compose.onNodeWithTag("beauty-keep-ink").assertIsSelected()
    }
    @Test fun pressureWeightsAndLayoutAttractionChangeOnlyTheNewRun(){
        fun stroke(x:Float,p:Float)=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,500f,0,p),InkSample(x+20,540f,20,p)))
        val strokes=listOf(stroke(100f,.1f),stroke(180f,.9f))
        val plain=beautyObject(strokes,"甲乙",BeautyOptions(size=60f,bold=true),false)
        assertTrue(plain.glyphs[0].weight<plain.glyphs[1].weight)
        val small=beautyObject(strokes,"甲乙",BeautyOptions(size=24f,bold=true,preserveLayout=false),false)
        val large=beautyObject(strokes,"甲乙",BeautyOptions(size=48f,bold=true,preserveLayout=false),false)
        assertEquals(24f,small.textRuns.first().size,.01f);assertEquals(48f,large.textRuns.first().size,.01f)
        assertTrue(large.glyphs.first().width>small.glyphs.first().width*1.8f)
        assertEquals(plain,PageObjectCodec.decode(PageObjectCodec.encode(listOf(plain))).single())
    }
}
