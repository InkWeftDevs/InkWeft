// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.OriginalBytes
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.OutputStream

/** The chunk tables are now only an exchange/staging representation and an upgrade source.
 * Their rows are removed only after the same bytes have been verified and published durably. */
internal object OriginalStorage {
    const val CHUNK=512_000
    val discard=object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,o:Int,n:Int){}}
    fun read(db:NoteDatabase,size:Int,hash:String,table:String,where:String,args:Array<String>):OriginalBytes {
        if(db.originalStore.contains(hash))return db.originalStore.content(size,hash)
        return chunks(db,size,hash,table,where,args,followPublishedFile=true)
    }
    fun chunks(db:NoteDatabase,size:Int,hash:String,table:String,where:String,args:Array<String>,followPublishedFile:Boolean=false):OriginalBytes {
        val sql=db.openHelper.readableDatabase
        val count=sql.query("SELECT COUNT(*) FROM $table WHERE $where",args).use{it.moveToFirst();it.getLong(0)}
        require(count==(size.toLong()+CHUNK-1)/CHUNK){"ORIGINAL_FILE_MISSING"}
        return OriginalBytes.stream(size,hash){OriginalChunkInput(size,CHUNK){position->
            sql.query("SELECT payload FROM $table WHERE $where AND position=?",arrayOf<Any>(*args,position)).use{c->if(c.moveToFirst())c.getBlob(0)else null}
                ?:if(followPublishedFile&&db.originalStore.contains(hash))db.originalStore.acquire(size,hash).use{lease->
                    FileInputStream(lease.file).use{input->
                        input.channel.position(position.toLong()*CHUNK)
                        ByteArray(minOf(CHUNK,size-position*CHUNK)).also{DataInputStream(input).readFully(it)}
                    }
                }else null
        }}
    }
    fun requireCacheFallback(db:NoteDatabase,hash:String){
        if(!referenced(db,hash))return // An imported preview may still be owned by its staging source.
        require(db.openHelper.readableDatabase.query(
            "SELECT 1 FROM document_chunks c JOIN document_sources s ON s.id=c.documentId WHERE s.digest=? UNION ALL SELECT 1 FROM image_chunks WHERE digest=? LIMIT 1",arrayOf(hash,hash)
        ).use{it.moveToFirst()}){"ORIGINAL_FILE_MISSING"}
    }
    fun referenced(db:NoteDatabase,hash:String)=db.openHelper.readableDatabase.query(
        "SELECT 1 FROM document_sources WHERE digest=? UNION ALL SELECT 1 FROM image_sources WHERE digest=? LIMIT 1",arrayOf(hash,hash)
    ).use{it.moveToFirst()}
}

/** Explicit idle maintenance, never called from a pen transaction or a render callback.
 * Source rows (including trash and hashes available to undo) remain authoritative retainers. */
class OriginalStorageMaintenance(private val db:NoteDatabase,private val availableBytes:(File)->Long={it.usableSpace}) {
    data class Report(val migratedOriginals:Int,val migratedBytes:Long,val removedFiles:Int,val removedFileBytes:Long,
        val originalBytesBefore:Long,val originalBytesAfter:Long,val databaseBytesBefore:Long,val databaseBytesAfter:Long,
        val cacheBytesBefore:Long,val cacheBytesAfter:Long,val freePagesBefore:Long,val freePagesAfter:Long,
        val compactionDeferred:String?) {
        val reclaimedBytes:Long get()=(databaseBytesBefore+originalBytesBefore+cacheBytesBefore-databaseBytesAfter-originalBytesAfter-cacheBytesAfter).coerceAtLeast(0)
    }
    suspend fun reclaim():Report=withContext(Dispatchers.IO){db.originalMaintenanceMutex.withLock{
        check(!db.inTransaction()){"ORIGINAL_MAINTENANCE_IN_TRANSACTION"}
        val sql=db.openHelper.writableDatabase;val active=currentCoroutineContext()
        fun number(pragma:String)=sql.query("PRAGMA $pragma").use{it.moveToFirst();it.getLong(0)}
        fun databaseBytes():Long {
            val main=File(sql.path)
            return main.length()+File(main.path+"-wal").length()+File(main.path+"-shm").length()
        }
        val originalBefore=db.originalStore.diskBytes();val databaseBefore=databaseBytes();val cacheBefore=db.originalFiles.diskBytes()
        var freeBefore=number("freelist_count")
        fun compact():String? {
            val freeNow=number("freelist_count");freeBefore=maxOf(freeBefore,freeNow);var deferred:String?=null
            active.ensureActive()
            if(freeNow>0){
                if(number("auto_vacuum")==0L){
                    // SQLite may need twice the database size in free space for VACUUM.
                    val required=number("page_count")*number("page_size")*2+32L*1024*1024
                    if(availableBytes(File(sql.path).parentFile!!)<required)deferred="SQLITE_COMPACTION_LOW_SPACE"
                    else {sql.execSQL("PRAGMA auto_vacuum=INCREMENTAL");sql.execSQL("VACUUM")}
                }else if(number("auto_vacuum")==1L)sql.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                if(deferred==null){
                    var free=number("freelist_count")
                    while(free>0){
                        active.ensureActive()
                        sql.query("PRAGMA incremental_vacuum(256)").use{while(it.moveToNext())Unit}
                        val next=number("freelist_count")
                        if(next>=free){deferred="SQLITE_COMPACTION_BUSY";break}
                        free=next
                    }
                }
            }
            // A checkpoint is required for the main file's released pages to shrink on disk.
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use{if(it.moveToFirst()&&it.getInt(0)!=0)deferred="SQLITE_CHECKPOINT_BUSY"}
            return deferred
        }
        // A cancelled or interrupted earlier run may have committed migration work already.
        // Reclaim that space before retrying the source that previously failed.
        if(freeBefore>0)compact()
        var migrated=0;var migratedBytes=0L
        try{
        for(document in listOf(true,false)){
            while(true){
                active.ensureActive()
                val moved=db.withTransaction {
                    val table=if(document)"document_chunks" else "image_chunks"
                    val owner=if(document)"document_sources" else "image_sources"
                    val join=if(document)"c.documentId=s.id" else "c.notebookId=s.notebookId AND c.digest=s.digest"
                    val row=sql.query("SELECT ${if(document)"s.id" else "s.notebookId"},s.digest,s.byteCount FROM $owner s WHERE EXISTS (SELECT 1 FROM $table c WHERE $join) LIMIT 1").use{c->
                        if(c.moveToFirst())Triple(c.getString(0),c.getString(1),c.getInt(2))else null
                    }?:return@withTransaction null
                    val where=if(document)"documentId=?" else "notebookId=? AND digest=?"
                    val args=if(document)arrayOf(row.first)else arrayOf(row.first,row.second)
                    val chunks=OriginalStorage.chunks(db,row.third,row.second,table,where,args)
                    // A retry can already have the durable file. Still validate all old chunks
                    // before removing them, so a damaged source is retained and reported.
                    if(db.originalStore.contains(row.second))chunks.copyTo(OriginalStorage.discard){active.ensureActive()}
                    db.originalStore.publish(row.third,row.second,{active.ensureActive()}){out->chunks.copyTo(out){active.ensureActive()}}
                    sql.execSQL("DELETE FROM $table WHERE $where",args)
                    row.third
                }?:break
                migrated++;migratedBytes+=moved
            }
        }
        }catch(failure:Throwable){
            if(failure !is CancellationException&&active.isActive){
                try{compact()}catch(cleanup:Throwable){if(cleanup!==failure)failure.addSuppressed(cleanup)}
            }
            throw failure
        }
        val reclaimed=db.withTransaction {
            // Fail closed on missing/corrupt originals. Metadata and the damaged file survive.
            sql.query("SELECT digest,byteCount FROM document_sources UNION ALL SELECT digest,byteCount FROM image_sources").use{c->while(c.moveToNext()){
                active.ensureActive();db.originalStore.acquire(c.getInt(1),c.getString(0)){active.ensureActive()}.close()
            }}
            db.originalStore.collect({hash->OriginalStorage.referenced(db,hash)}){active.ensureActive()}
        }
        db.originalFiles.trim()
        val deferred=compact()
        Report(migrated,migratedBytes,reclaimed.files,reclaimed.bytes,originalBefore,db.originalStore.diskBytes(),
            databaseBefore,databaseBytes(),cacheBefore,db.originalFiles.diskBytes(),freeBefore,number("freelist_count"),deferred)
    }}
}
