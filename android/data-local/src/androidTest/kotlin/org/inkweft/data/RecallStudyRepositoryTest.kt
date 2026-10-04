// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
        val repo=RecallStudyRepository(db);var attempt=begin(repo,f)
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
        assertEquals(schedule.dueAt,repo.queue(BranchReviewPlan(f.plan.ref,f.plan.branchId,"另一个集合",f.plan.cardCount,0,f.plan.entries)).single().dueAt)
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
        ok(repo.saveAnswer(op,f.book,row.row.id,row.row.revision,"未评分保留😀",byteArrayOf()))
        val targetName="recall-restore-${id()}.db";var target=NoteDatabase.open(context,targetName)
        try{
            val backup=LibraryBackupRepository(context,db);val restore=LibraryBackupRepository(context,target)
            backup.snapshot().use{snapshot->snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}}
            target.close();target=NoteDatabase.open(context,targetName)
            val recovered=RecallStudyRepository(target);val session=recovered.resume(f.book)!!;val answer=recovered.loadSession(session.id).current!!
            assertEquals("未评分保留😀",answer.row.answerText);assertEquals(row.row.id,answer.row.id);assertEquals(row.spec,answer.spec)
            assertArrayEquals(row.sources.sources.single().snapshot,answer.sources.sources.single().snapshot)
            ok(recovered.saveAnswer(op,f.book,row.row.id,row.row.revision,"未评分保留😀",byteArrayOf()))
            assertEquals(1,recovered.history(f.book).size);LibraryBackupRepository(context,target).snapshot().close()
        }finally{target.close();context.deleteDatabase(targetName)}
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
