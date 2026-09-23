// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.inkweft.core.Note
import org.inkweft.core.SaveNote
import org.inkweft.core.SaveResult

/** Single local-user author-write entry. No plugin or cloud permissions exposed. */
class NoteRepository(private val db: NoteDatabase) {
    private val dao = db.notes()

    fun observeNotes(): Flow<List<Note>> =
        dao.observeNotes().map { rows -> rows.map { it.domain() } }

    suspend fun read(id: String): Note? = dao.note(id)?.domain()

    /**
     * Returns COMMITTED only after Room's transaction returns.
     * Caller treats an exception/cancellation near commit as unconfirmed, keeps the
     * original command, and retries: a stored receipt is checked before the CAS.
     */
    suspend fun save(command: SaveNote): SaveResult = db.withTransaction {
        val digest = command.digest()
        val previous = dao.receipt(command.commandId)
        if (previous != null) {
            if (previous.noteId != command.noteId || previous.digest != digest) {
                return@withTransaction SaveResult.ReusedCommandId
            }
            val historical = checkNotNull(dao.revision(previous.noteId, previous.committedRevision)) {
                "Receipt revision is unavailable; do not reset storage"
            }
            return@withTransaction SaveResult.Committed(
                Note(historical.noteId, historical.revision, historical.title, historical.text)
            )
        }

        val current = dao.note(command.noteId)
        if (current?.revision != command.expectedRevision &&
            !(current == null && command.expectedRevision == 0L)) {
            return@withTransaction SaveResult.Conflict(current?.domain())
        }

        val revision = command.expectedRevision + 1
        val at = System.currentTimeMillis()
        val row = NoteRow(command.noteId, revision, command.title, command.text, at)
        if (current == null) {
            dao.insertNote(row)
        } else if (dao.compareAndSet(row.id, command.expectedRevision, revision,
                row.title, row.text, at) != 1) {
            return@withTransaction SaveResult.Conflict(dao.note(row.id)?.domain())
        }
        dao.insertRevision(NoteRevisionRow(row.id, revision, row.title, row.text, at))
        dao.insertReceipt(ReceiptRow(command.commandId, row.id, digest, revision))
        SaveResult.Committed(row.domain())
    }

    private fun NoteRow.domain() = Note(id, revision, title, text)
}
