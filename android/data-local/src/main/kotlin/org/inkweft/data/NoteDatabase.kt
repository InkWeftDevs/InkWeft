// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="notes")
data class NoteRow(@PrimaryKey val id:String,val revision:Long,val title:String,val text:String,val updatedAt:Long)
@Entity(tableName="note_revisions",primaryKeys=["noteId","revision"])
data class NoteRevisionRow(val noteId:String,val revision:Long,val title:String,val text:String,val committedAt:Long)
@Entity(tableName="command_receipts")
data class ReceiptRow(@PrimaryKey val commandId:String,val noteId:String,val digest:String,val committedRevision:Long)
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeNotes():Flow<List<NoteRow>>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun note(id:String):NoteRow?
    @Query("SELECT * FROM command_receipts WHERE commandId=:id") suspend fun receipt(id:String):ReceiptRow?
    @Query("SELECT * FROM note_revisions WHERE noteId=:id AND revision=:revision") suspend fun revision(id:String,revision:Long):NoteRevisionRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertNote(row:NoteRow)
    @Query("UPDATE notes SET title=:title,text=:text,revision=:nextRevision,updatedAt=:at WHERE id=:id AND revision=:expected")
    suspend fun compareAndSet(id:String,expected:Long,nextRevision:Long,title:String,text:String,at:Long):Int
    @Query("UPDATE notes SET updatedAt=:at WHERE id=:id") suspend fun touch(id:String,at:Long):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertRevision(row:NoteRevisionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertReceipt(row:ReceiptRow)
}
@Database(entities=[NoteRow::class,NoteRevisionRow::class,ReceiptRow::class,InkPageRow::class,InkStrokeRow::class,InkReceiptRow::class,WorkspaceRow::class],
    version=3,exportSchema=true,autoMigrations=[AutoMigration(from=1,to=2)])
abstract class NoteDatabase:RoomDatabase() {
    abstract fun notes():NoteDao
    abstract fun ink():InkDao
    abstract fun workspace():WorkspaceDao
    companion object {
        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `notebook_workspace` (`noteId` TEXT NOT NULL, `world` INTEGER NOT NULL, `paper` INTEGER NOT NULL, `folder` TEXT NOT NULL, `tags` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `trashedAt` INTEGER, `centerX` REAL NOT NULL, `centerY` REAL NOT NULL, `zoom` REAL NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`noteId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("INSERT INTO notebook_workspace (noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision) SELECT id,0,1,'','',0,NULL,500.0,707.0,0.0,0 FROM notes")
            }
        }
        fun open(context:Context,name:String="inkweft-a0.db"):NoteDatabase=
            Room.databaseBuilder(context.applicationContext,NoteDatabase::class.java,name)
                .openHelperFactory(PreservingOpenHelperFactory())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3)
                .addCallback(object:Callback(){override fun onOpen(db:SupportSQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}})
                .build()
    }
}
