// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme

/** Direct access to existing per-pen settings; this component never owns author state. */
@Composable internal fun EditorInkPresets(tool:Int,width:Float,color:Int,enabled:Boolean,onWidth:(Float)->Unit,onColor:(Int)->Unit){
    Row(verticalAlignment=Alignment.CenterVertically){
        PenWidthStore.presets(tool).forEachIndexed{index,value->
            IconToggleButton(width==value,{onWidth(value)},enabled=enabled,
                modifier=Modifier.size(48.dp).testTag("quick-width-$index").describedAs("笔宽 ${PenWidthStore.label(value)}")){
                Box(Modifier.size(32.dp).background(if(width==value)InkTheme.Selected else Color.Transparent,CircleShape),contentAlignment=Alignment.Center){
                    Box(Modifier.width(18.dp).height((index+1).dp).background(if(width==value)InkTheme.Accent else InkTheme.Text,CircleShape))
                }
            }
        }
        VerticalDivider(Modifier.padding(horizontal=12.dp).height(22.dp),color=InkTheme.Divider)
        PenWidthStore.colors(tool).forEachIndexed{index,value->
            IconToggleButton(color==value,{onColor(value)},enabled=enabled,
                modifier=Modifier.size(48.dp).testTag("quick-color-$index").describedAs("颜色："+listOf("墨黑","红色","蓝色","绿色","黄色")[index])){
                val swatch=Color(value or 0xff000000.toInt())
                Box(Modifier.size(26.dp).then(if(color==value)Modifier.border(1.2.dp,swatch,CircleShape)else Modifier).padding(4.dp).background(swatch,CircleShape))
            }
        }
    }
}
