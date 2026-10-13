// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchObservationTest {
    @Test fun initialRoomEventStartsOneFullDebounceWithoutASyntheticRead()=runTest {
        val f=Fixture(this)
        runCurrent();advanceTimeBy(50);runCurrent()
        f.completed.value++;runCurrent()
        assertTrue(f.reads.isEmpty())
        f.invalidate();runCurrent()
        advanceTimeBy(149);runCurrent();assertTrue(f.reads.isEmpty())
        advanceTimeBy(1);runCurrent()
        assertEquals(listOf(200L),f.reads.map{it.first})
        assertEquals(listOf(1L),f.results.map{it.sourceVersion})
        advanceTimeBy(1_000);runCurrent();assertEquals(1,f.results.size)
    }

    @Test fun noReadOrVersionExistsUntilRoomEmitsItsFirstSnapshot()=runTest {
        val f=Fixture(this,request(query=""))
        runCurrent();advanceTimeBy(1_000);runCurrent()
        assertTrue(f.reads.isEmpty());assertTrue(f.results.isEmpty())
        f.invalidate();runCurrent()
        assertEquals(listOf(1L),f.results.map{it.sourceVersion})
        assertEquals(1_000L,f.reads.single().first)
    }

    @Test fun rapidTypingCancelsOldReadAndRestartsDelayForOnlyTheLatestQuery()=runTest {
        val cancelled=mutableListOf<Long>()
        val f=Fixture(this){r->
            if(r.generation==1L)try{awaitCancellation()}finally{cancelled+=r.generation}
            response(r)
        }
        f.invalidate();runCurrent();advanceTimeBy(150);runCurrent()
        assertEquals(1,f.reads.size)
        f.requests.value=request(2,"ne");runCurrent()
        assertEquals(listOf(1L),cancelled)
        advanceTimeBy(100);f.requests.value=request(3,"new");runCurrent()
        advanceTimeBy(149);runCurrent();assertEquals(1,f.reads.size);assertTrue(f.results.isEmpty())
        advanceTimeBy(1);runCurrent()
        assertEquals(listOf(1L,3L),f.reads.map{it.second.generation})
        assertEquals(listOf(3L),f.results.map{it.generation})
        assertEquals(1L,f.results.single().sourceVersion)
    }

    @Test fun clearingTextAndChangingCategoryTypeOrSortRemainImmediate()=runTest {
        val f=Fixture(this)
        f.invalidate();runCurrent();advanceTimeBy(50)
        f.requests.value=request(2,"");runCurrent()
        assertEquals(50L,f.reads.single().first)
        val changes=listOf(
            request(3,"new").copy(filter="favorite"),
            request(4,"newer").copy(filter="favorite",type="board"),
            request(5,"newest").copy(filter="favorite",type="board",byTitle=true)
        )
        for(r in changes){f.requests.value=r;runCurrent();assertEquals(r.generation,f.results.last().generation)}
        assertTrue(f.reads.all{it.first==50L})
        assertEquals(listOf(2L,3L,4L,5L),f.results.map{it.generation})
    }

    @Test fun realInvalidationRefreshesSameRequestWhileIndexCompletionKeepsTheSourceVersion()=runTest {
        var authorRevision=10L
        val f=Fixture(this,request(query="").copy(offset=60)){r->response(r).copy(favorites=authorRevision.toInt())}
        f.invalidate();runCurrent()
        assertEquals(1L,f.results.last().sourceVersion)
        authorRevision=11
        f.invalidate("page_search_text");runCurrent()
        assertEquals(11,f.results.last().favorites)
        assertEquals(2L,f.results.last().sourceVersion)
        assertEquals(60,f.results.last().offset)
        f.completed.value++;runCurrent()
        assertEquals(listOf(1L,2L,2L),f.results.map{it.sourceVersion})
        f.requests.value=f.requests.value.copy(generation=2,offset=0);runCurrent()
        assertEquals(0,f.results.last().offset);assertEquals(2L,f.results.last().sourceVersion)
        f.invalidate("study_cards");runCurrent()
        assertEquals(3L,f.results.last().sourceVersion)
        assertTrue(f.reads.all{it.first==0L})
    }

    @Test fun aRealEditDuringDebounceIsNotDroppedOrDelayed()=runTest {
        val f=Fixture(this)
        f.invalidate();runCurrent();advanceTimeBy(50)
        f.invalidate("page_search_text");runCurrent()
        assertEquals(50L,f.reads.single().first)
        assertEquals(2L,f.results.single().sourceVersion)
        advanceTimeBy(150);runCurrent();assertEquals(1,f.results.size)
    }

    @Test fun invalidationCancelsAnObsoleteReadAndCollectorCancellationNeverPublishesFailure()=runTest {
        var started=0;var cancelled=0
        val f=Fixture(this,request(query="")) {r->
            started++
            if(started!=2)try{awaitCancellation()}finally{cancelled++}
            response(r)
        }
        f.invalidate();runCurrent();assertEquals(1,started)
        f.invalidate();runCurrent()
        assertEquals(1,cancelled);assertEquals(listOf(2L),f.results.map{it.sourceVersion})
        f.invalidate();runCurrent();assertEquals(3,started)
        f.collector.cancel();runCurrent()
        assertEquals(2,cancelled);assertEquals(1,f.results.size);assertFalse(f.results.single().failed)
    }

    @Test fun failedReadRetainsRequestIdentityAndANewInvalidationCanRecover()=runTest {
        var fail=true
        val r=request(7,"").copy(filter="favorite",type="board",byTitle=true,offset=60)
        val f=Fixture(this,r){if(fail)error("synthetic read failure") else response(it)}
        f.invalidate();runCurrent()
        val failed=f.results.single()
        assertEquals(0L,failed.sourceVersion)
        assertTrue(failed.failed);assertEquals(r.generation,failed.generation);assertEquals(r.query,failed.query)
        assertEquals(r.filter,failed.filter);assertEquals(r.type,failed.type);assertEquals(r.byTitle,failed.byTitle);assertEquals(r.offset,failed.offset)
        fail=false;f.invalidate();runCurrent()
        assertFalse(f.results.last().failed);assertEquals(2L,f.results.last().sourceVersion)
    }

    @Test fun diagnosticsAssociateRealTriggersWithReadsWithoutChangingTheDebounceOrVersion()=runTest {
        val events=mutableListOf<Event>()
        val f=Fixture(this,diagnostics=diagnostics(events))
        runCurrent();advanceTimeBy(50);runCurrent()
        assertEquals(listOf(1L),events.filter{it.stage=="observe-request"}.map{it.generation})
        assertEquals(listOf(0),events.filter{it.stage=="observe-index-initial"}.map{it.count})
        assertTrue(events.none{it.stage=="observe-invalidation"||it.readId!=0L})
        f.invalidate();runCurrent()
        assertEquals(listOf(1L),events.filter{it.stage=="observe-invalidation"}.map{it.version})
        advanceTimeBy(149);runCurrent();assertTrue(f.reads.isEmpty())
        advanceTimeBy(1);runCurrent()
        assertEquals(200L,f.reads.single().first)
        assertEquals(listOf("observe-scheduled","observe-debounce-start","observe-debounce-end",
            "observe-read-start","observe-read-end"),events.filter{it.readId==1L}.map{it.stage})
        advanceTimeBy(25);f.completed.value++;runCurrent()
        assertEquals(listOf(1),events.filter{it.stage=="observe-index-completed"}.map{it.count})
        assertEquals(listOf(1L,1L),f.results.map{it.sourceVersion})
        assertEquals(listOf("observe-scheduled","observe-read-start","observe-read-end"),events.filter{it.readId==2L}.map{it.stage})
        assertTrue(events.filter{it.readId!=0L}.all{it.generation==1L&&it.version==1L})
        assertTrue(events.filter{it.readId==0L&&it.stage!="observe-invalidation"}.all{it.version==-1L})
        assertMonotonicPerRead(events)
    }

    @Test fun diagnosticsDistinguishCancelledDebounceFromCancelledRunningRead()=runTest {
        val events=mutableListOf<Event>()
        val f=Fixture(this,diagnostics=diagnostics(events)){awaitCancellation()}
        f.invalidate();runCurrent();advanceTimeBy(50)
        f.requests.value=request(2,"new");runCurrent()
        assertEquals(listOf("observe-scheduled","observe-debounce-start","observe-debounce-cancel"),
            events.filter{it.readId==1L}.map{it.stage})
        advanceTimeBy(150);runCurrent()
        assertEquals(200L,f.reads.single().first)
        f.invalidate("page_search_text");runCurrent()
        assertEquals("observe-read-cancel",events.last{it.readId==2L}.stage)
        assertEquals(listOf("observe-scheduled","observe-read-start"),events.filter{it.readId==3L}.map{it.stage})
        f.collector.cancel();runCurrent()
        assertEquals("observe-read-cancel",events.last{it.readId==3L}.stage)
        assertTrue(f.results.isEmpty())
        assertTrue(events.none{it.stage=="observe-read-error"||it.stage=="observe-read-end"})
        assertEquals(listOf(1L,2L),events.filter{it.stage=="observe-invalidation"}.map{it.version})
        assertMonotonicPerRead(events)
    }

    @Test fun diagnosticsReportReadErrorsWithoutChangingFailedResultIdentityOrRecovery()=runTest {
        val events=mutableListOf<Event>();var fail=true
        val r=request(7,"").copy(filter="favorite",type="board",byTitle=true,offset=60)
        val f=Fixture(this,r,diagnostics(events)){if(fail)error("synthetic failure") else response(it)}
        f.invalidate();runCurrent()
        assertEquals(listOf("observe-scheduled","observe-read-start","observe-read-error"),
            events.filter{it.readId==1L}.map{it.stage})
        assertEquals(response(r).copy(failed=true),f.results.single())
        fail=false;f.invalidate();runCurrent()
        assertEquals(response(r).copy(sourceVersion=2),f.results.last())
        assertEquals(listOf("observe-scheduled","observe-read-start","observe-read-end"),
            events.filter{it.readId==2L}.map{it.stage})
        assertMonotonicPerRead(events)
    }

    private data class Event(val stage:String,val generation:Long,val version:Long,val readId:Long,val atNs:Long,val count:Int)
    private fun diagnostics(events:MutableList<Event>)=LibrarySearchDiagnostics{stage,generation,version,readId,atNs,count->
        events+=Event(stage,generation,version,readId,atNs,count)
    }
    private fun assertMonotonicPerRead(events:List<Event>){
        for(read in events.filter{it.readId!=0L}.groupBy{it.readId}.values){
            assertTrue(read.zipWithNext().all{(before,after)->after.atNs>=before.atNs})
        }
    }

    private class Fixture(scope:TestScope,initial:LibrarySearchRequest=request(),diagnostics:LibrarySearchDiagnostics?=null,
        read:suspend (LibrarySearchRequest)->LibrarySearchResult=::response) {
        val requests=MutableStateFlow(initial)
        val completed=MutableStateFlow(0)
        private val invalidations=Channel<Set<String>>(Channel.UNLIMITED)
        val reads=mutableListOf<Pair<Long,LibrarySearchRequest>>()
        val results=mutableListOf<LibrarySearchResult>()
        private val observation=LibrarySearchObservation(diagnostics){r,_->reads+=scope.currentTime to r;read(r)}
        val collector=scope.backgroundScope.launch {
            observation.results(requests,invalidations.receiveAsFlow(),completed).toList(results)
        }
        fun invalidate(table:String="notes"){check(invalidations.trySend(setOf(table)).isSuccess)}
    }

    companion object {
        private fun request(generation:Long=1,query:String="needle")=LibrarySearchRequest(generation,emptyList(),emptyMap(),query)
        private fun response(r:LibrarySearchRequest)=LibrarySearchResult(generation=r.generation,query=r.query,
            filter=r.filter,type=r.type,byTitle=r.byTitle,offset=r.offset)
    }
}
