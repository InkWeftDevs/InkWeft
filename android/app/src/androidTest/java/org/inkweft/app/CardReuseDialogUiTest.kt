// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CardReuseDialogUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<InkWeftApplication>()
    private fun id()=UUID.randomUUID().toString()
    @Test fun realReferenceAndCopyActionsKeepSharedIdentityAndIndependentContentAfterReopen(){
        val source=runBlocking{app.workspaceRepository.create("复用原本",false,PaperStyle.BLANK)}
        val target=runBlocking{app.workspaceRepository.create("复用目标本",false,PaperStyle.BLANK)}
        val cardId=id();val presentationId=id()
        val page=runBlocking{app.pages.activePages(source.id).first().id}
        val stroke=InkStroke(id(),InkPen.PEN,0xff234567.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(200f,200f,50)))
        runBlocking{
            check(app.inkRepository.save(CommitInk(id(),page,0,InkMutation.Replace(emptyList(),listOf(stroke)))) is InkCommitResult.Committed)
            app.study.submit(StudyCommand(id(),source.id,StudyAction.CREATE,cardId=cardId,nodeId=id(),title="知识卡",body="原正文",
                source=StudySourceDraft(page,1,stroke.bounds(),listOf(stroke.id))))
            app.knowledge.submit(KnowledgeCommand(id(),source.id,presentationId,0,KnowledgeData.CardPresentation(cardId,"原注释")))
            app.knowledge.submit(KnowledgeCommand(id(),source.id,id(),0,KnowledgeData.Question(cardId,"原题目")))
        }
        val card=runBlocking{app.study.cards(source.id).first().single()}
        var visible by mutableStateOf(true)
        compose.setContent{MaterialTheme{if(visible)CardReuseDialog(card){visible=false}}}
        fun tap(tag:String){val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.assertIsDisplayed().assertIsEnabled().performClick()}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("reuse-book-${target.id}").assertExists()}.isSuccess}
        tap("reuse-book-${target.id}")
        tap("card-reuse-save")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-reuse-message").assertTextContains("同步引用已加入",substring=true)}.isSuccess}
        val reference=runBlocking{app.knowledge.observeBook(target.id).first().single{it.data() is KnowledgeData.Link}}
        assertTrue(runBlocking{app.study.cards(target.id).first().isEmpty()})
        compose.onNodeWithText("返回原卡").performClick()
        compose.runOnIdle{visible=true}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("reuse-book-${target.id}").assertExists()}.isSuccess}
        tap("reuse-book-${target.id}")
        compose.onNodeWithText("独立副本",substring=false).performScrollTo().performClick()
        tap("card-reuse-save")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-reuse-message").assertTextContains("独立副本已加入",substring=true)}.isSuccess}
        val copy=runBlocking{app.study.cards(target.id).first().single()}
        assertNotEquals(cardId,copy.id);assertEquals("原正文",copy.body)
        val originalSources=runBlocking{app.study.sources(cardId,card.revision)}
        val copySources=runBlocking{app.study.sources(copy.id,copy.revision)}
        assertEquals(originalSources.refs,copySources.refs)
        assertEquals(originalSources.sources.map{ContentTransfer.hash(it.snapshot)},copySources.sources.map{ContentTransfer.hash(it.snapshot)})
        assertTrue(runBlocking{app.knowledge.observeBook(target.id).first().none{it.data() is KnowledgeData.Question}})
        compose.onNodeWithText("返回原卡").performClick()
        runBlocking{
            app.study.submit(StudyCommand(id(),source.id,StudyAction.EDIT,cardId=cardId,expectedRevision=card.revision,title="新版知识卡",body="更新正文"))
            app.knowledge.submit(KnowledgeCommand(id(),source.id,presentationId,1,KnowledgeData.CardPresentation(cardId,"更新注释")))
        }
        // Reopening the actual dialog reloads the same source identity; copies retain their own version.
        compose.runOnIdle{visible=true}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-reuse-save").assertIsEnabled()}.isSuccess}
        val refData=reference.data() as KnowledgeData.Link
        assertEquals(cardId,refData.target.id)
        assertEquals("更新正文",runBlocking{app.study.cards(source.id).first().single().body})
        assertEquals("原正文",runBlocking{app.study.cards(target.id).first().single().body})
        val copiedPresentation=runBlocking{app.knowledge.observeBook(target.id).first().map{it.data()}.filterIsInstance<KnowledgeData.CardPresentation>().single()}
        assertEquals(copy.id,copiedPresentation.cardId);assertEquals("原注释",copiedPresentation.annotation)
        assertEquals(1,runBlocking{app.knowledge.observeBook(target.id).first().count{it.data() is KnowledgeData.Link}})
    }
}
