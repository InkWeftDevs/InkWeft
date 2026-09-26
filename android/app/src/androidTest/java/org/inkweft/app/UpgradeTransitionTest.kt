// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Invoked in two separate CI phases with an actual adb install -r between.
 * No deleteDatabase, pm clear, uninstall, or author-file replacement. */
class UpgradeTransitionTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val book="11000000-0000-4000-8000-000000000001"
    private val stroke="11000000-0000-4000-8000-000000000002"
    private val command="11000000-0000-4000-8000-000000000003"
    private val bytes get()=Base64.decode("SVdTMQAkMTEwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAP8AAABAQAAAAQAAAANCyAAAQtwAAAAAAAAAAAAAPwAAAL+AAAC/gAAAQxYAAELcAAAAAAAAAAAAHj8AAAC/gAAAv4AAAENIAABC3AAAAAAAAAAAADw/AAAAv4AAAL+AAAA=",Base64.DEFAULT)
    @Test fun seedBaseline(){
        assertEquals(9L,ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        val path=ctx.getDatabasePath("inkweft-a0.db");path.parentFile!!.mkdirs()
        val sql=SQLiteDatabase.openOrCreateDatabase(path,null)
        val text=InstrumentationRegistry.getInstrumentation().context.assets.open("upgrade-schema7.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(text).getJSONObject("database")
        try{assertEquals(0,sql.version);sql.beginTransaction()
            val es=schema.getJSONArray("entities");for(i in 0 until es.length()){val e=es.getJSONObject(i);val t=e.getString("tableName");sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",t));val ix=e.optJSONArray("indices");for(j in 0 until (ix?.length()?:0))sql.execSQL(ix!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",t))}
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'UPGRADE_SENTINEL','DO_NOT_LOSE_SAVED_BODY',1)",arrayOf(book))
            sql.execSQL("INSERT INTO note_revisions VALUES(?,1,'UPGRADE_SENTINEL','DO_NOT_LOSE_SAVED_BODY',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace VALUES(?,0,1,'课程','升级',1,NULL,500,707,0,0,'auto',?,1)",arrayOf(book,book))
            sql.execSQL("INSERT INTO notebook_pages VALUES(?,?,0,0,1,500,707,0,NULL,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,?,1,1)",arrayOf(stroke,book,bytes,3))
            sql.execSQL("INSERT INTO ink_receipts VALUES(?,?,?,1,?,1)",arrayOf(command,book,"0".repeat(64),stroke))
            sql.execSQL("INSERT INTO page_search_text VALUES(?,1,'升级后仍可查找','MANUAL')",arrayOf(book));sql.version=7
            sql.setTransactionSuccessful()
        }finally{if(sql.inTransaction())sql.endTransaction();sql.close()}
        println("BASELINE_SEEDED_VERSION_9_SCHEMA_7")
    }
    @Test fun verifyAfterUpgrade(){
        assertEquals(10L,ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        val intent=Intent().setClassName(ctx.packageName,"org.inkweft.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val deadline=android.os.SystemClock.elapsedRealtime()+30000
        var migrated=false
        while(android.os.SystemClock.elapsedRealtime()<deadline&&!migrated){
            migrated=runCatching{SQLiteDatabase.openDatabase(ctx.getDatabasePath("inkweft-a0.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{it.version==8}}.getOrDefault(false)
            if(!migrated)Thread.sleep(150)
        };assertTrue("Actual app must migrate the preinstalled database",migrated)
        SQLiteDatabase.openDatabase(ctx.getDatabasePath("inkweft-a0.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{sql->
            sql.rawQuery("SELECT text FROM notes WHERE id=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals("DO_NOT_LOSE_SAVED_BODY",it.getString(0))}
            sql.rawQuery("SELECT payload,visible FROM ink_strokes WHERE id=?",arrayOf(stroke)).use{assertTrue(it.moveToFirst());assertArrayEquals(bytes,it.getBlob(0));assertEquals(1,it.getInt(1))}
            sql.rawQuery("SELECT pinned,favorite,selectedPageId FROM notebook_workspace WHERE noteId=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(1,it.getInt(1));assertEquals(book,it.getString(2))}
            sql.rawQuery("SELECT text FROM page_search_text WHERE pageId=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals("升级后仍可查找",it.getString(0))}
            sql.rawQuery("SELECT COUNT(*) FROM study_cards",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            sql.rawQuery("SELECT commandId FROM ink_receipts WHERE commandId=?",arrayOf(command)).use{assertTrue(it.moveToFirst())}
        }
        println("UPGRADE_VERSION_9_TO_10_PRESERVED_DATA_NO_UNINSTALL")
    }
}
