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
        compose.onNodeWithTag("beauty-snap").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.75f)}
        compose.onNodeWithTag("beauty-close").performScrollTo().performClick()
        assertEquals(before,compose.onNodeWithTag("ink-surface").fetchSemanticsNode().boundsInRoot)
        val saved=BeautyStore(app).read();assertTrue(saved.enabled&&saved.bold&&!saved.preserveLayout)
        assertEquals(TextFont.SERIF,saved.font);assertEquals(BeautyLanguage.ENGLISH,saved.language)
        assertEquals(36f,saved.size);assertEquals(1.5f,saved.spacing);assertEquals(.75f,saved.snap)
        compose.activityRule.scenario.recreate();compose.waitForIdle();compose.openBeautySettings()
        compose.onNodeWithTag("beauty-enabled").assertIsOn();assertEquals(saved,BeautyStore(app).read())
        compose.onNodeWithTag("beauty-close").performScrollTo().performClick()
        assertTrue(runBlocking{app.inkRepository.read(note.id).strokes.isEmpty()})
    }
    @Test fun pressureWeightsAndLayoutAttractionChangeOnlyTheNewRun(){
        fun stroke(x:Float,p:Float)=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,500f,0,p),InkSample(x+20,540f,20,p)))
        val strokes=listOf(stroke(100f,.1f),stroke(180f,.9f))
        val plain=beautyObject(strokes,"甲乙",BeautyOptions(size=60f,bold=true),false)
        assertTrue(plain.glyphs[0].weight<plain.glyphs[1].weight)
        val zero=beautyObject(strokes,"甲乙",BeautyOptions(size=60f,bold=true,preserveLayout=false,snap=0f),false)
        val half=beautyObject(strokes,"甲乙",BeautyOptions(size=60f,bold=true,preserveLayout=false,snap=.5f),false)
        val full=beautyObject(strokes,"甲乙",BeautyOptions(size=60f,bold=true,preserveLayout=false,snap=1f),false)
        assertEquals(plain.glyphs,zero.glyphs);assertNotEquals(zero.glyphs,full.glyphs)
        for(i in plain.glyphs.indices){assertEquals((zero.glyphs[i].width+full.glyphs[i].width)/2,half.glyphs[i].width,.001f);assertEquals((zero.glyphs[i].y+full.glyphs[i].y)/2,half.glyphs[i].y,.001f)}
        assertEquals(plain,PageObjectCodec.decode(PageObjectCodec.encode(listOf(plain))).single())
    }
}
