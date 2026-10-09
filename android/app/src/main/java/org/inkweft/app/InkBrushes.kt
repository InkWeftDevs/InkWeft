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
import android.graphics.Bitmap
import androidx.ink.brush.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.*

/** Live ink, reopened strokes and the preset preview share one render policy. */
internal object InkBrushes {
    private val round=StockBrushes.marker(StockBrushes.MarkerVersion.V1)
    private val chisel=StockBrushes.highlighter(version=StockBrushes.HighlighterVersion.V1)
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
    private val texture by lazy {
        val bitmap=Bitmap.createBitmap(GraphiteMaterial.SIZE,GraphiteMaterial.SIZE,Bitmap.Config.ARGB_8888)
        val pixels=GraphiteMaterial.alpha().map{((it.toInt() and 255) shl 24) or 0xffffff}.toIntArray()
        bitmap.setPixels(pixels,0,GraphiteMaterial.SIZE,0,0,GraphiteMaterial.SIZE,GraphiteMaterial.SIZE);bitmap
    }
    fun renderer()=CanvasStrokeRenderer.create(object:TextureBitmapStore { override fun get(clientTextureId:String):Bitmap?=if(clientTextureId==GraphiteMaterial.digest)texture else null })
    private fun behavior(target:TargetNode.Target,a:Float,b:Float,source:SourceNode.Source=SourceNode.Source.NORMALIZED_PRESSURE,lo:Float=0f,hi:Float=1f)=BrushBehavior(TargetNode(target,a,b,SourceNode(source,lo,hi)))
    private fun modern(pen:InkPen,a:StrokeAppearance):BrushFamily {
        val r=a.recipe
        if(pen==InkPen.BALLPOINT)return BrushFamily(BrushTip(),inputModel=BrushFamily.InputModel.PASSTHROUGH_MODEL)
        if(pen==InkPen.PEN)return BrushFamily(BrushTip(scaleY=if(r.roundNib)1f else 1f-.15f*(r.sharpness/.35f).coerceAtMost(2f),rotationDegrees=if(r.roundNib)0f else 45f,cornerRounding=1f,
            behaviors=listOf(behavior(TargetNode.Target.SIZE_MULTIPLIER,r.minimumWidth,1f))),inputModel=BrushFamily.InputModel.PASSTHROUGH_MODEL)
        val size=32f*r.grain
        val layers=listOf(BrushPaint.TilingTexture(clientTextureId=GraphiteMaterial.digest,sizeX=size,sizeY=size,
            offsetX=(a.grainSeed and 63).toFloat()/64f-a.originX/size,offsetY=((a.grainSeed ushr 6) and 63).toFloat()/64f-a.originY/size,
            sizeUnit=BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES,origin=BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN))
        val density=r.hardnessFactor*r.density
        val behaviors=mutableListOf(behavior(TargetNode.Target.SIZE_MULTIPLIER,.85f,1f),behavior(TargetNode.Target.OPACITY_MULTIPLIER,.18f*density,.73f*density))
        if(r.tiltShading)behaviors.add(behavior(TargetNode.Target.SIZE_MULTIPLIER,1f,4f,SourceNode.Source.TILT_IN_RADIANS,(PI/6).toFloat(),(PI/3).toFloat()))
        return BrushFamily(BrushTip(cornerRounding=1f,behaviors=behaviors),BrushPaint(textureLayers=layers,selfOverlap=SelfOverlap.DISCARD),inputModel=BrushFamily.InputModel.PASSTHROUGH_MODEL)
    }
    private val families=object:LinkedHashMap<Pair<InkPen,StrokeAppearance>,BrushFamily>(64,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<InkPen,StrokeAppearance>,BrushFamily>?)=size>64}
    fun brush(pen:InkPen,color:Int,width:Float,hasPressure:Boolean,appearance:StrokeAppearance=StrokeAppearance()):Brush {
        val family=when {
            appearance.recipe.version==1&&(pen==InkPen.PEN||pen==InkPen.PENCIL||pen==InkPen.BALLPOINT)->synchronized(families){
                val familyAppearance=if(pen==InkPen.PENCIL)appearance else StrokeAppearance(appearance.recipe)
                families.getOrPut(pen to familyAppearance){modern(pen,familyAppearance)}
            }
            pen==InkPen.HIGHLIGHTER||pen==InkPen.MARKER->chisel
            pen==InkPen.BRUSH->if(hasPressure)brushPressure else brushSpeed
            pen==InkPen.PEN->if(hasPressure)fountainPressure else fountainSpeed
            else->round
        }
        return Brush.createWithColorIntArgb(family,color,width,.1f)
    }
    fun add(batch:MutableStrokeInputBatch,sample:InkSample,tool:InkTool,pen:InkPen,appearance:StrokeAppearance=StrokeAppearance()){
        val input=when(tool){InkTool.STYLUS->InputToolType.STYLUS;InkTool.MOUSE->InputToolType.MOUSE;InkTool.TOUCH->InputToolType.TOUCH}
        val p=if(appearance.recipe.version==1)when(pen){
            InkPen.PEN->(if(sample.pressure<0).65f else sample.pressure).pow(appearance.recipe.pressureExponent)
            InkPen.PENCIL->sqrt(if(sample.pressure<0).5f else sample.pressure)
            else->PenKinds.renderPressure(pen,sample.pressure)
        }else PenKinds.renderPressure(pen,sample.pressure)
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
        stroke.renderSamples().forEach{sample->
            val previous=samples.lastOrNull()
            if(previous!=null&&previous.x==sample.x&&previous.y==sample.y&&previous.elapsedMs==sample.elapsedMs)samples[samples.lastIndex]=sample
            else samples.add(sample)
        }
        val batch=MutableStrokeInputBatch();samples.forEach{add(batch,it,stroke.tool,stroke.pen,stroke.appearance)}
        return Stroke(brush(stroke.pen,stroke.color,stroke.width,stroke.samples.first().pressure>=0,stroke.appearance),batch)
    }
}
