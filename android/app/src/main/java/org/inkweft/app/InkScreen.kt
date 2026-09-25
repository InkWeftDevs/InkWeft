// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*

@Composable
fun InkScreen(note:NoteDraft,workspace:WorkspaceViewModel=viewModel()){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val vm:InkViewModel=viewModel(key="ink-${note.base.id}",factory=InkViewModel.Factory(note.base.id,app.inkRepository))
    val ui by vm.ui.collectAsStateWithLifecycle();val entries by workspace.entries.collectAsStateWithLifecycle()
    val row=entries[note.base.id]
    LaunchedEffect(note.base.id){workspace.ensure(note.base.id)}
    val scope=rememberCoroutineScope()
    var tool by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    var finger by rememberSaveable(note.base.id){mutableStateOf(false)}
    var dockBottom by rememberSaveable(note.base.id){mutableStateOf(false)}
    var widths by remember{mutableStateOf(listOf(3f,3f,22f))}
    var gesture by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    var axes by remember{mutableStateOf("本次启动尚未检测笔输入")}
    var view by remember{mutableStateOf<InkCanvasView?>(null)}
    var zoom by remember{mutableDoubleStateOf(.7)}
    var settings by remember{mutableStateOf(false)}
    var more by remember{mutableStateOf(false)}
    var exportPending by remember{mutableStateOf<InkPageFile?>(null)}
    var confirmExport by remember{mutableStateOf(false)}
    var discard by remember{mutableStateOf(false)}
    val diagnosticState=when{ui.readFailed->DiagnosticResult.READ_FAILED;ui.loading->DiagnosticResult.LOADING;ui.blocked==InkCommitResult.Unknown->DiagnosticResult.UNKNOWN;ui.blocked==InkCommitResult.Conflict->DiagnosticResult.CONFLICT;ui.blocked!=null->DiagnosticResult.REJECTED;gesture->DiagnosticResult.EDITING;ui.queued>0||ui.processing->DiagnosticResult.SAVING;else->DiagnosticResult.SAVED}
    SideEffect{app.diagnostics.ink(ui.loading,ui.readFailed,ui.strokes.size,ui.queued,ui.revision,gesture,diagnosticState)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val file=exportPending;exportPending=null
        if(uri!=null&&file!=null)scope.launch{
            val ok=try{withContext(Dispatchers.IO){val bytes=file.encode();checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false}
            Toast.makeText(context,if(ok)"页面副本已写入所选位置"else"导出失败；原笔迹仍保留",Toast.LENGTH_LONG).show()
        }
    }
    val busy=gesture||ui.processing
    val toolbar:@Composable ()->Unit={
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
            listOf("黑色笔","红色笔","荧光笔").forEachIndexed{i,label->
                val color=when(i){1->Color(0xffb83239);2->Color(0xffd7ab20);else->TextInk}
                Surface(onClick={if(tool==i)settings=true else tool=i},enabled=!busy,color=if(tool==i)Leaf else Color.White,shape=RoundedCornerShape(9.dp),border=BorderStroke(1.dp,if(tool==i)Color(0xff95b7a5)else Line),modifier=Modifier.heightIn(min=48.dp).testTag("ink-tool-$i")){
                    Row(Modifier.padding(horizontal=11.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Glyph("pen",color);Column{Text(label,fontSize=12.sp);Text("${widths[i]} pt",fontSize=9.sp,color=Quiet)}
                    }
                }
            }
            VerticalDivider(Modifier.height(28.dp),color=Line)
            FilterChip(selected=tool==3,onClick={tool=3},enabled=!busy,label={Text("整笔橡皮",fontSize=12.sp)},leadingIcon={Glyph("eraser")},modifier=Modifier.testTag("ink-tool-3"))
            IconButton(onClick=vm::undo,enabled=ui.canUndo&&!busy,modifier=Modifier.testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
            IconButton(onClick=vm::redo,enabled=ui.canRedo&&!busy,modifier=Modifier.testTag("ink-redo").describedAs("重做")){Glyph("redo")}
            VerticalDivider(Modifier.height(28.dp),color=Line)
            FilterChip(selected=finger,onClick={finger=!finger},enabled=!busy,label={Text("手指书写",fontSize=12.sp)},modifier=Modifier.testTag("ink-finger"))
            Box{IconButton(onClick={more=true},enabled=!busy,modifier=Modifier.testTag("ink-more").describedAs("更多编辑选项")){Glyph("more")};DropdownMenu(expanded=more,onDismissRequest={more=false}){
                DropdownMenuItem(text={Text("导出页面副本")},onClick={more=false;confirmExport=true},enabled=!ui.loading&&row!=null)
                DropdownMenuItem(text={Text(if(dockBottom)"笔盒放到顶部"else"笔盒放到底部")},onClick={dockBottom=!dockBottom;more=false})
                PaperStyle.entries.forEach{style->DropdownMenuItem(text={Text("纸面 · "+when(style){PaperStyle.BLANK->"空白";PaperStyle.RULED->"横线";PaperStyle.GRID->"方格";PaperStyle.DOTS->"点阵"})},onClick={workspace.paper(note.base.id,style);more=false})}
                DropdownMenuItem(text={Text("查看输入与容量说明")},onClick={more=false;notice="$axes。最多 ${InkLimits.MAX_STROKES} 笔／${InkLimits.MAX_PAGE_POINTS} 个采样点；正在书写的一笔抬笔后才提交。撤销历史暂不跨进程恢复。"})
            }}
        }
    }
    Column(Modifier.fillMaxSize()){
        if(!dockBottom){toolbar();HorizontalDivider(color=Line)}
        val status=when{ui.readFailed->"无法读取笔迹，原数据不会被空页覆盖";ui.loading||row==null->"正在读取笔迹和视图…";ui.blocked==InkCommitResult.Unknown->"保存结果待核对，未确认笔迹保留";ui.blocked!=null->"版本冲突或容量限制，未确认笔迹保留";gesture->"本笔尚未保存，抬笔后提交";ui.processing->"正在计算整笔擦除…";ui.queued>0->"正在提交 ${ui.queued} 项操作…";!ui.canStart->"达到采样预算，请导出或新建笔记继续";else->"已提交 · ${ui.strokes.size} 笔 · 修订 ${ui.revision}"}
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=17.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status,Modifier.weight(1f).testTag("ink-status"),fontSize=11.sp,color=if(ui.blocked!=null||ui.readFailed)Color(0xff984c24)else Forest)
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!busy){Text("核对重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!busy){Text("读取已保存页")}
            if(ui.readFailed&&ui.blocked==null)TextButton(onClick=vm::load){Text("重试")}
        }
        if(notice!=null)Row(Modifier.fillMaxWidth().background(Color(0xfffff5e5)).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(notice!!,Modifier.weight(1f),fontSize=12.sp);IconButton(onClick={notice=null},modifier=Modifier.describedAs("关闭提示")){Glyph("close")}}
        if(row!=null){
            val initial=remember(note.base.id){workspace.cachedViewport(note.base.id)?:row.takeIf{it.zoom>0}?.let{runCatching{CanvasViewport(it.centerX,it.centerY,it.zoom)}.getOrNull()}}
            AndroidView(factory={ctx->InkCanvasView(ctx).also{v->
                view=v;v.onStroke=vm::accept;v.onErase=vm::erasePath;v.onGesture={gesture=it}
                v.onNotice={notice=it;app.diagnostics.event(DiagnosticCode.INK_UI,DiagnosticResult.REJECTED)}
                v.onAxes={pressure,tilt->app.diagnostics.inputAxes(pressure,tilt);axes="本次输入：压力${if(pressure)"已上报"else"未上报"} · 倾斜${if(tilt)"已上报"else"未上报"}"}
                v.onViewport={workspace.viewport(note.base.id,it)};v.onScale={zoom=it}
            }},update={v->
                v.configure(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},initial)
                v.allowInput=ui.canStart;v.fingerWrites=finger;v.eraseMode=tool==3;v.pen=if(tool==2)InkPen.HIGHLIGHTER else InkPen.PEN
                v.penWidth=widths[tool.coerceAtMost(2)];v.penColor=when(tool){1->0xffb83239.toInt();2->0x66efc63a;else->0xff24342f.toInt()}
                v.showStrokes(ui.strokes)
            },modifier=Modifier.fillMaxWidth().weight(1f).testTag("ink-surface"))
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        if(dockBottom){HorizontalDivider(color=Line);toolbar()}
        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Glyph(if(row?.world==true)"board"else"note",Quiet,Modifier.size(16.dp));Text(if(row?.world==true)"无界画布"else"纸张笔记",fontSize=11.sp,color=Quiet)
            Spacer(Modifier.width(10.dp));TextButton(onClick={view?.zoomBy(1/1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-out")){Text("−")}
            Text("${(zoom*100).toInt()}%",fontSize=11.sp,color=Quiet,modifier=Modifier.testTag("ink-zoom"))
            TextButton(onClick={view?.zoomBy(1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-in")){Text("＋")}
            if(row?.world==true){TextButton(onClick={view?.fitContent()},enabled=!busy,modifier=Modifier.testTag("fit-content")){Text("全部内容",fontSize=12.sp)};TextButton(onClick={view?.origin()},enabled=!busy,modifier=Modifier.testTag("view-origin")){Text("回到原点",fontSize=12.sp)}}
            else{TextButton(onClick={view?.fitPage()},enabled=!busy,modifier=Modifier.testTag("fit-page")){Text("适页",fontSize=12.sp)};TextButton(onClick={view?.fitWidth()},enabled=!busy,modifier=Modifier.testTag("fit-width")){Text("适宽",fontSize=12.sp)}}
        }
    }
    if(settings && tool<3)AlertDialog(onDismissRequest={settings=false},title={Text("${listOf("黑色笔","红色笔","荧光笔")[tool]} · 粗细")},text={Column{
        Text("${widths[tool]} pt · 只影响之后的笔迹",fontSize=13.sp,color=Quiet)
        Slider(value=widths[tool],onValueChange={value->widths=widths.mapIndexed{i,w->if(i==tool)value else w}},valueRange=if(tool==2)6f..40f else .5f..12f)
        Text("当前三支笔分别保留本次工作区内的粗细。设备能力及持久保存状态见诊断。",fontSize=12.sp,color=Quiet)
    }},confirmButton={TextButton(onClick={settings=false}){Text("完成")}})
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED});launcher.launch("墨织页面.iwpage")}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
