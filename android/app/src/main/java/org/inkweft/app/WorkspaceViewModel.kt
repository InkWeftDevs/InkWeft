// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.*

/** Shelf state never replaces an active text or ink draft. */
class WorkspaceViewModel(app:Application,private val saved:androidx.lifecycle.SavedStateHandle):AndroidViewModel(app){
    val pendingCreate=saved.getStateFlow<ArrayList<String>?>("create.request",null)
    private val cardNavigation=MutableStateFlow<Map<String,String>>(emptyMap());val pendingStudyCardNavigation=cardNavigation.asStateFlow()
    // Only the resolved destination may consume the latest card-opening request.
    fun requestStudyCardNavigation(bookId:String,cardId:String){cardNavigation.value=mapOf(bookId to cardId)}
    fun consumeStudyCardNavigation(bookId:String,cardId:String){cardNavigation.update{if(it[bookId]==cardId)it-bookId else it}}
    var knowledgeReturnRequested=false
        private set
    fun requestKnowledgeReturn():TargetRef?=peekKnowledgeReturn()?.target?.also{knowledgeReturnRequested=true}
    fun finishKnowledgeReturn(){knowledgeReturnRequested=false}
    val knowledgeReturns=saved.getStateFlow<ArrayList<String>>("knowledge.returns",arrayListOf())
    data class KnowledgeReturn(val target:TargetRef,val book:String,val page:String,val viewport:CanvasViewport?)
    fun rememberKnowledgeReturn(target:TargetRef,book:String,page:String){
        val v=liveViews[page]
        val value=listOf(target.kind.name,target.id,book,page,v?.centerX?.toString().orEmpty(),v?.centerY?.toString().orEmpty(),v?.zoom?.toString().orEmpty()).joinToString("|")
        val current=knowledgeReturns.value
        saved["knowledge.returns"]=ArrayList((current+value).takeLast(16))
    }
    fun peekKnowledgeReturn():KnowledgeReturn?=knowledgeReturns.value.lastOrNull()?.let{value->runCatching{
        val f=value.split('|');KnowledgeReturn(TargetRef(TargetKind.valueOf(f[0]),f[1]),f[2],f[3],if(f[4].isBlank())null else CanvasViewport(f[4].toDouble(),f[5].toDouble(),f[6].toDouble()))
    }.getOrNull()}
    fun consumeKnowledgeReturn(){saved["knowledge.returns"]=ArrayList(knowledgeReturns.value.dropLast(1))}
    private val returnViews=MutableStateFlow<Map<String,CanvasViewport>>(emptyMap())
    val pendingReturnViewport=returnViews.asStateFlow()
    fun restoreKnowledgeViewport(location:KnowledgeReturn){location.viewport?.let{liveViews[location.page]=it;returnViews.value=mapOf(location.page to it)}}
    fun consumeReturnViewport(page:String){returnViews.update{it-page}}
    val focusAnchor=MutableStateFlow<KnowledgeData.Anchor?>(null)
    private val repo=(app as InkWeftApplication).workspaceRepository
    private val pages=(app as InkWeftApplication).pages
    private val pageNavigation=MutableStateFlow<Map<String,String>>(emptyMap());val pendingPageNavigation=pageNavigation.asStateFlow()
    fun consumePageNavigation(bookId:String,pageId:String){pageNavigation.update{if(it[bookId]==pageId)it-bookId else it}}
    private val searchState=MutableStateFlow<List<PageSearchHit>>(emptyList());val searchable=searchState.asStateFlow()
    private val mutable=MutableStateFlow<Map<String,WorkspaceRow>>(emptyMap());val entries=mutable.asStateFlow()
    private val counts=MutableStateFlow<Map<String,LibraryInkCount>>(emptyMap());val inkCounts=counts.asStateFlow()
    private val failure=MutableStateFlow<String?>(null);val error=failure.asStateFlow()
    private val working=MutableStateFlow(false);val busy=working.asStateFlow()
    private val viewWrites=Mutex();private val viewGeneration=mutableMapOf<String,Long>()
    private val liveViews=mutableMapOf<String,CanvasViewport>()
    init{
        viewModelScope.launch{try{pages.observeSearch().collect{searchState.value=it}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="检索索引读取失败，不代表没有匹配的笔记。"}}
        viewModelScope.launch{try{repo.observe().collect{rows->mutable.value=rows.associateBy{it.noteId}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="无法读取资料分类，原数据未删除。"}}
        viewModelScope.launch{try{repo.observeInkCounts().collect{rows->counts.value=rows.associateBy{it.noteId}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="缩略统计读取失败，可继续尝试打开笔记。"}}
    }
    fun cachedViewport(id:String)=liveViews[id]
    fun ensure(id:String){viewModelScope.launch{try{val row=withContext(Dispatchers.IO){repo.get(id)};mutable.update{it+(id to row)}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="无法读取页面设置"}}}
    // Await in the caller's scope: closing/recreating the source card cancels publication.
    suspend fun openPageAwait(bookId:String,pageId:String,anchor:KnowledgeData.Anchor?=null,open:()->Unit):Boolean{
        return try{
            withContext(Dispatchers.IO){require(repo.get(bookId).trashedAt==null);pages.select(bookId,pageId)}
            currentCoroutineContext().ensureActive()
            pageNavigation.update{it+(bookId to pageId)}
            if(anchor!=null)focusAnchor.value=anchor
            open();true
        }catch(c:CancellationException){throw c}catch(_:Exception){currentCoroutineContext().ensureActive();failure.value="搜索位置已变化，请从页目录打开。";false}
    }
    fun openSearchPage(bookId:String,pageId:String,open:()->Unit){viewModelScope.launch{openPageAwait(bookId,pageId,open=open)}}
    fun clearError(){failure.value=null}
    internal fun create(title:String,world:Boolean,paper:PaperStyle,cover:NotebookCover=NotebookCover.AUTO,customCover:ByteArray?=null,template:TemplateRef?=null,onCreated:(Note)->Unit){
        if(working.value||pendingCreate.value!=null)return
        saved["create.cover"]=customCover?.copyOf()
        saved["create.template"]=template?.let{arrayListOf(it.hash,it.id)}
        saved["create.request"]=arrayListOf(java.util.UUID.randomUUID().toString(),title,world.toString(),paper.name,cover.key)
        retryCreate(onCreated)
    }
    fun retryCreate(onCreated:(Note)->Unit){
        val request=pendingCreate.value?:return;if(working.value)return;working.value=true
        viewModelScope.launch{try{
            val note=withContext(Dispatchers.IO){val resource=saved.get<ArrayList<String>>("create.template");if(resource!=null)getApplication<InkWeftApplication>().resourcePacks.instantiate(resource[0],resource[1],request[1],request[0],NotebookCover.fromKey(request[4]),saved.get<ByteArray>("create.cover"))else repo.create(request[1],request[2].toBooleanStrict(),PaperStyle.valueOf(request[3]),NotebookCover.fromKey(request[4]),request[0],saved.get<ByteArray>("create.cover"))}
            saved.set<ArrayList<String>?>("create.request",null);saved.set<ByteArray?>("create.cover",null);failure.value=null;onCreated(note)
        }catch(c:CancellationException){throw c}catch(_:IllegalArgumentException){saved.set<ArrayList<String>?>("create.request",null);saved.set<ByteArray?>("create.cover",null);failure.value="未创建：标题、模板或操作身份无效。"}
        catch(_:Exception){failure.value="新建结果待核对，请核对原创建请求，不会重复创建。"}finally{working.value=false}}
    }

    suspend fun cover(row:WorkspaceRow,style:NotebookCover,custom:ByteArray?=null):Boolean=withContext(Dispatchers.IO){repo.changeCover(row.noteId,row.revision,style,custom)}
    fun organize(row:WorkspaceRow,folder:String=row.folder,tags:String=row.tags,favorite:Boolean=row.favorite,trash:Boolean=row.trashedAt!=null){
        viewModelScope.launch{try{if(!withContext(Dispatchers.IO){repo.organize(row.noteId,row.revision,folder,tags,favorite,trash)})failure.value="分类已被其他操作修改，请检查最新状态后重试。"}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="未能确认分类操作，请检查当前状态。笔记内容没有被删除。"}}
    }
    fun pin(row:WorkspaceRow,pinned:Boolean){
        viewModelScope.launch{try{if(!withContext(Dispatchers.IO){repo.setPinned(row.noteId,row.revision,pinned)})failure.value="置顶设置已有变化，请检查最新状态。"}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="置顶结果待核对；请检查列表，重复保存同一状态不会反转。"}}
    }
    fun paper(id:String,style:PaperStyle){viewModelScope.launch{try{withContext(Dispatchers.IO){repo.changePaper(id,style)}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="未能保存纸张设置"}}}
    fun viewport(id:String,v:CanvasViewport){
        liveViews[id]=v;val token=(viewGeneration[id]?:0)+1;viewGeneration[id]=token
        viewModelScope.launch{try{viewWrites.withLock{if(viewGeneration[id]==token)withContext(Dispatchers.IO){repo.saveViewport(id,v)}}}catch(c:CancellationException){throw c}catch(_:Exception){failure.value="视图位置未保存；笔迹保存状态不受影响。"}}
    }
}
