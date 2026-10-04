// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.util.UUID

enum class CardReuseKind { REFERENCE, INDEPENDENT_COPY }
data class CardReuseRequest(val operationId:String,val cardId:String,val cardRevision:Long,val destination:String,
    val kind:CardReuseKind,val presentationId:String?=null,val presentationRevision:Long=0){
    init{listOfNotNull(operationId,cardId,destination,presentationId).forEach(UUID::fromString);require(cardRevision>0&&presentationRevision>=0);require((presentationId==null)==(presentationRevision==0L))}
    fun digest()=ContentTransfer.hash(listOf("card-reuse-v1",operationId,cardId,cardRevision,destination,kind,presentationId,presentationRevision).joinToString("|").toByteArray())
    fun resultId()=UUID.nameUUIDFromBytes("$operationId:${kind.name}".toByteArray()).toString()
}

/** A reference retains the content identity; a copy starts a new identity and never copies questions. */
class CardReuseRepository(private val db:NoteDatabase,private val fault:(KnowledgeFault)->Unit={}){
    suspend fun prepare(cardId:String,revision:Long,destination:String,kind:CardReuseKind):CardReuseRequest=db.withTransaction{
        val card=requireNotNull(db.study().card(cardId));require(card.revision==revision&&card.trashedAt==null){"CARD_REUSE_CHANGED"}
        val p=db.knowledge().forBook(card.notebookId).singleOrNull{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==cardId}
        CardReuseRequest(UUID.randomUUID().toString(),cardId,revision,destination,kind,p?.id,p?.revision?:0)
    }
    suspend fun outcome(request:CardReuseRequest):KnowledgeOutcome=try{KnowledgeOutcome.Success(submit(request))}
    catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
    catch(rejected:KnowledgeRejected){KnowledgeOutcome.Rejected(rejected.reason)}
    catch(rejected:StudyRejected){KnowledgeOutcome.Rejected(when(rejected.message){"STUDY_NODE_BUDGET"->KnowledgeRejection.STUDY_NODE_BUDGET;"STUDY_NODE_RECORD_BUDGET"->KnowledgeRejection.STUDY_NODE_RECORD_BUDGET;else->KnowledgeRejection.INVALID})}
    catch(_:Exception){try{receipt(request)?.let{KnowledgeOutcome.Success(it)}?:KnowledgeOutcome.Unknown}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){KnowledgeOutcome.Unknown}}
    private suspend fun receipt(r:CardReuseRequest)=db.knowledge().receipt(r.operationId)?.also{require(it.notebookId==r.destination&&it.digest==r.digest())}?.resultId
    suspend fun submit(r:CardReuseRequest):String{
        val result=db.withTransaction{
            receipt(r)?.let{return@withTransaction it}
            val card=db.study().card(r.cardId)?:throw KnowledgeRejected(KnowledgeRejection.UNAVAILABLE)
            if(card.trashedAt!=null||db.workspace().get(card.notebookId)?.trashedAt!=null||db.notes().note(r.destination)==null||db.workspace().get(r.destination)?.trashedAt!=null)throw KnowledgeRejected(KnowledgeRejection.UNAVAILABLE)
            if(card.revision!=r.cardRevision)throw KnowledgeRejected(KnowledgeRejection.CONFLICT)
            val p=db.knowledge().forBook(card.notebookId).singleOrNull{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==card.id}
            if(p?.id!=r.presentationId||(p?.revision?:0)!=r.presentationRevision)throw KnowledgeRejected(KnowledgeRejection.CONFLICT)
            val result=r.resultId()
            if(r.kind==CardReuseKind.REFERENCE){
                val link=KnowledgeData.Link(TargetRef(TargetKind.NOTE,r.destination),TargetRef(TargetKind.CARD,card.id))
                KnowledgeRepository(db).submit(KnowledgeCommand(UUID.nameUUIDFromBytes("${r.operationId}:link".toByteArray()).toString(),r.destination,result,0,link))
            }else{
                if(db.study().cards(r.destination).size>=StudyCapacity.MAX_CARDS_PER_NOTEBOOK)throw KnowledgeRejected(KnowledgeRejection.INVALID)
                val copy=card.copy(id=result,notebookId=r.destination,revision=1)
                db.study().addCard(copy);db.study().revision(StudyCardRevisionRow(copy.id,1,copy.title,copy.body,null))
                val sources=StudySourceVersions(db);sources.freezeCurrent(card);val frozen=sources.read(card.id,card.revision)
                sources.writeSet(copy.id,1,frozen.refs,frozen.complete)
                p?.let{row->val presentation=(row.data() as KnowledgeData.CardPresentation).copy(cardId=copy.id)
                    KnowledgeRepository(db).submit(KnowledgeCommand(UUID.nameUUIDFromBytes("${r.operationId}:presentation-op".toByteArray()).toString(),r.destination,UUID.nameUUIDFromBytes("${r.operationId}:presentation".toByteArray()).toString(),0,presentation))}
                val nodes=db.study().nodes(r.destination).filterNot{it.removed}
                StudyRepository(db).submit(StudyCommand(UUID.nameUUIDFromBytes("${r.operationId}:position".toByteArray()).toString(),r.destination,StudyAction.REUSE,cardId=copy.id,nodeId=UUID.nameUUIDFromBytes("${r.operationId}:node".toByteArray()).toString(),y=((nodes.maxOfOrNull{it.y}?:-80.0)+160.0).coerceAtMost(40000.0)))
            }
            fault(KnowledgeFault.BEFORE_RECEIPT)
            db.knowledge().receipt(KnowledgeReceiptRow(r.operationId,r.destination,r.digest(),result))
            db.notes().touch(r.destination,System.currentTimeMillis());result
        }
        fault(KnowledgeFault.AFTER_COMMIT);return result
    }
}
