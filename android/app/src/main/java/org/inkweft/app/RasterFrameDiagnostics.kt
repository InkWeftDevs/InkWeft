// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.os.Debug
import android.os.Process
import androidx.ink.strokes.Stroke

/** Explicit, per-raster diagnostic storage. It owns no strokes, bitmaps or callbacks. */
internal class RasterFrameDiagnostics {
    enum class Stage {
        REQUEST,WORKER_START,METADATA_START,RASTER_START,PREPARE_START,PREPARE_END,
        PUBLICATION_ENTER,PUBLISHED,NOTIFY_ENQUEUE,NOTIFY_CALLBACK,READY_DRAW_START,READY_DRAW_END,
        WORKER_FINALLY,CLEAR_REQUESTED,SUPERSEDED,CANCELLED,FAILED,BUDGET_BUSY
    }
    data class Snapshot(
        val phase:String,val generation:Int,val revision:Long,val times:Map<Stage,Long>,val threads:Map<Stage,Int>,
        val notificationRequests:Int,val notificationCallbacks:Int,val notificationsSuperseded:Int,
        val nativeAttempts:Int,val nativeCompleted:Int,val nativeWallNanos:Long,val nativeCpuNanos:Long?
    )
    @Volatile var phase="unset"
    private val frames=ArrayDeque<Frame>()
    var dropped=0;private set
    @Synchronized fun begin(generation:Int,revision:Long,requestedNanos:Long):Frame {
        if(frames.size==16){frames.removeFirst();dropped++}
        return Frame(phase,generation,revision).also{it.stamp(Stage.REQUEST,requestedNanos);frames.addLast(it)}
    }
    @Synchronized fun snapshots():List<Snapshot> = frames.map{it.snapshot()}

    class Frame(private val phase:String,val generation:Int,private val revision:Long) {
        private val times=LongArray(Stage.entries.size)
        private val threads=IntArray(Stage.entries.size)
        private var notificationRequests=0;private var notificationCallbacks=0;private var notificationsSuperseded=0
        private var nativeAttempts=0;private var nativeCompleted=0;private var nativeWallNanos=0L
        private var nativeCpuNanos:Long?=0L
        /** First timestamp wins; repeated coalesced notifications have separate counters. */
        @Synchronized fun stamp(stage:Stage,atNanos:Long=System.nanoTime()) {
            if(times[stage.ordinal]==0L){times[stage.ordinal]=atNanos;threads[stage.ordinal]=Process.myTid()}
        }
        @Synchronized fun enqueued(){notificationRequests++;stamp(Stage.NOTIFY_ENQUEUE)}
        @Synchronized fun notified(){notificationCallbacks++;stamp(Stage.NOTIFY_CALLBACK)}
        @Synchronized fun notificationSuperseded(){notificationsSuperseded++}
        @Synchronized fun ready(drawStarted:Long){stamp(Stage.READY_DRAW_START,drawStarted);stamp(Stage.READY_DRAW_END)}
        // This call is synchronous: no thread CPU difference is taken across a coroutine suspension.
        // It includes render-input/brush preparation as well as the native Stroke constructor.
        fun measureNative(build:()->Stroke):Stroke {
            val started=System.nanoTime();val cpu=Debug.threadCpuTimeNanos();val thread=Process.myTid()
            var complete=false
            try{return build().also{complete=true}}
            finally{
                val ended=System.nanoTime();val endCpu=Debug.threadCpuTimeNanos();val endThread=Process.myTid()
                synchronized(this){
                    nativeAttempts++;if(complete)nativeCompleted++
                    nativeWallNanos+=ended-started
                    nativeCpuNanos=if(thread==endThread&&cpu>=0&&endCpu>=cpu)nativeCpuNanos?.plus(endCpu-cpu)else null
                }
            }
        }
        @Synchronized fun snapshot()=Snapshot(phase,generation,revision,
            Stage.entries.filter{times[it.ordinal]!=0L}.associateWith{times[it.ordinal]},
            Stage.entries.filter{times[it.ordinal]!=0L}.associateWith{threads[it.ordinal]},
            notificationRequests,notificationCallbacks,notificationsSuperseded,nativeAttempts,nativeCompleted,nativeWallNanos,nativeCpuNanos)
    }
}
