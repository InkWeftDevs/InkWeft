// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import java.io.Closeable
import java.io.OutputStream

/** The archive's bounded chunk rows are synthesized from immutable files. Database tables
 * keep their current schema for a one-time upgrade source, but new writes never insert blobs. */
internal class OriginalArchiveRows(private val db:NoteDatabase,private val ids:List<String>?=null) {
    private val sql get()=db.openHelper.readableDatabase
    private fun query(statement:String)=if(ids==null)sql.query(statement)else sql.query(statement,ids.toTypedArray())
    private fun where()=if(ids==null)"" else " WHERE notebookId IN (${ids.joinToString(","){"?"}})"
    fun count(document:Boolean):Long=query("SELECT COALESCE(SUM((byteCount+${OriginalStorage.CHUNK-1})/${OriginalStorage.CHUNK}),0) FROM ${if(document)"document_sources" else "image_sources"}"+where()).use{it.moveToFirst();it.getLong(0)}
    fun visit(document:Boolean,active:()->Unit={},consume:(List<Any?>)->Unit) {
        val source=if(document)"document_sources" else "image_sources"
        val key=if(document)"id" else "notebookId,digest"
        query("SELECT ${if(document)"id" else "notebookId"},digest,byteCount FROM $source"+where()+" ORDER BY $key").use{rows->while(rows.moveToNext()){
            active();val owner=rows.getString(0);val hash=rows.getString(1);val size=rows.getInt(2)
            val chunks=if(document)"document_chunks" else "image_chunks"
            val condition=if(document)"documentId=?" else "notebookId=? AND digest=?"
            val args=if(document)arrayOf(owner)else arrayOf(owner,hash)
            val original=OriginalStorage.read(db,size,hash,chunks,condition,args)
            var block=ByteArray(minOf(size,OriginalStorage.CHUNK));var at=0;var seen=0;var position=0
            original.copyTo(object:OutputStream(){
                override fun write(value:Int){write(byteArrayOf(value.toByte()),0,1)}
                override fun write(bytes:ByteArray,offset:Int,length:Int){
                    var input=offset;var left=length
                    while(left>0){
                        val n=minOf(left,block.size-at);bytes.copyInto(block,at,input,input+n)
                        at+=n;input+=n;left-=n;seen+=n
                        if(at==block.size){
                            active();consume(if(document)listOf(owner,position.toLong(),block)else listOf(owner,hash,position.toLong(),block))
                            position++;at=0;block=ByteArray(minOf(size-seen,OriginalStorage.CHUNK))
                        }
                    }
                }
            },active)
            check(seen==size&&at==0)
        }}
    }
    fun copyTo(target:NoteDatabase,active:()->Unit) {
        for(document in listOf(true,false)){
            val source=if(document)"document_sources" else "image_sources"
            query("SELECT ${if(document)"id" else "notebookId"},digest,byteCount FROM $source"+where()).use{rows->while(rows.moveToNext()){
                active();val owner=rows.getString(0);val hash=rows.getString(1);val size=rows.getInt(2)
                val original=OriginalStorage.read(db,size,hash,if(document)"document_chunks" else "image_chunks",
                    if(document)"documentId=?" else "notebookId=? AND digest=?",if(document)arrayOf(owner)else arrayOf(owner,hash))
                target.originalStore.publish(size,hash,active){out->original.copyTo(out,active)}
            }}
        }
    }

    /** Archive writers order chunk rows by the declared primary key. Enforcing the same order
     * rejects duplicate, missing and interleaved chunks without retaining every key in memory. */
    class Import(private val db:NoteDatabase,private val active:()->Unit):Closeable {
        private var document=true;private var key:List<String>?=null;private var lastKey:String?=null
        private var writer:OriginalStore.Writer?=null;private var bytes=0;private var size=0;private var position=0
        fun accept(isDocument:Boolean,row:List<Any?>) {
            active()
            val owner=row[0] as String;val hash=if(isDocument)null else row[1] as String
            val nextKey=if(isDocument)listOf(owner)else listOf(owner,checkNotNull(hash))
            val ordinal=row[if(isDocument)1 else 2] as Long
            val payload=row[if(isDocument)2 else 3] as ByteArray
            if(key!=nextKey||document!=isDocument){
                finishCurrent()
                val ordered=(if(isDocument)"0" else "1")+nextKey.joinToString("\u0000")
                require(lastKey==null||ordered>lastKey!!){"ORIGINAL_ARCHIVE_ORDER"}
                lastKey=ordered;key=nextKey;document=isDocument;position=0;bytes=0
                val sql=db.openHelper.readableDatabase
                val metadata=if(isDocument)sql.query("SELECT digest,byteCount FROM document_sources WHERE id=?",arrayOf(owner))
                    else sql.query("SELECT digest,byteCount FROM image_sources WHERE notebookId=? AND digest=?",arrayOf(owner,hash))
                metadata.use{require(it.moveToFirst()){"ORIGINAL_ARCHIVE_OWNER"};size=it.getInt(1);writer=db.originalStore.writer(size,it.getString(0))}
            }
            require(ordinal==position.toLong()&&payload.size==minOf(OriginalStorage.CHUNK,size-bytes)&&payload.isNotEmpty()){"ORIGINAL_ARCHIVE_CHUNK"}
            checkNotNull(writer).write(payload);bytes+=payload.size;position++
        }
        private fun finishCurrent(){
            writer?.let{w->try{require(bytes==size){"ORIGINAL_TRUNCATED"};w.finish(active)}finally{w.close();writer=null}}
        }
        fun finish(){finishCurrent()}
        override fun close(){writer?.close();writer=null}
    }
}
