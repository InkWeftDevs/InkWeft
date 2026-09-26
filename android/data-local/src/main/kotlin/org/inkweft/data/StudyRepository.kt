// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
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
@Dao
interface StudyDao {
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
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun source(row:StudySourceRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun addNode(row:StudyNodeRow)
    @Update suspend fun updateNode(row:StudyNodeRow):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun receipt(row:StudyReceiptRow)
}
enum class StudyFault { BEFORE_RECEIPT, AFTER_COMMIT }
class StudyRepository(private val db:NoteDatabase,private val fault:(StudyFault)->Unit={}) {
    fun cards(book:String)=db.study().observeCards(book)
    fun nodes(book:String)=db.study().observeNodes(book)
    suspend fun source(card:String)=db.study().source(card)
    suspend fun lookup(c:StudyCommand):String?=db.withTransaction{
        db.study().receipt(c.id)?.let{require(it.notebookId==c.notebookId&&it.digest==c.digest());it.resultId}
    }
    suspend fun submit(c:StudyCommand):String {
        val result=db.withTransaction {
            lookup(c)?.let{return@withTransaction it}
            val w=db.workspace().get(c.notebookId);require(w!=null&&w.trashedAt==null){"STUDY_BOOK_UNAVAILABLE"}
            val dao=db.study();val cards=dao.cards(c.notebookId);val nodes=dao.nodes(c.notebookId)
            fun ownedCard():StudyCardRow=checkNotNull(cards.find{it.id==c.cardId})
            fun ownedNode():StudyNodeRow=checkNotNull(nodes.find{it.id==c.nodeId&&!it.removed})
            fun validParent(){require(c.parentId==null||nodes.any{it.id==c.parentId&&!it.removed}){"MAP_PARENT_UNAVAILABLE"}}
            suspend fun newNode(){
                require(nodes.size<256&&nodes.count{!it.removed}<StudyGraph.MAX_NODES)
                validParent();val n=StudyNodeRow(checkNotNull(c.nodeId),c.notebookId,checkNotNull(c.cardId),c.parentId,c.x,c.y)
                StudyGraph.validate((nodes+ n).map{it.model()});dao.addNode(n)
            }
            val id=when(c.action){
                StudyAction.CREATE->{
                    require(cards.size<200){"STUDY_CARD_BUDGET"}
                    var snap:StudySourceRow?=null
                    c.source?.let{s->
                        val p=checkNotNull(db.pages().get(s.pageId));require(p.notebookId==c.notebookId&&p.trashedAt==null)
                        val ink=InkRepository(db).read(s.pageId);require(ink.revision==s.inkRevision){"SOURCE_VERSION_CHANGED"}
                        val visible=InkSession(ink).visibleDraft().associateBy{it.id};val selected=s.strokeIds.map{checkNotNull(visible[it])}
                        require(selected.all{val b=it.bounds();b.left>=s.bounds.left-1&&b.top>=s.bounds.top-1&&b.right<=s.bounds.right+1&&b.bottom<=s.bounds.bottom+1})
                        val bytes=InkPageFile("摘录原迹","",selected,p.world,PaperStyle.entries[p.paper]).encode();require(bytes.size<=1_800_000)
                        val used=db.openHelper.writableDatabase.query("SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources").use{it.moveToFirst();it.getLong(0)}
                        require(used+bytes.size<=32_000_000){"STUDY_SNAPSHOT_BUDGET"}
                        snap=StudySourceRow(checkNotNull(c.cardId),p.id,ink.revision,s.bounds.left,s.bounds.top,s.bounds.right,s.bounds.bottom,s.strokeIds.joinToString(","),bytes)
                    }
                    val row=StudyCardRow(checkNotNull(c.cardId),c.notebookId,1,c.title,c.body)
                    dao.addCard(row);dao.revision(StudyCardRevisionRow(row.id,1,row.title,row.body,null));snap?.let{dao.source(it)};newNode();row.id
                }
                StudyAction.EDIT,StudyAction.TRASH_CARD,StudyAction.RESTORE_CARD->{
                    val old=ownedCard();require(old.revision==c.expectedRevision){"CARD_VERSION_CHANGED"}
                    if(c.action==StudyAction.TRASH_CARD)require(nodes.none{it.cardId==old.id&&!it.removed}){"REMOVE_OCCURRENCES_FIRST"}
                    if(c.action==StudyAction.EDIT)require(old.trashedAt==null)
                    val row=old.copy(revision=old.revision+1,title=if(c.action==StudyAction.EDIT)c.title else old.title,body=if(c.action==StudyAction.EDIT)c.body else old.body,
                        trashedAt=when(c.action){StudyAction.TRASH_CARD->System.currentTimeMillis();StudyAction.RESTORE_CARD->null;else->old.trashedAt})
                    check(dao.updateCard(row)==1);dao.revision(StudyCardRevisionRow(row.id,row.revision,row.title,row.body,row.trashedAt));row.id
                }
                StudyAction.REUSE->{require(ownedCard().trashedAt==null);newNode();checkNotNull(c.nodeId)}
                StudyAction.MOVE,StudyAction.REPARENT,StudyAction.REMOVE_NODE->{
                    val old=ownedNode();require(old.revision==c.expectedRevision){"NODE_VERSION_CHANGED"}
                    if(c.action==StudyAction.REMOVE_NODE)require(nodes.none{it.parentId==old.id&&!it.removed}){"REMOVE_CHILDREN_FIRST"}
                    if(c.action==StudyAction.REPARENT)validParent()
                    val next=old.copy(x=if(c.action==StudyAction.MOVE)c.x else old.x,y=if(c.action==StudyAction.MOVE)c.y else old.y,
                        parentId=if(c.action==StudyAction.REPARENT)c.parentId else old.parentId,removed=c.action==StudyAction.REMOVE_NODE,revision=old.revision+1)
                    StudyGraph.validate(nodes.map{if(it.id==old.id)next.model()else it.model()});check(dao.updateNode(next)==1);old.id
                }
                StudyAction.ARRANGE->{
                    require(c.expectedGraph==StudyGraph.orderHash(nodes.map{it.model()})){"MAP_VERSION_CHANGED"}
                    val positions=StudyGraph.arrange(nodes.map{it.model()});nodes.filter{!it.removed}.forEach{n->val p=positions.getValue(n.id);check(dao.updateNode(n.copy(x=p.x,y=p.y,revision=n.revision+1))==1)};c.notebookId
                }
            }
            fault(StudyFault.BEFORE_RECEIPT);dao.receipt(StudyReceiptRow(c.id,c.notebookId,c.digest(),id));db.notes().touch(c.notebookId,System.currentTimeMillis());id
        };fault(StudyFault.AFTER_COMMIT);return result
    }
}
