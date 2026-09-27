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
    private val chisel=StockBrushes.highlighter()
    private fun response(pressure:Boolean,minimum:Float)=BrushBehavior(
        TargetNode(TargetNode.Target.SIZE_MULTIPLIER,if(pressure)minimum else 1f,if(pressure)1f else minimum,
            SourceNode(if(pressure)SourceNode.Source.NORMALIZED_PRESSURE else SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND,
                0f,if(pressure)1f else 180f)))
    private fun fountain(pressure:Boolean)=BrushFamily(BrushTip(scaleX=1f,scaleY=.7f,rotationDegrees=-35f,cornerRounding=.8f,
        behaviors=listOf(response(pressure,.55f))))
    private fun brushPen(pressure:Boolean)=BrushFamily(BrushTip(cornerRounding=1f,behaviors=listOf(response(pressure,.12f))))
    private val fountainPressure=fountain(true)
    private val fountainSpeed=fountain(false)
    private val brushPressure=brushPen(true)
    private val brushSpeed=brushPen(false)
    fun brush(pen:InkPen,color:Int,width:Float,hasPressure:Boolean):Brush {
        val family=when {
            pen==InkPen.HIGHLIGHTER||pen==InkPen.MARKER->chisel
            pen==InkPen.BRUSH->if(hasPressure)brushPressure else brushSpeed
            pen==InkPen.PEN->if(hasPressure)fountainPressure else fountainSpeed
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
        // Ink permits pressure-only author samples; the native renderer requires a
        // new position or timestamp. Coalesce only the derived render input, keeping
        // the final sensor values and the immutable source/backup bytes unchanged.
        val samples=ArrayList<InkSample>()
        stroke.samples.forEach{sample->
            val previous=samples.lastOrNull()
            if(previous!=null&&previous.x==sample.x&&previous.y==sample.y&&previous.elapsedMs==sample.elapsedMs)samples[samples.lastIndex]=sample
            else samples.add(sample)
        }
        val batch=MutableStrokeInputBatch();samples.forEach{add(batch,it,stroke.tool,stroke.pen)}
        return Stroke(brush(stroke.pen,stroke.color,stroke.width,stroke.samples.first().pressure>=0),batch)
    }
}
