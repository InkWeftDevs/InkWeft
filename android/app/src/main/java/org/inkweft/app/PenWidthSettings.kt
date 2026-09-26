// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

/** Global pen presets, not author data. Existing A3 width keys stay valid. */
internal class PenWidthStore(context:Context,name:String="inkweft-pen-widths") {
    private val preferences=context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val global=if(name.startsWith("inkweft-pen-widths-book-"))PenWidthStore(context)else null
    fun read():List<Float> = (0..2).map { tool ->
        val fallback=global?.read()?.get(tool)?:if(tool==2)22f else 3f
        val width=runCatching{preferences.getFloat("width-$tool",fallback)}.getOrDefault(fallback)
        if(width.isFinite() && width in range(tool))width else fallback
    }
    fun readColors():List<Int> = (0..2).map { tool ->
        val fallback=global?.readColors()?.get(tool)?:colors(tool)[if(tool==1)1 else if(tool==2)4 else 0]
        runCatching{preferences.getInt("color-$tool",fallback)}.getOrDefault(fallback).takeIf{validColor(tool,it)}?:fallback
    }
    suspend fun save(tool:Int,width:Float):Boolean {
        require(tool in 0..2 && width.isFinite() && width in range(tool))
        return writes.withLock { withContext(Dispatchers.IO){preferences.edit().putFloat("width-$tool",width).commit()} }
    }
    suspend fun savePreset(tool:Int,width:Float,color:Int):Boolean {
        require(tool in 0..2 && width.isFinite() && width in range(tool) && validColor(tool,color))
        return writes.withLock { withContext(Dispatchers.IO){preferences.edit().putFloat("width-$tool",width).putInt("color-$tool",color).commit()} }
    }
    companion object {
        fun validColor(tool:Int,color:Int)=(color ushr 24)==(if(tool==2)0x66 else 0xff)
        private val writes=Mutex()
        fun range(tool:Int)=if(tool==2)6f..40f else .5f..12f
        fun presets(tool:Int)=if(tool==2)listOf(12f,22f,34f)else listOf(1.5f,3f,6f)
        fun colors(tool:Int)=listOf(0x24342f,0xb83239,0x2f53aa,0x126b50,0xe1ad19).map{it or (if(tool==2)0x66000000 else 0xff000000.toInt())}
        fun label(width:Float)=String.format(Locale.ROOT,"%.1f",width)
        fun name(tool:Int)=if(tool==2)"荧光笔"else"常用笔 ${tool+1}"
    }
}

/** Anchored to the toolbar. Outside taps dismiss without passing into ink. */
@Composable
internal fun PenPresetMenu(expanded:Boolean,tool:Int,current:Float,currentColor:Int,onDismiss:()->Unit,onApply:(Float,Int)->Unit) {
    DropdownMenu(expanded=expanded,onDismissRequest=onDismiss,modifier=Modifier.width(320.dp).testTag("pen-width-dialog")) {
        var draft by remember(expanded,tool,current){mutableFloatStateOf(current)}
        var color by remember(expanded,tool,currentColor){mutableIntStateOf(currentColor)}
        Column(Modifier.padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(PenWidthStore.name(tool),fontSize=17.sp,color=TextInk)
            HorizontalDivider(color=Line)
            Text("粗细",fontSize=13.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                PenWidthStore.presets(tool).forEachIndexed { index,width ->
                    FilterChip(selected=draft==width,onClick={draft=width},label={Text(listOf("细","中","粗")[index],fontSize=12.sp)},
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("width-preset-$index"))
                }
            }
            Text("线宽 ${PenWidthStore.label(draft)}",modifier=Modifier.testTag("pen-width-value"),fontSize=15.sp)
            Slider(value=draft,onValueChange={draft=(it*10).roundToInt()/10f},valueRange=PenWidthStore.range(tool),modifier=Modifier.testTag("pen-width-slider"))
            Text("颜色",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                PenWidthStore.colors(tool).forEachIndexed { index,value ->
                    Box(Modifier.size(48.dp).border(if(color==value)2.dp else 1.dp,if(color==value)Forest else Color.Transparent,CircleShape)
                        .clickable{color=value}.testTag("pen-color-$index").describedAs("颜色："+listOf("墨黑","红色","蓝色","绿色","黄色")[index]),contentAlignment=Alignment.Center) {
                        Box(Modifier.size(25.dp).background(Color(value or 0xff000000.toInt()),CircleShape))
                    }
                }
            }
            var hex by remember(color){mutableStateOf(String.format(Locale.ROOT,"%06X",color and 0xffffff))}
            OutlinedTextField(hex,{value->if(value.length<=6)hex=value.uppercase(Locale.ROOT)},label={Text("自定义颜色 · 六位十六进制")},isError=!hex.matches(Regex("[0-9A-F]{6}")),singleLine=true,modifier=Modifier.fillMaxWidth().testTag("pen-custom-color"))
            TextButton(onClick={color=hex.toInt(16) or (if(tool==2)0x66000000 else 0xff000000.toInt())},enabled=hex.matches(Regex("[0-9A-F]{6}"))){Text("使用自定义颜色")}
            Canvas(Modifier.fillMaxWidth().height(38.dp).background(Color.White)) {
                drawLine(Color(color),Offset(8.dp.toPx(),size.height/2),Offset(size.width-8.dp.toPx(),size.height/2),
                    strokeWidth=draft.coerceAtMost(24f).dp.toPx(),cap=StrokeCap.Round)
            }
            Text("预览为粗细示意。数值使用画布单位，不冒充物理毫米；压感使用当前笔刷与设备能力。",fontSize=11.sp,lineHeight=17.sp,color=Quiet)
            Text("只影响之后的笔迹。保存后，颜色和线宽在这支常用笔中一起记住。",fontSize=11.sp,lineHeight=17.sp,color=Quiet)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick=onDismiss,modifier=Modifier.testTag("cancel-pen-preset")){Text("取消")}
                Button(onClick={onApply(draft,color)},modifier=Modifier.testTag("apply-pen-width")){Text("保存到此笔")}
            }
        }
    }
}
