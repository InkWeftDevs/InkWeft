// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import java.util.UUID

data class NotebookUi(
    val notes: List<Note> = emptyList(),
    val drafts: Map<String, NoteDraft> = emptyMap(),
    val selectedId: String? = null,
    val loading: Boolean = true,
    val readFailed: Boolean = false,
) {
    val current: NoteDraft? get() = drafts[selectedId]
}

class NotebookViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as InkWeftApplication).repository
    private val mutableUi = MutableStateFlow(NotebookUi())
    val ui: StateFlow<NotebookUi> = mutableUi.asStateFlow()
    private var observation: Job? = null

    init { retryRead() }

    fun retryRead() {
        observation?.cancel()
        mutableUi.update { it.copy(loading = true, readFailed = false) }
        observation = viewModelScope.launch {
            try {
                repository.observeNotes().collect { notes ->
                    // DB emissions may update the shelf, never replace an active draft.
                    mutableUi.update { it.copy(notes = notes, loading = false, readFailed = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(loading = false, readFailed = true) }
            }
        }
    }

    fun create(title: String) {
        if (title.isBlank() || title.length > 120) return
        val note = Note(UUID.randomUUID().toString(), 0, title.trim(), "")
        mutableUi.update { it.copy(drafts = it.drafts + (note.id to NoteDraft(note)), selectedId = note.id) }
    }

    fun select(note: Note) {
        mutableUi.update {
            it.copy(selectedId = note.id, drafts = if (note.id in it.drafts) it.drafts
                else it.drafts + (note.id to NoteDraft(note)))
        }
    }

    fun back() { mutableUi.update { it.copy(selectedId = null) } }

    fun edit(title: String, text: String) {
        val d = ui.value.current ?: return
        if (!d.canEdit || title.length > 120 || text.length > 100_000) return
        replace(d.base.id, d.edit(title, text))
    }

    fun save() {
        val d = ui.value.current ?: return
        if (d.phase == SavePhase.SAVING || d.phase == SavePhase.CONFLICT ||
            d.phase == SavePhase.REJECTED || d.title.isBlank()) return
        val saving = d.beginSave()
        val command = checkNotNull(saving.pending)
        replace(d.base.id, saving)
        viewModelScope.launch {
            val result = try { repository.save(command)
            } catch (_: CancellationException) {
                // May have crossed commit. Never fabricate a definite rollback.
                replace(d.base.id, saving.finish(command, SaveResult.OutcomeUnknown))
                return@launch
            } catch (_: Exception) { SaveResult.OutcomeUnknown }
            val active = ui.value.drafts[d.base.id]
            if (active?.pending == command) replace(d.base.id, active.finish(command, result))
        }
    }

    /** Explicit discard is scoped to the exact draft present when requested. */
    fun discardDraftAndRead() {
        val requested = ui.value.current ?: return
        if (requested.phase in listOf(SavePhase.SAVING, SavePhase.UNKNOWN)) return
        val id = requested.base.id
        viewModelScope.launch {
            try {
                val note = repository.read(id) ?: return@launch
                mutableUi.update { currentUi ->
                    val currentDraft = currentUi.drafts[id] ?: return@update currentUi
                    val replacement = currentDraft.acceptExplicitReload(requested, note)
                    if (replacement === currentDraft) currentUi
                    else currentUi.copy(drafts = currentUi.drafts + (id to replacement))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableUi.update { it.copy(readFailed = true) } }
        }
    }

    private fun replace(id: String, draft: NoteDraft) {
        mutableUi.update { it.copy(drafts = it.drafts + (id to draft)) }
    }
}
