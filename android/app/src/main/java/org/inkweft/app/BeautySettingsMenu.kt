// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.inkweft.core.TextFont
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BeautySettingsMenu(expanded:Boolean,dismiss:()->Unit,value:BeautyOptions,change:(BeautyOptions)->Unit,select:()->Unit,smooth:()->Unit){
    var section by remember(expanded){mutableStateOf<String?>(null)}
    DropdownMenu(expanded,dismiss,shape=InkTheme.FloatingShape,containerColor=InkTheme.Surface,tonalElevation=0.dp,
        shadowElevation=InkTheme.FloatingElevation,modifier=Modifier.width(320.dp).testTag("beauty-settings")){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={if(section==null)dismiss()else section=null},modifier=Modifier.testTag("beauty-close").describedAs(if(section==null)"关闭美化参数"else"返回美化参数")){Glyph("back")}
            Text(when(section){"font"->"美化字体";"language"->"书写语言";else->"自动美化"},style=InkTheme.PanelTitle)
        }
        if(section=="font")TextFont.entries.forEach{font->
            DropdownMenuItem(text={Text(TextStyles.name(font),fontFamily=FontFamily(TextStyles.face(font)))},trailingIcon={if(value.font==font)Glyph("check",Forest)},
                onClick={change(value.copy(font=font,keepInk=false,formula=false));section=null},modifier=Modifier.testTag("font-${font.name}"))
        }else if(section=="language")BeautyLanguage.entries.forEach{language->
            DropdownMenuItem(text={Text(language.title)},trailingIcon={if(value.language==language)Glyph("check",Forest)},onClick={change(value.copy(language=language));section=null},modifier=Modifier.testTag("beauty-language-${language.name}"))
        }else{
            Column(Modifier.padding(horizontal=16.dp)){
                BeautySwitch("自动美化",value.enabled,"beauty-enabled"){change(value.copy(enabled=it))}
                if(!value.formula)TextButton(onClick={section="font"},contentPadding=PaddingValues(0.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("beauty-font-picker")){
                    Text("美化字体",color=TextInk);Spacer(Modifier.weight(1f));Text(if(value.keepInk)"选择字体"else TextStyles.name(value.font),fontSize=13.sp,color=Quiet);Text("  ›",color=Quiet)
                }
                HorizontalDivider(color=Line)
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    FilterChip(value.keepInk,{change(value.copy(keepInk=true,formula=false))},label={Text("保留笔形")},modifier=Modifier.testTag("beauty-keep-ink"))
                    FilterChip(!value.keepInk&&!value.formula,{change(value.copy(keepInk=false,formula=false))},label={Text("文字美化")},modifier=Modifier.testTag("beauty-replace-font"))
                    FilterChip(!value.keepInk&&value.formula,{change(value.copy(keepInk=false,formula=true))},label={Text("公式美化")},modifier=Modifier.testTag("beauty-formula"))
                }
                if(value.keepInk){
                    Text("只整理原迹，不换字体；适合公式",fontSize=12.sp,color=Quiet)
                    BeautySlider("整理强度","${(value.inkStrength*100).roundToInt()}%",value.inkStrength,0f..1f,true,"beauty-ink-strength"){change(value.copy(inkStrength=it))}
                }else if(value.formula){
                    Text("离线识别分式、上下标与数学符号；先校对，再应用。保存后仍可编辑公式或恢复原迹。",fontSize=12.sp,color=Quiet)
                    BeautySwitch("贴近原稿大小",value.preserveLayout,"beauty-formula-layout"){change(value.copy(preserveLayout=it))}
                    BeautySlider("字号",value.size.toInt().toString(),value.size,12f..96f,!value.preserveLayout,"beauty-formula-size"){change(value.copy(size=it.roundToInt().toFloat()))}
                }else{
                Text("稳定片段自动转换，含糊内容可校对",fontSize=12.sp,color=Quiet)
                BeautySwitch("动态加粗",value.bold,"beauty-dynamic-bold"){change(value.copy(bold=it))}
                TextButton(onClick={section="language"},contentPadding=PaddingValues(0.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("beauty-language-picker")){
                    Text("书写语言",color=TextInk);Spacer(Modifier.weight(1f));Text(value.language.title,fontSize=13.sp,color=Quiet);Text("  ›",color=Quiet)
                }
                HorizontalDivider(color=Line)
                BeautySwitch("段落整理",!value.preserveLayout,"beauty-arrange"){change(value.copy(preserveLayout=!it))}
                Text(if(value.preserveLayout)"原位换字体 · 字号随原行，保留基线"else"按选区宽度排版，应用前预览",fontSize=12.sp,color=Quiet)
                BeautySlider("字号",value.size.toInt().toString(),value.size,12f..96f,!value.preserveLayout,"beauty-font-size"){change(value.copy(size=it.roundToInt().toFloat()))}
                BeautySlider("行间距","%.1f×".format(value.spacing),value.spacing,1f..2f,!value.preserveLayout,"beauty-line-spacing"){change(value.copy(spacing=(it*10).roundToInt()/10f))}
                }
            }
            HorizontalDivider(color=Line)
            DropdownMenuItem(text={Text(if(value.formula)"框选公式"else"框选美化")},onClick=select,modifier=Modifier.testTag("beauty-select"))
            DropdownMenuItem(text={Text("笔形润色")},onClick=smooth)
        }
    }
}
@Composable private fun BeautySwitch(label:String,checked:Boolean,tag:String,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=14.sp);Switch(checked,change,modifier=Modifier.testTag(tag).describedAs(label))}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BeautySlider(label:String,display:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,tag:String,change:(Float)->Unit){
    Column(Modifier.padding(top=4.dp)){
        Row{Text(label,Modifier.weight(1f),fontSize=13.sp,color=if(enabled)TextInk else Quiet);Text(display,fontSize=13.sp,color=if(enabled)TextInk else Quiet)}
        Slider(value,change,enabled=enabled,valueRange=range,modifier=Modifier.fillMaxWidth().height(40.dp).testTag(tag).describedAs(label),
            thumb={Surface(Modifier.size(20.dp),shape=CircleShape,color=Color.White,border=BorderStroke(1.dp,Line),shadowElevation=if(enabled)2.dp else 0.dp){}},
            track={Canvas(Modifier.fillMaxWidth().height(4.dp)){
                drawLine(Line,Offset(0f,size.height/2),Offset(size.width,size.height/2),size.height,StrokeCap.Round)
                val fraction=(value-range.start)/(range.endInclusive-range.start)
                drawLine(if(enabled)Forest else Quiet.copy(alpha=.35f),Offset(0f,size.height/2),Offset(size.width*fraction,size.height/2),size.height,StrokeCap.Round)
            }})
    }
}
