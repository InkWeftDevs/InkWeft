// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.map
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

@Entity(tableName="card_transform_operations",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class CardTransformOperationRow(@PrimaryKey val operationId:String,val notebookId:String,val digest:String,val payload:ByteArray,
    val afterFingerprint:String,val revision:Long=1,val undone:Boolean=false,val undoOperationId:String?=null,val undoDigest:String?=null) {
    fun plan()=CardTransformCodec.decode(payload)
}
@Dao interface CardTransformDao {
    @Query("SELECT * FROM card_transform_operations WHERE operationId=:id") suspend fun get(id:String):CardTransformOperationRow?
    @Query("SELECT * FROM card_transform_operations WHERE undoOperationId=:id") suspend fun undo(id:String):CardTransformOperationRow?
    @Query("SELECT * FROM card_transform_operations WHERE notebookId=:book ORDER BY operationId") suspend fun forBook(book:String):List<CardTransformOperationRow>
    @Query("SELECT * FROM card_transform_operations ORDER BY operationId") suspend fun all():List<CardTransformOperationRow>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:CardTransformOperationRow)
    @Update suspend fun update(row:CardTransformOperationRow):Int
}
data class CardTransformImpact(val card:CardTransformCard,val occurrences:List<String>,val references:List<String>,val questions:List<String>,val sourceLabels:List<String>)
data class CardTransformPreview(val notebookId:String,val fingerprint:String,val cards:List<CardTransformCard>,val impact:List<CardTransformImpact>) {
    fun plan(kind:CardTransformKind,targets:List<CardTransformTarget>,operationId:String=UUID.randomUUID().toString())=
        CardTransformPlan(operationId,notebookId,kind,fingerprint,cards,targets)
}
data class CardTransformResolution(val operationId:String,val kind:CardTransformKind,val targets:List<StudyCardRow>,val undone:Boolean)
sealed interface CardTransformOutcome {
    data class Success(val operationId:String,val targetIds:List<String>):CardTransformOutcome
    data class Rejected(val reason:String):CardTransformOutcome
    data object Unknown:CardTransformOutcome
}
enum class CardTransformFault { BEFORE_RECEIPT, AFTER_COMMIT }
/** Originals, placements, links and questions are retained. Only explicit fresh content identities are created. */
class CardTransformRepository(private val db:NoteDatabase,private val fault:(CardTransformFault)->Unit={}) {
    fun observeResolution(cardId:String)=db.invalidationTracker.createFlow("card_transform_operations","study_cards").map{resolve(cardId)}
    suspend fun resolve(cardId:String):List<CardTransformResolution> = db.withTransaction {
        val card=db.study().card(cardId)?:return@withTransaction emptyList()
        db.cardTransforms().forBook(card.notebookId).filter{row->!row.undone&&row.plan().inputs.any{it.id==cardId}}.map{row->
            CardTransformResolution(row.operationId,row.plan().kind,row.plan().targets.mapNotNull{db.study().card(it.id)},row.undone)
        }
    }
    /** Read-only: opening or cancelling a preview does not materialize source history or write a receipt. */
    suspend fun preview(book:String,cardIds:List<String>):CardTransformPreview=db.withTransaction {
        require(cardIds.size in 1..16&&cardIds.distinct().size==cardIds.size){"TRANSFORM_SELECTION"}
        require(db.notes().note(book)!=null&&db.workspace().get(book)?.trashedAt==null){"TRANSFORM_BOOK_UNAVAILABLE"}
        val all=db.knowledge().all();val main=db.study().nodes(book)
        val cards=cardIds.map{id->
            val row=requireNotNull(db.study().card(id)){"TRANSFORM_CARD_UNAVAILABLE"}
            require(row.notebookId==book&&row.trashedAt==null){"TRANSFORM_SAME_NOTEBOOK_REQUIRED"}
            val presentation=all.filterNot{it.removed}.mapNotNull{it.data() as? KnowledgeData.CardPresentation}.singleOrNull{it.cardId==id}?:KnowledgeData.CardPresentation(id)
            val sources=StudySourceVersions(db).read(id)
            CardTransformCard(id,row.revision,row.title,row.body,presentation,sources.refs,sources.complete)
        }
        CardTransformPreview(book,fingerprint(book,cardIds),cards,cards.map{card->
            val related=all.filter{!it.removed&&mentions(it.data(),setOf(card.id))}
            CardTransformImpact(card,
                main.filter{!it.removed&&it.cardId==card.id}.map{"主图 · ${it.id}"}+related.mapNotNull{r->when(val d=r.data()){
                    is KnowledgeData.MapOccurrence->"导图 ${d.mapId} · ${r.id}";is KnowledgeData.Placement->"知识板 · ${r.id}";else->null}},
                related.filter{it.data() is KnowledgeData.Link}.map{r->val d=r.data() as KnowledgeData.Link;"${d.relation.label} · ${r.id}"+(d.pinnedRevision?.let{" · 固定版本 $it"}?:"")},
                related.mapNotNull{r->(r.data() as? KnowledgeData.Question)?.let{"${it.prompt} · ${it.state.label}"}},
                StudySourceVersions(db).read(card.id,card.revision).sources.map{source->
                    val page=db.pages().get(source.pageId);"第 ${(page?.position?:-1)+1} 页 · 来源 ${source.sourceId} · 版本 ${source.revision}"})
        })
    }
    suspend fun lookup(plan:CardTransformPlan):CardTransformOutcome.Success?=db.withTransaction {
        db.cardTransforms().get(plan.operationId)?.let{row->
            require(row.notebookId==plan.notebookId&&row.digest==plan.digest()){"TRANSFORM_OPERATION_MISMATCH"}
            CardTransformOutcome.Success(row.operationId,row.plan().targets.map{it.id})
        }
    }
    suspend fun outcome(plan:CardTransformPlan):CardTransformOutcome=try{submit(plan)}
        catch(c:kotlinx.coroutines.CancellationException){throw c}
        catch(e:IllegalArgumentException){CardTransformOutcome.Rejected(e.message?:"TRANSFORM_INVALID")}
        catch(_:Exception){try{lookup(plan)?:CardTransformOutcome.Unknown}catch(c:kotlinx.coroutines.CancellationException){throw c}catch(_:Exception){CardTransformOutcome.Unknown}}
    suspend fun submit(request:CardTransformPlan):CardTransformOutcome.Success {
        val plan=CardTransformCodec.decode(CardTransformCodec.encode(request))
        val result=db.withTransaction {
            lookup(plan)?.let{return@withTransaction it}
            require(db.cardTransforms().undo(plan.operationId)==null){"TRANSFORM_OPERATION_MISMATCH"}
            val fresh=preview(plan.notebookId,plan.inputs.map{it.id})
            require(fresh.fingerprint==plan.expectedFingerprint&&fresh.cards==plan.inputs){"TRANSFORM_VERSION_CHANGED"}
            val study=db.study();val knowledge=db.knowledge();val sources=StudySourceVersions(db)
            require(study.cards(plan.notebookId).size+plan.targets.size<=StudyCapacity.MAX_CARDS_PER_NOTEBOOK){"STUDY_CARD_BUDGET"}
            val extraKnowledge=plan.targets.size+if(plan.kind==CardTransformKind.SUMMARY)plan.inputs.size else 0
            require(knowledge.forBook(plan.notebookId).size+extraKnowledge<=2000){"KNOWLEDGE_BUDGET"}
            require(plan.targets.all{knowledge.get(presentationId(plan.operationId,it.id))==null}){"TRANSFORM_RECORD_ID_EXISTS"}
            if(plan.kind==CardTransformKind.SUMMARY)require(plan.inputs.all{knowledge.get(summaryId(plan.operationId,it.id))==null}){"TRANSFORM_RECORD_ID_EXISTS"}
            plan.inputs.forEach{input->sources.freezeCurrent(requireNotNull(study.card(input.id)))}
            plan.targets.forEach{target->
                require(study.card(target.id)==null){"TRANSFORM_TARGET_EXISTS"}
                val card=StudyCardRow(target.id,plan.notebookId,1,target.title,target.body)
                study.addCard(card);study.revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))
                sources.writeSet(card.id,1,target.sources,plan.inputs.all{it.sourcesComplete})
                writeKnowledge(KnowledgeRow(presentationId(plan.operationId,card.id),plan.notebookId,1,KnowledgeCodec.encode(KnowledgeData.CardPresentation(card.id,target.annotation,target.cardColor,target.titleBarColor))))
            }
            if(plan.kind==CardTransformKind.SUMMARY)plan.inputs.forEach{input->
                writeKnowledge(KnowledgeRow(summaryId(plan.operationId,input.id),plan.notebookId,1,
                    KnowledgeCodec.encode(KnowledgeData.Link(TargetRef(TargetKind.CARD,input.id),TargetRef(TargetKind.CARD,plan.targets.single().id),RelationKind.SUMMARY))))
            }
            val after=fingerprint(plan.notebookId,plan.inputs.map{it.id}+plan.targets.map{it.id},plan.operationId)
            fault(CardTransformFault.BEFORE_RECEIPT)
            db.cardTransforms().insert(CardTransformOperationRow(plan.operationId,plan.notebookId,plan.digest(),CardTransformCodec.encode(plan),after))
            db.notes().touch(plan.notebookId,System.currentTimeMillis())
            CardTransformOutcome.Success(plan.operationId,plan.targets.map{it.id})
        }
        fault(CardTransformFault.AFTER_COMMIT);return result
    }
    suspend fun lookupUndo(operationId:String,expectedRevision:Long,undoOperationId:String):CardTransformOutcome.Success?=db.withTransaction{
        val row=db.cardTransforms().get(operationId)?:return@withTransaction null
        if(!row.undone)return@withTransaction null
        require(row.undoOperationId==undoOperationId&&row.undoDigest==undoDigest(operationId,expectedRevision,undoOperationId)){"TRANSFORM_UNDO_OPERATION_MISMATCH"}
        CardTransformOutcome.Success(operationId,row.plan().inputs.map{it.id})
    }
    suspend fun undoOutcome(operationId:String,expectedRevision:Long,undoOperationId:String):CardTransformOutcome=try{
        undo(operationId,expectedRevision,undoOperationId)
    }catch(c:kotlinx.coroutines.CancellationException){throw c}
    catch(e:IllegalArgumentException){CardTransformOutcome.Rejected(e.message?:"TRANSFORM_INVALID")}
    catch(_:Exception){
        try{db.cardTransforms().get(operationId)?.takeIf{it.undoOperationId==undoOperationId&&it.undoDigest==undoDigest(operationId,expectedRevision,undoOperationId)}?.let{CardTransformOutcome.Success(it.operationId,it.plan().inputs.map{c->c.id})}?:CardTransformOutcome.Unknown}
        catch(c:kotlinx.coroutines.CancellationException){throw c}catch(_:Exception){CardTransformOutcome.Unknown}
    }
    /** Inverse only succeeds while every affected identity and dependent reference still matches the commit. */
    suspend fun undo(operationId:String,expectedRevision:Long,undoOperationId:String):CardTransformOutcome.Success {
        listOf(operationId,undoOperationId).forEach{UUID.fromString(it)};require(operationId!=undoOperationId)
        val digest=undoDigest(operationId,expectedRevision,undoOperationId)
        val result=db.withTransaction {
            val row=requireNotNull(db.cardTransforms().get(operationId)){"TRANSFORM_OPERATION_UNAVAILABLE"}
            val plan=row.plan()
            if(row.undone){require(row.undoOperationId==undoOperationId&&row.undoDigest==digest){"TRANSFORM_ALREADY_UNDONE"};return@withTransaction CardTransformOutcome.Success(operationId,plan.inputs.map{it.id})}
            require(db.cardTransforms().get(undoOperationId)==null&&db.cardTransforms().undo(undoOperationId)==null){"TRANSFORM_OPERATION_MISMATCH"}
            require(expectedRevision==row.revision){"TRANSFORM_VERSION_CHANGED"}
            require(db.workspace().get(row.notebookId)?.trashedAt==null){"TRANSFORM_BOOK_UNAVAILABLE"}
            require(fingerprint(row.notebookId,plan.inputs.map{it.id}+plan.targets.map{it.id},row.operationId)==row.afterFingerprint){"TRANSFORM_UNDO_DEPENDENCIES_CHANGED"}
            for(target in plan.targets){
                val card=requireNotNull(db.study().card(target.id));val next=card.copy(revision=card.revision+1,trashedAt=System.currentTimeMillis())
                check(db.study().updateCard(next)==1);db.study().revision(StudyCardRevisionRow(next.id,next.revision,next.title,next.body,next.trashedAt))
                StudySourceVersions(db).copyVersion(next,card.revision)
                val presentation=requireNotNull(db.knowledge().get(presentationId(plan.operationId,target.id)))
                writeKnowledge(presentation.copy(revision=presentation.revision+1,removed=true))
            }
            if(plan.kind==CardTransformKind.SUMMARY)plan.inputs.forEach{input->
                val link=requireNotNull(db.knowledge().get(summaryId(plan.operationId,input.id)));writeKnowledge(link.copy(revision=link.revision+1,removed=true))
            }
            fault(CardTransformFault.BEFORE_RECEIPT)
            check(db.cardTransforms().update(row.copy(revision=row.revision+1,undone=true,undoOperationId=undoOperationId,undoDigest=digest))==1)
            db.notes().touch(row.notebookId,System.currentTimeMillis());CardTransformOutcome.Success(operationId,plan.inputs.map{it.id})
        }
        fault(CardTransformFault.AFTER_COMMIT);return result
    }
    private suspend fun writeKnowledge(row:KnowledgeRow){
        if(row.revision==1L)db.knowledge().insert(row)else check(db.knowledge().update(row)==1)
        db.knowledge().revision(KnowledgeRevisionRow(row.id,row.revision,row.notebookId,row.payload,row.removed))
    }
    private suspend fun fingerprint(book:String,cardIds:List<String>,ignoreOperationId:String?=null):String {
        val ids=cardIds.toSet();val out=ByteArrayOutputStream()
        DataOutputStream(out).use{d->
            fun text(s:String){val bytes=s.toByteArray();d.writeInt(bytes.size);d.write(bytes)}
            text(book)
            ids.sorted().forEach{id->
                val c=requireNotNull(db.study().card(id));text(c.id);d.writeLong(c.revision);text(c.title);text(c.body);d.writeLong(c.trashedAt?:-1)
                val source=StudySourceVersions(db).read(id);text(StudySourceRefs.encode(source.refs));d.writeBoolean(source.complete)
                source.sources.forEach{d.writeUTF(ContentTransfer.hash(it.snapshot))}
            }
            db.study().nodes(book).filter{it.cardId in ids}.sortedBy{it.id}.forEach{n->text(n.toString())}
            db.cardTransforms().forBook(book).filter{row->!row.undone&&row.operationId!=ignoreOperationId&&row.plan().let{plan->plan.inputs.any{it.id in ids}||plan.targets.any{it.id in ids}}}.forEach{row->
                text(row.operationId);d.writeLong(row.revision);d.writeBoolean(row.undone);text(row.digest)
            }
            db.knowledge().all().filter{mentions(it.data(),ids)}.sortedBy{it.id}.forEach{r->text(r.id);text(r.notebookId);d.writeLong(r.revision);d.writeBoolean(r.removed);d.writeInt(r.payload.size);d.write(r.payload)}
        }
        return ContentTransfer.hash(out.toByteArray())
    }
    suspend fun validateArchive(){
        val operations=db.cardTransforms().all();require(operations.mapNotNull{it.undoOperationId}.distinct().size==operations.count{it.undoOperationId!=null})
        for(row in operations){
            val plan=row.plan();require(row.operationId==plan.operationId&&row.notebookId==plan.notebookId&&row.digest==plan.digest())
            require(row.afterFingerprint.matches(Regex("[0-9a-f]{64}")))
            require(row.revision==if(row.undone)2L else 1L)
            require(row.undone==(row.undoOperationId!=null)&&row.undone==(row.undoDigest!=null))
            row.undoOperationId?.let{UUID.fromString(it);require(row.undoDigest==undoDigest(row.operationId,1,it));require(operations.none{r->r.operationId==it})}
            plan.inputs.forEach{require(db.study().card(it.id)?.notebookId==row.notebookId);val old=requireNotNull(db.study().cardVersion(it.id,it.revision));require(old.title==it.title&&old.body==it.body)}
            plan.targets.forEach{target->require(db.study().card(target.id)?.notebookId==row.notebookId);val old=requireNotNull(db.study().cardVersion(target.id,1));require(old.title==target.title&&old.body==target.body);val sourceSet=requireNotNull(db.sourceVersions().set(target.id,1));require(sourceSet.refs()==target.sources&&sourceSet.complete==plan.inputs.all{it.sourcesComplete})}
        }
    }
    companion object {
        private fun undoDigest(op:String,revision:Long,undo:String)=ContentTransfer.hash("card-transform-undo-v1|$op|$revision|$undo".toByteArray())
        private fun presentationId(op:String,card:String)=UUID.nameUUIDFromBytes("transform-presentation:$op:$card".toByteArray()).toString()
        private fun summaryId(op:String,card:String)=UUID.nameUUIDFromBytes("transform-summary:$op:$card".toByteArray()).toString()
        private fun mentions(data:KnowledgeData,ids:Set<String>):Boolean=when(data){
            is KnowledgeData.Link->data.source.kind==TargetKind.CARD&&data.source.id in ids||data.target.kind==TargetKind.CARD&&data.target.id in ids
            is KnowledgeData.CardPresentation->data.cardId in ids;is KnowledgeData.Properties->data.cardId in ids;is KnowledgeData.Question->data.cardId in ids
            is KnowledgeData.Placement->data.cardId in ids;is KnowledgeData.Alias->data.cardId in ids;is KnowledgeData.MapOccurrence->data.cardId in ids
            else->false
        }
        internal fun createTable(sql:SupportSQLiteDatabase){
            sql.execSQL("CREATE TABLE card_transform_operations (operationId TEXT NOT NULL, notebookId TEXT NOT NULL, digest TEXT NOT NULL, payload BLOB NOT NULL, afterFingerprint TEXT NOT NULL, revision INTEGER NOT NULL, undone INTEGER NOT NULL, undoOperationId TEXT, undoDigest TEXT, PRIMARY KEY(operationId), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            sql.execSQL("CREATE INDEX index_card_transform_operations_notebookId ON card_transform_operations(notebookId)")
        }
    }
}
