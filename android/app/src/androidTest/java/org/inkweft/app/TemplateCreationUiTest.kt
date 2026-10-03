package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.zip.*
import java.util.UUID
import org.inkweft.core.*

class TemplateCreationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val fixtureId=UUID.randomUUID().toString()
    private val paperId="paper-$fixtureId"
    private val mapId="map-$fixtureId"
    private val defaultKeys=setOf("world","paper","cover")
    private var originalDefaults:Map<String,Any?> = emptyMap()
    @Before fun preserveCreationDefaults(){originalDefaults=app.getSharedPreferences("inkweft-new-notebook",0).all.filterKeys{it in defaultKeys}}
    @After fun restoreCreationDefaults(){
        val edit=app.getSharedPreferences("inkweft-new-notebook",0).edit()
        defaultKeys.forEach{edit.remove(it)}
        originalDefaults.forEach{(key,value)->when(value){
            is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value)
            is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        }}
        assertTrue(edit.commit())
    }
    private fun install():TemplateRef{
        val json="""{"format":"inkweft.resource-pack.v1","id":"example.creation.$fixtureId","title":"原创学习模板","author":"InkWeft","version":1,"resources":[{"id":"$paperId","title":"课堂提纲","type":"paper","paper":"CORNELL"},{"id":"$mapId","title":"章节复盘","type":"map","layout":"right","nodes":[{"title":"要点","parent":null,"x":40,"y":80},{"title":"依据","parent":0,"x":300,"y":80}]}]}"""
        val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry()}}.toByteArray()
        val pack=ResourcePackCodec.inspect(bytes)
        runBlocking{app.resourcePacks.install(pack)}
        return TemplateRef(pack.hash,paperId)
    }
    private fun shot(name:String){compose.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"v45-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun choose(){compose.onNodeWithTag("create-page").performClick();compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToIndex(1);compose.onNodeWithText("我的模板").performScrollTo().performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("installed-paper-$paperId").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("installed-paper-$paperId").performScrollTo().performClick()}
    private fun chooseInsert(){
        compose.onNodeWithText("我的模板").onParent().performScrollTo()
        compose.onNodeWithText("我的模板").performScrollTo().performClick()
        try{compose.waitUntil(10000){compose.onAllNodesWithTag("insert-installed-$paperId").fetchSemanticsNodes().isNotEmpty()}}
        catch(t:Throwable){shot("insert-failure");File(app.getExternalFilesDir(null),"insert-failure.txt").writeText(compose.onNodeWithTag("insert-pages-dialog",useUnmergedTree=true).printToString());throw t}
        compose.onNodeWithTag("insert-installed-$paperId").onParent().performScrollTo()
        compose.onNodeWithTag("insert-installed-$paperId").performScrollTo().performClick()
    }
    @Test fun installedTemplatesAreUsableInExistingCreationFlows(){
        val originalIds=runBlocking{app.repository.observeNotes().first()}.map{it.id}.toSet()
        val template=install();compose.waitUntil(10000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("new-note").performClick();choose();shot("new-template");compose.onNodeWithText("取消").performClick()
        assertEquals(originalIds,runBlocking{app.repository.observeNotes().first()}.map{it.id}.toSet())
        compose.onNodeWithTag("new-note").performClick();choose();compose.onNodeWithTag("create-note").performClick();compose.singlePageEditor();compose.waitForSavedInk()
        val note=runBlocking{app.repository.observeNotes().first()}.single{it.id !in originalIds}
        assertEquals(PaperStyle.CORNELL.ordinal,runBlocking{app.pages.activePages(note.id)}.single().paper)
        compose.openEditorAction("add-page");chooseInsert();shot("insert-template");compose.onNodeWithText("取消").performClick()
        assertEquals(1,runBlocking{app.pages.activePages(note.id)}.size)
        val preference=NewPagePaperPreference(app.getSharedPreferences("inkweft-reading",0),note.id)
        assertTrue(runBlocking{preference.save(PaperStyle.BLANK)})
        val insertion=SavedStateHandle()
        compose.runOnIdle{compose.activity.viewModelStore.put("book-${note.id}",BookPagesViewModel(note.id,app.pages,app.workspaceRepository,insertion,app.resourcePacks))}
        compose.activityRule.scenario.recreate();compose.singlePageEditor();compose.waitForSavedInk()
        compose.openEditorAction("add-page")
        compose.onNodeWithTag("insert-preview").performScrollTo().assertTextContains(PaperTemplates.title(PaperStyle.BLANK),substring=true)
        var insertOperation:String?=null
        compose.runOnIdle{insertion.getLiveData<ArrayList<String>?>("insert.operation").observe(compose.activity){fields->fields?.firstOrNull()?.let{insertOperation=it}}}
        chooseInsert();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithTag("confirm-insert-pages").performClick()
        compose.waitUntil(10000){runBlocking{app.pages.activePages(note.id)}.size==3};compose.waitForSavedInk()
        assertEquals(arrayListOf(template.hash,template.id),compose.runOnIdle{insertion.get<ArrayList<String>>("insert.template")})
        val operation=compose.runOnIdle{checkNotNull(insertOperation)}
        assertTrue(runBlocking{app.resourceTemplates.hasReceipt(operation,template.hash)})
        assertTrue(runBlocking{app.pages.activePages(note.id)}.all{it.paper==PaperStyle.CORNELL.ordinal})
        assertEquals(PaperStyle.BLANK,preference.read())
        compose.onNodeWithTag("quick-study").performClick();compose.revealAction("study-new-map");compose.onNodeWithTag("study-new-map").performClick();compose.onNodeWithTag("installed-map-$mapId").performScrollTo().performClick();compose.onNodeWithTag("study-new-map-save").performClick()
        try{compose.waitUntil(10000){runBlocking{app.mapGraphs.read(note.id)}.any{it.nodes.size==2}}}catch(t:Throwable){
            shot("map-failure");File(app.getExternalFilesDir(null),"template-map-failure.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString()+"\n"+runBlocking{app.mapGraphs.read(note.id)}.toString());throw t
        }
        assertEquals(originalIds+note.id,runBlocking{app.repository.observeNotes().first()}.map{it.id}.toSet());shot("map-template")
    }
}
