// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.*
import java.util.UUID

/** Full, already-committed AUTHOR LIBRARY, not device settings or a live .db copy.
 * Restore never overwrites an existing identity. Entire batch or no live writes.
 * Only trusted, locally compiled DDL is used. The archive contains values only. */
class LibraryBackupRepository(private val context:Context,private val db:NoteDatabase,
    private val fault:(BackupFault)->Unit={}) {
    enum class BackupFault { BEFORE_RESTORE_COMMIT, AFTER_RESTORE_COMMIT }
    enum class RestoreResult { RESTORED, ALREADY_PRESENT, IDENTITY_CONFLICT }
    class Preview internal constructor(internal val stage:NoteDatabase,internal val root:File,
        val summary:LibraryArchive.Summary,val notes:Int,val pages:Int,val trashed:Int,val recycledPages:Int,
        internal val canonical:String):Closeable {
        private var closed=false
        internal fun requireOpen(){check(!closed){"BACKUP_PREVIEW_CLOSED"}}
        override fun close(){if(!closed){closed=true;stage.close();root.deleteRecursively()}}
    }
    data class Snapshot(val file:File,val summary:LibraryArchive.Summary):Closeable {
        override fun close(){file.delete()}
    }
    private val scratch=File(context.cacheDir,"library-backup").apply{mkdirs()}
    private fun reserve(){require(scratch.usableSpace>=32L*1024*1024){"BACKUP_LOW_SPACE"}}
    suspend fun snapshot():Snapshot=withContext(Dispatchers.IO){
        reserve();val path=File(scratch,"snapshot-${UUID.randomUUID()}.iwbackup")
        try {
            val summary=db.withTransaction {
                val sql=db.openHelper.writableDatabase;checkSchema(sql);validate(db)
                val ctx=currentCoroutineContext()
                FileOutputStream(path).use{file->
                    val buffer=BufferedOutputStream(file)
                    val result=LibraryArchive.write(buffer,SCHEMA,SqlRows(sql),System.currentTimeMillis()){ctx.ensureActive()}
                    buffer.flush();file.fd.sync();result
                }
            }
            Snapshot(path,summary)
        }catch(t:Throwable){path.delete();throw t}
    }
    /** Caller owns the input. Only staging is touched before digest, FK and semantic validation. */
    suspend fun inspect(input:InputStream):Preview=withContext(Dispatchers.IO){
        reserve();val root=File(scratch,"stage-${UUID.randomUUID()}").apply{check(mkdirs())}
        val stage=NoteDatabase.open(context,File(root,"candidate.db").absolutePath)
        try {
            val parsed=stage.withTransaction {
                val sql=stage.openHelper.writableDatabase;checkSchema(sql);val ctx=currentCoroutineContext()
                var count=0
                LibraryArchive.read(input,SCHEMA,{table,row->
                    if(++count%128==0)reserve()
                    // Schema6 has no tombstone column; promote to live/default null.
                    val promoted=if(table==4&&row.size==SCHEMA[table].columns.size-1)row+listOf(null)else row
                    insert(sql,table,promoted)
                },legacySchema=SCHEMA_V6,otherLegacySchemas=listOf(SCHEMA_V7,SCHEMA_V8)){ctx.ensureActive()}
            }
            validate(stage)
            val sql=stage.openHelper.writableDatabase
            val notes=count(sql,"notes").toInt();val pages=count(sql,"notebook_pages").toInt()
            val trash=sql.query("SELECT COUNT(*) FROM notebook_workspace WHERE trashedAt IS NOT NULL").use{it.moveToFirst();it.getInt(0)}
            val recycled=sql.query("SELECT COUNT(*) FROM notebook_pages WHERE trashedAt IS NOT NULL").use{it.moveToFirst();it.getInt(0)}
            Preview(stage,root,parsed,notes,pages,trash,recycled,fingerprint(sql))
        }catch(t:Throwable){stage.close();root.deleteRecursively();throw t}
    }
    suspend fun restore(preview:Preview):RestoreResult=withContext(Dispatchers.IO){
        preview.requireOpen();reserve()
        val result=preview.stage.withTransaction {
            val source=preview.stage.openHelper.writableDatabase
            require(fingerprint(source)==preview.canonical){"BACKUP_PREVIEW_CHANGED"}
            val ids=source.query("SELECT id FROM notes ORDER BY id").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            db.withTransaction live@ {
                val target=db.openHelper.writableDatabase;checkSchema(target)
                if(ids.isEmpty())return@live RestoreResult.ALREADY_PRESENT
                val marks=ids.joinToString(","){"?"};val args=ids.toTypedArray<Any?>()
                val overlap=target.query("SELECT COUNT(*) FROM notes WHERE id IN ($marks)",args).use{it.moveToFirst();it.getInt(0)}
                if(overlap>0){
                    return@live if(overlap==ids.size && fingerprint(target,ids)==preview.canonical)
                        RestoreResult.ALREADY_PRESENT else RestoreResult.IDENTITY_CONFLICT
                }
                require(count(target,"notes")+preview.notes<=500 &&
                    count(target,"notebook_pages")+preview.pages<=20_000){"BACKUP_LIBRARY_BUDGET"}
                // All primary keys, including historical commands, must also be disjoint.
                for((index,t) in SCHEMA.withIndex()){
                    SqlRows(source).visit(index){row->
                        val argsKey=t.keys.map{key->row[t.columns.indexOfFirst{it.name==key}]}.toTypedArray()
                        val where=t.keys.joinToString(" AND "){"`$it`=?"}
                        val collision=target.query("SELECT 1 FROM `${t.name}` WHERE $where LIMIT 1",argsKey).use{it.moveToFirst()}
                        require(!collision){"BACKUP_COMMAND_IDENTITY_CONFLICT"}
                    }
                }
                val ctx=currentCoroutineContext()
                SCHEMA.indices.forEach{index->SqlRows(source).visit(index){row->ctx.ensureActive();insert(target,index,row)}}
                check(target.query("PRAGMA foreign_key_check").use{!it.moveToFirst()})
                check(fingerprint(target,ids)==preview.canonical){"BACKUP_RESTORE_MISMATCH"}
                fault(BackupFault.BEFORE_RESTORE_COMMIT)
                RestoreResult.RESTORED
            }
        }
        fault(BackupFault.AFTER_RESTORE_COMMIT)
        db.invalidationTracker.refreshVersionsAsync()
        result
    }
    private suspend fun validate(stage:NoteDatabase){
        val sql=stage.openHelper.writableDatabase
        check(sql.query("PRAGMA integrity_check").use{it.moveToFirst()&&it.getString(0)=="ok"&&!it.moveToNext()})
        check(sql.query("PRAGMA foreign_key_check").use{!it.moveToFirst()})
        require(count(sql,"notes")<=500 && count(sql,"notebook_pages")<=20_000){"BACKUP_LIBRARY_BUDGET"}
        val notes=sql.query("SELECT id FROM notes ORDER BY id").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(count(sql,"notebook_workspace")==notes.size.toLong())
        // Reject raw integers before Room narrows them to Int/Boolean. Otherwise
        // a corrupt 2^32 page ordinal can wrap to zero and look valid in Kotlin.
        fun noRows(query:String){require(sql.query(query).use{!it.moveToFirst()}){"BACKUP_REFERENCE_INVALID"}}
        noRows("SELECT 1 FROM notebook_pages WHERE world NOT IN (0,1) OR position NOT BETWEEN 0 AND 499 OR paper NOT BETWEEN 0 AND ${PaperStyle.entries.lastIndex}")
        noRows("SELECT 1 FROM notebook_workspace WHERE world NOT IN (0,1) OR favorite NOT IN (0,1) OR pinned NOT IN (0,1) OR paper NOT BETWEEN 0 AND ${PaperStyle.entries.lastIndex}")
        noRows("SELECT 1 FROM ink_receipts WHERE visible NOT IN (0,1)")
        noRows("SELECT 1 FROM library_content_receipts WHERE kind NOT IN ('COPY','IMPORT','CREATE')")
        noRows("SELECT 1 FROM notebook_pages WHERE trashedAt<0")
        noRows("SELECT 1 FROM study_nodes WHERE removed NOT IN (0,1)")
        require(sql.query("SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources").use{it.moveToFirst();it.getLong(0)}<=32_000_000){"STUDY_SNAPSHOT_BUDGET"}
        noRows("SELECT 1 FROM study_nodes n JOIN study_cards c ON c.id=n.cardId WHERE c.notebookId!=n.notebookId OR (c.trashedAt IS NOT NULL AND n.removed=0)")
        noRows("SELECT 1 FROM study_nodes n JOIN study_nodes p ON p.id=n.parentId WHERE n.notebookId!=p.notebookId")
        noRows("SELECT 1 FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE r.revision<1 OR r.revision>c.revision OR length(r.title)>120 OR length(r.body)>20000")
        noRows("SELECT 1 FROM study_cards c LEFT JOIN study_card_revisions r ON r.cardId=c.id AND r.revision=c.revision WHERE r.cardId IS NULL OR r.title!=c.title OR r.body!=c.body OR r.trashedAt IS NOT c.trashedAt")
        noRows("SELECT 1 FROM study_sources s JOIN study_cards c ON c.id=s.cardId JOIN notebook_pages p ON p.id=s.pageId WHERE c.notebookId!=p.notebookId")
        noRows("SELECT 1 FROM page_edit_receipts WHERE kind NOT IN ('MOVE','COPY','TRASH','RESTORE')")
        noRows("SELECT 1 FROM page_edit_receipts r LEFT JOIN notebook_pages p ON p.id=r.pageId LEFT JOIN notebook_pages q ON q.id=r.resultPageId WHERE p.id IS NULL OR q.id IS NULL OR p.notebookId!=r.notebookId OR q.notebookId!=r.notebookId")
        noRows("SELECT 1 FROM knowledge_records WHERE removed NOT IN (0,1) OR revision<1")
        noRows("SELECT 1 FROM knowledge_revisions r JOIN knowledge_records k ON k.id=r.id WHERE r.removed NOT IN (0,1) OR r.revision<1 OR r.revision>k.revision OR r.notebookId!=k.notebookId")
        KnowledgeRepository(stage).validateArchive()
        sql.query("SELECT notebookId,payload FROM knowledge_revisions").use{c->while(c.moveToNext())KnowledgeRepository(stage).validateData(c.getString(0),KnowledgeCodec.decode(c.getBlob(1)),false)}
        sql.query("SELECT operationId,digest,resultId FROM knowledge_receipts").use{c->while(c.moveToNext()){UUID.fromString(c.getString(0));require(c.getString(1).matches(Regex("[0-9a-f]{64}")));require(stage.knowledge().get(c.getString(2))!=null)}}
        for(id in notes){
            currentCoroutineContext().ensureActive();UUID.fromString(id)
            val n=checkNotNull(stage.notes().note(id));require(n.revision>=1&&RenameNote.validTitle(n.title)&&n.text.length<=100_000)
            val rev=checkNotNull(stage.notes().revision(id,n.revision));require(n.title==rev.title&&n.text==rev.text)
            val w=checkNotNull(stage.workspace().get(id));require(w.revision>=0&&NotebookCover.validKey(w.coverKey))
            require(w.folder.length<=48&&w.tags.length<=320&&w.paper in PaperStyle.entries.indices)
            CanvasViewport(w.centerX,w.centerY,if(w.zoom==0.0).7 else w.zoom)
            val cards=stage.study().cards(id);val nodes=stage.study().nodes(id)
            require(cards.size<=200);StudyGraph.validate(nodes.map{it.model()})
            for(c in cards){
                UUID.fromString(c.id);require(c.revision>=1&&c.title.isNotBlank()&&c.title.length<=120&&c.body.length<=20000&&(c.trashedAt==null||c.trashedAt>=0))
                stage.study().source(c.id)?.let{source->
                    val ids=source.strokeIds.split(',');val bounds=CanvasBounds(source.left,source.top,source.right,source.bottom)
                    StudySourceDraft(source.pageId,source.inkRevision,bounds,ids)
                    require(source.snapshot.size<=1800000);val snapshot=InkPageFile.decode(source.snapshot)
                    require(snapshot.strokes.map{it.id}.toSet()==ids.toSet())
                    for(strokeId in ids)require(stage.ink().stroke(strokeId)?.noteId==source.pageId)
                    require(snapshot.world==stage.pages().get(source.pageId)?.world)
                    require(source.inkRevision<=(stage.ink().page(source.pageId)?.revision?:0))
                }
            }
            val pages=stage.pages().allPages(id)
            val active=pages.filter{it.trashedAt==null}.sortedBy{it.position}
            require(pages.size in 1..500&&pages.any{it.id==id}&&pages.all{it.world==w.world})
            require(active.isNotEmpty()&&active.withIndex().all{(i,p)->p.position==i})
            require(!w.world||pages.size==1)
            require(w.selectedPageId.isEmpty()||active.any{it.id==w.selectedPageId})
            for(p in pages){
                UUID.fromString(p.id);require(p.paper in PaperStyle.entries.indices)
                CanvasViewport(p.centerX,p.centerY,if(p.zoom==0.0).7 else p.zoom)
                require(p.createdAfterId==null||pages.any{it.id==p.createdAfterId})
                val ink=InkRepository(stage).read(p.id);require(ink.revision>=0)
                stage.pages().search(p.id)?.let{s->require(s.inkRevision in 0..ink.revision&&s.text.length<=20_000&&s.method=="MANUAL")}
            }
        }
        noRows("SELECT 1 FROM note_revisions r LEFT JOIN notes n ON n.id=r.noteId WHERE n.id IS NULL OR r.revision<1 OR r.revision>n.revision OR length(r.title)>120 OR length(r.text)>100000")
        noRows("SELECT 1 FROM command_receipts r LEFT JOIN notes n ON n.id=r.noteId WHERE n.id IS NULL OR r.committedRevision<1 OR r.committedRevision>n.revision")
        noRows("SELECT 1 FROM ink_receipts r LEFT JOIN ink_pages p ON p.noteId=r.noteId WHERE p.noteId IS NULL OR r.committedRevision<1 OR r.committedRevision>p.revision")
        noRows("SELECT 1 FROM ink_strokes s LEFT JOIN ink_pages p ON p.noteId=s.noteId WHERE p.noteId IS NULL OR s.createdRevision<1 OR s.createdRevision>p.revision OR s.visible NOT IN (0,1)")
        noRows("SELECT 1 FROM ink_cuts s LEFT JOIN ink_pages p ON p.noteId=s.noteId WHERE p.noteId IS NULL OR s.createdRevision<1 OR s.createdRevision>p.revision OR s.visible NOT IN (0,1)")
        noRows("SELECT 1 FROM notebook_workspace WHERE world NOT IN (0,1) OR favorite NOT IN (0,1) OR pinned NOT IN (0,1)")
        for(t in listOf("command_receipts","ink_receipts","page_insert_receipts","library_content_receipts","page_edit_receipts")){
            sql.query("SELECT commandId,digest FROM $t").use{c->while(c.moveToNext()){UUID.fromString(c.getString(0));require(c.getString(1).matches(Regex("[0-9a-f]{64}")))}}
        }
        sql.query("SELECT id,digest,resultId FROM study_receipts").use{c->while(c.moveToNext()){UUID.fromString(c.getString(0));require(c.getString(1).matches(Regex("[0-9a-f]{64}")));UUID.fromString(c.getString(2))}}
        sql.query("SELECT notebookId,pageIds FROM page_insert_receipts").use{c->while(c.moveToNext()){
            val ids=c.getString(1).split(',');require(ids.size in 1..20&&ids.distinct().size==ids.size)
            for(page in ids){UUID.fromString(page);require(stage.pages().get(page)?.notebookId==c.getString(0))}
        }}
    }
    private class SqlRows(val db:SupportSQLiteDatabase,val ids:List<String>?=null):LibraryArchive.Rows {
        private fun where(table:Int):String {
            if(ids==null)return ""
            val marks=ids.joinToString(","){"?"}
            val col=OWNERS[table]
            return if(col=="@ink")" WHERE noteId IN (SELECT id FROM notebook_pages WHERE notebookId IN ($marks))"
                else if(col=="@search")" WHERE pageId IN (SELECT id FROM notebook_pages WHERE notebookId IN ($marks))"
                else if(col=="@cards")" WHERE cardId IN (SELECT id FROM study_cards WHERE notebookId IN ($marks))"
                else " WHERE `$col` IN ($marks)"
        }
        private fun query(sql:String)=if(ids==null)db.query(sql)else db.query(sql,ids.toTypedArray<Any?>())
        override fun count(table:Int)=query("SELECT COUNT(*) FROM `${SCHEMA[table].name}`"+where(table)).use{it.moveToFirst();it.getLong(0)}
        override fun visit(table:Int,consume:(List<Any?>)->Unit){
            val t=SCHEMA[table]
            query("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}`"+where(table)+" ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{c->
                while(c.moveToNext())consume(t.columns.mapIndexed{i,col->
                    if(c.isNull(i))null else when(col.kind){'I'->c.getLong(i);'F'->c.getDouble(i);'B'->c.getBlob(i);else->c.getString(i)}
                })
            }
        }
    }
    companion object {
        private fun col(name:String,kind:Char,nullable:Boolean=false)=LibraryArchive.Column(name,kind,nullable)
        private fun table(name:String,keys:String,vararg cols:LibraryArchive.Column)=LibraryArchive.Table(name,cols.toList(),keys.split(','))
        // Frozen author schema8; only explicitly compiled schema6/7 variants are also readable.
        val SCHEMA=listOf(
            table("notes","id",col("id",'S'),col("revision",'I'),col("title",'S'),col("text",'S'),col("updatedAt",'I')),
            table("note_revisions","noteId,revision",col("noteId",'S'),col("revision",'I'),col("title",'S'),col("text",'S'),col("committedAt",'I')),
            table("command_receipts","commandId",col("commandId",'S'),col("noteId",'S'),col("digest",'S'),col("committedRevision",'I')),
            table("notebook_workspace","noteId",col("noteId",'S'),col("world",'I'),col("paper",'I'),col("folder",'S'),col("tags",'S'),col("favorite",'I'),col("trashedAt",'I',true),col("centerX",'F'),col("centerY",'F'),col("zoom",'F'),col("revision",'I'),col("coverKey",'S'),col("selectedPageId",'S'),col("pinned",'I')),
            table("notebook_pages","id",col("id",'S'),col("notebookId",'S'),col("position",'I'),col("world",'I'),col("paper",'I'),col("centerX",'F'),col("centerY",'F'),col("zoom",'F'),col("createdAfterId",'S',true),col("trashedAt",'I',true)),
            table("ink_pages","noteId",col("noteId",'S'),col("revision",'I')),
            table("ink_strokes","id",col("id",'S'),col("noteId",'S'),col("payload",'B'),col("pointCount",'I'),col("visible",'I'),col("createdRevision",'I')),
            table("ink_receipts","commandId",col("commandId",'S'),col("noteId",'S'),col("digest",'S'),col("committedRevision",'I'),col("strokeIds",'S'),col("visible",'I')),
            table("ink_cuts","id",col("id",'S'),col("noteId",'S'),col("payload",'B'),col("strokeIds",'S'),col("visible",'I'),col("createdRevision",'I')),
            table("page_search_text","pageId",col("pageId",'S'),col("inkRevision",'I'),col("text",'S'),col("method",'S')),
            table("page_insert_receipts","commandId",col("commandId",'S'),col("notebookId",'S'),col("digest",'S'),col("pageIds",'S')),
            table("library_content_receipts","commandId",col("commandId",'S'),col("kind",'S'),col("digest",'S'),col("noteId",'S')),
            table("page_edit_receipts","commandId",col("commandId",'S'),col("notebookId",'S'),col("digest",'S'),col("kind",'S'),col("pageId",'S'),col("resultPageId",'S')),
            table("study_cards","id",col("id",'S'),col("notebookId",'S'),col("revision",'I'),col("title",'S'),col("body",'S'),col("trashedAt",'I',true)),
            table("study_card_revisions","cardId,revision",col("cardId",'S'),col("revision",'I'),col("title",'S'),col("body",'S'),col("trashedAt",'I',true)),
            table("study_sources","cardId",col("cardId",'S'),col("pageId",'S'),col("inkRevision",'I'),col("left",'F'),col("top",'F'),col("right",'F'),col("bottom",'F'),col("strokeIds",'S'),col("snapshot",'B')),
            table("study_nodes","id",col("id",'S'),col("notebookId",'S'),col("cardId",'S'),col("parentId",'S',true),col("x",'F'),col("y",'F'),col("revision",'I'),col("removed",'I')),
            table("study_receipts","id",col("id",'S'),col("notebookId",'S'),col("digest",'S'),col("resultId",'S')),
            table("knowledge_records","id",col("id",'S'),col("notebookId",'S'),col("revision",'I'),col("payload",'B'),col("removed",'I')),
            table("knowledge_revisions","id,revision",col("id",'S'),col("revision",'I'),col("notebookId",'S'),col("payload",'B'),col("removed",'I')),
            table("knowledge_receipts","operationId",col("operationId",'S'),col("notebookId",'S'),col("digest",'S'),col("resultId",'S'))
        )
        val SCHEMA_V8=SCHEMA.take(18)
        val SCHEMA_V7=SCHEMA.take(13)
        val SCHEMA_V6=SCHEMA.take(12).mapIndexed{i,t->if(i==4)t.copy(columns=t.columns.dropLast(1))else t}
        private val OWNERS=listOf("id","noteId","noteId","noteId","notebookId","@ink","@ink","@ink","@ink","@search","notebookId","noteId","notebookId","notebookId","@cards","@cards","notebookId","notebookId","notebookId","notebookId","notebookId")
        private fun count(sql:SupportSQLiteDatabase,table:String)=sql.query("SELECT COUNT(*) FROM `$table`").use{it.moveToFirst();it.getLong(0)}
        private fun insert(sql:SupportSQLiteDatabase,table:Int,row:List<Any?>){
            val t=SCHEMA[table]
            sql.execSQL("INSERT INTO `${t.name}` ("+t.columns.joinToString(","){"`${it.name}`"}+") VALUES ("+row.joinToString(","){"?"}+")",row.toTypedArray())
        }
        private fun fingerprint(sql:SupportSQLiteDatabase,ids:List<String>?=null):String =
            LibraryArchive.write(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,o:Int,n:Int){}},SCHEMA,SqlRows(sql,ids),0).sha256
        private fun checkSchema(sql:SupportSQLiteDatabase){
            val names=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name NOT IN ('room_master_table','android_metadata')").use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
            require(names==SCHEMA.map{it.name}.toSet()){"BACKUP_SCHEMA_COVERAGE_CHANGED"}
            for(t in SCHEMA){val cols=sql.query("PRAGMA table_info(`${t.name}`)").use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
                require(cols==t.columns.map{it.name}){"BACKUP_SCHEMA_COLUMNS_CHANGED"}}
        }
    }
}
