// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import org.inkweft.core.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/** Rebuildable spatial metadata. Source transactions queue invalidations, so raw restores and
 * ordinary Room writes obey the same rule: queries establish complete coverage of their
 * requested region before using the index; unbounded queries drain the entire page. */
internal object SpatialIndex {
    const val INK=0
    const val OBJECT=1
    // Exactly representable in RTree's float32 coordinates. Collisions only add candidates;
    // every query still checks the complete page UUID in the authoritative entry row.
    fun pageBucket(page:String)=page.hashCode() and 0x00ffffff
    val tables=setOf("page_spatial_entries","page_spatial_pending","page_spatial_bounds",
        "page_spatial_bounds_node","page_spatial_bounds_parent","page_spatial_bounds_rowid","page_spatial_hints","page_spatial_unknown","ink_raster_epochs",PageLayerTotals.TABLE)+InkCutIndex.tables+InkTotals.tables+LibraryTextIndex.tables

    fun install(sql:SupportSQLiteDatabase) {
        val own=!sql.inTransaction();if(own)sql.beginTransaction()
        try {
            InkCutIndex.install(sql);InkTotals.install(sql);PageLayerTotals.install(sql)
            sql.execSQL("CREATE TABLE IF NOT EXISTS ink_raster_epochs (pageId TEXT PRIMARY KEY NOT NULL,epoch INTEGER NOT NULL)")
            for(event in listOf("INSERT","UPDATE","DELETE")){
                val row=if(event=="DELETE")"OLD"else "NEW"
                sql.execSQL("DROP TRIGGER IF EXISTS raster_cut_${event.lowercase()}")
                sql.execSQL("CREATE TRIGGER raster_cut_${event.lowercase()} AFTER $event ON ink_cuts BEGIN INSERT INTO ink_raster_epochs SELECT $row.noteId,0 WHERE NOT EXISTS (SELECT 1 FROM ink_raster_epochs WHERE pageId=$row.noteId); UPDATE ink_raster_epochs SET epoch=epoch+1 WHERE pageId=$row.noteId; END")
            }
            sql.execSQL("DROP TRIGGER IF EXISTS raster_cut_move")
            sql.execSQL("CREATE TRIGGER raster_cut_move AFTER UPDATE OF noteId ON ink_cuts WHEN OLD.noteId!=NEW.noteId BEGIN INSERT INTO ink_raster_epochs SELECT OLD.noteId,0 WHERE NOT EXISTS (SELECT 1 FROM ink_raster_epochs WHERE pageId=OLD.noteId); UPDATE ink_raster_epochs SET epoch=epoch+1 WHERE pageId=OLD.noteId; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS raster_page_delete AFTER DELETE ON notebook_pages BEGIN DELETE FROM ink_raster_epochs WHERE pageId=OLD.id; END")
            val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='page_spatial_entries'").use{it.moveToFirst()}
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_entries (key INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,UNIQUE(pageId,kind,contentId))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_pending (pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,PRIMARY KEY(pageId,kind,contentId))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_hints (pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,minX REAL NOT NULL,maxX REAL NOT NULL,minY REAL NOT NULL,maxY REAL NOT NULL,PRIMARY KEY(pageId,kind,contentId))")
            val unknownExists=sql.query("SELECT 1 FROM sqlite_master WHERE name='page_spatial_unknown'").use{it.moveToFirst()}
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_spatial_unknown (pageId TEXT NOT NULL,kind INTEGER NOT NULL,contentId TEXT NOT NULL,PRIMARY KEY(pageId,kind,contentId))")
            sql.execSQL("DROP TRIGGER IF EXISTS spatial_unknown_insert")
            sql.execSQL("CREATE TRIGGER spatial_unknown_insert AFTER INSERT ON page_spatial_pending BEGIN INSERT INTO page_spatial_unknown SELECT NEW.pageId,NEW.kind,NEW.contentId WHERE NOT EXISTS (SELECT 1 FROM page_spatial_unknown WHERE pageId=NEW.pageId AND kind=NEW.kind AND contentId=NEW.contentId); END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_unknown_delete AFTER DELETE ON page_spatial_pending BEGIN DELETE FROM page_spatial_unknown WHERE pageId=OLD.pageId AND kind=OLD.kind AND contentId=OLD.contentId; END")
            if(!unknownExists)sql.execSQL("INSERT OR IGNORE INTO page_spatial_unknown SELECT p.pageId,p.kind,p.contentId FROM page_spatial_pending p LEFT JOIN page_spatial_hints h USING(pageId,kind,contentId) WHERE h.contentId IS NULL")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS spatial_hint_pending_delete AFTER DELETE ON page_spatial_pending BEGIN DELETE FROM page_spatial_hints WHERE pageId=OLD.pageId AND kind=OLD.kind AND contentId=OLD.contentId; END")
            val pageDimension=sql.query("PRAGMA table_info(page_spatial_bounds)").use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="minPage")found=true;found}
            if(!pageDimension)sql.execSQL("DROP TABLE IF EXISTS page_spatial_bounds")
            sql.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_spatial_bounds USING rtree(key,minX,maxX,minY,maxY,minPage,maxPage)")
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
            if(!exists||!pageDimension){
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
        val bucket=pageBucket(page)
        sql.execSQL("INSERT OR REPLACE INTO page_spatial_bounds VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(key,bounds.left,bounds.right,bounds.top,bounds.bottom,bucket,bucket))
        sql.execSQL("DELETE FROM page_spatial_pending WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
    }

    data class BuildBatch(val rows:Int,val payloadBytes:Long)
    private class Ready<T>(val value:T)
    /** Commit bounded progress before yielding. A region read requires no unknown coverage and
     * no intersecting pending row in the read transaction; page reads require an empty queue. */
    suspend fun <T> read(db:NoteDatabase,page:String,onBatch:suspend(BuildBatch)->Unit={},prepareCuts:Boolean=false,bounds:CanvasBounds?=null,read:suspend(SupportSQLiteDatabase)->T):T {
        check(!db.inTransaction()){"Spatial reads must not inherit a writer transaction"}
        while(true){
            var built:BuildBatch?=null
            val ready=db.withTransaction {
                val sql=db.openHelper.writableDatabase
                built=if(bounds==null)prepareBatch(sql,page)else prepareHints(sql,page)?:prepareBatch(sql,page,bounds)
                if(built==null&&prepareCuts){val cuts=InkCutIndex.prepareBatch(sql,page);if(cuts>0)built=BuildBatch(cuts,0)}
                if(built==null)Ready(read(sql))else null
            }
            if(ready!=null)return ready.value
            onBatch(checkNotNull(built));yield()
        }
    }
    /** Both decoding and transaction work are bounded, including first-time indexing. */
    private suspend fun prepareBatch(sql:SupportSQLiteDatabase,page:String,bounds:CanvasBounds?=null):BuildBatch? {
        check(sql.inTransaction())
        val context=currentCoroutineContext()
            val args=if(bounds==null)arrayOf<Any>(page)else arrayOf<Any>(page,bounds.right,bounds.left,bounds.bottom,bounds.top)
            val query=if(bounds==null)"SELECT kind,contentId FROM page_spatial_pending WHERE pageId=? ORDER BY kind,contentId LIMIT 128"else
                "SELECT p.kind,p.contentId FROM page_spatial_pending p JOIN page_spatial_hints h USING(pageId,kind,contentId) WHERE p.pageId=? AND h.minX<=? AND h.maxX>=? AND h.minY<=? AND h.maxY>=? ORDER BY p.kind,p.contentId LIMIT 128"
            val pending=sql.query(query,args).use{c->
                buildList{while(c.moveToNext())add(c.getInt(0) to c.getString(1))}
            }
            if(pending.isEmpty())return null
            var rows=0;var bytes=0L
            for((kind,id) in pending){
                context.ensureActive()
                val table=if(kind==INK)"ink_strokes" else "page_object_records"
                val column=if(kind==INK)"noteId" else "pageId"
                val value=if(kind==INK)"payload"else "substr(payload,1,128)"
                val payload=sql.query("SELECT $value FROM $table WHERE $column=? AND id=?${if(kind==INK)" AND visible=1" else ""}",arrayOf<Any>(page,id)).use{c->if(c.moveToFirst())c.getBlob(0)else null}
                if(rows>0&&bytes+(payload?.size?:0)>InkLimits.MAX_STROKE_BYTES)break
                if(payload==null){
                    sql.execSQL("DELETE FROM page_spatial_entries WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
                    sql.execSQL("DELETE FROM page_spatial_pending WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
                }else{
                    val bounds=if(kind==INK)InkStrokeCodec.decode(payload).also{check(it.id==id)}.bounds()else objectBounds(payload,id)
                    put(sql,page,kind,id,bounds)
                }
                rows++;bytes+=payload?.size?:0
            }
            return BuildBatch(rows,bytes)
    }

    /** Establish conservative coverage cheaply. Unknown legacy headers must be fully indexed;
     * they cannot be assumed offscreen. Hints and pending removals share writer transactions. */
    private suspend fun prepareHints(sql:SupportSQLiteDatabase,page:String):BuildBatch? {
        // A separate shrinking queue avoids rescanning already-covered offscreen rows for
        // every header batch. Neither this queue nor its hints are author data.
        val pending=sql.query("SELECT kind,contentId FROM page_spatial_unknown WHERE pageId=? ORDER BY kind,contentId LIMIT 128",arrayOf(page)).use{c->
            buildList{while(c.moveToNext())add(c.getInt(0) to c.getString(1))}
        }
        if(pending.isEmpty())return null
        var bytes=0L;var rows=0
        for((kind,id) in pending){
            currentCoroutineContext().ensureActive()
            val table=if(kind==INK)"ink_strokes"else"page_object_records";val column=if(kind==INK)"noteId"else"pageId"
            val prefix=sql.query("SELECT substr(payload,1,128) FROM $table WHERE $column=? AND id=?",arrayOf(page,id)).use{if(it.moveToFirst())it.getBlob(0)else null}
            bytes+=prefix?.size?:0
            if(prefix==null){sql.execSQL("DELETE FROM page_spatial_pending WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))}
            else{
                val hint=if(kind==INK)InkStrokeCodec.storedBounds(prefix)else objectBounds(prefix,id)
                if(hint==null){
                    val payload=sql.query("SELECT payload FROM ink_strokes WHERE noteId=? AND id=?",arrayOf(page,id)).use{check(it.moveToFirst());it.getBlob(0)}
                    if(rows>0&&bytes+payload.size>InkLimits.MAX_STROKE_BYTES)break
                    bytes+=payload.size;val stroke=InkStrokeCodec.decode(payload);check(stroke.id==id);put(sql,page,kind,id,stroke.bounds())
                }else{
                    sql.execSQL("INSERT OR REPLACE INTO page_spatial_hints VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(page,kind,id,hint.left,hint.right,hint.top,hint.bottom))
                    sql.execSQL("DELETE FROM page_spatial_unknown WHERE pageId=? AND kind=? AND contentId=?",arrayOf<Any>(page,kind,id))
                }
            }
            rows++
        }
        return BuildBatch(rows,bytes)
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
    data class InkCandidate(val id:String,val revision:Long,val pointCount:Int,val byteCount:Int,val spatialKey:Long=0,val bounds:CanvasBounds?=null)

    fun ink(sql:SupportSQLiteDatabase,page:String,bounds:CanvasBounds?,after:Cursor?=null,limit:Int=256,layers:List<String>?=null):List<InkCandidate> {
        android.os.Trace.beginSection("InkWeft.spatial.query")
        try{
        require(limit in 1..1024)
        if(layers?.isEmpty()==true)return emptyList()
        val args=mutableListOf<Any>(page)
        val spatial=if(bounds==null)"" else{
            val bucket=pageBucket(page)
            args.addAll(listOf(bounds.right,bounds.left,bounds.bottom,bounds.top,bucket,bucket))
            " AND b.minX<=? AND b.maxX>=? AND b.minY<=? AND b.maxY>=? AND b.minPage<=? AND b.maxPage>=?"
        }
        val owned=if(layers==null)""else{
            args.addAll(layers)
            " AND EXISTS (SELECT 1 FROM page_layer_memberships m WHERE m.pageId=s.noteId AND m.kind='INK' AND m.contentId=s.id AND m.layerId IN (${layers.joinToString(","){"?"}}))"
        }
        val cursor=if(after==null)""else{args.addAll(listOf(after.revision,after.revision,after.id));" AND (s.createdRevision>? OR (s.createdRevision=? AND s.id>?))"}
        args.add(limit)
        val from=if(bounds==null)"FROM ink_strokes s WHERE s.noteId=? AND s.visible=1"else "FROM page_spatial_bounds b "+
            "CROSS JOIN page_spatial_entries e ON e.key=b.key CROSS JOIN ink_strokes s ON s.id=e.contentId AND s.noteId=e.pageId "+
            "WHERE e.pageId=? AND e.kind=0 AND s.visible=1$spatial"
        val box=if(bounds==null)"NULL,NULL,NULL,NULL"else"b.minX,b.minY,b.maxX,b.maxY"
        return sql.query("SELECT s.id,s.createdRevision,s.pointCount,length(s.payload),${if(bounds==null)"0"else"e.key"},$box $from$owned$cursor ORDER BY s.createdRevision,s.id LIMIT ?",args.toTypedArray()).use{c->
                buildList{while(c.moveToNext())add(InkCandidate(c.getString(0),c.getLong(1),c.getInt(2),c.getInt(3),c.getLong(4),if(c.isNull(5))null else CanvasBounds(c.getDouble(5),c.getDouble(6),c.getDouble(7),c.getDouble(8))))}
            }
        }finally{android.os.Trace.endSection()}
    }

    fun objects(sql:SupportSQLiteDatabase,page:String,bounds:CanvasBounds):List<String> = sql.query(
        "SELECT o.id FROM page_spatial_bounds b CROSS JOIN page_spatial_entries e ON e.key=b.key "+
            "CROSS JOIN page_object_records o ON o.pageId=e.pageId AND o.id=e.contentId "+
            "WHERE e.pageId=? AND e.kind=1 AND b.minX<=? AND b.maxX>=? AND b.minY<=? AND b.maxY>=? AND b.minPage<=? AND b.maxPage>=? ORDER BY o.position,o.id",
        arrayOf<Any>(page,bounds.right,bounds.left,bounds.bottom,bounds.top,pageBucket(page),pageBucket(page))).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
}
