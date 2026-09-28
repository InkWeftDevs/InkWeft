// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import java.util.UUID
import kotlin.math.*

internal object ShapeInk {
    val kinds=linkedMapOf("rectangle" to "矩形","ellipse" to "椭圆","triangle" to "三角形","line" to "直线","arrow" to "箭头","table" to "表格")
    fun create(kind:String,viewport:CanvasViewport,world:Boolean,color:Int,width:Float):List<InkStroke>{
        val x=(viewport.centerX-120).toFloat().coerceIn(if(world)-BoardLimits.WORLD+1000 else 12f,if(world)BoardLimits.WORLD-1000 else 748f)
        val y=(viewport.centerY-90).toFloat().coerceIn(if(world)-BoardLimits.WORLD+1000 else 12f,if(world)BoardLimits.WORLD-1000 else 1222f)
        val paths:List<List<Pair<Float,Float>>> = when(kind){
            "rectangle"->listOf(listOf(0f to 0f,240f to 0f,240f to 180f,0f to 180f,0f to 0f))
            "ellipse"->listOf((0..96).map{val angle=it*2*PI/96;(120+120*cos(angle)).toFloat() to (90+90*sin(angle)).toFloat()})
            "triangle"->listOf(listOf(120f to 0f,240f to 180f,0f to 180f,120f to 0f))
            "line"->listOf(listOf(0f to 90f,240f to 90f))
            "arrow"->listOf(listOf(0f to 90f,240f to 90f),listOf(180f to 40f,240f to 90f,180f to 140f))
            "table"->(0..3).map{listOf(it*80f to 0f,it*80f to 180f)}+(0..3).map{listOf(0f to it*60f,240f to it*60f)}
            else->error("Unknown shape $kind")
        }
        return paths.map{points->InkStroke(UUID.randomUUID().toString(),InkPen.BALLPOINT,color or 0xff000000.toInt(),width.coerceIn(.5f,12f),InkTool.TOUCH,
            points.mapIndexed{i,(a,b)->InkSample(x+a,y+b,i*10L,world=world)},world,appearance=StrokeAppearance(BrushRecipe()))}
    }
}
@Composable internal fun ShapePicker(dismiss:()->Unit,choose:(String)->Unit){
    EditorPanel("图形","",dismiss,"shape-picker"){
        Column {
            ShapeInk.kinds.entries.chunked(2).forEach { row ->
                Row {
                    row.forEach { (id,title) ->
                        TextButton(onClick={choose(id)},modifier=Modifier.weight(1f).heightIn(min=56.dp).testTag("shape-$id")){Text(title)}
                    }
                }
            }
        }
    }
}
