// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class EditDomain { INK, OBJECT, AUTHORING }
internal data class HistoryHeads(val undo:EditDomain?=null,val redo:EditDomain?=null)
/** Chronological order across ink and objects; each domain keeps its own immutable inverse. */
internal class EditorHistory {
    private val undo=ArrayDeque<EditDomain>();private val redo=ArrayDeque<EditDomain>()
    private val mutable=MutableStateFlow(HistoryHeads());val state=mutable.asStateFlow()
    fun forget(domain:EditDomain){undo.removeAll{it==domain};redo.removeAll{it==domain};mutable.value=HistoryHeads(undo.lastOrNull(),redo.lastOrNull())}
    fun committed(domain:EditDomain,direction:Int=0){
        when(direction){
            -1->{if(undo.lastOrNull()==domain)undo.removeLast();redo.addLast(domain)}
            1->{if(redo.lastOrNull()==domain)redo.removeLast();undo.addLast(domain)}
            else->{undo.addLast(domain);redo.clear()}
        }
        while(undo.size>10)undo.removeFirst()
        mutable.value=HistoryHeads(undo.lastOrNull(),redo.lastOrNull())
    }
}
