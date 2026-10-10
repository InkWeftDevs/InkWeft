// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*

/** Rebuildable, verified seekable files. Leases prevent eviction while a native decoder owns a file. */
class OriginalFileCache(private val root:File,private val budget:Long=1_073_741_824L) {
    private data class Entry(val file:File,val size:Int,var leases:Int=0,val modified:Long=file.lastModified(),var verified:Boolean=true)
    private val lock=Mutex()
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    private var scanned=false
    class Lease internal constructor(val file:File,private val release:()->Unit):Closeable {
        private var closed=false
        override fun close(){synchronized(this){if(!closed){closed=true;release()}}}
    }
    /** Mutex serializes cache publication; readers of leased files do not hold it. */
    suspend fun acquire(size:Int,hash:String,copy:(OutputStream,()->Unit)->Unit):Lease {
        var acquired:Lease?=null
        try{return withContext(Dispatchers.IO){
        require(size>0&&size.toLong()<=budget&&hash.matches(Regex("[0-9a-f]{64}")))
        val context=currentCoroutineContext()
        lock.withLock {
            check(root.isDirectory||root.mkdirs()){"ORIGINAL_CACHE_UNAVAILABLE"}
            // This directory has one cache owner. After restart, verify each surviving file once on first use.
            if(!scanned){
                root.listFiles()?.sortedBy{it.lastModified()}?.forEach{f->
                    val key=f.name.removeSuffix(".original");val bytes=f.length()
                    if(f.name=="$key.original"&&key.matches(Regex("[0-9a-f]{64}"))&&bytes in 1..minOf(budget,Int.MAX_VALUE.toLong()))
                        synchronized(entries){entries[key]=Entry(f,bytes.toInt(),verified=false)}
                    else f.delete()
                };scanned=true
            }
            val hit=synchronized(entries){entries[hash]?.takeIf{it.size==size&&it.file.isFile&&it.file.length()==size.toLong()&&it.file.lastModified()==it.modified}?.also{it.leases++}}
            if(hit!=null){
                val lease=Lease(hit.file){synchronized(entries){hit.leases--}}
                try {
                    if(!hit.verified){
                        val original=org.inkweft.core.OriginalBytes.file(hit.file,size){context.ensureActive()}
                        require(original.sha256==hash){"ORIGINAL_CHECKSUM_CHANGED"}
                        hit.verified=true
                    }
                    return@withLock lease.also{acquired=it}
                }catch(c:CancellationException){lease.close();throw c}
                catch(_:Exception){lease.close();synchronized(entries){entries.remove(hash);hit.file.delete()}}
            }
            synchronized(entries){
                entries[hash]?.let{old->require(old.leases==0){"ORIGINAL_CACHE_CHANGED"};old.file.delete();entries.remove(hash)}
                val it=entries.entries.iterator();var used=entries.values.sumOf{e->e.size.toLong()}
                while(it.hasNext()&&(used+size>budget||root.usableSpace<size.toLong()+RESERVE)){
                    val e=it.next().value;if(e.leases==0&&e.file.delete()){used-=e.size;it.remove()}
                }
                require(used+size<=budget){"ORIGINAL_CACHE_BUSY"}
                require(root.usableSpace>=size.toLong()+RESERVE){"ORIGINAL_LOW_SPACE"}
            }
            val part=File.createTempFile("pending-",".part",root)
            try{
                FileOutputStream(part).use{out->copy(out){context.ensureActive()};out.flush()}
                context.ensureActive();require(part.length()==size.toLong()){"ORIGINAL_LENGTH_CHANGED"}
                val complete=File(root,"$hash.original");check(part.renameTo(complete)){"ORIGINAL_CACHE_PUBLICATION"}
                val e=Entry(complete,size,1);synchronized(entries){entries[hash]=e}
                Lease(complete){synchronized(entries){e.leases--}}.also{acquired=it}
            }finally{part.delete()}
        }
        }}catch(t:Throwable){acquired?.close();throw t}
    }
    fun trim(){
        if(!lock.tryLock())return // A materialization is in flight; memory-pressure callbacks never block it.
        try{synchronized(entries){
            if(!scanned){root.listFiles()?.forEach{it.delete()};scanned=true}
            val it=entries.entries.iterator();while(it.hasNext()){val e=it.next().value;if(e.leases==0&&e.file.delete())it.remove()}
        }}finally{lock.unlock()}
    }
    fun snapshot():Pair<Long,Int> = synchronized(entries){entries.values.sumOf{it.size.toLong()} to entries.values.count{it.leases>0}}
    fun diskBytes():Long=root.listFiles()?.filter{it.isFile}?.sumOf{it.length()}?:0L
    companion object {private const val RESERVE=32L*1024*1024}
}
