// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.strokes.*
import org.inkweft.core.*

/** Live ink, reopened strokes and the preset preview share one render policy. */
internal object InkBrushes {
    private val round=StockBrushes.marker()
    private val pressure=StockBrushes.pressurePen()
    private val chisel=StockBrushes.highlighter()
    private val calligraphy=BrushFamily(BrushTip(cornerRounding=1f,behaviors=listOf(
        BrushBehavior(TargetNode(TargetNode.Target.SIZE_MULTIPLIER,.12f,1f,
            SourceNode(SourceNode.Source.NORMALIZED_PRESSURE,0f,1f)),
            developerComment="Round calligraphy tip: squared author pressure maps to 12–100% width.")
    )))
    fun brush(pen:InkPen,color:Int,width:Float,hasPressure:Boolean):Brush {
        val family=when {
            pen==InkPen.HIGHLIGHTER||pen==InkPen.MARKER->chisel
            pen==InkPen.BRUSH&&hasPressure->calligraphy
            PenKinds.pressureSensitive(pen)&&hasPressure->pressure
            else->round
        }
        return Brush.createWithColorIntArgb(family,color,width,.1f)
    }
    fun add(batch:MutableStrokeInputBatch,sample:InkSample,tool:InkTool,pen:InkPen){
        val input=when(tool){InkTool.STYLUS->InputToolType.STYLUS;InkTool.MOUSE->InputToolType.MOUSE;InkTool.TOUCH->InputToolType.TOUCH}
        val p=PenKinds.renderPressure(pen,sample.pressure)
        batch.add(input,sample.x,sample.y,sample.elapsedMs,
            pressure=if(p<0)StrokeInput.NO_PRESSURE else p,
            tiltRadians=if(sample.tilt<0)StrokeInput.NO_TILT else sample.tilt,
            orientationRadians=if(sample.orientation<0)StrokeInput.NO_ORIENTATION else sample.orientation)
    }
    fun stroke(stroke:InkStroke):Stroke {
        val batch=MutableStrokeInputBatch();stroke.samples.forEach{add(batch,it,stroke.tool,stroke.pen)}
        return Stroke(brush(stroke.pen,stroke.color,stroke.width,stroke.samples.first().pressure>=0),batch)
    }
}
