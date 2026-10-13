// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import java.util.concurrent.atomic.AtomicLong

/** Optional coarse tracing. Never contains search text, author content or persistent IDs.
 * Only installing an observer allocates this counter; only enabled events read the clock.
 * Version -1 means no Room version is associated; readId 0 denotes an upstream event.
 * Count -1 means this boundary has no existing cheap count (no diagnostic SQL is run). */
internal class LibrarySearchDiagnostics(val observer:(String,Long,Long,Long,Long,Int)->Unit) {
    private val nextRead=AtomicLong()
    fun read(generation:Long,version:Long)=LibrarySearchReadTrace(this,generation,version,nextRead.incrementAndGet())
    fun event(stage:String,generation:Long=-1,version:Long=-1,readId:Long=0,count:Int=-1){
        observer(stage,generation,version,readId,System.nanoTime(),count)
    }
}

internal class LibrarySearchReadTrace(private val diagnostics:LibrarySearchDiagnostics,
    private val generation:Long,private val version:Long,private val readId:Long) {
    fun event(stage:String,count:Int=-1)=diagnostics.event(stage,generation,version,readId,count)
}
