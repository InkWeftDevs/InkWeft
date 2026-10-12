// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
data class AuthoringPageState(val scopeId:String,val revision:Long,val state:PageAuthoring,val inkRevision:Long=0,val objectRevision:Long=0,val inkLayerCounts:Map<String,Long> = emptyMap())
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
data class AuthoringSnapshot(val revision:Long,val state:PageAuthoring,val inkRevision:Long=0,val objectRevision:Long=0,val graphFingerprint:String?=null,val editorProjection:Boolean=false,val layerCounts:Map<String,Long> = emptyMap(),val inkLayerCounts:Map<String,Long> = emptyMap(),val legacy:Boolean=state.legacy,val hasVisibleInk:Boolean=false)
data class AuthoringPageExport(val authoring:AuthoringSnapshot,val ink:List<InkStroke>,val objects:List<PageObject>,val paper:PaperStyle,val world:Boolean)
data class PageRecognitionInput(val authoring:AuthoringSnapshot,val strokes:List<HandwritingStrokeBounds>,val objects:List<PageObject>)
enum class AuthoringFault { BEFORE_RECEIPT, AFTER_TRANSACTION }

/** Configuration and its contents are one local author transaction, not display preferences. */
class PageAuthoringRepository(private val db:NoteDatabase,private val fault:(AuthoringFault)->Unit={}) {
    @Volatile var readObserver:((ReadTiming)->Unit)?=null
    private val pending by lazy{AuthoringPendingStore(checkNotNull(db.checkpointRoot))}
    fun stagePending(value:AuthoringPending)=pending.save(value)
    fun pending(scope:AuthoringScope)=pending.read(scope)
    fun clearPending(scope:AuthoringScope,commandId:String?=null)=pending.remove(scope,commandId)
    private fun digest(scope:AuthoringScope,before:AuthoringSnapshot,payload:ByteArray,edit:LayerOwnershipEdit?=null)=ContentTransfer.hash("$scope:${before.revision}:${before.inkRevision}:${before.objectRevision}:${before.graphFingerprint}:${if(before.editorProjection)"editor:$edit:" else ""}".toByteArray()+payload)
    suspend fun confirmed(value:AuthoringPending):Long?=db.authoring().receipt(value.commandId)?.let{receipt->
        require(receipt.notebookId==value.scope.notebookId&&receipt.kind==value.scope.kind.name&&receipt.scopeId==value.scope.id&&receipt.digest==digest(value.scope,value.before,value.afterPayload(),value.ownershipEdit)){"AUTHORING_COMMAND_REUSED"};receipt.revision
    }
    fun observe(scope:AuthoringScope)=db.invalidationTracker.createFlow(*(if(scope.kind==AuthoringScopeKind.PAGE)arrayOf("canvas_authoring","page_layer_memberships","ink_pages","page_objects")else arrayOf("canvas_authoring","study_nodes","study_cards","knowledge_records"))).map{read(scope)}.flowOn(Dispatchers.IO)
    /** Only the editable window and bounded object/source references are retained by the UI. */
    fun observeEditor(scope:AuthoringScope,contents:Collection<LayerContent>)=db.invalidationTracker.createFlow("canvas_authoring","page_layer_memberships","ink_pages","page_objects").map{readEditor(scope,contents)}.flowOn(Dispatchers.IO)
    suspend fun readEditor(scope:AuthoringScope,contents:Collection<LayerContent> = emptyList()):AuthoringSnapshot {
        if(scope.kind!=AuthoringScopeKind.PAGE)return read(scope)
        val started=System.nanoTime()
        val result=db.withTransaction {
            owner(scope,false)
            val header=db.authoring().get("PAGE",scope.id)
            require(header==null||header.notebookId==scope.notebookId&&header.revision>=0)
            val objects=PageObjectStore(db).read(scope.id).objects
            val refs=(contents+objects.map{LayerContent(LayerContentKind.OBJECT,it.id)}+objects.flatMap{it.sourceStrokeIds}.map{LayerContent(LayerContentKind.INK,it)}).distinct()
            val selected=PageLayerStore(db).selected(scope.id,refs)
            val sql=db.openHelper.readableDatabase
            // A legacy blank header is projected without writing from a read-only/Shadow preview.
            val storedInk=ArrayList<LayerContent>()
            val assigned=selected.filter{it.kind=="INK"}.map{it.contentId}.toHashSet()
            val missing=refs.filter{it.kind==LayerContentKind.INK&&it.id !in assigned}
            for(batch in missing.chunked(500))if(batch.isNotEmpty())sql.query("SELECT id FROM ink_strokes WHERE noteId=? AND id IN (${batch.joinToString(","){"?"}})",
                (listOf(scope.id)+batch.map{it.id}).toTypedArray()).use{c->while(c.moveToNext())storedInk.add(LayerContent(LayerContentKind.INK,c.getString(0)))}
            val complete=header?.let{PageLayerStore.state(it,selected)}
            val state=complete?:PageAuthoring(UserLayers.legacy(storedInk+objects.map{LayerContent(LayerContentKind.OBJECT,it.id)}))
            require(header!=null||selected.isEmpty()){"LAYER_HEADER_MISSING"}
            require(storedInk.all{state.layers.owns(it)}&&refs.filter{it.kind==LayerContentKind.OBJECT}.all{state.layers.owns(it)}){"LAYER_CONTENT_UNASSIGNED"}
            val counts=if(header==null)mutableMapOf(UserLayers.DEFAULT_ID to (InkTotals.read(sql,scope.id).strokeCount.toLong()+objects.size))
                else PageLayerEditorStore(db).counts(scope.id).toMutableMap()
            state.layers.memberships.filter{it.content.kind==LayerContentKind.ANNOTATION}.forEach{counts[it.layerId]=(counts[it.layerId]?:0)+1}
            val inkCounts=if(header==null)mapOf(UserLayers.DEFAULT_ID to (counts.getValue(UserLayers.DEFAULT_ID)-objects.size))else PageLayerEditorStore(db).counts(scope.id,true)
            val legacy=state.legacy&&!sql.query("SELECT 1 FROM page_layer_memberships WHERE pageId=? AND layerId IS NULL LIMIT 1",arrayOf(scope.id)).use{it.moveToFirst()}
            AuthoringSnapshot(header?.revision?:0,state,db.ink().page(scope.id)?.revision?:0,db.objects().get(scope.id)?.revision?:0,editorProjection=true,layerCounts=counts,inkLayerCounts=inkCounts,legacy=legacy,hasVisibleInk=sql.query("SELECT 1 FROM ink_strokes WHERE noteId=? AND visible=1 LIMIT 1",arrayOf(scope.id)).use{it.moveToFirst()})
        }
        runCatching{readObserver?.invoke(ReadTiming(ReadKind.AUTHORING,System.nanoTime()-started,0,loadedContents(result.state)))}
        return result
    }
    private fun loadedContents(state:PageAuthoring)=state.layers.memberships.count{it.content.kind!=LayerContentKind.ANNOTATION}+state.layers.deleted.count{it.kind!=LayerContentKind.ANNOTATION}
    suspend fun readPageEditor(id:String,contents:Collection<LayerContent> = emptyList()):AuthoringSnapshot {
        val page=checkNotNull(db.pages().get(id));return readEditor(AuthoringScope.page(page.notebookId,id),contents)
    }
    /** A renderer resolves only its current geometry batch at the requested author revision. */
    suspend fun visibleLayerInk(page:String,inkRevision:Long,authoringRevision:Long,layer:String?,strokes:List<InkStroke>):List<InkStroke> = db.withTransaction {
        val owned=checkNotNull(db.pages().get(page));val state=read(AuthoringScope.page(owned.notebookId,page),strokes.map{LayerContent(LayerContentKind.INK,it.id)})
        check(state.revision==authoringRevision&&state.inkRevision==inkRevision){"EXPORT_REVISION_CHANGED"}
        strokes.filter{val ref=LayerContent(LayerContentKind.INK,it.id);state.state.layers.visible(ref)&&(layer==null||state.state.layers.layer(ref)?.id==layer)}
    }
    fun observeBook(book:String)=db.authoring().observeBook(book)
    fun observePages(book:String,ids:List<String>,contents:Map<String,List<LayerContent>> = emptyMap())=db.invalidationTracker.createFlow("canvas_authoring","page_layer_memberships","ink_pages","page_objects").map{
        db.withTransaction{ids.map{id->val state=readEditor(AuthoringScope.page(book,id),contents[id].orEmpty());AuthoringPageState(id,state.revision,state.state,state.inkRevision,state.objectRevision,state.inkLayerCounts)}}
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
    suspend fun read(scope:AuthoringScope,contents:Collection<LayerContent>?=null):AuthoringSnapshot {
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
            val indexedOwnership=contents==null&&row!=null&&PageAuthoringCodec.isHeader(row.payload)
            if(indexedOwnership)require(!db.openHelper.readableDatabase.query(
                "SELECT 1 FROM ink_strokes s WHERE s.noteId=? AND NOT EXISTS (SELECT 1 FROM page_layer_memberships m WHERE m.pageId=s.noteId AND m.kind='INK' AND m.contentId=s.id) LIMIT 1",arrayOf(scope.id)
            ).use{it.moveToFirst()}){"LAYER_CONTENT_UNASSIGNED"}
            PageRows(row,if(contents==null)db.authoring().memberships(scope.id)else PageLayerStore(db).selected(scope.id,contents),
                if(indexedOwnership)emptyList()else contents?.filter{it.kind==LayerContentKind.INK}?.map{it.id}?:db.ink().strokeIds(scope.id),
                contents?.filter{it.kind==LayerContentKind.OBJECT}?.map{it.id}?:db.objects().ids(scope.id),db.objects().get(scope.id)?.revision?:0,db.ink().page(scope.id)?.revision?:0)
        }
        val frozenAt=System.nanoTime()
        // Observation runs on IO; normal reads release their own transaction
        // before decoding. A surrounding author write still retains its lock.
        val ownedContents=frozen.strokeIds.map{LayerContent(LayerContentKind.INK,it)}+
            frozen.objectIds.map{LayerContent(LayerContentKind.OBJECT,it)}
        require(frozen.authoring!=null||frozen.memberships.isEmpty()){"LAYER_HEADER_MISSING"}
        val state=frozen.authoring?.let{PageLayerStore.state(it,frozen.memberships)}?:PageAuthoring(UserLayers.legacy(ownedContents))
        require(ownedContents.all{state.layers.owns(it)}){"LAYER_CONTENT_UNASSIGNED"}
        val decodedAt=System.nanoTime()
        runCatching{readObserver?.invoke(ReadTiming(ReadKind.AUTHORING,frozenAt-started,decodedAt-frozenAt,loadedContents(state)))}
        return AuthoringSnapshot(frozen.authoring?.revision?:0,state,frozen.inkRevision,frozen.objectRevision)
    }
    suspend fun exportPage(id:String):AuthoringPageExport=db.withTransaction {
        val page=checkNotNull(db.pages().get(id));owner(AuthoringScope.page(page.notebookId,id))
        AuthoringPageExport(readPage(id),InkRepository(db).readVisible(id),PageObjectRepository(db).read(id).objects,PaperStyle.entries[page.paper],page.world)
    }
    /** Freeze bounded configuration/object content; ink is visited separately at the same heads. */
    suspend fun exportHeader(id:String):AuthoringPageExport=db.withTransaction {
        val page=checkNotNull(db.pages().get(id));val scope=AuthoringScope.page(page.notebookId,id);owner(scope)
        val objects=PageObjectRepository(db).read(id).objects
        AuthoringPageExport(read(scope,objects.map{LayerContent(LayerContentKind.OBJECT,it.id)}),emptyList(),objects,PaperStyle.entries[page.paper],page.world)
    }
    /** Group the complete supported input using identities and bounds; geometry stays batched.
     * The existing recognition stroke/line limits apply without adding a page point limit. */
    suspend fun readForRecognition(id:String):PageRecognitionInput {
        val header=exportHeader(id);val layers=header.authoring.state.layers
        val objects=header.objects.filter{layers.visible(LayerContent(LayerContentKind.OBJECT,it.id))}
        val suppressed=objects.flatMap{it.sourceStrokeIds}.toSet()
        val ink=ArrayList<HandwritingStrokeBounds>()
        InkRepository(db).visitVisible(id,header.authoring.inkRevision){batch->
            for(stroke in visibleExportInk(id,header.authoring,batch))if(stroke.id !in suppressed&&stroke.pen!=InkPen.HIGHLIGHTER){
                require(ink.size<InkLimits.MAX_MUTATION_STROKES){"PAGE_RECOGNITION_BUDGET"}
                ink.add(HandwritingStrokeBounds(stroke.id,stroke.bounds()))
            }
        }
        checkExportHeads(id,header.authoring)
        return PageRecognitionInput(header.authoring,ink,objects)
    }
    suspend fun visibleExportInk(id:String,expected:AuthoringSnapshot,strokes:List<InkStroke>,layer:String?=null):List<InkStroke> = db.withTransaction {
        val page=checkNotNull(db.pages().get(id));val state=read(AuthoringScope.page(page.notebookId,id),strokes.map{LayerContent(LayerContentKind.INK,it.id)})
        check(state.revision==expected.revision&&state.inkRevision==expected.inkRevision&&state.objectRevision==expected.objectRevision){"EXPORT_REVISION_CHANGED"}
        strokes.filter{val ref=LayerContent(LayerContentKind.INK,it.id);state.state.layers.visible(ref)&&(layer==null||state.state.layers.layer(ref)?.id==layer)}
    }
    suspend fun checkExportHeads(id:String,expected:AuthoringSnapshot)=db.withTransaction {
        val page=checkNotNull(db.pages().get(id));val current=read(AuthoringScope.page(page.notebookId,id),emptyList())
        check(current.revision==expected.revision&&current.inkRevision==expected.inkRevision&&current.objectRevision==expected.objectRevision){"EXPORT_REVISION_CHANGED"}
    }
    suspend fun readPage(id:String):AuthoringSnapshot { val page=checkNotNull(db.pages().get(id));return read(AuthoringScope.page(page.notebookId,id)) }
    /** Imports/copies store the same complete state as save(), without inventing an author command. */
    suspend fun storePage(pageId:String,notebookId:String,revision:Long,state:PageAuthoring)=db.withTransaction {
        require(revision>=0);owner(AuthoringScope.page(notebookId,pageId),false)
        PageLayerStore(db).write(pageId,notebookId,revision,state)
    }
    suspend fun save(scope:AuthoringScope,before:AuthoringSnapshot,commandId:String,after:PageAuthoring,frozenPayload:ByteArray?=null,ownershipEdit:LayerOwnershipEdit?=null,retainedHistory:List<String> = emptyList()):Long {
        UUID.fromString(commandId);val payload=frozenPayload?.copyOf()?:PageAuthoringCodec.encode(after)
        require(PageAuthoringCodec.fingerprint(PageAuthoringCodec.decode(payload))==PageAuthoringCodec.fingerprint(after)){"AUTHORING_PAYLOAD_CHANGED"}
        val digest=digest(scope,before,payload,ownershipEdit)
        val revision=db.withTransaction {
            db.authoring().receipt(commandId)?.let{require(it.notebookId==scope.notebookId&&it.kind==scope.kind.name&&it.scopeId==scope.id&&it.digest==digest){"AUTHORING_COMMAND_REUSED"};return@withTransaction it.revision}
            owner(scope)
            if(before.editorProjection){require(scope.kind==AuthoringScopeKind.PAGE);PageLayerStore(db).ensureHeader(scope.id,scope.notebookId)}
            val current=if(before.editorProjection)readEditor(scope,before.state.layers.memberships.map{it.content}.filter{it.kind!=LayerContentKind.ANNOTATION}+before.state.layers.deleted.filter{it.kind!=LayerContentKind.ANNOTATION})else read(scope)
            require(current.revision==before.revision&&current.inkRevision==before.inkRevision&&current.objectRevision==before.objectRevision&&current.graphFingerprint==before.graphFingerprint){"AUTHORING_CONFLICT"}
            // Layer deletion keeps original author bytes; undo restores membership and geometry together.
            val next=before.revision+1;require(next>0)
            if(before.editorProjection){
                require(scope.kind==AuthoringScopeKind.PAGE)
                PageLayerEditorStore(db).write(scope.id,commandId,current.state,after,ownershipEdit,retainedHistory)
                db.authoring().put(PageAuthoringRow("PAGE",scope.id,scope.notebookId,next,PageAuthoringCodec.encodeHeader(after)))
                // Validate the actual SQL result, including retained beauty ink outside the viewport.
                validate(scope,readEditor(scope).state)
            }else{
                require(ownershipEdit==null)
                validate(scope,after)
                val required=(current.state.layers.memberships.map{it.content}+current.state.layers.deleted).filter{it.kind!=LayerContentKind.ANNOTATION}
                require(required.all{after.layers.owns(it)}){"LAYER_CONTENT_LOST"}
                if(scope.kind==AuthoringScopeKind.PAGE)PageLayerStore(db).write(scope.id,scope.notebookId,next,after)
                else db.authoring().put(PageAuthoringRow(scope.kind.name,scope.id,scope.notebookId,next,payload))
            }
            if(scope.kind==AuthoringScopeKind.PAGE)db.pages().invalidateSearch(scope.id)
            fault(AuthoringFault.BEFORE_RECEIPT)
            db.authoring().record(AuthoringReceiptRow(commandId,scope.notebookId,scope.kind.name,scope.id,digest,next))
            db.notes().touch(scope.notebookId,System.currentTimeMillis());next
        };fault(AuthoringFault.AFTER_TRANSACTION);return revision
    }
    /** Archive validation checks every ownership row with a cursor; only object/beauty closure is materialized. */
    internal suspend fun validateStored(row:PageAuthoringRow) {
        val scope=row.scope();owner(scope,false)
        if(scope.kind!=AuthoringScopeKind.PAGE){validate(scope,read(scope).state);return}
        val state=readEditor(scope).state;validate(scope,state)
        val layers=state.layers.layers.map{it.id}.toSet();val sql=db.openHelper.readableDatabase;val context=currentCoroutineContext()
        sql.query("SELECT kind,contentId,layerId FROM page_layer_memberships WHERE pageId=? ORDER BY kind,contentId",arrayOf(scope.id)).use{c->
            while(c.moveToNext()){
                context.ensureActive()
                val kind=LayerContentKind.valueOf(c.getString(0));require(kind!=LayerContentKind.ANNOTATION)
                UUID.fromString(c.getString(1));require(c.isNull(2)||c.getString(2) in layers){"LAYER_CONTENT_UNASSIGNED"}
            }
        }
        require(!sql.query("SELECT 1 FROM ink_strokes s WHERE s.noteId=? AND NOT EXISTS (SELECT 1 FROM page_layer_memberships m WHERE m.pageId=s.noteId AND m.kind='INK' AND m.contentId=s.id) LIMIT 1",arrayOf(scope.id)).use{it.moveToFirst()}){"LAYER_CONTENT_UNASSIGNED"}
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
