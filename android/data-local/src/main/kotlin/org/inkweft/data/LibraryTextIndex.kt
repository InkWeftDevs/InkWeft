// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*

/** Rebuildable FTS4 cache. Reads use authoritative pending rows while this cache catches up. */
internal object LibraryTextIndex {
    val tables=setOf("library_search_docs","library_search_pending","library_search_fts",
        "library_search_fts_content","library_search_fts_segments","library_search_fts_segdir",
        "library_search_fts_docsize","library_search_fts_stat")
    fun install(sql:SupportSQLiteDatabase){
        val own=!sql.inTransaction();if(own)sql.beginTransaction()
        try{
            val exists=sql.query("SELECT 1 FROM sqlite_master WHERE name='library_search_fts'").use{it.moveToFirst()}
            sql.execSQL("CREATE TABLE IF NOT EXISTS library_search_docs (key INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,kind INTEGER NOT NULL,target TEXT NOT NULL,UNIQUE(kind,target))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS library_search_pending (kind INTEGER NOT NULL,target TEXT NOT NULL,PRIMARY KEY(kind,target))")
            sql.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS library_search_fts USING fts4(terms,tokenize=simple)")
            for((kind,table,column) in listOf(Triple(0,"page_search_text","pageId"),Triple(1,"study_cards","id"))){
                // Room's outer OR ABORT overrides a trigger's OR IGNORE. Select only a
                // missing key, so an unchanged ID can enqueue twice without a conflict.
                fun enqueue(row:String)="INSERT INTO library_search_pending SELECT $kind,$row.$column WHERE NOT EXISTS (SELECT 1 FROM library_search_pending WHERE kind=$kind AND target=$row.$column); "
                for(event in listOf("insert","update","delete"))sql.execSQL("DROP TRIGGER IF EXISTS library_text_${kind}_$event")
                sql.execSQL("CREATE TRIGGER library_text_${kind}_insert AFTER INSERT ON $table BEGIN ${enqueue("NEW")} END")
                sql.execSQL("CREATE TRIGGER library_text_${kind}_update AFTER UPDATE ON $table BEGIN ${enqueue("OLD")}${enqueue("NEW")} END")
                sql.execSQL("CREATE TRIGGER library_text_${kind}_delete AFTER DELETE ON $table BEGIN ${enqueue("OLD")} END")
            }
            if(!exists){
                sql.execSQL("INSERT OR IGNORE INTO library_search_pending SELECT 0,pageId FROM page_search_text")
                sql.execSQL("INSERT OR IGNORE INTO library_search_pending SELECT 1,id FROM study_cards")
            }
            if(own)sql.setTransactionSuccessful()
        }finally{if(own)sql.endTransaction()}
    }
    suspend fun build(db:NoteDatabase,onFirstBatchStep:((String)->Unit)?=null,
        onBatchStep:((String)->Unit)?=null,onBatch:suspend(Int)->Unit={}){
        var firstBatch=true
        while(true){
            val firstStep=if(firstBatch)onFirstBatchStep else null
            val step:((String)->Unit)?=if(onBatchStep==null)firstStep
                else {{name:String->onBatchStep(name);firstStep?.invoke(name)}}
            step?.invoke("preparing-batch")
            // Ordinary read queries and CPU work do not enter Room's author-write queue.
            val prepared=prepare(db.openHelper.readableDatabase,step)
            if(prepared.isEmpty())return
            currentCoroutineContext().ensureActive()
            step?.invoke("batch-precomputed")
            step?.invoke("waiting-writer")
            val rows=db.withTransaction{
                step?.invoke("writer-acquired")
                commit(db.openHelper.writableDatabase,prepared,step)
            }
            step?.invoke("batch-committed");firstBatch=false
            if(rows>0)onBatch(rows)
            // A nonempty prepared batch may all be stale. Only an empty pending read ends
            // the build; retain changed sources for the next attempt (or another builder).
            delay(10)
        }
    }
    private data class PreparedSource(val kind:Int,val target:String,val value:Pair<String,String>?,val terms:String?)
    private fun source(sql:SupportSQLiteDatabase,kind:Int,target:String):Pair<String,String>?=
        sql.query(if(kind==0)"SELECT '',text FROM page_search_text WHERE pageId=?" else "SELECT title,body FROM study_cards WHERE id=?",arrayOf(target)).use{c->
            if(c.moveToFirst())c.getString(0) to c.getString(1)else null
        }
    private suspend fun prepare(sql:SupportSQLiteDatabase,step:((String)->Unit)?):List<PreparedSource>{
        val context=currentCoroutineContext();context.ensureActive()
        val pending=sql.query("SELECT kind,target FROM library_search_pending ORDER BY kind,target LIMIT 64").use{c->
            buildList{while(c.moveToNext())add(c.getInt(0) to c.getString(1))}
        }
        val prepared=ArrayList<PreparedSource>();var bytes=0L
        for((kind,target) in pending){
            context.ensureActive()
            val value=source(sql,kind,target)
            if(prepared.isEmpty())step?.invoke("first-source-read")
            val size=value?.let{(it.first.length.toLong()+it.second.length)*2}?:0L
            // This is the existing raw UTF-16 budget, not a heap/FTS-terms size limit.
            // An oversized first source must still make progress on its own.
            if(prepared.isNotEmpty()&&bytes+size>1_000_000)break
            val terms=value?.let{
                if(prepared.isEmpty())step?.invoke("first-tokenizing")
                LibrarySearchTerms.document(it.first,it.second){context.ensureActive()}.also{
                    if(prepared.isEmpty())step?.invoke("first-tokenized")
                }
            }
            context.ensureActive()
            prepared+=PreparedSource(kind,target,value,terms);bytes+=size
        }
        return prepared
    }
    private suspend fun commit(sql:SupportSQLiteDatabase,prepared:List<PreparedSource>,step:((String)->Unit)?):Int{
        var rows=0;val context=currentCoroutineContext()
        for((kind,target,value,terms) in prepared){
            context.ensureActive()
            val pending=sql.query("SELECT 1 FROM library_search_pending WHERE kind=? AND target=?",arrayOf<Any>(kind,target)).use{it.moveToFirst()}
            // Pending is a deduplicated key, not a version. A source write can leave the
            // same pending row in place; compare its exact nullable contents under the
            // writer before changing FTS or clearing the row. Later writes enqueue again.
            if(!pending||source(sql,kind,target)!=value)continue
            if(rows==0)step?.invoke("first-source-checked")
            val key=sql.query("SELECT key FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target)).use{if(it.moveToFirst())it.getLong(0)else null}
            if(key!=null)sql.execSQL("DELETE FROM library_search_fts WHERE docid=?",arrayOf(key))
            if(value==null){
                sql.execSQL("DELETE FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target))
            }else{
                sql.execSQL("INSERT OR IGNORE INTO library_search_docs(kind,target) VALUES(?,?)",arrayOf<Any>(kind,target))
                val doc=key?:sql.query("SELECT key FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target)).use{check(it.moveToFirst());it.getLong(0)}
                sql.execSQL("INSERT INTO library_search_fts(docid,terms) VALUES(?,?)",arrayOf<Any>(doc,checkNotNull(terms)))
                if(rows==0)step?.invoke("first-fts-written")
            }
            context.ensureActive()
            sql.execSQL("DELETE FROM library_search_pending WHERE kind=? AND target=?",arrayOf<Any>(kind,target))
            rows++
        }
        step?.invoke("batch-written")
        context.ensureActive()
        return rows
    }
}
