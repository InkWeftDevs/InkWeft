// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

enum class CanvasTool {
    PEN, SECOND_PEN, HIGHLIGHTER, ERASER, SELECTION, OBJECTS, BEAUTIFY, TAPE;
    val isPen get()=this==PEN||this==SECOND_PEN
    val isWriting get()=isPen||this==HIGHLIGHTER
    val supportsContinuous get()=isWriting||this==ERASER
    val penSlot get()=when(this){SECOND_PEN->1;HIGHLIGHTER->2;else->0}
    companion object {
        fun writingSlot(slot:Int)=when(slot){0->PEN;1->SECOND_PEN;2->HIGHLIGHTER;else->error("INVALID_PEN_SLOT")}
    }
}
enum class CanvasSelectionMode { SELECT, EXCERPT, ERASE }

/** Book-scoped preferences. Author heads, pending commands and undo stay with their owners. */
data class EditorSessionState(
    val tool:CanvasTool=CanvasTool.PEN,
    val selection:CanvasSelectionMode=CanvasSelectionMode.SELECT,
    val lastWriting:CanvasTool=CanvasTool.PEN,
    val lastPen:CanvasTool=CanvasTool.PEN
) {
    init {
        require(lastWriting.isWriting&&lastPen.isPen)
        require(tool==CanvasTool.SELECTION||selection==CanvasSelectionMode.SELECT)
    }
    fun choose(tool:CanvasTool)=copy(tool=tool,selection=CanvasSelectionMode.SELECT,
        lastWriting=if(tool.isWriting)tool else lastWriting,lastPen=if(tool.isPen)tool else lastPen)
    fun select(mode:CanvasSelectionMode)=copy(tool=CanvasTool.SELECTION,selection=mode)
    fun continuous()=if(tool.supportsContinuous)this else choose(lastWriting)
    fun saved()=listOf(tool.name,selection.name,lastWriting.name,lastPen.name)
    companion object {
        fun restore(values:List<String>):EditorSessionState?=runCatching {
            require(values.size==4)
            EditorSessionState(CanvasTool.valueOf(values[0]),CanvasSelectionMode.valueOf(values[1]),
                CanvasTool.valueOf(values[2]),CanvasTool.valueOf(values[3]))
        }.getOrNull()
    }
}
