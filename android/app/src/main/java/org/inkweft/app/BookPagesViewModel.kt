// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

data class BookPagesUi(
    val pages: List<NotebookPageRow> = emptyList(), val selectedId: String? = null,
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val insertionUnknown: Boolean = false,
)

class BookPagesViewModel(
    private val bookId: String, private val repo: NotebookPages,
    private val workspace: WorkspaceRepository, private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private var pending: InsertPages? = restorePending()
    private val mutable = MutableStateFlow(BookPagesUi(insertionUnknown = pending != null))
    val ui = mutable.asStateFlow()
    private var requestedSelection: String? = null
    private var selectGeneration = 0L
    private val selectionLock = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { repo.ensureFirst(bookId); workspace.get(bookId).selectedPageId }
                repo.observe(bookId).collect { rows ->
                    val desired = requestedSelection
                    mutable.update { old -> old.copy(pages = rows, loading = false,
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
        if (ui.value.busy || pending != null || ui.value.pages.none { it.id == id }) return
        mutable.update { it.copy(selectedId = id) }
        val generation = ++selectGeneration
        viewModelScope.launch {
            selectionLock.lock()
            try {
                if (selectGeneration == generation) withContext(Dispatchers.IO) { repo.select(bookId, id) }
            } catch (c: CancellationException) { throw c
            } catch (_: Exception) { mutable.update { it.copy(error = "当前页已打开，但最后阅读页未保存；无需重复加页。") }
            } finally { selectionLock.unlock() }
        }
    }
    fun insert(location: PageInsertLocation, anchorId: String?, paper: PaperStyle, count: Int, openNew: Boolean) {
        if (ui.value.loading || ui.value.busy || pending != null || count !in 1..InsertPages.MAX_BATCH) return
        val rows = ui.value.pages
        if (rows.isEmpty() || rows.any { it.world } || rows.size + count > InsertPages.MAX_PAGES) return
        pending = InsertPages(UUID.randomUUID().toString(), bookId, InsertPages.orderHash(rows.map { it.id }),
            location, anchorId, paper, List(count) { UUID.randomUUID().toString() }, openNew)
        persistPending(); submitPending()
    }
    fun retryInsertion() { if (!ui.value.busy && pending != null) submitPending() }
    private fun submitPending() {
        val command = pending ?: return
        mutable.update { it.copy(busy = true, insertionUnknown = false, error = null) }
        ++selectGeneration
        viewModelScope.launch {
            selectionLock.lock()
            try {
                when (val result = withContext(Dispatchers.IO) { repo.insert(command) }) {
                    is InsertPagesResult.Applied -> {
                        if (command.openInserted) requestedSelection = result.pageIds.first()
                        pending = null; persistPending()
                        mutable.update { old -> old.copy(busy = false, insertionUnknown = false, error = null,
                            selectedId = if (command.openInserted) result.pageIds.first() else old.selectedId) }
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
            } finally { selectionLock.unlock() }
        }
    }
    fun clearError() { if (pending == null) mutable.update { it.copy(error = null) } }

    // Saved state covers system-restored UI instances, not a promise of saving
    // unacknowledged work across force-stop. Durable receipts cover committed work.
    private fun persistPending() {
        val c = pending
        savedState.set<ArrayList<String>?>("insert.operation", c?.let {
            arrayListOf(it.commandId, it.notebookId, it.expectedOrder, it.location.name,
                it.anchorPageId.orEmpty(), it.paper.name, it.openInserted.toString(), *it.pageIds.toTypedArray())
        })
    }
    private fun restorePending(): InsertPages? {
        val values = savedState.get<ArrayList<String>>("insert.operation") ?: return null
        require(values.size in 8..(7 + InsertPages.MAX_BATCH) && values[1] == bookId)
        return InsertPages(values[0], values[1], values[2], PageInsertLocation.valueOf(values[3]),
            values[4].ifEmpty { null }, PaperStyle.valueOf(values[5]), values.drop(7), values[6].toBooleanStrict())
    }
    class Factory(private val id: String, private val repo: NotebookPages, private val workspace: WorkspaceRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(BookPagesViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BookPagesViewModel(id, repo, workspace, extras.createSavedStateHandle()) as T
        }
    }
}
