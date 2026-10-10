// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HandwritingLineBudgetRegressionTest {
    private fun page(rows:Int)=List(rows){i->
        fun stroke(x:Float,y:Float,w:Float,h:Float)=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,
            listOf(InkSample(x,y,0,world=true),InkSample(x+w,y+h,20,world=true)),true)
        listOf(stroke(60f,80f+i*220,3f,3f),stroke(50f,100f+i*220,30f,100f))
    }.flatten()

    @Test fun dotsAttachedToTheirBodiesDoNotConsumeExtraLineBudget(){
        val source=page(100);val lines=HandwritingLines.split(source)
        assertEquals(100,lines.size);assertTrue(lines.all{it.strokes.size==2})
        assertEquals(source.map{it.id}.toSet(),lines.flatMap{it.strokes}.map{it.id}.toSet())
    }

    @Test fun actualRowsStillRespectTheRecognitionBudget(){
        val error=assertThrows(IllegalArgumentException::class.java){HandwritingLines.split(page(101))}
        assertEquals("HANDWRITING_LINE_BUDGET",error.message)
    }
}
