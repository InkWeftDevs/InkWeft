// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkAdmissionRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private val points=List(InkLimits.MAX_POINTS){InkSample(100f+it%100*.1f,200f,it.toLong(),.3f,.4f,.5f)}
    private fun stroke(n:Int=2)=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,points.take(n))
    private fun rows(total:Int,visible:Boolean=true):List<StoredInk> {
        var left=total
        return buildList{while(left>0){val n=minOf(left,InkLimits.MAX_POINTS);add(StoredInk(stroke(n),visible,1));left-=n}}
    }
    private fun commit(s:InkSession){val c=checkNotNull(s.nextCommand());s.complete(c,InkCommitResult.Committed(c.expectedRevision+1))}

    @Test fun pendingReplacementAndAdditionUseTheCurrentDraftPointBudget(){
        val originals=rows(InkLimits.MAX_PAGE_POINTS-100)
        val s=InkSession(InkPage(id(),1,originals));val old=originals.first().stroke
        val shortened=stroke(InkLimits.MAX_POINTS-192)
        s.enqueue(InkMutation.Replace(listOf(old.id),listOf(shortened)))
        val first=checkNotNull(s.nextCommand())
        val extra=stroke(292)
        s.enqueue(InkMutation.Add(extra),finishInFlight=true)
        assertEquals(InkLimits.MAX_PAGE_POINTS,s.visibleDraft().sumOf{it.samples.size})
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Add(stroke(1)),finishInFlight=true)}
        s.complete(first,InkCommitResult.Committed(2));commit(s)
        assertEquals(InkLimits.MAX_PAGE_POINTS,s.visibleDraft().sumOf{it.samples.size})
        assertArrayEquals(InkStrokeCodec.encode(old),InkStrokeCodec.encode(s.page.strokes.first().stroke))
        assertFalse(s.page.strokes.first().visible)
    }

    @Test fun pendingEraseFreesLiveCountButDoesNotDiscardOriginalRows(){
        val originals=List(InkLimits.MAX_STROKES){StoredInk(stroke(1),true,1)}
        val s=InkSession(InkPage(id(),1,originals));val original=originals.first().stroke
        s.enqueue(InkMutation.Visibility(listOf(original.id),false));val erase=checkNotNull(s.nextCommand())
        val added=stroke(1);s.enqueue(InkMutation.Add(added),finishInFlight=true)
        assertEquals(InkLimits.MAX_STROKES,s.visibleDraft().size)
        s.complete(erase,InkCommitResult.Committed(2));commit(s)
        assertEquals(InkLimits.MAX_STROKES+1,s.page.strokes.size)
        assertSame(original,s.page.strokes.first().stroke)
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Add(original),finishInFlight=true)}
    }

    @Test fun lookupTracksCommittedReplacementUndoRedoAndRejectsHiddenOriginalReuse(){
        val original=stroke();val s=InkSession(InkPage(id(),1,listOf(StoredInk(original,true,1))))
        val moved=InkSelectionEdit.copy(listOf(original),20f,10f).single()
        s.enqueue(InkMutation.Replace(listOf(original.id),listOf(moved)));commit(s)
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Add(original))}
        s.requestUndo();commit(s);s.requestRedo();commit(s)
        val second=InkSelectionEdit.copy(listOf(moved),10f,0f).single()
        s.enqueue(InkMutation.Replace(listOf(moved.id),listOf(second)));commit(s)
        assertEquals(second.id,s.visibleDraft().single().id)
        s.requestUndo();commit(s)
        assertArrayEquals(InkStrokeCodec.encode(moved),InkStrokeCodec.encode(s.visibleDraft().single()))
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Replace(listOf(original.id),listOf(stroke())))}
    }

    @Test fun hiddenHistoryStillChargesTheRetainedPointBudget(){
        val originals=rows(InkLimits.MAX_RETAINED_POINTS,visible=false)
        val s=InkSession(InkPage(id(),1,originals))
        assertTrue(s.visibleDraft().isEmpty());assertFalse(s.canStart)
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Add(stroke(1)),finishInFlight=true)}
        assertThrows(IllegalArgumentException::class.java){s.enqueue(InkMutation.Replace(emptyList(),listOf(stroke(1))))}
        assertEquals(0,s.queued);assertEquals(originals,s.page.strokes)
    }
}
