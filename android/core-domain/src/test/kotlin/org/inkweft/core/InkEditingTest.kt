// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkEditingTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),12f,InkTool.TOUCH,listOf(InkSample(20f,80f,0),InkSample(200f,80f,100)))
    private fun cut()=InkCut(id(),15f,listOf(EraserPoint(90f,70f),EraserPoint(90f,90f)))
    private fun committed(s:InkSession):CommitInk{val c=checkNotNull(s.nextCommand());s.complete(c,InkCommitResult.Committed(c.expectedRevision+1));return c}
    @Test fun partialMaskKeepsOriginalSamplesAndOnlyItsTargets(){
        val a=stroke();val b=stroke();val s=InkSession(InkPage(id(),2,listOf(StoredInk(a,true,1),StoredInk(b,true,2))))
        val m=cut();s.enqueue(InkMutation.Cut(EraseSelection(m,listOf(a.id))),true);committed(s)
        val rows=s.visibleDraft();assertEquals(a.samples,rows[0].samples);assertEquals(m.id,rows[0].cuts.single().id);assertTrue(rows[1].cuts.isEmpty());assertTrue(s.page.strokes[0].stroke.cuts.isEmpty())
    }
    @Test fun maskUndoRedoIsOneOperationAndPreservesHistory(){
        val a=stroke();val s=InkSession(InkPage(id(),1,listOf(StoredInk(a,true,1))))
        s.enqueue(InkMutation.Cut(EraseSelection(cut(),listOf(a.id))),true);committed(s);s.requestUndo();committed(s);assertTrue(s.visibleDraft().single().cuts.isEmpty())
        s.requestRedo();committed(s);assertEquals(1,s.visibleDraft().single().cuts.size);assertEquals(a.samples,s.page.strokes.single().stroke.samples)
    }
    @Test fun newInkIsNotErasedByAnOldMask(){
        val a=stroke();val s=InkSession(InkPage(id(),1,listOf(StoredInk(a,true,1))))
        s.enqueue(InkMutation.Cut(EraseSelection(cut(),listOf(a.id))),true);committed(s);s.enqueue(InkMutation.Add(stroke()),true);committed(s)
        assertTrue(s.visibleDraft().last().cuts.isEmpty())
    }
    @Test fun unknownEraseKeepsTheSameCommand(){
        val a=stroke();val s=InkSession(InkPage(id(),1,listOf(StoredInk(a,true,1))))
        s.enqueue(InkMutation.Cut(EraseSelection(cut(),listOf(a.id))),true);val c=checkNotNull(s.nextCommand());val digest=c.digest();s.complete(c,InkCommitResult.Unknown)
        assertSame(c,s.retry());assertEquals(digest,s.nextCommand()!!.digest());s.complete(c,InkCommitResult.Committed(2));assertEquals(1,s.page.cuts.size)
    }
    @Test fun effectiveMaskedStrokeCodecRoundTrip(){
        val a=stroke();val mask=cut();val b=InkStrokeCodec.decode(InkStrokeCodec.encode(a.withCuts(listOf(mask))))
        assertEquals(a.samples,b.samples);assertEquals(mask.points,b.cuts.single().points);assertEquals(mask.radius,b.cuts.single().radius,0f)
    }
    @Test fun oldUnmaskedCodecStillRoundTrips(){val a=stroke();val b=InkStrokeCodec.decode(InkStrokeCodec.encode(a));assertEquals(a.samples,b.samples);assertTrue(b.cuts.isEmpty())}
    @Test fun partialEraseSurvivesPageContentExport(){
        val p=InkPageFile("测试","",listOf(stroke().withCuts(listOf(cut()))));val decoded=InkPageFile.decode(p.encode())
        assertEquals(1,decoded.strokes.single().cuts.size);assertEquals(p.strokes.single().samples,decoded.strokes.single().samples)
    }
    @Test fun wholeBookRoundTripIncludesEveryPageAndMask(){
        val a=InkPageFile("一","",listOf(stroke().withCuts(listOf(cut()))));val b=InkPageFile("二","",listOf(stroke()),paper=PaperStyle.GRID)
        val restored=NotebookFile.decode(NotebookFile("书","正文",listOf(a,b)).encode())
        assertEquals(2,restored.pages.size);assertEquals(PaperStyle.GRID,restored.pages[1].paper);assertEquals(1,restored.pages[0].strokes.single().cuts.size)
    }
    @Test fun maskValidationRejectsInvalidSizeAndEmptyPath(){
        for(r in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f,6000f))try{InkCut(id(),r,listOf(EraserPoint(1f,1f)));fail("invalid radius")}catch(_:IllegalArgumentException){}
        try{InkCut(id(),1f,emptyList());fail("empty")}catch(_:IllegalArgumentException){}
    }
    @Test fun eraserChangesSemanticCommandDigest(){
        val s=stroke();val c=cut();val command=id();val page=id()
        val a=CommitInk(command,page,1,InkMutation.Cut(EraseSelection(c,listOf(s.id))))
        val b=CommitInk(command,page,1,InkMutation.Cut(EraseSelection(InkCut(c.id,c.radius+1,c.points),listOf(s.id))))
        assertNotEquals(a.digest(),b.digest())
    }
    @Test fun staleRecognizedRegionDoesNotFollowInkRevision(){
        val scope=RevisionScope(id(),id(),3);val r=RecognizedRegion(scope,"文字",CanvasBounds(0.0,0.0,20.0,20.0),"test","1",true)
        assertTrue(r.isCurrent(scope));assertFalse(r.isCurrent(scope.copy(contentRevision=4)))
    }
    @Test fun noCloudProvidersAreEnabledByDefault(){assertTrue(DisabledCloudServices.capabilities.isEmpty());assertEquals("disabled",DisabledCloudServices.providerId)}
}
