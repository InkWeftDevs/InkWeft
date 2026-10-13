// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Per-collector query scheduling; the repository owns Room and background indexing. */
internal class LibrarySearchObservation(private val diagnostics:LibrarySearchDiagnostics?=null,
    private val read:suspend (LibrarySearchRequest,LibrarySearchReadTrace?)->LibrarySearchResult) {
    var query:String=""
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(requests:Flow<LibrarySearchRequest>,invalidations:Flow<Set<String>>,completed:StateFlow<Int>):Flow<LibrarySearchResult> {
        var previous:LibrarySearchRequest?=null
        // Room emits the first real snapshot event. The runningFold seed is not an
        // invalidation: publishing it lets Room's initial event cancel the first debounce.
        val versions=invalidations.runningFold(0L){version,tables->
            val next=version+1;diagnostics?.event("observe-invalidation",version=next,count=tables.size);next
        }.drop(1)
        val observedRequests=if(diagnostics==null)requests else requests.onEach{diagnostics.event("observe-request",generation=it.generation)}
        val observedCompleted=if(diagnostics==null)completed else completed.onEach{
            diagnostics.event(if(it==0)"observe-index-initial" else "observe-index-completed",count=it)
        }
        return combine(observedRequests,versions,observedCompleted){request,version,_->request to version}.mapLatest{(request,version)->
            val before=previous;previous=request;query=request.query
            val trace=diagnostics?.read(request.generation,version)
            trace?.event("observe-scheduled")
            // Text edits debounce; deleting the query and changing a category take effect immediately.
            if(request.query.isNotBlank()&&before?.query!=request.query&&
                (before==null||before.filter==request.filter&&before.type==request.type&&before.byTitle==request.byTitle)){
                trace?.event("observe-debounce-start")
                try{delay(150)}catch(c:CancellationException){trace?.event("observe-debounce-cancel");throw c}
                trace?.event("observe-debounce-end")
            }
            trace?.event("observe-read-start")
            try{read(request,trace).copy(sourceVersion=version).also{trace?.event("observe-read-end",it.items.size)}}
            catch(c:CancellationException){trace?.event("observe-read-cancel");throw c}
            catch(_:Exception){trace?.event("observe-read-error");LibrarySearchResult(generation=request.generation,failed=true,query=request.query,
                filter=request.filter,type=request.type,byTitle=request.byTitle,offset=request.offset)}
        }
    }
}
