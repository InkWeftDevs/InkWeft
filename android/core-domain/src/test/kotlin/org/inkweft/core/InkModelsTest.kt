// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkModelsTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(samples: List<InkSample> = listOf(InkSample(100f,100f,0),InkSample(200f,200f,10))) =
        InkStroke(id(),InkPen.PEN,0xff22342f.toInt(),3f,InkTool.TOUCH,samples)
    private fun session()=InkSession(InkPage(id(),0,emptyList()))
    private fun commit(s: InkSession): CommitInk = checkNotNull(s.nextCommand()).also { s.complete(it,InkCommitResult.Committed(it.expectedRevision+1)) }
    private fun rejects(block: ()->Unit) { try { block();fail("Expected invalid input") } catch (_: IllegalArgumentException) {} }

    @Test fun codecRoundTripPreservesPressureAndTiming() {
        val a=stroke(listOf(InkSample(1f,2f,0,.2f,.3f,.4f),InkSample(2f,3f,150,.8f,.4f,.5f)))
        val b=InkStrokeCodec.decode(InkStrokeCodec.encode(a));assertEquals(a.id,b.id);assertEquals(a.samples,b.samples);assertEquals(a.width,b.width)
    }
    @Test fun truncatedStrokeRejected() { val bytes=InkStrokeCodec.encode(stroke());rejects { InkStrokeCodec.decode(bytes.copyOf(bytes.size-1)) } }
    @Test fun trailingDataRejected() { rejects { InkStrokeCodec.decode(InkStrokeCodec.encode(stroke())+byteArrayOf(0)) } }
    @Test fun nanCoordinateRejected() { rejects { InkSample(Float.NaN,0f,0) } }
    @Test fun offPageRejected() { rejects { InkSample(-1f,0f,0) } }
    @Test fun backwardsTimeRejected() { rejects { stroke(listOf(InkSample(1f,1f,10),InkSample(2f,2f,9))) } }
    @Test fun duplicateInputRejected() { val p=InkSample(1f,1f,0);rejects { stroke(listOf(p,p)) } }
    @Test fun mixedPressureAvailabilityRejected() { rejects { stroke(listOf(InkSample(1f,1f,0),InkSample(2f,2f,1,.5f))) } }
    @Test fun authorInputListDefensivelyCopied() { val points=mutableListOf(InkSample(2f,2f,0));val s=stroke(points);points.clear();assertEquals(1,s.samples.size) }
    @Test fun sweptEraserHitsCrossingSegments() {
        assertTrue(InkHitTest.hits(stroke(listOf(InkSample(10f,10f,0),InkSample(110f,110f,20))),listOf(InkSample(10f,110f,0),InkSample(110f,10f,10)),1f))
    }
    @Test fun eraserDoesNotHitFarCollinearLine() { assertFalse(InkHitTest.hits(stroke(),listOf(InkSample(900f,900f,0)),1f)) }
    @Test fun initialQueueKeepsNewStrokeVisible() { val s=session();val path=stroke();s.enqueue(InkMutation.Add(path));assertEquals(path,s.visibleDraft().single());assertEquals(0,s.page.strokes.size) }
    @Test fun failedWriteKeepsSameCommandAndDraft() {
        val s=session();s.enqueue(InkMutation.Add(stroke()));val c=checkNotNull(s.nextCommand());s.complete(c,InkCommitResult.Unknown)
        assertFalse(s.canStart);assertEquals(0,s.page.strokes.size);assertEquals(1,s.visibleDraft().size);assertSame(c,s.retry());assertSame(c,s.nextCommand())
    }
    @Test fun committedReceiptAdvancesOnce() { val s=session();s.enqueue(InkMutation.Add(stroke()));commit(s);assertEquals(1,s.page.revision);assertEquals(0,s.queued);assertTrue(s.canUndo) }
    @Test fun orderedQueueUsesFreshExpectedVersion() { val s=session();repeat(3){s.enqueue(InkMutation.Add(stroke()))};repeat(3){assertEquals(it.toLong(),commit(s).expectedRevision)};assertEquals(3,s.page.strokes.size) }
    @Test fun historyUndoRedoPreservesPayloadAndAddsCommits() {
        val s=session();val path=stroke();s.enqueue(InkMutation.Add(path));commit(s);s.requestUndo();commit(s)
        assertTrue(s.visibleDraft().isEmpty());assertSame(path,s.page.strokes.single().stroke);assertTrue(s.canRedo)
        s.requestRedo();commit(s);assertSame(path,s.visibleDraft().single());assertEquals(3,s.page.revision)
    }
    @Test fun erasureAndUndoKeepOriginalInkIdentity() {
        val s=session();val p=stroke();s.enqueue(InkMutation.Add(p));commit(s);s.enqueue(InkMutation.Visibility(listOf(p.id),false));commit(s)
        s.requestUndo();commit(s);assertEquals(p.id,s.visibleDraft().single().id)
    }
    @Test fun staleReceiptCannotAcknowledgeNewOperation() {
        val s=session();s.enqueue(InkMutation.Add(stroke()));val c=checkNotNull(s.nextCommand())
        try { s.complete(CommitInk(c.commandId,c.noteId,c.expectedRevision,c.mutation),InkCommitResult.Committed(1));fail() } catch (_: IllegalStateException) {}
    }
    @Test fun conflictStopsWriterButRetainsVisibleDraft() { val s=session();s.enqueue(InkMutation.Add(stroke()));s.complete(checkNotNull(s.nextCommand()),InkCommitResult.Conflict);assertFalse(s.canStart);assertNotNull(s.pending);assertNull(s.retry()) }
    @Test fun queueIsBounded() { val s=session();repeat(16){s.enqueue(InkMutation.Add(stroke()))};assertFalse(s.canStart) }
    @Test fun semanticDigestChangesWithExpectedVersion() { val path=stroke();val command=id();val note=id();assertNotEquals(CommitInk(command,note,0,InkMutation.Add(path)).digest(),CommitInk(command,note,1,InkMutation.Add(path)).digest()) }
    @Test fun semanticDigestStableForUnorderedVisibilityIds() { val a=id();val b=id();val c=id();val n=id();assertEquals(CommitInk(c,n,0,InkMutation.Visibility(listOf(a,b),false)).digest(),CommitInk(c,n,0,InkMutation.Visibility(listOf(b,a),false)).digest()) }
    @Test fun pageCopyRoundTrip() { val p=InkPageFile("本地页","文字",listOf(stroke()));val q=InkPageFile.decode(p.encode());assertEquals(p.title,q.title);assertEquals(p.text,q.text);assertEquals(p.strokes.first().samples,q.strokes.first().samples) }
    @Test fun pageCopyTamperRejected() { val bytes=InkPageFile("测试","",listOf(stroke())).encode();bytes[15]=(bytes[15].toInt() xor 1).toByte();rejects { InkPageFile.decode(bytes) } }
    @Test fun emptyPageCopySupported() { assertEquals(0,InkPageFile.decode(InkPageFile("空白页","",emptyList()).encode()).strokes.size) }
    @Test fun sourcePageCannotContainDuplicateStrokeIds() { val s=stroke();rejects { InkPageFile("测试","",listOf(s,s)) } }
}
