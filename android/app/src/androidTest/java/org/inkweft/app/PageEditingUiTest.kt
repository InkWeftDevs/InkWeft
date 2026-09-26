// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
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

class PageEditingUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun counter(text:String){
        try{
            // The count Text is a descendant of a merging TextButton. Match the
            // same unmerged node used by the existing insertion regression suite.
            compose.waitUntil(15_000){runCatching{
                compose.onNodeWithTag("page-counter",useUnmergedTree=true).assertTextEquals(text)
                compose.onNodeWithTag("page-directory").assertIsEnabled()
            }.isSuccess}
        }catch(error:Throwable){
            runCatching{shot("page-edit-failure.png")}
            runCatching{val tree=compose.onRoot(useUnmergedTree=true).printToString();println(tree)
                File(compose.activity.getExternalFilesDir(null),"page-edit-failure-semantics.txt").writeText(tree)}
            throw error
        }
    }
    private fun seed(count:Int=3):Pair<Note,List<String>>{
        ready();val n=runBlocking{app.workspaceRepository.create("整理页面-${id().take(8)}",false,PaperStyle.GRID)}
        val ids=mutableListOf(n.id);repeat(count-1){ids+=runBlocking{app.pages.addAfter(n.id,ids.last(),id())}.id}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
        counter("第 1 / $count 页");compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("page-directory").assertIsEnabled()}.isSuccess}
        return n to ids
    }
    private fun menu(number:Int){compose.onNodeWithTag("page-directory").performClick();compose.onNodeWithTag("page-menu-$number").performClick()}
    private fun confirm(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("confirm-page-edit").assertIsEnabled()}.isSuccess};compose.onNodeWithTag("confirm-page-edit").performClick()}
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    @Test fun movingCurrentPageKeepsItsIdentityAndReadingPosition(){
        val (n,ids)=seed();menu(1);compose.onNodeWithTag("move-page-1").performClick()
        compose.onNodeWithTag("page-edit-end").performClick();shot("page-move-options.png");confirm();counter("第 3 / 3 页")
        assertEquals(listOf(ids[1],ids[2],ids[0]),runBlocking{app.pages.activePages(n.id)}.map{it.id})
        compose.activityRule.scenario.recreate();counter("第 3 / 3 页")
    }
    @Test fun copyingPageAtStartOpensIndependentPage(){
        val (n,ids)=seed();menu(2);compose.onNodeWithTag("copy-page-2").performClick()
        compose.onNodeWithTag("page-edit-start").performClick();confirm();counter("第 1 / 4 页")
        val pages=runBlocking{app.pages.activePages(n.id)}
        assertTrue(pages.first().id !in ids);assertEquals(ids,pages.drop(1).map{it.id})
    }
    @Test fun pageRecycleAndChosenRestoreUseActualDirectory(){
        val (n,ids)=seed();menu(2);compose.onNodeWithTag("recycle-page-2").performClick();confirm();counter("第 1 / 2 页")
        assertEquals(listOf(ids[0],ids[2]),runBlocking{app.pages.activePages(n.id)}.map{it.id})
        compose.onNodeWithTag("page-directory").performClick();compose.onNodeWithTag("pages-recycled").performClick()
        compose.onNodeWithTag("recycled-page-${ids[1]}").assertIsDisplayed();shot("page-recycle-directory.png")
        compose.onNodeWithTag("restore-page-${ids[1]}").performClick();compose.onNodeWithTag("page-edit-start").performClick();confirm();counter("第 1 / 3 页")
        assertEquals(listOf(ids[1],ids[0],ids[2]),runBlocking{app.pages.activePages(n.id)}.map{it.id})
        compose.activityRule.scenario.recreate();counter("第 1 / 3 页")
    }
    @Test fun lastPageCannotBeRecycledAndCancelDoesNotMove(){
        val (n,ids)=seed(1);menu(1);compose.onNodeWithTag("recycle-page-1").assertIsNotEnabled()
        compose.onNodeWithTag("move-page-1").performClick();compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        counter("第 1 / 1 页");assertEquals(ids,runBlocking{app.pages.activePages(n.id)}.map{it.id})
    }
}
