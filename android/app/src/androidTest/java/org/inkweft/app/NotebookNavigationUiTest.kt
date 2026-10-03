package org.inkweft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*

class NotebookNavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private var hadFingerWrites=false
    private var originalFingerWrites=false
    @Before fun captureFingerWrites(){
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        hadFingerWrites=prefs.contains("finger-writes");originalFingerWrites=prefs.getBoolean("finger-writes",false)
        assertTrue(prefs.edit().putBoolean("finger-writes",false).commit())
    }
    @After fun restoreFingerWrites(){
        val editor=app.getSharedPreferences("inkweft-editor",0).edit()
        if(hadFingerWrites)editor.putBoolean("finger-writes",originalFingerWrites)else editor.remove("finger-writes")
        assertTrue(editor.commit())
    }
    private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
    private fun open():Note {
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("末页追加与输入模式",false,PaperStyle.RULED)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()};ready();return note
    }
    private fun append(){
        compose.onNodeWithTag("continuous-pages").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)};ready()
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("document-add-page").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.8f),Offset(centerX,height*.15f),650)}
    }
    @Test fun realEditorAppendPersistsOneBlankPageAndRejectsStaleTail(){
        val note=open()
        compose.onNodeWithTag("quick-finger").assertIsDisplayed().assertIsOff()
        val preference=NewPagePaperPreference(app.getSharedPreferences("inkweft-reading",0),note.id)
        assertNull(preference.read());append()
        compose.waitUntil(15000){runBlocking{app.pages.activePages(note.id).size}==2};ready()
        val inherited=runBlocking{app.pages.activePages(note.id)}
        assertEquals(PaperStyle.RULED.ordinal,inherited.last().paper)
        assertTrue(runBlocking{app.inkRepository.read(inherited.last().id).strokes.isEmpty()})
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].appendBlankPage(note.id,PaperStyle.GRID)}
        ready();assertEquals(2,runBlocking{app.pages.activePages(note.id).size})
        compose.onNodeWithTag("quick-settings").performClick()
        compose.onNodeWithTag("settings-new-page-paper").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-grid").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-save").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("new-page-paper-dialog").fetchSemanticsNodes().isEmpty()}
        assertEquals(PaperStyle.GRID,preference.read())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()};ready();append()
        compose.waitUntil(15000){runBlocking{app.pages.activePages(note.id).size}==3};ready()
        val pages=runBlocking{app.pages.activePages(note.id)}
        assertEquals(inherited.map{it.id to it.paper},pages.take(2).map{it.id to it.paper})
        assertEquals(PaperStyle.GRID.ordinal,pages.last().paper)
        assertTrue(runBlocking{app.inkRepository.read(pages.last().id).strokes.isEmpty()})
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].appendBlankPage(inherited.last().id,PaperStyle.BLANK)}
        ready();assertEquals(pages.map{it.id},runBlocking{app.pages.activePages(note.id).map{it.id}})
        compose.onNodeWithTag("quick-finger").performClick();compose.onNodeWithTag("quick-finger").assertIsOn()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(width*.4f,height*.35f),Offset(width*.6f,height*.4f),350)}
        compose.waitUntil(15000){runBlocking{app.inkRepository.read(pages.last().id).strokes.size}==1};ready()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithTag("quick-finger").fetchSemanticsNodes().isNotEmpty()};ready()
        compose.onNodeWithTag("quick-finger").assertIsOn();compose.onNodeWithTag("continuous-pages").assertExists()
        assertEquals(pages.map{it.id},runBlocking{app.pages.activePages(note.id).map{it.id}})
        assertEquals(1,runBlocking{app.inkRepository.read(pages.last().id).strokes.size})
    }
}
