// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="knowledge_records",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class KnowledgeRow(@PrimaryKey val id:String,val notebookId:String,val revision:Long,val payload:ByteArray,val removed:Boolean=false){fun data()=KnowledgeCodec.decode(payload)}
@Entity(tableName="knowledge_revisions",primaryKeys=["id","revision"],indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=KnowledgeRow::class,parentColumns=["id"],childColumns=["id"]),ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class KnowledgeRevisionRow(val id:String,val revision:Long,val notebookId:String,val payload:ByteArray,val removed:Boolean)
@Entity(tableName="knowledge_receipts",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class KnowledgeReceiptRow(@PrimaryKey val operationId:String,val notebookId:String,val digest:String,val resultId:String)
@Dao interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_records WHERE notebookId=:book ORDER BY id") fun observeBook(book:String):Flow<List<KnowledgeRow>>
    @Query("SELECT * FROM knowledge_records WHERE notebookId=:book ORDER BY id") suspend fun forBook(book:String):List<KnowledgeRow>
    @Query("SELECT * FROM knowledge_records ORDER BY id") fun observe():Flow<List<KnowledgeRow>>
    @Query("SELECT * FROM knowledge_records ORDER BY id") suspend fun all():List<KnowledgeRow>
    @Query("SELECT * FROM knowledge_records WHERE id=:id") suspend fun get(id:String):KnowledgeRow?
    @Query("SELECT * FROM knowledge_revisions WHERE id=:id AND revision=:revision") suspend fun revision(id:String,revision:Long):KnowledgeRevisionRow?
    @Query("SELECT * FROM knowledge_revisions WHERE id=:id ORDER BY revision DESC") suspend fun revisions(id:String):List<KnowledgeRevisionRow>
    @Query("SELECT * FROM knowledge_receipts WHERE operationId=:id") suspend fun receipt(id:String):KnowledgeReceiptRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:KnowledgeRow)
    @Update suspend fun update(row:KnowledgeRow):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun revision(row:KnowledgeRevisionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun receipt(row:KnowledgeReceiptRow)
}

enum class KnowledgeRejection { CONFLICT, DUPLICATE, UNAVAILABLE, INVALID }
class KnowledgeRejected(val reason:KnowledgeRejection):IllegalArgumentException(reason.name)
sealed interface KnowledgeOutcome {
    data class Success(val id:String):KnowledgeOutcome
    data class Rejected(val reason:KnowledgeRejection):KnowledgeOutcome
    data object Unknown:KnowledgeOutcome
}
enum class KnowledgeFault { BEFORE_RECEIPT, AFTER_COMMIT }
class KnowledgeRepository(private val db:NoteDatabase,private val fault:(KnowledgeFault)->Unit={}){
    fun observe()=db.knowledge().observe()
    fun observeBook(book:String)=db.knowledge().observeBook(book)
    fun cards()=db.study().observeAllCards()
    fun pages()=db.pages().observeLibraryPages()
    fun notes()=db.notes().observeAvailableNotes()
    suspend fun resolve(ref:TargetRef):Pair<Note,KnowledgeData.Anchor?> = db.withTransaction {
        val book=owner(ref,true);val row=requireNotNull(db.notes().note(book))
        val anchor=if(ref.kind==TargetKind.ANCHOR)db.knowledge().get(ref.id)?.data() as? KnowledgeData.Anchor else null
        row.let{Note(it.id,it.revision,it.title,it.text)} to anchor
    }
    suspend fun cardVersion(id:String,revision:Long)=db.study().cardVersion(id,revision)
    suspend fun lookup(c:KnowledgeCommand):String?=db.withTransaction{db.knowledge().receipt(c.operationId)?.let{require(it.notebookId==c.notebookId&&it.digest==c.digest());it.resultId}}
    suspend fun outcome(c:KnowledgeCommand):KnowledgeOutcome = commandOutcome(c,null)
    suspend fun reviewOutcome(c:KnowledgeCommand,expectedCardRevision:Long):KnowledgeOutcome {
        require(expectedCardRevision>0&&c.data is KnowledgeData.Question&&!c.removed)
        return commandOutcome(c,expectedCardRevision)
    }
    private suspend fun commandOutcome(c:KnowledgeCommand,expectedCardRevision:Long?):KnowledgeOutcome = try { KnowledgeOutcome.Success(submit(c,expectedCardRevision)) }
    catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
    catch(rejected:KnowledgeRejected){KnowledgeOutcome.Rejected(rejected.reason)}
    catch(_:Exception){
        try { lookup(c)?.let{KnowledgeOutcome.Success(it)}?:KnowledgeOutcome.Unknown }
        catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(_:Exception){KnowledgeOutcome.Unknown}
    }
    suspend fun submit(c:KnowledgeCommand,expectedReviewCardRevision:Long?=null):String{
        val result=db.withTransaction{
            lookup(c)?.let{return@withTransaction it}
            val dao=db.knowledge()
            val (old,next)=try {
            require(db.workspace().get(c.notebookId)?.trashedAt==null&&db.notes().note(c.notebookId)!=null){"BOOK_UNAVAILABLE"}
            if(expectedReviewCardRevision!=null){
                val question=c.data as? KnowledgeData.Question
                require(question!=null&&!c.removed&&expectedReviewCardRevision>0){"INVALID_REVIEW_COMMAND"}
                val card=db.study().card(question.cardId)
                require(card!=null&&card.trashedAt==null&&card.revision==expectedReviewCardRevision){"REVIEW_CARD_CHANGED"}
            }
            val old=dao.get(c.id);require((old?.revision?:0)==c.expectedRevision){"KNOWLEDGE_VERSION_CHANGED"}
            require(old==null||old.notebookId==c.notebookId&&old.data()::class==c.data::class)
            require(!c.removed||old!=null)
            if(c.removed)require(old!!.payload.contentEquals(c.payload)){"REMOVE_MUST_PRESERVE_PAYLOAD"}
            val records=dao.all();require(old!=null||records.count{it.notebookId==c.notebookId}<2000){"KNOWLEDGE_BUDGET"}
            validateData(c.notebookId,c.data,!c.removed)
            if(c.data is KnowledgeData.Anchor&&old==null){val a=c.data as KnowledgeData.Anchor;require((db.ink().page(a.pageId)?.revision?:0)==a.inkRevision){"SOURCE_CHANGED"}
                val visible=InkSession(InkRepository(db).read(a.pageId)).visibleDraft().associateBy{it.id}
                a.strokeIds.forEach{id->val b=requireNotNull(visible[id]).bounds();require(b.left>=a.bounds.left-1&&b.right<=a.bounds.right+1&&b.top>=a.bounds.top-1&&b.bottom<=a.bounds.bottom+1)}
            }
            if(c.data is KnowledgeData.PageMark&&!c.removed){val d=c.data as KnowledgeData.PageMark;if(d.bookmark)require(records.none{it.id!=c.id&&!it.removed&&(it.data() as? KnowledgeData.PageMark)?.let{m->m.bookmark&&m.pageId==d.pageId}==true}){"BOOKMARK_EXISTS"}}
            if(c.data is KnowledgeData.Properties){val d=c.data as KnowledgeData.Properties;require(records.none{it.id!=c.id&&!it.removed&&(it.data() as? KnowledgeData.Properties)?.cardId==d.cardId}){"PROPERTY_EXISTS"}}
            if(c.data is KnowledgeData.Link&&!c.removed)require(records.none{it.id!=c.id&&!it.removed&&it.data()==c.data}){"LINK_EXISTS"}
            if(c.data is KnowledgeData.MapPortal&&!c.removed)require(records.none{it.id!=c.id&&it.notebookId==c.notebookId&&!it.removed&&it.data()==c.data}){"MAP_PORTAL_EXISTS"}
            val next=KnowledgeRow(c.id,c.notebookId,c.expectedRevision+1,c.payload,c.removed)
            validateMaps(records.filter{it.id!=c.id}+next)
            old to next
            }catch(e:IllegalArgumentException){
                throw KnowledgeRejected(when(e.message){
                    "KNOWLEDGE_VERSION_CHANGED","SOURCE_CHANGED","REVIEW_CARD_CHANGED"->KnowledgeRejection.CONFLICT
                    "BOOKMARK_EXISTS","PROPERTY_EXISTS","LINK_EXISTS","MAP_PORTAL_EXISTS"->KnowledgeRejection.DUPLICATE
                    "BOOK_UNAVAILABLE","MAP_PORTAL_SOURCE_UNAVAILABLE","MAP_PORTAL_TARGET_UNAVAILABLE"->KnowledgeRejection.UNAVAILABLE
                    else->KnowledgeRejection.INVALID
                })
            }
            if(old==null)dao.insert(next)else check(dao.update(next)==1)
            dao.revision(KnowledgeRevisionRow(next.id,next.revision,next.notebookId,next.payload,next.removed))
            fault(KnowledgeFault.BEFORE_RECEIPT);dao.receipt(KnowledgeReceiptRow(c.operationId,c.notebookId,c.digest(),c.id));db.notes().touch(c.notebookId,System.currentTimeMillis());c.id
        };fault(KnowledgeFault.AFTER_COMMIT);return result
    }
    suspend fun available(ref:TargetRef):Boolean=try{owner(ref,true);true}catch(c:kotlinx.coroutines.CancellationException){throw c}catch(_:Exception){false}
    private suspend fun owner(ref:TargetRef,active:Boolean):String {
        val book=when(ref.kind){
            TargetKind.NOTE->{require(db.notes().note(ref.id)!=null);ref.id}
            TargetKind.PAGE->{val p=requireNotNull(db.pages().get(ref.id));if(active)require(p.trashedAt==null);p.notebookId}
            TargetKind.CARD->{val c=requireNotNull(db.study().card(ref.id));if(active)require(c.trashedAt==null);c.notebookId}
            TargetKind.ANCHOR->{val a=requireNotNull(db.knowledge().get(ref.id));require(a.data() is KnowledgeData.Anchor);if(active)require(!a.removed);val d=a.data() as KnowledgeData.Anchor;owner(TargetRef(TargetKind.PAGE,d.pageId),active);a.notebookId}
        }
        if(active)require(db.workspace().get(book)?.trashedAt==null&&db.notes().note(book)!=null)
        return book
    }
    suspend fun validateData(book:String,data:KnowledgeData,active:Boolean){
        KnowledgeCodec.validate(data)
        suspend fun card(id:String){require(owner(TargetRef(TargetKind.CARD,id),active)==book)}
        when(data){
            is KnowledgeData.PageMark->require(owner(TargetRef(TargetKind.PAGE,data.pageId),active)==book)
            is KnowledgeData.Anchor->{require(owner(TargetRef(TargetKind.PAGE,data.pageId),active)==book);require(data.inkRevision<=(db.ink().page(data.pageId)?.revision?:0));data.strokeIds.forEach{require(db.ink().stroke(it)?.noteId==data.pageId)}}
            is KnowledgeData.Link->{require(owner(data.source,active)==book);owner(data.target,active);data.pinnedRevision?.let{require(db.study().cardVersion(data.target.id,it)!=null)}}
            is KnowledgeData.Properties->card(data.cardId)
            is KnowledgeData.Question->card(data.cardId)
            is KnowledgeData.Placement->card(data.cardId)
            is KnowledgeData.Alias->card(data.cardId)
            is KnowledgeData.MapDefinition,is KnowledgeData.MapTemplate->Unit
            is KnowledgeData.MapOccurrence->{card(data.cardId);val map=requireNotNull(db.knowledge().get(data.mapId));require(map.notebookId==book&&map.data() is KnowledgeData.MapDefinition);if(active)require(!map.removed)}
            is KnowledgeData.MapPortal->MapPortalRepository(db).validateData(book,data,active)
            is KnowledgeData.Decoration->{for(id in listOf(data.from,data.to)){val p=requireNotNull(db.knowledge().get(id));require(p.notebookId==book&&p.data() is KnowledgeData.Placement);if(active)require(!p.removed)}}
            is KnowledgeData.Collection->Unit
        }
    }
    suspend fun validateArchive(){
        val rows=db.knowledge().all();require(rows.size<=500*2000)
        require(rows.groupBy{it.notebookId}.values.all{it.size<=2000})
        for(row in rows){
            UUID.fromString(row.id);require(row.revision in 1 until Long.MAX_VALUE)
            validateData(row.notebookId,row.data(),false)
            val version=requireNotNull(db.knowledge().revision(row.id,row.revision))
            require(version.notebookId==row.notebookId&&version.payload.contentEquals(row.payload)&&version.removed==row.removed)
        }
        val props=rows.filter{!it.removed}.mapNotNull{it.data() as? KnowledgeData.Properties}
        require(props.map{it.cardId}.distinct().size==props.size)
        val portals=rows.filter{!it.removed}.mapNotNull{row->(row.data() as? KnowledgeData.MapPortal)?.let{row.notebookId to it}}
        require(portals.distinct().size==portals.size){"MAP_PORTAL_EXISTS"}
        validateMaps(rows)
    }
    private fun validateMaps(rows:List<KnowledgeRow>){
        val maps=rows.filter{it.data() is KnowledgeData.MapDefinition}.associateBy{it.id}
        val nodes=rows.mapNotNull{r->(r.data() as? KnowledgeData.MapOccurrence)?.let{r to it}}
        for((mapId,items) in nodes.groupBy{it.second.mapId}){
            val map=requireNotNull(maps[mapId]);require(items.all{it.first.notebookId==map.notebookId});if(map.removed)require(items.all{it.first.removed})
            StudyGraph.validate((map.data() as KnowledgeData.MapDefinition).structures.map{StudyNode(it.id,it.id,it.parentId,it.x,it.y,map.revision)}+items.map{(r,n)->StudyNode(r.id,n.cardId,n.parentId,n.x,n.y,r.revision,r.removed)})
        }
    }
}
