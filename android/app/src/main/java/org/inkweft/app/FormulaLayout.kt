// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Canvas
import org.inkweft.core.*
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.UUID
import kotlin.math.*

/** Formula source is author data; the drawable is a disposable vector layout. */
internal object FormulaLayout {
    @Synchronized fun drawable(text:String,size:Float,color:Int):JLatexMathDrawable {
        FormulaText.validate(text)
        val lines=text.trim().lines().filter{it.isNotBlank()}
        val latex=if(lines.size==1)lines.single() else "\\begin{aligned}"+lines.joinToString("\\\\"){"&"+it}+"\\end{aligned}"
        return JLatexMathDrawable.builder(latex).textSize(size).color(color).padding(2).build().also{
            require(it.intrinsicWidth in 1..16000&&it.intrinsicHeight in 1..16000){"FORMULA_SIZE"}
        }
    }
    fun build(strokes:List<InkStroke>,text:String,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString()):PageObject {
        require(strokes.isNotEmpty())
        val b=strokes.map{it.bounds()}.reduce{a,v->a.union(v)}
        val x=if(world)b.left.toFloat()else b.left.toFloat().coerceIn(0f,976f)
        val y=if(world)b.top.toFloat()else b.top.toFloat().coerceIn(0f,1390f)
        val color=strokes.first().color
        val initial=drawable(text,options.size,color)
        val size=if(options.preserveLayout)(options.size*min((b.right-b.left)/initial.intrinsicWidth,(b.bottom-b.top)/initial.intrinsicHeight)).toFloat().coerceIn(12f,96f)else options.size
        val layout=drawable(text,size,color)
        val maxWidth=if(world)4000f else 1000f-x;val maxHeight=if(world)4000f else 1414f-y
        val scale=minOf(1f,maxWidth/layout.intrinsicWidth,maxHeight/layout.intrinsicHeight)
        require(size*scale>=12f){"FORMULA_SIZE"}
        return PageObject(id,PageObjectKind.FORMULA,x,y,max(24f,layout.intrinsicWidth*scale),max(24f,layout.intrinsicHeight*scale),text=text.trim(),fontSize=size*scale,color=color,sourceStrokeIds=strokes.map{it.id})
    }
    fun edited(original:PageObject,text:String,size:Float,color:Int,world:Boolean):PageObject {
        if(text.trim()==original.text&&size==original.fontSize)return original.copy(color=color)
        val layout=drawable(text,size,color);val width=max(24f,layout.intrinsicWidth.toFloat());val height=max(24f,layout.intrinsicHeight.toFloat())
        require(width<=4000&&height<=4000&&(world||original.x+width<=1000&&original.y+height<=1414)){"公式超出页面，请减小字号或分段编辑"}
        return original.copy(text=text.trim(),fontSize=size,color=color,width=width,height=height,erasures=emptyList())
    }
    fun draw(canvas:Canvas,o:PageObject,layout:JLatexMathDrawable){
        val saved=canvas.save();canvas.translate(o.x,o.y)
        val scale=min(o.width/layout.intrinsicWidth,o.height/layout.intrinsicHeight)
        canvas.scale(scale,scale);layout.draw(canvas);canvas.restoreToCount(saved)
    }
}
