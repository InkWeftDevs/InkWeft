// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import org.inkweft.core.*
import java.util.UUID

data class RecallLoadedAttempt(val row:RecallAttemptRow,val spec:RecallQuestionSpec,val card:StudyCardRevisionRow,val sources:FrozenStudySources,
    val hints:List<RecallHintRow>,val correction:RecallCorrectionRow?,val schedule:RecallScheduleRow?,val presentation:KnowledgeData.CardPresentation?=null)
data class RecallHistoryEntry(val row:RecallAttemptSummary,val prompt:String,val kind:RecallQuestionKind,val withdrawn:Boolean,val scheduleRevision:Long)
data class RecallHistoryPage(val entries:List<RecallHistoryEntry>,val total:Int,val offset:Int)
data class RecallLoadedSession(val row:RecallSessionRow,val attempts:List<RecallAttemptSummary>,val current:RecallLoadedAttempt?)

/**
 * Read projections depend only on persisted rows, never command receipts or fault injection.
 * Projection queries keep their own consistent Room snapshot. The shared validation helpers
 * also serve commands and archive validation without changing their transaction ownership.
 */
internal class RecallStudyQueries(private val db:NoteDatabase){
    private val dao get()=db.recall()
    fun observeSession(id:String)=db.invalidationTracker.createFlow("recall_sessions","recall_attempts","recall_hint_events","recall_schedules","recall_corrections","recall_questions","study_cards","knowledge_records").map{loadSession(id)}
    fun observeHistory(book:String)=db.invalidationTracker.createFlow("recall_attempts","recall_hint_events","recall_schedules","recall_corrections").map{history(book)}
    suspend fun resume(book:String):RecallSessionRow?=db.withTransaction{requireBook(book);dao.sessions(book).lastOrNull{!it.closed}}
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
    suspend fun frozenPresentation(spec:RecallQuestionSpec):KnowledgeData.CardPresentation? {
        val reference=spec.presentation?:return null
        require(spec.presentationKnown){"RECALL_PRESENTATION_UNAVAILABLE"}
        val row=requireNotNull(db.knowledge().revision(reference.id,reference.revision)){"RECALL_PRESENTATION_UNAVAILABLE"}
        val data=KnowledgeCodec.decode(row.payload) as? KnowledgeData.CardPresentation
        require(!row.removed&&data?.cardId==spec.cardId&&row.notebookId==db.study().card(spec.cardId)?.notebookId){"RECALL_PRESENTATION_UNAVAILABLE"}
        return data
    }
    suspend fun requireBook(book:String){UUID.fromString(book);require(db.notes().note(book)!=null&&db.workspace().get(book)?.trashedAt==null){"RECALL_BOOK_UNAVAILABLE"}}
}
