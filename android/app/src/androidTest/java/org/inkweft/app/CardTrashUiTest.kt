// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses retained synthetic notebooks only; exercises the normal card management and recovery controls. */
class CardTrashUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val db get()=StudyRepository::class.java.getDeclaredField("db").apply{isAccessible=true}.get(app.study) as NoteDatabase
    private fun id()=UUID.randomUUID().toString()
    private fun waitFor(tag:String){compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(tag:String){
        compose.revealAction(tag);waitFor(tag)
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag(tag).assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag(tag).let{runCatching{it.performScrollTo()};it.performClick()};compose.waitForIdle()
    }
    private fun current(card:String)=runBlocking{db.study().card(card)!!}
    @Test fun cardManagementCancelsRejectsChangedQuestionThenRestoresFromRecycleArea(){
        waitFor("new-note")
        val note=runBlocking{app.workspaceRepository.create("回收闭环合成 ${id().take(6)}",false,PaperStyle.BLANK)}
        val other=runBlocking{app.workspaceRepository.create("其他本引用合成 ${id().take(6)}",false,PaperStyle.BLANK)}
        val card=id();val node=id();val question=id();val link=id()
        runBlocking{
            app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,cardId=card,nodeId=node,title="可恢复卡",body="保留正文"))
            app.study.submit(StudyCommand(id(),note.id,StudyAction.REMOVE_NODE,nodeId=node,expectedRevision=1))
            app.knowledge.submit(KnowledgeCommand(id(),note.id,question,0,KnowledgeData.Question(card,"预览里的旧题目")))
            app.knowledge.submit(KnowledgeCommand(id(),other.id,link,0,KnowledgeData.Link(TargetRef(TargetKind.NOTE,other.id),TargetRef(TargetKind.CARD,card))))
        }
        assertTrue(app.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${note.id}",false).commit())
        assertTrue(app.getSharedPreferences("inkweft-study-window",0).edit().putString("${note.id}-mode","FOCUS").commit())
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.waitForSavedInk();tap("quick-study");tap("study-tab-0")
        tap("study-card-$card");tap("card-management-actions");tap("study-trash-card")
        waitFor("card-trash-impact-$link")
        compose.onNodeWithTag("card-trash-impact-$link")
            .assertTextContains("${other.title} · ${other.id}",substring=true)
            .assertTextContains("可恢复卡 · $card",substring=true)
            .assertTextContains("记录 $link · 修订 1",substring=true)
        compose.onNodeWithTag("card-trash-recovery").assertTextContains("导图管理 → 整理 → 容量与整理 → 查看卡片回收区",substring=true)
        compose.onNodeWithTag("card-trash-confirm").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        val previewFrame=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(app.getExternalFilesDir(null),"card-trash-impact-preview.png").outputStream().use{
            check(previewFrame.compress(Bitmap.CompressFormat.PNG,100,it))
        }}finally{previewFrame.recycle()}
        tap("card-trash-cancel");assertNull(current(card).trashedAt);assertEquals(1L,current(card).revision)
        tap("study-trash-card");waitFor("card-trash-impact-$question")
        runBlocking{app.knowledge.submit(KnowledgeCommand(id(),note.id,question,1,KnowledgeData.Question(card,"预览后修改的题目")))}
        tap("card-trash-confirm");waitFor("card-trash-message")
        compose.onNodeWithTag("card-trash-message").assertTextContains("引用或题目已变化",substring=true)
        compose.onNodeWithTag("card-trash-confirm").assertIsNotEnabled();assertNull(current(card).trashedAt)
        tap("card-trash-refresh");waitFor("card-trash-impact-$question")
        compose.onNodeWithTag("card-trash-impact-$question")
            .assertTextContains("预览后修改的题目",substring=true)
            .assertTextContains("${note.title} · ${note.id}",substring=true)
            .assertTextContains("记录 $question · 修订 2",substring=true)
        tap("card-trash-confirm")
        // Successful StudyCommands close the inspector automatically; the active list must no longer contain this card.
        compose.waitUntil(15_000){current(card).trashedAt!=null&&
            compose.onAllNodesWithTag("card-trash-dialog").fetchSemanticsNodes().isEmpty()&&
            compose.onAllNodesWithTag("study-card-details").fetchSemanticsNodes().isEmpty()&&
            compose.onAllNodesWithTag("study-card-$card").fetchSemanticsNodes().isEmpty()}
        waitFor("study-list");compose.onNodeWithTag("study-card-$card").assertDoesNotExist()
        assertEquals(2L,current(card).revision)
        // Compact maps expose recovery through the existing management/capacity panel, not the wide-only filter chip.
        tap("study-management");tap("map-menu-group-1");tap("study-capacity");tap("capacity-open-trash")
        tap("study-card-$card")
        compose.onNodeWithText("恢复卡片",substring=false).let{runCatching{it.performScrollTo()};it.performClick()}
        compose.waitUntil(15_000){current(card).trashedAt==null&&current(card).revision==3L}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("study-card-details").fetchSemanticsNodes().isEmpty()&&
            compose.onAllNodesWithTag("study-card-$card").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("study-card-$card").assertDoesNotExist()
        tap("study-management");tap("map-menu-group-1");tap("study-capacity");tap("capacity-open-cards")
        waitFor("study-card-$card");compose.onNodeWithTag("study-card-$card").assertIsDisplayed()
        assertEquals("保留正文",current(card).body)
        runBlocking{assertFalse(db.knowledge().get(link)!!.removed);assertEquals("预览后修改的题目",(db.knowledge().get(question)!!.data() as KnowledgeData.Question).prompt)}
    }
}
