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
    var advanced by remember{mutableStateOf(false)}
    fun update(value:EraserSettings){draft=value;apply(value)}
    EditorPanel("橡皮","",dismiss,"eraser-dialog",kind=PanelKind.SETTINGS){

    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        EditorSegments(listOf("常用","高级"),if(advanced)1 else 0,listOf("eraser-basic","eraser-advanced")){advanced=it==1}
        if(!advanced){
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            FilterChip(!draft.whole&&!draft.onlyTape,{update(draft.copy(whole=false))},label={Text("局部擦除")},enabled=!draft.onlyTape,modifier=Modifier.testTag("eraser-local"))
            FilterChip(draft.whole&&!draft.onlyTape,{update(draft.copy(whole=true))},label={Text("整笔擦除")},enabled=!draft.onlyTape,modifier=Modifier.testTag("eraser-whole"))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Text("擦除大小",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            Text("${draft.diameterDp.toInt()}",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("eraser-size-value"))
        }
        Canvas(Modifier.fillMaxWidth().height(104.dp).testTag("eraser-size-preview")){
            val radius=draft.diameterDp.dp.toPx()/2
            drawCircle(androidx.compose.ui.graphics.Color(0x183f7d67),radius)
            drawCircle(androidx.compose.ui.graphics.Color(0xff24342f),radius,style=Stroke(1.dp.toPx()))
        }
        Slider(draft.diameterDp,{update(draft.copy(diameterDp=it.roundToInt().toFloat()))},valueRange=8f..96f,modifier=Modifier.testTag("eraser-size-slider"))
        TextButton(circle,enabled=!draft.onlyTape,modifier=Modifier.heightIn(min=48.dp).testTag("eraser-circle")){Glyph("area-erase");Spacer(Modifier.width(8.dp));Text("圈选擦除")}
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(14f,28f,56f).forEachIndexed{i,size->FilterChip(draft.diameterDp==size,{update(draft.copy(diameterDp=size))},label={Text(listOf("小","中","大")[i])},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("eraser-size-$i"))}}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("只擦荧光笔",Modifier.weight(1f));Checkbox(draft.onlyHighlighter,{update(draft.copy(onlyHighlighter=it,onlyTape=false))},modifier=Modifier.testTag("erase-highlighter-only"))}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("只擦胶带（整条）",Modifier.weight(1f));Checkbox(draft.onlyTape,{update(draft.copy(onlyTape=it,onlyHighlighter=false))},modifier=Modifier.testTag("erase-tape-only"))}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("擦除后切回笔",Modifier.weight(1f));Switch(draft.returnToPen,{update(draft.copy(returnToPen=it))},modifier=Modifier.testTag("eraser-return-pen"))}
        }
    }}
}
