// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import org.inkweft.core.*

@Entity(tableName = "page_insert_receipts", indices = [Index("notebookId")], foreignKeys = [
    ForeignKey(entity = NoteRow::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.NO_ACTION)
])
data class PageInsertReceiptRow(@PrimaryKey val commandId: String, val notebookId: String, val digest: String, val pageIds: String)

@Dao
interface PageInsertionDao {
    @Query("SELECT * FROM page_insert_receipts WHERE commandId=:id")
    suspend fun receipt(id: String): PageInsertReceiptRow?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun record(row: PageInsertReceiptRow)
    @Query("UPDATE notebook_pages SET position=position+:amount WHERE notebookId=:bookId AND position>=:index")
    suspend fun shift(bookId: String, index: Int, amount: Int): Int
}

enum class PageInsertFault { BEFORE_RECEIPT, AFTER_TRANSACTION }

/** Page order, all new pages, reading position and receipt publish atomically.
 * No source/ink/search payload is rewritten. A post-commit exception is UNKNOWN
 * to the caller; the same command recovers its receipt, never creates another batch. */
class PageInsertionRepository(private val db: NoteDatabase, private val fault: (PageInsertFault) -> Unit = {}) {
    suspend fun insert(command: InsertPages): InsertPagesResult {
        val result = db.withTransaction {
            val book = db.notes().note(command.notebookId) ?: return@withTransaction InsertPagesResult.Unavailable
            val workspace = db.workspace().get(book.id) ?: return@withTransaction InsertPagesResult.Unavailable
            if (workspace.trashedAt != null || workspace.world) return@withTransaction InsertPagesResult.Unavailable
            val digest = command.digest()
            val previous = db.pageInsertions().receipt(command.commandId)
            if (previous != null) {
                if (previous.notebookId != book.id || previous.digest != digest || previous.pageIds != command.pageIds.joinToString(","))
                    return@withTransaction InsertPagesResult.CommandReused
                // A damaged receipt is not a reason to create replacement pages.
                command.pageIds.forEach { check(db.pages().get(it)?.notebookId == book.id) }
                return@withTransaction InsertPagesResult.Applied(command.pageIds, replayed = true)
            }
            val pages = db.pages().list(book.id)
            check(pages.isNotEmpty() && pages.withIndex().all { (index, page) -> page.position == index && !page.world })
            if (InsertPages.orderHash(pages.map { it.id }) != command.expectedOrder)
                return@withTransaction InsertPagesResult.OrderChanged
            if (pages.size + command.pageIds.size > InsertPages.MAX_PAGES)
                return@withTransaction InsertPagesResult.CapacityReached
            if (command.pageIds.any { db.pages().get(it) != null })
                return@withTransaction InsertPagesResult.CommandReused
            val stay = command.stayOnPageId ?: workspace.selectedPageId.takeIf { id -> pages.any { it.id == id } }
                ?: pages.first().id
            if (pages.none { it.id == stay }) return@withTransaction InsertPagesResult.Unavailable
            val index = command.insertionIndex(pages.map { it.id })
            check(db.pageInsertions().shift(book.id, index, command.pageIds.size) == pages.size - index)
            val predecessor = pages.getOrNull(index - 1)?.id
            command.pageIds.forEachIndexed { offset, id ->
                db.pages().insert(NotebookPageRow(id, book.id, index + offset, false, command.paper.ordinal,
                    createdAfterId = if (offset == 0) predecessor else command.pageIds[offset - 1]))
            }
            check(db.workspace().selectPage(book.id, if (command.openInserted) command.pageIds.first() else stay) == 1)
            check(db.notes().touch(book.id, System.currentTimeMillis()) == 1)
            fault(PageInsertFault.BEFORE_RECEIPT)
            db.pageInsertions().record(PageInsertReceiptRow(command.commandId, book.id, digest, command.pageIds.joinToString(",")))
            InsertPagesResult.Applied(command.pageIds)
        }
        fault(PageInsertFault.AFTER_TRANSACTION)
        return result
    }
}
