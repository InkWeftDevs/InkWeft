// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun PenCaseColors(current:Int,highlighter:Boolean,enabled:Boolean,onColor:(Int)->Unit){
    val rgb=listOf(0x24342f,0xb83239,0x2f53aa,0x126b50,0xe1ad19,0x7355a2)
    var expanded by remember{mutableStateOf(false)}
    fun apply(rgb:Int){onColor(rgb or if(highlighter)0x66000000 else 0xff000000.toInt())}
    Column(Modifier.testTag("pen-case-colors")){
        rgb.chunked(2).forEach{row->Row{row.forEach{c->
            IconToggleButton((current and 0xffffff)==c,{apply(c)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("case-color-${c.toString(16)}").describedAs("颜色 #${c.toString(16)}")){
                Box(Modifier.size(28.dp).border(if((current and 0xffffff)==c)2.dp else 0.dp,if((current and 0xffffff)==c)Forest else Color.Transparent,CircleShape).padding(4.dp).background(Color(c or 0xff000000.toInt()),CircleShape))
            }
        }}}
        Box{
            TextButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.width(96.dp).testTag("case-color-more")){Text("更多颜色")}
            DropdownMenu(expanded,{expanded=false},containerColor=Color.White){
                var hex by remember(expanded,current){mutableStateOf("%06X".format(current and 0xffffff))}
                Column(Modifier.width(240.dp).padding(12.dp)){
                    Text("自定义颜色",style=MaterialTheme.typography.titleSmall)
                    OutlinedTextField(hex,{hex=it.take(6)},singleLine=true,prefix={Text("#")},label={Text("六位色值")},modifier=Modifier.testTag("case-color-hex"))
                    TextButton(onClick={hex.toIntOrNull(16)?.let(::apply);expanded=false},enabled=hex.length==6&&hex.toIntOrNull(16)!=null,modifier=Modifier.testTag("case-color-apply")){Text("选用颜色")}
                }
            }
        }
    }
}
