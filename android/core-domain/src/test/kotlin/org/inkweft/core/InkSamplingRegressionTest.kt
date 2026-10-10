// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs

class InkSamplingRegressionTest {
    private fun stroke(points:List<InkSample>)=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff112233.toInt(),4f,InkTool.STYLUS,points,points.first().world)

    @Test fun stationarySensorUpdatesRemainInAuthorBytesAndCoalesceOnlyForRendering(){
        val points=listOf(InkSample(20f,30f,0,.2f,.45f,.1f),InkSample(20f,30f,0,.5f,.9f,.2f),InkSample(20f,30f,0,.9f,.9f,.3f),InkSample(40f,50f,20,0f,.9f,.3f))
        val original=stroke(points);val bytes=InkStrokeCodec.encode(original)
        assertEquals(listOf(points[2],points[3]),InkSampling.forRendering(original.samples))
        assertEquals(points,original.samples)
        assertArrayEquals(bytes,InkStrokeCodec.encode(InkStrokeCodec.decode(bytes)))
    }

    @Test fun unchangedRenderInputDoesNotAllocateAndLivePrefixIgnoresDomainFlags(){
        val points=listOf(InkSample(10f,10f,0,.2f),InkSample(20f,20f,10,.9f))
        assertSame(points,InkSampling.forRendering(points))
        assertTrue(InkSampling.renderPrefix(points,points.map{it.copy(world=true)}))
        assertTrue(InkSampling.renderPrefix(points,points+InkSample(30f,25f,20,0f)))
        assertFalse(InkSampling.renderPrefix(points,points.take(1)))
    }

    @Test fun cacheValidationDetectsInteriorPositionPressureAndOrientationChanges(){
        val points=List(62){InkSample(10f+it,50f,it*20L,.5f,.45f,.3f)}
        for(changed in listOf(points[20].copy(y=55f),points[20].copy(pressure=.9f),points[20].copy(tilt=.9f),points[20].copy(orientation=.9f),points[20].copy(elapsedMs=401))){
            val next=points.toMutableList().apply{this[20]=changed}
            assertEquals(points.last(),next.last());assertFalse(InkSampling.renderPrefix(points,next))
        }
    }

    @Test fun orientationWrapKeepsUnknownAxesAndRejectsNonFiniteReadings(){
        assertEquals((2*PI-.1).toFloat(),InkSampling.orientation(-.1f)!!,1e-6f)
        assertEquals(0f,InkSampling.orientation((2*PI).toFloat())!!,0f)
        assertEquals(0f,InkSampling.orientation((-2*PI).toFloat())!!,0f)
        for(raw in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))assertNull(InkSampling.orientation(raw))
        assertEquals(-1f,InkSampling.orientationBetween(-1f,-1f,.5f),0f)
    }

    @Test fun crossingPageEdgeInterpolatesShortOrientationArcAndRetainsOriginalReadings(){
        val a=InkSample(100f,1400f,0,.2f,0f,(2*PI-.1).toFloat(),true)
        val b=InkSample(140f,1428f,20,.9f,.9f,.1f,true)
        val original=stroke(listOf(a,b));val bytes=InkStrokeCodec.encode(original)
        val split=ContinuousInk.split(original,0,2)
        val edge=split.getValue(0).single().samples.last()
        assertTrue("nib rotated across the long arc",minOf(abs(edge.orientation),abs(edge.orientation-(2*PI).toFloat()))<1e-5f)
        assertEquals(.55f,edge.pressure,1e-6f);assertEquals(.45f,edge.tilt,1e-6f);assertEquals(10L,edge.elapsedMs)
        assertEquals(edge.orientation,split.getValue(1).single().samples.first().orientation,0f)
        assertEquals(a.copy(world=false),split.getValue(0).single().samples.first())
        assertEquals(b.copy(y=14f,world=false),split.getValue(1).single().samples.last())
        assertArrayEquals(bytes,InkStrokeCodec.encode(original))
    }

    @Test fun polishingUnevenlySampledStraightLinesDoesNotSlideTheirReadings(){
        for(xs in listOf(listOf(10f,11f,100f,101f,500f),listOf(10f,100f,101f,490f,500f))){
            val points=xs.mapIndexed{i,x->InkSample(x,20f+x*.5f,i*20L,.2f+i*.1f,.45f,.3f)}
            val result=InkSelectionEdit.beautify(listOf(stroke(points)),1f).single().samples
            for(i in points.indices){assertEquals(points[i].x,result[i].x,1e-4f);assertEquals(points[i].y,result[i].y,1e-4f)}
            assertEquals(points.map{it.elapsedMs},result.map{it.elapsedMs})
            assertEquals(points.map{it.pressure},result.map{it.pressure})
            assertEquals(points.map{it.tilt},result.map{it.tilt});assertEquals(points.map{it.orientation},result.map{it.orientation})
        }
    }

    @Test fun polishingSuppressesJitterAndRetainsCornersAtUnevenInputSpacing(){
        val points=listOf(InkSample(10f,20f,0,.2f),InkSample(12f,20.5f,10,.5f),InkSample(80f,20f,20,.9f),InkSample(80f,80f,40,0f))
        val result=InkSelectionEdit.beautify(listOf(stroke(points)),1f).single().samples
        assertTrue(result[1].y<points[1].y)
        assertEquals(points[0],result[0]);assertEquals(points[2],result[2]);assertEquals(points[3],result[3])
    }
}
