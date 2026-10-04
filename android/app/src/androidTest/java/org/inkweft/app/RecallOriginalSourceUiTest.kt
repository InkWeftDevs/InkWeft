// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.util.AtomicFile
import android.util.Base64
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Production recall/dialog components with real Room and native source canvas; synthetic data only. */
class RecallOriginalSourceUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val db by lazy{NoteDatabase.open(app)}
    private val journals=mutableListOf<File>()
    private var vm:RecallStudyViewModel?=null
    private fun id()=UUID.randomUUID().toString()
    @After fun finish(){
        compose.runOnIdle{vm?.viewModelScope?.cancel()}
        journals.forEach{AtomicFile(it).delete()};db.close()
    }
    private data class Fixture(val book:Note,val other:Note,val plan:BranchReviewPlan,val sources:List<StudySourceRevisionRow>)
    private fun fixture():Fixture=runBlocking{
        val workspace=WorkspaceRepository(db);val pages=NotebookPages(db);val study=StudyRepository(db)
        val book=workspace.create("当前原页合成测试 "+id().take(8),false,PaperStyle.BLANK)
        val other=workspace.create("跨本秘密 "+id().take(8),false,PaperStyle.BLANK)
        val pageIds=listOf(book.id,pages.addAfter(book.id,book.id,id()).id)
        val cards=pageIds.mapIndexed{index,page->
            val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(70f,90f,10)))
            InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
            id().also{card->study.submit(StudyCommand(id(),book.id,StudyAction.CREATE,card,id(),title="来源 ${index+1}",body="固定答案 ${index+1}",
                source=StudySourceDraft(page,1,stroke.bounds(),listOf(stroke.id))))}
        }
        val transforms=CardTransformRepository(db);val preview=transforms.preview(book.id,cards)
        val target=CardTransforms.mergeTarget(preview.cards,"多来源固定题")
        assertTrue(transforms.submit(preview.plan(CardTransformKind.MERGE,listOf(target))) is CardTransformOutcome.Success)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book.id,id(),0,KnowledgeData.Question(target.id,"合成问题：请回忆两个来源")))
        val plan=BranchReviewRepository(db).prepareCard(MapRef(book.id),target.id,1)
        journals+=File(app.filesDir,"recall-${book.id}.pending");journals+=File(app.filesDir,"recall-${book.id}.draft")
        Fixture(book,other,plan,study.sources(target.id,1).sources)
    }
    private fun exists(tag:String)=compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag:String){compose.waitUntil(15_000){exists(tag)};compose.waitForIdle()}
    private fun tap(tag:String){
        waitFor(tag);val node=compose.onNodeWithTag(tag)
        compose.waitUntil(15_000){runCatching{node.performScrollTo()};runCatching{node.assertIsDisplayed().assertIsEnabled()}.isSuccess}
        node.performTouchInput{click()};compose.waitForIdle()
    }
    private fun hideKeyboard(){compose.runOnIdle{WindowInspector.getGlobalWindowViews().forEach{ViewCompat.getWindowInsetsController(it)?.hide(WindowInsetsCompat.Type.ime())}};compose.waitForIdle()}
    private fun awaitSaved(){compose.waitUntil(15_000){vm!!.ui.value.let{!it.busy&&!it.pending&&it.draftSaved}}}
    private fun answer(value:String){
        compose.onNodeWithTag("recall-answer-text").performScrollTo().performTextReplacement(value);hideKeyboard();awaitSaved()
    }
    private fun start(f:Fixture,fail:AtomicBoolean=AtomicBoolean(false),restoration:StateRestorationTester?=null){
        val repository=RecallStudyRepository(db){if(it==RecallFault.BEFORE_RECEIPT&&fail.get())throw java.io.IOException("synthetic original rollback")}
        compose.runOnIdle{
            vm=RecallStudyViewModel(repository,f.book.id,app,SavedStateHandle())
            compose.activity.viewModelStore.put("durable-recall-${f.book.id}",vm!!)
        }
        val visible=mutableStateOf(true)
        val content:@androidx.compose.runtime.Composable ()->Unit={MaterialTheme{if(visible.value)BranchReviewDialog(f.plan,{visible.value=false})}}
        if(restoration==null)compose.setContent(content)else restoration.setContent(content)
        tap("branch-review-durable");tap("recall-start-practice");waitFor("recall-answer-text");awaitSaved()
    }
    /** Only an explicitly committed recall command may touch its own notes.updatedAt; all other
     * columns/books remain exact. Pure source browsing uses the default, with no permitted changes. */
    private fun authorStamp(recallWriteBook:String?=null):String=runBlocking{
        JSONArray().apply{LibraryBackupRepository.SCHEMA_V15.forEach{table->
            val rows=JSONArray()
            db.openHelper.readableDatabase.query("SELECT * FROM `${table.name}` ORDER BY "+table.keys.joinToString(","){"`$it`"}).use{cursor->
                while(cursor.moveToNext())rows.put(JSONArray().apply{repeat(cursor.columnCount){index->
                    put(if(table.name=="notes"&&recallWriteBook!=null&&cursor.getColumnName(index)=="updatedAt"&&
                        cursor.getString(cursor.getColumnIndexOrThrow("id"))==recallWriteBook)"expected-recall-touch"
                    else when(cursor.getType(index)){
                        Cursor.FIELD_TYPE_NULL->org.json.JSONObject.NULL
                        Cursor.FIELD_TYPE_BLOB->"blob:"+Base64.encodeToString(cursor.getBlob(index),Base64.NO_WRAP)
                        Cursor.FIELD_TYPE_INTEGER->"int:"+cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT->"float:"+cursor.getDouble(index)
                        else->"text:"+cursor.getString(index)
                    })
                }})
            };put(rows)
        }}.toString()
    }
    private fun note(book:String)=runBlocking{checkNotNull(db.notes().note(book))}
    private fun assertRecallTouch(before:NoteRow,startedAt:Long){
        val after=note(before.id)
        assertEquals("A recall command may only touch its own note timestamp",before.copy(updatedAt=after.updatedAt),after)
        assertTrue("The touch must belong to this successful command",after.updatedAt>=before.updatedAt&&after.updatedAt in startedAt..System.currentTimeMillis())
    }
    private fun sameAttempt(session:String,attempt:String,text:String){
        val loaded=runBlocking{RecallStudyRepository(db).loadSession(session)}
        assertEquals(attempt,loaded.current!!.row.id);assertEquals(1,loaded.attempts.size)
        assertEquals(text,loaded.current!!.row.answerText);assertFalse(loaded.current!!.row.answerRevealed)
        compose.onNodeWithTag("recall-answer-text").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(text)))
    }

    @Test fun durableCurrentSourceWaitsForOriginalReceiptAndReturnsToSameAnswerAndWindow(){
        val f=fixture();val fail=AtomicBoolean(false);val restoration=StateRestorationTester(compose)
        start(f,fail,restoration)
        val row=vm!!.ui.value.session!!.current!!.row;val before=authorStamp(f.book.id)
        val beforeDraft=authorStamp();answer("原题尚未封存的作答");assertEquals(beforeDraft,authorStamp())
        val beforeSave=note(f.book.id);val saveAt=System.currentTimeMillis()
        tap("recall-save-answer");awaitSaved();assertRecallTouch(beforeSave,saveAt)
        compose.onNodeWithTag("review-source").assertDoesNotExist()
        for(index in 0..1){
            val action=compose.onNodeWithTag("recall-current-source-$index").performScrollTo().assertIsDisplayed()
            assertTrue(action.fetchSemanticsNode().boundsInRoot.height>=with(compose.density){48.dp.toPx()})
        }
        val beforeUnknown=authorStamp()
        fail.set(true);tap("recall-current-source-1");waitFor("recall-retry-operation")
        assertEquals("Unknown ORIGINAL must roll back even the note timestamp",beforeUnknown,authorStamp())
        compose.onNodeWithTag("review-source").assertDoesNotExist();compose.onNodeWithTag("study-snapshot-viewer").assertDoesNotExist()
        compose.onNodeWithText("固定答案 1\n\n固定答案 2").assertDoesNotExist()
        val pending=File(app.filesDir,"recall-${f.book.id}.pending").readBytes()
        compose.onNodeWithTag("recall-current-source-0").assertIsNotEnabled()
        assertEquals(0,runBlocking{RecallStudyRepository(db).loadAttempt(row.id).row.hintMask})
        assertArrayEquals(pending,File(app.filesDir,"recall-${f.book.id}.pending").readBytes())
        val beforeOriginal=note(f.book.id);val originalAt=System.currentTimeMillis()
        fail.set(false);tap("recall-retry-operation");waitFor("review-source-canvas");assertRecallTouch(beforeOriginal,originalAt)
        val openedSource=authorStamp()
        val chosenPage=runBlocking{NotebookPages(db).activePages(f.book.id).single{it.id==f.sources[1].pageId}}
        compose.onNodeWithText("${f.book.title} · 第 ${chosenPage.position+1} 页").assertIsDisplayed()
        assertEquals(RecallHint.ORIGINAL.bit,runBlocking{RecallStudyRepository(db).loadAttempt(row.id).row.hintMask})
        restoration.emulateSavedInstanceStateRestore();waitFor("review-source-canvas")
        compose.onNodeWithText("${f.book.title} · 第 ${chosenPage.position+1} 页").assertIsDisplayed()
        tap("return-to-review");sameAttempt(row.sessionId,row.id,"原题尚未封存的作答")
        assertEquals("Source restore/read/return must not touch any author column",openedSource,authorStamp())
        answer("回源后继续的作答");assertEquals(openedSource,authorStamp())
        val beforeSecondSave=note(f.book.id);val secondSaveAt=System.currentTimeMillis()
        tap("recall-current-source-0");waitFor("review-source-canvas");assertRecallTouch(beforeSecondSave,secondSaveAt)
        val reopenedSource=authorStamp()
        assertEquals("回源后继续的作答",runBlocking{RecallStudyRepository(db).loadAttempt(row.id).row.answerText})
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(15_000){!exists("review-source")};sameAttempt(row.sessionId,row.id,"回源后继续的作答")
        assertEquals(1,runBlocking{RecallStudyRepository(db).loadAttempt(row.id).hints.count{it.kind=="ORIGINAL"}})
        assertEquals("Back from the source is read-only including all timestamps",reopenedSource,authorStamp())
        assertEquals(before,authorStamp(f.book.id))
    }

    @Test fun recycledCurrentSourceKeepsFixedSnapshotAndSameAttempt(){
        val f=fixture();val source=f.sources[1]
        runBlocking{
            val pages=NotebookPages(db);val active=pages.activePages(f.book.id)
            val stay=active.first{it.id!=source.pageId}.id
            assertTrue(pages.edit(EditPage(id(),f.book.id,source.pageId,PageEditKind.TRASH,InsertPages.orderHash(active.map{it.id}),
                source.inkRevision,stayOnPageId=stay)) is EditPageResult.Applied)
        }
        start(f);val row=vm!!.ui.value.session!!.current!!.row;val before=authorStamp(f.book.id)
        val beforeDraft=authorStamp();answer("原页回收也保留我的作答");assertEquals(beforeDraft,authorStamp())
        val beforeCommands=note(f.book.id);val commandsAt=System.currentTimeMillis()
        tap("recall-current-source-1");waitFor("review-source-unavailable");assertRecallTouch(beforeCommands,commandsAt)
        val unavailableSource=authorStamp()
        compose.onNodeWithTag("review-source-canvas").assertDoesNotExist()
        tap("review-source-fixed-snapshot");waitFor("study-snapshot-canvas")
        compose.onNodeWithText("摘录时快照 · 只读").assertIsDisplayed();tap("study-snapshot-close")
        waitFor("review-source-unavailable");tap("return-to-review");sameAttempt(row.sessionId,row.id,"原页回收也保留我的作答")
        assertEquals("Unavailable source and frozen-snapshot reading must not touch timestamps",unavailableSource,authorStamp())
        assertEquals(before,authorStamp(f.book.id))
    }

    @Test fun sealedComparisonKeepsSixScoresReachableWithinBoundedReadingColumn(){
        val f=fixture();start(f);answer("已作答，等待自评")
        val attempt=vm!!.ui.value.session!!.current!!.row.id
        tap("recall-seal-compare");waitFor("recall-grade-5");awaitSaved()
        val column=compose.onNodeWithTag("recall-reading-column").fetchSemanticsNode().boundsInRoot
        val window=compose.onNodeWithTag("durable-recall").fetchSemanticsNode().boundsInRoot
        assertTrue(column.width<=with(compose.density){840.dp.toPx()}+1f)
        assertEquals(window.center.x,column.center.x,1f)
        for(quality in 0..5){
            val button=compose.onNodeWithTag("recall-grade-$quality").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(button.fetchSemanticsNode().boundsInRoot.height>=with(compose.density){48.dp.toPx()})
        }
        compose.onNodeWithTag("recall-round-result").assertDoesNotExist()
        val row=runBlocking{RecallStudyRepository(db).loadAttempt(attempt).row}
        assertTrue(row.answerRevealed);assertEquals(RecallAttemptStatus.OPEN.name,row.status);assertNull(row.requestedQuality)
    }

    @Test fun foreignSourceIsRejectedWithoutRenderingAnotherNotebook(){
        val f=fixture();val source=f.sources.first().legacy(f.plan.entries.single().cardId).copy(pageId=f.other.id)
        val before=authorStamp();val visible=mutableStateOf(true)
        compose.setContent{MaterialTheme{if(visible.value)ReviewSourceDialog(source,{visible.value=false},recallNotebookId=f.book.id)}}
        waitFor("review-source-unavailable");compose.onNodeWithTag("review-source-canvas").assertDoesNotExist()
        compose.onNodeWithText(f.other.title,substring=true).assertDoesNotExist()
        tap("return-to-review");compose.onNodeWithTag("review-source").assertDoesNotExist();assertEquals(before,authorStamp())
    }
}
