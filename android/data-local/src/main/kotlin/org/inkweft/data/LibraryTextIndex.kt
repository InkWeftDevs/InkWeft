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
    suspend fun build(db:NoteDatabase,onBatch:suspend(Int)->Unit={}){
        while(true){val rows=db.withTransaction{prepare(db.openHelper.writableDatabase)}
            if(rows==0)return
            onBatch(rows);delay(10) // Release the writer before author work and another bounded batch.
        }
    }
    private suspend fun prepare(sql:SupportSQLiteDatabase):Int{
        val pending=sql.query("SELECT kind,target FROM library_search_pending ORDER BY kind,target LIMIT 64").use{c->
            buildList{while(c.moveToNext())add(c.getInt(0) to c.getString(1))}
        }
        var bytes=0L;var rows=0;val context=currentCoroutineContext()
        for((kind,target) in pending){
            context.ensureActive()
            val value=sql.query(if(kind==0)"SELECT '',text FROM page_search_text WHERE pageId=?" else "SELECT title,body FROM study_cards WHERE id=?",arrayOf(target)).use{c->
                if(c.moveToFirst())c.getString(0) to c.getString(1)else null
            }
            val size=value?.let{(it.first.length.toLong()+it.second.length)*2}?:0L
            if(rows>0&&bytes+size>1_000_000)break
            val key=sql.query("SELECT key FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target)).use{if(it.moveToFirst())it.getLong(0)else null}
            if(key!=null)sql.execSQL("DELETE FROM library_search_fts WHERE docid=?",arrayOf(key))
            if(value==null){
                sql.execSQL("DELETE FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target))
            }else{
                sql.execSQL("INSERT OR IGNORE INTO library_search_docs(kind,target) VALUES(?,?)",arrayOf<Any>(kind,target))
                val doc=key?:sql.query("SELECT key FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target)).use{check(it.moveToFirst());it.getLong(0)}
                val terms=LibrarySearchTerms.document(value.first,value.second){context.ensureActive()}
                sql.execSQL("INSERT INTO library_search_fts(docid,terms) VALUES(?,?)",arrayOf<Any>(doc,terms))
            }
            sql.execSQL("DELETE FROM library_search_pending WHERE kind=? AND target=?",arrayOf<Any>(kind,target))
            rows++;bytes+=size
        }
        return rows
    }
}
