// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.inkweft.core.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/** Rebuildable spatial metadata. Source transactions queue invalidations, so raw restores and
 * ordinary Room writes obey the same rule: queries drain their page before using the index. */
internal object SpatialIndex {
    const val INK=0
    const val OBJECT=1
    val tables=setOf("page_spatial_entries","page_spatial_pending","page_spatial_bounds",
        "page_spatial_bounds_node","page_spatial_bounds_parent","page_spatial_bounds_rowid")

    fun install(sql:SupportSQLiteDatabase) {
        val own=!sql.inTransaction();if(own)sql.beginTransaction()
        try {
            val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='page_spatial_entries'").use{it.moveToFirst()}
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_entries (key INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,UNIQUE(pageId,kind,contentId))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_pending (pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,PRIMARY KEY(pageId,kind,contentId))")
            sql.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_spatial_bounds USING rtree(key,minX,maxX,minY,maxY)")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_entry_delete AFTER DELETE ON page_spatial_entries BEGIN DELETE FROM page_spatial_bounds WHERE key=OLD.key; END")
            for((table,page,kind) in listOf(Triple("ink_strokes","noteId",INK),Triple("page_object_records","pageId",OBJECT))){
                val visible=if(kind==INK)" WHEN NEW.visible=1" else ""
                sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_${kind}_insert AFTER INSERT ON $table$visible BEGIN INSERT OR REPLACE INTO page_spatial_pending VALUES(NEW.$page,$kind,NEW.id); END")
                sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_${kind}_update AFTER UPDATE OF payload,$page,id${if(kind==INK)",visible" else ""} ON $table BEGIN "+
                    "DELETE FROM page_spatial_entries WHERE pageId=OLD.$page AND kind=$kind AND contentId=OLD.id; "+
                    "DELETE FROM page_spatial_pending WHERE pageId=OLD.$page AND kind=$kind AND contentId=OLD.id; "+
                    "INSERT OR REPLACE INTO page_spatial_pending SELECT NEW.$page,$kind,NEW.id${if(kind==INK)" WHERE NEW.visible=1" else ""}; END")
                sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_${kind}_delete AFTER DELETE ON $table BEGIN "+
                    "DELETE FROM page_spatial_entries WHERE pageId=OLD.$page AND kind=$kind AND contentId=OLD.id; "+
                    "DELETE FROM page_spatial_pending WHERE pageId=OLD.$page AND kind=$kind AND contentId=OLD.id; END")
            }
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_page_delete AFTER DELETE ON notebook_pages BEGIN "+
                "DELETE FROM page_spatial_entries WHERE pageId=OLD.id; DELETE FROM page_spatial_pending WHERE pageId=OLD.id; END")
            if(!exists){
                sql.execSQL("INSERT OR IGNORE INTO page_spatial_pending SELECT noteId,0,id FROM ink_strokes WHERE visible=1")
                sql.execSQL("INSERT OR IGNORE INTO page_spatial_pending SELECT pageId,1,id FROM page_object_records")
            }
            if(own)sql.setTransactionSuccessful()
        }finally{if(own)sql.endTransaction()}
    }

    fun put(sql:SupportSQLiteDatabase,page:String,kind:Int,id:String,bounds:CanvasBounds) {
        check(sql.inTransaction())
        sql.execSQL("INSERT OR IGNORE INTO page_spatial_entries(pageId,kind,contentId) VALUES(?,?,?)",arrayOf<Any>(page,kind,id))
        val key=sql.query("SELECT key FROM page_spatial_entries WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id)).use{check(it.moveToFirst());it.getLong(0)}
        sql.execSQL("INSERT OR REPLACE INTO page_spatial_bounds VALUES(?,?,?,?,?)",arrayOf<Any>(key,bounds.left,bounds.right,bounds.top,bounds.bottom))
        sql.execSQL("DELETE FROM page_spatial_pending WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
    }

    /** Cursor windows never contain a page's complete geometry, including first-time indexing. */
    suspend fun prepare(sql:SupportSQLiteDatabase,page:String) {
        check(sql.inTransaction())
        val context=currentCoroutineContext()
        while(true){
            val pending=sql.query("SELECT kind,contentId FROM page_spatial_pending WHERE pageId=? ORDER BY kind,contentId LIMIT 128",arrayOf<Any>(page)).use{c->
                buildList{while(c.moveToNext())add(c.getInt(0) to c.getString(1))}
            }
            if(pending.isEmpty())return
            for((kind,id) in pending){
                context.ensureActive()
                val table=if(kind==INK)"ink_strokes" else "page_object_records"
                val column=if(kind==INK)"noteId" else "pageId"
                val payload=sql.query("SELECT payload FROM $table WHERE $column=? AND id=?${if(kind==INK)" AND visible=1" else ""}",arrayOf<Any>(page,id)).use{c->if(c.moveToFirst())c.getBlob(0)else null}
                if(payload==null){
                    sql.execSQL("DELETE FROM page_spatial_entries WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
                    sql.execSQL("DELETE FROM page_spatial_pending WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
                }else{
                    val bounds=if(kind==INK)InkStrokeCodec.decode(payload).also{check(it.id==id)}.bounds()else objectBounds(payload,id)
                    put(sql,page,kind,id,bounds)
                }
            }
        }
    }

    /** Geometry prefix only; JPEG previews are neither read nor decoded for candidate selection.
     * Full object reads still validate all metadata, preview hashes and original references. */
    private fun objectBounds(payload:ByteArray,expected:String):CanvasBounds=DataInputStream(ByteArrayInputStream(payload)).use{input->
        require(payload.size in 8..PageObjectCodec.MAX_BYTES&&input.readInt()==0x49574f3b&&input.readInt()==1)
        val id=input.readUTF();UUID.fromString(id);require(id==expected)
        val kind=PageObjectKind.entries.getOrNull(input.readUnsignedByte())?:error("Object kind")
        val x=input.readFloat();val y=input.readFloat();val width=input.readFloat();val height=input.readFloat()
        require(listOf(x,y,width,height).all{it.isFinite()}&&width in 24f..4000f&&height in 24f..4000f)
        require(kotlin.math.abs(x)+width<=BoardLimits.WORLD&&kotlin.math.abs(y)+height<=BoardLimits.WORLD)
        // Tape centre lines may touch their box edge. The maximum permitted line width
        // supplies conservative coverage without deserializing text or image metadata.
        CanvasBounds(x.toDouble(),y.toDouble(),(x+width).toDouble(),(y+height).toDouble())
            .padded(if(kind==PageObjectKind.TAPE||kind==PageObjectKind.SHAPE)96.0 else 0.0)
    }

    data class Cursor(val revision:Long,val id:String)
    data class InkCandidate(val id:String,val revision:Long,val pointCount:Int,val byteCount:Int)

    fun ink(sql:SupportSQLiteDatabase,page:String,bounds:CanvasBounds?,after:Cursor?=null,limit:Int=256):List<InkCandidate> {
        android.os.Trace.beginSection("InkWeft.spatial.query")
        try{
        require(limit in 1..1024)
        val args=mutableListOf<Any>(page)
        val spatial=if(bounds==null)"" else{
            args.addAll(listOf(bounds.right,bounds.left,bounds.bottom,bounds.top))
            " AND b.minX<=? AND b.maxX>=? AND b.minY<=? AND b.maxY>=?"
        }
        val cursor=if(after==null)""else{args.addAll(listOf(after.revision,after.revision,after.id));" AND (s.createdRevision>? OR (s.createdRevision=? AND s.id>?))"}
        args.add(limit)
        val from=if(bounds==null)"FROM ink_strokes s WHERE s.noteId=? AND s.visible=1"else "FROM page_spatial_bounds b "+
            "JOIN page_spatial_entries e ON e.key=b.key JOIN ink_strokes s ON s.id=e.contentId AND s.noteId=e.pageId "+
            "WHERE e.pageId=? AND e.kind=0 AND s.visible=1$spatial"
        return sql.query("SELECT s.id,s.createdRevision,s.pointCount,length(s.payload) $from$cursor ORDER BY s.createdRevision,s.id LIMIT ?",args.toTypedArray()).use{c->
                buildList{while(c.moveToNext())add(InkCandidate(c.getString(0),c.getLong(1),c.getInt(2),c.getInt(3)))}
            }
        }finally{android.os.Trace.endSection()}
    }

    fun objects(sql:SupportSQLiteDatabase,page:String,bounds:CanvasBounds):List<String> = sql.query(
        "SELECT o.id FROM page_spatial_bounds b JOIN page_spatial_entries e ON e.key=b.key "+
            "JOIN page_object_records o ON o.pageId=e.pageId AND o.id=e.contentId "+
            "WHERE e.pageId=? AND e.kind=1 AND b.minX<=? AND b.maxX>=? AND b.minY<=? AND b.maxY>=? ORDER BY o.position,o.id",
        arrayOf<Any>(page,bounds.right,bounds.left,bounds.bottom,bounds.top)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
}
