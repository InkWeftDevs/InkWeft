// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PageVersionState(val head:Long?=null,val error:String?=null)

/** A result must survive dialog subcomposition, including a very fast Room read. */
internal class PageVersionPreview(private val read:suspend ()->Long){
    private val mutable=MutableStateFlow(PageVersionState())
    val state=mutable.asStateFlow()
    suspend fun load(){
        mutable.value=PageVersionState()
        try{
            // Bound dispatch as well as the database query, not just the query
            // after an IO worker becomes available.
            val head=withTimeout(8_000){withContext(Dispatchers.IO){read()}}
            mutable.value=PageVersionState(head)
        }catch(_:TimeoutCancellationException){
            mutable.value=PageVersionState(error="页面版本读取超时，未执行操作。可以重试核对或取消。")
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){mutable.value=PageVersionState(error="无法读取页面版本，未修改原资料。请重试核对。")}
    }
}
