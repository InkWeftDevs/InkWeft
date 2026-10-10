// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkDraftRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(100f,100f,0,.2f),InkSample(120f,120f,10,.8f)))
    private fun commit(s:InkSession){val c=checkNotNull(s.nextCommand());s.complete(c,InkCommitResult.Committed(c.expectedRevision+1))}

    @Test fun queuedCutsAndCommitsKeepTheSameVisibleBytesAndImmutableSnapshots(){
        val s=InkSession(InkPage(id(),0,emptyList()));val a=stroke();val b=stroke()
        s.enqueue(InkMutation.Add(a));s.enqueue(InkMutation.Add(b))
        val draft=s.visibleDraft();assertEquals(listOf(a.id,b.id),draft.map{it.id})
        assertThrows(UnsupportedOperationException::class.java){(draft as MutableList<InkStroke>).clear()}
        commit(s);commit(s);assertEquals(draft.map(InkStrokeCodec::encode).map(ContentTransfer::hash),s.visibleDraft().map(InkStrokeCodec::encode).map(ContentTransfer::hash))
        val cut=InkCut(id(),4f,listOf(EraserPoint(110f,110f)))
        s.enqueue(InkMutation.Cut(EraseSelection(cut,listOf(a.id))))
        val masked=s.visibleDraft();val bytes=InkStrokeCodec.encode(masked.first())
        commit(s);assertArrayEquals(bytes,InkStrokeCodec.encode(s.visibleDraft().first()))
        assertTrue(draft.first().cuts.isEmpty())
        s.requestUndo();assertTrue(s.visibleDraft().first().cuts.isEmpty());commit(s)
        s.requestRedo();assertArrayEquals(bytes,InkStrokeCodec.encode(s.visibleDraft().first()));commit(s)
    }
    @Test fun replacementUndoAndRetryNeverReturnAnEarlierSelection(){
        val a=stroke();val s=InkSession(InkPage(id(),1,listOf(StoredInk(a,true,1))))
        val moved=InkSelectionEdit.copy(listOf(a),20f,10f)
        s.enqueue(InkMutation.Replace(listOf(a.id),moved));val c=checkNotNull(s.nextCommand())
        assertEquals(moved.map{it.id},s.visibleDraft().map{it.id})
        s.complete(c,InkCommitResult.Unknown);assertSame(c,s.retry())
        s.complete(c,InkCommitResult.Committed(2));assertEquals(moved.map{it.id},s.visibleDraft().map{it.id})
        s.requestUndo();assertEquals(listOf(a.id),s.visibleDraft().map{it.id});commit(s)
        s.requestRedo();assertEquals(moved.map{it.id},s.visibleDraft().map{it.id});commit(s)
        s.enqueue(InkMutation.Visibility(moved.map{it.id},false));assertTrue(s.visibleDraft().isEmpty());commit(s)
        s.requestUndo();commit(s);assertEquals(moved.map{it.id},s.visibleDraft().map{it.id})
    }
    @Test fun largeLassoEditsAndTheirUndoKeepPressureAndOriginalSamples(){
        val source=List(InkSelectionEdit.MAX_SELECTED){stroke()}
        val copied=InkSelectionEdit.copy(source,10f,10f)
        val s=InkSession(InkPage(id(),1,source.map{StoredInk(it,true,1)}))
        s.enqueue(InkMutation.Replace(source.map{it.id},copied));commit(s)
        assertEquals(source.size,s.visibleDraft().size)
        source.zip(s.visibleDraft()).forEach{(a,b)->assertEquals(a.samples.map{it.pressure},b.samples.map{it.pressure})}
        s.requestUndo();commit(s)
        source.zip(s.visibleDraft()).forEach{(a,b)->assertArrayEquals(InkStrokeCodec.encode(a),InkStrokeCodec.encode(b))}
        assertEquals(CanvasBounds(100.0,100.0,120.0,120.0),source.first().sampleBounds())
        assertEquals(source.first().sampleBounds().padded(source.first().coverageRadius().toDouble()),source.first().bounds())
    }
}
