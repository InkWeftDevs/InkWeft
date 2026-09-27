// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.runtime.*
import org.inkweft.core.*
import java.util.UUID

internal data class BeautyOptions(val enabled:Boolean=false,val font:TextFont=TextFont.WENKAI,
    val size:Float=28f,val spacing:Float=1.2f,val bold:Boolean=false)
internal class BeautyStore(context:Context){
    private val prefs=context.getSharedPreferences("inkweft-beauty",Context.MODE_PRIVATE)
    fun read()=BeautyOptions(prefs.getBoolean("enabled",false),TextFont.entries.getOrElse(prefs.getInt("font",1)){TextFont.WENKAI},
        prefs.getFloat("size",28f).coerceIn(12f,96f),prefs.getFloat("spacing",1.2f).coerceIn(1f,2f),prefs.getBoolean("bold",false))
    fun save(value:BeautyOptions){prefs.edit().putBoolean("enabled",value.enabled).putInt("font",value.font.ordinal)
        .putFloat("size",value.size).putFloat("spacing",value.spacing).putBoolean("bold",value.bold).apply()}
}

@Composable internal fun AutomaticBeautyBinding(model:PageObjectViewModel,ink:InkUi,writing:Boolean,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
    SideEffect{model.observeBeauty(ink,writing,options,world,app)}
}

internal fun beautyObject(strokes:List<InkStroke>,text:String,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString()):PageObject {
    require(strokes.isNotEmpty()&&text.isNotBlank()&&text.length<=4000)
    val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
    val x=if(world)bounds.left.toFloat()else bounds.left.toFloat().coerceIn(0f,976f)
    val y=if(world)bounds.top.toFloat()else bounds.top.toFloat().coerceIn(0f,1390f)
    val width=maxOf(80f,(bounds.right-bounds.left).toFloat()+options.size).coerceAtMost(if(world)4000f else 1000f-x)
    val o=PageObject(id,PageObjectKind.TEXT,x,y,width,24f,text=text,color=strokes.first().color,fontSize=options.size,
        font=options.font,lineSpacing=options.spacing,bold=options.bold,sourceStrokeIds=strokes.map{it.id})
    val height=maxOf(24f,TextStyles.layout(o).height+4f)
    require(height<=4000f&&(world||y+height<=1414f))
    return o.copy(height=height)
}
