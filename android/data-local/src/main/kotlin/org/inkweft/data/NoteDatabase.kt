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
    @Query("SELECT n.* FROM notes n JOIN notebook_workspace w ON w.noteId=n.id WHERE w.trashedAt IS NULL ORDER BY n.updatedAt DESC") fun observeAvailableNotes():Flow<List<NoteRow>>
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
@Database(entities=[NoteRow::class,NoteRevisionRow::class,ReceiptRow::class,InkPageRow::class,InkStrokeRow::class,InkReceiptRow::class,WorkspaceRow::class,NotebookPageRow::class,InkCutRow::class,PageSearchRow::class,PageInsertReceiptRow::class,LibraryContentReceipt::class,PageEditReceiptRow::class,StudyCardRow::class,StudyCardRevisionRow::class,StudySourceRow::class,StudyNodeRow::class,StudyReceiptRow::class,KnowledgeRow::class,KnowledgeRevisionRow::class,KnowledgeReceiptRow::class,NotebookCoverRow::class,PageObjectRow::class,ObjectReceiptRow::class,DocumentSourceRow::class,DocumentChunkRow::class,DocumentPageRow::class],
    version=12,exportSchema=true,autoMigrations=[AutoMigration(from=1,to=2)])
abstract class NoteDatabase:RoomDatabase() {
    internal var documentScratch:java.io.File?=null
    abstract fun notes():NoteDao
    abstract fun ink():InkDao
    abstract fun workspace():WorkspaceDao
    abstract fun pages():NotebookPageDao
    abstract fun pageInsertions():PageInsertionDao
    abstract fun libraryContent():LibraryContentDao
    abstract fun pageEdits():PageEditingDao
    abstract fun study():StudyDao
    abstract fun knowledge():KnowledgeDao
    abstract fun objects():PageObjectDao
    abstract fun documents():DocumentDao
    abstract fun covers():NotebookCoverDao
    companion object {
        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `notebook_workspace` (`noteId` TEXT NOT NULL, `world` INTEGER NOT NULL, `paper` INTEGER NOT NULL, `folder` TEXT NOT NULL, `tags` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `trashedAt` INTEGER, `centerX` REAL NOT NULL, `centerY` REAL NOT NULL, `zoom` REAL NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`noteId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("INSERT INTO notebook_workspace (noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision) SELECT id,0,1,'','',0,NULL,500.0,707.0,0.0,0 FROM notes")
            }
        }
        val MIGRATION_3_4=object:Migration(3,4){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE `notebook_workspace` ADD COLUMN `coverKey` TEXT NOT NULL DEFAULT 'auto'")
                db.execSQL("ALTER TABLE `notebook_workspace` ADD COLUMN `selectedPageId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `notebook_pages` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `position` INTEGER NOT NULL, `world` INTEGER NOT NULL, `paper` INTEGER NOT NULL, `centerX` REAL NOT NULL, `centerY` REAL NOT NULL, `zoom` REAL NOT NULL, `createdAfterId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`notebookId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebook_pages_notebookId` ON `notebook_pages` (`notebookId`)")
                db.execSQL("INSERT INTO notebook_pages (id,notebookId,position,world,paper,centerX,centerY,zoom,createdAfterId) SELECT n.id,n.id,0,COALESCE(w.world,0),COALESCE(w.paper,1),COALESCE(w.centerX,500.0),COALESCE(w.centerY,707.0),COALESCE(w.zoom,0.0),NULL FROM notes n LEFT JOIN notebook_workspace w ON w.noteId=n.id")
                // Copy bytes without decoding/resampling; only the ownership FK changes.
                db.execSQL("CREATE TABLE ink_pages_new (`noteId` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`noteId`), FOREIGN KEY(`noteId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("INSERT INTO ink_pages_new SELECT * FROM ink_pages")
                db.execSQL("DROP TABLE ink_pages")
                db.execSQL("ALTER TABLE ink_pages_new RENAME TO ink_pages")
                db.execSQL("CREATE TABLE ink_strokes_new (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `payload` BLOB NOT NULL, `pointCount` INTEGER NOT NULL, `visible` INTEGER NOT NULL, `createdRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("INSERT INTO ink_strokes_new SELECT * FROM ink_strokes")
                db.execSQL("DROP TABLE ink_strokes")
                db.execSQL("ALTER TABLE ink_strokes_new RENAME TO ink_strokes")
                db.execSQL("CREATE INDEX index_ink_strokes_noteId ON ink_strokes(noteId)")
                db.execSQL("CREATE TABLE ink_receipts_new (`commandId` TEXT NOT NULL, `noteId` TEXT NOT NULL, `digest` TEXT NOT NULL, `committedRevision` INTEGER NOT NULL, `strokeIds` TEXT NOT NULL, `visible` INTEGER NOT NULL, PRIMARY KEY(`commandId`), FOREIGN KEY(`noteId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("INSERT INTO ink_receipts_new SELECT * FROM ink_receipts")
                db.execSQL("DROP TABLE ink_receipts")
                db.execSQL("ALTER TABLE ink_receipts_new RENAME TO ink_receipts")
                db.execSQL("CREATE INDEX index_ink_receipts_noteId ON ink_receipts(noteId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ink_cuts` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `payload` BLOB NOT NULL, `strokeIds` TEXT NOT NULL, `visible` INTEGER NOT NULL, `createdRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ink_cuts_noteId` ON `ink_cuts` (`noteId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `page_search_text` (`pageId` TEXT NOT NULL, `inkRevision` INTEGER NOT NULL, `text` TEXT NOT NULL, `method` TEXT NOT NULL, PRIMARY KEY(`pageId`), FOREIGN KEY(`pageId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            }
        }
        val MIGRATION_4_5=object:Migration(4,5){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `page_insert_receipts` (`commandId` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `digest` TEXT NOT NULL, `pageIds` TEXT NOT NULL, PRIMARY KEY(`commandId`), FOREIGN KEY(`notebookId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_insert_receipts_notebookId` ON `page_insert_receipts` (`notebookId`)")
            }
        }
        val MIGRATION_5_6=object:Migration(5,6){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE `notebook_workspace` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `library_content_receipts` (`commandId` TEXT NOT NULL, `kind` TEXT NOT NULL, `digest` TEXT NOT NULL, `noteId` TEXT NOT NULL, PRIMARY KEY(`commandId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_content_receipts_noteId` ON `library_content_receipts` (`noteId`)")
            }
        }
        val MIGRATION_6_7=object:Migration(6,7){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE notebook_pages ADD COLUMN trashedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS page_edit_receipts (commandId TEXT NOT NULL,notebookId TEXT NOT NULL,digest TEXT NOT NULL,kind TEXT NOT NULL,pageId TEXT NOT NULL,resultPageId TEXT NOT NULL,PRIMARY KEY(commandId),FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_page_edit_receipts_notebookId ON page_edit_receipts(notebookId)")
            }
        }
        val MIGRATION_7_8=object:Migration(7,8){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS study_cards (id TEXT NOT NULL, notebookId TEXT NOT NULL, revision INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, trashedAt INTEGER, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_cards_notebookId ON study_cards(notebookId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS study_card_revisions (cardId TEXT NOT NULL, revision INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, trashedAt INTEGER, PRIMARY KEY(cardId,revision), FOREIGN KEY(cardId) REFERENCES study_cards(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE TABLE IF NOT EXISTS study_sources (cardId TEXT NOT NULL, pageId TEXT NOT NULL, inkRevision INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, strokeIds TEXT NOT NULL, snapshot BLOB NOT NULL, PRIMARY KEY(cardId), FOREIGN KEY(cardId) REFERENCES study_cards(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_sources_pageId ON study_sources(pageId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS study_nodes (id TEXT NOT NULL, notebookId TEXT NOT NULL, cardId TEXT NOT NULL, parentId TEXT, x REAL NOT NULL, y REAL NOT NULL, revision INTEGER NOT NULL, removed INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(cardId) REFERENCES study_cards(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(parentId) REFERENCES study_nodes(id) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_nodes_notebookId ON study_nodes(notebookId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_nodes_cardId ON study_nodes(cardId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_nodes_parentId ON study_nodes(parentId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS study_receipts (id TEXT NOT NULL, notebookId TEXT NOT NULL, digest TEXT NOT NULL, resultId TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_receipts_notebookId ON study_receipts(notebookId)")
        }}
        val MIGRATION_8_9=object:Migration(8,9){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_records (id TEXT NOT NULL, notebookId TEXT NOT NULL, revision INTEGER NOT NULL, payload BLOB NOT NULL, removed INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_records_notebookId ON knowledge_records(notebookId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_revisions (id TEXT NOT NULL, revision INTEGER NOT NULL, notebookId TEXT NOT NULL, payload BLOB NOT NULL, removed INTEGER NOT NULL, PRIMARY KEY(id,revision), FOREIGN KEY(id) REFERENCES knowledge_records(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_revisions_notebookId ON knowledge_revisions(notebookId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_receipts (operationId TEXT NOT NULL, notebookId TEXT NOT NULL, digest TEXT NOT NULL, resultId TEXT NOT NULL, PRIMARY KEY(operationId), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_receipts_notebookId ON knowledge_receipts(notebookId)")
        }}
        val MIGRATION_9_10=object:Migration(9,10){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS notebook_covers (noteId TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(noteId), FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        }}
        val MIGRATION_10_11=object:Migration(10,11){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS page_objects (pageId TEXT NOT NULL, revision INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(pageId), FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE TABLE IF NOT EXISTS object_receipts (commandId TEXT NOT NULL, pageId TEXT NOT NULL, digest TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(commandId), FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_object_receipts_pageId ON object_receipts(pageId)")
        }}
        val MIGRATION_11_12=object:Migration(11,12){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE document_sources (id TEXT NOT NULL, notebookId TEXT NOT NULL, digest TEXT NOT NULL, pageCount INTEGER NOT NULL, byteCount INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX index_document_sources_notebookId ON document_sources(notebookId)")
            db.execSQL("CREATE TABLE document_chunks (documentId TEXT NOT NULL, position INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(documentId,position), FOREIGN KEY(documentId) REFERENCES document_sources(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE TABLE document_pages (pageId TEXT NOT NULL, documentId TEXT NOT NULL, sourcePage INTEGER NOT NULL, PRIMARY KEY(pageId), FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(documentId) REFERENCES document_sources(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX index_document_pages_documentId ON document_pages(documentId)")
        }}
        fun open(context:Context,name:String="inkweft-a0.db"):NoteDatabase=
            Room.databaseBuilder(context.applicationContext,NoteDatabase::class.java,name)
                .openHelperFactory(PreservingOpenHelperFactory())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10,MIGRATION_10_11,MIGRATION_11_12)
                .addCallback(object:Callback(){override fun onOpen(db:SupportSQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}})
                .build().also{it.documentScratch=context.cacheDir}
    }
}
