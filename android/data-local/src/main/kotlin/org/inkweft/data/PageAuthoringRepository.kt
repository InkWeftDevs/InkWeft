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
@Dao interface PageAuthoringDao {
    @Query("SELECT * FROM canvas_authoring WHERE kind=:kind AND scopeId=:id") suspend fun get(kind:String,id:String):PageAuthoringRow?
    @Query("SELECT * FROM canvas_authoring WHERE kind=:kind AND scopeId=:id") fun observe(kind:String,id:String):kotlinx.coroutines.flow.Flow<PageAuthoringRow?>
    @Query("SELECT * FROM canvas_authoring WHERE notebookId=:book ORDER BY kind,scopeId") fun observeBook(book:String):kotlinx.coroutines.flow.Flow<List<PageAuthoringRow>>
    @Query("SELECT * FROM canvas_authoring WHERE notebookId=:book ORDER BY kind,scopeId") suspend fun forBook(book:String):List<PageAuthoringRow>
    @Query("SELECT * FROM authoring_receipts WHERE commandId=:id") suspend fun receipt(id:String):AuthoringReceiptRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:PageAuthoringRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(row:AuthoringReceiptRow)
}
data class AuthoringSnapshot(val revision:Long,val state:PageAuthoring,val inkRevision:Long=0,val objectRevision:Long=0,val graphFingerprint:String?=null)
data class AuthoringPageExport(val authoring:AuthoringSnapshot,val ink:List<InkStroke>,val objects:List<PageObject>,val paper:PaperStyle,val world:Boolean)
enum class AuthoringFault { BEFORE_RECEIPT, AFTER_TRANSACTION }

/** Configuration and its contents are one local author transaction, not display preferences. */
class PageAuthoringRepository(private val db:NoteDatabase,private val fault:(AuthoringFault)->Unit={}) {
    private val pending by lazy{AuthoringPendingStore(checkNotNull(db.checkpointRoot))}
    fun stagePending(value:AuthoringPending)=pending.save(value)
    fun pending(scope:AuthoringScope)=pending.read(scope)
    fun clearPending(scope:AuthoringScope,commandId:String?=null)=pending.remove(scope,commandId)
    private fun digest(scope:AuthoringScope,before:AuthoringSnapshot,payload:ByteArray)=ContentTransfer.hash("$scope:${before.revision}:${before.inkRevision}:${before.objectRevision}:${before.graphFingerprint}:".toByteArray()+payload)
    suspend fun confirmed(value:AuthoringPending):Long?=db.authoring().receipt(value.commandId)?.let{receipt->
        require(receipt.notebookId==value.scope.notebookId&&receipt.kind==value.scope.kind.name&&receipt.scopeId==value.scope.id&&receipt.digest==digest(value.scope,value.before,value.afterPayload())){"AUTHORING_COMMAND_REUSED"};receipt.revision
    }
    fun observe(scope:AuthoringScope)=db.invalidationTracker.createFlow(*(if(scope.kind==AuthoringScopeKind.PAGE)arrayOf("canvas_authoring","ink_pages","page_objects")else arrayOf("canvas_authoring","study_nodes","study_cards","knowledge_records"))).map{read(scope)}.flowOn(Dispatchers.IO)
    fun observeBook(book:String)=db.authoring().observeBook(book)
    private suspend fun owner(scope:AuthoringScope,active:Boolean=true) {
        require(db.notes().note(scope.notebookId)!=null)
        if(active)require(db.workspace().get(scope.notebookId)?.trashedAt==null){"AUTHORING_OWNER_UNAVAILABLE"}
        when(scope.kind){
            AuthoringScopeKind.PAGE->{val page=checkNotNull(db.pages().get(scope.id));require(page.notebookId==scope.notebookId&&(!active||page.trashedAt==null))}
            AuthoringScopeKind.MAP->if(scope.id!=scope.notebookId){val row=checkNotNull(db.knowledge().get(scope.id));require(row.notebookId==scope.notebookId&&row.data() is KnowledgeData.MapDefinition&&(!active||!row.removed))}
        }
    }
    private data class PageRows(val authoring:PageAuthoringRow?,val strokeIds:List<String>,val objects:PageObjectRow?,val inkRevision:Long)
    suspend fun read(scope:AuthoringScope):AuthoringSnapshot {
        if(scope.kind==AuthoringScopeKind.MAP)return db.withTransaction {
            owner(scope,false)
            val row=db.authoring().get(scope.kind.name,scope.id);require(row==null||row.notebookId==scope.notebookId&&row.revision>=0)
            val graph=StudyRepository(db).readGraph(scope.notebookId,scope.id.takeUnless{it==scope.notebookId})
            AuthoringSnapshot(row?.revision?:0,row?.data()?:PageAuthoring(),graphFingerprint=graph.graphFingerprint)
        }
        // Freeze headers, identities and the two author payloads together. No
        // ink geometry is needed to validate layer ownership, including history.
        val frozen=db.withTransaction {
            owner(scope,false)
            val row=db.authoring().get(scope.kind.name,scope.id);require(row==null||row.notebookId==scope.notebookId&&row.revision>=0)
            PageRows(row,db.ink().strokeIds(scope.id),db.objects().get(scope.id),db.ink().page(scope.id)?.revision?:0)
        }
        // Observation runs on IO; normal reads release their own transaction
        // before decoding. A surrounding author write still retains its lock.
        val contents=frozen.strokeIds.map{LayerContent(LayerContentKind.INK,it)}+
            frozen.objects?.let{PageObjectCodec.decode(it.payload)}.orEmpty().map{LayerContent(LayerContentKind.OBJECT,it.id)}
        val state=frozen.authoring?.data()?:PageAuthoring(UserLayers.legacy(contents))
        require(contents.all{state.layers.owns(it)}){"LAYER_CONTENT_UNASSIGNED"}
        return AuthoringSnapshot(frozen.authoring?.revision?:0,state,frozen.inkRevision,frozen.objects?.revision?:0)
    }
    suspend fun exportPage(id:String):AuthoringPageExport=db.withTransaction {
        val page=checkNotNull(db.pages().get(id));owner(AuthoringScope.page(page.notebookId,id))
        AuthoringPageExport(readPage(id),InkSession(InkRepository(db).read(id)).visibleDraft(),PageObjectRepository(db).read(id).objects,PaperStyle.entries[page.paper],page.world)
    }
    suspend fun readPage(id:String):AuthoringSnapshot { val page=checkNotNull(db.pages().get(id));return read(AuthoringScope.page(page.notebookId,id)) }
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
            db.authoring().put(PageAuthoringRow(scope.kind.name,scope.id,scope.notebookId,next,payload))
            if(scope.kind==AuthoringScopeKind.PAGE)db.pages().invalidateSearch(scope.id)
            fault(AuthoringFault.BEFORE_RECEIPT)
            db.authoring().record(AuthoringReceiptRow(commandId,scope.notebookId,scope.kind.name,scope.id,digest,next))
            db.notes().touch(scope.notebookId,System.currentTimeMillis());next
        };fault(AuthoringFault.AFTER_TRANSACTION);return revision
    }
    internal suspend fun validate(scope:AuthoringScope,state:PageAuthoring) {
        PageAuthoringCodec.encode(state)
        if(scope.kind==AuthoringScopeKind.MAP)require(state.blanks.isEmpty())
        val objectRows=if(scope.kind==AuthoringScopeKind.PAGE)db.objects().get(scope.id)?.let{PageObjectCodec.decode(it.payload)}.orEmpty()else emptyList()
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
        val owner=checkNotNull(db.pages().get(command.noteId));val snapshot=readPage(command.noteId)
        val affected=when(val m=command.mutation){
            is InkMutation.Add->emptyList()
            is InkMutation.Replace->(m.hidden+m.layerSources).distinct()
            is InkMutation.Swap->m.hide+m.show
            is InkMutation.Visibility->m.ids
            is InkMutation.Cut->m.selection.strokeIds
            is InkMutation.CutVisibility->checkNotNull(db.ink().cut(m.cutId)).strokeIds.split(',')
        }.map{LayerContent(LayerContentKind.INK,it)}
        snapshot.state.layers.checkWrite(command.layerScope,snapshot.revision,affected)
        val added=when(val m=command.mutation){is InkMutation.Add->listOf(m.stroke.id);is InkMutation.Replace->m.added.map{it.id};else->emptyList()}
        if(added.isEmpty())return null
        val inherited=affected.mapNotNull{snapshot.state.layers.layer(it)?.id}.distinct()
        val sources=(command.mutation as? InkMutation.Replace)?.layerSources.orEmpty()
        require(inherited.size<=1||sources.size==added.size){"LAYER_EDIT_SEPARATE_LAYERS"}
        val grouped=linkedMapOf<String?,MutableList<LayerContent>>()
        for((index,id) in added.withIndex()){
            val copied=(command.mutation as? InkMutation.Replace)?.hidden?.isEmpty()==true
            val layer=if(copied)snapshot.state.layers.currentId else sources.getOrNull(index)?.let{snapshot.state.layers.layer(LayerContent(LayerContentKind.INK,it))?.id}
                ?:inherited.singleOrNull()?:snapshot.state.layers.currentId
            grouped.getOrPut(layer){mutableListOf()}.add(LayerContent(LayerContentKind.INK,id))
        }
        var assigned=snapshot.state.layers
        grouped.forEach{(layer,contents)->assigned=assigned.assignNew(contents,layer)}
        val next=snapshot.state.withLayers(assigned)
        return PageAuthoringRow(AuthoringScopeKind.PAGE.name,command.noteId,owner.notebookId,snapshot.revision,PageAuthoringCodec.encode(next))
    }
    /** Exact changed object set is checked; a locked member rejects the entire edit. */
    internal suspend fun acceptObjects(pageId:String,objects:List<PageObject>,scope:LayerWriteScope?):PageAuthoringRow? {
        val owner=checkNotNull(db.pages().get(pageId));val snapshot=readPage(pageId)
        val before=db.objects().get(pageId)?.let{PageObjectCodec.decode(it.payload)}.orEmpty()
        val old=before.associateBy{it.id};val next=objects.associateBy{it.id}
        val touched=(old.keys+next.keys).filter{old[it]!=next[it]}
        require((snapshot.state.annotations.map{it.target}+snapshot.state.regions.map{it.target}).none{it.kind==AnnotationTargetKind.PAGE_OBJECT&&it.id in old&&it.id !in next}){"ANNOTATION_DETACH_BEFORE_DELETE"}
        val known=touched.filter{snapshot.state.layers.layer(LayerContent(LayerContentKind.OBJECT,it))!=null||LayerContent(LayerContentKind.OBJECT,it) in snapshot.state.layers.deleted}
        snapshot.state.layers.checkWrite(scope,snapshot.revision,known.map{LayerContent(LayerContentKind.OBJECT,it)})
        var layers=snapshot.state.layers
        for(id in touched.filter{it !in known}){
            val sources=next[id]?.sourceStrokeIds.orEmpty().map{LayerContent(LayerContentKind.INK,it)}
            layers.requireEditable(sources)
            val inherited=sources.mapNotNull{layers.layer(it)?.id}.distinct();require(inherited.size<=1){"LAYER_EDIT_SEPARATE_LAYERS"}
            layers=layers.assignNew(listOf(LayerContent(LayerContentKind.OBJECT,id)),inherited.singleOrNull()?:layers.currentId)
        }
        layers.requireBeautyOwnership(objects)
        return if(layers===snapshot.state.layers)null else PageAuthoringRow(AuthoringScopeKind.PAGE.name,pageId,owner.notebookId,snapshot.revision,PageAuthoringCodec.encode(snapshot.state.withLayers(layers)))
    }
}
