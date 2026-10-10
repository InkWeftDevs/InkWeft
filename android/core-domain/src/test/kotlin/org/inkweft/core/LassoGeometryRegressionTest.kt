// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LassoGeometryRegressionTest {
    private fun notch(width:Float,x:Float)=InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(width,0f),EraserPoint(width,100f),EraserPoint(x+.1f,100f),EraserPoint(x+.1f,40f),EraserPoint(x,40f),EraserPoint(x,100f),EraserPoint(0f,100f)),false)
    private fun line(x:Float,end:Float)=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(x,60f,0,world=true),InkSample(end,60f,20,world=true)),true)

    @Test fun subSampleNotchCannotSilentlySelectAnOutsideStroke(){
        val region=notch(100f,50.1f);val stroke=line(10f,90f)
        assertTrue(stroke.samples.all{region.contains(it.x.toDouble(),it.y.toDouble())})
        assertFalse(region.selects(stroke));assertFalse(region.containsSegment(90.0,60.0,10.0,60.0))
        assertTrue(region.containsSegment(10.0,20.0,90.0,20.0))
    }

    @Test fun longBoardSegmentDoesNotSkipNotchesBeyondTheOld512StepBudget(){
        val region=notch(20_000f,10_050.1f)
        assertFalse(region.selects(line(10f,19_990f)))
        assertFalse(region.selects(CanvasBounds(5.0,50.0,19_995.0,90.0)))
    }

    @Test fun polygonEdgesVerticesAndTangentSegmentsAreIncludedConsistently(){
        val triangle=InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(100f,0f),EraserPoint(0f,100f)),false)
        assertTrue(triangle.contains(100.0,0.0));assertTrue(triangle.contains(50.0,50.0))
        assertTrue(triangle.containsSegment(0.0,0.0,100.0,0.0))
        assertTrue(triangle.containsSegment(100.0,0.0,0.0,100.0))
        assertTrue(triangle.selects(CanvasBounds(0.0,0.0,50.0,50.0)))
        assertFalse(triangle.containsSegment(50.0,50.0,51.0,51.0))
        assertFalse(triangle.contains(Double.NaN,50.0))
        assertFalse(InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(100f,100f))).contains(50.0,Double.POSITIVE_INFINITY))
    }
}
