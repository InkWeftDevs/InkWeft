// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses synthetic content and real app/database/ContentResolver, not a mock UI. */
class LibraryTransfersUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun note()=runBlocking{app.workspaceRepository.create("资料测试-"+UUID.randomUUID().toString().take(6),false,PaperStyle.GRID,NotebookCover.FOREST)}
    private fun menu(id:String){compose.waitUntil(10_000){compose.onAllNodesWithTag("note-menu-$id").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("note-menu-$id").performScrollTo().performClick()}
    private fun shot(name:String){compose.waitForIdle();val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}}
    @Test fun copyFromShelfCreatesSeparateNotebookWithoutOpeningSource(){
        ready();val n=note();menu(n.id);compose.onNodeWithTag("duplicate-note-${n.id}").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-copy").assertIsDisplayed();compose.onNodeWithTag("confirm-copy").performClick()
        compose.waitUntil(20_000){compose.onAllNodesWithTag("open-transfer-result").fetchSemanticsNodes().isNotEmpty()}
        val copy=runBlocking{app.repository.observeNotes().first()}.single{it.title==n.title+" · 副本"}
        assertNotEquals(n.id,copy.id);assertEquals(n,runBlocking{app.repository.read(n.id)})
        compose.onNodeWithTag("dismiss-library-transfer").performClick();ready()
        compose.onAllNodesWithTag("ink-surface").assertCountEquals(0)
    }
    @Test fun pinSurvivesRecreationAndDoesNotSetFavorite(){
        ready();val n=note();menu(n.id);compose.onNodeWithTag("pin-note-${n.id}").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).pinned}}
        assertFalse(runBlocking{app.workspaceRepository.get(n.id).favorite})
        compose.activityRule.scenario.recreate();ready();compose.onNodeWithTag("pinned-${n.id}").assertExists()
        menu(n.id);compose.onNodeWithText("取消置顶",useUnmergedTree=true).assertExists();shot("library-complete-menu.png")
        compose.onNodeWithTag("pin-note-${n.id}").performScrollTo().performClick()
        compose.waitUntil(10_000){!runBlocking{app.workspaceRepository.get(n.id).pinned}}
    }
    @Test fun exportStartsAtShelfAndWritesTheSamePreparedSnapshot(){
        ready();val n=note();menu(n.id);compose.onNodeWithTag("export-note-${n.id}").performScrollTo().performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithTag("save-library-export").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("library-transfer-message").assertTextContains("整本所有页面",substring=true)
        shot("library-export-preview.png")
        val out=File(app.cacheDir,"synthetic-export-${UUID.randomUUID()}.iwbook")
        try{
            // The destination picker itself is an external provider. Exercise its
            // Activity-result receiver with an app-owned URI; do not claim a cloud target.
            compose.runOnIdle{ViewModelProvider(compose.activity)[LibraryTransfersViewModel::class.java].writeExport(Uri.fromFile(out))}
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("library-transfer-message").assertTextContains("已完成向所选",substring=true)}.isSuccess}
            val file=out.inputStream().use{ContentTransfer.read(it)}.content as ContentTransfer.Content.Book
            assertEquals(n.title,file.value.title);assertEquals(1,file.value.pages.size)
            assertEquals(n,runBlocking{app.repository.read(n.id)})
        }finally{out.delete()}
    }
    @Test fun importPreviewChecksThenCommitsWithoutOverwritingExistingNote(){
        ready();val n=note();val path=File(app.cacheDir,"synthetic-import-${UUID.randomUUID()}.iwbook")
        val book=NotebookFile("导入测试-${UUID.randomUUID()}","合成正文",listOf(InkPageFile("页","",emptyList())))
        path.writeBytes(book.encode())
        try{
            compose.runOnIdle{ViewModelProvider(compose.activity)[LibraryTransfersViewModel::class.java].readUri(Uri.fromFile(path))}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("confirm-content-import").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(runBlocking{app.repository.observeNotes().first()}.none{it.title==book.title})
            compose.onNodeWithTag("confirm-content-import").performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithTag("open-transfer-result").fetchSemanticsNodes().isNotEmpty()}
            val imported=runBlocking{app.repository.observeNotes().first()}.single{it.title==book.title}
            assertEquals("合成正文",imported.text);assertNotEquals(n.id,imported.id);assertEquals(n,runBlocking{app.repository.read(n.id)})
        }finally{path.delete()}
    }
}
