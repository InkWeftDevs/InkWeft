package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
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

class UiR2Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun ready(){compose.waitUntil(15000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),"r2-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun seed():Pair<Note,String>{ready();val n=runBlocking{app.workspaceRepository.create("R2 验收 ${id().take(5)}",false,PaperStyle.CORNELL)}
        val card=id();runBlocking{app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,card,id(),title="条件概率",body="答案专用暗号 7654321"))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("knowledge-open").assertIsEnabled()}.isSuccess};return n to card}
    @Test fun libraryPrimaryActionAndOverflowRemainReachable(){
        ready();compose.onNodeWithTag("new-note").assertIsDisplayed().assertIsEnabled()
        if(compose.onAllNodesWithTag("library-more").fetchSemanticsNodes().isNotEmpty()){
            compose.onNodeWithTag("library-more").performClick()
            compose.onNodeWithTag("library-layout").assertIsDisplayed().performClick()
            compose.onNodeWithTag("library-list").assertExists()
        }
        compose.onNodeWithTag("new-note").performClick()
        compose.onNodeWithTag("create-note").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick();ready()
    }
    @Test fun systemBackWaitsForLiveStrokeAndKeepsPageIdentity(){
        val(n,_)=seed();compose.onNodeWithTag("ink-more").performClick();compose.onNodeWithTag("ink-finger").performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput{down(center);moveBy(Offset(30f,10f))}
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("抬笔",substring=true)}.isSuccess}
        compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("ink-surface").assertExists()
        assertEquals(n.id,ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
        compose.onNodeWithTag("ink-surface").performTouchInput{up()}
        compose.waitUntil(10000){runBlocking{app.inkRepository.read(n.id).strokes.size}==1}
    }
    @Test fun visualCreationCancelsWithoutWritingThenCreatesRealTemplate(){
        ready();val before=runBlocking{app.repository.observeNotes().first().size}
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput("康奈尔模板验收")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToKey("CORNELL");compose.onNodeWithTag("template-cornell").performClick();shot("new-notebook")
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick();ready();assertEquals(before,runBlocking{app.repository.observeNotes().first().size})
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToKey("CORNELL");compose.onNodeWithTag("template-cornell").performClick();compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        val n=ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.current!!.base
        assertEquals(PaperStyle.CORNELL.ordinal,runBlocking{app.pages.activePages(n.id).single().paper});shot("editor")
    }
    @Test fun confirmedLinkIsPersistentAndBacklinkUsesSameRecord(){
        val(n,card)=seed();compose.onNodeWithTag("knowledge-open").performClick();compose.onNodeWithTag("add-knowledge-link").performScrollTo().performClick()
        compose.onNodeWithText("条件概率 · ${n.title} · ${card.take(6)}").performScrollTo().performClick()
        compose.waitUntil(15000){runBlocking{app.knowledge.observe().first().any{it.notebookId==n.id&&it.data() is KnowledgeData.Link}}}
        val links=runBlocking{app.knowledge.observe().first().filter{it.notebookId==n.id}.map{it.data()}.filterIsInstance<KnowledgeData.Link>()}
        assertEquals(1,links.size);assertEquals(TargetRef(TargetKind.PAGE,n.id),links.single().source);assertEquals(card,links.single().target.id);shot("links")
        compose.onNodeWithTag("knowledge-tab-2").performScrollTo().performClick();shot("local-graph")
    }
    @Test fun manualRecallDoesNotShowAnswerBeforeExplicitReveal(){
        val(n,card)=seed();runBlocking{app.knowledge.submit(KnowledgeCommand(id(),n.id,id(),0,KnowledgeData.Question(card,"说出条件概率的定义")))}
        compose.onNodeWithTag("knowledge-open").performClick();compose.onNodeWithTag("knowledge-tab-4").performScrollTo().performClick();compose.onNodeWithTag("manual-review-start").performClick()
        compose.onNodeWithTag("review-question").assertTextEquals("说出条件概率的定义");compose.onAllNodesWithTag("review-answer").assertCountEquals(0)
        compose.onAllNodesWithText("答案专用暗号 7654321",substring=true).assertCountEquals(0);shot("review-hidden")
        compose.onNodeWithTag("reveal-answer").performClick();compose.onNodeWithTag("review-answer").assertTextContains("答案专用暗号 7654321",substring=true)
        val q=runBlocking{app.knowledge.observe().first().filter{it.notebookId==n.id}.map{it.data()}.filterIsInstance<KnowledgeData.Question>().single()}
        assertEquals(ManualState.REVIEW,q.state);shot("review-revealed")
    }
    @Test fun sourceReadingReturnsToSameQuestionWithoutRating(){
        val(n,_)=seed();val stroke=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(200f,100f,20)))
        val card=id();val question=id()
        runBlocking{
            app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(stroke)))
            app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,card,id(),title="来源卡",body="来源答案",source=StudySourceDraft(n.id,1,CanvasBounds(95.0,95.0,205.0,105.0),listOf(stroke.id))))
            app.knowledge.submit(KnowledgeCommand(id(),n.id,question,0,KnowledgeData.Question(card,"原题保持不变")))
        }
        compose.onNodeWithTag("knowledge-open").performClick();compose.onNodeWithTag("knowledge-tab-4").performScrollTo().performClick();compose.onNodeWithTag("manual-review-start").performClick()
        compose.onNodeWithTag("reveal-answer").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("review-open-source").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("review-open-source").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("review-source-canvas").fetchSemanticsNodes().isNotEmpty()};shot("review-source")
        compose.onNodeWithTag("return-to-review").performClick();compose.onNodeWithTag("review-question").assertTextEquals("原题保持不变")
        assertEquals(1L,runBlocking{app.knowledge.observe().first().single{it.id==question}.revision})
    }
    @Test fun independentMapsAndDecorativeBoardShareCardWithoutCreatingRelations(){
        val(n,card)=seed();compose.onNodeWithTag("knowledge-open").performClick();compose.onNodeWithTag("knowledge-tab-3").performScrollTo().performClick()
        fun records()=runBlocking{app.knowledge.observe().first().filter{it.notebookId==n.id}}
        fun map(title:String):String{
            compose.onNodeWithText("新建独立脑图").performScrollTo().performClick();compose.onNodeWithText("脑图名称").performTextInput(title);compose.onNodeWithText("创建脑图").performClick()
            compose.waitUntil(10000){records().any{(it.data() as? KnowledgeData.MapDefinition)?.title==title}}
            compose.onNodeWithText(title).performScrollTo().performClick();return records().single{(it.data() as? KnowledgeData.MapDefinition)?.title==title}.id
        }
        fun place(){val before=records().size;compose.onNodeWithText("放入共享卡片").performScrollTo().performClick();compose.onNodeWithText("条件概率").performScrollTo().performClick();compose.waitUntil(10000){records().size==before+1}}
        val a=map("甲图");place();val b=map("乙图");place()
        runBlocking{app.study.submit(StudyCommand(id(),n.id,StudyAction.EDIT,cardId=card,expectedRevision=1,title="条件概率",body="两张图共用正文"))}
        compose.waitForIdle();compose.onNodeWithTag("knowledge-board").performTouchInput{click(center)};compose.onNodeWithText("两张图共用正文").assertIsDisplayed();compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("甲图").performScrollTo().performClick();compose.onNodeWithTag("knowledge-board").performTouchInput{click(center)};compose.onNodeWithText("两张图共用正文").assertIsDisplayed();compose.onNodeWithText("从此视图移除").performClick()
        compose.waitUntil(10000){records().any{it.removed&&(it.data() as? KnowledgeData.MapOccurrence)?.mapId==a}}
        assertTrue(records().any{!it.removed&&(it.data() as? KnowledgeData.MapOccurrence)?.mapId==b});assertEquals(1,runBlocking{app.study.cards(n.id).first().size})
        compose.onNodeWithText("自由白板").performScrollTo().performClick();place();place();compose.onNodeWithText("适配全部").performClick()
        compose.onNodeWithText("装饰连线").performClick();compose.onAllNodesWithText("起点")[0].performClick();compose.onAllNodesWithText("终点")[1].performClick();compose.onNodeWithText("添加装饰线").performClick()
        compose.waitUntil(10000){records().any{it.data() is KnowledgeData.Decoration}};val before=records().filter{it.data() is KnowledgeData.Placement}.map{it.revision}.sum()
        compose.onNodeWithTag("knowledge-board").performTouchInput{swipe(Offset(width*.32f,height*.5f),Offset(width*.36f,height*.52f),300)}
        compose.waitUntil(10000){records().filter{it.data() is KnowledgeData.Placement}.map{it.revision}.sum()>before}
        assertTrue(records().none{it.data() is KnowledgeData.Link});shot("board-decorations")
    }
}
