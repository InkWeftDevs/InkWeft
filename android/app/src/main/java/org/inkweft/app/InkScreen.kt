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
    val objectsVm:PageObjectViewModel=viewModel(key="objects-${page.id}",factory=PageObjectViewModel.Factory(page.id,app.pageObjects))
    val objectsUi by objectsVm.ui.collectAsStateWithLifecycle()
    val suppressed=remember(objectsUi.objects){objectsUi.objects.flatMap{it.sourceStrokeIds}.toSet()}
    SideEffect{vm.suppressedIds=suppressed}
    val selectable=ui.strokes.filterNot{it.id in suppressed}
    var fontSelection by remember(page.id){mutableStateOf<SelectedInk?>(null)}
    var smoothSelection by remember(page.id){mutableStateOf<SelectedInk?>(null)}
    var beautyFont by remember{mutableStateOf(true)}
    var selectedObject by remember(page.id){mutableStateOf<String?>(null)}
    var objectInteraction by remember{mutableStateOf(false)}
    val objectsBlocked=objectsUi.loading||objectsUi.busy||objectsUi.pending||objectInteraction||fontSelection!=null||smoothSelection!=null
    SideEffect{app.diagnostics.pageObjects(objectsUi.loading,objectsUi.busy,objectsUi.pending,objectsUi.objects.size,when{objectsUi.loading->DiagnosticResult.LOADING;objectsUi.busy->DiagnosticResult.SAVING;objectsUi.pending->DiagnosticResult.UNKNOWN;objectsUi.error!=null->DiagnosticResult.REJECTED;else->DiagnosticResult.SAVED})}
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
    LaunchedEffect(tool){if(tool!=4&&tool!=6){selected=null;view?.selectionPreview(emptySet())}}
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
    LaunchedEffect(continuousPages==null){if(continuousPages==null)continuousBlocked=false else if(tool>=4)tool=0}
    val busy=gesture||ui.processing||objectsBlocked
    SideEffect{onCanNavigate(!busy&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!continuousBlocked)}
    LaunchedEffect(ui.message){if(ui.message!=null){notice=ui.message;vm.clearMessage()}}
    fun savePreset(selected:Int,width:Float,color:Int,kind:InkPen){
        kinds=kinds.mapIndexed{i,p->if(i==selected)kind else p}
        widths=widths.mapIndexed{i,w->if(i==selected)width else w}
        colors=colors.mapIndexed{i,c->if(i==selected)color else c}
        scope.launch{val saved=try{penStore.savePreset(selected,width,color,kind)}catch(c:CancellationException){throw c}catch(_:Exception){false}
            if(!saved)notice="常用笔已用于本次书写，但设置未保存；原笔迹未改动。"}
    }
    fun beautify(selection:SelectedInk){
        val writing=selection.strokes.filterNot{it.pen==InkPen.HIGHLIGHTER}
        if(writing.isEmpty()){notice="请框选手写文字；荧光标注和 PDF 原文不能美化。";return}
        if(writing.size>256){notice="这段笔迹较多，请缩小到一两行再试。";return}
        val target=selection.copy(strokes=writing)
        if(beautyFont)fontSelection=target else if(writing.any{it.cuts.isNotEmpty()})notice="已局部擦除的笔迹暂不能润色，可以选择换字体。"else smoothSelection=target
    }
    fun enterBeauty(){
        if(continuousPages!=null)leaveContinuous()
        tool=6
        if(selected?.strokes?.isNotEmpty()==true)beautify(selected!!)else{selected=null;freehand=false}
    }
    fun wholePageBeauty(){
        val strokes=selectable.filterNot{it.pen==InkPen.HIGHLIGHTER}
        if(strokes.isEmpty()){notice="先写一段字，再来试试美化。";return}
        val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}.padded(2.0)
        val region=InkRegion(listOf(EraserPoint(bounds.left.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.top.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD)),EraserPoint(bounds.right.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.bottom.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD))))
        beautify(SelectedInk(region,ui.revision,strokes))
    }
    val toolbar:@Composable ()->Unit={
        Column(Modifier.fillMaxWidth().background(Color.White)){
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal=8.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                EditorTool("书写","pen",tool in 0..1,!busy,"ink-tool-0",Modifier.weight(1f).fillMaxHeight()){if(tool in 0..1)settings=true else tool=0}
                EditorTool("荧光笔","pen",tool==2,!busy,"ink-tool-2",Modifier.weight(1f).fillMaxHeight()){if(tool==2)settings=true else tool=2}
                EditorTool("橡皮","eraser",tool==3,!busy,"ink-tool-3",Modifier.weight(1f).fillMaxHeight()){if(tool==3)eraserDialog=true else tool=3}
                EditorTool("套索","select",tool==4,!busy&&!continuousBlocked,"ink-select",Modifier.weight(1f).fillMaxHeight()){if(continuousPages!=null)leaveContinuous();tool=4}
                EditorTool("美化","beauty",tool==6,!busy&&!continuousBlocked,"ink-beauty",Modifier.weight(1f).fillMaxHeight()){enterBeauty()}
                EditorTool("插入","add",tool==5,!busy&&!continuousBlocked,"page-objects",Modifier.weight(1f).fillMaxHeight()){if(continuousPages!=null)leaveContinuous();tool=5}
                Box{
                    EditorTool("设置","settings",more,!busy,"ink-more",Modifier.width(48.dp).fillMaxHeight()){more=true}
                    DropdownMenu(more,{more=false}){
                        (0..2).forEach{i->DropdownMenuItem(text={Text("常用笔 ${i+1} · "+PenWidthStore.name(i,kinds[i]))},onClick={tool=i;more=false},modifier=Modifier.testTag("choose-pen-$i"))}
                        DropdownMenuItem(text={Text("圈选擦除与摘录")},onClick={more=false;if(continuousPages!=null)leaveContinuous();tool=4;freehand=true},enabled=!continuousBlocked,modifier=Modifier.testTag("selection-tools"))
                        DropdownMenuItem(text={Text(if(dockBottom)"工具栏放在顶部"else"工具栏放在底部")},onClick={more=false;dockBottom=!dockBottom;editorPrefs.edit().putBoolean("toolbar-bottom",dockBottom).apply()},modifier=Modifier.testTag("toolbar-position"))
                        if(continuousPages!=null)DropdownMenuItem(text={Text("手指滚动，触控笔书写")},onClick={},enabled=false)else DropdownMenuItem(text={Text(if(finger)"关闭手指书写"else"开启手指书写")},onClick={finger=!finger;more=false},modifier=Modifier.testTag("ink-finger"))
                        DropdownMenuItem(text={Text("导出当前页")},onClick={more=false;confirmExport=true},enabled=!ui.loading)
                        DropdownMenuItem(text={Text("设为默认笔盒")},onClick={more=false;scope.launch{try{val defaults=PenWidthStore(context);val ok=(0..2).map{defaults.savePreset(it,widths[it],colors[it],kinds[it])}.all{it};notice=if(ok)"已设为新笔记的默认笔盒"else"默认笔盒未完全保存，请重试。"}catch(c:CancellationException){throw c}catch(_:Exception){notice="默认笔盒未保存"}}})
                        DropdownMenuItem(text={Text("更换纸张模板")},onClick={more=false;showPaperPicker=true},modifier=Modifier.testTag("change-paper"))
                        DropdownMenuItem(text={Text("输入与容量说明")},onClick={more=false;notice="$axes。最多 ${InkLimits.MAX_STROKES} 笔／${InkLimits.MAX_PAGE_POINTS} 个采样点。"})
                    }
                }
            }
            HorizontalDivider(color=Line)
            Box(Modifier.fillMaxWidth().height(56.dp).background(Side).testTag("editor-options")){
                if(tool<3)Column{
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(tool!=2){FilterChip(tool==0,{tool=0},label={Text("常用笔 1")});FilterChip(tool==1,{tool=1},label={Text("常用笔 2")},modifier=Modifier.testTag("ink-tool-1"))}
                        Box{TextButton(onClick={settings=true},enabled=!busy,modifier=Modifier.testTag("pen-width-open")){Text(PenWidthStore.name(tool,kinds[tool])+" · "+PenWidthStore.label(widths[tool]));Spacer(Modifier.width(6.dp));Glyph("settings")}
                            PenPresetMenu(settings,tool,widths[tool],colors[tool],kinds[tool],{settings=false}){width,color,kind->savePreset(tool,width,color,kind);settings=false}}
                        PenWidthStore.colors(tool).forEachIndexed{i,color->
                            val name=listOf("墨黑","朱红","靛蓝","深绿","金黄")[i]
                            IconToggleButton(colors[tool]==color,{savePreset(tool,widths[tool],color,kinds[tool])},enabled=!busy,modifier=Modifier.size(48.dp).testTag("quick-color-$i").describedAs(name)){
                                Box(Modifier.size(if(colors[tool]==color)28.dp else 22.dp).background(Color(color),androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center){if(colors[tool]==color)Glyph("check",Color.White,Modifier.size(16.dp))}
                            }
                        }
                        PenWidthStore.presets(tool).forEachIndexed{i,width->FilterChip(widths[tool]==width,{savePreset(tool,width,colors[tool],kinds[tool])},label={Text(listOf("细","中","粗")[i])},enabled=!busy,modifier=Modifier.testTag("quick-width-$i"))}
                    }
                }
                if(tool==3)Column(Modifier.padding(horizontal=12.dp)){
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(!eraser.whole,{eraser=eraser.copy(whole=false);scope.launch{val saved=try{eraserStore.save(eraser)}catch(c:CancellationException){throw c}catch(_:Exception){false};if(!saved)notice="本次橡皮已应用，但设置未保存。"}},label={Text("局部擦除")})
                        FilterChip(eraser.whole,{eraser=eraser.copy(whole=true);scope.launch{val saved=try{eraserStore.save(eraser)}catch(c:CancellationException){throw c}catch(_:Exception){false};if(!saved)notice="本次橡皮已应用，但设置未保存。"}},label={Text("整笔擦除")})
                        TextButton(onClick={if(continuousPages!=null)leaveContinuous();tool=4;freehand=true}){Text("圈选擦除")}
                        TextButton(onClick={eraserDialog=true}){Text("大小与选项")}
                    }
                }
                if(tool==4)SelectionActions(selected,selectable,editable&&!objectsBlocked,freehand,{freehand=it},::applySelected,{selected=null},onExcerpt,onAssociate,{fontSelection=it})
                if(tool==6)Column(Modifier.padding(horizontal=12.dp)){
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(beautyFont,{beautyFont=true},label={Text("换字体")},modifier=Modifier.testTag("beauty-mode-font"))
                        FilterChip(!beautyFont,{beautyFont=false},label={Text("笔形润色")},modifier=Modifier.testTag("beauty-mode-smooth"))
                        TextButton(onClick={freehand=!freehand}){Text(if(freehand)"自由圈选"else"矩形框选")}
                        TextButton(onClick=::wholePageBeauty,enabled=editable&&!objectsBlocked&&selectable.isNotEmpty(),modifier=Modifier.testTag("beauty-whole-page")){Text("整页")}
                        Text("框住手写即可预览",style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(top=16.dp).testTag("beauty-guide"))
                    }
                }
                PageObjectTools(objectsVm,objectsUi,page.id,page.world,tool==5,editable&&!gesture,selectedObject,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it})
            }
        }
    }
    Column(Modifier.fillMaxSize()){
        if(!dockBottom){toolbar();HorizontalDivider(color=Line)}
        val status=when{ui.readFailed->"无法读取笔迹，原数据不会被空页覆盖";ui.loading||row==null->"正在读取笔迹和视图…";ui.blocked==InkCommitResult.Unknown->"保存结果待核对，未确认笔迹保留";ui.blocked!=null->"版本冲突或容量限制，未确认笔迹保留";gesture->"本笔尚未保存，抬笔后提交";ui.processing->"正在计算整笔擦除…";ui.queued>0->"正在提交 ${ui.queued} 项操作…";!ui.canStart->"达到采样预算，请导出或新建笔记继续";else->"已保存 · ${ui.strokes.size} 笔"}
        if(ui.blocked!=null||ui.readFailed)Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=17.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status,Modifier.weight(1f),fontSize=11.sp,color=if(ui.blocked!=null||ui.readFailed)Color(0xff984c24)else Forest)
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!busy){Text("核对重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!busy){Text("读取已保存页")}
            if(ui.readFailed&&ui.blocked==null)TextButton(onClick=vm::load){Text("重试")}
        }
        if(notice!=null)Row(Modifier.fillMaxWidth().background(Color(0xfffff5e5)).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(notice!!,Modifier.weight(1f),fontSize=12.sp);IconButton(onClick={notice=null},modifier=Modifier.describedAs("关闭提示")){Glyph("close")}}
        if(continuousPages!=null){
            Box(Modifier.fillMaxWidth().weight(1f)){ContinuousPages(continuousPages,page.id,
                ContinuousTools(kinds[tool.coerceIn(0,2)],colors[tool.coerceIn(0,2)],widths[tool.coerceIn(0,2)],tool==3,eraser.whole,eraser.onlyHighlighter,eraser.diameterDp,externalEnabled&&tool<4&&!objectsBlocked),
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
                v.allowInput=externalEnabled&&tool<4&&!objectsBlocked&&(if(tool==3)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart);v.eraserWhole=eraser.whole;v.eraserHighlighterOnly=eraser.onlyHighlighter;v.eraserDiameterDp=eraser.diameterDp;v.fingerWrites=finger;v.eraseMode=tool==3;v.pen=kinds[tool.coerceIn(0,2)]
                v.penWidth=widths[tool.coerceAtMost(2)];v.penColor=colors[tool.coerceAtMost(2)]
                v.showDocument(page.id);v.showStrokes(ui.strokes);v.showObjects(objectsUi.objects)
            },modifier=Modifier.fillMaxSize().testTag("ink-surface"))
            if(tool==5)AndroidView(factory={PageObjectOverlay(it)},update={v->
                v.canvasView=view;v.objects=objectsUi.objects;v.selected=selectedObject;v.world=page.world
                v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending&&!objectInteraction
                v.onSelect={selectedObject=it};v.onChange=objectsVm::put;v.onActive={gesture=it};v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("object-overlay"))
            if(tool==4||tool==6)AndroidView(factory={SelectionOverlayView(it)},update={v->
                v.canvasView=view;v.region=selected?.region;v.selected=selected?.strokes.orEmpty();v.freehand=freehand;v.enabledInput=editable
                v.onActive={gesture=it};v.onRegion={region->selected=region?.let{SelectedInk(it,ui.revision,selectable.filter{stroke->it.selects(stroke)})};if(tool==6)selected?.let{beautify(it)}}
                v.onShift={dx,dy->selected?.let{current->runCatching{InkSelectionEdit.copy(current.strokes,dx,dy)}.onSuccess{changed->if(applySelected(current.revision,InkMutation.Replace(current.strokes.map{it.id},changed)))selected=null}.onFailure{notice="移动超出画布或编辑预算，原笔迹保留。"}}}
                v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("selection-overlay"))
            }}
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        if(dockBottom){HorizontalDivider(color=Line);toolbar()}
        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            pageNavigation()
            IconButton(onClick={if(tool==5)objectsVm.undo()else vm.undo()},enabled=(if(tool==5)objectsUi.undo else ui.canUndo)&&!busy,modifier=Modifier.testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
            IconButton(onClick={if(tool==5)objectsVm.redo()else vm.redo()},enabled=(if(tool==5)objectsUi.redo else ui.canRedo)&&!busy,modifier=Modifier.testTag("ink-redo").describedAs("重做")){Glyph("redo")}
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
    fontSelection?.let{selection->FontBeautyDialog(selection,page.world,{fontSelection=null;selected=null}){o->
        objectsVm.change(objectsUi.objects+o,expectedInk=selection.revision);selectedObject=o.id;selected=null;fontSelection=null;tool=5
    }}
    smoothSelection?.let{s->BeautifyDialog(s.strokes,{smoothSelection=null;selected=null}){changed->if(applySelected(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed))){smoothSelection=null;selected=null;tool=0}}}
    if(eraserDialog)EraserDialog(eraser,{eraserDialog=false}){next->eraser=next;eraserDialog=false;scope.launch{val saved=try{eraserStore.save(next)}catch(c:CancellationException){throw c}catch(_:Exception){false};if(!saved)notice="本次橡皮已应用，但设置未保存。"}}
    if(showPaperPicker)PaperPickerDialog(PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},row.world,{showPaperPicker=false}){style->workspace.paper(row.noteId,style);showPaperPicker=false}
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括图片、文本框、胶带状态、可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;scope.launch{try{val source=withContext(Dispatchers.IO){app.documents.read(page.id)};exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED},objectsUi.objects,source);launcher.launch("墨织页面.iwpage")}catch(c:CancellationException){throw c}catch(_:Exception){notice="页面源文件未能读取，没有导出残缺副本。"}}}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
