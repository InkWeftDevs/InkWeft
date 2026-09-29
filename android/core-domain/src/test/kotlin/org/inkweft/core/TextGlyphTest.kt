package org.inkweft.core

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class TextGlyphTest {
    private fun text()=PageObject(UUID.randomUUID().toString(),PageObjectKind.TEXT,width=180f,height=60f,text="甲乙丙",
        sourceStrokeIds=listOf(UUID.randomUUID().toString()),glyphs=listOf(
            TextGlyph(0,1,0f,0f,30f,40f),TextGlyph(1,2,50f,0f,30f,40f,hidden=true),TextGlyph(2,3,100f,0f,30f,40f)))
    @Test fun partialErasureRoundTripsWithoutMovingOrRevealingNeighbours(){
        val o=text().copy(erasures=listOf(TextErasePath(0,3,3f,listOf(TextErasePoint(3f,10f),TextErasePoint(120f,10f)))));val restored=PageObjectCodec.decode(PageObjectCodec.encode(listOf(o))).single()
        assertEquals(o,restored);assertEquals("甲丙",restored.visibleText());assertEquals(100f,restored.glyphs[2].x,0f)
        assertEquals("",o.copy(hidden=true).visibleText())
    }
    @Test fun invalidGlyphRangesAndGeometryAreRejected(){
        val o=text()
        assertThrows(IllegalArgumentException::class.java){o.copy(glyphs=listOf(TextGlyph(0,4,0f,0f,30f,40f)))}
        assertThrows(IllegalArgumentException::class.java){o.copy(glyphs=listOf(TextGlyph(0,1,170f,0f,30f,40f)))}
        assertThrows(IllegalArgumentException::class.java){o.copy(glyphs=listOf(o.glyphs[1],o.glyphs[0]))}
        assertThrows(IllegalArgumentException::class.java){TextGlyph(0,1,Float.NaN,0f,30f,40f)}
    }
    @Test fun layoutFragmentsRoundTripAndRejectUnownedSources(){
        val base=text();val run=TextRun(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,3,0f,40f,40f,base.sourceStrokeIds,12)
        val o=base.copy(textRuns=listOf(run));assertEquals(o,PageObjectCodec.decode(PageObjectCodec.encode(listOf(o))).single())
        assertThrows(IllegalArgumentException::class.java){o.copy(textRuns=listOf(run.copy(sourceIds=listOf(UUID.randomUUID().toString()))))}
        val legacy=PageObjectCodec.encode(listOf(base)).dropLast(4).toByteArray().also{it[3]=0x38}
        assertEquals(base,PageObjectCodec.decode(legacy).single())
    }
}
