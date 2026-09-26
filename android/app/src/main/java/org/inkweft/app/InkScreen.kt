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
import org.inkweft.data.NotebookPageRow
import org.inkweft.data.WorkspaceRow

@Composable
internal fun InkPageScreen(note:NoteDraft,workspace:WorkspaceViewModel,page:NotebookPageRow,onCanNavigate:(Boolean)->Unit,onSearch:(Long)->Unit,externalEnabled:Boolean=true,onExcerpt:(SelectedInk)->Unit={},onAssociate:(SelectedInk)->Unit={},focusRegion:CanvasBounds?=null,onFocusConsumed:()->Unit={},pageNavigation:@Composable ()->Unit={},continuousPages:List<NotebookPageRow>?=null,onContinuousPage:(String)->Unit={},leaveContinuous:()->Unit={}){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val vm:InkViewModel=viewModel(key="ink-${page.id}",factory=InkViewModel.Factory(page.id,app.inkRepository))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val row=WorkspaceRow(page.id,page.world,page.paper,centerX=page.centerX,centerY=page.centerY,zoom=page.zoom)
    val scope=rememberCoroutineScope()
    var showPaperPicker by remember { mutableStateOf(false) }
    var tool by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    var finger by rememberSaveable(note.base.id){mutableStateOf(false)}
    val editorPrefs=remember(context){context.getSharedPreferences("inkweft-editor",android.content.Context.MODE_PRIVATE)}
    var dockBottom by rememberSaveable{mutableStateOf(editorPrefs.getBoolean("toolbar-bottom",false))}
    val penStore=remember(context){PenWidthStore(context,"inkweft-pen-widths-book-"+note.base.id)}
    var widths by remember(note.base.id){mutableStateOf(penStore.read())}
    var kinds by remember(note.base.id){mutableStateOf(penStore.readKinds())}
    var colors by remember(note.base.id){mutableStateOf(penStore.readColors())}
    var continuousBlocked by remember{mutableStateOf(false)}
    var gesture by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    var axes by remember{mutableStateOf("本次启动尚未检测笔输入")}
    var view by remember{mutableStateOf<InkCanvasView?>(null)}
    var zoom by remember{mutableDoubleStateOf(.7)}
    var settings by remember{mutableStateOf(false)}
    var more by remember{mutableStateOf(false)}
    val eraserStore=remember(context){EraserSettingsStore(context)}
    var eraser by remember{mutableStateOf(eraserStore.read())}
    var eraserDialog by remember{mutableStateOf(false)}
    var exportPending by remember{mutableStateOf<InkPageFile?>(null)}
    var confirmExport by remember{mutableStateOf(false)}
    var discard by remember{mutableStateOf(false)}
    var selected by remember(page.id){mutableStateOf<SelectedInk?>(null)}
    var pendingSelection by remember(page.id){mutableStateOf<Pair<InkRegion,List<String>>?>(null)}
    var freehand by remember{mutableStateOf(false)}
    val editable=externalEnabled&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!ui.processing
    LaunchedEffect(ui.revision,ui.queued){
        if(ui.queued==0){
            pendingSelection?.let{(region,ids)->val found=ui.strokes.filter{it.id in ids};if(found.size==ids.size){selected=SelectedInk(region,ui.revision,found);pendingSelection=null}}
            if(selected?.revision!=ui.revision)selected=null
        }
    }
    LaunchedEffect(tool){if(tool!=4){selected=null;view?.selectionPreview(emptySet())}}
    LaunchedEffect(focusRegion,view,ui.loading){if(focusRegion!=null&&!ui.loading){view?.post{view?.focusRegion(focusRegion);onFocusConsumed()}}}
    fun applySelected(revision:Long,change:InkMutation):Boolean{
        val ok=vm.selectedEdit(revision,change)
        if(ok&&change is InkMutation.Replace){
            val bounds=change.added.map{it.bounds()}.reduce{a,b->a.union(b)}.padded(2.0)
            pendingSelection=runCatching{InkRegion(listOf(
                EraserPoint(bounds.left.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.top.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD)),
                EraserPoint(bounds.right.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.bottom.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD)))) to change.added.map{it.id}}.getOrNull()
        };return ok
    }

    val diagnosticState=when{ui.readFailed->DiagnosticResult.READ_FAILED;ui.loading->DiagnosticResult.LOADING;ui.blocked==InkCommitResult.Unknown->DiagnosticResult.UNKNOWN;ui.blocked==InkCommitResult.Conflict->DiagnosticResult.CONFLICT;ui.blocked!=null->DiagnosticResult.REJECTED;gesture->DiagnosticResult.EDITING;ui.queued>0||ui.processing->DiagnosticResult.SAVING;else->DiagnosticResult.SAVED}
    SideEffect{app.diagnostics.ink(ui.loading,ui.readFailed,ui.strokes.size,ui.queued,ui.revision,gesture,diagnosticState)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val file=exportPending;exportPending=null
        if(uri!=null&&file!=null)scope.launch{
            val ok=try{withContext(Dispatchers.IO){val bytes=file.encode();checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false}
            Toast.makeText(context,if(ok)"页面副本已写入所选位置"else"导出失败；原笔迹仍保留",Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(continuousPages==null){if(continuousPages==null)continuousBlocked=false else if(tool==4)tool=0}
    val busy=gesture||ui.processing
    SideEffect{onCanNavigate(!busy&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!continuousBlocked)}
    LaunchedEffect(ui.message){if(ui.message!=null){notice=ui.message;vm.clearMessage()}}
    fun savePreset(selected:Int,width:Float,color:Int,kind:InkPen){
        kinds=kinds.mapIndexed{i,p->if(i==selected)kind else p}
        widths=widths.mapIndexed{i,w->if(i==selected)width else w}
        colors=colors.mapIndexed{i,c->if(i==selected)color else c}
        scope.launch{val saved=try{penStore.savePreset(selected,width,color,kind)}catch(c:CancellationException){throw c}catch(_:Exception){false}
            if(!saved)notice="常用笔已用于本次书写，但设置未保存；原笔迹未改动。"}
    }
    val toolbar:@Composable ()->Unit={
        BoxWithConstraints(Modifier.fillMaxWidth().background(Color.White)){
            val compact=maxWidth<600.dp
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                val visiblePens=if(compact)listOf(if(tool in 0..2)tool else 0)else listOf(0,1,2)
                visiblePens.forEach{i->Box{
                    IconButton(onClick={if(tool==i)settings=true else tool=i},enabled=!busy,modifier=Modifier.background(if(tool==i)Leaf else Color.Transparent,RoundedCornerShape(8.dp)).testTag("ink-tool-$i").describedAs(PenWidthStore.name(i,kinds[i]))){Glyph("pen",Color(colors[i] or 0xff000000.toInt()))}
                }}
                IconButton(onClick={if(tool==3)eraserDialog=true else tool=3},enabled=!busy,modifier=Modifier.testTag("ink-tool-3").describedAs(if(eraser.whole)"整笔橡皮"else"局部橡皮")){Glyph("eraser",if(tool==3)Forest else Quiet)}
                IconButton(onClick={if(continuousPages!=null)leaveContinuous();tool=4},enabled=!busy&&!continuousBlocked,modifier=Modifier.testTag("ink-select").describedAs("框选与套索")){Glyph("select",if(tool==4)Forest else Quiet)}
                IconButton(onClick=vm::undo,enabled=ui.canUndo&&!busy,modifier=Modifier.testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
                if(!compact)IconButton(onClick=vm::redo,enabled=ui.canRedo&&!busy,modifier=Modifier.testTag("ink-redo").describedAs("重做")){Glyph("redo")}
                Box{
                    IconButton(onClick={if(tool==3)eraserDialog=true else settings=true},enabled=!busy&&tool<4,modifier=Modifier.testTag("pen-width-open").describedAs("笔型、颜色与线宽")){Glyph("settings")}
                    if(tool<3)PenPresetMenu(settings,tool,widths[tool],colors[tool],kinds[tool],{settings=false}){width,color,kind->savePreset(tool,width,color,kind);settings=false}
                }
                if(!compact){Text(if(tool<3)PenWidthStore.name(tool,kinds[tool])+" · "+PenWidthStore.label(widths[tool])else if(tool==3)"橡皮"else"选择",fontSize=12.sp,color=Quiet);Spacer(Modifier.weight(1f))}
                Box{IconButton(onClick={more=true},enabled=!busy,modifier=Modifier.testTag("ink-more").describedAs("更多编辑选项")){Glyph("more")}
                    DropdownMenu(more,{more=false}){
                        if(compact){(0..2).forEach{i->DropdownMenuItem(text={Text(PenWidthStore.name(i,kinds[i]))},onClick={tool=i;more=false},modifier=Modifier.testTag("choose-pen-$i"))}
                            DropdownMenuItem(text={Text("重做")},onClick={vm.redo();more=false},enabled=ui.canRedo,modifier=Modifier.testTag("ink-redo"))}
                        DropdownMenuItem(text={Text("套索、圈选擦除与摘录")},onClick={more=false;if(continuousPages!=null)leaveContinuous();tool=4;freehand=true},enabled=!continuousBlocked,modifier=Modifier.testTag("selection-tools"))
                        DropdownMenuItem(text={Text(if(dockBottom)"工具栏移到顶部"else"工具栏移到底部")},onClick={more=false;dockBottom=!dockBottom;editorPrefs.edit().putBoolean("toolbar-bottom",dockBottom).apply()},modifier=Modifier.testTag("toolbar-position"))
                        if(continuousPages!=null)DropdownMenuItem(text={Text("连续模式：手指滚动，触控笔书写")},onClick={},enabled=false)else DropdownMenuItem(text={Text(if(finger)"关闭手指书写"else"开启手指书写")},onClick={finger=!finger;more=false},modifier=Modifier.testTag("ink-finger"))
                        DropdownMenuItem(text={Text("本页手写检索文字")},onClick={more=false;onSearch(ui.revision)},enabled=ui.queued==0&&ui.blocked==null&&!ui.loading)
                        DropdownMenuItem(text={Text("导出页面副本")},onClick={more=false;confirmExport=true},enabled=!ui.loading)
                        DropdownMenuItem(text={Text("设为默认笔盒")},onClick={more=false;scope.launch{try{val defaults=PenWidthStore(context);val ok=(0..2).map{defaults.savePreset(it,widths[it],colors[it],kinds[it])}.all{it};notice=if(ok)"已设为新笔记的默认笔盒"else"默认笔盒未完全保存，请重试。"}catch(c:CancellationException){throw c}catch(_:Exception){notice="默认笔盒未保存"}}})
                        DropdownMenuItem(text={Text("更换纸面…")},onClick={more=false;showPaperPicker=true},modifier=Modifier.testTag("change-paper"))
                        DropdownMenuItem(text={Text("查看输入与容量说明")},onClick={more=false;notice="$axes。最多 ${InkLimits.MAX_STROKES} 笔／${InkLimits.MAX_PAGE_POINTS} 个采样点；抬笔后提交，撤销历史暂不跨进程恢复。"})
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()){
        if(!dockBottom){toolbar();HorizontalDivider(color=Line)}
        val status=when{ui.readFailed->"无法读取笔迹，原数据不会被空页覆盖";ui.loading||row==null->"正在读取笔迹和视图…";ui.blocked==InkCommitResult.Unknown->"保存结果待核对，未确认笔迹保留";ui.blocked!=null->"版本冲突或容量限制，未确认笔迹保留";gesture->"本笔尚未保存，抬笔后提交";ui.processing->"正在计算整笔擦除…";ui.queued>0->"正在提交 ${ui.queued} 项操作…";!ui.canStart->"达到采样预算，请导出或新建笔记继续";else->"已提交 · ${ui.strokes.size} 笔 · 修订 ${ui.revision}"}
        if(ui.blocked!=null||ui.readFailed)Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=17.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status,Modifier.weight(1f),fontSize=11.sp,color=if(ui.blocked!=null||ui.readFailed)Color(0xff984c24)else Forest)
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!busy){Text("核对重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!busy){Text("读取已保存页")}
            if(ui.readFailed&&ui.blocked==null)TextButton(onClick=vm::load){Text("重试")}
        }
        if(tool==4)SelectionActions(selected,ui.strokes,editable,freehand,{freehand=it},::applySelected,{selected=null},onExcerpt,onAssociate)
        if(notice!=null)Row(Modifier.fillMaxWidth().background(Color(0xfffff5e5)).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(notice!!,Modifier.weight(1f),fontSize=12.sp);IconButton(onClick={notice=null},modifier=Modifier.describedAs("关闭提示")){Glyph("close")}}
        if(continuousPages!=null){
            Box(Modifier.fillMaxWidth().weight(1f)){ContinuousPages(continuousPages,page.id,
                ContinuousTools(kinds[tool.coerceIn(0,2)],colors[tool.coerceIn(0,2)],widths[tool.coerceIn(0,2)],tool==3,eraser.whole,eraser.onlyHighlighter,eraser.diameterDp,externalEnabled&&tool!=4),
                gesture,onContinuousPage,{gesture=it},{continuousBlocked=it},{notice=it},{id->onContinuousPage(id);leaveContinuous()})}
        }else if(row!=null){
            val initial=remember(page.id){workspace.cachedViewport(page.id)?:row.takeIf{it.zoom>0}?.let{runCatching{CanvasViewport(it.centerX,it.centerY,it.zoom)}.getOrNull()}}
            Box(Modifier.fillMaxWidth().weight(1f)){
            key(page.id){AndroidView(factory={ctx->InkCanvasView(ctx).also{v->
                view=v;v.onStroke=vm::accept;v.onErase={path,radius,whole,only->vm.erasePath(path,radius,whole,only)};v.onGesture={gesture=it}
                v.onNotice={notice=it;app.diagnostics.event(DiagnosticCode.INK_UI,DiagnosticResult.REJECTED)}
                v.onAxes={pressure,tilt->app.diagnostics.inputAxes(pressure,tilt);axes="本次输入：压力${if(pressure)"已上报"else"未上报"} · 倾斜${if(tilt)"已上报"else"未上报"}"}
                v.onViewport={workspace.viewport(page.id,it)};v.onScale={zoom=it}
            }},update={v->
                v.configure(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},initial)
                v.allowInput=externalEnabled&&tool!=4&&(if(tool==3)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart);v.eraserWhole=eraser.whole;v.eraserHighlighterOnly=eraser.onlyHighlighter;v.eraserDiameterDp=eraser.diameterDp;v.fingerWrites=finger;v.eraseMode=tool==3;v.pen=kinds[tool.coerceIn(0,2)]
                v.penWidth=widths[tool.coerceAtMost(2)];v.penColor=colors[tool.coerceAtMost(2)]
                v.showStrokes(ui.strokes)
            },modifier=Modifier.fillMaxSize().testTag("ink-surface"))
            if(tool==4)AndroidView(factory={SelectionOverlayView(it)},update={v->
                v.canvasView=view;v.region=selected?.region;v.selected=selected?.strokes.orEmpty();v.freehand=freehand;v.enabledInput=editable
                v.onActive={gesture=it};v.onRegion={region->selected=region?.let{SelectedInk(it,ui.revision,ui.strokes.filter{stroke->it.selects(stroke)})}}
                v.onShift={dx,dy->selected?.let{current->runCatching{InkSelectionEdit.copy(current.strokes,dx,dy)}.onSuccess{changed->if(applySelected(current.revision,InkMutation.Replace(current.strokes.map{it.id},changed)))selected=null}.onFailure{notice="移动超出画布或编辑预算，原笔迹保留。"}}}
                v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("selection-overlay"))
            }}
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        if(dockBottom){HorizontalDivider(color=Line);toolbar()}
        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            pageNavigation()
            Text(status,fontSize=12.sp,color=Forest,modifier=Modifier.testTag("ink-status"))
            if(continuousPages!=null)TextButton(onClick=leaveContinuous,enabled=!busy&&!continuousBlocked){Text("单页缩放")}else{
            Spacer(Modifier.width(10.dp));TextButton(onClick={view?.zoomBy(1/1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-out")){Text("−")}
            Text("${(zoom*100).toInt()}%",fontSize=11.sp,color=Quiet,modifier=Modifier.testTag("ink-zoom"))
            TextButton(onClick={view?.zoomBy(1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-in")){Text("＋")}
            if(row?.world==true){TextButton(onClick={view?.fitContent()},enabled=!busy,modifier=Modifier.testTag("fit-content")){Text("全部内容",fontSize=12.sp)};TextButton(onClick={view?.origin()},enabled=!busy,modifier=Modifier.testTag("view-origin")){Text("回到原点",fontSize=12.sp)}}
            else{TextButton(onClick={view?.fitPage()},enabled=!busy,modifier=Modifier.testTag("fit-page")){Text("适页",fontSize=12.sp)};TextButton(onClick={view?.fitWidth()},enabled=!busy,modifier=Modifier.testTag("fit-width")){Text("适宽",fontSize=12.sp)}}
            }
        }
    }
    if(eraserDialog)EraserDialog(eraser,{eraserDialog=false}){next->eraser=next;eraserDialog=false;scope.launch{val saved=try{eraserStore.save(next)}catch(c:CancellationException){throw c}catch(_:Exception){false};if(!saved)notice="本次橡皮已应用，但设置未保存。"}}
    if(showPaperPicker)PaperPickerDialog(PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},row.world,{showPaperPicker=false}){style->workspace.paper(row.noteId,style);showPaperPicker=false}
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED});launcher.launch("墨织页面.iwpage")}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
