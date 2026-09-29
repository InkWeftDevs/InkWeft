// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.inkweft.core.*

@Composable internal fun LearningLibrary(notes:List<Note>,review:Boolean,dismiss:()->Unit,choose:(Note)->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Side){Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=dismiss,modifier=Modifier.describedAs("返回资料库")){Glyph("back")};Text(if(review)"手动回忆"else"学习资料",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold)}
            Text("选择笔记范围，继续整理共享卡片与知识。加入学习不会复制原笔记。",fontSize=14.sp,color=Quiet)
            if(notes.isEmpty())Text("先在资料库新建笔记，或在笔记中摘录一张摘要卡。")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){items(notes,key={it.id}){n->Card(onClick={choose(n)},border=BorderStroke(1.dp,Line),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=Color.White),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){Text(n.title,fontSize=18.sp);Text(if(review)"查看问题与手工复习"else"摘要卡 · 大纲 · 思维导图",fontSize=12.sp,color=Quiet)}
            }}}
        }}
    }
}

@Composable internal fun WorkspaceSettings(dismiss:()->Unit,diagnostics:()->Unit){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    val beautyStore=remember{BeautyStore(context)}
    var favorites by remember{mutableStateOf(prefs.getBoolean("favorites-open",false))}
    var beauty by remember{mutableStateOf(beautyStore.read())}
    var reset by remember{mutableStateOf(false)}
    var backupOpen by remember{mutableStateOf(false)}
    var resourcesOpen by remember{mutableStateOf(false)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.padding(16.dp).widthIn(max=560.dp).fillMaxWidth().heightIn(max=680.dp).testTag("workspace-settings"),shape=RoundedCornerShape(24.dp),color=Side,border=BorderStroke(1.dp,Line)){
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("设置与数据",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold);IconButton(onClick=dismiss,modifier=Modifier.describedAs("关闭设置")){Glyph("close")}}
                SettingsGroup("外观","note","浅色工作台","跟随系统字号，保留文档原有纸面与颜色。")
                Text("书写",color=Quiet,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=12.dp))
                Surface(shape=RoundedCornerShape(14.dp),color=Color.White,border=BorderStroke(1.dp,Line)){Column(Modifier.padding(horizontal=16.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){Text("显示收藏笔盒",Modifier.weight(1f));Switch(favorites,{favorites=it;prefs.edit().putBoolean("favorites-open",it).apply()},modifier=Modifier.testTag("settings-favorite-pens").describedAs("显示收藏笔盒"))}
                    HorizontalDivider(color=Line)
                    Row(verticalAlignment=Alignment.CenterVertically){Text("自动美化",Modifier.weight(1f));Switch(beauty.enabled,{beauty=beauty.copy(enabled=it);beautyStore.save(beauty)},modifier=Modifier.testTag("settings-auto-beauty").describedAs("自动美化"))}
                    Text("停笔后美化新笔迹，字体在笔盒中选择。",color=Quiet,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={prefs.edit().remove("case-x").remove("case-y").remove("case-collapsed").remove("favorites-x").remove("favorites-y").remove("favorites-collapsed").apply();reset=true},modifier=Modifier.testTag("settings-reset-case")){Text(if(reset)"笔盒位置已重置"else"重置笔盒位置")}
                }}
                SettingsGroup("数据","folder","本地保存","完整备份与恢复位于资料库底部。导出副本可保留笔迹、图片与文档。")
                OutlinedButton(onClick={backupOpen=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("open-encrypted-backup")){Text("加密备份与恢复（实验）")}
                OutlinedButton(onClick={resourcesOpen=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("open-resource-packs")){Text("本地模板包")}
                Button(onClick=diagnostics,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Glyph("diagnostics");Spacer(Modifier.width(8.dp));Text("诊断与导出")}
            }
        }
    }
    if(backupOpen)BackupSettings{backupOpen=false}
    if(resourcesOpen)ResourcePackSettings{resourcesOpen=false}
}

@Composable private fun SettingsGroup(section:String,icon:String,title:String,detail:String){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(section,color=Quiet,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=12.dp))
        Surface(shape=RoundedCornerShape(14.dp),color=Color.White,border=BorderStroke(1.dp,Line)){
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Glyph(icon,Forest);Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.Medium);Text(detail,color=Quiet,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
