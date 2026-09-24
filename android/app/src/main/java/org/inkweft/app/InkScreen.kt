// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.inkweft.core.*

@Composable
fun InkScreen(note: NoteDraft) {
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val vm:InkViewModel=viewModel(key="ink-${note.base.id}",factory=InkViewModel.Factory(note.base.id,app.inkRepository))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var tool by remember { mutableIntStateOf(0) }
    var finger by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var axes by remember { mutableStateOf("设备笔轴：等待实际输入") }
    var view by remember { mutableStateOf<InkCanvasView?>(null) }
    var exportPending by remember { mutableStateOf<InkPageFile?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file=exportPending;exportPending=null
        if(uri!=null && file!=null)scope.launch {
            val ok=runCatching { withContext(Dispatchers.IO) {
                val bytes=file.encode()
                checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) }
            } }.isSuccess
            Toast.makeText(context,if(ok) "页面副本已写入所选位置" else "导出失败；笔迹没有删除",Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("黑色笔","红色笔","荧光笔","整笔橡皮").forEachIndexed { i,label ->
                FilterChip(selected=tool==i,onClick={tool=i},enabled=!gesture,label={Text(label)},modifier=Modifier.testTag("ink-tool-$i"))
            }
            TextButton(onClick=vm::undo,enabled=ui.canUndo && !gesture,modifier=Modifier.testTag("ink-undo")){Text("撤销")}
            TextButton(onClick=vm::redo,enabled=ui.canRedo && !gesture,modifier=Modifier.testTag("ink-redo")){Text("重做")}
            TextButton(onClick={view?.fitPage()},enabled=!gesture){Text("适合页面")}
            FilterChip(selected=finger,onClick={finger=!finger},enabled=!gesture,label={Text("手指书写")},modifier=Modifier.testTag("ink-finger"))
            TextButton(onClick={confirmExport=true},enabled=!gesture && !ui.loading){Text("导出页面副本")}
        }
        val status=when {
            ui.readFailed -> "无法读取笔迹。不会用空页覆盖原数据。"
            ui.loading -> "正在读取笔迹…"
            ui.blocked==InkCommitResult.Unknown -> "保存结果待核对；未确认笔迹仍保留，先重试原命令。"
            ui.blocked!=null -> "本页出现版本冲突或达到容量限制。未确认笔迹已保留，可先导出副本。"
            gesture -> "正在书写的这一笔尚未保存；抬笔后自动提交。"
            ui.queued>0 -> "正在保存 ${ui.queued} 项笔迹操作…"
            !ui.canStart -> "达到本实验单页容量预算，请导出或新建笔记继续。"
            else -> "已提交至本机数据库 · ${ui.strokes.size} 笔 · 第 ${ui.revision} 次修改"
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(status,modifier=Modifier.weight(1f).testTag("ink-status"),fontSize=12.sp,
                color=if(ui.blocked!=null || ui.readFailed)Color(0xff8a451e) else Color(0xff315b4b))
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!gesture){Text("核对并重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!gesture){Text("读取已保存页")}
            if(ui.readFailed && ui.blocked==null)TextButton(onClick=vm::load){Text("重新读取")}
        }
        if(notice!=null)TextButton(onClick={notice=null},modifier=Modifier.fillMaxWidth()) { Text(notice!!,fontSize=12.sp) }
        if(ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(factory={ctx->InkCanvasView(ctx).also { v ->
            view=v;v.onStroke=vm::accept;v.onErase=vm::erase
            v.onGesture={gesture=it};v.onNotice={notice=it}
            v.onAxes={pressure,tilt->axes="本次输入：压力${if(pressure)"已上报" else "未上报"} · 倾斜${if(tilt)"已上报" else "未上报"}"}
        }},update={v->
            v.allowInput=ui.canStart;v.fingerWrites=finger;v.eraseMode=tool==3
            v.pen=if(tool==2)InkPen.HIGHLIGHTER else InkPen.PEN
            v.penWidth=if(tool==2)22f else 3f
            v.penColor=when(tool){1->0xffb83239.toInt();2->0x66efc63a;else->0xff24342f.toInt()}
            v.showStrokes(ui.strokes)
        },modifier=Modifier.fillMaxWidth().weight(1f).testTag("ink-surface"))
        Text("单页手写实验 · 双指缩放，未开启手指书写时单指移动 · $axes",
            Modifier.fillMaxWidth().background(Color.White).padding(10.dp),fontSize=11.sp,color=Color(0xff52685c))
    }
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},
        text={Text("包含本页可见笔迹和当前文字（包括会话内未确认内容），为明文 .iwpage 文件。它不是整库备份，不包含隐藏笔迹、撤销历史和回执。所选位置可能属于云盘提供方。")},
        confirmButton={TextButton(onClick={
            confirmExport=false
            exportPending=InkPageFile(note.title.ifBlank { "手写页" },note.text,ui.strokes)
            launcher.launch("墨织页面.iwpage")
        }){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},
        text={Text("这会重新读取数据库中的本页。建议先导出页面副本；不会删除数据库里已保存的笔迹。")},
        confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},
        dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
