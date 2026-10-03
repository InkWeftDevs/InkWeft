// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
internal data class PortalReturn(val mapId:String?,val title:String,val selected:String?,val viewport:MapViewport?,val collapsed:List<String>,val focus:String?) {
    fun saved()=JSONObject().apply{put("map",mapId.orEmpty());put("title",title);put("selected",selected.orEmpty());put("collapsed",JSONArray(collapsed));put("focus",focus.orEmpty());viewport?.let{put("scale",it.scale);put("x",it.x);put("y",it.y)}}.toString()
    companion object {
        fun restore(value:String):PortalReturn?=runCatching{val v=JSONObject(value);val ids=v.getJSONArray("collapsed");PortalReturn(v.getString("map").ifEmpty{null},v.getString("title"),v.getString("selected").ifEmpty{null},if(v.has("scale"))MapViewport(v.getDouble("scale").toFloat(),v.getDouble("x").toFloat(),v.getDouble("y").toFloat())else null,List(ids.length()){ids.getString(it)},v.getString("focus").ifEmpty{null})}.getOrNull()
    }
}
internal data class StudyUi(val cards:List<StudyCardRow> = emptyList(),val nodes:List<StudyNodeRow> = emptyList(),val mainNodes:List<StudyNodeRow> = emptyList(),val loading:Boolean=true,val readFailed:Boolean=false,val busy:Boolean=false,val message:String?=null,val unknown:Boolean=false,val completed:String?=null)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class StudyViewModel(val book:String,val repo:StudyRepository,private val saved:SavedStateHandle):ViewModel(){
    var authorAllowed:()->Boolean={true}
    data class SearchSession(val query:String,val allMaps:Boolean,val initialMap:String?,val viewports:Map<String,MapViewport>,val collapsed:Map<String,List<String>>,val focus:Map<String,String?>,var changedByUser:Boolean=false)
    var searchSession by mutableStateOf<SearchSession?>(null)
    var searchHit by mutableStateOf<MapSearchHit?>(null)
    var viewportRestore by mutableIntStateOf(0)
    fun beginSearch(query:String,all:Boolean,hit:MapSearchHit){
        if(searchSession==null)searchSession=SearchSession(query,all,mapId.value,viewports.toMap(),collapsedByMap.toMap(),focusedByMap.toMap())
        else searchSession=searchSession!!.copy(query=query,allMaps=all)
        locate(hit)
    }
    fun locate(hit:MapSearchHit){require(hit.ref.notebookId==book);selectMap(hit.ref.mapId,false);searchHit=hit}
    fun endSearch(){val before=searchSession;searchSession=null;searchHit=null
        if(before!=null&&!before.changedByUser){viewports.clear();viewports.putAll(before.viewports);collapsedByMap.clear();collapsedByMap.putAll(before.collapsed);focusedByMap.clear();focusedByMap.putAll(before.focus);selectMap(before.initialMap,false);viewportRestore++}
    }
    val mapId=MutableStateFlow<String?>(saved["study.map"])
    val editorState=mutableStateOf<CardEditor?>(null)
    val viewports=mutableMapOf<String,MapViewport>()
    val collapsedByMap=mutableMapOf<String,List<String>>()
    val focusedByMap=mutableMapOf<String,String?>()
    val portalBranches=mutableStateMapOf<String,String>()
    private val restoredTab=(saved.get<Any?>("study.tab") as? Int)?.takeIf{it in 0..2}
    var lastTab by mutableIntStateOf(restoredTab?:0)
        private set
    fun selectTab(value:Int){lastTab=value;saved["study.tab"]=value;compactInitialized=true}
    var captureGeneration by mutableLongStateOf(0L)
    var compactInitialized=restoredTab!=null
    val captureUndo=mutableStateMapOf<String,StudyCommand>()
    val revealByMap=mutableMapOf<String,String>()
    fun undoCapture(){undoCaptureAt(mapId.value)}
    fun undoCaptureAt(target:String?){val c=captureUndo[target?:"main"]?:return;submit(c)}
    val selectedByMap=mutableStateMapOf<String,String?>()
    val portalReturns=mutableStateListOf<PortalReturn>().also{list->saved.get<ArrayList<String>>("study.portalReturns").orEmpty().takeLast(32).mapNotNull(PortalReturn::restore).forEach(list::add)}
    private fun savePortalReturns(){saved["study.portalReturns"]=ArrayList(portalReturns.map{it.saved()})}
    fun selectMap(id:String?,userInitiated:Boolean=true){if(id!=mapId.value&&!ui.value.busy&&!ui.value.unknown){if(userInitiated){searchSession?.changedByUser=true;portalReturns.clear();portalBranches.clear();savePortalReturns()};saved["study.map"]=id;mapId.value=id;state.update{it.copy(loading=true,nodes=emptyList())}}}
    fun openPortal(preview:MapPortalPreview,viewport:MapViewport?,collapsed:List<String>,focus:String?):Boolean {
        if(!preview.canOpen||preview.source.notebookId!=book||preview.target.notebookId!=book||preview.source.mapId!=mapId.value||ui.value.busy||ui.value.unknown)return false
        val key=mapId.value?:"main"
        viewport?.let{viewports[key]=it};collapsedByMap[key]=collapsed;focusedByMap[key]=focus
        portalReturns.add(PortalReturn(mapId.value,preview.sourceMapTitle,selectedByMap[key],viewport,collapsed,focus))
        if(portalReturns.size>32)portalReturns.removeAt(0)
        portalBranches.remove(preview.target.key)
        preview.targetBranchId?.let{branch->
            val targetKey=preview.target.key
            portalBranches[targetKey]=branch
            focusedByMap[targetKey]=branch;selectedByMap[targetKey]=branch
            collapsedByMap[targetKey]=collapsedByMap[targetKey].orEmpty()-branch
            revealByMap[targetKey]=branch
        }
        savePortalReturns();searchSession?.changedByUser=true;searchHit=null;selectMap(preview.target.mapId,false);selectTab(2);viewportRestore++
        return true
    }
    fun returnPortal(nodes:Set<String>):Boolean {
        val back=portalReturns.lastOrNull()?:return false
        if(ui.value.busy||ui.value.unknown)return false
        val key=back.mapId?:"main"
        back.viewport?.let{viewports[key]=it};collapsedByMap[key]=back.collapsed.filter{it in nodes};focusedByMap[key]=back.focus?.takeIf{it in nodes||portalBranches[key]==it};selectedByMap[key]=back.selected?.takeIf{it in nodes}
        portalReturns.removeAt(portalReturns.lastIndex);savePortalReturns();searchHit=null;selectMap(back.mapId,false);selectTab(2);viewportRestore++
        return true
    }
    private var pending:StudyCommand?=restorePending()
    private val state=MutableStateFlow(StudyUi(unknown=pending!=null,message=if(pending!=null)"上次摘要操作待核对，请重试原操作。"else null));val ui=state.asStateFlow()
    private val reload=MutableStateFlow(0)
    fun refresh(){state.update{it.copy(loading=true)};reload.value++}
    private var observation:Job?=null
    private var visibleOwners=0
    fun attach(){visibleOwners++;startObservation()}
    fun detach(){visibleOwners=(visibleOwners-1).coerceAtLeast(0);if(visibleOwners==0){observation?.cancel();observation=null}}
    private fun startObservation(){if(observation?.isActive==true)return;observation=viewModelScope.launch{combine(mapId,reload){m,_->m}.flatMapLatest{m->
        combine(repo.cards(book),repo.nodes(book,m),repo.nodes(book)){c,n,main->Triple(c,n,main)}
            .catch{e->if(e is CancellationException)throw e;state.update{it.copy(loading=false,readFailed=true)}}
    }.collect{(c,n,main)->state.update{it.copy(cards=c,nodes=n,mainNodes=main,loading=false,readFailed=false)}}}}
    init{startObservation()}
    fun submit(c:StudyCommand){require(c.notebookId==book);if(ui.value.busy||pending!=null)return;if(!authorAllowed()){state.update{it.copy(message="当前为阅读模式，请返回书写后编辑。")};return};pending=c;persistPending();execute()}
    fun retry(){if(!ui.value.busy&&pending!=null)execute()}
    private fun execute(){val c=pending?:return;state.update{it.copy(busy=true,message=null,completed=null)}
        viewModelScope.launch{try{when(val result=withContext(Dispatchers.IO){repo.outcome(c)}){
            is StudyOutcome.Success->{if(c.action==StudyAction.CREATE_EXCERPT)captureGeneration++;if(c.action==StudyAction.UNDO_CAPTURE)captureUndo.remove(c.mapId?:"main");if(c.action==StudyAction.CREATE&&c.source!=null){captureGeneration++;c.nodeId?.let{revealByMap[c.mapId?:"main"]=it;selectedByMap[c.mapId?:"main"]=it}};if(c.action==StudyAction.CREATE&&c.source!=null)captureUndo[c.mapId?:"main"]=StudyCommand(UUID.randomUUID().toString(),book,StudyAction.UNDO_CAPTURE,cardId=c.cardId,nodeId=c.nodeId,expectedRevision=1,mapId=c.mapId);pending=null;persistPending();state.update{it.copy(busy=false,unknown=false,completed=result.id)}}
            is StudyOutcome.Rejected->{pending=null;persistPending();state.update{it.copy(busy=false,unknown=false,message="未提交：来源或内容已变化，请核对当前图和分支后重新保存（${result.reason}）。")}}
            StudyOutcome.Unknown->state.update{it.copy(busy=false,unknown=true,message="操作结果待核对。重试核对同一操作，不重复建卡。")}
        }}catch(cancel:CancellationException){state.update{it.copy(busy=false,unknown=true)};throw cancel}}
    }

    fun clear(){state.update{it.copy(message=null,completed=null)}}
    // Lifecycle restoration, not a promise to preserve an unsaved editor after force-stop.
    // Snapshot ink stays in the DB; only the user-confirmed bounded request is in saved state.
    private fun persistPending(){
        val c=pending
        saved.set<ArrayList<String>?>("study.command",c?.let{arrayListOf(it.id,it.notebookId,it.action.name,it.cardId.orEmpty(),it.nodeId.orEmpty(),it.expectedRevision.toString(),it.parentId.orEmpty(),it.title,it.body,it.x.toString(),it.y.toString(),it.expectedGraph,it.mapId.orEmpty())})
        saved["study.preview"]=c?.source?.previewBytes();saved["study.objectRevision"]=c?.source?.objectRevision
        saved.set<ArrayList<String>?>("study.source",c?.source?.let{arrayListOf(it.pageId,it.inkRevision.toString(),it.bounds.left.toString(),it.bounds.top.toString(),it.bounds.right.toString(),it.bounds.bottom.toString(),*it.strokeIds.toTypedArray())})
    }
    private fun restorePending():StudyCommand?{
        val values=saved.get<ArrayList<String>>("study.command")?:return null
        require(values.size in 12..13&&values[1]==book)
        val s=saved.get<ArrayList<String>>("study.source")?.let{require(it.size in 6..262);StudySourceDraft(it[0],it[1].toLong(),CanvasBounds(it[2].toDouble(),it[3].toDouble(),it[4].toDouble(),it[5].toDouble()),it.drop(6),saved["study.preview"],saved["study.objectRevision"])}
        return StudyCommand(values[0],book,StudyAction.valueOf(values[2]),values[3].ifEmpty{null},values[4].ifEmpty{null},values[5].toLong(),values[6].ifEmpty{null},values[7],values[8],values[9].toDouble(),values[10].toDouble(),s,values[11],values.getOrNull(12)?.ifEmpty{null})
    }
    class Factory(val book:String,val repo:StudyRepository):ViewModelProvider.Factory{override fun<T:ViewModel>create(c:Class<T>,extras:CreationExtras):T{require(c.isAssignableFrom(StudyViewModel::class.java));@Suppress("UNCHECKED_CAST")return StudyViewModel(book,repo,extras.createSavedStateHandle()) as T}}
}
internal class StudyPanelSession:ViewModel(){
    val embedInsertion=mutableStateOf<EmbedInsertion?>(null)
    val captureDraft=mutableStateOf<CaptureDraft?>(null)
    val captureTarget=mutableStateOf<Pair<MapRef,String?>?>(null)
    val captureResult=mutableStateOf<Pair<MapRef,String?>?>(null)
    val opened=mutableStateOf(false)
    val source=mutableStateOf<StudySourceDraft?>(null)
    val captureRequest=mutableLongStateOf(0L)
    val card=mutableStateOf<String?>(null)
}
internal data class CardEditor(val card:StudyCardRow?=null,val parent:StudyNodeRow?=null,val source:StudySourceDraft?=null)
