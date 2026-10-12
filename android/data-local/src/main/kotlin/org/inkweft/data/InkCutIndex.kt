// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import org.inkweft.core.InkLimits
import java.util.UUID

/** Rebuildable membership index; author CSV and payload remain the source of truth. */
internal object InkCutIndex {
    val tables=setOf("ink_cut_targets","ink_cut_pending")
    fun install(sql:SupportSQLiteDatabase){
        val exists=sql.query("SELECT 1 FROM sqlite_master WHERE name='ink_cut_targets' AND type='table'").use{it.moveToFirst()}
        sql.execSQL("CREATE TABLE IF NOT EXISTS ink_cut_targets (cutId TEXT NOT NULL,strokeId TEXT NOT NULL,PRIMARY KEY(cutId,strokeId))")
        sql.execSQL("CREATE INDEX IF NOT EXISTS ink_cut_targets_stroke ON ink_cut_targets(strokeId,cutId)")
        sql.execSQL("CREATE TABLE IF NOT EXISTS ink_cut_pending (cutId TEXT NOT NULL PRIMARY KEY,noteId TEXT NOT NULL)")
        sql.execSQL("CREATE INDEX IF NOT EXISTS ink_cut_pending_page ON ink_cut_pending(noteId,cutId)")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS cut_target_insert AFTER INSERT ON ink_cuts BEGIN INSERT OR REPLACE INTO ink_cut_pending VALUES(NEW.id,NEW.noteId); END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS cut_target_update AFTER UPDATE OF id,noteId,strokeIds ON ink_cuts BEGIN DELETE FROM ink_cut_targets WHERE cutId=OLD.id; DELETE FROM ink_cut_pending WHERE cutId=OLD.id; INSERT OR REPLACE INTO ink_cut_pending VALUES(NEW.id,NEW.noteId); END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS cut_target_delete AFTER DELETE ON ink_cuts BEGIN DELETE FROM ink_cut_targets WHERE cutId=OLD.id; DELETE FROM ink_cut_pending WHERE cutId=OLD.id; END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS cut_target_stroke_delete AFTER DELETE ON ink_strokes BEGIN INSERT OR IGNORE INTO ink_cut_pending SELECT t.cutId,c.noteId FROM ink_cut_targets t JOIN ink_cuts c ON c.id=t.cutId WHERE t.strokeId=OLD.id; END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS cut_target_stroke_update AFTER UPDATE OF id,noteId ON ink_strokes WHEN OLD.id!=NEW.id OR OLD.noteId!=NEW.noteId BEGIN INSERT OR IGNORE INTO ink_cut_pending SELECT t.cutId,c.noteId FROM ink_cut_targets t JOIN ink_cuts c ON c.id=t.cutId WHERE t.strokeId=OLD.id; END")
        if(!exists)sql.execSQL("INSERT OR IGNORE INTO ink_cut_pending SELECT id,noteId FROM ink_cuts")
    }
    private class Ready<T>(val value:T)
    /** Ordinary display/edit reads commit progress between batches. Explicitly atomic source
     * operations may already own a transaction and retain that operation's snapshot contract. */
    suspend fun <T> read(db:NoteDatabase,page:String,onBatch:suspend(Int)->Unit={},read:suspend(SupportSQLiteDatabase)->T):T {
        if(db.inTransaction())return db.withTransaction{val sql=db.openHelper.writableDatabase;prepare(sql,page);read(sql)}
        while(true){
            var built=0
            val result=db.withTransaction{
                val sql=db.openHelper.writableDatabase;built=prepareBatch(sql,page)
                if(built==0)Ready(read(sql))else null
            }
            if(result!=null)return result.value
            onBatch(built);yield()
        }
    }
    suspend fun prepare(sql:SupportSQLiteDatabase,page:String){while(prepareBatch(sql,page)>0)currentCoroutineContext().ensureActive()}
    suspend fun prepareBatch(sql:SupportSQLiteDatabase,page:String):Int {
        check(sql.inTransaction())
        var rows=0;var memberships=0
        while(rows<128&&memberships<4096){
            val pending=sql.query("SELECT c.id,c.strokeIds FROM ink_cut_pending p INDEXED BY ink_cut_pending_page CROSS JOIN ink_cuts c ON c.id=p.cutId WHERE p.noteId=? ORDER BY p.cutId LIMIT 1",arrayOf(page)).use{c->if(c.moveToFirst())c.getString(0) to c.getString(1)else null}?:break
            currentCoroutineContext().ensureActive()
            UUID.fromString(pending.first)
            val targets=pending.second.split(',');require(targets.size in 1..InkLimits.MAX_MUTATION_STROKES&&targets.distinct().size==targets.size)
            for(batch in targets.chunked(500)){
                currentCoroutineContext().ensureActive()
                batch.forEach{UUID.fromString(it)}
                val args=(listOf(page)+batch).toTypedArray()
                val count=sql.query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=? AND id IN (${batch.joinToString(","){"?"}})",args).use{check(it.moveToFirst());it.getInt(0)}
                check(count==batch.size){"INK_CUT_TARGET_MISSING"}
            }
            sql.compileStatement("INSERT OR IGNORE INTO ink_cut_targets VALUES(?,?)").use{insert->
                targets.forEach{insert.bindString(1,pending.first);insert.bindString(2,it);insert.executeInsert()}
            }
            sql.execSQL("DELETE FROM ink_cut_pending WHERE cutId=?",arrayOf(pending.first))
            rows++;memberships+=targets.size
        }
        return rows
    }
    /** Both payload and projected target membership have hard bounds, independent of page size. */
    suspend fun selected(sql:SupportSQLiteDatabase,page:String,ids:Set<String>,byteBudget:Long):List<InkCutRow>{
        prepare(sql,page)
        if(ids.isEmpty())return emptyList()
        val cuts=linkedMapOf<String,InkCutRow>();var bytes=0L
        for(batch in ids.toList().chunked(500)){
            currentCoroutineContext().ensureActive()
            sql.query("SELECT c.id,c.payload,c.createdRevision FROM (SELECT DISTINCT cutId FROM ink_cut_targets INDEXED BY ink_cut_targets_stroke WHERE strokeId IN (${batch.joinToString(","){"?"}})) t CROSS JOIN ink_cuts c ON c.id=t.cutId WHERE c.noteId=? AND c.visible=1 ORDER BY c.createdRevision,c.id",(batch+page).toTypedArray()).use{c->
                while(c.moveToNext()){
                    val id=c.getString(0);if(id in cuts)continue
                    val payload=c.getBlob(1);bytes+=payload.size
                    if(bytes>byteBudget)throw InkReadBudgetExceeded()
                    val targets=ids.toList().chunked(500).flatMap{selected->
                        sql.query("SELECT strokeId FROM ink_cut_targets WHERE cutId=? AND strokeId IN (${selected.joinToString(","){"?"}}) ORDER BY strokeId",(listOf(id)+selected).toTypedArray()).use{t->buildList{while(t.moveToNext())add(t.getString(0))}}
                    }.sorted()
                    bytes+=targets.size*80L
                    if(bytes>byteBudget)throw InkReadBudgetExceeded()
                    check(targets.isNotEmpty())
                    cuts[id]=InkCutRow(id,page,payload,targets.joinToString(","),true,c.getLong(2))
                }
            }
        }
        return cuts.values.sortedWith(compareBy({it.createdRevision},{it.id}))
    }
}

internal class InkReadBudgetExceeded:IllegalStateException("INK_READ_WORKING_SET_EXCEEDED")
