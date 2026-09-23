// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

data class Note(val id: String, val revision: Long, val title: String, val text: String) {
    init {
        UUID.fromString(id)
        require(revision in 0 until Long.MAX_VALUE)
        require(title.length <= 120 && text.length <= 100_000)
    }
}

/** Semantic identity excludes retry time and refreshable credentials. */
data class SaveNote(
    val commandId: String,
    val noteId: String,
    val expectedRevision: Long,
    val title: String,
    val text: String,
) {
    init {
        UUID.fromString(commandId)
        UUID.fromString(noteId)
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE - 1)
        require(title.isNotBlank() && title.length <= 120 && text.length <= 100_000)
    }

    fun digest(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf("inkweft.save-note.v1", commandId, noteId,
                expectedRevision.toString(), title, text).forEach {
                val field = it.toByteArray(Charsets.UTF_8)
                out.writeInt(field.size)
                out.write(field)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

sealed interface SaveResult {
    data class Committed(val note: Note) : SaveResult
    data class Conflict(val current: Note?) : SaveResult
    data object ReusedCommandId : SaveResult
    data object OutcomeUnknown : SaveResult
}

enum class SavePhase { EDITING, SAVING, UNKNOWN, CONFLICT, REJECTED }

/** Per-note draft: blur/navigation do not replace text with the latest DB head. */
data class NoteDraft(
    val base: Note,
    val title: String = base.title,
    val text: String = base.text,
    val pending: SaveNote? = null,
    val phase: SavePhase = SavePhase.EDITING,
) {
    val dirty: Boolean get() = base.revision == 0L || title != base.title || text != base.text
    val canEdit: Boolean get() = phase != SavePhase.SAVING && phase != SavePhase.UNKNOWN

    fun edit(title: String = this.title, text: String = this.text): NoteDraft {
        require(canEdit) { "Resolve the pending command before changing its semantic payload" }
        require(title.length <= 120 && text.length <= 100_000)
        return copy(title = title, text = text, pending = null, phase = SavePhase.EDITING)
    }

    fun beginSave(newCommandId: () -> String = { UUID.randomUUID().toString() }): NoteDraft {
        require(phase != SavePhase.SAVING)
        require(phase != SavePhase.CONFLICT && phase != SavePhase.REJECTED)
        val command = pending ?: SaveNote(newCommandId(), base.id, base.revision, title, text)
        return copy(pending = command, phase = SavePhase.SAVING)
    }

    fun finish(command: SaveNote, result: SaveResult): NoteDraft {
        require(pending == command) { "Receipt does not belong to this exact pending edit" }
        return when (result) {
            is SaveResult.Committed -> {
                val n = result.note
                require(n.id == base.id && n.revision == command.expectedRevision + 1)
                require(n.title == command.title && n.text == command.text)
                NoteDraft(n)
            }
            is SaveResult.Conflict -> copy(pending = null, phase = SavePhase.CONFLICT)
            SaveResult.ReusedCommandId -> copy(pending = null, phase = SavePhase.REJECTED)
            SaveResult.OutcomeUnknown -> copy(phase = SavePhase.UNKNOWN)
        }
    }
}
