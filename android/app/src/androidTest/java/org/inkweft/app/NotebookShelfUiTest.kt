// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** All content is synthetic. Tests do not claim the user's flicker is reproduced. */
class NotebookShelfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun noKeyboard(){compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())};compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false};compose.waitForIdle()}
    private fun saved(n:Int){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true).assertTextContains("$n 笔",substring=true)}.isSuccess}}
    private fun create():Note{ready();val title="封面测试-"+UUID.randomUUID().toString().take(6);compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("cover-choice-forest").performScrollTo().performClick();compose.onNodeWithTag("create-note").performClick();saved(0);noKeyboard();return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}}
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun rename(value:String){compose.onNodeWithTag("rename-title").performTextReplacement(value);compose.onNodeWithTag("confirm-rename").performClick();compose.waitUntil(10_000){compose.onAllNodesWithTag("rename-dialog").fetchSemanticsNodes().isEmpty()};noKeyboard()}
    @Test fun shelfRenameCancelBlankAndPersistedIdentity(){
        val n=create();assertEquals("forest",runBlocking{app.workspaceRepository.get(n.id).coverKey});compose.onNodeWithTag("back-library").performClick();ready()
        compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("rename-note-${n.id}").performClick()
        compose.onNodeWithTag("rename-title").performTextReplacement("   ");compose.onNodeWithTag("confirm-rename").assertIsNotEnabled();compose.onNodeWithTag("cancel-rename").performClick();assertEquals(n.title,runBlocking{app.repository.read(n.id)?.title})
        val renamed="高数 · 公式与推导-${n.id.take(8)}"
        compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("rename-note-${n.id}").performClick();rename(renamed)
        assertEquals(renamed,runBlocking{app.repository.read(n.id)?.title});compose.onNodeWithTag("library-search").performTextInput("公式与推导");compose.onNodeWithText(renamed).assertExists();compose.onNodeWithTag("library-search").performTextClearance();noKeyboard()
        runBlocking{for((i,c) in listOf(NotebookCover.INK,NotebookCover.SAND,NotebookCover.ROSE,NotebookCover.GRID,NotebookCover.WAVE).withIndex())app.workspaceRepository.create(listOf("英语 · 阅读积累","专业课 · 知识整理","每日复习","数学 · 易错题","灵感与草稿")[i],i==4,PaperStyle.DOTS,c)}
        compose.waitUntil(10_000){compose.onAllNodesWithText("英语 · 阅读积累").fetchSemanticsNodes().isNotEmpty()};shot("shelf-covers-emulator.png")
    }
    @Test fun coverChangesPersistWithoutChangingInkAndCanReturnToContentPreview(){
        val n=create();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.2f,height*.3f),Offset(width*.45f,height*.55f),230)};saved(1)
        val before=runBlocking{app.inkRepository.read(n.id).strokes.single().stroke};compose.onNodeWithTag("back-library").performClick();ready()
        fun pick(){compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("change-cover-${n.id}").performClick()}
        pick();compose.onNodeWithTag("cover-choice-wave").performScrollTo().performClick();noKeyboard();shot("cover-picker-emulator.png");compose.onNodeWithTag("confirm-cover").performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).coverKey}=="wave"};compose.activityRule.scenario.recreate();ready()
        assertEquals(before.samples,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples})
        pick();compose.onNodeWithTag("cover-choice-content").performScrollTo().performClick();compose.onNodeWithTag("confirm-cover").performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).coverKey}=="content"};assertEquals(before.id,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.id})
    }
    @Test fun editorRenameKeepsUnsavedBodyUntilExplicitTextSave(){
        val n=create();compose.onNodeWithTag("mode-text").performClick();compose.onNodeWithTag("note-body").performTextInput("未保存的个人理解，不随改名提交")
        compose.onNodeWithTag("rename-from-editor").performClick();rename("新的标题但保留草稿")
        assertEquals("",runBlocking{app.repository.read(n.id)?.text});compose.onNodeWithTag("note-body").assertTextEquals("未保存的个人理解，不随改名提交")
        compose.onNodeWithTag("save-text").performClick();compose.waitUntil(10_000){runBlocking{app.repository.read(n.id)?.text}=="未保存的个人理解，不随改名提交"}
        assertEquals("新的标题但保留草稿",runBlocking{app.repository.read(n.id)?.title})
    }
    @Test fun drawerUsesOneSearchAndClosesWithBackWithAnimationsEnabled(){
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(c:String):String=automation.executeShellCommand(c).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8).trim()}}
        val oldScale=shell("settings get global animator_duration_scale")
        try{
            shell("wm size 1256x1920");shell("settings put global animator_duration_scale 1");Thread.sleep(600);ready()
            repeat(3){compose.onNodeWithTag("open-library-drawer").performClick();compose.waitForIdle();compose.onNodeWithTag("library-drawer").assertIsDisplayed();compose.onAllNodesWithTag("library-search").assertCountEquals(1);compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle();compose.onNodeWithTag("new-note").assertIsDisplayed()}
            compose.onNodeWithTag("open-library-drawer").performClick();compose.waitForIdle();shot("shelf-drawer-emulator.png")
        }finally{if(oldScale.matches(Regex("[0-9.]+")))shell("settings put global animator_duration_scale $oldScale")else shell("settings delete global animator_duration_scale");shell("wm size 1920x1200");Thread.sleep(500)}
    }
}
