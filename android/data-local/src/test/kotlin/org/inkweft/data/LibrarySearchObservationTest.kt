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

    private class Fixture(scope:TestScope,initial:LibrarySearchRequest=request(),read:suspend (LibrarySearchRequest)->LibrarySearchResult=::response) {
        val requests=MutableStateFlow(initial)
        val completed=MutableStateFlow(0)
        private val invalidations=Channel<Set<String>>(Channel.UNLIMITED)
        val reads=mutableListOf<Pair<Long,LibrarySearchRequest>>()
        val results=mutableListOf<LibrarySearchResult>()
        private val observation=LibrarySearchObservation {r->reads+=scope.currentTime to r;read(r)}
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
