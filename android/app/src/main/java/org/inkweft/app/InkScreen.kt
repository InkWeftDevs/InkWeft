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
    SideEffect{vm.suppressedIds=suppressed;objectsVm.history=vm.history}
    val historyHeads by vm.history.state.collectAsStateWithLifecycle()
    val selectable=ui.strokes.filterNot{it.id in suppressed}
    var smoothSelection by remember(page.id){mutableStateOf<SelectedInk?>(null)}
    var beautyFont by remember{mutableStateOf(true)}
    val beautyStore=remember{BeautyStore(context)}
    var beautyOptions by remember{mutableStateOf(beautyStore.read())}
    var beautySettings by remember{mutableStateOf(false)}
    fun saveBeauty(value:BeautyOptions){beautyOptions=value;beautyStore.save(value)}
    val beautyStatus by objectsVm.beautyStatus.collectAsStateWithLifecycle()
    var selectedObject by remember(page.id){mutableStateOf<String?>(null)}
    var objectInteraction by remember{mutableStateOf(false)}
    val objectsBlocked=objectsUi.loading||objectsUi.busy||objectsUi.pending||objectInteraction||smoothSelection!=null
    SideEffect{app.diagnostics.pageObjects(objectsUi.loading,objectsUi.busy,objectsUi.pending,objectsUi.objects.size,when{objectsUi.loading->DiagnosticResult.LOADING;objectsUi.busy->DiagnosticResult.SAVING;objectsUi.pending->DiagnosticResult.UNKNOWN;objectsUi.error!=null->DiagnosticResult.REJECTED;else->DiagnosticResult.SAVED})}
    val row=WorkspaceRow(page.id,page.world,page.paper,centerX=page.centerX,centerY=page.centerY,zoom=page.zoom)
    val scope=rememberCoroutineScope()
    var showPaperPicker by remember { mutableStateOf(false) }
    var tool by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    var finger by rememberSaveable(note.base.id){mutableStateOf(false)}
    val penStore=remember(context){PenWidthStore(context,"inkweft-pen-widths-book-"+note.base.id)}
    var widths by remember(note.base.id){mutableStateOf(penStore.read())}
    var kinds by remember(note.base.id){mutableStateOf(penStore.readKinds())}
    var colors by remember(note.base.id){mutableStateOf(penStore.readColors())}
    val favoriteStore=remember{FavoritePenStore(context)}
    var favorites by remember{mutableStateOf(favoriteStore.read())}
    var favoriteBusy by remember{mutableStateOf(false)}
    val casePrefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    var favoritesOpen by rememberSaveable{mutableStateOf(casePrefs.getBoolean("favorites-open",false))}
    fun showFavorites(value:Boolean){favoritesOpen=value;casePrefs.edit().putBoolean("favorites-open",value).apply()}
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
        if(beautyFont){objectsVm.beautify(target,beautyOptions,page.world,app);selected=null;tool=0} else if(writing.any{it.cuts.isNotEmpty()})notice="已局部擦除的笔迹暂不能润色，可以选择换字体。"else smoothSelection=target
    }
    fun enterBeauty(){
        if(continuousPages!=null)leaveContinuous()
        tool=6
        if(selected?.strokes?.isNotEmpty()==true)beautify(selected!!)else{selected=null;freehand=false}
    }
    fun toggleFavorite(kind:InkPen,width:Float,color:Int){
        if(favoriteBusy)return
        val match=favorites.firstOrNull{it.matches(kind,width,color)}
        if(match==null&&favorites.size>=12){notice="收藏笔盒已满，可在参数卡片取消已有收藏。";return}
        val next=if(match!=null)favorites.filterNot{it.id==match.id} else favorites+FavoritePen(java.util.UUID.randomUUID().toString(),kind,width,color)
        favoriteBusy=true
        scope.launch{try{if(favoriteStore.save(next)){favorites=next;if(match==null)showFavorites(true)}else notice="收藏未保存，请重试。"}catch(c:CancellationException){throw c}catch(_:Exception){notice="收藏未保存，请重试。"}finally{favoriteBusy=false}}
    }
    if(continuousPages==null)AutomaticBeautyBinding(objectsVm,ui,gesture,beautyOptions,page.world,app)
    val toolbar:@Composable ()->Unit={
        FloatingPenCase {
            (0..2).forEach{i->
                Box{
                    IconToggleButton(tool==i,{if(tool==i)settings=true else tool=i},enabled=!busy,modifier=Modifier.size(64.dp,48.dp).testTag("ink-tool-$i").describedAs(PenWidthStore.name(i,kinds[i])+"，再点调整")){
                        PenSilhouette(kinds[i],colors[i],Modifier.background(if(tool==i)Leaf else Color.Transparent,RoundedCornerShape(8.dp)))
                    }
                    if(tool==i)PenPresetMenu(settings,i,widths[i],colors[i],kinds[i],{settings=false},favorites,favoriteBusy,::toggleFavorite){width,color,kind->savePreset(i,width,color,kind);settings=false}
                }
            }
            HorizontalDivider(Modifier.width(36.dp),color=Line)
            IconToggleButton(favoritesOpen,{showFavorites(it)},modifier=Modifier.size(48.dp).testTag("favorite-pens-toggle").describedAs("收藏笔盒")){Glyph(if(favoritesOpen)"star-filled"else"star",if(favoritesOpen)Forest else Quiet)}
            IconToggleButton(tool==3,{if(tool==3)eraserDialog=true else tool=3},enabled=!busy,modifier=Modifier.size(48.dp).testTag("ink-tool-3").describedAs("橡皮，再点调整")){Glyph("eraser")}
            IconToggleButton(tool==4,{if(continuousPages!=null)leaveContinuous();tool=4},enabled=!busy&&!continuousBlocked,modifier=Modifier.size(48.dp).testTag("ink-select").describedAs("套索")){Glyph("select")}
            IconToggleButton(beautyOptions.enabled,{saveBeauty(beautyOptions.copy(enabled=it))},enabled=!gesture,modifier=Modifier.size(56.dp).testTag("auto-beauty-toggle").describedAs("自动美化")){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph("beauty",if(beautyOptions.enabled)Forest else Quiet);Text("自动美化",fontSize=10.sp,color=if(beautyOptions.enabled)Forest else Quiet)}
            }
            Box{
                IconButton(onClick={beautySettings=true},enabled=!busy,modifier=Modifier.size(48.dp).testTag("ink-beauty").describedAs("美化字体与框选美化")){Glyph("settings")}
                DropdownMenu(beautySettings,{beautySettings=false},modifier=Modifier.width(292.dp).testTag("beauty-settings")){
                    Column(Modifier.padding(horizontal=16.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){Text("自动美化",Modifier.weight(1f));Switch(beautyOptions.enabled,{saveBeauty(beautyOptions.copy(enabled=it))})}
                        FontControls(beautyOptions.font,{saveBeauty(beautyOptions.copy(font=it))},beautyOptions.bold,{saveBeauty(beautyOptions.copy(bold=it))},beautyOptions.spacing,{saveBeauty(beautyOptions.copy(spacing=it))})
                        Row(verticalAlignment=Alignment.CenterVertically){Text("字号 ${beautyOptions.size.toInt()}");Slider(beautyOptions.size,{saveBeauty(beautyOptions.copy(size=it))},valueRange=12f..96f,modifier=Modifier.weight(1f).testTag("beauty-font-size"))}
                    }
                    DropdownMenuItem(text={Text("框选美化")},onClick={beautySettings=false;beautyFont=true;enterBeauty()},modifier=Modifier.testTag("beauty-select"))
                    DropdownMenuItem(text={Text("笔形润色")},onClick={beautySettings=false;beautyFont=false;enterBeauty()})
                }
            }
            IconToggleButton(tool==5,{if(continuousPages!=null)leaveContinuous();selectedObject=null;tool=5},enabled=!busy&&!continuousBlocked,modifier=Modifier.size(48.dp).testTag("page-objects").describedAs("图片、拍照与文本框")){Glyph("add")}
            Box{
                IconButton(onClick={more=true},enabled=!busy,modifier=Modifier.size(48.dp).testTag("ink-more").describedAs("更多工具")){Glyph("more")}
                DropdownMenu(more,{more=false}){
                    DropdownMenuItem(text={Text("圈选擦除与摘录")},onClick={more=false;if(continuousPages!=null)leaveContinuous();tool=4;freehand=true},enabled=!continuousBlocked,modifier=Modifier.testTag("selection-tools"))
                    if(continuousPages==null)DropdownMenuItem(text={Text(if(finger)"关闭手指书写"else"开启手指书写")},onClick={finger=!finger;more=false},modifier=Modifier.testTag("ink-finger"))
                    DropdownMenuItem(text={Text("导出当前页")},onClick={more=false;confirmExport=true},enabled=!ui.loading)
                    DropdownMenuItem(text={Text("更换纸张模板")},onClick={more=false;showPaperPicker=true},modifier=Modifier.testTag("change-paper"))
                    DropdownMenuItem(text={Text("设为默认笔盒")},onClick={more=false;scope.launch{val defaults=PenWidthStore(context);(0..2).forEach{defaults.savePreset(it,widths[it],colors[it],kinds[it])}}})
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize()){

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
                ContinuousTools(kinds[tool.coerceIn(0,2)],colors[tool.coerceIn(0,2)],widths[tool.coerceIn(0,2)],tool==3,eraser.whole,eraser.onlyHighlighter,eraser.diameterDp,externalEnabled&&tool<4&&!objectsBlocked,beautyOptions),
                gesture,onContinuousPage,{gesture=it},{continuousBlocked=it},{notice=it},{id->onContinuousPage(id);leaveContinuous()})}
        }else if(row!=null){
            val initial=remember(page.id){workspace.cachedViewport(page.id)?:row.takeIf{it.zoom>0}?.let{runCatching{CanvasViewport(it.centerX,it.centerY,it.zoom)}.getOrNull()}}
            Box(Modifier.fillMaxWidth().weight(1f)){
            key(page.id){AndroidView(factory={ctx->InkCanvasView(ctx).also{v->
                view=v;v.onStroke=vm::accept;v.onErase={path,radius,whole,only->vm.erasePath(path,radius,whole,only);if(!only)objectsVm.eraseBeauty(path,radius)};v.onGesture={gesture=it}
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
                v.canvasView=view;v.objects=objectsUi.objects.filterNot{it.hidden};v.selected=selectedObject;v.world=page.world
                v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending&&!objectInteraction
                v.onSelect={selectedObject=it};v.onChange=objectsVm::put;v.onActive={gesture=it};v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("object-overlay"))
            if(tool==4||tool==6)AndroidView(factory={SelectionOverlayView(it)},update={v->
                v.selectedObjects=objectsUi.objects.filter{!it.hidden&&it.sourceStrokeIds.isNotEmpty()&&selected?.region?.selects(it.bounds())==true}.map{it.bounds()};v.canvasView=view;v.region=selected?.region;v.selected=selected?.strokes.orEmpty();v.freehand=freehand;v.enabledInput=editable
                v.onActive={gesture=it};v.onRegion={region->selected=region?.let{SelectedInk(it,ui.revision,selectable.filter{stroke->it.selects(stroke)})};if(tool==6)selected?.let{beautify(it)}}
                v.onShift={dx,dy->selected?.let{current->runCatching{InkSelectionEdit.copy(current.strokes,dx,dy)}.onSuccess{changed->if(applySelected(current.revision,InkMutation.Replace(current.strokes.map{it.id},changed)))selected=null}.onFailure{notice="移动超出画布或编辑预算，原笔迹保留。"}}}
                v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("selection-overlay"))
            }}
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}

        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            pageNavigation()
            IconButton(onClick={if(historyHeads.undo==EditDomain.OBJECT)objectsVm.undo()else vm.undo()},enabled=(if(historyHeads.undo==EditDomain.OBJECT)objectsUi.undo else ui.canUndo)&&!busy,modifier=Modifier.testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
            IconButton(onClick={if(historyHeads.redo==EditDomain.OBJECT)objectsVm.redo()else vm.redo()},enabled=(if(historyHeads.redo==EditDomain.OBJECT)objectsUi.redo else ui.canRedo)&&!busy,modifier=Modifier.testTag("ink-redo").describedAs("重做")){Glyph("redo")}
            Text(beautyStatus?:if(gesture)"书写中 · 抬笔保存"else if(ui.queued>0)"保存中"else "已保存 · ${ui.strokes.size} 笔",fontSize=11.sp,color=Forest,modifier=Modifier.testTag("ink-status"))
            if(continuousPages!=null)TextButton(onClick=leaveContinuous,enabled=!busy&&!continuousBlocked){Text("单页缩放")}else{
            Spacer(Modifier.width(10.dp));TextButton(onClick={view?.zoomBy(1/1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-out")){Text("−")}
            Text("${(zoom*100).toInt()}%",fontSize=11.sp,color=Quiet,modifier=Modifier.testTag("ink-zoom"))
            TextButton(onClick={view?.zoomBy(1.2)},enabled=!busy,modifier=Modifier.testTag("zoom-in")){Text("＋")}
            if(row?.world==true){TextButton(onClick={view?.fitContent()},enabled=!busy,modifier=Modifier.testTag("fit-content")){Text("全部内容",fontSize=12.sp)};TextButton(onClick={view?.origin()},enabled=!busy,modifier=Modifier.testTag("view-origin")){Text("回到原点",fontSize=12.sp)}}
            else{TextButton(onClick={view?.fitPage()},enabled=!busy,modifier=Modifier.testTag("fit-page")){Text("适页",fontSize=12.sp)};TextButton(onClick={view?.fitWidth()},enabled=!busy,modifier=Modifier.testTag("fit-width")){Text("适宽",fontSize=12.sp)}}
            }
        }
    }
        toolbar()
        if(favoritesOpen)FloatingPenCase("favorites",wide=true){
            if(favorites.isEmpty())Text("在笔参数卡片点星号收藏",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=Quiet)
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=8.dp,end=8.dp,bottom=12.dp)){
                favorites.forEach{p->Column(horizontalAlignment=Alignment.CenterHorizontally){
                    IconToggleButton(tool==p.slot&&kinds[p.slot]==p.kind&&widths[p.slot]==p.width&&colors[p.slot]==p.color,{savePreset(p.slot,p.width,p.color,p.kind);tool=p.slot},enabled=!busy,modifier=Modifier.size(64.dp,48.dp).testTag("favorite-pen-${p.id}").describedAs("${PenKinds.title(p.kind)}，${PenWidthStore.label(p.width)}")){PenSilhouette(p.kind,p.color)}
                    Text(PenWidthStore.label(p.width),style=MaterialTheme.typography.labelSmall,color=Quiet)
                }}
            }
        }
        if(tool in 4..6||objectsUi.error!=null||objectsUi.pending)Surface(Modifier.align(Alignment.TopCenter).padding(start=72.dp,end=8.dp).widthIn(max=620.dp),shape=RoundedCornerShape(12.dp),shadowElevation=3.dp){
            Column {
                if(tool==4){
                    SelectionActions(selected,selectable,editable&&!objectsBlocked,freehand,{freehand=it},::applySelected,{selected=null},onExcerpt,onAssociate,{beautify(it)})
                    val chosen=objectsUi.objects.filter{!it.hidden&&it.sourceStrokeIds.isNotEmpty()&&selected?.region?.selects(it.bounds())==true}
                    if(chosen.isNotEmpty())TextButton(onClick={objectsVm.deleteObjects(chosen.map{it.id}.toSet());selected=null},enabled=editable&&!objectsBlocked,modifier=Modifier.testTag("selection-delete-beauty")){Text("删除选中的美化文字")}
                }
                if(tool==6)Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
                    Text("框选文字即可美化",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={freehand=!freehand}){Text(if(freehand)"自由圈选"else"矩形框选")}
                    TextButton(onClick={tool=0}){Text("完成")}
                }
                PageObjectTools(objectsVm,objectsUi,page.id,page.world,tool==5,editable&&!gesture,selectedObject,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it})
            }
        }
    }
    smoothSelection?.let{s->BeautifyDialog(s.strokes,{smoothSelection=null;selected=null}){changed->if(applySelected(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed))){smoothSelection=null;selected=null;tool=0}}}
    if(eraserDialog)EraserDialog(eraser,{eraserDialog=false}){next->eraser=next;eraserDialog=false;scope.launch{val saved=try{eraserStore.save(next)}catch(c:CancellationException){throw c}catch(_:Exception){false};if(!saved)notice="本次橡皮已应用，但设置未保存。"}}
    if(showPaperPicker)PaperPickerDialog(PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},row.world,{showPaperPicker=false}){style->workspace.paper(row.noteId,style);showPaperPicker=false}
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括图片、文本框、胶带状态、可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;scope.launch{try{val source=withContext(Dispatchers.IO){app.documents.read(page.id)};exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED},objectsUi.objects,source);launcher.launch("墨织页面.iwpage")}catch(c:CancellationException){throw c}catch(_:Exception){notice="页面源文件未能读取，没有导出残缺副本。"}}}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
