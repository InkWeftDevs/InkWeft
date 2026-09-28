// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

internal data class EraserSettings(val diameterDp:Float=28f,val whole:Boolean=false,val onlyHighlighter:Boolean=false,val returnToPen:Boolean=false,val onlyTape:Boolean=false) {
    init {require(diameterDp.isFinite()&&diameterDp in 8f..96f)}
}
internal class EraserSettingsStore(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("inkweft-eraser",Context.MODE_PRIVATE)
    fun read():EraserSettings=runCatching{EraserSettings(prefs.getFloat("diameter",28f),prefs.getBoolean("whole",false),prefs.getBoolean("highlighter",false),prefs.getBoolean("return-pen",false),prefs.getBoolean("tape-only",false))}.getOrDefault(EraserSettings())
    fun save(value:EraserSettings):Boolean{prefs.edit().putFloat("diameter",value.diameterDp).putBoolean("whole",value.whole).putBoolean("highlighter",value.onlyHighlighter).putBoolean("return-pen",value.returnToPen).putBoolean("tape-only",value.onlyTape).apply();return true}
}
@Composable
internal fun EraserDialog(current:EraserSettings,dismiss:()->Unit,circle:()->Unit={},apply:(EraserSettings)->Unit){
    var draft by remember{mutableStateOf(current)}
    fun update(value:EraserSettings){draft=value;apply(value)}
    EditorPanel("橡皮","",dismiss,"eraser-dialog"){

    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            FilterChip(!draft.whole&&!draft.onlyTape,{update(draft.copy(whole=false))},label={Text("局部擦除")},enabled=!draft.onlyTape,modifier=Modifier.testTag("eraser-local"))
            FilterChip(draft.whole&&!draft.onlyTape,{update(draft.copy(whole=true))},label={Text("整笔擦除")},enabled=!draft.onlyTape,modifier=Modifier.testTag("eraser-whole"))
        }
        TextButton(circle,enabled=!draft.onlyTape,modifier=Modifier.testTag("eraser-circle")){Glyph("area-erase");Text("圈选擦除")}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(14f,28f,56f).forEachIndexed{i,size->FilterChip(draft.diameterDp==size,{update(draft.copy(diameterDp=size))},label={Text(listOf("小","中","大")[i])},modifier=Modifier.weight(1f).testTag("eraser-size-$i"))}}
        Text("擦除直径 ${draft.diameterDp.toInt()} dp",fontSize=14.sp,modifier=Modifier.testTag("eraser-size-value"))
        Canvas(Modifier.fillMaxWidth().height(64.dp).testTag("eraser-preview")){drawCircle(Forest.copy(alpha=.09f),radius=draft.diameterDp.dp.toPx()/2);drawCircle(Forest,radius=draft.diameterDp.dp.toPx()/2,style=Stroke(1.dp.toPx()))}
        Slider(draft.diameterDp,{update(draft.copy(diameterDp=it.roundToInt().toFloat()))},valueRange=8f..96f,modifier=Modifier.testTag("eraser-size-slider"))
        Row{Checkbox(draft.onlyHighlighter,{update(draft.copy(onlyHighlighter=it,onlyTape=false))},modifier=Modifier.testTag("erase-highlighter-only"));Text("只擦荧光笔，保留普通笔",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        Row{Checkbox(draft.onlyTape,{update(draft.copy(onlyTape=it,onlyHighlighter=false))},modifier=Modifier.testTag("erase-tape-only"));Text("只擦胶带（整条）",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        Row{Switch(draft.returnToPen,{update(draft.copy(returnToPen=it))},modifier=Modifier.testTag("eraser-return-pen"));Text("擦除后自动切回笔",modifier=Modifier.padding(start=8.dp,top=12.dp))}
    }}
}
