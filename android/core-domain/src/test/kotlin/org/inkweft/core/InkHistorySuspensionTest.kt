// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkHistorySuspensionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,100f,0,.2f),InkSample(120f,120f,10,.8f)))
    private fun commit(s:InkSession){val c=checkNotNull(s.nextCommand());s.complete(c,InkCommitResult.Committed(c.expectedRevision+1))}
    @Test fun releaseAndReloadPreserveUndoRedoAndIdentityWithoutRetainingPoints(){
        val session=InkSession(InkPage(id(),0,emptyList()));val ink=stroke()
        session.enqueue(InkMutation.Add(ink));commit(session)
        val identity=session.undoIdentity;val history=session.suspendHistory()
        assertTrue(history.undo.all{it is InkMutation.Visibility})
        val resumed=InkSession(session.page);resumed.restoreHistory(history);assertSame(identity,resumed.undoIdentity)
        resumed.requestUndo();commit(resumed);assertTrue(resumed.visibleDraft().isEmpty())
        val again=InkSession(resumed.page);again.restoreHistory(resumed.suspendHistory());assertTrue(again.canRedo)
        again.requestRedo();commit(again);assertArrayEquals(InkStrokeCodec.encode(ink),InkStrokeCodec.encode(again.visibleDraft().single()))
    }
    @Test fun cutsAndReplacementInversesKeepMasksAndPressureAfterReload(){
        var session=InkSession(InkPage(id(),0,emptyList()));val original=stroke()
        session.enqueue(InkMutation.Add(original));commit(session)
        val moved=InkSelectionEdit.copy(listOf(original),20f,10f)
        session.enqueue(InkMutation.Replace(listOf(original.id),moved));commit(session)
        session.enqueue(InkMutation.Cut(EraseSelection(InkCut(id(),4f,listOf(EraserPoint(130f,120f))),moved.map{it.id})));commit(session)
        val expected=session.visibleDraft().map(InkStrokeCodec::encode)
        val saved=session.suspendHistory();val page=session.page;session=InkSession(page);session.restoreHistory(saved)
        session.requestUndo();commit(session);assertTrue(session.visibleDraft().single().cuts.isEmpty())
        session.requestRedo();commit(session);assertArrayEquals(expected.single(),InkStrokeCodec.encode(session.visibleDraft().single()))
    }
    @Test fun queuedUnknownOrChangedRevisionCannotBeSuspendedOrReplayed(){
        val session=InkSession(InkPage(id(),0,emptyList()));session.enqueue(InkMutation.Add(stroke()))
        assertThrows(IllegalStateException::class.java){session.suspendHistory()}
        val c=checkNotNull(session.nextCommand());session.complete(c,InkCommitResult.Unknown)
        assertThrows(IllegalStateException::class.java){session.suspendHistory()}
        session.retry();session.complete(c,InkCommitResult.Committed(1))
        val wrong=InkSession(session.page.copy(revision=2))
        assertThrows(IllegalArgumentException::class.java){wrong.restoreHistory(session.suspendHistory())}
    }
}
