// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteRow(
    @PrimaryKey val id: String,
    val revision: Long,
    val title: String,
    val text: String,
    val updatedAt: Long,
)

@Entity(tableName = "note_revisions", primaryKeys = ["noteId", "revision"])
data class NoteRevisionRow(
    val noteId: String,
    val revision: Long,
    val title: String,
    val text: String,
    val committedAt: Long,
)

@Entity(tableName = "command_receipts")
data class ReceiptRow(
    @PrimaryKey val commandId: String,
    val noteId: String,
    val digest: String,
    val committedRevision: Long,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<NoteRow>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteRow?

    @Query("SELECT * FROM command_receipts WHERE commandId = :id")
    suspend fun receipt(id: String): ReceiptRow?

    @Query("SELECT * FROM note_revisions WHERE noteId = :id AND revision = :revision")
    suspend fun revision(id: String, revision: Long): NoteRevisionRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(row: NoteRow)

    @Query("UPDATE notes SET title=:title, text=:text, revision=:nextRevision, updatedAt=:at WHERE id=:id AND revision=:expected")
    suspend fun compareAndSet(id: String, expected: Long, nextRevision: Long,
                             title: String, text: String, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(row: NoteRevisionRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(row: ReceiptRow)
}

@Database(entities = [NoteRow::class, NoteRevisionRow::class, ReceiptRow::class],
    version = 1, exportSchema = true)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun open(context: Context, name: String = "inkweft-a0.db"): NoteDatabase =
            Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, name)
                .openHelperFactory(PreservingOpenHelperFactory())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous=FULL")
                    }
                })
                // Never recover errors by destroying the user's database.
                .build()
    }
}
