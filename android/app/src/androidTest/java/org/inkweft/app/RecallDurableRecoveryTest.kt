// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Recreates ViewModels and their private atomic journals, not a claim of killing the OS process. */
class RecallDurableRecoveryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun<T>main(block:()->T):T{var value:T?=null;InstrumentationRegistry.getInstrumentation().runOnMainSync{value=block()};@Suppress("UNCHECKED_CAST")return value as T}
    private fun fixture(block:suspend(RecallStudyRepository,BranchReviewPlan,AtomicBoolean)->Unit)=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="recall-ui-${id()}.db";val db=NoteDatabase.open(context,name)
        val fail=AtomicBoolean(false);val book=WorkspaceRepository(db).create("合成恢复",false,PaperStyle.BLANK).id
        try{
            val card=id();StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,card,id(),title="答案标题",body="固定答案暗号"))
            KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(card,"合成问题")))
            val repo=RecallStudyRepository(db){if(it==RecallFault.BEFORE_RECEIPT&&fail.get())throw java.io.IOException("synthetic rollback")}
            block(repo,BranchReviewRepository(db).prepareCard(MapRef(book),card,1),fail)
        }finally{db.close();context.deleteDatabase(name);java.io.File(context.filesDir,"recall-$book.pending").delete();java.io.File(context.filesDir,"recall-$book.draft").delete()}
    }
    @Test fun originalGateDoesNotExposeBeforeDurableFactAndUnknownReusesId()=fixture{repo,plan,fail->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        var first:RecallStudyViewModel?=null;var second:RecallStudyViewModel?=null
        try{
            val a=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}};first=a
            withTimeout(15000){a.ui.first{!it.loading}};main{a.start(RecallMode.DUE)}
            withTimeout(15000){a.ui.first{it.session?.current!=null&&!it.busy}}
            fail.set(true);var exposed=false;main{assertTrue(a.original{exposed=true})}
            withTimeout(15000){a.ui.first{it.pending&&!it.busy}}
            assertFalse(exposed);assertEquals(0,repo.loadSession(a.sessionId!!).current!!.row.hintMask)
            val originalBytes=java.io.File(context.filesDir,"recall-${plan.ref.notebookId}.pending").readBytes()
            main{a.viewModelScope.cancel()}
            val b=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}};second=b
            withTimeout(15000){b.ui.first{it.pending&&!it.loading&&it.session?.current!=null}}
            assertArrayEquals(originalBytes,java.io.File(context.filesDir,"recall-${plan.ref.notebookId}.pending").readBytes())
            fail.set(false);main{assertTrue(b.original{exposed=true})}
            withTimeout(15000){b.ui.first{!it.pending&&!it.busy&&it.navigationReady}}
            assertFalse(exposed);assertEquals(RecallHint.ORIGINAL.bit,repo.loadSession(b.sessionId!!).current!!.row.hintMask)
            main{b.finishNavigation()};assertTrue(exposed)
            assertEquals(1,repo.loadSession(b.sessionId!!).current!!.hints.count{it.kind=="ORIGINAL"})
        }finally{main{first?.viewModelScope?.cancel();second?.viewModelScope?.cancel()}}
    }
    @Test fun repeatedOriginalSavesNewAnswerAndCancellationNeverRunsStaleContinuation()=fixture{repo,plan,fail->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vm=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
        try{
            withTimeout(15000){vm.ui.first{!it.loading}};main{vm.start(RecallMode.PRACTICE)}
            withTimeout(15000){vm.ui.first{it.session?.current!=null&&!it.busy}}
            val session=vm.sessionId!!;val attempt=vm.ui.value.session!!.current!!.row.id
            var visits=0;main{assertTrue(vm.original{visits++})}
            withTimeout(15000){vm.ui.first{it.navigationReady&&!it.busy&&!it.pending}}
            main{vm.cancelNavigation();vm.finishNavigation()};assertEquals(0,visits)
            val first=repo.loadAttempt(attempt);assertEquals(1,first.hints.count{it.kind=="ORIGINAL"})
            main{vm.draft(text="回源后继续写下的原作答")};withTimeout(15000){vm.ui.first{it.draftSaved}}
            fail.set(true);main{assertTrue(vm.original{visits++})}
            withTimeout(15000){vm.ui.first{it.pending&&!it.busy}}
            main{vm.finishNavigation()};assertEquals(0,visits);assertEquals("",repo.loadAttempt(attempt).row.answerText)
            fail.set(false);main{vm.retry()}
            withTimeout(15000){vm.ui.first{it.navigationReady&&!it.busy&&!it.pending}}
            assertEquals("回源后继续写下的原作答",repo.loadAttempt(attempt).row.answerText)
            main{vm.finishNavigation();vm.finishNavigation()};assertEquals(1,visits)
            val same=repo.loadSession(session)
            assertEquals(attempt,same.current!!.row.id);assertEquals(1,same.attempts.size)
            assertEquals(1,same.current!!.hints.count{it.kind=="ORIGINAL"});assertFalse(same.current!!.row.answerRevealed)
            assertEquals("回源后继续写下的原作答",vm.ui.value.draftText)
        }finally{main{vm.viewModelScope.cancel()}}
    }
    @Test fun cancellingUnknownAnswerBeforeOriginalRetryDoesNotOpenOrRecordOriginal()=fixture{repo,plan,fail->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vm=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
        try{
            withTimeout(15000){vm.ui.first{!it.loading}};main{vm.start(RecallMode.DUE)}
            withTimeout(15000){vm.ui.first{it.session?.current!=null&&!it.busy}}
            val attempt=vm.ui.value.session!!.current!!.row.id
            main{vm.draft(text="取消回源仍保留作答")};withTimeout(15000){vm.ui.first{it.draftSaved}}
            var exposed=false;fail.set(true);main{assertTrue(vm.original{exposed=true})}
            withTimeout(15000){vm.ui.first{it.pending&&!it.busy}}
            main{vm.cancelNavigation()};fail.set(false);main{vm.retry()}
            withTimeout(15000){vm.ui.first{!it.pending&&!it.busy}}
            main{vm.finishNavigation()};assertFalse(exposed)
            val row=repo.loadAttempt(attempt)
            assertEquals("取消回源仍保留作答",row.row.answerText);assertEquals(0,row.row.hintMask);assertTrue(row.hints.isEmpty())
        }finally{main{vm.viewModelScope.cancel()}}
    }
    @Test fun unsubmittedTextAndStrokeCheckpointRecoverIntoSameAttemptWithoutAuthorPageWrite()=fixture{repo,plan,_->
        val context=InstrumentationRegistry.getInstrumentation().targetContext;var a:RecallStudyViewModel?=null;var b:RecallStudyViewModel?=null
        try{
            a=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
            withTimeout(15000){a!!.ui.first{!it.loading}};main{a!!.start(RecallMode.PRACTICE)}
            withTimeout(15000){a!!.ui.first{it.session?.current!=null&&!it.busy}}
            val attempt=a!!.ui.value.session!!.current!!.row.id
            main{a!!.draft(text="仍在书写的文字")};withTimeout(15000){a!!.ui.first{it.draftSaved}}
            val stroke=InkStroke(id(),InkPen.PEN,0xff135790.toInt(),3f,InkTool.STYLUS,listOf(InkSample(4f,5f,0),InkSample(9f,12f,10)))
            main{a!!.checkpoint(stroke)};withTimeout(15000){a!!.ui.first{it.draftSaved}};main{a!!.viewModelScope.cancel()}
            b=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
            withTimeout(15000){b!!.ui.first{!it.loading&&it.session?.current!=null}}
            assertEquals(attempt,b!!.ui.value.session!!.current!!.row.id);assertEquals("仍在书写的文字",b!!.ui.value.draftText)
            assertEquals(stroke.id,RecallStudyViewModel.answerStrokes(b!!.ui.value.draftInk).single().id)
            assertEquals("",repo.loadAttempt(attempt).row.answerText)
            main{b!!.save()};withTimeout(15000){b!!.ui.first{!it.busy&&!it.pending&&!it.draftDirty}}
            assertEquals("仍在书写的文字",repo.loadAttempt(attempt).row.answerText);assertEquals(0,repo.schedule(plan.entries.single().questionId)!!.repetitions)
        }finally{main{a?.viewModelScope?.cancel();b?.viewModelScope?.cancel()}}
    }
    @Test fun endingRoundSavesPrivateDraftBeforeAbandoningAndDoesNotInventScore()=fixture{repo,plan,_->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vm=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
        try{
            withTimeout(15000){vm.ui.first{!it.loading}};main{vm.start(RecallMode.DUE)}
            withTimeout(15000){vm.ui.first{it.session?.current!=null&&!it.busy}}
            val attempt=vm.ui.value.session!!.current!!.row.id
            main{vm.draft(text="结束前仍需保存的作答")};withTimeout(15000){vm.ui.first{it.draftSaved}}
            main{vm.closeSession()};withTimeout(15000){vm.ui.first{it.session?.row?.closed==true&&!it.busy&&!it.pending}}
            val row=repo.loadAttempt(attempt).row
            assertEquals("结束前仍需保存的作答",row.answerText);assertEquals(RecallAttemptStatus.ABANDONED.name,row.status)
            assertNull(row.requestedQuality);assertNull(row.scheduleAfter);assertEquals(0,repo.schedule(row.questionId)!!.repetitions)
        }finally{main{vm.viewModelScope.cancel()}}
    }
    @Test fun atomicBackupPendingFileIsRecoveredRatherThanReplacedByNewOperation()=fixture{repo,plan,fail->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val a=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
        var b:RecallStudyViewModel?=null
        try{
            withTimeout(15000){a.ui.first{!it.loading}};fail.set(true);main{a.start(RecallMode.DUE)}
            withTimeout(15000){a.ui.first{it.pending&&!it.busy}};main{a.viewModelScope.cancel()}
            val file=java.io.File(context.filesDir,"recall-${plan.ref.notebookId}.pending");val bytes=file.readBytes()
            assertTrue(file.renameTo(java.io.File(file.path+".bak")))
            assertTrue(RecallStudyViewModel.hasPending(context,plan.ref.notebookId));assertArrayEquals(bytes,file.readBytes())
            b=main{RecallStudyViewModel(repo,plan.ref.notebookId,context,SavedStateHandle()).also{it.open(plan)}}
            withTimeout(15000){b!!.ui.first{it.pending&&!it.loading}}
            fail.set(false);main{b!!.retry()};withTimeout(15000){b!!.ui.first{it.session?.current!=null&&!it.busy&&!it.pending}}
            assertEquals(1,b!!.ui.value.session!!.attempts.size);assertFalse(file.exists());assertEquals(0,repo.history(plan.ref.notebookId).single().hintMask)
        }finally{main{a.viewModelScope.cancel();b?.viewModelScope?.cancel()}}
    }

}
