// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Rebuildable counts; page projections read at most one row per layer/content kind. */
internal object PageLayerTotals {
    const val TABLE="page_layer_totals"
    private fun match(row:String)="pageId=$row.pageId AND layerId=$row.layerId AND kind=$row.kind"
    private fun add(row:String)="INSERT INTO $TABLE SELECT $row.pageId,$row.layerId,$row.kind,0 WHERE $row.layerId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM $TABLE WHERE ${match(row)}); "+
        "UPDATE $TABLE SET entries=entries+1 WHERE ${match(row)}; "
    private fun remove(row:String)="UPDATE $TABLE SET entries=entries-1 WHERE ${match(row)}; DELETE FROM $TABLE WHERE ${match(row)} AND entries=0; "
    fun install(sql:SupportSQLiteDatabase){
        // REPLACE's implicit DELETE fires its trigger only with recursive triggers enabled.
        // Configure the primary (writer) connection, including when install is called directly.
        val own=!sql.inTransaction();if(own)sql.beginTransaction()
        try{
        sql.execSQL("PRAGMA recursive_triggers=ON")
        val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(TABLE)).use{it.moveToFirst()}
        sql.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (pageId TEXT NOT NULL,layerId TEXT NOT NULL,kind TEXT NOT NULL,entries INTEGER NOT NULL,PRIMARY KEY(pageId,layerId,kind))")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS layer_totals_insert AFTER INSERT ON page_layer_memberships BEGIN ${add("NEW")} END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS layer_totals_delete AFTER DELETE ON page_layer_memberships BEGIN ${remove("OLD")} END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS layer_totals_update AFTER UPDATE OF pageId,layerId,kind ON page_layer_memberships BEGIN ${remove("OLD")}${add("NEW")} END")
        if(!exists)sql.execSQL("INSERT INTO $TABLE SELECT pageId,layerId,kind,COUNT(*) FROM page_layer_memberships WHERE layerId IS NOT NULL GROUP BY pageId,layerId,kind")
        if(own)sql.setTransactionSuccessful()
        }finally{if(own)sql.endTransaction()}
    }
    fun read(sql:SupportSQLiteDatabase,page:String,inkOnly:Boolean):Map<String,Long> = sql.query(
        "SELECT layerId,SUM(entries) FROM $TABLE WHERE pageId=?"+(if(inkOnly)" AND kind='INK'"else "")+" GROUP BY layerId",arrayOf(page)
    ).use{c->buildMap{while(c.moveToNext()){val count=c.getLong(1);check(count>0);put(c.getString(0),count)}}}
}
