// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import org.inkweft.core.*
import kotlin.reflect.KProperty

/** One observable transition changes tool, selection mode and return tools together. */
internal class EditorSession(initial:EditorSessionState=EditorSessionState()) {
    var state by mutableStateOf(initial)
        private set
    operator fun getValue(owner:Any?,property:KProperty<*>)=state.tool
    operator fun setValue(owner:Any?,property:KProperty<*>,tool:CanvasTool){state=state.choose(tool)}
    fun select(mode:CanvasSelectionMode){state=state.select(mode)}
    fun continuous(){state=state.continuous()}
    companion object {
        val saver=Saver<EditorSession,ArrayList<String>>(
            save={ArrayList(it.state.saved())},restore={EditorSessionState.restore(it)?.let(::EditorSession)})
    }
}

/** All three author domains use the same live layer projection and chronological history. */
internal fun bindPageEditor(ink:InkViewModel,objects:PageObjectViewModel,authoring:PageAuthoringViewModel) {
    authoring.history=ink.history;objects.history=ink.history
    ink.layerScopeProvider=authoring::scopeForWrite;objects.layerScopeProvider=authoring::scopeForWrite
    ink.editableContent={authoring.ui.value.state.layers.editable(LayerContent(LayerContentKind.INK,it))}
    objects.editableContent={authoring.ui.value.state.layers.editable(LayerContent(LayerContentKind.OBJECT,it))}
    objects.editableInk=ink.editableContent
}
