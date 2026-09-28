package org.inkweft.app

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import org.inkweft.core.*
import kotlin.math.*

@Composable internal fun PenStrokePreview(pen:InkPen,color:Int,width:Float,recipe:BrushRecipe=BrushRecipe(),compact:Boolean=false){
    val renderer=remember{InkBrushes.renderer()}
    val stroke=remember(pen,color,width,recipe){InkStroke("00000000-0000-0000-0000-000000000001",pen,color,width,InkTool.STYLUS,
        (0..80).map{i->val t=i/80f;InkSample(20+360*t,48+18*sin(t*4*PI).toFloat(),i*8L,.08f+.92f*sin(t*PI).toFloat())},appearance=StrokeAppearance(recipe))}
    Canvas(Modifier.fillMaxWidth().height(if(compact)40.dp else 80.dp).background(Color.White)){
        val matrix=Matrix().apply{val scale=minOf(size.width/400f,size.height/96f);setScale(scale,scale);postTranslate(0f,(size.height-96f*scale)/2)}
        val canvas=drawContext.canvas.nativeCanvas;val saved=canvas.save()
        try{canvas.concat(matrix);if(stroke.pen==InkPen.PENCIL)PencilRenderer.draw(canvas,stroke)else renderer.draw(canvas,InkBrushes.stroke(stroke),matrix)}finally{canvas.restoreToCount(saved)}
    }
}
