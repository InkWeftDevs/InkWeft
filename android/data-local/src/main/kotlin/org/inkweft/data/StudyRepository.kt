// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.inkweft.core.*

@Entity(tableName="study_cards",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class StudyCardRow(@PrimaryKey val id:String,val notebookId:String,val revision:Long,val title:String,val body:String,val trashedAt:Long?=null)
@Entity(tableName="study_card_revisions",primaryKeys=["cardId","revision"],foreignKeys=[ForeignKey(entity=StudyCardRow::class,parentColumns=["id"],childColumns=["cardId"])])
data class StudyCardRevisionRow(val cardId:String,val revision:Long,val title:String,val body:String,val trashedAt:Long?)
@Entity(tableName="study_sources",indices=[Index("pageId")],foreignKeys=[ForeignKey(entity=StudyCardRow::class,parentColumns=["id"],childColumns=["cardId"]),ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"])])
data class StudySourceRow(@PrimaryKey val cardId:String,val pageId:String,val inkRevision:Long,val left:Double,val top:Double,val right:Double,val bottom:Double,val strokeIds:String,val snapshot:ByteArray)
@Entity(tableName="study_nodes",indices=[Index("notebookId"),Index("cardId"),Index("parentId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"]),ForeignKey(entity=StudyCardRow::class,parentColumns=["id"],childColumns=["cardId"]),ForeignKey(entity=StudyNodeRow::class,parentColumns=["id"],childColumns=["parentId"],deferred=true)])
data class StudyNodeRow(@PrimaryKey val id:String,val notebookId:String,val cardId:String,val parentId:String?,val x:Double,val y:Double,val revision:Long=1,val removed:Boolean=false){fun model()=StudyNode(id,cardId,parentId,x,y,revision,removed)}
@Entity(tableName="study_receipts",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class StudyReceiptRow(@PrimaryKey val id:String,val notebookId:String,val digest:String,val resultId:String)
data class StudySourceSummary(val cardId:String,val count:Int,val complete:Boolean)
data class ExcerptRow(val id:String,val title:String,val body:String,val pageId:String,val left:Double,val top:Double,val right:Double,val bottom:Double)
@Dao
interface StudyDao {
    @Query("SELECT c.id AS cardId,CASE WHEN f.cardId IS NULL THEN CASE WHEN EXISTS(SELECT 1 FROM study_sources s WHERE s.cardId=c.id) THEN 1 ELSE 0 END WHEN f.sourceRefs='' THEN 0 ELSE length(f.sourceRefs)-length(replace(f.sourceRefs,',',''))+1 END AS count,COALESCE(f.complete,1) AS complete FROM study_cards c LEFT JOIN study_card_source_sets f ON f.cardId=c.id AND f.cardRevision=c.revision WHERE c.notebookId=:book AND c.trashedAt IS NULL ORDER BY c.id") fun observeSourceSummaries(book:String):Flow<List<StudySourceSummary>>
    @Query("SELECT id,notebookId,cardId,removed FROM study_nodes ORDER BY id") fun observeLearningNodes():Flow<List<LearningNodeRow>>
    @Query("SELECT id,notebookId,title,trashedAt FROM study_cards ORDER BY id") fun observeLearningCards():Flow<List<LearningCardRow>>
    @Query("SELECT c.id FROM study_cards c WHERE c.notebookId=:book AND (EXISTS(SELECT 1 FROM study_card_source_sets v WHERE v.cardId=c.id AND v.cardRevision=c.revision AND v.sourceRefs!='') OR EXISTS(SELECT 1 FROM study_sources s WHERE s.cardId=c.id))") fun observeSourceIds(book:String):Flow<List<String>>
    @Query("SELECT c.id FROM study_cards c WHERE c.notebookId=:book AND (EXISTS(SELECT 1 FROM study_card_source_sets v WHERE v.cardId=c.id AND v.cardRevision=c.revision AND v.sourceRefs!='') OR EXISTS(SELECT 1 FROM study_sources s WHERE s.cardId=c.id))") suspend fun sourceIds(book:String):List<String>
    @Query("SELECT (SELECT COALESCE(SUM(length(snapshot)),0) FROM study_source_revisions) + (SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources s WHERE NOT EXISTS (SELECT 1 FROM study_source_revisions v WHERE v.sourceId=s.cardId))") fun observeSnapshotBytes():Flow<Long>
    @Query("SELECT (SELECT COALESCE(SUM(length(snapshot)),0) FROM study_source_revisions) + (SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources s WHERE NOT EXISTS (SELECT 1 FROM study_source_revisions v WHERE v.sourceId=s.cardId))") suspend fun snapshotBytes():Long
    @Query("SELECT c.id,c.title,c.body,s.pageId,s.`left`,s.`top`,s.`right`,s.`bottom` FROM study_cards c JOIN study_sources s ON s.cardId=c.id WHERE c.notebookId=:book AND c.trashedAt IS NULL ORDER BY c.id") fun excerpts(book:String):Flow<List<ExcerptRow>>
    @Query("SELECT * FROM study_cards ORDER BY id") fun observeAllCards():Flow<List<StudyCardRow>>
    @Query("SELECT * FROM study_card_revisions WHERE cardId=:id AND revision=:revision") suspend fun cardVersion(id:String,revision:Long):StudyCardRevisionRow?
    @Query("SELECT * FROM study_card_revisions WHERE cardId=:id ORDER BY revision") suspend fun cardVersions(id:String):List<StudyCardRevisionRow>
    @Query("SELECT * FROM study_cards WHERE notebookId=:book ORDER BY id") fun observeCards(book:String):Flow<List<StudyCardRow>>
    @Query("SELECT * FROM study_nodes WHERE notebookId=:book ORDER BY id") fun observeNodes(book:String):Flow<List<StudyNodeRow>>
    @Query("SELECT * FROM study_cards WHERE notebookId=:book ORDER BY id") suspend fun cards(book:String):List<StudyCardRow>
    @Query("SELECT * FROM study_nodes WHERE notebookId=:book ORDER BY id") suspend fun nodes(book:String):List<StudyNodeRow>
    @Query("SELECT * FROM study_cards WHERE id=:id") suspend fun card(id:String):StudyCardRow?
    @Query("SELECT * FROM study_nodes WHERE id=:id") suspend fun node(id:String):StudyNodeRow?
    @Query("SELECT c.id AS cardId,v.pageId,v.inkRevision,v.`left`,v.`top`,v.`right`,v.`bottom`,v.strokeIds,v.snapshot FROM study_cards c JOIN study_card_source_sets f ON f.cardId=c.id AND f.cardRevision=c.revision JOIN study_source_revisions v ON f.sourceRefs=v.sourceId||'@'||v.revision WHERE c.id=:id AND f.complete=1 UNION ALL SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE s.cardId=:id AND NOT EXISTS(SELECT 1 FROM study_card_source_sets f WHERE f.cardId=c.id AND f.cardRevision=c.revision)") suspend fun source(id:String):StudySourceRow?
    @Query("SELECT * FROM study_sources WHERE cardId=:id") suspend fun legacySource(id:String):StudySourceRow?
    @Query("SELECT * FROM study_receipts WHERE id=:id") suspend fun receipt(id:String):StudyReceiptRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun addCard(row:StudyCardRow)
    @Update suspend fun updateCard(row:StudyCardRow):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun revision(row:StudyCardRevisionRow)
    @Update suspend fun updateSource(row:StudySourceRow):Int
    @Query("SELECT c.id AS cardId,v.pageId,v.inkRevision,v.`left`,v.`top`,v.`right`,v.`bottom`,v.strokeIds,v.snapshot FROM study_cards c JOIN study_card_source_sets f ON f.cardId=c.id AND f.cardRevision=c.revision JOIN study_source_revisions v ON f.sourceRefs=v.sourceId||'@'||v.revision WHERE c.id=:id AND f.complete=1 UNION ALL SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE s.cardId=:id AND NOT EXISTS(SELECT 1 FROM study_card_source_sets f WHERE f.cardId=c.id AND f.cardRevision=c.revision)") fun observeSource(id:String):Flow<StudySourceRow?>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun source(row:StudySourceRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun addNode(row:StudyNodeRow)
    @Update suspend fun updateNode(row:StudyNodeRow):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun receipt(row:StudyReceiptRow)
}
class StudyRejected(message:String):IllegalArgumentException(message)
private inline fun studyRequire(value:Boolean,message:()->Any={"STUDY_INVALID"}){if(!value)throw StudyRejected(message().toString())}
private fun <T:Any> studyNotNull(value:T?):T=value?:throw StudyRejected("STUDY_SOURCE_UNAVAILABLE")
private fun validateStudyGraph(nodes:List<StudyNode>){try{StudyGraph.validate(nodes)}catch(e:IllegalArgumentException){throw StudyRejected(e.message?:"STUDY_GRAPH_INVALID")}}
sealed interface StudyOutcome { data class Success(val id:String):StudyOutcome;data class Rejected(val reason:String):StudyOutcome;data object Unknown:StudyOutcome }
enum class StudyFault { BEFORE_RECEIPT, AFTER_COMMIT }
class StudyRepository(private val db:NoteDatabase,private val fault:(StudyFault)->Unit={}) {
    val reuse by lazy{CardReuseRepository(db)}
    private val transformRepository by lazy{CardTransformRepository(db)}
    fun transforms()=transformRepository
    private val recallRepository by lazy{RecallStudyRepository(db)}
    fun recall()=recallRepository
    fun sourceSummaries(book:String)=db.study().observeSourceSummaries(book)
    fun excerpts(book:String)=db.study().excerpts(book)
    fun cards(book:String)=db.study().observeCards(book)
    fun observeSnapshotBytes():Flow<Long> = db.study().observeSnapshotBytes()
    suspend fun snapshotBytes():Long = db.study().snapshotBytes()
    fun observeGraph(book:String,mapId:String?=null):Flow<StudyGraphSnapshot> =
        db.invalidationTracker.createFlow("study_cards","study_nodes","knowledge_records","study_sources","study_card_source_sets","study_source_revisions").map{readGraph(book,mapId)}
    suspend fun readGraph(book:String,mapId:String?=null):StudyGraphSnapshot=db.withTransaction{
        graphSnapshot(MapRef(book,mapId),db.study().cards(book),db.study().nodes(book),db.knowledge().forBook(book))
    }
    fun nodes(book:String,mapId:String?=null):Flow<List<StudyNodeRow>> = observeGraph(book,mapId).map{it.nodes}
    fun observeSource(card:String)=StudySourceVersions(db).observe(card).map{it.singleLegacy(card)}
    suspend fun source(card:String)=StudySourceVersions(db).read(card).singleLegacy(card)
    suspend fun sources(card:String,revision:Long?=null)=StudySourceVersions(db).read(card,revision)
    suspend fun previewTrash(book:String,cardId:String):CardTrashPreview=readCardTrashPreview(db,book,cardId)
    suspend fun lookup(c:StudyCommand):String?=db.withTransaction{
        db.study().receipt(c.id)?.let{studyRequire(it.notebookId==c.notebookId&&it.digest==c.digest());it.resultId}
    }
    suspend fun outcome(c:StudyCommand):StudyOutcome=try{StudyOutcome.Success(submit(c))}
    catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
    catch(e:StudyRejected){StudyOutcome.Rejected(e.message.orEmpty())}
    catch(e:KnowledgeRejected){StudyOutcome.Rejected(e.reason.name)}
    catch(_:Exception){try{lookup(c)?.let{StudyOutcome.Success(it)}?:StudyOutcome.Unknown}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){StudyOutcome.Unknown}}

    private suspend fun commitGraph(before:StudyGraphSnapshot,candidates:List<StudyNodeRow>,requestedOrder:List<String>,organized:StudyGraphState?){
        try{
            val book=before.ref.notebookId;val mapId=before.ref.mapId;val knowledge=KnowledgeRepository(db)
            val oldById=before.nodes.associateBy{it.id};val structures=before.state.structuralNodeIds
            var nodes=candidates.filterNot{it.id in structures&&it.removed}
            val ids=nodes.map{it.id}.toSet()
            nodes=nodes.map{n->if(n.removed&&n.parentId!=null&&n.parentId !in ids)n.copy(parentId=null,revision=oldById.getValue(n.id).revision+1)else n}
            val changes=mutableListOf<KnowledgeRow>()
            var definitionRevision=before.state.definitionRevision
            if(mapId!=null){
                val row=studyNotNull(before.definition);val value=row.data() as KnowledgeData.MapDefinition;val nextById=nodes.associateBy{it.id}
                val definition=value.copy(structures=value.structures.mapNotNull{s->nextById[s.id]?.takeUnless{it.removed}?.let{s.copy(parentId=it.parentId,x=it.x,y=it.y)}})
                if(definition!=value){definitionRevision=row.revision+1;changes+=row.copy(revision=definitionRevision,payload=KnowledgeCodec.encode(definition))}
                nodes=nodes.map{if(it.id in structures)it.copy(revision=definitionRevision)else it}
                nodes.filter{it.id !in structures}.forEach{node->
                    if(oldById[node.id]!=node)changes+=KnowledgeRow(node.id,book,node.revision,
                        KnowledgeCodec.encode(KnowledgeData.MapOccurrence(mapId,node.cardId,node.parentId,node.x,node.y)),node.removed)
                }
            }
            val order=canonicalGraphOrder(nodes.map{it.model()},requestedOrder)
            val orderRevision=if(before.order==null)1L else before.order.revision+if(order!=before.orderedNodeIds)1 else 0
            if(before.order==null||orderRevision!=before.order.revision){
                changes+=KnowledgeRow(before.order?.id?:knowledge.orderId(book,mapId),book,orderRevision,
                    KnowledgeCodec.encode(KnowledgeData.MapOrder(mapId,order)))
            }
            if(organized!=null){
                studyRequire(order==organized.orderedNodeIds&&orderRevision==organized.orderRevision&&definitionRevision==organized.definitionRevision){"MAP_PLAN_VERSION_MISMATCH"}
                studyRequire(nodes.map{it.model()}.associateBy{it.id}==organized.nodes.associateBy{it.id}){"MAP_PLAN_NODE_MISMATCH"}
            }
            knowledge.commitGraph(book,changes,if(mapId==null)nodes else null)
            if(organized!=null)studyRequire(readGraph(book,mapId).graphFingerprint==StudyOrganization.fingerprint(organized)){"MAP_AFTER_CONFLICT"}
        }catch(e:IllegalArgumentException){if(e is StudyRejected)throw e;throw StudyRejected(e.message?:"STUDY_GRAPH_INVALID")}
    }

    suspend fun submit(c:StudyCommand):String {
        val selectedMap=c.mapId
        val result=db.withTransaction {
            lookup(c)?.let{return@withTransaction it}
            val w=db.workspace().get(c.notebookId);studyRequire(w!=null&&w.trashedAt==null){"STUDY_BOOK_UNAVAILABLE"}
            if(c.action==StudyAction.ARRANGE)throw StudyRejected("LAYOUT_PREVIEW_REQUIRED")
            val dao=db.study();val before=readGraph(c.notebookId,selectedMap);val cards=before.cards;val nodes=before.nodes
            if(selectedMap!=null)studyRequire(before.definition?.let{!it.removed}==true){"MAP_UNAVAILABLE"}
            if(c.expectedGraph.isNotEmpty())studyRequire(c.expectedGraph==before.graphFingerprint){"MAP_VERSION_CHANGED"}
            var finalNodes=nodes;var finalOrder=before.orderedNodeIds;var graphEdited=false
            var organized:StudyGraphState?=null
            fun writeNode(n:StudyNodeRow,expected:Long){
                val old=finalNodes.find{it.id==n.id};studyRequire((old?.revision?:0)==expected){"NODE_VERSION_CHANGED"}
                finalNodes=if(old==null)finalNodes+n else finalNodes.map{if(it.id==n.id)n else it};graphEdited=true
            }
            fun organization(plan:StudyOrganizationPlan){
                val next=try{StudyOrganization.apply(before.state,plan)}catch(e:IllegalArgumentException){throw StudyRejected(e.message?:"MAP_VERSION_CHANGED")}
                organized=next;finalNodes=next.nodes.map{StudyNodeRow(it.id,c.notebookId,it.cardId,it.parentId,it.x,it.y,it.revision,it.removed)}
                finalOrder=next.orderedNodeIds;graphEdited=true
            }
            fun ownedCard():StudyCardRow=studyNotNull(cards.find{it.id==c.cardId})
            fun ownedNode():StudyNodeRow=studyNotNull(nodes.find{it.id==c.nodeId&&!it.removed})
            fun validParent(){studyRequire(c.parentId==null||nodes.any{it.id==c.parentId&&!it.removed}){"MAP_PARENT_UNAVAILABLE"}}
            suspend fun newNode(){
                studyRequire(nodes.size<StudyGraph.MAX_RECORDS){"STUDY_NODE_RECORD_BUDGET"}
                studyRequire(nodes.count{!it.removed}<StudyGraph.MAX_NODES){"STUDY_NODE_BUDGET"}
                validParent();val n=StudyNodeRow(studyNotNull(c.nodeId),c.notebookId,studyNotNull(c.cardId),c.parentId,c.x,c.y)
                validateStudyGraph((nodes+n).map{it.model()})
                val anchor=c.afterNodeId?.let{id->studyNotNull(nodes.find{it.id==id&&!it.removed}).also{studyRequire(it.parentId==c.parentId){"MAP_INSERT_ANCHOR_CHANGED"}}}?.id?:c.parentId
                val descendants=mutableSetOf<String>();if(anchor!=null){descendants+=anchor;var added=true;while(added){added=false;nodes.filter{!it.removed&&it.parentId in descendants}.forEach{if(descendants.add(it.id))added=true}}}
                val index=if(anchor==null)finalOrder.size else finalOrder.indexOfLast{it in descendants}+1
                finalOrder=finalOrder.toMutableList().also{it.add(index,n.id)};writeNode(n,0)
            }
            suspend fun captureSource(s:StudySourceDraft):StudySourceRow {
                val p=studyNotNull(db.pages().get(s.pageId));studyRequire(p.notebookId==c.notebookId&&p.trashedAt==null)
                val ink=InkRepository(db).read(s.pageId);studyRequire(ink.revision==s.inkRevision){"SOURCE_VERSION_CHANGED"}
                val authoring=PageAuthoringRepository(db).readPage(s.pageId)
                studyRequire(s.authoringRevision?.let{it==authoring.revision}?:authoring.state.legacy){"SOURCE_AUTHORING_CHANGED"}
                studyRequire(s.strokeIds.all{authoring.state.layers.visible(LayerContent(LayerContentKind.INK,it))}){"SOURCE_LAYER_HIDDEN"}
                val visible=InkSession(ink).visibleDraft().associateBy{it.id};val selected=s.strokeIds.map{studyNotNull(visible[it])}
                val picture=s.previewBytes()
                studyRequire(picture==null||p.world||(s.bounds.left>=0&&s.bounds.top>=0&&s.bounds.right<=1000&&s.bounds.bottom<=1414)){"EXCERPT_OUTSIDE_PAGE"}
                if(picture!=null){studyRequire(s.objectRevision==(db.objects().get(s.pageId)?.revision?:0L)){"OBJECT_VERSION_CHANGED"}}
                studyRequire(picture!=null||selected.all{val b=it.bounds();b.left>=s.bounds.left-1&&b.top>=s.bounds.top-1&&b.right<=s.bounds.right+1&&b.bottom<=s.bounds.bottom+1})
                val bytes=if(picture==null)InkPageFile("摘录原迹","",selected,p.world,PaperStyle.entries[p.paper]).encode()else {
                    val w=s.bounds.right-s.bounds.left;val h=s.bounds.bottom-s.bounds.top
                    val scale=maxOf(24.0/minOf(w,h),minOf(1.0,1000.0/maxOf(w,h)))
                    studyRequire(maxOf(w,h)*scale<=4000){"EXCERPT_TOO_NARROW"}
                    InkPageFile("区域摘录","",emptyList(),true,PaperStyle.BLANK,listOf(PageObject(java.util.UUID.randomUUID().toString(),PageObjectKind.IMAGE,0f,0f,(w*scale).toFloat(),(h*scale).toFloat(),image=java.util.Base64.getEncoder().encodeToString(picture)))).encode()
                };studyRequire(bytes.size<=StudyCapacity.MAX_SOURCE_BYTES){"STUDY_SNAPSHOT_TOO_LARGE"}
                val used=dao.snapshotBytes()
                studyRequire(used+bytes.size<=StudyCapacity.MAX_SNAPSHOT_BYTES){"STUDY_SNAPSHOT_BUDGET"}
                return StudySourceRow(studyNotNull(c.cardId),p.id,ink.revision,s.bounds.left,s.bounds.top,s.bounds.right,s.bounds.bottom,s.strokeIds.joinToString(","),bytes)
            }
            val id=when(c.action){
                StudyAction.CREATE,StudyAction.CREATE_EXCERPT->{
                    studyRequire(cards.size<StudyCapacity.MAX_CARDS_PER_NOTEBOOK){"STUDY_CARD_BUDGET"}
                    var snap:StudySourceRow?=null
                    c.source?.let{snap=captureSource(it)}
                    val row=StudyCardRow(studyNotNull(c.cardId),c.notebookId,1,c.title,c.body)
                    dao.addCard(row);dao.revision(StudyCardRevisionRow(row.id,1,row.title,row.body,null))
                    if(snap!=null){dao.source(snap!!);StudySourceVersions(db).capture(row,snap!!)}else StudySourceVersions(db).writeSet(row.id,1,emptyList(),true)
                    if(c.action==StudyAction.CREATE)newNode();row.id
                }
                StudyAction.UNDO_CAPTURE->{
                    val old=ownedNode();studyRequire(old.cardId==c.cardId&&old.revision==c.expectedRevision){"CAPTURE_ALREADY_CHANGED"}
                    studyRequire(nodes.none{it.parentId==old.id&&!it.removed}){"REMOVE_CHILDREN_FIRST"}
                    writeNode(old.copy(removed=true,revision=old.revision+1),old.revision)
                    val card=ownedCard()
                    val used=(if(selectedMap==null)finalNodes else dao.nodes(c.notebookId)).any{!it.removed&&it.cardId==card.id}||
                        (selectedMap!=null&&finalNodes.any{!it.removed&&it.cardId==card.id})||db.knowledge().all().any{r->!r.removed&&when(val d=r.data()){
                            is KnowledgeData.MapOccurrence->d.mapId!=selectedMap&&d.cardId==card.id
                            is KnowledgeData.CardPresentation->d.cardId==card.id;is KnowledgeData.Placement->d.cardId==card.id;is KnowledgeData.Properties->d.cardId==card.id;is KnowledgeData.Question->d.cardId==card.id;is KnowledgeData.Alias->d.cardId==card.id;is KnowledgeData.Link->d.source==TargetRef(TargetKind.CARD,card.id)||d.target==TargetRef(TargetKind.CARD,card.id);else->false}}
                    if(!used&&card.revision==1L&&card.trashedAt==null){StudySourceVersions(db).freezeCurrent(card);val next=card.copy(revision=2,trashedAt=System.currentTimeMillis());check(dao.updateCard(next)==1);dao.revision(StudyCardRevisionRow(next.id,next.revision,next.title,next.body,next.trashedAt));StudySourceVersions(db).copyVersion(next,card.revision)};old.id
                }
                StudyAction.RECROP_EXCERPT->{
                    val old=ownedCard();studyRequire(old.trashedAt==null&&old.revision==c.expectedRevision){"CARD_VERSION_CHANGED"}
                    val previous=studyNotNull(dao.source(old.id));val draft=studyNotNull(c.source)
                    studyRequire(draft.pageId==previous.pageId){"EXCERPT_PAGE_CHANGED"}
                    StudySourceVersions(db).freezeCurrent(old)
                    val next=captureSource(draft)
                    if(dao.legacySource(old.id)==null)dao.source(next)else check(dao.updateSource(next)==1)
                    val row=old.copy(revision=old.revision+1)
                    check(dao.updateCard(row)==1);dao.revision(StudyCardRevisionRow(row.id,row.revision,row.title,row.body,row.trashedAt));StudySourceVersions(db).capture(row,next);row.id
                }
                StudyAction.EDIT,StudyAction.TRASH_CARD,StudyAction.RESTORE_CARD->{
                    val old=ownedCard();studyRequire(old.revision==c.expectedRevision){"CARD_VERSION_CHANGED"}
                    StudySourceVersions(db).freezeCurrent(old)
                    if(c.action==StudyAction.TRASH_CARD){
                        // Receipt lookup above still resolves old confirmed operations; never replay a legacy unpreviewed write.
                        studyRequire(c.expectedTrashImpact.isNotEmpty()){ "TRASH_PREVIEW_REQUIRED" }
                        studyRequire(old.trashedAt==null){"CARD_VERSION_CHANGED"}
                        studyRequire(previewTrash(c.notebookId,old.id).fingerprint==c.expectedTrashImpact){"TRASH_IMPACT_CHANGED"}
                        studyRequire(dao.nodes(c.notebookId).none{it.cardId==old.id&&!it.removed}){"REMOVE_OCCURRENCES_FIRST"}
                        studyRequire(db.knowledge().all().none{r->!r.removed&&when(val d=r.data()){is KnowledgeData.Placement->d.cardId==old.id;is KnowledgeData.MapOccurrence->d.cardId==old.id;else->false}}){"REMOVE_OTHER_VIEW_OCCURRENCES_FIRST"}
                    }
                    if(c.action==StudyAction.EDIT)studyRequire(old.trashedAt==null)
                    val row=old.copy(revision=old.revision+1,title=if(c.action==StudyAction.EDIT)c.title else old.title,body=if(c.action==StudyAction.EDIT)c.body else old.body,
                        trashedAt=when(c.action){StudyAction.TRASH_CARD->System.currentTimeMillis();StudyAction.RESTORE_CARD->null;else->old.trashedAt})
                    check(dao.updateCard(row)==1);dao.revision(StudyCardRevisionRow(row.id,row.revision,row.title,row.body,row.trashedAt));StudySourceVersions(db).copyVersion(row,old.revision);row.id
                }
                StudyAction.REUSE->{studyRequire(ownedCard().trashedAt==null);newNode();studyNotNull(c.nodeId)}
                StudyAction.MOVE,StudyAction.REPARENT,StudyAction.REMOVE_NODE->{
                    val old=ownedNode();studyRequire(old.revision==c.expectedRevision){"NODE_VERSION_CHANGED"}
                    if(c.action==StudyAction.REMOVE_NODE)studyRequire(nodes.none{it.parentId==old.id&&!it.removed}){"REMOVE_CHILDREN_FIRST"}
                    if(c.action==StudyAction.REPARENT)validParent()
                    if(c.action==StudyAction.REMOVE_NODE)writeNode(old.copy(removed=true,revision=old.revision+1),old.revision)
                    else try{organization(if(c.action==StudyAction.MOVE)StudyOrganization.move(before.state,old.id,c.x,c.y)else StudyOrganization.reparent(before.state,old.id,c.parentId))}
                        catch(e:IllegalArgumentException){throw StudyRejected(e.message?:"STUDY_GRAPH_INVALID")}
                    old.id
                }
                StudyAction.ARRANGE->throw StudyRejected("LAYOUT_PREVIEW_REQUIRED")
                StudyAction.ORGANIZE->{organization(studyNotNull(c.organization));selectedMap?:c.notebookId}
            }
            if(graphEdited)commitGraph(before,finalNodes,finalOrder,organized)
            fault(StudyFault.BEFORE_RECEIPT);dao.receipt(StudyReceiptRow(c.id,c.notebookId,c.digest(),id));db.notes().touch(c.notebookId,System.currentTimeMillis());id
        };fault(StudyFault.AFTER_COMMIT);return result
    }
}
