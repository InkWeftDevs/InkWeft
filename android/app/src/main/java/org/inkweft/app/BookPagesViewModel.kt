// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

data class BookPagesUi(
    val pages: List<NotebookPageRow> = emptyList(), val selectedId: String? = null,
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val insertionUnknown: Boolean = false, val actionUnknown:Boolean=false,
    val recycled:List<NotebookPageRow> = emptyList(),
)

class BookPagesViewModel(
    private val bookId: String, private val repo: NotebookPages,
    private val workspace: WorkspaceRepository, private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private var pending: InsertPages? = restorePending()
    private var pendingEdit:EditPage?=savedState.get<ArrayList<String>>("page.edit")?.let{EditPage.fromFields(it).also{c->require(c.notebookId==bookId)}}
    private val mutable = MutableStateFlow(BookPagesUi(insertionUnknown = pending != null,actionUnknown=pendingEdit!=null))
    val ui = mutable.asStateFlow()
    private var requestedSelection: String? = null
    private var selectGeneration = 0L
    private val selectionLock = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { repo.ensureFirst(bookId); workspace.get(bookId).selectedPageId }
                repo.observeAll(bookId).collect { all ->
                    val rows=all.filter{it.trashedAt==null}
                    val recycled=all.filter{it.trashedAt!=null}.sortedByDescending{it.trashedAt}
                    val desired = requestedSelection
                    mutable.update { old -> old.copy(pages = rows, recycled=recycled, loading = false,
                        selectedId = desired?.takeIf { id -> rows.any { it.id == id } }
                            ?: old.selectedId?.takeIf { id -> rows.any { it.id == id } }
                            ?: selected.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id) }
                    if (desired != null && rows.any { it.id == desired }) requestedSelection = null
                }
            } catch (c: CancellationException) { throw c
            } catch (_: Exception) { mutable.update { it.copy(loading = false, error = "无法读取页目录，原页未删除。请返回重试。") } }
        }
    }
    fun select(id: String) {
        if (ui.value.busy || pending != null || pendingEdit != null || ui.value.pages.none { it.id == id }) return
        mutable.update { it.copy(selectedId = id) }
        val generation = ++selectGeneration
        viewModelScope.launch {
            try {
                selectionLock.withLock { if (selectGeneration == generation) withContext(Dispatchers.IO) { repo.select(bookId, id) } }
            } catch (c: CancellationException) { throw c
            } catch (_: Exception) { mutable.update { it.copy(error = "当前页已打开，但最后阅读页未保存；无需重复加页。") }
            }
        }
    }
    fun insert(location: PageInsertLocation, anchorId: String?, paper: PaperStyle, count: Int, openNew: Boolean, previewOrder: String) {
        if (ui.value.loading || ui.value.busy || pending != null || pendingEdit != null || count !in 1..InsertPages.MAX_BATCH) return
        val rows = ui.value.pages
        if (rows.isEmpty() || rows.any { it.world } || rows.size + ui.value.recycled.size + count > InsertPages.MAX_PAGES) return
        if (previewOrder != InsertPages.orderHash(rows.map { it.id })) {
            mutable.update { it.copy(error = "页面顺序已变化，没有插入。请重新核对插页预览。") }; return
        }
        pending = InsertPages(UUID.randomUUID().toString(), bookId, previewOrder,
            location, anchorId, paper, List(count) { UUID.randomUUID().toString() }, openNew,
            if (openNew) null else ui.value.selectedId)
        persistPending(); submitPending()
    }
    fun retryInsertion() { if (!ui.value.busy && pending != null) submitPending() }
    private fun submitPending() {
        val command = pending ?: return
        mutable.update { it.copy(busy = true, insertionUnknown = false, error = null) }
        ++selectGeneration // Queued older page-selection writes are no longer authoritative.
        viewModelScope.launch {
            try {
                val outcome = selectionLock.withLock { withContext(Dispatchers.IO) { repo.insert(command) } }
                when (val result = outcome) {
                    is InsertPagesResult.Applied -> {
                        // Reading position is part of that SAME transaction, not a
                        // second write which could misreport a successful insertion.
                        val available=withContext(Dispatchers.IO){workspace.get(bookId).selectedPageId}
                        requestedSelection=available
                        pending = null; persistPending()
                        mutable.update { old -> old.copy(busy = false, insertionUnknown = false, error = null,
                            selectedId = available.takeIf{it.isNotEmpty()} ?: old.selectedId) }
                    }
                    else -> {
                        pending = null; persistPending()
                        val message = when (result) {
                            InsertPagesResult.OrderChanged -> "页面顺序已变化，没有插入。请重新选择位置并核对预览。"
                            InsertPagesResult.CapacityReached -> "超过本笔记的页面预算，没有插入。"
                            InsertPagesResult.CommandReused -> "插页命令身份不一致，没有覆盖既有页面。"
                            else -> "当前笔记或目标页面不可插入，原内容保留。"
                        }
                        mutable.update { it.copy(busy = false, insertionUnknown = false, error = message) }
                    }
                }
            } catch (c: CancellationException) {
                mutable.update { it.copy(busy = false, insertionUnknown = true) }; throw c
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, insertionUnknown = true,
                    error = "插页结果待核对。使用“核对原插页”，不会新建另一批页面。") }
            }
        }
    }
    fun clearError() { if (pending == null && pendingEdit==null) mutable.update { it.copy(error = null) } }

    fun editPage(kind:PageEditKind,pageId:String,where:PageInsertLocation,anchor:String?,order:String,sourceRevision:Long,trashAt:Long?) {
        if(ui.value.loading||ui.value.busy||pending!=null||pendingEdit!=null)return
        val selected=ui.value.selectedId?:return
        pendingEdit=EditPage(UUID.randomUUID().toString(),bookId,pageId,kind,order,sourceRevision,
            where,anchor,if(kind==PageEditKind.COPY)UUID.randomUUID().toString()else null,trashAt,selected)
        savedState["page.edit"]=pendingEdit!!.fields();submitEdit()
    }
    fun retryPageEdit(){if(!ui.value.busy&&pendingEdit!=null)submitEdit()}
    private fun submitEdit(){
        val command=pendingEdit?:return
        mutable.update{it.copy(busy=true,actionUnknown=false,error=null)};++selectGeneration
        viewModelScope.launch{
            try{
                val result=selectionLock.withLock{withContext(Dispatchers.IO){repo.edit(command)}}
                pendingEdit=null;savedState.remove<ArrayList<String>>("page.edit")
                if(result is EditPageResult.Applied){
                    requestedSelection=result.selectedPageId
                    mutable.update{it.copy(busy=false,actionUnknown=false,selectedId=result.selectedPageId)}
                } else mutable.update{it.copy(busy=false,actionUnknown=false,error=when(result){
                    EditPageResult.OrderChanged->"页序已变化，未执行。请重新核对目标位置。"
                    EditPageResult.SourceChanged->"本页内容或回收状态已变化，未执行。请重新打开操作。"
                    EditPageResult.LastPage->"至少保留一张可用页面，不能回收最后一页。"
                    EditPageResult.CapacityReached->"页面总量（含回收区）已达预算，请使用新笔记。"
                    EditPageResult.CommandReused->"操作身份冲突，没有改写页面。"
                    else->"目标页面当前不可操作，原资料保留。"
                })}
            }catch(c:CancellationException){mutable.update{it.copy(busy=false,actionUnknown=true)};throw c}
            catch(_:Exception){mutable.update{it.copy(busy=false,actionUnknown=true,error="页面操作结果待核对。请核对原操作，勿重复创建副本。")}}
        }
    }

    // Saved state covers system-restored UI instances, not a promise of saving
    // unacknowledged work across force-stop. Durable receipts cover committed work.
    private fun persistPending() {
        val c = pending
        savedState.set<String?>("insert.stay", c?.stayOnPageId)
        savedState.set<ArrayList<String>?>("insert.operation", c?.let {
            arrayListOf(it.commandId, it.notebookId, it.expectedOrder, it.location.name,
                it.anchorPageId.orEmpty(), it.paper.name, it.openInserted.toString(), *it.pageIds.toTypedArray())
        })
    }
    private fun restorePending(): InsertPages? {
        val values = savedState.get<ArrayList<String>>("insert.operation") ?: return null
        require(values.size in 8..(7 + InsertPages.MAX_BATCH) && values[1] == bookId)
        return InsertPages(values[0], values[1], values[2], PageInsertLocation.valueOf(values[3]),
            values[4].ifEmpty { null }, PaperStyle.valueOf(values[5]), values.drop(7), values[6].toBooleanStrict(),
            savedState.get<String>("insert.stay"))
    }
    class Factory(private val id: String, private val repo: NotebookPages, private val workspace: WorkspaceRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(BookPagesViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BookPagesViewModel(id, repo, workspace, extras.createSavedStateHandle()) as T
        }
    }
}
