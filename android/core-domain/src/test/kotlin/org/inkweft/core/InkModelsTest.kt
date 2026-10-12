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
    @Test fun compactStoragePreservesAllPayloadVariantsAndCanonicalBytes() {
        val points=listOf(InkSample(-0.0f,2.5f,0,.25f,.5f,.75f),InkSample(1000f,1414f,3_600_000,1f,1f,6f))
        val plain=stroke(points)
        val round=InkCut(id(),2f,listOf(EraserPoint(10f,20f)))
        val rectangle=InkCut(id(),3f,listOf(EraserPoint(10f,20f),EraserPoint(30f,40f)),InkCutShape.RECTANGLE)
        val appearance=StrokeAppearance(BrushRecipe(),1234L,1f,2f,
            points.first().copy(x=-10f,world=true),points.last().copy(x=1100f,world=true))
        val variants=listOf(plain,
            InkStroke(id(),InkPen.BRUSH,plain.color,4f,InkTool.STYLUS,points.map{it.copy(x=it.x-20f,world=true)},true),
            plain.withCuts(listOf(round)),plain.withCuts(listOf(rectangle)),
            InkStroke(id(),InkPen.PENCIL,plain.color,5f,InkTool.STYLUS,points,cuts=listOf(round,rectangle),appearance=appearance))
        variants.forEach { original ->
            val canonical=InkStrokeCodec.encode(original)
            val stored=InkStrokeCodec.encodeStored(original)
            assertEquals(canonical.size-original.samples.size*4+36,stored.size)
            assertEquals(original.bounds(),InkStrokeCodec.storedBounds(stored.copyOf(36)))
            assertEquals(java.nio.ByteBuffer.wrap(canonical).int+5,java.nio.ByteBuffer.wrap(stored,36,stored.size-36).int)
            val restored=InkStrokeCodec.decode(stored)
            assertArrayEquals(canonical,InkStrokeCodec.encode(restored))
            assertArrayEquals(stored,InkStrokeCodec.encodeStored(restored))
            val command=id();val page=id()
            assertEquals(CommitInk(command,page,7,InkMutation.Add(original)).digest(),CommitInk(command,page,7,InkMutation.Add(restored)).digest())
        }
    }
    @Test fun compactStoragePreservesMaximumSampleCountAndExactFloatBits() {
        val points=List(InkLimits.MAX_POINTS){i->InkSample((i%1000).toFloat(),(i%1414).toFloat(),i.toLong()*400,
            Float.fromBits(0x3eaaaaab),Float.fromBits(0x3f123456),Float.fromBits(0x40234567))}
        val original=stroke(points);val stored=InkStrokeCodec.encodeStored(original)
        assertEquals(InkStrokeCodec.encode(original).size-InkLimits.MAX_POINTS*4+36,stored.size)
        val decoded=InkStrokeCodec.decode(stored)
        assertEquals(points,decoded.samples)
        points.zip(decoded.samples).forEach{(a,b)->
            assertEquals(a.pressure.toRawBits(),b.pressure.toRawBits())
            assertEquals(a.tilt.toRawBits(),b.tilt.toRawBits())
            assertEquals(a.orientation.toRawBits(),b.orientation.toRawBits())
        }
    }
    @Test fun compactStorageRejectsMalformedTimingCountTruncationAndUnknownVersion() {
        val value=stroke();val bytes=InkStrokeCodec.encodeStored(value)
        val firstPoint=bytes.size-value.samples.size*24
        for(time in listOf(-1,3_600_001,Int.MAX_VALUE)){
            val malformed=bytes.copyOf();java.nio.ByteBuffer.wrap(malformed).putInt(firstPoint+8,time)
            rejects{InkStrokeCodec.decode(malformed)}
        }
        val badCount=bytes.copyOf();java.nio.ByteBuffer.wrap(badCount).putInt(firstPoint-4,InkLimits.MAX_POINTS+1)
        rejects{InkStrokeCodec.decode(badCount)}
        rejects{InkStrokeCodec.decode(bytes.copyOf(bytes.size-1))}
        rejects{InkStrokeCodec.decode(bytes+byteArrayOf(0))}
        val unknown=bytes.copyOf();java.nio.ByteBuffer.wrap(unknown).putInt(0x4957533b)
        rejects{InkStrokeCodec.decode(unknown)}
        val wrongBounds=bytes.copyOf();java.nio.ByteBuffer.wrap(wrongBounds).putDouble(4,0.0)
        rejects{InkStrokeCodec.decode(wrongBounds)}
        val nested=bytes.copyOfRange(0,36)+bytes;rejects{InkStrokeCodec.decode(nested)}
    }
    @Test fun canonicalEncodingRemainsStableAcrossStorageOptimization() {
        val original=InkStroke("00000000-0000-0000-0000-000000000001",InkPen.PEN,0xff22342f.toInt(),3f,InkTool.TOUCH,
            listOf(InkSample(1.25f,2.5f,3_600_000,.25f,.5f,.75f)))
        val canonical=InkStrokeCodec.encode(original)
        assertEquals(84,canonical.size)
        assertEquals("a41f5b2ae5616c054fdfc74047665350604c36d1bb6a8667b8175689166e5fee",ContentTransfer.hash(canonical))
        assertArrayEquals(canonical,InkStrokeCodec.encode(InkStrokeCodec.decode(InkStrokeCodec.encodeStored(original))))
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
