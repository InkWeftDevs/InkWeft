// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class EraserSettings(val diameterDp:Float=28f,val whole:Boolean=false,val onlyHighlighter:Boolean=false) {
    init {require(diameterDp.isFinite()&&diameterDp in 8f..96f)}
}
internal class EraserSettingsStore(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("inkweft-eraser",Context.MODE_PRIVATE)
    fun read():EraserSettings=runCatching{EraserSettings(prefs.getFloat("diameter",28f),prefs.getBoolean("whole",false),prefs.getBoolean("highlighter",false))}.getOrDefault(EraserSettings())
    suspend fun save(value:EraserSettings)=withContext(Dispatchers.IO){prefs.edit().putFloat("diameter",value.diameterDp).putBoolean("whole",value.whole).putBoolean("highlighter",value.onlyHighlighter).commit()}
}
@Composable
internal fun EraserDialog(current:EraserSettings,dismiss:()->Unit,apply:(EraserSettings)->Unit){
    var draft by remember{mutableStateOf(current)}
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("eraser-dialog"),title={Text("橡皮 · 模式与范围")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            FilterChip(!draft.whole,{draft=draft.copy(whole=false)},label={Text("局部擦除")},modifier=Modifier.testTag("eraser-local"))
            FilterChip(draft.whole,{draft=draft.copy(whole=true)},label={Text("整笔擦除")},modifier=Modifier.testTag("eraser-whole"))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(14f,28f,56f).forEachIndexed{i,size->FilterChip(draft.diameterDp==size,{draft=draft.copy(diameterDp=size)},label={Text(listOf("小","中","大")[i])},modifier=Modifier.weight(1f).testTag("eraser-size-$i"))}}
        Text("擦除直径 ${draft.diameterDp.toInt()} dp",fontSize=14.sp,modifier=Modifier.testTag("eraser-size-value"))
        Canvas(Modifier.fillMaxWidth().height(104.dp).testTag("eraser-preview")){drawCircle(Forest.copy(alpha=.09f),radius=draft.diameterDp.dp.toPx()/2);drawCircle(Forest,radius=draft.diameterDp.dp.toPx()/2,style=Stroke(1.dp.toPx()))}
        Slider(draft.diameterDp,{draft=draft.copy(diameterDp=it.roundToInt().toFloat())},valueRange=8f..96f,modifier=Modifier.testTag("eraser-size-slider"))
        Row{Checkbox(draft.onlyHighlighter,{draft=draft.copy(onlyHighlighter=it)},modifier=Modifier.testTag("erase-highlighter-only"));Text("只擦荧光笔，保留普通笔",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        Text("圆圈就是触摸或笔悬停时的擦除范围，屏幕大小不随缩放变化。局部擦除是真实笔迹裁剪，不是涂白。一次拖动可以整体撤销。",fontSize=12.sp,color=Quiet)
    }},confirmButton={TextButton(onClick={apply(draft)},modifier=Modifier.testTag("apply-eraser")){Text("使用此橡皮")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
