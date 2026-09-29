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
    private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
    private fun open():Note {
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("末页追加与输入模式",false,PaperStyle.RULED)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()};ready();return note
    }
    @Test fun realEditorAppendPersistsOneBlankPageAndRejectsStaleTail(){
        val note=open()
        compose.onNodeWithTag("quick-finger").assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("continuous-pages").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)};ready()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.8f),Offset(centerX,height*.15f),650)}
        compose.waitUntil(15000){runBlocking{app.pages.activePages(note.id).size}==2};ready()
        val pages=runBlocking{app.pages.activePages(note.id)}
        assertEquals(PaperStyle.RULED.ordinal,pages.last().paper)
        assertTrue(runBlocking{app.inkRepository.read(pages.last().id).strokes.isEmpty()})
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].appendBlankPage(note.id)}
        ready();assertEquals(2,runBlocking{app.pages.activePages(note.id).size})
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
