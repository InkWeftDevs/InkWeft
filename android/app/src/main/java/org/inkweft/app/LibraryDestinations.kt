// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.inkweft.core.*

@Composable internal fun LearningLibrary(notes:List<Note>,review:Boolean,dismiss:()->Unit,choose:(Note)->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row{Text(if(review)"手动回忆"else"学习资料",Modifier.weight(1f),fontSize=24.sp);TextButton(onClick=dismiss){Text("返回资料库")}}
            Text("选择笔记范围，继续整理共享卡片与知识。加入学习不会复制原笔记。",fontSize=14.sp,color=Quiet)
            if(notes.isEmpty())Text("先在资料库新建笔记，或在笔记中摘录一张摘要卡。")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){items(notes,key={it.id}){n->OutlinedCard(onClick={choose(n)},modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){Text(n.title,fontSize=18.sp);Text(if(review)"查看问题与手工复习"else"摘要卡 · 大纲 · 思维导图",fontSize=12.sp,color=Quiet)}
            }}}
        }}
    }
}

@Composable internal fun WorkspaceSettings(dismiss:()->Unit,diagnostics:()->Unit){
    AlertDialog(onDismissRequest=dismiss,title={Text("设置与数据")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("外观",fontSize=18.sp);Text("统一浅色界面；文档纸面与笔迹颜色独立。系统字号与显示大小可在系统设置中调整。")
        Text("书写",fontSize=18.sp);Text("在文档工具栏设置笔盒、橡皮和手指书写。参数只影响之后的笔迹。")
        Text("数据与扩展",fontSize=18.sp);Text("完整备份与恢复在资料库底部。当前支持内置声明式模板；代码插件、自动识别和云服务尚未启用。")
        Text("图标：Google Material Symbols，Apache 2.0。",fontSize=12.sp,color=Quiet)
        OutlinedButton(onClick=diagnostics){Text("诊断与导出")}
    }},confirmButton={TextButton(onClick=dismiss){Text("关闭")}})
}
