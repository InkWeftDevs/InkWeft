// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.inkweft.core.*

@Composable internal fun PaperPickerDialog(current:PaperStyle,world:Boolean,dismiss:()->Unit,choose:(PaperStyle)->Unit){
    var selected by rememberSaveable{mutableStateOf(current)}
    var category by rememberSaveable{mutableStateOf("全部")};var query by rememberSaveable{mutableStateOf("")}
    val styles=PaperStyle.entries.filter{(!world||it.ordinal<4)&&PaperTemplates.matches(it,category,query)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("paper-picker"),color=Color.White){Column(Modifier.safeDrawingPadding().imePadding()){
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=dismiss,modifier=Modifier.testTag("paper-picker-cancel")){Text("取消")}
                Text("更换纸面",Modifier.weight(1f),fontSize=22.sp)
                Button(onClick={choose(selected)},modifier=Modifier.testTag("paper-picker-confirm")){Text("使用此纸面")}
            }
            Text("${PaperTemplates.title(selected)} · 只更换背景，保留原笔迹",Modifier.padding(horizontal=20.dp),fontSize=13.sp,color=Quiet)
            OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索：会议、阅读、打卡…")},modifier=Modifier.fillMaxWidth().padding(16.dp).testTag("paper-search"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                (if(world)listOf("全部","基础") else PaperTemplates.categories).forEach{c->FilterChip(category==c,{category=c},label={Text(c)})}
            }
            if(styles.isEmpty())Text("没有匹配的纸面，试试其他关键词或分类。",Modifier.padding(20.dp),color=Quiet)
            LazyVerticalGrid(columns=GridCells.Adaptive(170.dp),modifier=Modifier.weight(1f).testTag("paper-picker-grid"),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                items(styles,key={it.name}){style->Surface(onClick={selected=style},color=if(selected==style)Leaf else Color.White,shape=RoundedCornerShape(10.dp),border=BorderStroke(if(selected==style)2.dp else 1.dp,if(selected==style)Forest else Line),modifier=Modifier.testTag("paper-option-${style.name.lowercase()}")){
                    Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.width(140.dp).height(198.dp).border(1.dp,Line)){PaperThumbnail(world,style)}
                        Text(PaperTemplates.title(style),Modifier.padding(top=10.dp),fontSize=15.sp);Text(PaperTemplates.description(style),fontSize=11.sp,color=Quiet)}
                }}
            }
        }}
    }
}
