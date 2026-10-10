// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecognitionAndHistoryRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float,y:Float,w:Float,h:Float)=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,y,0,.5f),InkSample(x+w,y+h,20,.7f)))
    @Test fun lateVerticalConnectsTopAndBottomWithoutChangingInk(){
        val top=stroke(350f,280f,110f,0f);val middle=stroke(360f,350f,90f,0f);val bottom=stroke(340f,420f,130f,0f);val stem=stroke(405f,280f,0f,140f)
        val ink=listOf(top,middle,bottom,stem);val before=ink.map{InkStrokeCodec.encode(it)}
        for(order in listOf(ink,ink.reversed(),listOf(stem,bottom,top,middle))){
            val line=HandwritingLines.split(order).single();assertEquals(ink.map{it.id}.toSet(),line.strokes.map{it.id}.toSet())
        }
        ink.indices.forEach{assertArrayEquals(before[it],InkStrokeCodec.encode(ink[it]))}
    }
    @Test fun delayedDotsAcrossSeveralCharactersJoinBodies(){
        val bodies=listOf(stroke(360f,327f,0f,103f),stroke(500f,327f,0f,103f),stroke(620f,327f,35f,116f))
        val dots=listOf(stroke(360f,278f,0f,0f),stroke(500f,278f,0f,0f),stroke(655f,278f,0f,0f))
        for(order in listOf(bodies+dots,dots+bodies)){
            val lines=HandwritingLines.split(order);assertEquals(1,lines.size);assertEquals((bodies+dots).map{it.id}.toSet(),lines.single().strokes.map{it.id}.toSet())
        }
    }
    @Test fun nearbyDotDoesNotBridgeUnrelatedRowsOrColumns(){
        val one=stroke(100f,100f,20f,60f);val two=stroke(100f,330f,20f,60f);val column=stroke(700f,100f,20f,60f);val dot=stroke(108f,75f,2f,2f)
        val lines=HandwritingLines.split(listOf(two,dot,column,one));assertEquals(3,lines.size)
        assertEquals(setOf(one.id,dot.id),lines.single{it.strokes.any{s->s.id==one.id}}.strokes.map{it.id}.toSet())
    }
    @Test fun separateShortHorizontalRowsStayAmbiguous(){
        val strokes=listOf(stroke(440f,308f,110f,0f),stroke(430f,402f,135f,0f))
        assertEquals(2,HandwritingLines.split(strokes).size)
    }
    @Test fun highlighterNeverBecomesRecognitionInput(){
        val pen=stroke(100f,100f,10f,50f);val marker=InkStroke(id(),InkPen.HIGHLIGHTER,0xff000000.toInt(),40f,InkTool.STYLUS,pen.samples)
        assertEquals(listOf(pen.id),HandwritingLines.split(listOf(marker,pen)).single().strokes.map{it.id})
    }
    @Test fun restoreRequestBindsBothCardRevisionAndHistoricalSource(){
        val op=id();val book=id();val card=id()
        fun command(expected:Long,restore:Long)=StudyCommand(op,book,StudyAction.RESTORE_EXCERPT,cardId=card,expectedRevision=expected,restoreSourceRevision=restore)
        assertNotEquals(command(3,1).digest(),command(3,2).digest());assertNotEquals(command(3,1).digest(),command(4,1).digest())
        assertEquals(command(3,1).digest(),command(3,1).digest())
    }
    @Test fun restorationCannotTargetFutureOrMissingHistory(){
        for(revision in listOf<Long?>(null,0,4))assertThrows(IllegalArgumentException::class.java){StudyCommand(id(),id(),StudyAction.RESTORE_EXCERPT,cardId=id(),expectedRevision=3,restoreSourceRevision=revision)}
        assertThrows(IllegalArgumentException::class.java){StudyCommand(id(),id(),StudyAction.EDIT,cardId=id(),expectedRevision=3,title="x",restoreSourceRevision=1)}
    }
    @Test fun existingReceiptDigestIsUnchanged(){
        val c=StudyCommand("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002",StudyAction.EDIT,cardId="00000000-0000-0000-0000-000000000003",expectedRevision=2,title="old",body="body")
        assertEquals("0f6b7ceb3f8edbd8b7fe038390f34655795846bcb15e1890d67cb862d9d8ea70",c.digest())
    }
    @Test fun timingUnitsAndAllowlistedJournalRoundTrip(){
        val timing=ReadTiming(ReadKind.INK,1234567,9876543,22);assertEquals(1234L,timing.freezeMicros);assertEquals(9876L,timing.decodeMicros)
        val log=DiagnosticLog("0123456789ab");log.add(DiagnosticCode.INK_FREEZE,DiagnosticResult.OK,1,2,timing.freezeMicros,4);log.add(DiagnosticCode.INK_DECODE,DiagnosticResult.OK,1,2,timing.decodeMicros,4)
        val events=DiagnosticLog.decode(DiagnosticLog.encode(log.snapshot()));assertEquals(listOf(1234L,9876L),events.map{it.count});assertEquals(listOf(4L,4L),events.map{it.auxiliary})
    }
    @Test fun negativeReadTimingIsRejected(){
        assertThrows(IllegalArgumentException::class.java){ReadTiming(ReadKind.INK,-1,0,0)}
        assertThrows(IllegalArgumentException::class.java){ReadTiming(ReadKind.AUTHORING,0,-1,0)}
        assertThrows(IllegalArgumentException::class.java){ReadTiming(ReadKind.INK,0,0,-1)}
    }
}
