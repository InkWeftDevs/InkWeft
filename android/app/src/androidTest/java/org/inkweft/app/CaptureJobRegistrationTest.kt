// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Registration protocol only; the existing excerpt/continuous UI tests exercise the production callers. */
class CaptureJobRegistrationTest {
    private class Owner(val scope:CoroutineScope) {
        var job:Job?=null
        var busy=false
        var failures=0
        fun start(work:suspend ()->Unit):Job {
            busy=true
            val next=scope.launch(start=CoroutineStart.LAZY){
                val request=currentCoroutineContext().job
                try{work()}catch(c:CancellationException){throw c}catch(_:Exception){failures++}
                finally{if(job===request){busy=false;job=null}}
            }
            job=next;next.start();return next
        }
    }

    @Test fun immediateSuccessAndCompletedFailureBothReleaseTheirOwnGate()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try{
            val owner=Owner(scope)
            val success=owner.start{}
            assertTrue(success.isCompleted);assertFalse(owner.busy);assertNull(owner.job)
            // A checkpoint's already-failed prepared Deferred can throw without suspending on pen-up.
            val prepared=CompletableDeferred<Unit>().apply{completeExceptionally(IllegalStateException("synthetic read failure"))}
            val failure=owner.start{prepared.await()}
            assertTrue(failure.isCompleted);assertFalse(owner.busy);assertNull(owner.job);assertEquals(1,owner.failures)
        }finally{scope.cancel()}
    }

    @Test fun suspendedCompletionKeepsGateUntilItsOwnWorkFinishes()=runBlocking {
        withTimeout(5_000){
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try{
                val owner=Owner(scope);val release=CompletableDeferred<Unit>()
                val job=owner.start{release.await()}
                assertTrue(job.isActive);assertTrue(owner.busy);assertSame(job,owner.job)
                release.complete(Unit);job.join()
                assertFalse(owner.busy);assertNull(owner.job)
            }finally{scope.cancel()}
        }
    }

    @Test fun olderCompletionOrCancellationCannotUnlockItsReplacement()=runBlocking {
        withTimeout(5_000){
            for(cancelOld in listOf(false,true)){
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
                try{
                    val owner=Owner(scope);val oldRelease=CompletableDeferred<Unit>();val newRelease=CompletableDeferred<Unit>()
                    val old=owner.start{withContext(NonCancellable){oldRelease.await()}}
                    if(cancelOld)old.cancel()
                    val next=owner.start{newRelease.await()}
                    oldRelease.complete(Unit);old.join()
                    assertEquals(cancelOld,old.isCancelled)
                    assertTrue(owner.busy);assertSame(next,owner.job);assertTrue(next.isActive)
                    newRelease.complete(Unit);next.join()
                    assertFalse(owner.busy);assertNull(owner.job);assertEquals(0,owner.failures)
                }finally{scope.cancel()}
            }
        }
    }
}
