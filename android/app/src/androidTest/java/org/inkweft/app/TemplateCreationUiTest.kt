package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.zip.*
import org.inkweft.core.*

class TemplateCreationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun install(){
        val json="""{"format":"inkweft.resource-pack.v1","id":"example.creation","title":"原创学习模板","author":"InkWeft","version":1,"resources":[{"id":"paper","title":"课堂提纲","type":"paper","paper":"CORNELL"},{"id":"map","title":"章节复盘","type":"map","layout":"right","nodes":[{"title":"要点","parent":null,"x":40,"y":80},{"title":"依据","parent":0,"x":300,"y":80}]}]}"""
        val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry()}}.toByteArray()
        runBlocking{app.resourcePacks.install(ResourcePackCodec.inspect(bytes))}
    }
    private fun shot(name:String){compose.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"v45-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun choose(){compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToIndex(1);compose.onNodeWithText("我的模板").performScrollTo().performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("installed-paper-paper").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("installed-paper-paper").performScrollTo().performClick()}
    private fun chooseInsert(){
        compose.onNodeWithText("我的模板").onParent().performScrollTo()
        compose.onNodeWithText("我的模板").performScrollTo().performClick()
        try{compose.waitUntil(10000){compose.onAllNodesWithTag("insert-installed-paper").fetchSemanticsNodes().isNotEmpty()}}
        catch(t:Throwable){shot("insert-failure");File(app.getExternalFilesDir(null),"insert-failure.txt").writeText(compose.onNodeWithTag("insert-pages-dialog",useUnmergedTree=true).printToString());throw t}
        compose.onNodeWithTag("insert-installed-paper").onParent().performScrollTo()
        compose.onNodeWithTag("insert-installed-paper").performScrollTo().performClick()
    }
    @Test fun installedTemplatesAreUsableInExistingCreationFlows(){
        install();compose.waitUntil(10000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("new-note").performClick();choose();shot("new-template");compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking{app.repository.observeNotes().first()}.isEmpty())
        compose.onNodeWithTag("new-note").performClick();choose();compose.onNodeWithTag("create-note").performClick();compose.singlePageEditor();compose.waitForSavedInk()
        val note=runBlocking{app.repository.observeNotes().first()}.single()
        assertEquals(PaperStyle.CORNELL.ordinal,runBlocking{app.pages.activePages(note.id)}.single().paper)
        compose.openEditorAction("add-page");chooseInsert();shot("insert-template");compose.onNodeWithText("取消").performClick()
        assertEquals(1,runBlocking{app.pages.activePages(note.id)}.size)
        compose.openEditorAction("add-page");chooseInsert();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithTag("confirm-insert-pages").performClick()
        compose.waitUntil(10000){runBlocking{app.pages.activePages(note.id)}.size==3};compose.waitForSavedInk()
        compose.onNodeWithTag("quick-study").performClick();compose.revealAction("study-new-map");compose.onNodeWithTag("study-new-map").performClick();compose.onNodeWithTag("installed-map-map").performScrollTo().performClick();compose.onNodeWithTag("study-new-map-save").performClick()
        try{compose.waitUntil(10000){runBlocking{app.mapGraphs.read(note.id)}.any{it.nodes.size==2}}}catch(t:Throwable){
            shot("map-failure");File(app.getExternalFilesDir(null),"template-map-failure.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString()+"\n"+runBlocking{app.mapGraphs.read(note.id)}.toString());throw t
        }
        assertEquals(1,runBlocking{app.repository.observeNotes().first()}.size);shot("map-template")
    }
}
