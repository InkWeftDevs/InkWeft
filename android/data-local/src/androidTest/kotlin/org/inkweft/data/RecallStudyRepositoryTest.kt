// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic only. Compiling these tests is not Android execution evidence. */
class RecallStudyRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private val now=1_791_072_000_000L
    private data class Fixture(val book:String,val card:String,val question:String,val plan:BranchReviewPlan)
    private fun fixture(block:suspend(NoteDatabase,Fixture)->Unit)=runBlocking{
        val name="recall-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val book=WorkspaceRepository(db).create("合成持久回忆",false,PaperStyle.BLANK).id
            val stroke=InkStroke(id(),InkPen.PEN,0xff234567.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0),InkSample(30f,30f,10)))
            InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke)))
            val card=id();StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,card,id(),title="固定标题",body="甲乙😀丙丁",
                source=StudySourceDraft(book,1,CanvasBounds(10.0,10.0,40.0,40.0),listOf(stroke.id))))
            val question=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,question,0,KnowledgeData.Question(card,"请回忆这项知识")))
            block(db,Fixture(book,card,question,BranchReviewRepository(db).prepareCard(MapRef(book),card,1)))
        }finally{db.close();context.deleteDatabase(name)}
    }
    private fun ok(value:RecallOutcome):String {assertTrue(value.toString(),value is RecallOutcome.Success);return(value as RecallOutcome.Success).id}
    private suspend fun begin(repo:RecallStudyRepository,f:Fixture,at:Long=now,mode:RecallMode=RecallMode.DUE):RecallLoadedAttempt{
        val session=ok(repo.start(id(),id(),f.plan,mode,at));return requireNotNull(repo.loadSession(session).current)
    }
    private suspend fun compare(repo:RecallStudyRepository,f:Fixture,row:RecallLoadedAttempt,at:Long=now+100):RecallLoadedAttempt{
        ok(repo.revealAnswer(id(),f.book,row.row.id,row.row.revision,at));return repo.loadAttempt(row.row.id)
    }
    private fun counts(db:NoteDatabase)=LibraryBackupRepository.SCHEMA.map{table->db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `${table.name}`").use{it.moveToFirst();it.getLong(0)}}
    @Test fun normalSealedComparisonRetainsRawAndEffectiveAndOneScoreAcrossCollections()=fixture{db,f->
        val repo=RecallStudyRepository(db)
        val collections=List(2){index->id().also{collection->
            KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,collection,0,KnowledgeData.Collection("真实集合 ${index+1}")))
        }}.map{BranchReviewRepository(db).prepareCollection(f.book,it,1)}
        assertEquals(collections[0].entries,collections[1].entries)
        var attempt=begin(repo,f.copy(plan=collections[0]))
        val ink=InkPageFile("本次作答","",listOf(InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(1f,2f,0)))),false,PaperStyle.BLANK).encode()
        ok(repo.saveAnswer(id(),f.book,attempt.row.id,attempt.row.revision,"我的文字答案",ink));attempt=compare(repo,f,repo.loadAttempt(attempt.row.id))
        assertEquals(0,attempt.row.hintMask);assertEquals(listOf("ANSWER_COMPARE"),attempt.hints.map{it.kind})
        assertTrue(repo.saveAnswer(id(),f.book,attempt.row.id,attempt.row.revision,"不能改封存答案",ink) is RecallOutcome.Rejected)
        val operation=id();val expected=attempt.row.revision;val gradeAt=now+200
        ok(repo.grade(operation,f.book,attempt.row.id,expected,5,gradeAt));val schedule=repo.schedule(f.question)!!
        assertEquals(now+200+Sm2Schedule.DAY_MS,schedule.dueAt);assertEquals(1,schedule.repetitions)
        assertEquals(RecallOutcome.Success(attempt.row.id),repo.grade(operation,f.book,attempt.row.id,expected,5,gradeAt))
        assertEquals(schedule,repo.schedule(f.question));assertEquals(1,repo.history(f.book).size)
        val stored=repo.loadAttempt(attempt.row.id);assertEquals(5,stored.row.requestedQuality);assertEquals(5,stored.row.effectiveQuality);assertArrayEquals(ink,stored.row.answerInk)
        assertEquals("我的文字答案",stored.row.answerText);assertTrue(repo.loadSession(attempt.row.sessionId).row.closed)
        // Any collection reuses the same questionId/schedule, not a copied scheduler identity.
        assertEquals(schedule.dueAt,repo.queue(collections[1]).single().dueAt)
        assertEquals(f.question,repo.queue(collections[1]).single().reference.questionId)
        assertTrue(repo.start(id(),id(),f.plan,RecallMode.DUE,now+201) is RecallOutcome.Rejected)
        LibraryBackupRepository(context,db).snapshot().close()
    }
    @Test fun prematureOriginalPersistsThroughReopenAndCapsEffectiveOnly()=fixture{db,f->
        var repo=RecallStudyRepository(db);var row=begin(repo,f)
        val operation=id();ok(repo.consultOriginal(operation,f.book,row.row.id,row.row.revision,now+1))
        assertEquals(RecallOutcome.Success(row.row.id),repo.consultOriginal(operation,f.book,row.row.id,row.row.revision,now+1))
        repo=RecallStudyRepository(db);row=repo.loadSession(repo.resume(f.book)!!.id).current!!
        assertEquals(RecallHint.ORIGINAL.bit,row.row.hintMask);assertEquals(1,row.hints.size)
        row=compare(repo,f,row);ok(repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        val stored=repo.loadAttempt(row.row.id);assertEquals(5,stored.row.requestedQuality);assertEquals(2,stored.row.effectiveQuality)
        assertEquals(0,repo.schedule(f.question)!!.repetitions);assertEquals(218,repo.schedule(f.question)!!.easeHundredths)
    }
    @Test fun afterAnswerOriginalIsComparisonAndPracticeNeverChangesSchedule()=fixture{db,f->
        val repo=RecallStudyRepository(db);var row=compare(repo,f,begin(repo,f,mode=RecallMode.PRACTICE));val before=repo.schedule(f.question)
        ok(repo.consultOriginal(id(),f.book,row.row.id,row.row.revision,now+101));row=repo.loadAttempt(row.row.id)
        assertEquals(0,row.row.hintMask);assertEquals("ORIGINAL_COMPARE",row.hints.last().kind)
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,0,now+200));assertEquals(before,repo.schedule(f.question))
        val done=repo.loadAttempt(row.row.id);assertNull(done.row.effectiveQuality);assertNull(done.row.scheduleAfter)
        LibraryBackupRepository(context,db).snapshot().close()
    }
    @Test fun rollbackAndLostGradeResponseAreAtomicWithIdempotentSameId()=fixture{db,f->
        val repo=RecallStudyRepository(db);val row=compare(repo,f,begin(repo,f));val before=repo.schedule(f.question);val count=counts(db);val op=id()
        val failed=RecallStudyRepository(db){if(it==RecallFault.BEFORE_RECEIPT)error("synthetic rollback")}
        assertEquals(RecallOutcome.Unknown,failed.grade(op,f.book,row.row.id,row.row.revision,4,now+200))
        assertEquals(before,repo.schedule(f.question));assertEquals(count,counts(db));assertFalse(repo.loadSession(row.row.sessionId).row.closed)
        val lost=RecallStudyRepository(db){if(it==RecallFault.AFTER_COMMIT)error("synthetic lost response")}
        ok(lost.grade(op,f.book,row.row.id,row.row.revision,4,now+200));val after=repo.schedule(f.question)
        ok(repo.grade(op,f.book,row.row.id,row.row.revision,4,now+200));assertEquals(after,repo.schedule(f.question))
        assertTrue(repo.grade(op,f.book,row.row.id,row.row.revision,5,now+200) is RecallOutcome.Rejected)
        assertTrue(repo.grade(id(),f.book,row.row.id,row.row.revision,4,now+200) is RecallOutcome.Rejected)
        assertEquals(1,repo.history(f.book).size)
    }
    @Test fun clozeAndRegionMasksAreFrozenAndOnlyExplicitHintsReveal()=fixture{db,f->
        val repo=RecallStudyRepository(db);val source=StudyRepository(db).sources(f.card,1).refs.single()
        ok(repo.configure(id(),f.book,f.question,1,0,0,f.card,1,"请回忆这项知识",RecallQuestionKind.TEXT_CLOZE,listOf(RecallCloze(2,4))))
        var row=begin(repo,f);assertEquals("甲乙［空位 1］丙丁",row.spec.maskedText(row.card.body))
        ok(repo.hint(id(),f.book,row.row.id,row.row.revision,RecallHint.TEXT,0,now+1));row=repo.loadAttempt(row.row.id)
        assertEquals("甲乙😀丙丁",row.spec.maskedText(row.card.body,RecallCodec.revealed(row.row.revealedMasks)))
        assertTrue(repo.hint(id(),f.book,row.row.id,row.row.revision,RecallHint.REGION,0,now+2) is RecallOutcome.Rejected)
        val session=repo.loadSession(row.row.sessionId);ok(repo.closeSession(id(),f.book,session.row.id,session.row.revision,now+3))
        ok(repo.configure(id(),f.book,f.question,1,1,1,f.card,1,"请回忆这项知识",RecallQuestionKind.SOURCE_MASK,regions=listOf(RecallRegion(source,0.1,0.2,0.8,0.9))))
        val next=begin(repo,f,now+4);assertEquals(source,next.spec.regions.single().source);assertEquals(0,next.row.hintMask)
        assertEquals(RecallQuestionKind.TEXT_CLOZE,repo.loadAttempt(row.row.id).spec.kind)
        LibraryBackupRepository(context,db).snapshot().close()
    }
    @Test fun changedContentCannotScoreOldVersionButExplicitPracticeRetainsOldAnswer()=fixture{db,f->
        val repo=RecallStudyRepository(db);var row=begin(repo,f);val source=row.sources.sources.single().snapshot.copyOf()
        StudyRepository(db).submit(StudyCommand(id(),f.book,StudyAction.EDIT,cardId=f.card,expectedRevision=1,title="新标题",body="新正文"))
        row=compare(repo,f,row);assertEquals("甲乙😀丙丁",row.card.body);assertArrayEquals(source,row.sources.sources.single().snapshot)
        assertEquals(RecallOutcome.Rejected("RECALL_SPEC_NEEDS_REVIEW"),repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200,asPractice=true));assertEquals(0,repo.schedule(f.question)!!.repetitions)
        val fresh=repo.refreshReferences(f.plan);assertEquals(2L,fresh.entries.single().cardRevision);assertTrue(repo.queue(fresh).single().needsReview)
    }
    @Test fun reassociatedQuestionCannotScoreFrozenAnswerAgainstAnotherCard()=fixture{db,f->
        val repo=RecallStudyRepository(db);val row=compare(repo,f,begin(repo,f))
        val otherCard=id()
        StudyRepository(db).submit(StudyCommand(id(),f.book,StudyAction.CREATE,otherCard,id(),title="另一张卡",body="另一份答案"))
        val current=db.knowledge().get(f.question)!!;val question=current.data() as KnowledgeData.Question
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,f.question,current.revision,question.copy(cardId=otherCard)))
        val schedule=repo.schedule(f.question);val before=counts(db)
        assertEquals(RecallOutcome.Rejected("RECALL_SPEC_NEEDS_REVIEW"),repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        assertEquals(schedule,repo.schedule(f.question));assertEquals(before,counts(db))
        val stillOpen=repo.loadAttempt(row.row.id)
        assertEquals(RecallAttemptStatus.OPEN.name,stillOpen.row.status);assertEquals(f.card,stillOpen.spec.cardId)
        assertEquals("甲乙😀丙丁",stillOpen.card.body);assertNull(stillOpen.row.scheduleAfter)
        // The old answer remains eligible only for the explicitly selected non-scheduling path.
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200,asPractice=true))
        assertEquals(schedule,repo.schedule(f.question));assertNull(repo.loadAttempt(row.row.id).row.effectiveQuality)
    }
    @Test fun manualStateOnlyChangeKeepsFrozenQuestionEligibleForFormalGrade()=fixture{db,f->
        val repo=RecallStudyRepository(db);val row=compare(repo,f,begin(repo,f))
        val current=db.knowledge().get(f.question)!!;val question=current.data() as KnowledgeData.Question
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,f.question,current.revision,question.copy(state=ManualState.UNDERSTOOD)))
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        assertEquals(1,repo.schedule(f.question)!!.repetitions)
        assertEquals(5,repo.loadAttempt(row.row.id).row.effectiveQuality)
    }
    @Test fun correctionMustFollowSuccessorChainAndKeepsOriginalRows()=fixture{db,f->
        val repo=RecallStudyRepository(db);val first=compare(repo,f,begin(repo,f));ok(repo.grade(id(),f.book,first.row.id,first.row.revision,4,now+200))
        val firstSchedule=repo.schedule(f.question)!!;val second=compare(repo,f,begin(repo,f,firstSchedule.dueAt),firstSchedule.dueAt+1)
        ok(repo.grade(id(),f.book,second.row.id,second.row.revision,5,firstSchedule.dueAt+2));val secondSchedule=repo.schedule(f.question)!!
        assertEquals(RecallOutcome.Rejected("RECALL_CORRECTION_HAS_SUCCESSOR"),repo.withdraw(id(),f.book,first.row.id,secondSchedule.revision,"误点",firstSchedule.dueAt+3))
        val inverse=id();ok(repo.withdraw(inverse,f.book,second.row.id,secondSchedule.revision,"后一次误点",firstSchedule.dueAt+3))
        ok(repo.withdraw(inverse,f.book,second.row.id,secondSchedule.revision,"后一次误点",firstSchedule.dueAt+3))
        val restored=repo.schedule(f.question)!!;assertEquals(firstSchedule.state(),restored.state());assertEquals(first.row.id,restored.lastAttemptId)
        ok(repo.withdraw(id(),f.book,first.row.id,restored.revision,"第一次误点",firstSchedule.dueAt+4))
        assertEquals(Sm2State(),repo.schedule(f.question)!!.state());assertEquals(2,repo.history(f.book).size);assertTrue(repo.filteredHistory(f.book,RecallHistoryFilter(),firstSchedule.dueAt+4).entries.all{it.withdrawn})
        assertEquals(4,repo.loadAttempt(first.row.id).row.requestedQuality);assertEquals(5,repo.loadAttempt(second.row.id).row.requestedQuality)
        LibraryBackupRepository(context,db).snapshot().close()
    }
    @Test fun saveReopenResumeFullBackupRestoreAndReceiptKeepAnswerBytes()=fixture{db,f->
        val repo=RecallStudyRepository(db);val row=begin(repo,f);val op=id()
        db.notes().touch(f.book,1234L);val before=db.notes().note(f.book)!!;val answerAt=System.currentTimeMillis()
        val answerInk=InkPageFile("本次回答","",listOf(InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
            listOf(InkSample(2f,3f,0),InkSample(8f,9f,10)))),false,PaperStyle.BLANK).encode()
        ok(repo.saveAnswer(op,f.book,row.row.id,row.row.revision,"未评分保留😀",answerInk))
        val answered=db.notes().note(f.book)!!;assertEquals(before.copy(updatedAt=answered.updatedAt),answered)
        assertTrue(answered.updatedAt in answerAt..System.currentTimeMillis())
        val savedAnswer=repo.loadAttempt(row.row.id);val originalOp=id()
        val failed=RecallStudyRepository(db){if(it==RecallFault.BEFORE_RECEIPT)error("synthetic original rollback")}
        assertEquals(RecallOutcome.Unknown,failed.consultOriginal(originalOp,f.book,row.row.id,savedAnswer.row.revision,now+1))
        assertEquals(answered,db.notes().note(f.book));assertNull(repo.lookup(originalOp))
        val originalAt=System.currentTimeMillis()
        ok(repo.consultOriginal(originalOp,f.book,row.row.id,savedAnswer.row.revision,now+1))
        val touched=db.notes().note(f.book)!!;assertEquals(answered.copy(updatedAt=touched.updatedAt),touched)
        assertTrue(touched.updatedAt>=answered.updatedAt&&touched.updatedAt in originalAt..System.currentTimeMillis())
        val fixed=repo.loadAttempt(row.row.id);val schedule=repo.schedule(f.question)
        val targetName="recall-restore-${id()}.db";var target=NoteDatabase.open(context,targetName)
        try{
            val backup=LibraryBackupRepository(context,db);val restore=LibraryBackupRepository(context,target)
            backup.snapshot().use{snapshot->snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}}
            target.close();target=NoteDatabase.open(context,targetName)
            val recovered=RecallStudyRepository(target);val session=recovered.resume(f.book)!!;val answer=recovered.loadSession(session.id).current!!
            assertEquals("未评分保留😀",answer.row.answerText);assertEquals(row.row.id,answer.row.id);assertEquals(row.spec,answer.spec)
            assertArrayEquals(row.sources.sources.single().snapshot,answer.sources.sources.single().snapshot)
            assertArrayEquals(answerInk,answer.row.answerInk);assertEquals(fixed.hints,answer.hints)
            assertEquals(RecallHint.ORIGINAL.bit,answer.row.hintMask);assertEquals(schedule,recovered.schedule(f.question))
            assertEquals(touched,target.notes().note(f.book));assertEquals(repo.history(f.book),recovered.history(f.book))
            assertEquals(repo.lookup(op),recovered.lookup(op));assertEquals(repo.lookup(originalOp),recovered.lookup(originalOp))
            ok(recovered.saveAnswer(op,f.book,row.row.id,row.row.revision,"未评分保留😀",answerInk))
            ok(recovered.consultOriginal(originalOp,f.book,row.row.id,savedAnswer.row.revision,now+1))
            assertEquals("Replayed receipts must not touch the restored note again",touched,target.notes().note(f.book))
            assertEquals(fixed.hints,recovered.loadAttempt(row.row.id).hints)
            assertEquals(1,recovered.history(f.book).size);LibraryBackupRepository(context,target).snapshot().close()
        }finally{target.close();context.deleteDatabase(targetName)}
    }
    @Test fun skippedDurableTextAndInkReopenAtNextQuestionWithoutChangingSchedules()=fixture{db,f->
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,id(),0,KnowledgeData.Question(f.card,"第二道独立问题")))
        val plan=BranchReviewRepository(db).prepareCard(MapRef(f.book),f.card,1)
        val repo=RecallStudyRepository(db);var row=begin(repo,f.copy(plan=plan))
        val schedules=plan.entries.associate{it.questionId to repo.schedule(it.questionId)}
        val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,listOf(InkSample(2f,3f,0),InkSample(20f,30f,10)))
        val ink=InkPageFile("跳过前作答","",listOf(stroke),false,PaperStyle.BLANK).encode()
        ok(repo.saveAnswer(id(),f.book,row.row.id,row.row.revision,"不会丢弃的作答",ink));row=repo.loadAttempt(row.row.id)
        ok(repo.consultOriginal(id(),f.book,row.row.id,row.row.revision,now+1));row=repo.loadAttempt(row.row.id)
        val operation=id();ok(repo.skip(operation,f.book,row.row.id,row.row.revision,now+2))
        ok(repo.skip(operation,f.book,row.row.id,row.row.revision,now+2))
        val next=repo.loadSession(row.row.sessionId).current!!
        assertNotEquals(row.row.id,next.row.id);assertEquals(1,next.row.position)
        val name=checkNotNull(db.openHelper.databaseName);db.close()
        val reopened=NoteDatabase.open(context,name)
        try{
            val recovered=RecallStudyRepository(reopened);val restored=recovered.loadSession(recovered.resume(f.book)!!.id)
            assertEquals(row.row.sessionId,restored.row.id);assertEquals(next.row.id,restored.current!!.row.id)
            assertEquals(next.row.questionId,restored.current!!.row.questionId);assertEquals(2,restored.attempts.size)
            val stored=recovered.loadAttempt(row.row.id)
            assertEquals(schedules,plan.entries.associate{it.questionId to recovered.schedule(it.questionId)})
            assertEquals(RecallAttemptStatus.SKIPPED.name,stored.row.status);assertEquals("不会丢弃的作答",stored.row.answerText)
            assertArrayEquals(ink,stored.row.answerInk);assertEquals(RecallHint.ORIGINAL.bit,stored.row.hintMask)
            assertNull(stored.row.requestedQuality);assertNull(stored.row.effectiveQuality);assertNull(stored.row.scheduleAfter)
            assertEquals(RecallAttemptStatus.OPEN.name,restored.current!!.row.status);assertFalse(restored.row.closed)
        }finally{reopened.close()}
    }
    @Test fun timezoneChangeAndDatabaseReopenKeepUtcScheduleAndSameOpenAttempt()=fixture{db,f->
        val repo=RecallStudyRepository(db);val graded=compare(repo,f,begin(repo,f))
        ok(repo.grade(id(),f.book,graded.row.id,graded.row.revision,5,now+200))
        val schedule=repo.schedule(f.question)!!
        var active=begin(repo,f,now+300,RecallMode.PRACTICE)
        ok(repo.saveAnswer(id(),f.book,active.row.id,active.row.revision,"跨时区继续原作答",byteArrayOf()))
        active=repo.loadAttempt(active.row.id)
        ok(repo.consultOriginal(id(),f.book,active.row.id,active.row.revision,now+301))
        val name=checkNotNull(db.openHelper.databaseName);val originalZone=java.util.TimeZone.getDefault();db.close()
        try{
            for(zoneId in listOf("Pacific/Kiritimati","America/Los_Angeles")){
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zoneId))
                val reopened=NoteDatabase.open(context,name)
                try{
                    val current=RecallStudyRepository(reopened);val session=current.resume(f.book)!!
                    val restored=current.loadSession(session.id)
                    assertEquals(active.row.sessionId,session.id);assertEquals(active.row.id,restored.current!!.row.id)
                    assertEquals("跨时区继续原作答",restored.current!!.row.answerText)
                    assertEquals(RecallHint.ORIGINAL.bit,restored.current!!.row.hintMask);assertEquals(1,restored.attempts.size)
                    assertEquals(schedule,current.schedule(f.question));assertEquals(schedule.dueAt,current.queue(f.plan).single().dueAt)
                    val day=java.time.Instant.ofEpochMilli(now+200).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    val range=RecallHistoryFilter.localDays(day,day,zoneId)
                    assertTrue(current.filteredHistory(f.book,RecallHistoryFilter(fromInclusive=range.first,untilExclusive=range.second),now+400)
                        .entries.any{it.row.id==graded.row.id})
                }finally{reopened.close()}
            }
        }finally{java.util.TimeZone.setDefault(originalZone)}
    }
    @Test fun answerCapacityRejectsWithoutAnyPartialScoreOrInkWrite()=fixture{db,f->
        val repo=RecallStudyRepository(db);val row=begin(repo,f);val before=counts(db)
        assertTrue(repo.saveAnswer(id(),f.book,row.row.id,row.row.revision,"x".repeat(RecallLimits.MAX_ANSWER_CHARS+1),byteArrayOf()) is RecallOutcome.Rejected)
        assertTrue(repo.saveAnswer(id(),f.book,row.row.id,row.row.revision,"",ByteArray(RecallLimits.MAX_ANSWER_INK_BYTES+1)) is RecallOutcome.Rejected)
        assertEquals(before,counts(db));assertEquals("",repo.loadAttempt(row.row.id).row.answerText);assertEquals(0,repo.schedule(f.question)!!.repetitions)
    }
    @Test fun backwardClockAndPostSealHintsRejectBeforeWrites()=fixture{db,f->
        val repo=RecallStudyRepository(db);var row=begin(repo,f)
        ok(repo.hint(id(),f.book,row.row.id,row.row.revision,RecallHint.HINT,null,now+1000));row=repo.loadAttempt(row.row.id)
        val before=counts(db)
        assertEquals(RecallOutcome.Rejected("RECALL_CLOCK_MOVED_BACK"),repo.revealAnswer(id(),f.book,row.row.id,row.row.revision,now+10))
        assertEquals(before,counts(db));row=compare(repo,f,row,now+1001)
        assertEquals(RecallOutcome.Rejected("RECALL_ANSWER_ALREADY_REVEALED"),repo.hint(id(),f.book,row.row.id,row.row.revision,RecallHint.HINT,null,now+1002))
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,4,now+1003));LibraryBackupRepository(context,db).snapshot().close()
    }

    @Test fun sharedAnnotationFreezesIndependentlyWhileColorChangeDoesNotInvalidateAnswer()=fixture{db,f->
        val presentation=id();val knowledge=KnowledgeRepository(db)
        knowledge.submit(KnowledgeCommand(id(),f.book,presentation,0,KnowledgeData.CardPresentation(f.card,"原注释",CardTint.BLUE,CardTint.ROSE)))
        val repo=RecallStudyRepository(db);var row=begin(repo,f)
        assertEquals("原注释",row.presentation!!.annotation);assertEquals(RecallPresentationRef(presentation,1),row.spec.presentation)
        knowledge.submit(KnowledgeCommand(id(),f.book,presentation,1,KnowledgeData.CardPresentation(f.card,"原注释",CardTint.GREEN,CardTint.ROSE)))
        assertFalse(repo.queue(f.plan).single().needsReview)
        knowledge.submit(KnowledgeCommand(id(),f.book,presentation,2,KnowledgeData.CardPresentation(f.card,"新注释",CardTint.GREEN,CardTint.ROSE)))
        assertTrue(repo.queue(f.plan).single().needsReview);row=repo.loadAttempt(row.row.id)
        assertEquals("原注释",row.presentation!!.annotation);assertEquals(CardTint.BLUE,row.presentation!!.cardColor)
        row=compare(repo,f,row)
        assertEquals(RecallOutcome.Rejected("RECALL_SPEC_NEEDS_REVIEW"),repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        ok(repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200,asPractice=true));LibraryBackupRepository(context,db).snapshot().close()
    }

    @Test fun readQueriesKeepFrozenHistoryDescriptionsAndCurrentScheduleFiltersWithoutWrites()=fixture{db,f->
        val repo=RecallStudyRepository(db)
        ok(repo.configure(id(),f.book,f.question,1,0,0,f.card,1,"请回忆这项知识",RecallQuestionKind.TEXT_CLOZE,listOf(RecallCloze(2,4))))
        var first=begin(repo,f)
        ok(repo.hint(id(),f.book,first.row.id,first.row.revision,RecallHint.TEXT,0,now+1))
        first=compare(repo,f,repo.loadAttempt(first.row.id))
        ok(repo.grade(id(),f.book,first.row.id,first.row.revision,5,now+200))
        val graded=repo.schedule(f.question)!!
        val queries=RecallStudyQueries(db)
        assertEquals(1,queries.filteredHistory(f.book,RecallHistoryFilter(due=RecallDueFilter.NOT_DUE),now+201).total)
        ok(repo.withdraw(id(),f.book,first.row.id,graded.revision,"合成误点评分",now+202))
        val restored=repo.schedule(f.question)!!
        ok(repo.configure(id(),f.book,f.question,1,1,restored.revision,f.card,1,"新的问法",RecallQuestionKind.QUESTION))
        val fresh=f.copy(plan=repo.refreshReferences(f.plan))
        val second=compare(repo,fresh,begin(repo,fresh,now+300,RecallMode.PRACTICE),now+301)
        ok(repo.grade(id(),f.book,second.row.id,second.row.revision,4,now+302))
        val before=counts(db);val note=db.notes().note(f.book);val schedule=repo.schedule(f.question);val receipts=db.recall().receipts()

        val all=queries.filteredHistory(f.book,RecallHistoryFilter(),now+400)
        assertEquals(listOf(second.row.id,first.row.id),all.entries.map{it.row.id})
        assertEquals(listOf("新的问法","请回忆这项知识"),all.entries.map{it.prompt})
        assertEquals(listOf(RecallQuestionKind.QUESTION,RecallQuestionKind.TEXT_CLOZE),all.entries.map{it.kind})
        assertEquals(listOf(false,true),all.entries.map{it.withdrawn})
        assertTrue(all.entries.all{it.scheduleRevision==schedule!!.revision})
        assertEquals(all,repo.filteredHistory(f.book,RecallHistoryFilter(),now+400))
        val page=queries.filteredHistory(f.book,RecallHistoryFilter(),now+400,offset=1,limit=1)
        assertEquals(2,page.total);assertEquals(1,page.offset);assertEquals(listOf(first.row.id),page.entries.map{it.row.id})
        val pastEnd=queries.filteredHistory(f.book,RecallHistoryFilter(),now+400,offset=2,limit=1)
        assertEquals(2,pastEnd.total);assertTrue(pastEnd.entries.isEmpty())
        val hinted=RecallHistoryFilter(due=RecallDueFilter.DUE,kind=RecallQuestionKind.TEXT_CLOZE,usedHint=true,fromInclusive=now+200,untilExclusive=now+201)
        assertEquals(listOf(first.row.id),queries.filteredHistory(f.book,hinted,now+400).entries.map{it.row.id})
        assertEquals(0,queries.filteredHistory(f.book,RecallHistoryFilter(due=RecallDueFilter.NOT_DUE),now+400).total)
        assertEquals(listOf(second.row.id),queries.filteredHistory(f.book,RecallHistoryFilter(usedHint=false),now+400).entries.map{it.row.id})
        val old=queries.loadAttempt(first.row.id)
        assertEquals(first.spec,old.spec);assertEquals(first.card,old.card);assertNotNull(old.correction)
        assertArrayEquals(first.sources.sources.single().snapshot,old.sources.sources.single().snapshot)
        assertEquals(schedule,old.schedule);assertNull(queries.loadSession(first.row.sessionId).current);assertNull(queries.resume(f.book))
        assertEquals(queries.history(f.book),repo.history(f.book))
        assertEquals(before,counts(db));assertEquals(note,db.notes().note(f.book));assertEquals(schedule,repo.schedule(f.question));assertEquals(receipts,db.recall().receipts())
    }

    @Test fun historyAndClosedSessionQueriesDoNotHydrateFixedSourcesOrUnstartedAttempts()=fixture{db,f->
        val other=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,other,0,KnowledgeData.Question(f.card,"第二个问题")))
        val plan=BranchReviewRepository(db).prepareCard(MapRef(f.book),f.card,1)
        val repo=RecallStudyRepository(db);val sessionId=ok(repo.start(id(),id(),plan,RecallMode.PRACTICE,now))
        val queries=RecallStudyQueries(db);val active=withTimeout(5_000){queries.observeSession(sessionId).first()}
        assertEquals(2,active.attempts.size);assertEquals(sessionId,queries.resume(f.book)!!.id)
        val started=requireNotNull(active.current).row.id
        assertEquals(listOf(started),withTimeout(5_000){queries.observeHistory(f.book).first()}.map{it.id})
        ok(repo.closeSession(id(),f.book,sessionId,active.row.revision,now+1))
        // Summary projections must not require the source BLOBs needed only by the detail query.
        db.openHelper.writableDatabase.execSQL("DELETE FROM study_source_revisions WHERE sourceId=?",arrayOf(f.card))
        val before=counts(db);val note=db.notes().note(f.book)
        val closed=queries.loadSession(sessionId)
        assertTrue(closed.row.closed);assertNull(closed.current);assertEquals(2,closed.attempts.size)
        assertEquals(listOf(started),queries.history(f.book).map{it.id})
        assertEquals(listOf(started),queries.filteredHistory(f.book,RecallHistoryFilter(),now+2).entries.map{it.row.id})
        assertEquals("RECALL_SOURCE_VERSION_CHANGED",runCatching{queries.loadAttempt(started)}.exceptionOrNull()?.message)
        assertEquals(before,counts(db));assertEquals(note,db.notes().note(f.book))
    }

    @Test fun detailCommandsAndArchiveShareTheFrozenPresentationValidation()=fixture{db,f->
        val presentation=id()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),f.book,presentation,0,KnowledgeData.CardPresentation(f.card,"固定注释")))
        val repo=RecallStudyRepository(db);val row=compare(repo,f,begin(repo,f))
        val queries=RecallStudyQueries(db)
        assertEquals("固定注释",queries.loadAttempt(row.row.id).presentation!!.annotation)
        repo.validateArchive()
        db.openHelper.writableDatabase.execSQL("UPDATE knowledge_revisions SET removed=1 WHERE id=? AND revision=1",arrayOf(presentation))
        val before=counts(db);val schedule=repo.schedule(f.question)
        assertEquals("RECALL_PRESENTATION_UNAVAILABLE",runCatching{queries.loadAttempt(row.row.id)}.exceptionOrNull()?.message)
        assertEquals("RECALL_PRESENTATION_UNAVAILABLE",runCatching{repo.loadAttempt(row.row.id)}.exceptionOrNull()?.message)
        assertEquals("RECALL_PRESENTATION_UNAVAILABLE",runCatching{repo.validateArchive()}.exceptionOrNull()?.message)
        assertEquals(RecallOutcome.Rejected("RECALL_PRESENTATION_UNAVAILABLE"),repo.grade(id(),f.book,row.row.id,row.row.revision,5,now+200))
        assertEquals(before,counts(db));assertEquals(schedule,repo.schedule(f.question))
    }

    @Test fun schema15MigrationAddsRecallWithoutRewritingAuthorInkQuestionOrAnnotation()=runBlocking{
        val name="recall-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/15.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val book=id();val card=id();val question=id();val presentation=id()
        val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
        val ink=InkStrokeCodec.encode(stroke);val questionBytes=KnowledgeCodec.encode(KnowledgeData.Question(card,"旧问法"))
        val annotationBytes=KnowledgeCodec.encode(KnowledgeData.CardPresentation(card,"旧共享注释"))
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use{sql->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            sql.execSQL("INSERT INTO notes VALUES (?,1,'旧本','',1234)",arrayOf(book));sql.execSQL("INSERT INTO note_revisions VALUES (?,1,'旧本','',1234)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace (noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision) VALUES (?,0,0,'','',0,NULL,500,707,0,0)",arrayOf(book))
            sql.execSQL("INSERT INTO study_cards VALUES (?,?,1,'旧卡','旧正文',NULL)",arrayOf(card,book))
            sql.execSQL("INSERT INTO study_card_revisions VALUES (?,1,'旧卡','旧正文',NULL)",arrayOf(card))
            sql.execSQL("INSERT INTO notebook_pages VALUES (?,?,0,0,0,500,707,0,NULL,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES (?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES (?,?,?,1,1,1)",arrayOf(stroke.id,book,ink))
            for((key,bytes)in listOf(question to questionBytes,presentation to annotationBytes)){
                sql.execSQL("INSERT INTO knowledge_records VALUES (?,?,1,?,0)",arrayOf(key,book,bytes))
                sql.execSQL("INSERT INTO knowledge_revisions VALUES (?,1,?,?,0)",arrayOf(key,book,bytes))
            }
            sql.version=15
        }
        val db=NoteDatabase.open(context,name)
        try{
            assertEquals(16,db.openHelper.readableDatabase.version);assertEquals("旧正文",db.study().card(card)!!.body)
            assertEquals(WorkspaceRow(book,paper=0),db.workspace().get(book))
            assertArrayEquals(questionBytes,db.knowledge().get(question)!!.payload);assertArrayEquals(annotationBytes,db.knowledge().get(presentation)!!.payload)
            db.openHelper.readableDatabase.query("SELECT payload FROM ink_strokes WHERE id=?",arrayOf(stroke.id)).use{cursor->assertTrue(cursor.moveToFirst());assertArrayEquals(ink,cursor.getBlob(0))}
            val repo=RecallStudyRepository(db);assertTrue(repo.history(book).isEmpty());assertNull(repo.schedule(question))
            val plan=BranchReviewRepository(db).prepareCard(MapRef(book),card,1)
            val session=ok(repo.start(id(),id(),plan,RecallMode.DUE,now));val current=repo.loadSession(session).current!!
            assertEquals("旧问法",current.spec.prompt);assertEquals("旧共享注释",current.presentation!!.annotation)
            assertArrayEquals(questionBytes,db.knowledge().get(question)!!.payload);assertEquals(1L,db.knowledge().get(question)!!.revision)
        }finally{db.close();context.deleteDatabase(name)}
    }

}
