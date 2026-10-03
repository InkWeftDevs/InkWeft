// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.inkweft.app.ui.designsystem.InkTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Compose controls + Room readback on an emulator, not Pencil3 evidence. */
class PageInsertionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private var hadFingerWrites=false
    private var originalFingerWrites=false
    private var originalNewWorld:Boolean?=null
    private var originalNewPaper:String?=null
    private var originalNewCover:String?=null
    @Before fun captureOwnedDefaults(){
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        hadFingerWrites=prefs.contains("finger-writes");originalFingerWrites=prefs.getBoolean("finger-writes",false)
        val defaults=app.getSharedPreferences("inkweft-new-notebook",0)
        originalNewWorld=if(defaults.contains("world"))defaults.getBoolean("world",false)else null
        originalNewPaper=defaults.getString("paper",null);originalNewCover=defaults.getString("cover",null)
    }
    @After fun restoreOwnedDefaults(){
        val editor=app.getSharedPreferences("inkweft-editor",0).edit()
        if(hadFingerWrites)editor.putBoolean("finger-writes",originalFingerWrites)else editor.remove("finger-writes")
        val fingerRestored=editor.commit()
        val defaults=app.getSharedPreferences("inkweft-new-notebook",0).edit()
        originalNewWorld?.let{defaults.putBoolean("world",it)}?:defaults.remove("world")
        defaults.putString("paper",originalNewPaper).putString("cover",originalNewCover)
        val defaultsRestored=defaults.commit()
        assertTrue(fingerRestored);assertTrue(defaultsRestored)
    }
    private fun saved(){compose.waitForSavedInk()}
    private fun create():String{
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val title="分页回归-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("create-note").performClick();compose.singlePageEditor();saved()
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
    }
    private fun rows(id:String)=runBlocking{app.pages.observe(id).first()}
    private fun shot(name:String){compose.waitForIdle();val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}}
    private fun insert(){compose.onNodeWithTag("confirm-insert-pages").performClick();compose.waitUntil(10_000){compose.onAllNodesWithTag("insert-pages-dialog").fetchSemanticsNodes().isEmpty()};saved()}
    private fun preference(book:String)=NewPagePaperPreference(app.getSharedPreferences("inkweft-reading",0),book)
    private fun openPaperDefault(){
        if(compose.onAllNodesWithTag("document-settings-dialog").fetchSemanticsNodes().isEmpty())compose.onNodeWithTag("quick-settings").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("settings-new-page-paper").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("settings-new-page-paper").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-dialog").assertIsDisplayed()
    }
    private fun savePaperDefault(paper:PaperStyle?){
        compose.onNodeWithTag("new-page-paper-${paper?.name?.lowercase()?:"inherit"}").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-save").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("new-page-paper-dialog").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("document-settings-dialog-close").performClick();saved()
    }
    private fun cancelPaperDefault(){
        compose.onNodeWithTag("new-page-paper-cancel").performClick()
        compose.onNodeWithTag("document-settings-dialog-close").performClick()
    }
    @Test fun beginningBatchKeepsOriginalInkAndPageIdentity(){
        val book=create()
        // New pages read the persisted input mode. Enable writing only if it is off;
        // preceding UI fixtures may already have enabled it.
        if(!app.getSharedPreferences("inkweft-editor",0).getBoolean("finger-writes",false))compose.openEditorAction("quick-finger")
        compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.3f),Offset(width*.5f,height*.5f),200)}
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(book).strokes.size}==1};saved()
        val stroke=runBlocking{app.inkRepository.read(book).strokes.single().stroke}
        val originalPaper=rows(book).single().paper
        openPaperDefault();savePaperDefault(PaperStyle.BLANK)
        assertEquals(originalPaper,rows(book).single().paper)
        assertEquals(stroke.samples,runBlocking{app.inkRepository.read(book).strokes.single().stroke.samples})
        compose.openEditorAction("add-page")
        compose.onNodeWithTag("insert-start").performScrollTo().performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick()
        compose.onNodeWithTag("insert-paper-grid").performScrollTo().performClick();compose.onNodeWithTag("insert-preview").performScrollTo().assertTextContains("插入 2 页",substring=true)
        shot("insert-pages-options.png");insert()
        compose.waitUntil(10_000){rows(book).size==3}
        assertEquals(book,rows(book).last().id);assertTrue(rows(book).take(2).all{it.paper==PaperStyle.GRID.ordinal})
        assertEquals(originalPaper,rows(book).last().paper);assertEquals(PaperStyle.BLANK,preference(book).read())
        assertEquals(stroke.samples,runBlocking{app.inkRepository.read(book).strokes.single().stroke.samples})
        compose.assertCurrentPage("第 1 / 3 页")
        compose.openOverviewGrid();shot("page-directory-insertion.png")
        compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-3"));compose.onNodeWithTag("jump-page-3").performClick();saved();compose.onNodeWithTag("ink-surface").assertInkCount(1)
    }
    @Test fun thumbnailBeforeMenuDoesNotRequireNavigatingToTarget(){
        val book=create();compose.openEditorAction("add-page");compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==3};val previous=rows(book)
        compose.openOverviewGrid();compose.onNodeWithTag("page-menu-1").performClick()
        compose.onNodeWithText("在此页之前插入").performClick()
        compose.onNodeWithTag("insert-open-new").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==4};val actual=rows(book)
        assertEquals(previous.map{it.id},actual.drop(1).map{it.id})
        compose.assertCurrentPage("第 3 / 4 页")
        compose.activityRule.scenario.recreate();saved();compose.assertCurrentPage("第 3 / 4 页")
    }
    @Test fun cancelledDialogDoesNotAddPagesAndEndKeepsOrder(){
        val book=create();val first=rows(book)
        compose.openEditorAction("add-page");compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithText("取消").performClick()
        assertEquals(first,rows(book));compose.openEditorAction("add-page");compose.onNodeWithTag("insert-end").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==2};assertEquals(book,rows(book).first().id)
        compose.assertCurrentPage("第 2 / 2 页")
    }
    @Test fun newPagePaperRequiresSaveRestoresAndStaysNotebookLocal(){
        val book=create();val original=rows(book).single()
        val fixed=if(original.paper==PaperStyle.GRID.ordinal)PaperStyle.BLANK else PaperStyle.GRID
        val otherPaper=if(fixed==PaperStyle.BLANK)PaperStyle.GRID else PaperStyle.BLANK
        assertNull(preference(book).read())
        openPaperDefault();compose.onNodeWithTag("new-page-paper-inherit").assertIsSelected()
        compose.onNodeWithTag("new-page-paper-${fixed.name.lowercase()}").performScrollTo().performClick()
        cancelPaperDefault();assertNull(preference(book).read())
        openPaperDefault();compose.onNodeWithTag("new-page-paper-inherit").assertIsSelected()
        compose.onNodeWithTag("new-page-paper-${fixed.name.lowercase()}").performScrollTo().performClick()
        compose.activityRule.scenario.recreate();saved();assertNull(preference(book).read())
        openPaperDefault();compose.onNodeWithTag("new-page-paper-inherit").assertIsSelected();savePaperDefault(fixed)
        assertEquals(fixed,preference(book).read())
        openPaperDefault();compose.onNodeWithTag("new-page-paper-${fixed.name.lowercase()}").assertIsSelected()
        compose.onNodeWithTag("new-page-paper-inherit").performScrollTo().performClick()
        compose.activityRule.scenario.recreate();saved();assertEquals(fixed,preference(book).read())
        openPaperDefault();compose.onNodeWithTag("new-page-paper-${fixed.name.lowercase()}").assertIsSelected()
        cancelPaperDefault()
        val other=runBlocking{app.workspaceRepository.create("另一册新增页纸面",false,PaperStyle.RULED)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(other)};compose.singlePageEditor();saved()
        openPaperDefault();compose.onNodeWithTag("new-page-paper-inherit").assertIsSelected();savePaperDefault(otherPaper)
        val first=runBlocking{checkNotNull(app.repository.read(book))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(first)};compose.singlePageEditor();saved()
        openPaperDefault();compose.onNodeWithTag("new-page-paper-${fixed.name.lowercase()}").assertIsSelected()
        cancelPaperDefault()
        assertEquals(listOf(original.id to original.paper),rows(book).map{it.id to it.paper})
        compose.openEditorAction("add-page");compose.onNodeWithTag("insert-paper-inherit").assertIsNotSelected()
        compose.onNodeWithTag("insert-preview").performScrollTo().assertTextContains(PaperTemplates.title(fixed),substring=true);insert()
        compose.waitUntil(10_000){rows(book).size==2};assertEquals(fixed.ordinal,rows(book).last().paper)
        compose.openEditorAction("add-page");compose.onNodeWithTag("insert-target-1").performScrollTo().performClick()
        compose.onNodeWithTag("insert-paper-inherit").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==3}
        assertEquals(listOf(original.paper,original.paper,fixed.ordinal),rows(book).map{it.paper})
        assertEquals(fixed,preference(book).read());assertEquals(otherPaper,preference(other.id).read())
        openPaperDefault();savePaperDefault(null);assertNull(preference(book).read())
        assertFalse(app.getSharedPreferences("inkweft-reading",0).contains("new-page-paper-$book"))
        compose.openEditorAction("add-page");compose.onNodeWithTag("insert-paper-inherit").assertIsSelected()
        compose.onNodeWithText("取消").performClick();assertEquals(3,rows(book).size)
        assertEquals(otherPaper,preference(other.id).read())
    }
    @Test fun restoredPendingInsertionKeepsCapturedPaperAndReceiptAfterDefaultChanges(){
        val book=create()
        for(committed in listOf(false,true)){
            assertTrue(runBlocking{preference(book).save(PaperStyle.GRID)})
            val before=rows(book)
            val command=InsertPages(UUID.randomUUID().toString(),book,InsertPages.orderHash(before.map{it.id}),
                PageInsertLocation.AFTER,before.last().id,checkNotNull(preference(book).read()),listOf(UUID.randomUUID().toString()),true)
            if(committed)assertTrue(runBlocking{app.pages.insert(command)} is InsertPagesResult.Applied)
            assertTrue(runBlocking{preference(book).save(PaperStyle.BLANK)})
            val state=SavedStateHandle(mapOf("insert.operation" to arrayListOf(command.commandId,book,command.expectedOrder,
                command.location.name,command.anchorPageId.orEmpty(),command.paper.name,"true",*command.pageIds.toTypedArray())))
            val store=ViewModelStore()
            val vm=compose.runOnIdle{BookPagesViewModel(book,app.pages,app.workspaceRepository,state,app.resourcePacks).also{store.put("pending",it)}}
            try{
                compose.waitUntil(10_000){!vm.ui.value.loading};assertTrue(vm.ui.value.insertionUnknown)
                compose.runOnIdle{vm.retryInsertion()}
                compose.waitUntil(10_000){!vm.ui.value.busy&&!vm.ui.value.insertionUnknown}
                assertNull(vm.ui.value.error);assertNull(compose.runOnIdle{state.get<ArrayList<String>>("insert.operation")})
                assertEquals(before.map{it.id}+command.pageIds,rows(book).map{it.id})
                assertEquals(PaperStyle.GRID.ordinal,rows(book).last().paper)
                compose.runOnIdle{vm.retryInsertion()};compose.waitForIdle()
                assertEquals(before.size+1,rows(book).size);assertEquals(PaperStyle.BLANK,preference(book).read())
            }finally{compose.runOnIdle{store.clear()}}
        }
    }
    // Isolated picker/fault coverage; this does not exercise the full app settings flow.
    @Test fun failedDefaultSaveRollsBackAndKeepsDialogOpen(){
        val book=create();val before=rows(book).map{it.id to it.paper}
        val prefs=app.getSharedPreferences("inkweft-reading",0)
        assertTrue(runBlocking{preference(book).save(PaperStyle.GRID)})
        val failing=NewPagePaperPreference(object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val editor=prefs.edit()
                return object:SharedPreferences.Editor by editor {
                    override fun commit():Boolean { editor.commit();return false }
                }
            }
        },book)
        var dismissed=false
        compose.runOnUiThread{compose.activity.setContent{InkTheme.Content{NewPagePaperDialog(PaperStyle.GRID,true,{dismissed=true}){failing.save(it)}}}}
        compose.onNodeWithTag("new-page-paper-blank").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-save").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("new-page-paper-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("new-page-paper-dialog").assertIsDisplayed()
        compose.onNodeWithTag("new-page-paper-blank").assertIsSelected()
        compose.onNodeWithTag("new-page-paper-save").assertIsEnabled();assertFalse(dismissed)
        assertEquals(PaperStyle.GRID,preference(book).read())
        assertEquals(PaperStyle.GRID,runBlocking{withTimeout(10_000){preference(book).observe().first()}})
        assertEquals(before,rows(book).map{it.id to it.paper})
        compose.onNodeWithTag("new-page-paper-cancel").performClick();assertTrue(dismissed)
    }
}
