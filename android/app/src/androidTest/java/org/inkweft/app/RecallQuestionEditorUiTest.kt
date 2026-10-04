// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.AtomicFile
import android.util.Base64
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual MainActivity authoring routes and Activity rotation, using synthetic notes only. */
class RecallQuestionEditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private var oldCollapsed:Boolean?=null
    private val journals=mutableListOf<File>()
    private fun id()=UUID.randomUUID().toString()
    @Before fun prepare(){
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        oldCollapsed=if(prefs.contains("case-collapsed"))prefs.getBoolean("case-collapsed",false)else null
        assertTrue(prefs.edit().putBoolean("case-collapsed",true).commit())
    }
    @After fun restore(){
        hideKeyboard();journals.forEach{AtomicFile(it).delete()}
        val editor=app.getSharedPreferences("inkweft-editor",0).edit()
        oldCollapsed?.let{editor.putBoolean("case-collapsed",it)}?:editor.remove("case-collapsed")
        assertTrue(editor.commit())
    }
    private data class Fixture(val note:Note,val card:String,val question:String,val title:String,val prompt:String)
    private fun fixture(kind:RecallQuestionKind=RecallQuestionKind.QUESTION):Fixture{
        waitFor("new-note")
        return runBlocking{
            val note=app.workspaceRepository.create("合成题型恢复 "+id().take(8),false,PaperStyle.BLANK)
            val f=Fixture(note,id(),id(),"题型恢复卡 "+id().take(8),"问法A：原配置")
            app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,f.card,id(),title=f.title,body="甲乙丙丁：固定答案"))
            app.knowledge.submit(KnowledgeCommand(id(),note.id,f.question,0,KnowledgeData.Question(f.card,f.prompt)))
            assertTrue(app.study.recall().configure(id(),note.id,f.question,1,0,0,f.card,1,f.prompt,kind,
                clozes=if(kind==RecallQuestionKind.TEXT_CLOZE)listOf(RecallCloze(0,2))else emptyList()) is RecallOutcome.Success)
            journals+=File(app.filesDir,"recall-config-${note.id}-${f.question}.pending")
            f
        }
    }
    private fun exists(tag:String)=compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag:String){compose.waitUntil(15_000){exists(tag)};compose.waitForIdle()}
    private fun tap(tag:String){
        compose.revealAction(tag);waitFor(tag)
        val node=compose.onNodeWithTag(tag)
        // A newly mounted dialog may enable its action before its scroll viewport is placed.
        // Keep the same deadline, and require the actual touch target to be laid out and visible.
        compose.waitUntil(15_000){
            runCatching{node.performScrollTo()}
            runCatching{node.assertIsDisplayed().assertIsEnabled()}.isSuccess
        }
        try {
            node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()
        } catch(error:Throwable) {
            // Instrumentation stdout omits Android println; attach bounded synthetic state to the original failure.
            val diagnostic=runCatching {
                val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true)
                val state=roots.fetchSemanticsNodes().indices.joinToString("\n"){roots[it].printToString()}
                val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                bitmap?.let { image->try{File(app.getExternalFilesDir(null),"recall-config-$tag-failure.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()} }
                "Synthetic target=$tag config=${compose.activity.resources.configuration}\n$state"
            }.getOrElse{"Synthetic diagnostic capture failed: ${it.javaClass.simpleName}"}
            error.addSuppressed(AssertionError(diagnostic));throw error
        }
    }
    private fun draft(tag:String,value:String)=compose.onNodeWithTag(tag).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value)))
    private fun replace(tag:String,value:String){
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performTextReplacement(value)
        hideKeyboard()
    }
    private fun hideKeyboard(){
        compose.runOnIdle{WindowInspector.getGlobalWindowViews().forEach{ViewCompat.getWindowInsetsController(it)?.hide(WindowInsetsCompat.Type.ime())}}
        compose.waitForIdle()
    }
    private fun properties(f:Fixture){
        if(exists("open-library-drawer"))tap("open-library-drawer")
        compose.onNodeWithText("复习",useUnmergedTree=true).performScrollTo().performClick()
        val note=hasText(f.note.title) and hasAnyAncestor(isDialog())
        compose.waitUntil(15_000){compose.onAllNodes(note).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(note).performScrollTo().performClick();tap("knowledge-tab-4")
        val card=hasText("添加问题 · ${f.title}")
        compose.waitUntil(15_000){compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(card).performScrollTo().performClick();waitFor("question-row-${f.question}")
    }
    private fun configure(f:Fixture){tap("question-edit-${f.question}");hideKeyboard();tap("question-configure-type");waitFor("recall-config-prompt")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("recall-config-save").assertIsEnabled()}.isSuccess}}
    private fun question(f:Fixture)=runBlocking{app.knowledge.observeBook(f.note.id).first().single{it.id==f.question}}
    private fun config(f:Fixture)=runBlocking{app.study.recall().configuration(f.question)!!.spec()}
    private fun saved(){compose.waitUntil(15_000){!exists("recall-config-prompt")};compose.waitForIdle()}
    private fun rotated(check:()->Unit){
        val previousActivity=compose.activity;val originalRequest=previousActivity.requestedOrientation
        val landscape=compose.activity.resources.configuration.orientation!=Configuration.ORIENTATION_LANDSCAPE
        val expectedOrientation=if(landscape)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        try{
            compose.activityRule.scenario.onActivity{it.requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15_000){compose.activity!==previousActivity&&compose.activity.resources.configuration.orientation==expectedOrientation}
            waitFor("recall-config-prompt");check()
        }finally{compose.activityRule.scenario.onActivity{it.requestedOrientation=originalRequest}}
    }
    private fun assertConflict(prompt:String){
        waitFor("recall-config-base-changed");draft("recall-config-prompt",prompt)
        compose.onNodeWithTag("recall-config-save").assertIsNotEnabled()
        assertFalse(journals.last().exists())
    }

    @Test fun latestSavedQuestionPromptSurvivesOpeningAndSavingOldConfiguration(){
        val f=fixture();properties(f);tap("question-edit-${f.question}")
        val changed="问法B：作者刚保存的新问题"
        replace("question-edit-prompt",changed);tap("question-edit-save")
        compose.waitUntil(15_000){!exists("question-edit-dialog")}
        assertEquals(changed,(question(f).data() as KnowledgeData.Question).prompt)
        assertEquals(f.prompt,config(f).prompt)
        configure(f);draft("recall-config-prompt",changed);tap("recall-config-save");saved()
        assertEquals(changed,(question(f).data() as KnowledgeData.Question).prompt)
        assertEquals(changed,config(f).prompt)
    }

    @Test fun rotationPreservesPromptKindAndExplicitlyClearedClozeDraft(){
        val f=fixture(RecallQuestionKind.TEXT_CLOZE);properties(f);configure(f)
        val changed="尚未保存的问法草稿\n旋转后仍保留"
        replace("recall-config-prompt",changed)
        compose.onNode(hasText("移除") and hasAnyAncestor(hasTestTag("recall-config-dialog"))).performScrollTo().performClick()
        compose.onNodeWithText(RecallQuestionKind.QUESTION.label).performScrollTo().performClick()
        rotated{
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("recall-config-save").assertIsEnabled()}.isSuccess}
            compose.onNodeWithTag("recall-config-base-changed").assertDoesNotExist()
            draft("recall-config-prompt",changed)
            compose.onNodeWithText(RecallQuestionKind.QUESTION.label).assertIsSelected()
            assertEquals(f.prompt,(question(f).data() as KnowledgeData.Question).prompt)
            tap("recall-config-save");saved()
            val spec=config(f);assertEquals(changed,spec.prompt);assertEquals(RecallQuestionKind.QUESTION,spec.kind);assertTrue(spec.clozes.isEmpty())
        }
    }

    @Test fun rotationAfterBodyChangeDoesNotApplyOldClozesToNewValidOffsets(){
        val f=fixture(RecallQuestionKind.TEXT_CLOZE);properties(f);configure(f)
        val local="本机旧正文上的问法草稿";replace("recall-config-prompt",local)
        val before=config(f);val schedule=runBlocking{app.study.recall().schedule(f.question)}
        runBlocking{app.study.submit(StudyCommand(id(),f.note.id,StudyAction.EDIT,cardId=f.card,expectedRevision=1,title=f.title,body="戊己庚辛：不同答案"))}
        rotated{
            assertConflict(local)
            // The old offsets still fit the new body; only the frozen base detects the semantic change.
            draft("recall-cloze-select-text","甲乙丙丁：固定答案")
            assertEquals(before,config(f));assertEquals(schedule,runBlocking{app.study.recall().schedule(f.question)})
            assertEquals("戊己庚辛：不同答案",runBlocking{app.study.cards(f.note.id).first().single{it.id==f.card}.body})
            assertEquals(f.prompt,(question(f).data() as KnowledgeData.Question).prompt)
        }
    }

    @Test fun rotationAfterQuestionChangeCannotOverwriteNewPromptWithOldDraft(){
        val f=fixture();properties(f);configure(f)
        val local="本机未保存问法";val external="另一处已保存的新问法"
        replace("recall-config-prompt",local)
        val before=config(f);val schedule=runBlocking{app.study.recall().schedule(f.question)}
        val current=question(f)
        runBlocking{app.knowledge.submit(KnowledgeCommand(id(),f.note.id,f.question,current.revision,(current.data() as KnowledgeData.Question).copy(prompt=external)))}
        rotated{
            assertConflict(local)
            assertEquals(external,(question(f).data() as KnowledgeData.Question).prompt)
            assertEquals(before,config(f));assertEquals(schedule,runBlocking{app.study.recall().schedule(f.question)})
        }
    }

    @Test fun rotationAfterConfigurationChangeCannotResetNewMasksOrSchedule(){
        val f=fixture(RecallQuestionKind.TEXT_CLOZE);properties(f);configure(f)
        val local="旧配置的本机草稿";replace("recall-config-prompt",local)
        runBlocking{assertTrue(app.study.recall().configure(id(),f.note.id,f.question,1,1,1,f.card,1,f.prompt,
            RecallQuestionKind.TEXT_CLOZE,clozes=listOf(RecallCloze(2,4))) is RecallOutcome.Success)}
        val external=config(f);val schedule=runBlocking{app.study.recall().schedule(f.question)}
        rotated{
            assertConflict(local)
            compose.onNodeWithText("空位 1：甲乙").assertExists()
            assertEquals(listOf(RecallCloze(2,4)),external.clozes)
            assertEquals(external,config(f));assertEquals(schedule,runBlocking{app.study.recall().schedule(f.question)})
        }
    }

    @Test fun pendingCommandTakesPrecedenceOverCurrentPromptAcrossRecreation(){
        val f=fixture();val pendingPrompt="已封存待核对的问法"
        val spec=runBlocking{app.study.recall().configuration(f.question)!!.spec()}.copy(prompt=pendingPrompt)
        val operation=id()
        val raw=JSONObject().put("id",operation).put("book",f.note.id).put("question",f.question).put("questionRevision",1)
            .put("specRevision",1).put("scheduleRevision",1).put("spec",Base64.encodeToString(RecallCodec.spec(spec),Base64.NO_WRAP)).toString().toByteArray()
        val file=journals.last();val journal=AtomicFile(file);val stream=journal.startWrite();stream.write(raw);journal.finishWrite(stream)
        properties(f);configure(f);draft("recall-config-prompt",pendingPrompt)
        compose.onNodeWithTag("recall-config-prompt").assertIsNotEnabled();assertArrayEquals(raw,journal.readFully())
        // Simulate a committed command whose response was lost before journal cleanup.
        runBlocking{assertTrue(app.study.recall().configure(operation,f.note.id,f.question,1,1,1,f.card,1,pendingPrompt,
            spec.kind,spec.clozes,spec.regions,spec.presentation,true) is RecallOutcome.Success)}
        val committedSchedule=runBlocking{app.study.recall().schedule(f.question)}
        compose.activityRule.scenario.recreate();waitFor("recall-config-prompt")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("recall-config-save").assertIsEnabled()}.isSuccess}
        draft("recall-config-prompt",pendingPrompt);compose.onNodeWithTag("recall-config-prompt").assertIsNotEnabled()
        assertArrayEquals(raw,journal.readFully());tap("recall-config-save");saved()
        assertEquals(pendingPrompt,(question(f).data() as KnowledgeData.Question).prompt)
        assertEquals(pendingPrompt,config(f).prompt);assertFalse(file.exists())
        assertEquals(committedSchedule,runBlocking{app.study.recall().schedule(f.question)})
        assertNotNull(runBlocking{app.study.recall().lookup(operation)})
    }
}
