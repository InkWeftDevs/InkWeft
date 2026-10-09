package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkCapacityRegressionTest {
    private fun stroke()=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(101f,101f,10)))
    private fun page(rows:List<StoredInk>)=InkSession(InkPage(UUID.randomUUID().toString(),10,rows))
    private fun commit(session:InkSession){val command=checkNotNull(session.nextCommand());session.complete(command,InkCommitResult.Committed(command.expectedRevision+1))}
    @Test fun existingThousandStrokePageCanContinueWithoutDiscardingItsHistory(){
        val rows=List(1000){StoredInk(stroke(),it<841,1)};val session=page(rows)
        assertTrue("A normal lecture page must remain writable past the old lifetime count",session.canStart)
        val added=stroke();session.enqueue(InkMutation.Add(added));commit(session)
        assertEquals(842,session.visibleDraft().size);assertEquals(rows.map{it.stroke},session.page.strokes.take(1000).map{it.stroke})
    }
    @Test fun movingAtTheVisibleLimitDoesNotChargeTheSameInkTwiceAndUndoRestoresIt(){
        val rows=List(InkLimits.MAX_STROKES){StoredInk(stroke(),true,1)};val session=page(rows)
        val original=rows.take(4).map{it.stroke};val moved=InkSelectionEdit.copy(original,20f,0f)
        session.enqueue(InkMutation.Replace(original.map{it.id},moved,original.map{it.id}));commit(session)
        assertEquals(InkLimits.MAX_STROKES,session.visibleDraft().size)
        assertTrue(moved.all{m->session.visibleDraft().any{it.id==m.id}})
        session.requestUndo();commit(session)
        assertEquals(rows.map{it.stroke.id}.toSet(),session.visibleDraft().map{it.id}.toSet())
    }
    @Test fun wholeEraseFreesWritingCapacityWhileKeepingUndoAndOriginalSamples(){
        val rows=List(InkLimits.MAX_STROKES){StoredInk(stroke(),true,1)};val session=page(rows)
        assertFalse(session.canStart)
        session.enqueue(InkMutation.Visibility(listOf(rows.first().stroke.id),false));commit(session)
        assertTrue("Hidden undo history must not consume the live writing quota",session.canStart)
        assertSame(rows.first().stroke,session.page.strokes.first().stroke)
        session.requestUndo();commit(session);assertEquals(rows.size,session.visibleDraft().size)
    }
}
