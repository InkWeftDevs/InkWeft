// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

/** Hook logic only: these tests do not exercise Room, SQLite or Android timing. */
class SaveBoundaryDiagnosticsTest {
    private data class Event(val stage:String,val sample:Int,val task:Long,val transaction:Long,
        val depth:Int,val successful:Boolean,val outcome:String)

    @Test fun executorRunsOnceRestoresThreadContextAndPreservesFailure() {
        val events=ArrayList<Event>()
        val trace=SaveBoundaryDiagnostics { stage,sample,task,transaction,depth,successful,outcome ->
            events+=Event(stage,sample,task,transaction,depth,successful,outcome)
        }
        var pending:Runnable?=null
        var submissions=0
        val executor=trace.executor(Executor { submissions++;pending=it })
        trace.sample.set(7)
        val failure=IllegalStateException("delegate failure")
        var runs=0
        executor.execute { runs++;assertEquals(7,trace.sample.get());throw failure }
        trace.sample.set(99)
        try { pending!!.run();fail("Expected original failure") }
        catch(actual:IllegalStateException){assertSame(failure,actual)}
        assertEquals(1,submissions);assertEquals(1,runs);assertEquals(99,trace.sample.get())
        assertEquals(listOf("writer-submit","writer-run-enter","writer-run-exit"),events.map{it.stage})
        assertTrue(events.all{it.sample==7&&it.task==events.first().task})
        assertEquals("threw",events.last().outcome)
        assertFalse(trace.invalid)
        trace.sample.remove()
    }

    @Test fun callbackFailureDoesNotChangeCommitActionsOrOriginalExceptions() {
        val observerFailure=IllegalArgumentException("observer")
        val trace=SaveBoundaryDiagnostics { _,_,_,_,_,_,_->throw observerFailure }
        val actions=ArrayList<String>()
        trace.begin("sqlite"){actions+="begin"}
        trace.success("sqlite"){actions+="success"}
        trace.end("sqlite"){actions+="end"}
        assertEquals(listOf("begin","success","end"),actions)
        assertTrue(trace.invalid)
        val delegateFailure=IllegalStateException("delegate")
        try { trace.begin("sqlite"){throw delegateFailure};fail("Expected delegate failure") }
        catch(actual:IllegalStateException){assertSame(delegateFailure,actual)}
    }

    @Test fun nestedTransactionsKeepOneOuterIdentityAndReportDepth() {
        val events=ArrayList<Event>()
        val trace=SaveBoundaryDiagnostics { stage,sample,task,transaction,depth,successful,outcome ->
            events+=Event(stage,sample,task,transaction,depth,successful,outcome)
        }
        trace.begin("sqlite"){}
        trace.begin("sqlite"){}
        trace.success("sqlite"){}
        trace.end("sqlite"){}
        trace.success("sqlite"){}
        trace.end("sqlite"){}
        assertEquals(listOf(1,2),events.filter{it.stage=="sqlite-begin-enter"}.map{it.depth})
        assertEquals(listOf(2,1),events.filter{it.stage=="sqlite-end-exit"}.map{it.depth})
        assertEquals(1,events.map{it.transaction}.distinct().size)
        assertTrue(events.filter{it.stage=="sqlite-end-exit"}.all{it.successful&&it.outcome=="returned"})
        assertFalse(trace.invalid)
    }

    @Test fun failedBeginDoesNotPushAndFailedEndInvalidatesTheTrace() {
        val events=ArrayList<Event>()
        val trace=SaveBoundaryDiagnostics { stage,sample,task,transaction,depth,successful,outcome ->
            events+=Event(stage,sample,task,transaction,depth,successful,outcome)
        }
        val failure=IllegalStateException("delegate")
        try { trace.begin("room"){throw failure};fail("Expected BEGIN failure") }
        catch(actual:IllegalStateException){assertSame(failure,actual)}
        trace.begin("room"){}
        try { trace.end("room"){throw failure};fail("Expected END failure") }
        catch(actual:IllegalStateException){assertSame(failure,actual)}
        assertEquals(listOf(1,1),events.filter{it.stage=="room-begin-enter"}.map{it.depth})
        assertEquals("threw",events.last().outcome)
        assertTrue(trace.invalid)
    }
}
