// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import java.io.*
import java.util.UUID

/** Full, already-committed AUTHOR LIBRARY, not device settings or a live .db copy.
 * Restore never overwrites an existing identity. Entire batch or no live writes.
 * Only trusted, locally compiled DDL is used. The archive contains values only. */
class LibraryBackupRepository(private val context:Context,private val db:NoteDatabase,
    private val fault:(BackupFault)->Unit={}) {
    enum class BackupFault { BEFORE_RESTORE_COMMIT, AFTER_RESTORE_COMMIT, BEFORE_ISOLATED_COPY, AFTER_ISOLATED_COPY, BEFORE_SNAPSHOT_SYNC }
    enum class RestoreResult { RESTORED, ALREADY_PRESENT, IDENTITY_CONFLICT, SNAPSHOT_CAPACITY_EXCEEDED }
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
    suspend fun snapshot(maxArchiveBytes:Long=LibraryArchive.MAX_BYTES,additionalBytes:(Long)->Long={0}):Snapshot {
        require(maxArchiveBytes in 1..LibraryArchive.MAX_BYTES)
        var returned:Snapshot?=null
        try{return withContext(Dispatchers.IO){snapshotInIo(maxArchiveBytes,additionalBytes).also{returned=it}}}
        catch(t:Throwable){returned?.close();throw t}
    }
    private suspend fun snapshotInIo(maxArchiveBytes:Long,additionalBytes:(Long)->Long):Snapshot=db.originalMaintenanceMutex.withLock {
        // Originals are immutable. Exclude only their explicit GC/migration while the private
        // database reads them; ordinary author transactions continue throughout this operation.
        reserve();val path=File(scratch,"snapshot-${UUID.randomUUID()}.iwbackup")
        val root=File(scratch,"copy-${UUID.randomUUID()}");var stage:NoteDatabase?=null
        val ctx=currentCoroutineContext()
        fun preflight(sql:SupportSQLiteDatabase,copyBytes:Long){
            checkSchema(sql)
            val bytes=archiveBytes(sql){ctx.ensureActive()}
            require(bytes<=maxArchiveBytes){if(maxArchiveBytes<LibraryArchive.MAX_BYTES)"BACKUP_TOO_LARGE" else "BACKUP_SIZE_LIMIT"}
            val extra=additionalBytes(bytes);require(extra>=0)
            val required=Math.addExact(Math.addExact(bytes,extra),Math.addExact(copyBytes,32L*1024*1024))
            require(scratch.usableSpace>=required){"BACKUP_LOW_SPACE"}
        }
        try {
            val live=db.openHelper.writableDatabase
            // A separate read-only connection sees committed WAL pages. Never copy a live .db
            // or use Room's write transaction for a full-library traversal.
            SQLiteDatabase.openDatabase(live.path,null,SQLiteDatabase.OPEN_READONLY){
                throw SQLiteDatabaseCorruptException("Database corruption reported; original files retained")
            }.use{source->
                source.beginTransactionDeferred()
                try{
                    val pages=source.query("PRAGMA page_count").use{it.moveToFirst();it.getLong(0)}
                    preflight(source,Math.multiplyExact(Math.multiplyExact(pages,source.pageSize),2L))
                }finally{source.endTransaction()}
                ctx.ensureActive();fault(BackupFault.BEFORE_ISOLATED_COPY)
                check(root.mkdir());val copy=File(root,"candidate.db")
                val cancellation=android.os.CancellationSignal()
                // A child cancellation handler runs even while SQLite occupies this IO thread.
                coroutineScope {
                    val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED){
                        try{awaitCancellation()}finally{cancellation.cancel()}
                    }
                    try{source.query(SimpleSQLiteQuery("VACUUM INTO ?",arrayOf(copy.absolutePath)),cancellation).use{it.moveToNext()}}
                    finally{watcher.cancel()}
                }
                ctx.ensureActive()
                stage=NoteDatabase.open(context,copy.absolutePath).also{it.originalRoot=db.originalRoot}
            }
            fault(BackupFault.AFTER_ISOLATED_COPY)
            val isolated=checkNotNull(stage)
            // Recheck the captured generation in case edits grew the library after preflight.
            preflight(isolated.openHelper.readableDatabase,0)
            validate(isolated)
            val summary=FileOutputStream(path).use{file->
                val buffer=BufferedOutputStream(file)
                val result=LibraryArchive.write(buffer,SCHEMA,SqlRows(isolated),System.currentTimeMillis()){ctx.ensureActive()}
                // The archive bytes are fixed; syncing this private file needs no live database lock.
                currentCoroutineContext().ensureActive()
                fault(BackupFault.BEFORE_SNAPSHOT_SYNC)
                buffer.flush();file.fd.sync();result
            }
            Snapshot(path,summary)
        }catch(t:Throwable){path.delete();throw t}
        finally{stage?.close();if(root.exists())root.deleteRecursively()}
    }

    /** Exact typed-stream length using SQL length metadata, without reading BLOBs into memory.
     * Original bytes are emitted once per source row, including shared hashes across notebooks. */
    internal fun archiveBytes(sql:SupportSQLiteDatabase,active:()->Unit={}):Long {
        var bytes=84L+SCHEMA.size*8L // header, table counts and checksum
        for((index,table) in SCHEMA.withIndex()){
            active()
            val query=if(originalChunks(index)){
                val document=table.name=="document_chunks"
                val owner=if(document)"document_sources" else "image_sources"
                val key=if(document)"length(CAST(id AS BLOB))" else "length(CAST(notebookId AS BLOB))+5+length(CAST(digest AS BLOB))"
                "SELECT COALESCE(SUM(byteCount+((byteCount+${OriginalStorage.CHUNK-1})/${OriginalStorage.CHUNK})*(19+$key)),0) FROM $owner"
            }else{
                val row=table.columns.joinToString("+"){column->
                    val name="`${column.name}`"
                    val present=when(column.kind){'I','F'->"9";else->"5+length(CAST($name AS BLOB))"}
                    "CASE WHEN $name IS NULL THEN 1 ELSE $present END"
                }
                "SELECT COALESCE(SUM($row),0) FROM `${table.name}`"
            }
            bytes=Math.addExact(bytes,sql.query(query).use{it.moveToFirst();it.getLong(0)})
        }
        return bytes
    }
    /** Caller owns the input. Only staging is touched before digest, FK and semantic validation. */
    suspend fun inspect(input:InputStream):Preview {
        var returned:Preview?=null
        try{return withContext(Dispatchers.IO){inspectInIo(input).also{returned=it}}}
        catch(t:Throwable){returned?.close();throw t}
    }
    private suspend fun inspectInIo(input:InputStream):Preview {
        reserve();val root=File(scratch,"stage-${UUID.randomUUID()}").apply{check(mkdirs())}
        val stage=NoteDatabase.open(context,File(root,"candidate.db").absolutePath)
        try {
            val parsed=stage.withTransaction {
                val sql=stage.openHelper.writableDatabase;checkSchema(sql);val ctx=currentCoroutineContext()
                var count=0
                val parsed=OriginalArchiveRows.Import(stage){ctx.ensureActive()}.use{originals->LibraryArchive.read(input,SCHEMA,{table,row->
                    if(++count%128==0)reserve()
                    // Schema6 has no tombstone column; promote to live/default null.
                    val promoted=if(table==4&&row.size==SCHEMA[table].columns.size-1)row+listOf(null)else row
                    if(originalChunks(table))originals.accept(SCHEMA[table].name=="document_chunks",promoted)
                    else if(table==22&&promoted.size==3){
                        insert(sql,table,promoted.take(2));PageObjectStore.promote(sql,promoted[0] as String,promoted[2] as ByteArray)
                    }else insert(sql,table,promoted)
                },legacySchema=SCHEMA_V6,otherLegacySchemas=listOf(SCHEMA_V7,SCHEMA_V8,SCHEMA_V9,SCHEMA_V10,SCHEMA_V11,SCHEMA_V12,SCHEMA_V13,SCHEMA_V14,SCHEMA_V15,SCHEMA_V16,SCHEMA_V17)){ctx.ensureActive()}.also{originals.finish()}}
                if(parsed.rows.size<32)StudySourceVersions.promoteLegacy(sql)
                if(parsed.rows.size<SCHEMA.size)PageLayerStore.promote(sql)
                parsed
            }
            validate(stage)
            val sql=stage.openHelper.writableDatabase
            val notes=count(sql,"notes").toInt();val pages=count(sql,"notebook_pages").toInt()
            val trash=sql.query("SELECT COUNT(*) FROM notebook_workspace WHERE trashedAt IS NOT NULL").use{it.moveToFirst();it.getInt(0)}
            val recycled=sql.query("SELECT COUNT(*) FROM notebook_pages WHERE trashedAt IS NOT NULL").use{it.moveToFirst();it.getInt(0)}
            return Preview(stage,root,parsed,notes,pages,trash,recycled,fingerprint(stage))
        }catch(t:Throwable){stage.close();root.deleteRecursively();throw t}
    }
    suspend fun restore(preview:Preview):RestoreResult=withContext(Dispatchers.IO){
        preview.requireOpen();reserve()
        val result=preview.stage.withTransaction {
            val source=preview.stage.openHelper.writableDatabase
            require(fingerprint(preview.stage)==preview.canonical){"BACKUP_PREVIEW_CHANGED"}
            val ids=source.query("SELECT id FROM notes ORDER BY id").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            db.withTransaction live@ {
                val target=db.openHelper.writableDatabase;checkSchema(target)
                if(ids.isEmpty())return@live RestoreResult.ALREADY_PRESENT
                val marks=ids.joinToString(","){"?"};val args=ids.toTypedArray<Any?>()
                val overlap=target.query("SELECT COUNT(*) FROM notes WHERE id IN ($marks)",args).use{it.moveToFirst();it.getInt(0)}
                if(overlap>0){
                    return@live if(overlap==ids.size && fingerprint(db,ids)==preview.canonical)
                        RestoreResult.ALREADY_PRESENT else RestoreResult.IDENTITY_CONFLICT
                }
                require(count(target,"notes")+preview.notes<=500 &&
                    count(target,"notebook_pages")+preview.pages<=20_000){"BACKUP_LIBRARY_BUDGET"}
                // All primary keys, including historical commands, must also be disjoint.
                for((index,t) in SCHEMA.withIndex()){
                    if(originalChunks(index))continue // Ownership metadata already detects all original identities.
                    SqlRows(preview.stage).visit(index){row->
                        val argsKey=t.keys.map{key->row[t.columns.indexOfFirst{it.name==key}]}.toTypedArray()
                        val where=t.keys.joinToString(" AND "){"`$it`=?"}
                        val collision=target.query("SELECT 1 FROM `${t.name}` WHERE $where LIMIT 1",argsKey).use{it.moveToFirst()}
                        if(collision&&t.name=="object_previews"){
                            val existing=target.query("SELECT payload FROM object_previews WHERE digest=?",argsKey).use{check(it.moveToFirst());it.getBlob(0)}
                            require(existing.contentEquals(row[1] as ByteArray)){"OBJECT_PREVIEW_CHANGED"}
                        }else require(!collision){"BACKUP_COMMAND_IDENTITY_CONFLICT"}
                    }
                }
                require(db.recall().answerInkBytes()+preview.stage.recall().answerInkBytes()<=RecallLimits.MAX_INK_LIBRARY_BYTES){"RECALL_ANSWER_LIBRARY_BUDGET"}
                require(db.covers().otherBytes("")+preview.stage.covers().otherBytes("")<=32_000_000){"COVER_LIBRARY_BUDGET"}
                if(db.study().snapshotBytes()+preview.stage.study().snapshotBytes()>StudyCapacity.MAX_SNAPSHOT_BYTES)
                    return@live RestoreResult.SNAPSHOT_CAPACITY_EXCEEDED
                val ctx=currentCoroutineContext()
                OriginalArchiveRows(preview.stage).copyTo(db){ctx.ensureActive()}
                SCHEMA.indices.filterNot(::originalChunks).forEach{index->SqlRows(preview.stage).visit(index){row->
                    ctx.ensureActive()
                    if(SCHEMA[index].name!="object_previews"||!target.query("SELECT 1 FROM object_previews WHERE digest=?",arrayOf(row[0])).use{it.moveToFirst()})insert(target,index,row)
                }}
                check(target.query("PRAGMA foreign_key_check").use{!it.moveToFirst()})
                check(fingerprint(db,ids)==preview.canonical){"BACKUP_RESTORE_MISMATCH"}
                fault(BackupFault.BEFORE_RESTORE_COMMIT)
                RestoreResult.RESTORED
            }
        }
        fault(BackupFault.AFTER_RESTORE_COMMIT)
        db.invalidationTracker.refreshVersionsAsync()
        result
    }
    internal suspend fun validate(stage:NoteDatabase){
        val sql=stage.openHelper.writableDatabase
        check(sql.query("PRAGMA integrity_check").use{it.moveToFirst()&&it.getString(0)=="ok"&&!it.moveToNext()})
        check(sql.query("PRAGMA foreign_key_check").use{!it.moveToFirst()})
        require(count(sql,"notes")<=500 && count(sql,"notebook_pages")<=20_000){"BACKUP_LIBRARY_BUDGET"}
        val documentCache=mutableMapOf<String,PdfDocumentSource>()
        val notes=sql.query("SELECT id FROM notes ORDER BY id").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(count(sql,"notebook_workspace")==notes.size.toLong())
        // Reject raw integers before Room narrows them to Int/Boolean. Otherwise
        // a corrupt 2^32 page ordinal can wrap to zero and look valid in Kotlin.
        fun noRows(query:String){require(sql.query(query).use{!it.moveToFirst()}){"BACKUP_REFERENCE_INVALID"}}
        noRows("SELECT 1 FROM notebook_pages WHERE world NOT IN (0,1) OR position NOT BETWEEN 0 AND 499 OR paper NOT BETWEEN 0 AND ${PaperStyle.entries.lastIndex}")
        noRows("SELECT 1 FROM notebook_workspace WHERE world NOT IN (0,1) OR favorite NOT IN (0,1) OR pinned NOT IN (0,1) OR paper NOT BETWEEN 0 AND ${PaperStyle.entries.lastIndex}")
        noRows("SELECT 1 FROM ink_receipts WHERE visible NOT IN (0,1)")
        noRows("SELECT 1 FROM page_objects WHERE revision<1")
        noRows("SELECT 1 FROM page_object_records WHERE position NOT BETWEEN 0 AND ${PageObjectCodec.MAX_RECORDS-1} OR length(payload) NOT BETWEEN 8 AND ${PageObjectCodec.MAX_BYTES}")
        noRows("SELECT 1 FROM object_previews p WHERE length(payload) NOT BETWEEN 5 AND ${PageObjectCodec.MAX_IMAGE} OR NOT EXISTS (SELECT 1 FROM page_object_records r WHERE r.previewHash=p.digest)")
        noRows("SELECT 1 FROM library_content_receipts WHERE kind NOT IN ('COPY','IMPORT','CREATE','RESOURCE','INK_GROUP')")
        noRows("SELECT 1 FROM notebook_pages WHERE trashedAt<0")
        noRows("SELECT 1 FROM study_nodes WHERE removed NOT IN (0,1)")
        require(stage.study().snapshotBytes()<=StudyCapacity.MAX_SNAPSHOT_BYTES){"STUDY_SNAPSHOT_BUDGET"}
        noRows("SELECT 1 FROM study_nodes n JOIN study_cards c ON c.id=n.cardId WHERE c.notebookId!=n.notebookId OR (c.trashedAt IS NOT NULL AND n.removed=0)")
        noRows("SELECT 1 FROM study_nodes n JOIN study_nodes p ON p.id=n.parentId WHERE n.notebookId!=p.notebookId")
        noRows("SELECT 1 FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE r.revision<1 OR r.revision>c.revision OR length(r.title)>120 OR length(r.body)>20000")
        noRows("SELECT 1 FROM study_cards c LEFT JOIN study_card_revisions r ON r.cardId=c.id AND r.revision=c.revision WHERE r.cardId IS NULL OR r.title!=c.title OR r.body!=c.body OR r.trashedAt IS NOT c.trashedAt")
        noRows("SELECT 1 FROM study_sources s JOIN study_cards c ON c.id=s.cardId JOIN notebook_pages p ON p.id=s.pageId WHERE c.notebookId!=p.notebookId")
        noRows("SELECT 1 FROM page_edit_receipts WHERE kind NOT IN ('MOVE','COPY','TRASH','RESTORE')")
        noRows("SELECT 1 FROM page_edit_receipts r LEFT JOIN notebook_pages p ON p.id=r.pageId LEFT JOIN notebook_pages q ON q.id=r.resultPageId WHERE p.id IS NULL OR q.id IS NULL OR p.notebookId!=r.notebookId OR q.notebookId!=r.notebookId")
        noRows("SELECT 1 FROM knowledge_records WHERE removed NOT IN (0,1) OR revision<1")
        noRows("SELECT 1 FROM knowledge_revisions r JOIN knowledge_records k ON k.id=r.id WHERE r.removed NOT IN (0,1) OR r.revision<1 OR r.revision>k.revision OR r.notebookId!=k.notebookId")
        require(sql.query("SELECT COALESCE(SUM(length(payload)),0) FROM notebook_covers").use{it.moveToFirst();it.getLong(0)}<=32_000_000){"COVER_LIBRARY_BUDGET"}
        sql.query("SELECT payload FROM notebook_covers").use{c->while(c.moveToNext())validateCoverPayload(c.getBlob(0))}
        noRows("SELECT 1 FROM notebook_workspace w LEFT JOIN notebook_covers c ON c.noteId=w.noteId WHERE w.coverKey='custom' AND c.noteId IS NULL")
        noRows("SELECT 1 FROM document_sources WHERE pageCount NOT BETWEEN 1 AND 500 OR byteCount NOT BETWEEN 8 AND ${PdfDocumentSource.MAX_BYTES}")
        noRows("SELECT 1 FROM document_pages p JOIN document_sources s ON s.id=p.documentId WHERE sourcePage<0 OR sourcePage>=s.pageCount")
        noRows("SELECT 1 FROM document_chunks WHERE position NOT BETWEEN 0 AND ${(PdfDocumentSource.MAX_BYTES+DocumentRepository.CHUNK-1)/DocumentRepository.CHUNK-1} OR length(payload) NOT BETWEEN 1 AND ${DocumentRepository.CHUNK}")
        noRows("SELECT 1 FROM image_sources WHERE byteCount NOT BETWEEN 1 AND ${ImageSource.MAX_BYTES}")
        noRows("SELECT 1 FROM image_chunks WHERE position NOT BETWEEN 0 AND 40 OR length(payload) NOT BETWEEN 1 AND 512000")
        val imageValidationContext=currentCoroutineContext()
        for(image in stage.images().all()){
            imageValidationContext.ensureActive()
            if(stage.originalStore.contains(image.digest))stage.originalStore.acquire(image.byteCount,image.digest){imageValidationContext.ensureActive()}.use{
                ImageSourceRepository.validateFile(it.file){imageValidationContext.ensureActive()}
            }else ImageSourceRepository.validate(ImageSourceRepository(stage).read(image.notebookId,image.digest),checkNotNull(stage.documentScratch)){imageValidationContext.ensureActive()}
        }
        noRows("SELECT 1 FROM study_card_source_sets WHERE complete NOT IN (0,1)")
        noRows("SELECT 1 FROM card_transform_operations WHERE undone NOT IN (0,1)")
        noRows("SELECT 1 FROM canvas_authoring WHERE kind NOT IN ('PAGE','MAP') OR revision<0 OR length(payload)<8")
        noRows("SELECT 1 FROM page_layer_memberships m LEFT JOIN canvas_authoring a ON a.kind='PAGE' AND a.scopeId=m.pageId WHERE a.scopeId IS NULL OR m.kind NOT IN ('INK','OBJECT')")
        noRows("SELECT 1 FROM authoring_receipts r LEFT JOIN canvas_authoring a ON a.kind=r.kind AND a.scopeId=r.scopeId WHERE a.scopeId IS NULL OR a.notebookId!=r.notebookId OR r.revision<1 OR r.revision>a.revision")
        require(count(sql,"canvas_authoring")<=25_000){"AUTHORING_LIBRARY_BUDGET"}
        val authoring=PageAuthoringRepository(stage)
        sql.query("SELECT kind,scopeId,notebookId,revision,payload FROM canvas_authoring ORDER BY kind,scopeId").use{c->while(c.moveToNext()){
            currentCoroutineContext().ensureActive()
            authoring.validateStored(PageAuthoringRow(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getBlob(4)))
        }}
        sql.query("SELECT commandId,digest FROM authoring_receipts").use{c->while(c.moveToNext()){UUID.fromString(c.getString(0));require(c.getString(1).matches(Regex("[0-9a-f]{64}")))}}
        RecallStudyRepository(stage).validateArchive()
        StudySourceVersions(stage).validateArchive()
        CardTransformRepository(stage).validateArchive()
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
            require(cards.size<=StudyCapacity.MAX_CARDS_PER_NOTEBOOK);StudyGraph.validate(nodes.map{it.model()})
            for(c in cards){
                UUID.fromString(c.id);require(c.revision>=1&&c.title.isNotBlank()&&c.title.length<=120&&c.body.length<=20000&&(c.trashedAt==null||c.trashedAt>=0))
                stage.study().source(c.id)?.let{source->
                    val ids=source.strokeIds.split(',').filter{it.isNotBlank()};val bounds=CanvasBounds(source.left,source.top,source.right,source.bottom)
                    require(source.snapshot.size<=StudyCapacity.MAX_SOURCE_BYTES);val snapshot=InkPageFile.decode(source.snapshot)
                    val regionPreview=snapshot.objects.singleOrNull()?.takeIf{it.kind==PageObjectKind.IMAGE&&snapshot.strokes.isEmpty()}?.let{java.util.Base64.getDecoder().decode(it.image)}
                    StudySourceDraft(source.pageId,source.inkRevision,bounds,ids,regionPreview)
                    if(regionPreview==null)require(snapshot.strokes.map{it.id}.toSet()==ids.toSet())
                    for(strokeId in ids)require(stage.ink().stroke(strokeId)?.noteId==source.pageId)
                    if(regionPreview==null)require(snapshot.world==stage.pages().get(source.pageId)?.world)
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
                val inkRevision=InkRepository(stage).validateStored(p.id);require(inkRevision>=0)
                DocumentRepository(stage).read(p.id,documentCache)
                val objects=PageObjectRepository(stage).read(p.id).objects
                val refs=objects.flatMap{it.sourceStrokeIds};require(refs.distinct().size==refs.size);val owned=stage.ink().strokeIds(p.id).toSet();require(refs.all{it in owned})
                MapEmbedRepository(stage).validateReferences(p.notebookId,objects)
                ImageSourceRepository(stage).validateReferences(p.notebookId,objects)
                PageObjectRepository.validateBounds(objects,p.world);PageObjectRepository.validateImages(objects)
                stage.pages().search(p.id)?.let{s->require(s.inkRevision in 0..inkRevision&&s.text.length<=20_000&&s.method in listOf("MANUAL","OCR"))}
            }
        }
        val validationContext=currentCoroutineContext()
        documentCache.values.forEach{document->
            if(stage.originalStore.contains(document.sha256))stage.originalStore.acquire(document.size,document.sha256){validationContext.ensureActive()}.use{
                DocumentRepository.validatePdfFile(it.file,document.pages){validationContext.ensureActive()}
            }else DocumentRepository.validatePdf(document,checkNotNull(stage.documentScratch)){validationContext.ensureActive()}
        }
        noRows("SELECT 1 FROM document_sources s WHERE NOT EXISTS (SELECT 1 FROM document_pages p WHERE p.documentId=s.id)")
        noRows("SELECT 1 FROM note_revisions r LEFT JOIN notes n ON n.id=r.noteId WHERE n.id IS NULL OR r.revision<1 OR r.revision>n.revision OR length(r.title)>120 OR length(r.text)>100000")
        noRows("SELECT 1 FROM command_receipts r LEFT JOIN notes n ON n.id=r.noteId WHERE n.id IS NULL OR r.committedRevision<1 OR r.committedRevision>n.revision")
        noRows("SELECT 1 FROM ink_receipts r LEFT JOIN ink_pages p ON p.noteId=r.noteId WHERE p.noteId IS NULL OR r.committedRevision<1 OR r.committedRevision>p.revision")
        noRows("SELECT 1 FROM ink_strokes s LEFT JOIN ink_pages p ON p.noteId=s.noteId WHERE p.noteId IS NULL OR s.createdRevision<1 OR s.createdRevision>p.revision OR s.visible NOT IN (0,1)")
        noRows("SELECT 1 FROM object_receipts r LEFT JOIN page_objects p ON r.pageId=p.pageId WHERE p.pageId IS NULL OR r.revision<1 OR r.revision>p.revision")
        sql.query("SELECT commandId,digest FROM object_receipts").use{c->while(c.moveToNext()){UUID.fromString(c.getString(0));require(c.getString(1).matches(Regex("[0-9a-f]{64}")))}}
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
    private class SqlRows(val owner:NoteDatabase,val ids:List<String>?=null):LibraryArchive.Rows {
        private val db get()=owner.openHelper.readableDatabase
        private val originals=OriginalArchiveRows(owner,ids)
        private fun where(table:Int):String {
            if(ids==null)return ""
            val marks=ids.joinToString(","){"?"}
            val col=OWNERS[table]
            return if(col=="@ink")" WHERE noteId IN (SELECT id FROM notebook_pages WHERE notebookId IN ($marks))"
                else if(col=="@search")" WHERE pageId IN (SELECT id FROM notebook_pages WHERE notebookId IN ($marks))"
                else if(col=="@documents")" WHERE documentId IN (SELECT id FROM document_sources WHERE notebookId IN ($marks))"
                else if(col=="@cards")" WHERE cardId IN (SELECT id FROM study_cards WHERE notebookId IN ($marks))"
                else if(col=="@previews")" WHERE digest IN (SELECT previewHash FROM page_object_records WHERE pageId IN (SELECT id FROM notebook_pages WHERE notebookId IN ($marks)))"
                else " WHERE `$col` IN ($marks)"
        }
        private fun query(sql:String)=if(ids==null)db.query(sql)else db.query(sql,ids.toTypedArray<Any?>())
        override fun count(table:Int)=if(originalChunks(table))originals.count(SCHEMA[table].name=="document_chunks")
            else query("SELECT COUNT(*) FROM `${SCHEMA[table].name}`"+where(table)).use{it.moveToFirst();it.getLong(0)}
        override fun visit(table:Int,consume:(List<Any?>)->Unit){
            if(originalChunks(table)){originals.visit(SCHEMA[table].name=="document_chunks",consume=consume);return}
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
        // Current author schema; older supported archives promote page objects into records.
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
            table("knowledge_receipts","operationId",col("operationId",'S'),col("notebookId",'S'),col("digest",'S'),col("resultId",'S')),
            table("notebook_covers","noteId",col("noteId",'S'),col("payload",'B')),
            table("page_objects","pageId",col("pageId",'S'),col("revision",'I')),
            table("object_receipts","commandId",col("commandId",'S'),col("pageId",'S'),col("digest",'S'),col("revision",'I')),
            table("document_sources","id",col("id",'S'),col("notebookId",'S'),col("digest",'S'),col("pageCount",'I'),col("byteCount",'I')),
            table("document_chunks","documentId,position",col("documentId",'S'),col("position",'I'),col("payload",'B')),
            table("document_pages","pageId",col("pageId",'S'),col("documentId",'S'),col("sourcePage",'I')),
            table("image_sources","notebookId,digest",col("notebookId",'S'),col("digest",'S'),col("byteCount",'I')),
            table("image_chunks","notebookId,digest,position",col("notebookId",'S'),col("digest",'S'),col("position",'I'),col("payload",'B')),
            table("study_source_revisions","sourceId,revision",col("sourceId",'S'),col("revision",'I'),col("notebookId",'S'),col("pageId",'S'),col("inkRevision",'I'),col("left",'F'),col("top",'F'),col("right",'F'),col("bottom",'F'),col("strokeIds",'S'),col("snapshot",'B')),
            table("study_card_source_sets","cardId,cardRevision",col("cardId",'S'),col("cardRevision",'I'),col("sourceRefs",'S'),col("complete",'I')),
            table("card_transform_operations","operationId",col("operationId",'S'),col("notebookId",'S'),col("digest",'S'),col("payload",'B'),col("afterFingerprint",'S'),col("revision",'I'),col("undone",'I'),col("undoOperationId",'S',true),col("undoDigest",'S',true)),
            table("canvas_authoring","kind,scopeId",col("kind",'S'),col("scopeId",'S'),col("notebookId",'S'),col("revision",'I'),col("payload",'B')),
            table("authoring_receipts","commandId",col("commandId",'S'),col("notebookId",'S'),col("kind",'S'),col("scopeId",'S'),col("digest",'S'),col("revision",'I'))
        )+RecallStorage.SCHEMA+listOf(
            table("object_previews","digest",col("digest",'S'),col("payload",'B')),
            table("page_object_records","pageId,id",col("pageId",'S'),col("id",'S'),col("position",'I'),col("payload",'B'),col("previewHash",'S',true)),
            table("page_layer_memberships","pageId,kind,contentId",col("pageId",'S'),col("kind",'S'),col("contentId",'S'),col("layerId",'S',true))
        )
        val SCHEMA_V17=SCHEMA.dropLast(1)
        val SCHEMA_V16=SCHEMA_V17.dropLast(2).map{if(it.name=="page_objects")it.copy(columns=it.columns+col("payload",'B'))else it}
        val SCHEMA_V15=SCHEMA_V16.take(34)
        val SCHEMA_V14=SCHEMA_V16.take(32)
        val SCHEMA_V13=SCHEMA_V16.take(29)
        val SCHEMA_V12=SCHEMA_V16.take(27)
        val SCHEMA_V11=SCHEMA_V16.take(24)
        val SCHEMA_V10=SCHEMA.take(22)
        val SCHEMA_V9=SCHEMA.take(21)
        val SCHEMA_V8=SCHEMA.take(18)
        val SCHEMA_V7=SCHEMA.take(13)
        val SCHEMA_V6=SCHEMA.take(12).mapIndexed{i,t->if(i==4)t.copy(columns=t.columns.dropLast(1))else t}
        private val OWNERS=listOf("id","noteId","noteId","noteId","notebookId","@ink","@ink","@ink","@ink","@search","notebookId","noteId","notebookId","notebookId","@cards","@cards","notebookId","notebookId","notebookId","notebookId","notebookId","noteId","@search","@search","notebookId","@documents","@search","notebookId","notebookId","notebookId","@cards","notebookId","notebookId","notebookId")+List(RecallStorage.SCHEMA.size){"notebookId"}+listOf("@previews","@search","@search")
        private fun count(sql:SupportSQLiteDatabase,table:String)=sql.query("SELECT COUNT(*) FROM `$table`").use{it.moveToFirst();it.getLong(0)}
        private fun insert(sql:SupportSQLiteDatabase,table:Int,row:List<Any?>){
            val t=SCHEMA[table]
            sql.execSQL("INSERT INTO `${t.name}` ("+t.columns.joinToString(","){"`${it.name}`"}+") VALUES ("+row.joinToString(","){"?"}+")",row.toTypedArray())
        }
        private fun originalChunks(table:Int)=SCHEMA[table].name in setOf("document_chunks","image_chunks")
        private fun fingerprint(db:NoteDatabase,ids:List<String>?=null):String =
            LibraryArchive.write(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,o:Int,n:Int){}},SCHEMA,SqlRows(db,ids),0).sha256
        private fun checkSchema(sql:SupportSQLiteDatabase){
            val names=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name NOT IN ('room_master_table','android_metadata')").use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
            require(names==SCHEMA.map{it.name}.toSet()+SpatialIndex.tables+PageLayerEditorStore.TABLE){"BACKUP_SCHEMA_COVERAGE_CHANGED"}
            for(t in SCHEMA){
                val cols=sql.query("PRAGMA table_info(`${t.name}`)").use{c->buildList{while(c.moveToNext()){
                    // Compare SQLite affinity, not spelling (e.g. VARCHAR is still text).
                    // Reject drift before cursor getters can coerce or truncate author values.
                    val type=c.getString(2).uppercase()
                    val kind=when {
                        "INT" in type->'I'
                        "CHAR" in type||"CLOB" in type||"TEXT" in type->'S'
                        type.isEmpty()||"BLOB" in type->'B'
                        "REAL" in type||"FLOA" in type||"DOUB" in type->'F'
                        else->'?' // NUMERIC affinity has no lossless archive column equivalent.
                    }
                    add(col(c.getString(1),kind,c.getInt(3)==0) to c.getInt(5))
                }}}
                require(cols.map{it.first.name}==t.columns.map{it.name}){"BACKUP_SCHEMA_COLUMNS_CHANGED"}
                require(cols.map{it.first.kind}==t.columns.map{it.kind}){"BACKUP_SCHEMA_TYPES_CHANGED"}
                require(cols.map{it.first.nullable}==t.columns.map{it.nullable}){"BACKUP_SCHEMA_NULLABILITY_CHANGED"}
                require(cols.filter{it.second>0}.sortedBy{it.second}.map{it.first.name}==t.keys){"BACKUP_SCHEMA_KEYS_CHANGED"}
            }
        }
    }
}
