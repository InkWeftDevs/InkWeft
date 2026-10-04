// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

enum class RecallFault{BEFORE_RECEIPT,AFTER_COMMIT}
sealed interface RecallOutcome {
    data class Success(val id:String):RecallOutcome
    data class Rejected(val reason:String):RecallOutcome
    data object Unknown:RecallOutcome
}
data class RecallQueueItem(val reference:BranchReviewEntryRef,val prompt:String,val kind:RecallQuestionKind,val dueAt:Long,
    val needsReview:Boolean,val specRevision:Long,val scheduleRevision:Long)
data class RecallLoadedAttempt(val row:RecallAttemptRow,val spec:RecallQuestionSpec,val card:StudyCardRevisionRow,val sources:FrozenStudySources,
    val hints:List<RecallHintRow>,val correction:RecallCorrectionRow?,val schedule:RecallScheduleRow?,val presentation:KnowledgeData.CardPresentation?=null)
data class RecallHistoryEntry(val row:RecallAttemptSummary,val prompt:String,val kind:RecallQuestionKind,val withdrawn:Boolean,val scheduleRevision:Long)
data class RecallHistoryPage(val entries:List<RecallHistoryEntry>,val total:Int,val offset:Int)
data class RecallLoadedSession(val row:RecallSessionRow,val attempts:List<RecallAttemptSummary>,val current:RecallLoadedAttempt?)

/** One question identity owns one schedule across every collection. All state-changing methods use receipts. */
class RecallStudyRepository(private val db:NoteDatabase,private val fault:(RecallFault)->Unit={}){
    private val dao get()=db.recall()
    fun observeSession(id:String)=db.invalidationTracker.createFlow("recall_sessions","recall_attempts","recall_hint_events","recall_schedules","recall_corrections","recall_questions","study_cards","knowledge_records").map{loadSession(id)}
    fun observeHistory(book:String)=db.invalidationTracker.createFlow("recall_attempts","recall_hint_events","recall_schedules","recall_corrections").map{history(book)}
    suspend fun resume(book:String):RecallSessionRow?=db.withTransaction{requireBook(book);dao.sessions(book).lastOrNull{!it.closed}}
    suspend fun queue(plan:BranchReviewPlan):List<RecallQueueItem> = db.withTransaction {
        requireBook(plan.ref.notebookId)
        val positions=dao.questionOrder(plan.ref.notebookId).associate{it.questionId to it.position}
        val cards=plan.entries.map{it.cardId}.distinct().withIndex().associate{it.value to it.index}
        plan.entries.map{ref->
            val q=requireNotNull(db.knowledge().get(ref.questionId)){"RECALL_QUESTION_UNAVAILABLE"};val data=q.data() as? KnowledgeData.Question
            require(q.notebookId==plan.ref.notebookId&&!q.removed&&data?.cardId==ref.cardId&&q.revision==ref.questionRevision){"RECALL_QUESTION_CHANGED"}
            val card=requireNotNull(db.study().card(ref.cardId));require(card.trashedAt==null&&card.notebookId==plan.ref.notebookId&&card.revision==ref.cardRevision){"RECALL_CARD_CHANGED"}
            val configured=dao.question(ref.questionId);val schedule=dao.schedule(ref.questionId)
            val spec=configured?.spec()
            RecallQueueItem(ref,data!!.prompt,spec?.kind?:RecallQuestionKind.QUESTION,schedule?.dueAt?:0,
                spec?.let{it.cardId!=card.id||it.cardRevision!=card.revision||it.prompt!=data.prompt||!presentationCurrent(it)}?:false,configured?.revision?:0L,schedule?.revision?:0L)
        }.sortedWith(compareBy<RecallQueueItem>{cards.getValue(it.reference.cardId)}.thenBy{positions[it.reference.questionId]?:Long.MAX_VALUE}.thenBy{it.prompt}.thenBy{it.reference.questionId})
    }
    /** Explicit preparation refresh. Never called while starting/retrying a frozen pending session command. */
    suspend fun refreshReferences(plan:BranchReviewPlan):BranchReviewPlan = db.withTransaction {
        requireBook(plan.ref.notebookId)
        val entries=plan.entries.map{ref->
            val question=requireNotNull(db.knowledge().get(ref.questionId)){"RECALL_QUESTION_UNAVAILABLE"}
            val data=question.data() as? KnowledgeData.Question
            require(!question.removed&&question.notebookId==plan.ref.notebookId&&data?.cardId==ref.cardId){"RECALL_QUESTION_CHANGED"}
            val card=requireNotNull(db.study().card(ref.cardId)){"RECALL_CARD_CHANGED"}
            require(card.trashedAt==null&&card.notebookId==plan.ref.notebookId){"RECALL_CARD_CHANGED"}
            ref.copy(questionRevision=question.revision,cardRevision=card.revision)
        }
        BranchReviewPlan(plan.ref,plan.branchId,plan.title,plan.cardCount,plan.withoutQuestionCount,entries,plan.scope,plan.totalQuestionCount,plan.otherStateOnlyCardCount)
    }
    suspend fun configuration(questionId:String)=db.withTransaction{dao.question(questionId)}
    suspend fun schedule(questionId:String)=db.withTransaction{dao.schedule(questionId)}
    suspend fun configure(operationId:String,book:String,questionId:String,expectedQuestionRevision:Long,expectedSpecRevision:Long,expectedScheduleRevision:Long,
        cardId:String,expectedCardRevision:Long,prompt:String,kind:RecallQuestionKind,clozes:List<RecallCloze> = emptyList(),regions:List<RecallRegion> = emptyList(),expectedPresentation:RecallPresentationRef?=null,checkPresentation:Boolean=false):RecallOutcome {
        val legacyParts=arrayOf("CONFIGURE",operationId,book,questionId,expectedQuestionRevision,expectedSpecRevision,expectedScheduleRevision,cardId,expectedCardRevision,prompt,kind.name,clozes,regions)
        val digest=if(checkPresentation)digest(*legacyParts,expectedPresentation,true)else digest(*legacyParts)
        return write(operationId,book,"CONFIGURE",digest){
            val card=requireNotNull(db.study().card(cardId));require(card.notebookId==book&&card.trashedAt==null&&card.revision==expectedCardRevision){"RECALL_CARD_CHANGED"}
            val old=dao.question(questionId);require(old!=null||dao.questionOrder(book).size<BranchReview.MAX_QUESTIONS){"RECALL_QUESTION_BUDGET"};require((old?.revision?:0L)==expectedSpecRevision){"RECALL_SPEC_CHANGED"}
            require((dao.schedule(questionId)?.revision?:0L)==expectedScheduleRevision){"RECALL_SCHEDULE_CHANGED"}
            val q=db.knowledge().get(questionId);require((q?.revision?:0L)==expectedQuestionRevision){"RECALL_QUESTION_CHANGED"}
            val previous=q?.data() as? KnowledgeData.Question
            require(q==null||q.notebookId==book&&!q.removed&&previous?.cardId==cardId){"RECALL_QUESTION_UNAVAILABLE"}
            val changed=q==null||previous!!.prompt!=prompt
            val nextKnowledgeRevision=expectedQuestionRevision+if(changed)1 else 0
            if(changed){
                KnowledgeRepository(db).submit(KnowledgeCommand(fresh(operationId,"question"),book,questionId,expectedQuestionRevision,
                    KnowledgeData.Question(cardId,prompt,previous?.state?:ManualState.REVIEW)))
            }
            val presentation=currentPresentation(cardId)
            if(checkPresentation)require(presentation?.let{RecallPresentationRef(it.id,it.revision)}==expectedPresentation){"RECALL_PRESENTATION_CHANGED"}
            val sources=StudySourceVersions(db);sources.freezeCurrent(card);val frozen=sources.read(cardId,card.revision)
            val spec=RecallQuestionSpec(questionId,nextKnowledgeRevision,cardId,card.revision,prompt,kind,frozen.refs,frozen.complete,clozes.toList(),regions.toList(),presentation?.let{RecallPresentationRef(it.id,it.revision)})
            spec.validateBody(card.body)
            val row=RecallQuestionRow(questionId,book,expectedSpecRevision+1,old?.position?:((dao.questionOrder(book).maxOfOrNull{it.position}?:-1)+1),RecallCodec.spec(spec))
            if(old==null)dao.insert(row)else check(dao.update(row)==1)
            dao.insert(RecallQuestionVersionRow(questionId,row.revision,book,row.payload))
            val schedule=dao.schedule(questionId)
            val reset=RecallScheduleRow(questionId,book,(schedule?.revision?:0L)+1,row.revision)
            if(schedule==null)dao.insert(reset)else check(dao.update(reset)==1)
            questionId
        }
    }
    suspend fun start(operationId:String,sessionId:String,plan:BranchReviewPlan,mode:RecallMode,at:Long):RecallOutcome {
        val book=plan.ref.notebookId
        val digest=digest("START",operationId,sessionId,book,plan.ref.mapId,plan.branchId,plan.title,plan.entries,mode.name,at)
        return write(operationId,book,"START",digest){
            UUID.fromString(sessionId);require(at>=0)
            require(dao.session(sessionId)==null){"RECALL_SESSION_EXISTS"}
            require(dao.sessions(book).none{!it.closed}){"RECALL_RESUME_PENDING_SESSION"}
            val items=queue(plan).filter{mode==RecallMode.PRACTICE||it.dueAt<=at}
            require(items.isNotEmpty()){"RECALL_QUEUE_EMPTY"};require(items.none{it.needsReview}){"RECALL_SPEC_NEEDS_REVIEW"}
            require(dao.attemptCount(book)+items.size<=RecallLimits.MAX_ATTEMPTS_PER_BOOK){"RECALL_ATTEMPT_BUDGET"}
            require(dao.sessions(book).size<RecallLimits.MAX_SESSIONS_PER_BOOK){"RECALL_SESSION_BUDGET"}
            val session=RecallSessionRow(sessionId,book,1,mode.name,plan.title,at,0,items.size,mapId=plan.ref.mapId,branchId=plan.branchId)
            dao.insert(session)
            for((index,item)in items.withIndex()){
                val config=ensureConfiguration(book,item.reference)
                val attempt=RecallAttemptRow(fresh(sessionId,"attempt:$index"),book,sessionId,config.questionId,config.revision,index,mode=mode.name)
                dao.insert(attempt)
            }
            activate(session,at);sessionId
        }
    }
    private suspend fun ensureConfiguration(book:String,ref:BranchReviewEntryRef):RecallQuestionRow {
        dao.question(ref.questionId)?.let{require(dao.schedule(ref.questionId)!=null){"RECALL_SCHEDULE_MISSING"};return it}
        require(dao.questionOrder(book).size<BranchReview.MAX_QUESTIONS){"RECALL_QUESTION_BUDGET"}
        val card=requireNotNull(db.study().card(ref.cardId));require(card.revision==ref.cardRevision&&card.trashedAt==null)
        val q=requireNotNull(db.knowledge().revision(ref.questionId,ref.questionRevision));val question=KnowledgeCodec.decode(q.payload) as KnowledgeData.Question
        val sources=StudySourceVersions(db);sources.freezeCurrent(card);val frozen=sources.read(card.id,card.revision)
        val spec=RecallQuestionSpec(ref.questionId,ref.questionRevision,card.id,card.revision,question.prompt,sourceRefs=frozen.refs,sourcesComplete=frozen.complete,presentation=currentPresentation(card.id)?.let{RecallPresentationRef(it.id,it.revision)})
        val row=RecallQuestionRow(ref.questionId,book,1,(dao.questionOrder(book).maxOfOrNull{it.position}?:-1)+1,RecallCodec.spec(spec))
        dao.insert(row);dao.insert(RecallQuestionVersionRow(row.questionId,1,book,row.payload));dao.insert(RecallScheduleRow(row.questionId,book,1,1));return row
    }
    suspend fun saveAnswer(operationId:String,book:String,attemptId:String,expectedRevision:Long,text:String,ink:ByteArray):RecallOutcome {
        val frozen=ink.copyOf();val digest=digest("ANSWER",operationId,book,attemptId,expectedRevision,text,ContentTransfer.hash(frozen))
        return write(operationId,book,"ANSWER",digest){
            val row=openAttempt(book,attemptId,expectedRevision);require(!row.answerRevealed){"RECALL_ANSWER_ALREADY_REVEALED"}
            RecallCodec.validateAnswer(text,frozen)
            require(dao.answerInkBytes()-row.answerInk.size+frozen.size<=RecallLimits.MAX_INK_LIBRARY_BYTES){"RECALL_ANSWER_LIBRARY_BUDGET"}
            check(dao.update(row.copy(revision=row.revision+1,answerText=text,answerInk=frozen))==1);attemptId
        }
    }
    suspend fun hint(operationId:String,book:String,attemptId:String,expectedRevision:Long,kind:RecallHint,target:Int?,at:Long):RecallOutcome {
        val digest=digest("HINT",operationId,book,attemptId,expectedRevision,kind.name,target,at)
        return write(operationId,book,"HINT",digest){
            val row=openAttempt(book,attemptId,expectedRevision);requireAttemptTime(row,at)
            require(!row.answerRevealed){"RECALL_ANSWER_ALREADY_REVEALED"}
            val spec=requireNotNull(dao.questionVersion(row.questionId,row.specRevision)).spec();val revealed=RecallCodec.revealed(row.revealedMasks).toMutableSet()
            when(kind){
                RecallHint.TEXT->{require(spec.kind==RecallQuestionKind.TEXT_CLOZE&&target!=null&&target in spec.clozes.indices);revealed+=target}
                RecallHint.REGION->{require(spec.kind==RecallQuestionKind.SOURCE_MASK&&target!=null&&target in spec.regions.indices);revealed+=target}
                else->require(target==null)
            }
            if(dao.hints(attemptId).none{it.kind==kind.name&&it.target==target})dao.insert(RecallHintRow(operationId,book,attemptId,kind.name,target,at))
            check(dao.update(row.copy(revision=row.revision+1,hintMask=row.hintMask or kind.bit,revealedMasks=RecallCodec.revealed(revealed)))==1);attemptId
        }
    }
    suspend fun consultOriginal(operationId:String,book:String,attemptId:String,expectedRevision:Long,at:Long):RecallOutcome =
        write(operationId,book,"ORIGINAL",digest("ORIGINAL",operationId,book,attemptId,expectedRevision,at)){
            val row=openAttempt(book,attemptId,expectedRevision);requireAttemptTime(row,at)
            val kind=if(row.answerRevealed)"ORIGINAL_COMPARE"else RecallHint.ORIGINAL.name
            if(dao.hints(attemptId).none{it.kind==kind})dao.insert(RecallHintRow(operationId,book,attemptId,kind,null,at))
            check(dao.update(row.copy(revision=row.revision+1,hintMask=if(row.answerRevealed)row.hintMask else row.hintMask or RecallHint.ORIGINAL.bit))==1);attemptId
        }
    suspend fun revealAnswer(operationId:String,book:String,attemptId:String,expectedRevision:Long,at:Long):RecallOutcome =
        write(operationId,book,"REVEAL",digest("REVEAL",operationId,book,attemptId,expectedRevision,at)){
            val row=openAttempt(book,attemptId,expectedRevision);requireAttemptTime(row,at)
            if(!row.answerRevealed)dao.insert(RecallHintRow(operationId,book,attemptId,"ANSWER_COMPARE",null,at))
            check(dao.update(row.copy(revision=row.revision+1,answerRevealed=true))==1);attemptId
        }
    suspend fun grade(operationId:String,book:String,attemptId:String,expectedRevision:Long,quality:Int,at:Long,asPractice:Boolean=false):RecallOutcome =
        write(operationId,book,"GRADE",digest("GRADE",operationId,book,attemptId,expectedRevision,quality,at,asPractice)){
            val row=openAttempt(book,attemptId,expectedRevision);require(row.answerRevealed){"RECALL_REVEAL_BEFORE_GRADE"};require(quality in 0..5);requireAttemptTime(row,at)
            val schedule=requireNotNull(dao.schedule(row.questionId));var after:ByteArray?=null;var effective:Int?=null
            if(row.mode==RecallMode.DUE.name&&!asPractice){
                val spec=requireNotNull(dao.questionVersion(row.questionId,row.specRevision)).spec()
                val head=requireNotNull(dao.question(row.questionId));val card=requireNotNull(db.study().card(spec.cardId));val q=db.knowledge().get(spec.questionId)
                val question=q?.data() as? KnowledgeData.Question
                require(head.revision==row.specRevision&&card.trashedAt==null&&card.revision==spec.cardRevision&&q?.removed==false&&question!=null&&question.cardId==spec.cardId&&question.prompt==spec.prompt&&presentationCurrent(spec)){"RECALL_SPEC_NEEDS_REVIEW"}
                val before=RecallCodec.schedule(requireNotNull(row.scheduleBefore))
                require(schedule.revision==before.revision&&schedule.specRevision==row.specRevision){"RECALL_SCHEDULE_CHANGED"}
                val computed=Sm2Schedule.grade(schedule.state(),quality,at,row.hintMask!=0);effective=computed.effectiveQuality
                val next=schedule.withState(computed.state,schedule.revision+1,attemptId)
                check(dao.update(next)==1);after=next.snapshot()
            }
            check(dao.update(row.copy(revision=row.revision+1,status=RecallAttemptStatus.GRADED.name,completedAt=at,requestedQuality=quality,effectiveQuality=effective,scheduleAfter=after))==1)
            advance(row,at);attemptId
        }
    suspend fun skip(operationId:String,book:String,attemptId:String,expectedRevision:Long,at:Long):RecallOutcome =
        write(operationId,book,"SKIP",digest("SKIP",operationId,book,attemptId,expectedRevision,at)){
            val row=openAttempt(book,attemptId,expectedRevision);requireAttemptTime(row,at)
            check(dao.update(row.copy(revision=row.revision+1,status=RecallAttemptStatus.SKIPPED.name,completedAt=at))==1);advance(row,at);attemptId
        }
    suspend fun closeSession(operationId:String,book:String,sessionId:String,expectedRevision:Long,at:Long):RecallOutcome =
        write(operationId,book,"CLOSE",digest("CLOSE",operationId,book,sessionId,expectedRevision,at)){
            val session=requireNotNull(dao.session(sessionId));require(session.notebookId==book&&session.revision==expectedRevision&&!session.closed&&at>=session.createdAt){"RECALL_SESSION_CHANGED"}
            for(row in dao.unfinished(sessionId)){
                if(row.startedAt!=null)requireAttemptTime(row,at)
                check(dao.update(row.copy(revision=row.revision+1,status=RecallAttemptStatus.ABANDONED.name,completedAt=if(row.startedAt==null)null else at))==1)
            }
            check(dao.update(session.copy(revision=session.revision+1,closed=true))==1);sessionId
        }
    /** Withdraws a score by appending a correction. Later effective grades must be withdrawn first. */
    suspend fun withdraw(operationId:String,book:String,attemptId:String,expectedScheduleRevision:Long,reason:String,at:Long):RecallOutcome =
        write(operationId,book,"WITHDRAW",digest("WITHDRAW",operationId,book,attemptId,expectedScheduleRevision,reason,at)){
            require(reason.isNotBlank()&&reason.length<=500)
            val row=requireNotNull(dao.attempt(attemptId));require(row.notebookId==book&&row.status==RecallAttemptStatus.GRADED.name&&at>=requireNotNull(row.completedAt)){"RECALL_GRADE_UNAVAILABLE"}
            require(dao.correction(attemptId)==null){"RECALL_GRADE_ALREADY_WITHDRAWN"}
            var beforeBytes:ByteArray?=null;var afterBytes:ByteArray?=null
            if(row.scheduleAfter!=null){
                val current=requireNotNull(dao.schedule(row.questionId));val after=RecallCodec.schedule(row.scheduleAfter)
                require(current.revision==expectedScheduleRevision&&current.lastAttemptId==attemptId&&current.state()==after.state&&current.specRevision==after.specRevision){"RECALL_CORRECTION_HAS_SUCCESSOR"}
                val before=RecallCodec.schedule(requireNotNull(row.scheduleBefore));beforeBytes=current.snapshot()
                val restored=current.withState(before.state,current.revision+1,before.lastAttemptId).copy(specRevision=before.specRevision)
                check(dao.update(restored)==1);afterBytes=restored.snapshot()
            }
            dao.insert(RecallCorrectionRow(operationId,book,attemptId,at,reason,beforeBytes,afterBytes));attemptId
        }
    private suspend fun activate(session:RecallSessionRow,at:Long){
        if(session.position>=session.size)return
        val next=requireNotNull(dao.atPosition(session.id,session.position));require(next.status==RecallAttemptStatus.QUEUED.name)
        val schedule=requireNotNull(dao.schedule(next.questionId))
        check(dao.update(next.copy(revision=next.revision+1,status=RecallAttemptStatus.OPEN.name,startedAt=at,scheduleBefore=schedule.snapshot()))==1)
    }
    private suspend fun advance(attempt:RecallAttemptRow,at:Long){
        val session=requireNotNull(dao.session(attempt.sessionId));require(!session.closed&&session.position==attempt.position){"RECALL_SESSION_CHANGED"}
        val next=session.copy(revision=session.revision+1,position=session.position+1,closed=session.position+1==session.size)
        check(dao.update(next)==1);if(!next.closed)activate(next,at)
    }
    private suspend fun openAttempt(book:String,id:String,revision:Long):RecallAttemptRow {
        val row=requireNotNull(dao.attempt(id)){"RECALL_ATTEMPT_UNAVAILABLE"}
        require(row.notebookId==book&&row.revision==revision){"RECALL_ATTEMPT_CHANGED"}
        require(row.status==RecallAttemptStatus.OPEN.name){"RECALL_ATTEMPT_FINISHED"}
        val session=requireNotNull(dao.session(row.sessionId));require(!session.closed&&session.position==row.position){"RECALL_SESSION_CHANGED"};return row
    }
    suspend fun loadSession(id:String):RecallLoadedSession=db.withTransaction{
        val session=requireNotNull(dao.session(id)){"RECALL_SESSION_UNAVAILABLE"};requireBook(session.notebookId)
        val attempts=dao.sessionSummaries(id);require(attempts.size==session.size&&attempts.withIndex().all{it.index==it.value.position})
        RecallLoadedSession(session,attempts,attempts.getOrNull(session.position)?.takeIf{!session.closed}?.let{loadAttempt(requireNotNull(dao.attempt(it.id)))})
    }
    suspend fun loadAttempt(id:String):RecallLoadedAttempt=db.withTransaction{loadAttempt(requireNotNull(dao.attempt(id)))}
    private suspend fun loadAttempt(row:RecallAttemptRow):RecallLoadedAttempt {
        val spec=requireNotNull(dao.questionVersion(row.questionId,row.specRevision)).spec()
        val card=requireNotNull(db.study().cardVersion(spec.cardId,spec.cardRevision));spec.validateBody(card.body)
        val sources=StudySourceVersions(db).read(spec.cardId,spec.cardRevision)
        require(sources.refs==spec.sourceRefs&&sources.complete==spec.sourcesComplete){"RECALL_SOURCE_VERSION_CHANGED"}
        return RecallLoadedAttempt(row,spec,card,sources,dao.hints(row.id),dao.correction(row.id),dao.schedule(row.questionId),frozenPresentation(spec))
    }
    suspend fun history(book:String):List<RecallAttemptSummary> = db.withTransaction{requireBook(book);dao.attemptSummaries(book).filter{it.startedAt!=null}}
    /** History pages never load answer ink or source BLOBs. Open an individual row for the fixed answer. */
    suspend fun filteredHistory(book:String,filter:RecallHistoryFilter,now:Long,offset:Int=0,limit:Int=100):RecallHistoryPage = db.withTransaction{
        require(now>=0&&offset>=0&&limit in 1..200)
        val specs=mutableMapOf<Pair<String,Long>,Pair<String,RecallQuestionKind>>()
        val schedules=dao.schedules(book).associateBy{it.questionId}
        val corrections=dao.corrections(book).map{it.attemptId}.toSet()
        val matches=history(book).mapNotNull{row->
            val key=row.questionId to row.specRevision
            val description=specs[key]?:requireNotNull(dao.questionVersion(row.questionId,row.specRevision)).spec().let{it.prompt to it.kind}.also{specs[key]=it}
            val schedule=schedules[row.questionId]
            if(filter.matches(description.second,row.hintMask,row.completedAt?:requireNotNull(row.startedAt),schedule?.dueAt?:0,now))
                RecallHistoryEntry(row,description.first,description.second,row.id in corrections,schedule?.revision?:0L)else null
        }
        RecallHistoryPage(matches.drop(offset).take(limit),matches.size,offset)
    }
    /** Checks raw integer ranges before Room narrows them, then verifies frozen and causal closure. */
    suspend fun validateArchive(){
        val sql=db.openHelper.readableDatabase
        fun none(query:String){require(sql.query(query).use{!it.moveToFirst()}){"RECALL_ARCHIVE_INVALID"}}
        none("SELECT 1 FROM recall_schedules WHERE repetitions NOT BETWEEN 0 AND 2147483647 OR intervalDays NOT BETWEEN 0 AND ${Sm2Schedule.MAX_INTERVAL_DAYS} OR easeHundredths NOT BETWEEN 130 AND 2147483647 OR dueAt<0 OR revision<1 OR specRevision<1")
        none("SELECT 1 FROM recall_sessions WHERE closed NOT IN (0,1) OR position NOT BETWEEN 0 AND size OR size NOT BETWEEN 1 AND 2000 OR revision<1 OR createdAt<0")
        none("SELECT 1 FROM recall_attempts WHERE answerRevealed NOT IN (0,1) OR hintMask NOT BETWEEN 0 AND 15 OR position NOT BETWEEN 0 AND 1999 OR revision<1 OR specRevision<1 OR startedAt<0 OR completedAt<startedAt OR requestedQuality NOT BETWEEN 0 AND 5 OR effectiveQuality NOT BETWEEN 0 AND 5")
        none("SELECT 1 FROM recall_questions WHERE revision<1 OR position<0")
        none("SELECT 1 FROM recall_question_revisions r JOIN recall_questions q ON q.questionId=r.questionId WHERE r.revision<1 OR r.revision>q.revision OR r.notebookId!=q.notebookId")
        require(dao.answerInkBytes()<=RecallLimits.MAX_INK_LIBRARY_BYTES)
        val books=sql.query("SELECT id FROM notes").use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
        for(version in dao.questionVersions()){
            val spec=version.spec();require(spec.questionId==version.questionId)
            frozenPresentation(spec)
            val q=requireNotNull(db.knowledge().revision(spec.questionId,spec.knowledgeRevision));val value=KnowledgeCodec.decode(q.payload) as? KnowledgeData.Question
            require(!q.removed&&q.notebookId==version.notebookId&&value?.cardId==spec.cardId&&value.prompt==spec.prompt)
            require(db.study().card(spec.cardId)?.notebookId==version.notebookId)
            val card=requireNotNull(db.study().cardVersion(spec.cardId,spec.cardRevision));require(card.trashedAt==null);spec.validateBody(card.body)
            val sourceSet=requireNotNull(db.sourceVersions().set(spec.cardId,spec.cardRevision))
            require(sourceSet.refs()==spec.sourceRefs&&sourceSet.complete==spec.sourcesComplete)
        }
        for(book in books){
            val questions=dao.questions(book);require(questions.size<=BranchReview.MAX_QUESTIONS&&questions.map{it.position}.distinct().size==questions.size)
            for(question in questions){
                val frozen=requireNotNull(dao.questionVersion(question.questionId,question.revision));require(frozen.payload.contentEquals(question.payload))
                require(dao.schedule(question.questionId)!=null)
            }
            val sessions=dao.sessions(book);require(sessions.size<=RecallLimits.MAX_SESSIONS_PER_BOOK&&sessions.count{!it.closed}<=1)
            for(session in sessions){
                UUID.fromString(session.id);RecallMode.valueOf(session.mode);require(session.title.isNotBlank()&&session.title.length<=120)
                listOfNotNull(session.mapId,session.branchId).forEach{UUID.fromString(it)}
                val attempts=dao.sessionSummaries(session.id)
                require(attempts.size==session.size&&attempts.withIndex().all{it.index==it.value.position&&it.value.notebookId==book&&it.value.mode==session.mode})
                require(attempts.map{it.questionId}.distinct().size==attempts.size)
                if(!session.closed)require(attempts[session.position].status==RecallAttemptStatus.OPEN.name&&attempts.drop(session.position+1).all{it.status==RecallAttemptStatus.QUEUED.name})
                if(session.closed)require(attempts.none{it.status in setOf(RecallAttemptStatus.OPEN.name,RecallAttemptStatus.QUEUED.name)})
            }
            require(dao.attemptCount(book)<=RecallLimits.MAX_ATTEMPTS_PER_BOOK)
            for(summary in dao.attemptSummaries(book)){
                val row=requireNotNull(dao.attempt(summary.id));UUID.fromString(row.id);RecallMode.valueOf(row.mode)
                val status=RecallAttemptStatus.valueOf(row.status);val spec=requireNotNull(dao.questionVersion(row.questionId,row.specRevision)).spec()
                require(db.knowledge().get(row.questionId)?.notebookId==book)
                RecallCodec.validateAnswer(row.answerText,row.answerInk)
                val revealed=RecallCodec.revealed(row.revealedMasks)
                require(revealed.all{it in 0 until when(spec.kind){RecallQuestionKind.QUESTION->0;RecallQuestionKind.TEXT_CLOZE->spec.clozes.size;RecallQuestionKind.SOURCE_MASK->spec.regions.size}})
                val hints=dao.hints(row.id);var mask=0;val masks=mutableSetOf<Int>()
                require(hints.map{it.kind to it.target}.distinct().size==hints.size)
                for(hint in hints){
                    UUID.fromString(hint.id);require(hint.notebookId==book&&row.startedAt!=null&&hint.at>=row.startedAt&&(row.completedAt==null||hint.at<=row.completedAt))
                    if(hint.kind in setOf("ANSWER_COMPARE","ORIGINAL_COMPARE")){require(hint.target==null);if(hint.kind=="ORIGINAL_COMPARE")require(hints.any{it.kind=="ANSWER_COMPARE"&&it.at<=hint.at})}else{
                        val kind=RecallHint.valueOf(hint.kind);mask=mask or kind.bit
                        if(kind==RecallHint.TEXT){require(spec.kind==RecallQuestionKind.TEXT_CLOZE&&hint.target in spec.clozes.indices);masks+=requireNotNull(hint.target)}
                        else if(kind==RecallHint.REGION){require(spec.kind==RecallQuestionKind.SOURCE_MASK&&hint.target in spec.regions.indices);masks+=requireNotNull(hint.target)}
                        else require(hint.target==null)
                    }
                }
                require(mask==row.hintMask&&masks==revealed&&row.answerRevealed==hints.any{it.kind=="ANSWER_COMPARE"})
                when(status){
                    RecallAttemptStatus.QUEUED->require(row.startedAt==null&&row.completedAt==null&&row.answerText.isEmpty()&&row.answerInk.isEmpty()&&hints.isEmpty()&&row.scheduleBefore==null)
                    RecallAttemptStatus.OPEN->require(row.startedAt!=null&&row.completedAt==null&&row.scheduleBefore!=null)
                    RecallAttemptStatus.GRADED->require(row.startedAt!=null&&row.completedAt!=null&&row.requestedQuality!=null&&row.answerRevealed&&row.scheduleBefore!=null)
                    RecallAttemptStatus.SKIPPED->require(row.startedAt!=null&&row.completedAt!=null&&row.scheduleBefore!=null)
                    RecallAttemptStatus.ABANDONED->require((row.startedAt==null)==(row.completedAt==null))
                }
                row.scheduleBefore?.let{RecallCodec.schedule(it)}
                if(status!=RecallAttemptStatus.GRADED)require(row.requestedQuality==null&&row.effectiveQuality==null&&row.scheduleAfter==null)
                if(row.scheduleAfter!=null){
                    require(row.mode==RecallMode.DUE.name&&status==RecallAttemptStatus.GRADED)
                    val before=RecallCodec.schedule(requireNotNull(row.scheduleBefore));val after=RecallCodec.schedule(row.scheduleAfter)
                    val computed=Sm2Schedule.grade(before.state,requireNotNull(row.requestedQuality),requireNotNull(row.completedAt),row.hintMask!=0)
                    require(row.effectiveQuality==computed.effectiveQuality&&after.state==computed.state&&after.revision==before.revision+1&&after.lastAttemptId==row.id&&after.specRevision==row.specRevision&&before.specRevision==row.specRevision)
                }else require(row.effectiveQuality==null)
            }
            for(correction in dao.corrections(book)){
                UUID.fromString(correction.id);val attempt=requireNotNull(dao.attempt(correction.attemptId))
                require(attempt.notebookId==book&&attempt.status==RecallAttemptStatus.GRADED.name&&correction.at>=requireNotNull(attempt.completedAt)&&correction.reason.isNotBlank()&&correction.reason.length<=500)
                require((correction.scheduleBefore!=null)==(attempt.scheduleAfter!=null)&&(correction.scheduleAfter!=null)==(attempt.scheduleAfter!=null))
                if(correction.scheduleAfter!=null){
                    val originalBefore=RecallCodec.schedule(requireNotNull(attempt.scheduleBefore));val originalAfter=RecallCodec.schedule(requireNotNull(attempt.scheduleAfter))
                    val before=RecallCodec.schedule(requireNotNull(correction.scheduleBefore));val after=RecallCodec.schedule(correction.scheduleAfter)
                    require(before.state==originalAfter.state&&before.lastAttemptId==attempt.id&&after.state==originalBefore.state&&after.lastAttemptId==originalBefore.lastAttemptId&&after.specRevision==originalBefore.specRevision&&after.revision==before.revision+1)
                }
            }
            for(schedule in dao.schedules(book)){
                val head=requireNotNull(dao.question(schedule.questionId));require(head.revision==schedule.specRevision);schedule.state()
                if(schedule.lastAttemptId==null)require(schedule.state()==Sm2State())else{
                    val attempt=requireNotNull(dao.attempt(schedule.lastAttemptId));require(attempt.questionId==schedule.questionId&&attempt.notebookId==book&&dao.correction(attempt.id)==null)
                    val after=RecallCodec.schedule(requireNotNull(attempt.scheduleAfter));require(schedule.state()==after.state&&schedule.revision>=after.revision&&schedule.specRevision==after.specRevision)
                }
            }
            require(dao.receiptCount(book)<=RecallLimits.MAX_COMMANDS_PER_BOOK)
        }
        for(receipt in dao.receipts()){
            UUID.fromString(receipt.operationId);UUID.fromString(receipt.resultId);require(receipt.digest.matches(Regex("[0-9a-f]{64}")))
            when(receipt.kind){
                "CONFIGURE"->require(dao.question(receipt.resultId)?.notebookId==receipt.notebookId)
                "START","CLOSE"->require(dao.session(receipt.resultId)?.notebookId==receipt.notebookId)
                "ANSWER","HINT","ORIGINAL","REVEAL","GRADE","SKIP","WITHDRAW"->require(dao.attempt(receipt.resultId)?.notebookId==receipt.notebookId)
                else->error("RECALL_RECEIPT_KIND")
            }
        }
    }
    suspend fun presentation(cardId:String):KnowledgeRow?=db.withTransaction{currentPresentation(cardId)}
    private suspend fun currentPresentation(cardId:String):KnowledgeRow? {
        val card=requireNotNull(db.study().card(cardId))
        return db.knowledge().forBook(card.notebookId).singleOrNull{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==cardId}
    }
    private suspend fun frozenPresentation(spec:RecallQuestionSpec):KnowledgeData.CardPresentation? {
        val reference=spec.presentation?:return null
        require(spec.presentationKnown){"RECALL_PRESENTATION_UNAVAILABLE"}
        val row=requireNotNull(db.knowledge().revision(reference.id,reference.revision)){"RECALL_PRESENTATION_UNAVAILABLE"}
        val data=KnowledgeCodec.decode(row.payload) as? KnowledgeData.CardPresentation
        require(!row.removed&&data?.cardId==spec.cardId&&row.notebookId==db.study().card(spec.cardId)?.notebookId){"RECALL_PRESENTATION_UNAVAILABLE"}
        return data
    }
    private suspend fun presentationCurrent(spec:RecallQuestionSpec):Boolean=spec.presentationKnown&&
        ((currentPresentation(spec.cardId)?.data() as? KnowledgeData.CardPresentation)?.annotation.orEmpty()==frozenPresentation(spec)?.annotation.orEmpty())
    private suspend fun requireAttemptTime(row:RecallAttemptRow,at:Long){
        require(at>=maxOf(requireNotNull(row.startedAt),dao.hints(row.id).maxOfOrNull{it.at}?:0)){"RECALL_CLOCK_MOVED_BACK"}
    }
    suspend fun lookup(operationId:String):RecallReceiptRow?=dao.receipt(operationId)
    private suspend fun requireBook(book:String){UUID.fromString(book);require(db.notes().note(book)!=null&&db.workspace().get(book)?.trashedAt==null){"RECALL_BOOK_UNAVAILABLE"}}
    private suspend fun write(operationId:String,book:String,kind:String,digest:String,action:suspend()->String):RecallOutcome =try{
        UUID.fromString(operationId)
        val result=db.withTransaction{
            dao.receipt(operationId)?.let{require(it.notebookId==book&&it.kind==kind&&it.digest==digest){"RECALL_OPERATION_MISMATCH"};return@withTransaction it.resultId}
            requireBook(book);require(dao.receiptCount(book)<RecallLimits.MAX_COMMANDS_PER_BOOK){"RECALL_COMMAND_BUDGET"}
            val id=action();fault(RecallFault.BEFORE_RECEIPT);dao.insert(RecallReceiptRow(operationId,book,kind,digest,id));db.notes().touch(book,System.currentTimeMillis());id
        };fault(RecallFault.AFTER_COMMIT);RecallOutcome.Success(result)
    }catch(c:CancellationException){throw c}catch(e:IllegalArgumentException){RecallOutcome.Rejected(e.message?:"RECALL_INVALID")}
    catch(_:Exception){try{dao.receipt(operationId)?.takeIf{it.notebookId==book&&it.kind==kind&&it.digest==digest}?.let{RecallOutcome.Success(it.resultId)}?:RecallOutcome.Unknown}catch(c:CancellationException){throw c}catch(_:Exception){RecallOutcome.Unknown}}
    companion object {
        private fun fresh(operation:String,part:String)=UUID.nameUUIDFromBytes("recall:$operation:$part".toByteArray()).toString()
        private fun digest(vararg parts:Any?):String=ContentTransfer.hash(ByteArrayOutputStream().also{out->DataOutputStream(out).use{d->parts.forEach{value->val bytes=(value?.toString()?:"<null>").toByteArray();d.writeInt(bytes.size);d.write(bytes)}}}.toByteArray())
    }
}
