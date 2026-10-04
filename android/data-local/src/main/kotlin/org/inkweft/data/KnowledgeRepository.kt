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

enum class KnowledgeRejection { CONFLICT, DUPLICATE, UNAVAILABLE, INVALID, STUDY_NODE_BUDGET, STUDY_NODE_RECORD_BUDGET, KNOWLEDGE_BUDGET }
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
    internal fun orderId(book:String,mapId:String?)=UUID.nameUUIDFromBytes("inkweft.map-order.v1:$book:${mapId?:"main"}".toByteArray()).toString()

    /** Build the order and tombstone changes against the final graph, before any row is written. */
    internal suspend fun graphChanges(book:String,changes:List<KnowledgeRow>,preferredOrders:Map<String?,List<String>> = emptyMap()):List<KnowledgeRow>{
        val previous=db.knowledge().forBook(book);val main=db.study().nodes(book)
        val oldById=previous.associateBy{it.id};val updates=changes.associateBy{it.id}.toMutableMap()
        val affected=linkedSetOf<String?>()
        changes.forEach{row->listOfNotNull(oldById[row.id],row).forEach{r->when(val data=r.data()){
            is KnowledgeData.MapDefinition->affected+=r.id
            is KnowledgeData.MapOccurrence->affected+=data.mapId
            is KnowledgeData.MapOrder->affected+=data.mapId
            else->error("NOT_A_MAP_RECORD")
        }}}
        fun records()=previous.filter{it.id !in updates}+updates.values
        for(mapId in affected){
            val oldNodes=graphNodes(book,mapId,main,previous)
            var current=records()
            val definition=mapId?.let{id->current.find{it.id==id}}
            val existingOrder=graphOrder(previous,mapId)?:previous.firstOrNull{(it.data() as? KnowledgeData.MapOrder)?.let{v->v.mapId==mapId}==true}
            var nextNodes=graphNodes(book,mapId,main,current)
            val ids=nextNodes.map{it.id}.toSet()
            // Structure nodes live in one definition; their old revisions remain the historical owner.
            // Detached occurrence tombstones must not keep a parent that no longer exists in that definition.
            nextNodes.filter{it.removed&&it.parentId!=null&&it.parentId !in ids}.forEach{node->
                val row=current.firstOrNull{it.id==node.id}?:return@forEach
                val value=row.data() as? KnowledgeData.MapOccurrence?:return@forEach
                updates[row.id]=row.copy(revision=(oldById[row.id]?.revision?:0)+1,payload=KnowledgeCodec.encode(value.copy(parentId=null)))
            }
            current=records();nextNodes=graphNodes(book,mapId,main,current)
            if(definition?.removed==true){
                existingOrder?.takeIf{!it.removed}?.let{updates[it.id]=it.copy(revision=it.revision+1,removed=true)}
                continue
            }
            val seed=preferredOrders[mapId]?:((existingOrder?.data() as? KnowledgeData.MapOrder)?.orderedNodeIds
                ?:StudyOrganization.legacyOrder(oldNodes.map{it.model()}))
            val addedStructures=(definition?.data() as? KnowledgeData.MapDefinition)?.structures.orEmpty().map{it.id}
            val order=canonicalGraphOrder(nextNodes.map{it.model()},seed+addedStructures)
            val payload=KnowledgeCodec.encode(KnowledgeData.MapOrder(mapId,order))
            if(existingOrder==null){
                val id=orderId(book,mapId);require(previous.none{it.id==id}){"MAP_ORDER_ID_CONFLICT"}
                updates[id]=KnowledgeRow(id,book,1,payload)
            }else if(existingOrder.removed||!existingOrder.payload.contentEquals(payload)){
                updates[existingOrder.id]=existingOrder.copy(revision=existingOrder.revision+1,payload=payload,removed=false)
            }
        }
        return updates.values.toList()
    }

    /** Caller owns the transaction and top-level receipt. Intermediate graphs are never validated or emitted. */
    internal suspend fun commitGraph(book:String,changes:List<KnowledgeRow>,mainNodes:List<StudyNodeRow>?=null){
        val dao=db.knowledge();val previous=dao.forBook(book);val oldById=previous.associateBy{it.id}
        require(changes.map{it.id}.distinct().size==changes.size)
        changes.forEach{row->
            val old=dao.get(row.id);require(row.notebookId==book&&row.revision in 1 until Long.MAX_VALUE)
            require(row.data() is KnowledgeData.MapDefinition||row.data() is KnowledgeData.MapOccurrence||row.data() is KnowledgeData.MapOrder)
            require(if(old==null)row.revision==1L else old.notebookId==book&&row.revision==old.revision+1&&old.data()::class==row.data()::class)
        }
        val changedIds=changes.map{it.id}.toSet();val finalRows=previous.filter{it.id !in changedIds}+changes
        require(finalRows.size<=2000){"KNOWLEDGE_BUDGET"}
        val oldMain=db.study().nodes(book);val finalMain=mainNodes?:oldMain
        if(mainNodes!=null){
            require(oldMain.all{old->mainNodes.any{it.id==old.id}}){"MAP_NODE_HISTORY_MISSING"}
            val oldNodes=oldMain.associateBy{it.id}
            mainNodes.forEach{n->val old=oldNodes[n.id];require(n.notebookId==book)
                if(old==null)require(db.study().node(n.id)==null){"MAP_NODE_ID_EXISTS"}
                require(old==n||if(old==null)n.revision==1L else n.revision==old.revision+1&&n.cardId==old.cardId)
            }
        }
        validateMaps(finalRows,mapOf(book to finalMain))
        if(mainNodes!=null){
            val oldNodes=oldMain.associateBy{it.id}
            mainNodes.forEach{n->val old=oldNodes[n.id];if(old==null)db.study().addNode(n)else if(old!=n)check(db.study().updateNode(n)==1)}
        }
        changes.forEach{row->
            if(oldById[row.id]==null)dao.insert(row)else check(dao.update(row)==1)
            dao.revision(KnowledgeRevisionRow(row.id,row.revision,book,row.payload,row.removed))
        }
    }
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
            if(c.data is KnowledgeData.MapOrder)require(!c.removed){"MAP_ORDER_REQUIRED"}
            val next=KnowledgeRow(c.id,c.notebookId,c.expectedRevision+1,c.payload,c.removed)
            if(c.data !is KnowledgeData.MapDefinition&&c.data !is KnowledgeData.MapOccurrence&&c.data !is KnowledgeData.MapOrder)validateMaps(records.filter{it.notebookId==c.notebookId&&it.id!=c.id}+next)
            old to next
            }catch(e:IllegalArgumentException){
                throw KnowledgeRejected(when(e.message){
                    "KNOWLEDGE_VERSION_CHANGED","SOURCE_CHANGED","REVIEW_CARD_CHANGED"->KnowledgeRejection.CONFLICT
                    "BOOKMARK_EXISTS","PROPERTY_EXISTS","LINK_EXISTS","MAP_PORTAL_EXISTS"->KnowledgeRejection.DUPLICATE
                    "BOOK_UNAVAILABLE","MAP_PORTAL_SOURCE_UNAVAILABLE","MAP_PORTAL_TARGET_UNAVAILABLE"->KnowledgeRejection.UNAVAILABLE
                    "STUDY_NODE_BUDGET"->KnowledgeRejection.STUDY_NODE_BUDGET
                    "STUDY_NODE_RECORD_BUDGET"->KnowledgeRejection.STUDY_NODE_RECORD_BUDGET
                    "KNOWLEDGE_BUDGET"->KnowledgeRejection.KNOWLEDGE_BUDGET
                    else->KnowledgeRejection.INVALID
                })
            }
            if(c.data is KnowledgeData.MapDefinition||c.data is KnowledgeData.MapOccurrence||c.data is KnowledgeData.MapOrder){
                try{
                    val changes=if(c.data is KnowledgeData.MapOrder)listOf(next)else graphChanges(c.notebookId,listOf(next))
                    commitGraph(c.notebookId,changes)
                }catch(e:IllegalArgumentException){throw KnowledgeRejected(when(e.message){
                    "MAP_ORDER_EXISTS"->KnowledgeRejection.DUPLICATE
                    "STUDY_NODE_BUDGET"->KnowledgeRejection.STUDY_NODE_BUDGET
                    "STUDY_NODE_RECORD_BUDGET"->KnowledgeRejection.STUDY_NODE_RECORD_BUDGET
                    "KNOWLEDGE_BUDGET"->KnowledgeRejection.KNOWLEDGE_BUDGET
                    else->KnowledgeRejection.INVALID
                })}
            }else{
                if(old==null)dao.insert(next)else check(dao.update(next)==1)
                dao.revision(KnowledgeRevisionRow(next.id,next.revision,next.notebookId,next.payload,next.removed))
            }
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
            is KnowledgeData.MapOrder->validateOrderOwnership(book,data,active)
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
    private suspend fun validateOrderOwnership(book:String,order:KnowledgeData.MapOrder,active:Boolean){
        require(db.notes().note(book)!=null)
        val map=order.mapId?.let{id->requireNotNull(db.knowledge().get(id)).also{
            require(it.notebookId==book&&it.data() is KnowledgeData.MapDefinition)
            if(active)require(!it.removed)
        }}
        if(active){
            val nodes=graphNodes(book,order.mapId,db.study().nodes(book),db.knowledge().forBook(book)).map{it.model()}
            require(order.orderedNodeIds.toSet()==nodes.filterNot{it.removed}.map{it.id}.toSet()){"MAP_ORDER_MEMBERSHIP"}
            require(StudyOrganization.canonicalOrder(nodes,order.orderedNodeIds)==order.orderedNodeIds){"MAP_ORDER_HIERARCHY"}
            return
        }
        // An old order proves identities at that revision, not membership in today's graph.
        val structureIds=if(map==null)emptySet()else (listOf(map.payload)+db.knowledge().revisions(map.id).map{it.payload})
            .flatMap{(KnowledgeCodec.decode(it) as? KnowledgeData.MapDefinition)?.structures.orEmpty().map{n->n.id}}.toSet()
        for(id in order.orderedNodeIds){
            if(map==null){require(db.study().node(id)?.notebookId==book);continue}
            if(id in structureIds)continue
            val node=requireNotNull(db.knowledge().get(id));require(node.notebookId==book&&node.data() is KnowledgeData.MapOccurrence)
            val versions=listOf(node.payload)+db.knowledge().revisions(id).filter{it.notebookId==book}.map{it.payload}
            require(versions.any{payload->(KnowledgeCodec.decode(payload) as? KnowledgeData.MapOccurrence)?.let{
                it.mapId==map.id&&db.study().card(it.cardId)?.notebookId==book
            }==true}){"MAP_ORDER_OWNER"}
        }
    }

    private suspend fun validateMaps(rows:List<KnowledgeRow>,mainOverrides:Map<String,List<StudyNodeRow>> = emptyMap()){
        val books=rows.map{it.notebookId}.toSet()+mainOverrides.keys
        for(book in books){
            val own=rows.filter{it.notebookId==book};val maps=own.filter{it.data() is KnowledgeData.MapDefinition}.associateBy{it.id}
            val cards=db.study().cards(book).associateBy{it.id};val main=mainOverrides[book]?:db.study().nodes(book)
            fun card(id:String,removed:Boolean){val c=requireNotNull(cards[id]);require(removed||c.trashedAt==null)}
            main.forEach{require(it.notebookId==book);card(it.cardId,it.removed)}
            StudyGraph.validate(main.map{it.model()})
            val occurrences=own.mapNotNull{r->(r.data() as? KnowledgeData.MapOccurrence)?.let{r to it}}
            occurrences.forEach{(row,node)->
                val map=requireNotNull(maps[node.mapId]);require(row.removed||!map.removed);card(node.cardId,row.removed)
            }
            val byMap=mutableMapOf<String?,List<StudyNode>>(null to main.map{it.model()})
            maps.keys.forEach{id->
                val models=graphNodes(book,id,main,own).map{it.model()};StudyGraph.validate(models);byMap[id]=models
            }
            val orders=own.filterNot{it.removed}.mapNotNull{(it.data() as? KnowledgeData.MapOrder)}
            require(orders.map{it.mapId}.distinct().size==orders.size){"MAP_ORDER_EXISTS"}
            orders.forEach{order->
                order.mapId?.let{require(maps[it]?.removed==false){"MAP_ORDER_UNAVAILABLE"}}
                val nodes=requireNotNull(byMap[order.mapId]);require(order.orderedNodeIds.toSet()==nodes.filterNot{it.removed}.map{it.id}.toSet()){"MAP_ORDER_MEMBERSHIP"}
                require(StudyOrganization.canonicalOrder(nodes,order.orderedNodeIds)==order.orderedNodeIds){"MAP_ORDER_HIERARCHY"}
            }
        }
    }
}
