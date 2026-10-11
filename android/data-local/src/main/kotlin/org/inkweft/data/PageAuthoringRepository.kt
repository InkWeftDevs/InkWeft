// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="canvas_authoring",primaryKeys=["kind","scopeId"],indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class PageAuthoringRow(val kind:String,val scopeId:String,val notebookId:String,val revision:Long,val payload:ByteArray) {
    fun scope()=AuthoringScope(notebookId,AuthoringScopeKind.valueOf(kind),scopeId)
    fun data()=PageAuthoringCodec.decode(payload)
}
@Entity(tableName="authoring_receipts",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class AuthoringReceiptRow(@PrimaryKey val commandId:String,val notebookId:String,val kind:String,val scopeId:String,val digest:String,val revision:Long)
data class PageLayerHeaderRow(val scopeId:String,val revision:Long,val header:ByteArray)
data class AuthoringPageState(val scopeId:String,val revision:Long,val state:PageAuthoring)
@Dao interface PageAuthoringDao {
    @Query("SELECT * FROM canvas_authoring WHERE kind=:kind AND scopeId=:id") suspend fun get(kind:String,id:String):PageAuthoringRow?
    @Query("SELECT * FROM canvas_authoring WHERE kind=:kind AND scopeId=:id") fun observe(kind:String,id:String):kotlinx.coroutines.flow.Flow<PageAuthoringRow?>
    @Query("SELECT * FROM canvas_authoring WHERE notebookId=:book ORDER BY kind,scopeId") fun observeBook(book:String):kotlinx.coroutines.flow.Flow<List<PageAuthoringRow>>
    @Query("SELECT * FROM canvas_authoring WHERE notebookId=:book AND kind='PAGE' AND scopeId IN (:ids) ORDER BY scopeId") suspend fun pages(book:String,ids:List<String>):List<PageAuthoringRow>
    @Query("SELECT scopeId,revision,SUBSTR(payload,1,8192) AS header FROM canvas_authoring WHERE notebookId=:book AND kind='PAGE' ORDER BY scopeId") fun observePageLayerHeaders(book:String):kotlinx.coroutines.flow.Flow<List<PageLayerHeaderRow>>
    @Query("SELECT * FROM canvas_authoring WHERE notebookId=:book ORDER BY kind,scopeId") suspend fun forBook(book:String):List<PageAuthoringRow>
    @Query("SELECT * FROM authoring_receipts WHERE commandId=:id") suspend fun receipt(id:String):AuthoringReceiptRow?
    @Query("SELECT * FROM page_layer_memberships WHERE pageId=:page ORDER BY kind,contentId") suspend fun memberships(page:String):List<PageLayerMembershipRow>
    @Query("SELECT * FROM page_layer_memberships WHERE pageId=:page AND kind=:kind AND contentId IN (:ids)") suspend fun memberships(page:String,kind:String,ids:List<String>):List<PageLayerMembershipRow>
    @Query("SELECT COUNT(*) FROM page_layer_memberships WHERE pageId=:page") suspend fun membershipCount(page:String):Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun assign(row:PageLayerMembershipRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:PageAuthoringRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(row:AuthoringReceiptRow)
}
data class AuthoringSnapshot(val revision:Long,val state:PageAuthoring,val inkRevision:Long=0,val objectRevision:Long=0,val graphFingerprint:String?=null)
data class AuthoringPageExport(val authoring:AuthoringSnapshot,val ink:List<InkStroke>,val objects:List<PageObject>,val paper:PaperStyle,val world:Boolean)
enum class AuthoringFault { BEFORE_RECEIPT, AFTER_TRANSACTION }

/** Configuration and its contents are one local author transaction, not display preferences. */
class PageAuthoringRepository(private val db:NoteDatabase,private val fault:(AuthoringFault)->Unit={}) {
    @Volatile var readObserver:((ReadTiming)->Unit)?=null
    private val pending by lazy{AuthoringPendingStore(checkNotNull(db.checkpointRoot))}
    fun stagePending(value:AuthoringPending)=pending.save(value)
    fun pending(scope:AuthoringScope)=pending.read(scope)
    fun clearPending(scope:AuthoringScope,commandId:String?=null)=pending.remove(scope,commandId)
    private fun digest(scope:AuthoringScope,before:AuthoringSnapshot,payload:ByteArray)=ContentTransfer.hash("$scope:${before.revision}:${before.inkRevision}:${before.objectRevision}:${before.graphFingerprint}:".toByteArray()+payload)
    suspend fun confirmed(value:AuthoringPending):Long?=db.authoring().receipt(value.commandId)?.let{receipt->
        require(receipt.notebookId==value.scope.notebookId&&receipt.kind==value.scope.kind.name&&receipt.scopeId==value.scope.id&&receipt.digest==digest(value.scope,value.before,value.afterPayload())){"AUTHORING_COMMAND_REUSED"};receipt.revision
    }
    fun observe(scope:AuthoringScope)=db.invalidationTracker.createFlow(*(if(scope.kind==AuthoringScopeKind.PAGE)arrayOf("canvas_authoring","page_layer_memberships","ink_pages","page_objects")else arrayOf("canvas_authoring","study_nodes","study_cards","knowledge_records"))).map{read(scope)}.flowOn(Dispatchers.IO)
    fun observeBook(book:String)=db.authoring().observeBook(book)
    fun observePages(book:String,ids:List<String>)=db.invalidationTracker.createFlow("canvas_authoring","page_layer_memberships").map{
        db.withTransaction{db.authoring().pages(book,ids).map{row->AuthoringPageState(row.scopeId,row.revision,PageLayerStore.state(row,db.authoring().memberships(row.scopeId)))}}
    }.flowOn(Dispatchers.IO)
    fun observePageWriteScopes(book:String)=db.authoring().observePageLayerHeaders(book).map{rows->
        rows.associate{row->val layers=PageAuthoringCodec.layerHeader(row.header);row.scopeId to layers.currentId?.let{LayerWriteScope(it,row.revision)}}
    }.flowOn(Dispatchers.IO)
    private suspend fun owner(scope:AuthoringScope,active:Boolean=true) {
        require(db.notes().note(scope.notebookId)!=null)
        if(active)require(db.workspace().get(scope.notebookId)?.trashedAt==null){"AUTHORING_OWNER_UNAVAILABLE"}
        when(scope.kind){
            AuthoringScopeKind.PAGE->{val page=checkNotNull(db.pages().get(scope.id));require(page.notebookId==scope.notebookId&&(!active||page.trashedAt==null))}
            AuthoringScopeKind.MAP->if(scope.id!=scope.notebookId){val row=checkNotNull(db.knowledge().get(scope.id));require(row.notebookId==scope.notebookId&&row.data() is KnowledgeData.MapDefinition&&(!active||!row.removed))}
        }
    }
    private data class PageRows(val authoring:PageAuthoringRow?,val memberships:List<PageLayerMembershipRow>,val strokeIds:List<String>,val objectIds:List<String>,val objectRevision:Long,val inkRevision:Long)
    suspend fun read(scope:AuthoringScope):AuthoringSnapshot {
        if(scope.kind==AuthoringScopeKind.MAP)return db.withTransaction {
            owner(scope,false)
            val row=db.authoring().get(scope.kind.name,scope.id);require(row==null||row.notebookId==scope.notebookId&&row.revision>=0)
            val graph=StudyRepository(db).readGraph(scope.notebookId,scope.id.takeUnless{it==scope.notebookId})
            AuthoringSnapshot(row?.revision?:0,row?.data()?:PageAuthoring(),graphFingerprint=graph.graphFingerprint)
        }
        // Freeze headers, content identities and the author payload together. No
        // ink geometry is needed to validate layer ownership, including history.
        val started=System.nanoTime()
        val frozen=db.withTransaction {
            owner(scope,false)
            val row=db.authoring().get(scope.kind.name,scope.id);require(row==null||row.notebookId==scope.notebookId&&row.revision>=0)
            PageRows(row,db.authoring().memberships(scope.id),db.ink().strokeIds(scope.id),db.objects().ids(scope.id),db.objects().get(scope.id)?.revision?:0,db.ink().page(scope.id)?.revision?:0)
        }
        val frozenAt=System.nanoTime()
        // Observation runs on IO; normal reads release their own transaction
        // before decoding. A surrounding author write still retains its lock.
        val contents=frozen.strokeIds.map{LayerContent(LayerContentKind.INK,it)}+
            frozen.objectIds.map{LayerContent(LayerContentKind.OBJECT,it)}
        require(frozen.authoring!=null||frozen.memberships.isEmpty()){"LAYER_HEADER_MISSING"}
        val state=frozen.authoring?.let{PageLayerStore.state(it,frozen.memberships)}?:PageAuthoring(UserLayers.legacy(contents))
        require(contents.all{state.layers.owns(it)}){"LAYER_CONTENT_UNASSIGNED"}
        val decodedAt=System.nanoTime()
        runCatching{readObserver?.invoke(ReadTiming(ReadKind.AUTHORING,frozenAt-started,decodedAt-frozenAt,contents.size))}
        return AuthoringSnapshot(frozen.authoring?.revision?:0,state,frozen.inkRevision,frozen.objectRevision)
    }
    suspend fun exportPage(id:String):AuthoringPageExport=db.withTransaction {
        val page=checkNotNull(db.pages().get(id));owner(AuthoringScope.page(page.notebookId,id))
        AuthoringPageExport(readPage(id),InkRepository(db).readVisible(id),PageObjectRepository(db).read(id).objects,PaperStyle.entries[page.paper],page.world)
    }
    suspend fun readPage(id:String):AuthoringSnapshot { val page=checkNotNull(db.pages().get(id));return read(AuthoringScope.page(page.notebookId,id)) }
    /** Imports/copies store the same complete state as save(), without inventing an author command. */
    suspend fun storePage(pageId:String,notebookId:String,revision:Long,state:PageAuthoring)=db.withTransaction {
        require(revision>=0);owner(AuthoringScope.page(notebookId,pageId),false)
        PageLayerStore(db).write(pageId,notebookId,revision,state)
    }
    suspend fun save(scope:AuthoringScope,before:AuthoringSnapshot,commandId:String,after:PageAuthoring,frozenPayload:ByteArray?=null):Long {
        UUID.fromString(commandId);val payload=frozenPayload?.copyOf()?:PageAuthoringCodec.encode(after)
        require(PageAuthoringCodec.fingerprint(PageAuthoringCodec.decode(payload))==PageAuthoringCodec.fingerprint(after)){"AUTHORING_PAYLOAD_CHANGED"}
        val digest=digest(scope,before,payload)
        val revision=db.withTransaction {
            db.authoring().receipt(commandId)?.let{require(it.notebookId==scope.notebookId&&it.kind==scope.kind.name&&it.scopeId==scope.id&&it.digest==digest){"AUTHORING_COMMAND_REUSED"};return@withTransaction it.revision}
            owner(scope)
            val current=read(scope)
            require(current.revision==before.revision&&current.inkRevision==before.inkRevision&&current.objectRevision==before.objectRevision&&current.graphFingerprint==before.graphFingerprint){"AUTHORING_CONFLICT"}
            validate(scope,after)
            val required=(current.state.layers.memberships.map{it.content}+current.state.layers.deleted).filter{it.kind!=LayerContentKind.ANNOTATION}
            require(required.all{after.layers.owns(it)}){"LAYER_CONTENT_LOST"}
            // Layer deletion keeps original author bytes; undo restores membership and geometry together.
            val next=before.revision+1;require(next>0)
            if(scope.kind==AuthoringScopeKind.PAGE)PageLayerStore(db).write(scope.id,scope.notebookId,next,after)
            else db.authoring().put(PageAuthoringRow(scope.kind.name,scope.id,scope.notebookId,next,payload))
            if(scope.kind==AuthoringScopeKind.PAGE)db.pages().invalidateSearch(scope.id)
            fault(AuthoringFault.BEFORE_RECEIPT)
            db.authoring().record(AuthoringReceiptRow(commandId,scope.notebookId,scope.kind.name,scope.id,digest,next))
            db.notes().touch(scope.notebookId,System.currentTimeMillis());next
        };fault(AuthoringFault.AFTER_TRANSACTION);return revision
    }
    internal suspend fun validate(scope:AuthoringScope,state:PageAuthoring) {
        PageAuthoringCodec.encode(state)
        if(scope.kind==AuthoringScopeKind.MAP)require(state.blanks.isEmpty())
        val objectRows=if(scope.kind==AuthoringScopeKind.PAGE)PageObjectStore(db).read(scope.id).objects else emptyList()
        state.layers.requireBeautyOwnership(objectRows)
        val objects=objectRows.map{it.id}.toSet()
        val nodes=if(scope.kind==AuthoringScopeKind.MAP){
            val current=StudyRepository(db).readGraph(scope.notebookId,scope.id.takeUnless{it==scope.notebookId}).nodes.map{it.id}.toSet()
            // A removed structural appearance remains provably owned by its versioned definition.
            val historical=if(scope.id==scope.notebookId)emptySet()else db.knowledge().revisions(scope.id)
                .filter{it.notebookId==scope.notebookId}.flatMap{(KnowledgeCodec.decode(it.payload) as? KnowledgeData.MapDefinition)?.structures.orEmpty().map{node->node.id}}.toSet()
            current+historical
        }else emptySet()
        (state.annotations.map{it.target}+state.regions.map{it.target}).forEach{target->when(target.kind){
            AnnotationTargetKind.PAGE->require(target.id==scope.id)
            AnnotationTargetKind.WHITESPACE->require(scope.kind==AuthoringScopeKind.PAGE&&state.blanks.any{it.id==target.id})
            AnnotationTargetKind.PAGE_OBJECT->require(target.id in objects)
            AnnotationTargetKind.MAP_OCCURRENCE->require(target.id in nodes)
        }}
    }
    /** Called inside the ink transaction, after replay lookup and before author rows change. */
    internal suspend fun acceptInk(command:CommitInk):PageAuthoringRow? {
        val owner=checkNotNull(db.pages().get(command.noteId));val store=PageLayerStore(db)
        val affected=when(val m=command.mutation){
            is InkMutation.Add->emptyList()
            is InkMutation.Replace->(m.hidden+m.layerSources).distinct()
            is InkMutation.Swap->m.hide+m.show
            is InkMutation.Visibility->m.ids
            is InkMutation.Cut->m.selection.strokeIds
            is InkMutation.CutVisibility->checkNotNull(db.ink().cut(m.cutId)).strokeIds.split(',')
        }.map{LayerContent(LayerContentKind.INK,it)}
        val added=when(val m=command.mutation){is InkMutation.Add->listOf(m.stroke.id);is InkMutation.Replace->m.added.map{it.id};else->emptyList()}
        val header=store.ensureHeader(command.noteId,owner.notebookId)
        val state=PageLayerStore.state(header,store.selected(command.noteId,affected+added.map{LayerContent(LayerContentKind.INK,it)}))
        state.layers.checkWrite(command.layerScope,header.revision,affected)
        if(added.isEmpty())return null
        val inherited=affected.mapNotNull{state.layers.layer(it)?.id}.distinct()
        val sources=(command.mutation as? InkMutation.Replace)?.layerSources.orEmpty()
        require(inherited.size<=1||sources.size==added.size){"LAYER_EDIT_SEPARATE_LAYERS"}
        val grouped=linkedMapOf<String?,MutableList<LayerContent>>()
        for((index,id) in added.withIndex()){
            val copied=(command.mutation as? InkMutation.Replace)?.hidden?.isEmpty()==true
            val layer=if(copied)state.layers.currentId else sources.getOrNull(index)?.let{state.layers.layer(LayerContent(LayerContentKind.INK,it))?.id}
                ?:inherited.singleOrNull()?:state.layers.currentId
            grouped.getOrPut(layer){mutableListOf()}.add(LayerContent(LayerContentKind.INK,id))
        }
        var assigned=state.layers
        grouped.forEach{(layer,contents)->assigned=assigned.assignNew(contents,layer)}
        for(id in added){val ref=LayerContent(LayerContentKind.INK,id)
            db.authoring().assign(PageLayerMembershipRow(command.noteId,ref.kind.name,ref.id,checkNotNull(assigned.layer(ref)).id))}
        return header
    }
    /** Exact changed object set is checked; a locked member rejects the entire edit. */
    internal suspend fun acceptObjects(pageId:String,objects:List<PageObject>,scope:LayerWriteScope?,prepared:List<PageObjectRecordRow>):PageAuthoringRow? {
        val owner=checkNotNull(db.pages().get(pageId));val store=PageLayerStore(db)
        val old=db.objects().records(pageId).associateBy{it.id};val next=objects.associateBy{it.id}
        val records=prepared.associateBy{it.id}
        val touched=(old.keys+next.keys).filter{id->val a=old[id];val b=records[id];a==null||b==null||a.position!=b.position||a.previewHash!=b.previewHash||!a.payload.contentEquals(b.payload)}
        val changedObjects=touched.mapNotNull(next::get)
        val refs=touched.map{LayerContent(LayerContentKind.OBJECT,it)}+changedObjects.flatMap{it.sourceStrokeIds}.map{LayerContent(LayerContentKind.INK,it)}
        val header=store.ensureHeader(pageId,owner.notebookId);val state=PageLayerStore.state(header,store.selected(pageId,refs))
        require((state.annotations.map{it.target}+state.regions.map{it.target}).none{it.kind==AnnotationTargetKind.PAGE_OBJECT&&it.id in old&&it.id !in next}){"ANNOTATION_DETACH_BEFORE_DELETE"}
        require(touched.filter{it in old}.all{state.layers.owns(LayerContent(LayerContentKind.OBJECT,it))}){"LAYER_CONTENT_UNASSIGNED"}
        val known=touched.filter{state.layers.owns(LayerContent(LayerContentKind.OBJECT,it))}
        state.layers.checkWrite(scope,header.revision,known.map{LayerContent(LayerContentKind.OBJECT,it)})
        var layers=state.layers
        val added=touched.filter{it !in known}
        for(id in added){
            val sources=next[id]?.sourceStrokeIds.orEmpty().map{LayerContent(LayerContentKind.INK,it)}
            layers.requireEditable(sources)
            val inherited=sources.mapNotNull{layers.layer(it)?.id}.distinct();require(inherited.size<=1){"LAYER_EDIT_SEPARATE_LAYERS"}
            layers=layers.assignNew(listOf(LayerContent(LayerContentKind.OBJECT,id)),inherited.singleOrNull()?:layers.currentId)
        }
        layers.requireBeautyOwnership(changedObjects)
        added.forEach{id->val ref=LayerContent(LayerContentKind.OBJECT,id)
            db.authoring().assign(PageLayerMembershipRow(pageId,ref.kind.name,ref.id,checkNotNull(layers.layer(ref)).id))}
        return header.takeIf{added.isNotEmpty()}
    }
}
