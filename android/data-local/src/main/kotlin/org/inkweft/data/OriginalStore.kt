// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.inkweft.core.OriginalBytes
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest

/** Durable, immutable originals owned by one database. Publish before committing a reference;
 * a failed database transaction leaves only an orphan, never a reference to partial bytes.
 * Callers serialize publication and reference collection with the database write transaction. */
internal class OriginalStore(internal val root:File,private val availableBytes:()->Long={root.usableSpace},private val syncDirectory:(File)->Unit) {
    private data class Stamp(val size:Long,val modified:Long)
    private val lock=Any()
    private val verified=LinkedHashMap<String,Stamp>(16,.75f,true)
    private val leases=mutableMapOf<String,Int>()
    private val pending=mutableSetOf<File>()
    private fun markVerified(hash:String,stamp:Stamp){
        verified[hash]=stamp
        while(verified.size>128){val entries=verified.entries.iterator();entries.next();entries.remove()}
    }
    private fun file(hash:String):File {
        require(hash.matches(HASH)){"ORIGINAL_DIGEST_INVALID"}
        return File(root,"$hash.original")
    }
    fun contains(hash:String)=file(hash).isFile
    private fun verify(size:Int,hash:String,active:()->Unit):File {
        require(size>0)
        val f=file(hash);require(f.isFile){"ORIGINAL_FILE_MISSING"}
        require(f.length()==size.toLong()){"ORIGINAL_LENGTH_CHANGED"}
        val stamp=Stamp(f.length(),f.lastModified())
        if(verified[hash]!=stamp){
            require(OriginalBytes.file(f,size,active).sha256==hash){"ORIGINAL_CHECKSUM_CHANGED"}
            markVerified(hash,stamp)
        }
        active();return f
    }
    private fun lease(f:File,hash:String):OriginalFileCache.Lease {
        leases[hash]=(leases[hash]?:0)+1
        return OriginalFileCache.Lease(f){synchronized(lock){
            val count=checkNotNull(leases[hash])-1
            if(count==0)leases.remove(hash)else leases[hash]=count
        }}
    }
    fun acquire(size:Int,hash:String,active:()->Unit={}):OriginalFileCache.Lease=synchronized(lock){lease(verify(size,hash,active),hash)}
    fun content(size:Int,hash:String):OriginalBytes=OriginalBytes.stream(size,hash){
        // OriginalBytes.copyTo verifies the complete stream. Avoid a second full-file read
        // before every cold export; seekable native decoder leases use acquire() above.
        val lease=synchronized(lock){
            val f=file(hash);require(f.isFile){"ORIGINAL_FILE_MISSING"}
            require(f.length()==size.toLong()){"ORIGINAL_LENGTH_CHANGED"};this@OriginalStore.lease(f,hash)
        }
        try{object:FilterInputStream(lease.file.inputStream()){
            override fun close(){try{super.close()}finally{lease.close()}}
        }}catch(t:Throwable){lease.close();throw t}
    }
    fun publish(size:Int,hash:String,active:()->Unit={},validate:(File)->Unit={},copy:(OutputStream)->Unit) {
        synchronized(lock){if(contains(hash)){validate(verify(size,hash,active));active();syncDirectory(root);return}}
        writer(size,hash).use{writer->copy(writer);active();writer.finish(active,validate)}
    }
    fun writer(size:Int,hash:String):Writer=synchronized(lock){
        require(size>0);file(hash)
        if(!root.isDirectory){
            check(root.mkdir()){"ORIGINAL_STORAGE_UNAVAILABLE"}
        }
        // Retry a failed parent-directory sync even when the first attempt created root.
        syncDirectory(checkNotNull(root.parentFile))
        require(availableBytes()>=size.toLong()+RESERVE){"ORIGINAL_LOW_SPACE"}
        val part=File.createTempFile("pending-",".part",root);pending+=part
        try{Writer(size,hash,part)}catch(t:Throwable){pending-=part;part.delete();throw t}
    }
    inner class Writer internal constructor(private val size:Int,private val hash:String,private val part:File):OutputStream(),Closeable {
        private val output=FileOutputStream(part)
        private val digest=MessageDigest.getInstance("SHA-256")
        private var count=0L;private var closed=false;private var finished=false
        override fun write(value:Int){write(byteArrayOf(value.toByte()),0,1)}
        override fun write(bytes:ByteArray,offset:Int,length:Int){
            check(!closed&&!finished);require(offset>=0&&length>=0&&offset<=bytes.size-length)
            require(length.toLong()<=size-count){"ORIGINAL_LENGTH_CHANGED"}
            output.write(bytes,offset,length);digest.update(bytes,offset,length);count+=length
        }
        fun finish(active:()->Unit={},validate:(File)->Unit={})=synchronized(lock){
            check(!closed&&!finished);active()
            require(count==size.toLong()){"ORIGINAL_TRUNCATED"}
            require(digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==hash){"ORIGINAL_CHECKSUM_CHANGED"}
            output.flush();validate(part);active();output.fd.sync();output.close();closed=true
            val complete=file(hash)
            if(complete.exists()){
                verify(size,hash,active);check(part.delete()){"ORIGINAL_STAGING_CLEANUP"}
                syncDirectory(root)
            }else{
                check(part.renameTo(complete)){"ORIGINAL_FILE_PUBLICATION"}
                // Both the contents and the new directory entry precede the database commit.
                syncDirectory(root)
                markVerified(hash,Stamp(complete.length(),complete.lastModified()))
            }
            pending-=part;finished=true
        }
        override fun close()=synchronized(lock){
            try{if(!closed){closed=true;output.close()}}finally{pending-=part;if(!finished)part.delete()}
        }
    }
    data class Reclaimed(val files:Int,val bytes:Long)
    /** referenced must query the same database transaction that protects concurrent attach writes.
     * Directory iteration and reference queries are streaming; no library-sized hash set is built. */
    fun collect(referenced:(String)->Boolean,active:()->Unit={}):Reclaimed=synchronized(lock){
        if(!root.exists())return Reclaimed(0,0)
        var files=0;var bytes=0L
        Files.newDirectoryStream(root.toPath()).use{paths->for(path in paths){
            active();val f=path.toFile();val hash=f.name.removeSuffix(".original")
            val owned=f.name.endsWith(".original")&&hash.matches(HASH)
            val stalePart=f.name.startsWith("pending-")&&f.name.endsWith(".part")&&f !in pending
            if(f.isFile&&!Files.isSymbolicLink(path)&&(stalePart||owned&&leases[hash]==null&&!referenced(hash))){
                val size=f.length();check(f.delete()){"ORIGINAL_RECLAIM_FAILED"}
                verified.remove(hash);files++;bytes+=size
            }
        }}
        if(files>0)syncDirectory(root)
        Reclaimed(files,bytes)
    }
    fun diskBytes():Long {
        if(!root.isDirectory)return 0
        var bytes=0L;Files.newDirectoryStream(root.toPath()).use{paths->for(p in paths)if(Files.isRegularFile(p)&&!Files.isSymbolicLink(p))bytes+=Files.size(p)}
        return bytes
    }
    companion object {
        private val HASH=Regex("[0-9a-f]{64}")
        private const val RESERVE=32L*1024*1024
    }
}
