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
data class ExcerptRow(val id:String,val title:String,val body:String,val pageId:String,val left:Double,val top:Double,val right:Double,val bottom:Double)
@Dao
interface StudyDao {
    @Query("SELECT c.id,c.title,c.body,s.pageId,s.`left`,s.`top`,s.`right`,s.`bottom` FROM study_cards c JOIN study_sources s ON s.cardId=c.id WHERE c.notebookId=:book AND c.trashedAt IS NULL ORDER BY c.id") fun excerpts(book:String):Flow<List<ExcerptRow>>
    @Query("SELECT * FROM study_cards ORDER BY id") fun observeAllCards():Flow<List<StudyCardRow>>
    @Query("SELECT * FROM study_card_revisions WHERE cardId=:id AND revision=:revision") suspend fun cardVersion(id:String,revision:Long):StudyCardRevisionRow?
    @Query("SELECT * FROM study_cards WHERE notebookId=:book ORDER BY id") fun observeCards(book:String):Flow<List<StudyCardRow>>
    @Query("SELECT * FROM study_nodes WHERE notebookId=:book ORDER BY id") fun observeNodes(book:String):Flow<List<StudyNodeRow>>
    @Query("SELECT * FROM study_cards WHERE notebookId=:book ORDER BY id") suspend fun cards(book:String):List<StudyCardRow>
    @Query("SELECT * FROM study_nodes WHERE notebookId=:book ORDER BY id") suspend fun nodes(book:String):List<StudyNodeRow>
    @Query("SELECT * FROM study_cards WHERE id=:id") suspend fun card(id:String):StudyCardRow?
    @Query("SELECT * FROM study_nodes WHERE id=:id") suspend fun node(id:String):StudyNodeRow?
    @Query("SELECT * FROM study_sources WHERE cardId=:id") suspend fun source(id:String):StudySourceRow?
    @Query("SELECT * FROM study_receipts WHERE id=:id") suspend fun receipt(id:String):StudyReceiptRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun addCard(row:StudyCardRow)
    @Update suspend fun updateCard(row:StudyCardRow):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun revision(row:StudyCardRevisionRow)
    @Update suspend fun updateSource(row:StudySourceRow):Int
    @Query("SELECT * FROM study_sources WHERE cardId=:id") fun observeSource(id:String):Flow<StudySourceRow?>
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
    fun excerpts(book:String)=db.study().excerpts(book)
    fun cards(book:String)=db.study().observeCards(book)
    fun nodes(book:String,mapId:String?=null):Flow<List<StudyNodeRow>> = if(mapId==null)db.study().observeNodes(book)else db.knowledge().observe().map{rows->mapNodes(book,mapId,rows)}
    private fun mapNodes(book:String,mapId:String,rows:List<KnowledgeRow>)=rows.filter{it.notebookId==book}.mapNotNull{r->
        (r.data() as? KnowledgeData.MapOccurrence)?.takeIf{it.mapId==mapId}?.let{StudyNodeRow(r.id,book,it.cardId,it.parentId,it.x,it.y,r.revision,r.removed)}
    }
    fun observeSource(card:String)=db.study().observeSource(card)
    suspend fun source(card:String)=db.study().source(card)
    suspend fun lookup(c:StudyCommand):String?=db.withTransaction{
        db.study().receipt(c.id)?.let{studyRequire(it.notebookId==c.notebookId&&it.digest==c.digest());it.resultId}
    }
    suspend fun outcome(c:StudyCommand):StudyOutcome=try{StudyOutcome.Success(submit(c))}
    catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
    catch(e:StudyRejected){StudyOutcome.Rejected(e.message.orEmpty())}
    catch(e:KnowledgeRejected){StudyOutcome.Rejected(e.reason.name)}
    catch(_:Exception){try{lookup(c)?.let{StudyOutcome.Success(it)}?:StudyOutcome.Unknown}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){StudyOutcome.Unknown}}
    suspend fun submit(c:StudyCommand):String {
        val selectedMap=c.mapId
        val result=db.withTransaction {
            lookup(c)?.let{return@withTransaction it}
            val w=db.workspace().get(c.notebookId);studyRequire(w!=null&&w.trashedAt==null){"STUDY_BOOK_UNAVAILABLE"}
            val dao=db.study();val cards=dao.cards(c.notebookId);val nodes=if(selectedMap==null)dao.nodes(c.notebookId)else {
                val map=studyNotNull(db.knowledge().get(selectedMap));studyRequire(map.notebookId==c.notebookId&&!map.removed&&map.data() is KnowledgeData.MapDefinition){"MAP_UNAVAILABLE"}
                mapNodes(c.notebookId,selectedMap,db.knowledge().all())
            }
            suspend fun writeNode(n:StudyNodeRow,expected:Long){
                if(selectedMap==null){if(expected==0L)dao.addNode(n)else check(dao.updateNode(n)==1)}else {
                    val op=java.util.UUID.nameUUIDFromBytes((c.id+":"+n.id).toByteArray()).toString()
                    KnowledgeRepository(db).submit(KnowledgeCommand(op,c.notebookId,n.id,expected,KnowledgeData.MapOccurrence(selectedMap,n.cardId,n.parentId,n.x,n.y),n.removed))
                }
            }
            fun ownedCard():StudyCardRow=studyNotNull(cards.find{it.id==c.cardId})
            fun ownedNode():StudyNodeRow=studyNotNull(nodes.find{it.id==c.nodeId&&!it.removed})
            fun validParent(){studyRequire(c.parentId==null||nodes.any{it.id==c.parentId&&!it.removed}){"MAP_PARENT_UNAVAILABLE"}}
            suspend fun newNode(){
                studyRequire(nodes.size<256&&nodes.count{!it.removed}<StudyGraph.MAX_NODES)
                validParent();val n=StudyNodeRow(studyNotNull(c.nodeId),c.notebookId,studyNotNull(c.cardId),c.parentId,c.x,c.y)
                validateStudyGraph((nodes+ n).map{it.model()});writeNode(n,0)
            }
            suspend fun captureSource(s:StudySourceDraft,replacedBytes:Int=0):StudySourceRow {
                val p=studyNotNull(db.pages().get(s.pageId));studyRequire(p.notebookId==c.notebookId&&p.trashedAt==null)
                val ink=InkRepository(db).read(s.pageId);studyRequire(ink.revision==s.inkRevision){"SOURCE_VERSION_CHANGED"}
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
                };studyRequire(bytes.size<=1_800_000)
                val used=db.openHelper.writableDatabase.query("SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources").use{it.moveToFirst();it.getLong(0)}
                studyRequire(used-replacedBytes+bytes.size<=32_000_000){"STUDY_SNAPSHOT_BUDGET"}
                return StudySourceRow(studyNotNull(c.cardId),p.id,ink.revision,s.bounds.left,s.bounds.top,s.bounds.right,s.bounds.bottom,s.strokeIds.joinToString(","),bytes)
            }
            val id=when(c.action){
                StudyAction.CREATE,StudyAction.CREATE_EXCERPT->{
                    studyRequire(cards.size<200){"STUDY_CARD_BUDGET"}
                    var snap:StudySourceRow?=null
                    c.source?.let{snap=captureSource(it)}
                    val row=StudyCardRow(studyNotNull(c.cardId),c.notebookId,1,c.title,c.body)
                    dao.addCard(row);dao.revision(StudyCardRevisionRow(row.id,1,row.title,row.body,null));snap?.let{dao.source(it)};if(c.action==StudyAction.CREATE)newNode();row.id
                }
                StudyAction.RECROP_EXCERPT->{
                    val old=ownedCard();studyRequire(old.trashedAt==null&&old.revision==c.expectedRevision){"CARD_VERSION_CHANGED"}
                    val previous=studyNotNull(dao.source(old.id));val draft=studyNotNull(c.source)
                    studyRequire(draft.pageId==previous.pageId){"EXCERPT_PAGE_CHANGED"}
                    val next=captureSource(draft,previous.snapshot.size)
                    check(dao.updateSource(next)==1)
                    val row=old.copy(revision=old.revision+1)
                    check(dao.updateCard(row)==1);dao.revision(StudyCardRevisionRow(row.id,row.revision,row.title,row.body,row.trashedAt));row.id
                }
                StudyAction.EDIT,StudyAction.TRASH_CARD,StudyAction.RESTORE_CARD->{
                    val old=ownedCard();studyRequire(old.revision==c.expectedRevision){"CARD_VERSION_CHANGED"}
                    if(c.action==StudyAction.TRASH_CARD){studyRequire(dao.nodes(c.notebookId).none{it.cardId==old.id&&!it.removed}){"REMOVE_OCCURRENCES_FIRST"}
                        studyRequire(db.knowledge().all().none{r->!r.removed&&when(val d=r.data()){is KnowledgeData.Placement->d.cardId==old.id;is KnowledgeData.MapOccurrence->d.cardId==old.id;else->false}}){"REMOVE_OTHER_VIEW_OCCURRENCES_FIRST"}
                    }
                    if(c.action==StudyAction.EDIT)studyRequire(old.trashedAt==null)
                    val row=old.copy(revision=old.revision+1,title=if(c.action==StudyAction.EDIT)c.title else old.title,body=if(c.action==StudyAction.EDIT)c.body else old.body,
                        trashedAt=when(c.action){StudyAction.TRASH_CARD->System.currentTimeMillis();StudyAction.RESTORE_CARD->null;else->old.trashedAt})
                    check(dao.updateCard(row)==1);dao.revision(StudyCardRevisionRow(row.id,row.revision,row.title,row.body,row.trashedAt));row.id
                }
                StudyAction.REUSE->{studyRequire(ownedCard().trashedAt==null);newNode();studyNotNull(c.nodeId)}
                StudyAction.MOVE,StudyAction.REPARENT,StudyAction.REMOVE_NODE->{
                    val old=ownedNode();studyRequire(old.revision==c.expectedRevision){"NODE_VERSION_CHANGED"}
                    if(c.action==StudyAction.REMOVE_NODE)studyRequire(nodes.none{it.parentId==old.id&&!it.removed}){"REMOVE_CHILDREN_FIRST"}
                    if(c.action==StudyAction.REPARENT)validParent()
                    val next=old.copy(x=if(c.action==StudyAction.MOVE)c.x else old.x,y=if(c.action==StudyAction.MOVE)c.y else old.y,
                        parentId=if(c.action==StudyAction.REPARENT)c.parentId else old.parentId,removed=c.action==StudyAction.REMOVE_NODE,revision=old.revision+1)
                    validateStudyGraph(nodes.map{if(it.id==old.id)next.model()else it.model()});writeNode(next,old.revision);old.id
                }
                StudyAction.ARRANGE->{
                    studyRequire(c.expectedGraph==StudyGraph.orderHash(nodes.map{it.model()})){"MAP_VERSION_CHANGED"}
                    val positions=StudyGraph.arrange(nodes.map{it.model()});nodes.filter{!it.removed}.forEach{n->val p=positions.getValue(n.id);writeNode(n.copy(x=p.x,y=p.y,revision=n.revision+1),n.revision)};c.notebookId
                }
            }
            fault(StudyFault.BEFORE_RECEIPT);dao.receipt(StudyReceiptRow(c.id,c.notebookId,c.digest(),id));db.notes().touch(c.notebookId,System.currentTimeMillis());id
        };fault(StudyFault.AFTER_COMMIT);return result
    }
}
