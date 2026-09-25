// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Settings only: no note IDs, text, stroke coordinates or diagnostics. */
internal class PenWidthStore(context:Context,name:String="inkweft-pen-widths") {
    private val preferences=context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE)
    fun read():List<Float> = (0..2).map { tool ->
        val default=if(tool==2)22f else 3f
        val width=runCatching{preferences.getFloat("width-$tool",default)}.getOrDefault(default)
        if(width.isFinite() && width in range(tool))width else default
    }
    suspend fun save(tool:Int,width:Float):Boolean {
        require(tool in 0..2 && width.isFinite() && width in range(tool))
        return withContext(Dispatchers.IO){preferences.edit().putFloat("width-$tool",width).commit()}
    }
    companion object {
        fun range(tool:Int)=if(tool==2)6f..40f else .5f..12f
        fun presets(tool:Int)=if(tool==2)listOf(12f,22f,34f)else listOf(1.5f,3f,6f)
        fun label(width:Float)=String.format(Locale.ROOT,"%.1f",width)
    }
}

@Composable
internal fun PenWidthDialog(tool:Int,current:Float,onDismiss:()->Unit,onApply:(Float)->Unit) {
    var draft by remember(tool,current){mutableFloatStateOf(current)}
    val name=listOf("黑色笔","红色笔","荧光笔")[tool]
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("pen-width-dialog"),title={Text("$name · 线宽")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("细 / 中 / 粗：点击即可选择，也可以拖动下方滑块。",fontSize=13.sp,color=Quiet)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                PenWidthStore.presets(tool).forEachIndexed { index,width ->
                    FilterChip(selected=draft==width,onClick={draft=width},label={Text(listOf("细","中","粗")[index]+" "+PenWidthStore.label(width))},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("width-preset-$index"))
                }
            }
            Canvas(Modifier.fillMaxWidth().height(36.dp)){
                drawLine(Forest,Offset(8.dp.toPx(),size.height/2),Offset(size.width-8.dp.toPx(),size.height/2),strokeWidth=(draft.coerceAtMost(18f)).dp.toPx(),cap=StrokeCap.Round)
            }
            Text("线宽 ${PenWidthStore.label(draft)}",modifier=Modifier.testTag("pen-width-value"),fontSize=16.sp)
            Slider(value=draft,onValueChange={draft=(it*10).roundToInt()/10f},valueRange=PenWidthStore.range(tool),modifier=Modifier.testTag("pen-width-slider"))
            Text("只影响之后的笔迹，不改已有内容。三支笔分别记忆；确定后保存到本机笔设置。这里的数值是画布线宽，不是屏幕物理毫米。",fontSize=12.sp,color=Quiet)
        }
    },confirmButton={TextButton(onClick={onApply(draft)},modifier=Modifier.testTag("apply-pen-width")){Text("使用此线宽")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
