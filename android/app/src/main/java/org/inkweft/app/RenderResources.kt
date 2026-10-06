package org.inkweft.app

import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
internal class RenderBudgetBusy(val requestedBytes:Long=0,val usedBytes:Long=Long.MAX_VALUE):IllegalStateException("RENDER_BUDGET_BUSY")
/** Logical retained bytes, counted once per allocation. Driver/GPU allocations are not inferred. */
internal object RenderResources {
    enum class Role { CACHE, IN_FLIGHT, ACTIVE, LIVE_INK }
    private data class Entry(var bytes:Long,val category:String,val owners:MutableMap<String,Role>)
    private val resources=WeakHashMap<Any,Entry>()
    private val trimmers=CopyOnWriteArrayList<()->Unit>()
    private var estimated=0L;private var peak=0L
    val inFlightJobs=java.util.concurrent.atomic.AtomicInteger()
    val cancelledJobs=java.util.concurrent.atomic.AtomicInteger()
    const val BUDGET=128L*1024*1024
    @Synchronized fun track(resource:Any,bytes:Long,category:String,owner:String,role:Role){
        val e=resources.getOrPut(resource){Entry(0,category,mutableMapOf())};estimated+=bytes-e.bytes;e.bytes=bytes;peak=maxOf(peak,estimated);e.owners[owner]=role
    }
    @Synchronized fun release(resource:Any,owner:String){resources[resource]?.let{it.owners.remove(owner);if(it.owners.isEmpty()){estimated-=it.bytes;resources.remove(resource)}}}
    @Synchronized fun releaseOwner(owner:String){val it=resources.entries.iterator();while(it.hasNext()){val e=it.next().value;e.owners.remove(owner);if(e.owners.isEmpty()){estimated-=e.bytes;it.remove()}}}
    /** A replaced frame only makes room when this owner holds its last accounted reference. */
    @Synchronized fun canReplace(resource:Any,owner:String,busy:RenderBudgetBusy):Boolean {
        val entry=resources[resource]?:return false
        if(entry.owners.size!=1||entry.owners[owner]!=Role.ACTIVE||busy.requestedBytes<=0)return false
        // A failed decoder's temporary byte lease may already be released at its caller.
        val retained=maxOf(resources.values.sumOf{it.bytes},busy.usedBytes)-entry.bytes
        return retained<=BUDGET&&busy.requestedBytes<=BUDGET-retained
    }
    @Synchronized fun snapshot():Map<String,Long>{
        val values=resources.values.toList();estimated=values.sumOf{it.bytes};val out=mutableMapOf("totalBytes" to estimated,"budgetBytes" to BUDGET,"peakTrackedBytes" to peak,"inFlightJobs" to inFlightJobs.get().toLong(),"cancelledJobs" to cancelledJobs.get().toLong())
        Role.entries.forEach{r->out[r.name.lowercase()+"Bytes"]=values.filter{it.owners.values.maxByOrNull{v->v.ordinal}==r}.sumOf{it.bytes}}
        values.groupBy{it.category}.forEach{(k,v)->out["category.$k"]=v.sumOf{it.bytes}}
        return out
    }
    fun onTrim(callback:()->Unit){trimmers.add(callback)}
    fun trim(){trimmers.forEach{it()}}
    /** Active input has priority; background frames are deferred when pinned allocations fill the budget. */
    fun admit(bytes:Long,live:Boolean=false){
        if(synchronized(this){estimated}+bytes<=BUDGET)return
        trim()
        val used=snapshot().getValue("totalBytes")
        if(!live&&used+bytes>BUDGET)throw RenderBudgetBusy(bytes,used)
    }
}
/** Comparisons are cached only for immutable instances, including their erase masks. */
internal class InkEqualityCache {
    private data class PairRef(val a:java.lang.ref.WeakReference<org.inkweft.core.InkStroke>,val b:java.lang.ref.WeakReference<org.inkweft.core.InkStroke>,val same:Boolean)
    private val entries=object:LinkedHashMap<Long,PairRef>(128,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<Long,PairRef>?)=size>2048}
    fun same(a:org.inkweft.core.InkStroke,b:org.inkweft.core.InkStroke,compare:()->Boolean):Boolean{
        if(a===b)return true
        val key=(System.identityHashCode(a).toLong() shl 32) xor (System.identityHashCode(b).toLong() and 0xffffffffL)
        entries[key]?.let{if(it.a.get()===a&&it.b.get()===b)return it.same}
        android.os.Trace.beginSection("InkWeft.equal-strokes")
        return try{compare().also{entries[key]=PairRef(java.lang.ref.WeakReference(a),java.lang.ref.WeakReference(b),it)}}finally{android.os.Trace.endSection()}
    }
    fun clear(){entries.clear()}
}
