// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

class EditorSessionStateTest {
    @Test fun returnToolsChangeInSameTransition(){
        val s=EditorSessionState().choose(CanvasTool.SECOND_PEN).choose(CanvasTool.HIGHLIGHTER).choose(CanvasTool.ERASER)
        assertEquals(CanvasTool.HIGHLIGHTER,s.lastWriting);assertEquals(CanvasTool.SECOND_PEN,s.lastPen)
        assertEquals(CanvasTool.HIGHLIGHTER,s.choose(s.lastWriting).tool)
    }
    @Test fun selectionModesAreExclusiveAndClearedOnExit(){
        var s=EditorSessionState().select(CanvasSelectionMode.EXCERPT)
        s=s.select(CanvasSelectionMode.ERASE);assertEquals(CanvasSelectionMode.ERASE,s.selection)
        s=s.choose(CanvasTool.OBJECTS).choose(CanvasTool.SELECTION)
        assertEquals(CanvasSelectionMode.SELECT,s.selection)
    }
    @Test fun restoreEveryModeAndReturnTool(){
        for(tool in CanvasTool.entries)for(mode in CanvasSelectionMode.entries){
            var s=EditorSessionState().choose(CanvasTool.SECOND_PEN).choose(CanvasTool.HIGHLIGHTER).choose(tool)
            if(tool==CanvasTool.SELECTION)s=s.select(mode)
            assertEquals(s,EditorSessionState.restore(s.saved()))
        }
    }
    @Test fun invalidRestorationCannotProduceMixedMode(){
        assertNull(EditorSessionState.restore(listOf("PEN","ERASE","PEN","PEN")))
        assertNull(EditorSessionState.restore(listOf("SELECTION","SELECT","ERASER","PEN")))
        assertNull(EditorSessionState.restore(listOf("0")))
    }
    @Test fun enteringContinuousKeepsWritingOrReturnsToLastWriting(){
        val base=EditorSessionState().choose(CanvasTool.HIGHLIGHTER)
        assertEquals(CanvasTool.HIGHLIGHTER,base.select(CanvasSelectionMode.EXCERPT).continuous().tool)
        assertEquals(CanvasTool.ERASER,base.choose(CanvasTool.ERASER).continuous().tool)
    }
}
