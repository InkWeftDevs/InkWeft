// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Per-collector query scheduling; the repository owns Room and background indexing. */
internal class LibrarySearchObservation(private val read:suspend (LibrarySearchRequest)->LibrarySearchResult) {
    var query:String=""
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(requests:Flow<LibrarySearchRequest>,invalidations:Flow<Set<String>>,completed:StateFlow<Int>):Flow<LibrarySearchResult> {
        var previous:LibrarySearchRequest?=null
        // Room emits the first real snapshot event. The runningFold seed is not an
        // invalidation: publishing it lets Room's initial event cancel the first debounce.
        val versions=invalidations.runningFold(0L){version,_->version+1}.drop(1)
        return combine(requests,versions,completed){request,version,_->request to version}.mapLatest{(request,version)->
            val before=previous;previous=request;query=request.query
            // Text edits debounce; deleting the query and changing a category take effect immediately.
            if(request.query.isNotBlank()&&before?.query!=request.query&&
                (before==null||before.filter==request.filter&&before.type==request.type&&before.byTitle==request.byTitle))delay(150)
            try{read(request).copy(sourceVersion=version)}catch(c:CancellationException){throw c}
            catch(_:Exception){LibrarySearchResult(generation=request.generation,failed=true,query=request.query,
                filter=request.filter,type=request.type,byTitle=request.byTitle,offset=request.offset)}
        }
    }
}
