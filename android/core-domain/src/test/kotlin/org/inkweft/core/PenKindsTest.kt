// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PenKindsTest {
    private val samples=(0..20).map{InkSample(40f+it*12,60f+it%2,it*10L,it/20f,.3f,.4f)}
    private fun stroke(pen:InkPen)=InkStroke(UUID.randomUUID().toString(),pen,if(pen==InkPen.HIGHLIGHTER)0x66ffcc00 else 0xff20352a.toInt(),8f,InkTool.STYLUS,samples)
    @Test fun allPenKindsSurviveEditablePageRoundTripWithoutChangingAuthorAxes(){
        val paths=InkPen.entries.map(::stroke)
        val restored=InkPageFile.decode(InkPageFile("五种笔","",paths).encode()).strokes
        assertEquals(paths.map{it.pen},restored.map{it.pen})
        restored.forEach{assertEquals(samples,it.samples)}
    }
    @Test fun noPressureHasExplicitFallbackAndBrushCurveKeepsEndpoints(){
        InkPen.entries.forEach{assertEquals(-1f,PenKinds.renderPressure(it,-1f),0f)}
        assertEquals(.5f,PenKinds.renderPressure(InkPen.PEN,.5f),0f)
        assertEquals(.25f,PenKinds.renderPressure(InkPen.BRUSH,.5f),0f)
        assertEquals(1f,PenKinds.renderPressure(InkPen.BRUSH,1f),0f)
        listOf(InkPen.BALLPOINT,InkPen.MARKER,InkPen.HIGHLIGHTER).forEach{assertEquals(-1f,PenKinds.renderPressure(it,.5f),0f)}
    }
    @Test fun beautificationOfNewWritingPensRetainsRawPressureAndType(){
        PenKinds.writing.forEach{pen->val result=InkSelectionEdit.beautify(listOf(stroke(pen)),.8f).single()
            assertEquals(pen,result.pen);assertEquals(samples.map{it.pressure},result.samples.map{it.pressure})
            assertEquals(samples.map{it.tilt to it.elapsedMs},result.samples.map{it.tilt to it.elapsedMs})}
    }
}
