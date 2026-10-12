// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*

data class HistoryStorageGroup(val label:String,val rows:Long,val payloadBytes:Long,val reason:String)
data class HistoryStorageReport(val groups:List<HistoryStorageGroup>,val detachedSources:Long,val detachedSourceBytes:Long,
    val detachedPreviews:Long,val detachedPreviewBytes:Long,val shadowProtected:Boolean,val collectionDeferred:Boolean=false) {
    val payloadBytes get()=groups.sumOf{it.payloadBytes}
    val reclaimableBytes get()=detachedSourceBytes+detachedPreviewBytes
}

/** One retention policy: receipts and all author history remain roots, including hidden/trash rows.
 * Keeping these roots also covers in-memory undo, SavedState and unconfirmed requests without
 * pretending that their process lifetime or a timestamp proves a record disposable. */
class HistoryStorage(private val db:NoteDatabase,private val phase:(String)->Unit={}) {
    suspend fun audit():HistoryStorageReport=run(false)
    suspend fun collectDetached():HistoryStorageReport=run(true)
    private data class Candidate(val sequence:Long,val kind:Int,val id:String,val revision:Long,val bytes:Long)
    private data class WriterVersion(val external:Long,val changes:Long)
    private fun writerVersion(sql:SupportSQLiteDatabase):WriterVersion {
        check(sql.inTransaction())
        val external=sql.query("PRAGMA data_version").use{it.moveToFirst();it.getLong(0)}
        // Requery transactions pin the primary connection. Its data_version detects other
        // connections; total_changes detects author writes through this Room connection.
        // Room 2.8.5 resets invalidated=1 rows after commit: each reset adds one change and
        // removes one pending row. Include pending rows so that reset alone is neutral.
        // Query failures (including a changed/missing Room log) abort collection, never
        // substitute zero or guess how many data_version changes were our own commits.
        val changes=sql.query("SELECT total_changes()+(SELECT COUNT(*) FROM room_table_modification_log WHERE invalidated=1)").use{it.moveToFirst();it.getLong(0)}
        return WriterVersion(external,changes)
    }
    private suspend fun run(collect:Boolean):HistoryStorageReport=withContext(Dispatchers.IO){coroutineScope{
        val active=currentCoroutineContext();val cancellation=android.os.CancellationSignal()
        val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{cancellation.cancel()}}
        try{SQLiteDatabase.openDatabase(db.openHelper.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY){
            throw android.database.sqlite.SQLiteDatabaseCorruptException("History audit corruption; author data retained")
        }.use{reader->
            val sql=object:SupportSQLiteDatabase by reader{
                override fun query(query:String)=reader.query(SimpleSQLiteQuery(query),cancellation)
                override fun query(query:String,bindArgs:Array<out Any?>)=reader.query(SimpleSQLiteQuery(query,bindArgs),cancellation)
            }
            var expected=if(collect)db.originalMaintenanceMutex.withLock{db.withTransaction{writerVersion(db.openHelper.writableDatabase)}}else null
            // Candidate identities live on the private connection, not in author storage or
            // an unbounded Kotlin list. The WAL read snapshot ends before any deletion.
            if(collect)sql.execSQL("CREATE TEMP TABLE history_candidates(sequence INTEGER PRIMARY KEY,kind INTEGER NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,bytes INTEGER NOT NULL)")
            reader.beginTransactionDeferred()
            val report:HistoryStorageReport
            try{
                report=scan(sql,collect)
                reader.setTransactionSuccessful() // Persist only this connection's TEMP candidates.
            }finally{reader.endTransaction()}
            if(!collect)return@use report
            phase("after-scan")
            var sources=0L;var sourceBytes=0L;var previews=0L;var previewBytes=0L;var sequence=0L;var changed=false
            while(true){
                active.ensureActive()
                val candidates=sql.query("SELECT sequence,kind,id,revision,bytes FROM history_candidates WHERE sequence>? ORDER BY sequence LIMIT 256",arrayOf(sequence)).use{c->
                    buildList{while(c.moveToNext())add(Candidate(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3),c.getLong(4)))}
                }
                val committed=db.originalMaintenanceMutex.withLock{db.withTransaction{
                    val writer=db.openHelper.writableDatabase
                    if(writerVersion(writer)!=expected)return@withTransaction false
                    for(candidate in candidates){
                        active.ensureActive()
                        if(candidate.kind==0)writer.execSQL("DELETE FROM study_source_revisions WHERE sourceId=? AND revision=?",arrayOf<Any>(candidate.id,candidate.revision))
                        else writer.execSQL("DELETE FROM object_previews WHERE digest=?",arrayOf(candidate.id))
                    }
                    if(candidates.isNotEmpty())phase("before-batch-commit")
                    expected=writerVersion(writer)
                    true
                }}
                if(!committed){changed=true;break}
                if(candidates.isEmpty())break
                for(candidate in candidates){
                    if(candidate.kind==0){sources++;sourceBytes+=candidate.bytes}else{previews++;previewBytes+=candidate.bytes}
                }
                sequence=candidates.last().sequence
                phase("after-batch")
                yield()
            }
            report.copy(detachedSources=sources,detachedSourceBytes=sourceBytes,detachedPreviews=previews,
                detachedPreviewBytes=previewBytes,collectionDeferred=changed)
        }}catch(failure:Exception){active.ensureActive();throw failure}finally{watcher.cancel()}
    }}
    private suspend fun scan(sql:SupportSQLiteDatabase,collect:Boolean):HistoryStorageReport {
            val active=currentCoroutineContext()
            fun count(query:String)=sql.query(query).use{it.moveToFirst();it.getLong(0)}
            fun stats(table:String,bytes:String,where:String="1")=sql.query("SELECT COUNT(*),COALESCE(SUM($bytes),0) FROM $table WHERE $where").use{it.moveToFirst();it.getLong(0) to it.getLong(1)}
            fun absent(query:String){require(count("SELECT COUNT(*) FROM ($query)")==0L){"HISTORY_REFERENCE_UNAVAILABLE"}}
            // Derived roots contain only identities; source geometry is never decoded here.
            sql.execSQL("CREATE TEMP TABLE history_source_roots (sourceId TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(sourceId,revision))")
            try {
                fun retain(refs:List<StudySourceVersionRef>){refs.forEach{ref->
                    active.ensureActive()
                    sql.execSQL("INSERT INTO history_source_roots SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM history_source_roots WHERE sourceId=? AND revision=?)",arrayOf<Any>(ref.sourceId,ref.revision,ref.sourceId,ref.revision))
                }}
                sql.query("SELECT sourceRefs FROM study_card_source_sets").use{c->while(c.moveToNext()){active.ensureActive();retain(StudySourceRefs.decode(c.getString(0)))}}
                sql.query("SELECT length(payload),payload FROM card_transform_operations").use{c->while(c.moveToNext()){
                    active.ensureActive();require(c.getLong(0)<=CardTransformCodec.MAX_BYTES)
                    val plan=CardTransformCodec.decode(c.getBlob(1));plan.inputs.forEach{retain(it.sources)};plan.targets.forEach{retain(it.sources)}
                }}
                sql.query("SELECT length(payload),payload FROM recall_question_revisions").use{c->while(c.moveToNext()){
                    active.ensureActive();require(c.getLong(0)<=RecallLimits.MAX_SPEC_BYTES);retain(RecallCodec.spec(c.getBlob(1)).sourceRefs)
                }}
                phase("after-roots")
                absent("SELECT r.sourceId FROM history_source_roots r LEFT JOIN study_source_revisions s ON s.sourceId=r.sourceId AND s.revision=r.revision WHERE s.sourceId IS NULL")
                absent("SELECT r.commandId FROM command_receipts r LEFT JOIN note_revisions v ON v.noteId=r.noteId AND v.revision=r.committedRevision WHERE v.noteId IS NULL")
                // Experimental replica payloads have their own closure; conservatively retain sources.
                val shadowTables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('shadow_versions','shadow_bundles','shadow_outbox','shadow_inbox')").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
                val shadow=shadowTables.any{count("SELECT COUNT(*) FROM $it")>0}
                val sourceWhere=if(shadow)"0" else "NOT EXISTS(SELECT 1 FROM history_source_roots r WHERE r.sourceId=study_source_revisions.sourceId AND r.revision=study_source_revisions.revision) AND revision<(SELECT MAX(v.revision) FROM study_source_revisions v WHERE v.sourceId=study_source_revisions.sourceId) AND NOT EXISTS(SELECT 1 FROM study_sources s WHERE s.cardId=study_source_revisions.sourceId)"
                val previewWhere=if(shadow)"0" else "NOT EXISTS(SELECT 1 FROM page_object_records r WHERE r.previewHash=object_previews.digest)"
                val sources=stats("study_source_revisions","length(snapshot)",sourceWhere)
                val previews=stats("object_previews","length(payload)",previewWhere)
                val groups=buildList {
                    fun group(label:String,table:String,bytes:String,reason:String){active.ensureActive();val s=stats(table,bytes);add(HistoryStorageGroup(label,s.first,s.second,reason))}
                    group("笔迹记录","ink_strokes","length(payload)","撤销、来源和结果待核对请求")
                    group("擦除记录","ink_cuts","length(payload)","撤销与笔迹引用")
                    group("笔记版次","note_revisions","length(CAST(title AS BLOB))+length(CAST(text AS BLOB))","保存回执的原结果")
                    group("卡片版次","study_card_revisions","length(CAST(title AS BLOB))+length(CAST(body AS BLOB))","固定关联、导图、复习和撤销")
                    group("知识版次","knowledge_revisions","length(payload)","导图、关联及待核对操作")
                    group("来源副本","study_source_revisions","length(snapshot)","全部卡片版次、转换、复习及来源序号")
                    group("旧来源缓存","study_sources","length(snapshot)","历史来源的当前缓存")
                    group("题目版次","recall_question_revisions","length(payload)","复习尝试和原题回放")
                    group("卡片转换","card_transform_operations","length(payload)","逆操作与幂等回执")
                    group("对象预览","object_previews","length(payload)","当前对象引用")
                    var receiptRows=0L;var receiptBytes=0L
                    for(table in LibraryBackupRepository.SCHEMA.filter{it.name.endsWith("receipts")}){
                        val bytes=table.columns.filter{it.kind=='S'}.joinToString("+"){"COALESCE(length(CAST(`${it.name}` AS BLOB)),0)"}
                        active.ensureActive();val s=stats(table.name,bytes);receiptRows+=s.first;receiptBytes+=s.second
                    }
                    add(HistoryStorageGroup("操作回执",receiptRows,receiptBytes,"原操作身份与重试结果"))
                }
                phase("after-stats")
                if(collect){
                    active.ensureActive()
                    sql.execSQL("INSERT INTO history_candidates(kind,id,revision,bytes) SELECT 0,sourceId,revision,length(snapshot) FROM study_source_revisions WHERE $sourceWhere ORDER BY sourceId,revision")
                    active.ensureActive()
                    sql.execSQL("INSERT INTO history_candidates(kind,id,revision,bytes) SELECT 1,digest,0,length(payload) FROM object_previews WHERE $previewWhere ORDER BY digest")
                }
                return HistoryStorageReport(groups,sources.first,sources.second,previews.first,previews.second,shadow)
            }finally{sql.execSQL("DROP TABLE history_source_roots")}
    }
}
