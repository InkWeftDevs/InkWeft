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
    private val baseSchema get()=InstrumentationRegistry.getArguments().getString("baseSchema","7").toInt().also{require(it==4||it==7||it==8||it==9)}
    private val book="11000000-0000-4000-8000-000000000001"
    private val stroke="11000000-0000-4000-8000-000000000002"
    private val command="11000000-0000-4000-8000-000000000003"
    private val card="11000000-0000-4000-8000-000000000004"
    private val node="11000000-0000-4000-8000-000000000005"
    private val bytes get()=Base64.decode("SVdTMQAkMTEwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAP8AAABAQAAAAQAAAANCyAAAQtwAAAAAAAAAAAAAPwAAAL+AAAC/gAAAQxYAAELcAAAAAAAAAAAAHj8AAAC/gAAAv4AAAENIAABC3AAAAAAAAAAAADw/AAAAv4AAAL+AAAA=",Base64.DEFAULT)
    @Test fun seedBaseline(){
        assertEquals(if(baseSchema==4)5L else if(baseSchema==7)9L else if(baseSchema==8)10L else 11L,ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        val path=ctx.getDatabasePath("inkweft-a0.db");path.parentFile!!.mkdirs()
        val sql=SQLiteDatabase.openOrCreateDatabase(path,null)
        val text=InstrumentationRegistry.getInstrumentation().context.assets.open("upgrade-schema$baseSchema.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(text).getJSONObject("database")
        try{assertEquals(0,sql.version);sql.beginTransaction()
            val es=schema.getJSONArray("entities");for(i in 0 until es.length()){val e=es.getJSONObject(i);val t=e.getString("tableName");sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",t));val ix=e.optJSONArray("indices");for(j in 0 until (ix?.length()?:0))sql.execSQL(ix!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",t))}
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'UPGRADE_SENTINEL','DO_NOT_LOSE_SAVED_BODY',1)",arrayOf(book))
            sql.execSQL("INSERT INTO note_revisions VALUES(?,1,'UPGRADE_SENTINEL','DO_NOT_LOSE_SAVED_BODY',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace(noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision,coverKey,selectedPageId) VALUES(?,0,1,'课程','升级',1,NULL,500,707,0,0,'auto',?)",arrayOf(book,book))
            if(baseSchema>=7)sql.execSQL("UPDATE notebook_workspace SET pinned=1 WHERE noteId=?",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_pages(id,notebookId,position,world,paper,centerX,centerY,zoom,createdAfterId) VALUES(?,?,0,0,1,500,707,0,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,?,1,1)",arrayOf(stroke,book,bytes,3))
            sql.execSQL("INSERT INTO ink_receipts VALUES(?,?,?,1,?,1)",arrayOf(command,book,"0".repeat(64),stroke))
            sql.execSQL("INSERT INTO page_search_text VALUES(?,1,'升级后仍可查找','MANUAL')",arrayOf(book))
            if(baseSchema>=8){
                sql.execSQL("INSERT INTO study_cards VALUES(?,?,1,'UPGRADE_CARD','SHARED_CARD_BODY',NULL)",arrayOf(card,book))
                sql.execSQL("INSERT INTO study_card_revisions VALUES(?,1,'UPGRADE_CARD','SHARED_CARD_BODY',NULL)",arrayOf(card))
                val snapshot=org.inkweft.core.InkPageFile("原迹","",listOf(org.inkweft.core.InkStrokeCodec.decode(bytes)),false,org.inkweft.core.PaperStyle.RULED).encode()
                sql.execSQL("INSERT INTO study_sources VALUES(?,?,1,90,100,210,120,?,?)",arrayOf(card,book,stroke,snapshot))
                sql.execSQL("INSERT INTO study_nodes VALUES(?,?,?,NULL,40,80,1,0)",arrayOf(node,book,card))
            }
            if(baseSchema==9){
                val payload=org.inkweft.core.KnowledgeCodec.encode(org.inkweft.core.KnowledgeData.Link(org.inkweft.core.TargetRef(org.inkweft.core.TargetKind.PAGE,book),org.inkweft.core.TargetRef(org.inkweft.core.TargetKind.CARD,card)))
                sql.execSQL("INSERT INTO knowledge_records VALUES(?,?,1,?,0)",arrayOf(command,book,payload))
                sql.execSQL("INSERT INTO knowledge_revisions VALUES(?,1,?,?,0)",arrayOf(command,book,payload))
            }
            sql.version=baseSchema
            sql.setTransactionSuccessful()
        }finally{if(sql.inTransaction())sql.endTransaction();sql.close()}
        println("BASELINE_SEEDED_SCHEMA_$baseSchema")
    }
    @Test fun verifyAfterUpgrade(){
        assertEquals(12L,ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        val intent=Intent().setClassName(ctx.packageName,"org.inkweft.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val deadline=android.os.SystemClock.elapsedRealtime()+30000
        var migrated=false
        while(android.os.SystemClock.elapsedRealtime()<deadline&&!migrated){
            migrated=runCatching{SQLiteDatabase.openDatabase(ctx.getDatabasePath("inkweft-a0.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{it.version==10}}.getOrDefault(false)
            if(!migrated)Thread.sleep(150)
        };assertTrue("Actual app must migrate the preinstalled database",migrated)
        SQLiteDatabase.openDatabase(ctx.getDatabasePath("inkweft-a0.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{sql->
            sql.rawQuery("SELECT text FROM notes WHERE id=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals("DO_NOT_LOSE_SAVED_BODY",it.getString(0))}
            sql.rawQuery("SELECT payload,visible FROM ink_strokes WHERE id=?",arrayOf(stroke)).use{assertTrue(it.moveToFirst());assertArrayEquals(bytes,it.getBlob(0));assertEquals(1,it.getInt(1))}
            sql.rawQuery("SELECT pinned,favorite,selectedPageId FROM notebook_workspace WHERE noteId=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals(if(baseSchema==4)0 else 1,it.getInt(0));assertEquals(1,it.getInt(1));assertEquals(book,it.getString(2))}
            sql.rawQuery("SELECT text FROM page_search_text WHERE pageId=?",arrayOf(book)).use{assertTrue(it.moveToFirst());assertEquals("升级后仍可查找",it.getString(0))}
            sql.rawQuery("SELECT COUNT(*) FROM study_cards",null).use{assertTrue(it.moveToFirst());assertEquals(if(baseSchema>=8)1 else 0,it.getInt(0))}
            if(baseSchema>=8){
                sql.rawQuery("SELECT body FROM study_cards WHERE id=?",arrayOf(card)).use{assertTrue(it.moveToFirst());assertEquals("SHARED_CARD_BODY",it.getString(0))}
                sql.rawQuery("SELECT pageId,snapshot FROM study_sources WHERE cardId=?",arrayOf(card)).use{assertTrue(it.moveToFirst());assertEquals(book,it.getString(0));assertEquals(stroke,org.inkweft.core.InkPageFile.decode(it.getBlob(1)).strokes.single().id)}
                sql.rawQuery("SELECT cardId FROM study_nodes WHERE id=?",arrayOf(node)).use{assertTrue(it.moveToFirst());assertEquals(card,it.getString(0))}
            }
            if(baseSchema==9)sql.rawQuery("SELECT payload FROM knowledge_records WHERE id=?",arrayOf(command)).use{assertTrue(it.moveToFirst());val link=org.inkweft.core.KnowledgeCodec.decode(it.getBlob(0)) as org.inkweft.core.KnowledgeData.Link;assertEquals(card,link.target.id)}
            sql.rawQuery("SELECT commandId FROM ink_receipts WHERE commandId=?",arrayOf(command)).use{assertTrue(it.moveToFirst())}
        }
        println("UPGRADE_SCHEMA_${baseSchema}_TO_10_PRESERVED_DATA_NO_UNINSTALL")
    }
}
