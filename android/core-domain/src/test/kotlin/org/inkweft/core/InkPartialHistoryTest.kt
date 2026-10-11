// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkPartialHistoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(id:String=id())=InkStroke(id,InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(10f,20f,0,.2f),InkSample(30f,40f,50,.8f)))
    private fun commit(session:InkSession){val c=checkNotNull(session.nextCommand());session.complete(c,InkCommitResult.Committed(c.expectedRevision+1))}

    @Test fun unloadedHistoryBeyondFormerBudgetsDoesNotBlockWritingOrMasks() {
        val many=UnloadedInk(List(40_001){id()}.associateWith{1})
        assertTrue(InkSession(InkPage(id(),1,emptyList(),unloaded=many)).canStart)
        val points=linkedMapOf<String,Int>();var remaining=2_000_001
        while(remaining>0){val count=minOf(remaining,InkLimits.MAX_POINTS);points[id()]=count;remaining-=count}
        assertTrue(InkSession(InkPage(id(),1,emptyList(),unloaded=UnloadedInk(points))).canStart)
        val original=stroke()
        val session=InkSession(InkPage(id(),1,listOf(StoredInk(original,true,1)),unloaded=UnloadedInk(cutIds=List(2049){id()}.toSet())))
        session.enqueue(InkMutation.Cut(EraseSelection(InkCut(id(),2f,listOf(EraserPoint(20f,30f))),listOf(original.id))));commit(session)
        assertEquals(2050,session.page.retainedCutCount)
    }
    @Test fun unseenRetainedIdentitiesCannotBeInsertedAgainOrLostAfterCommit() {
        val old=stroke();val originalMap=linkedMapOf(old.id to old.samples.size)
        val unloaded=UnloadedInk(originalMap);originalMap.clear()
        val session=InkSession(InkPage(id(),1,emptyList(),unloaded=unloaded))
        assertThrows(IllegalArgumentException::class.java){session.enqueue(InkMutation.Add(old))}
        assertThrows(IllegalArgumentException::class.java){session.enqueue(InkMutation.Replace(emptyList(),listOf(old)))}
        val added=stroke();session.enqueue(InkMutation.Add(added));commit(session)
        assertEquals(2,session.page.retainedStrokeCount)
        assertEquals(mapOf(old.id to 2),session.page.unloaded.strokePoints)
        session.requestUndo();commit(session)
        assertTrue(session.visibleDraft().isEmpty());assertEquals(2,session.page.retainedStrokeCount)
        session.requestRedo();commit(session);assertEquals(added.id,session.visibleDraft().single().id)
    }
    @Test fun historyIsNotReadyUntilItsRequiredHiddenStrokeIsLoaded() {
        val session=InkSession(InkPage(id(),0,emptyList()));val original=stroke()
        session.enqueue(InkMutation.Add(original));commit(session)
        session.enqueue(InkMutation.Visibility(listOf(original.id),false));commit(session)
        val history=session.suspendHistory();assertEquals(setOf(original.id),history.strokeIds)
        val incomplete=InkSession(InkPage(session.page.noteId,session.page.revision,emptyList(),unloaded=UnloadedInk(mapOf(original.id to 2))))
        assertThrows(IllegalArgumentException::class.java){incomplete.restoreHistory(history)}
        assertFalse(incomplete.canUndo)
        val ready=InkSession(session.page);ready.restoreHistory(history);ready.requestUndo();commit(ready)
        assertArrayEquals(InkStrokeCodec.encode(original),InkStrokeCodec.encode(ready.visibleDraft().single()))
    }
    @Test fun historyIsNotReadyUntilItsRequiredHiddenMaskIsLoaded() {
        val original=stroke();val session=InkSession(InkPage(id(),1,listOf(StoredInk(original,true,1))))
        val mask=InkCut(id(),3f,listOf(EraserPoint(20f,30f)))
        session.enqueue(InkMutation.Cut(EraseSelection(mask,listOf(original.id))));commit(session)
        session.requestUndo();commit(session)
        val history=session.suspendHistory();assertEquals(setOf(mask.id),history.cutIds)
        val partial=InkSession(session.page.copy(cuts=emptyList(),unloaded=UnloadedInk(cutIds=setOf(mask.id))))
        assertThrows(IllegalArgumentException::class.java){partial.restoreHistory(history)}
        val ready=InkSession(session.page);ready.restoreHistory(history);ready.requestRedo();commit(ready)
        assertEquals(mask.id,ready.visibleDraft().single().cuts.single().id)
    }
}
