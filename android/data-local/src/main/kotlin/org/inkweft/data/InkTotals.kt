// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.inkweft.core.InkLimits

/** Disposable retained-ink counters. Author rows and receipts never depend on this table;
 * triggers share their transaction, and a missing projection is rebuilt without reading blobs. */
internal object InkTotals {
    const val TABLE="ink_page_totals"
    val tables=setOf(TABLE)
    data class Counts(val strokeCount:Int,val pointCount:Long,val cutCount:Int,val invalidPointRows:Int)
    private fun invalid(row:String)="CASE WHEN typeof($row.pointCount)='integer' AND $row.pointCount BETWEEN 1 AND ${InkLimits.MAX_POINTS} THEN 0 ELSE 1 END"
    // Room's outer INSERT OR ABORT overrides a trigger's OR IGNORE. Avoid the conflict entirely.
    private fun empty(page:String)="INSERT INTO $TABLE SELECT $page,0,0,0,0 WHERE NOT EXISTS (SELECT 1 FROM $TABLE WHERE pageId=$page); "
    private fun addStroke(row:String)="UPDATE $TABLE SET strokeCount=strokeCount+1,pointCount=pointCount+$row.pointCount,invalidPointRows=invalidPointRows+(${invalid(row)}) WHERE pageId=$row.noteId; "
    private fun removeStroke(row:String)="UPDATE $TABLE SET strokeCount=strokeCount-1,pointCount=pointCount-$row.pointCount,invalidPointRows=invalidPointRows-(${invalid(row)}) WHERE pageId=$row.noteId; "
    fun install(sql:SupportSQLiteDatabase){
        transaction(sql){
            val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(TABLE)).use{it.moveToFirst()}
            sql.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (pageId TEXT NOT NULL PRIMARY KEY,strokeCount INTEGER NOT NULL,pointCount INTEGER NOT NULL,cutCount INTEGER NOT NULL,invalidPointRows INTEGER NOT NULL)")
            for(name in listOf("page_insert","page_delete","stroke_insert","stroke_delete","stroke_update","cut_insert","cut_delete","cut_update"))
                sql.execSQL("DROP TRIGGER IF EXISTS ink_totals_$name")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_page_insert AFTER INSERT ON notebook_pages BEGIN ${empty("NEW.id")} END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_page_delete AFTER DELETE ON notebook_pages BEGIN DELETE FROM $TABLE WHERE pageId=OLD.id; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_stroke_insert AFTER INSERT ON ink_strokes BEGIN ${empty("NEW.noteId")}${addStroke("NEW")} END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_stroke_delete AFTER DELETE ON ink_strokes BEGIN ${removeStroke("OLD")} END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_stroke_update AFTER UPDATE OF noteId,pointCount ON ink_strokes BEGIN ${removeStroke("OLD")}${empty("NEW.noteId")}${addStroke("NEW")} END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_cut_insert AFTER INSERT ON ink_cuts BEGIN ${empty("NEW.noteId")}UPDATE $TABLE SET cutCount=cutCount+1 WHERE pageId=NEW.noteId; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_cut_delete AFTER DELETE ON ink_cuts BEGIN UPDATE $TABLE SET cutCount=cutCount-1 WHERE pageId=OLD.noteId; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS ink_totals_cut_update AFTER UPDATE OF noteId ON ink_cuts BEGIN UPDATE $TABLE SET cutCount=cutCount-1 WHERE pageId=OLD.noteId; ${empty("NEW.noteId")}UPDATE $TABLE SET cutCount=cutCount+1 WHERE pageId=NEW.noteId; END")
            if(!exists)backfill(sql)
        }
    }
    private inline fun transaction(sql:SupportSQLiteDatabase,body:()->Unit){
        val own=!sql.inTransaction();if(own)sql.beginTransaction()
        try{body();if(own)sql.setTransactionSuccessful()}finally{if(own)sql.endTransaction()}
    }
    private fun backfill(sql:SupportSQLiteDatabase){
        sql.execSQL("INSERT INTO $TABLE SELECT p.id,COALESCE(s.strokes,0),COALESCE(s.points,0),COALESCE(c.cuts,0),COALESCE(s.invalid,0) FROM notebook_pages p "+
            "LEFT JOIN (SELECT noteId,COUNT(*) AS strokes,SUM(pointCount) AS points,SUM(${invalid("ink_strokes")}) AS invalid FROM ink_strokes GROUP BY noteId) s ON s.noteId=p.id "+
            "LEFT JOIN (SELECT noteId,COUNT(*) AS cuts FROM ink_cuts GROUP BY noteId) c ON c.noteId=p.id")
    }
    fun rebuild(sql:SupportSQLiteDatabase)=transaction(sql){sql.execSQL("DELETE FROM $TABLE");backfill(sql)}
    fun read(sql:SupportSQLiteDatabase,page:String):Counts=sql.query("SELECT strokeCount,pointCount,cutCount,invalidPointRows FROM $TABLE WHERE pageId=?",arrayOf(page)).use{c->
        check(c.moveToFirst()){"INK_TOTALS_MISSING"}
        val strokes=c.getLong(0);val points=c.getLong(1);val cuts=c.getLong(2);val invalid=c.getLong(3)
        check(strokes in 0..Int.MAX_VALUE.toLong()&&cuts in 0..Int.MAX_VALUE.toLong()&&invalid in 0..strokes){"INK_TOTALS_INVALID"}
        if(invalid==0L)check(points in strokes..strokes*InkLimits.MAX_POINTS){"INK_TOTALS_INVALID"}
        Counts(strokes.toInt(),points,cuts.toInt(),invalid.toInt())
    }
}
