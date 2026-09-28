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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow
import org.inkweft.data.WorkspaceRow

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InkPageScreen(note:NoteDraft,workspace:WorkspaceViewModel,page:NotebookPageRow,onCanNavigate:(Boolean)->Unit,onSearch:(Long)->Unit,externalEnabled:Boolean=true,onExcerpt:(SelectedInk)->Unit={},onMapExcerpt:(SelectedInk)->Unit={},onAssociate:(SelectedInk)->Unit={},focusRegion:CanvasBounds?=null,onFocusConsumed:()->Unit={},pageNavigation:@Composable ()->Unit={},continuousPages:List<NotebookPageRow>?=null,onContinuousPage:(String)->Unit={},leaveContinuous:()->Unit={},onTags:()->Unit={},onDocumentAction:(String)->Unit={},canAddPage:Boolean=false,excerptRequest:Int=0,fullScreen:Boolean=false,onFullScreen:(Boolean)->Unit={}){
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
    val visibleGeometry=remember{VisibleInkGeometry()}
    var pendingObject by remember(note.base.id){mutableStateOf<Pair<String,String>?>(null)}
    var penOpenRequest by remember{mutableIntStateOf(0)}
    var selectedObject by remember(page.id){mutableStateOf<String?>(null)}
    var objectInteraction by remember{mutableStateOf(false)}
    val objectsBlocked=objectsUi.loading||objectsUi.busy||objectsUi.pending||objectInteraction||smoothSelection!=null
    SideEffect{app.diagnostics.pageObjects(objectsUi.loading,objectsUi.busy,objectsUi.pending,objectsUi.objects.size,when{objectsUi.loading->DiagnosticResult.LOADING;objectsUi.busy->DiagnosticResult.SAVING;objectsUi.pending->DiagnosticResult.UNKNOWN;objectsUi.error!=null->DiagnosticResult.REJECTED;else->DiagnosticResult.SAVED})}
    val row=WorkspaceRow(page.id,page.world,page.paper,centerX=page.centerX,centerY=page.centerY,zoom=page.zoom)
    val scope=rememberCoroutineScope()
    var showPaperPicker by remember { mutableStateOf(false) }
    var tool by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    var readOnly by rememberSaveable(note.base.id){mutableStateOf(false)}
    var timerOpen by remember{mutableStateOf(false)}
    var finger by rememberSaveable(note.base.id){mutableStateOf(false)}
    val penStore=remember(context){PenWidthStore(context,"inkweft-pen-widths-book-"+note.base.id)}
    var widths by remember(note.base.id){mutableStateOf(penStore.read())}
    var kinds by remember(note.base.id){mutableStateOf(penStore.readKinds())}
    var colors by remember(note.base.id){mutableStateOf(penStore.readColors())}
    var recipes by remember(note.base.id){mutableStateOf(penStore.readRecipes())}
    fun saveRecipe(slot:Int,r:BrushRecipe){recipes=recipes.mapIndexed{i,old->if(i==slot)r else old};penStore.applyRecipe(slot,r);penStore.savePen(kinds[slot],PenSettings(widths[slot],colors[slot],r))}
    val favoriteStore=remember{FavoritePenStore(context)}
    var favorites by remember{mutableStateOf(favoriteStore.read())}
    var favoriteSettings by remember{mutableStateOf<String?>(null)}
    var objectRequest by remember{mutableStateOf<String?>(null)}
    var excerptMode by remember{mutableStateOf(false)}
    val excerptPrefs=remember{context.getSharedPreferences("inkweft-excerpts",0)}
    var excerptTextMode by rememberSaveable{mutableStateOf(excerptPrefs.getBoolean("text",false))}
    var capturingExcerpt by remember{mutableStateOf(false)}
    var showExcerptMarkers by rememberSaveable{mutableStateOf(excerptPrefs.getBoolean("markers",true))}
    val excerptRows by remember(note.base.id){app.study.excerpts(note.base.id)}.collectAsStateWithLifecycle(initialValue=emptyList())
    var areaEraseMode by remember{mutableStateOf(false)}
    var shapePicker by remember{mutableStateOf(false)}
    var tapeSettings by remember{mutableStateOf(false)}
    val tapePrefs=remember{context.getSharedPreferences("inkweft-tape",0)}
    var tapeMode by rememberSaveable{mutableIntStateOf(tapePrefs.getInt("brush-mode-v30",2).coerceIn(0,2))}
    var tapeWidth by rememberSaveable{mutableFloatStateOf(tapePrefs.getFloat("width",32f).coerceIn(4f,192f))}
    var tapeColor by rememberSaveable{mutableIntStateOf(tapePrefs.getInt("color",0xff91d8dc.toInt()))}
    var tapePatternIndex by rememberSaveable{mutableIntStateOf(tapePrefs.getInt("pattern",0).coerceIn(0,TapePattern.entries.lastIndex))}
    fun saveTape(){tapePrefs.edit().putInt("brush-mode-v30",tapeMode).putFloat("width",tapeWidth).putInt("color",tapeColor).putInt("pattern",tapePatternIndex).apply()}
    var lastWritingTool by rememberSaveable{mutableIntStateOf(0)}
    LaunchedEffect(tool){if(tool in 0..2)lastWritingTool=tool}
    var favoriteBusy by remember{mutableStateOf(false)}
    val casePrefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    var favoritesOpen by remember{mutableStateOf(casePrefs.getBoolean("favorites-open",false))}
    fun showFavorites(value:Boolean){favoritesOpen=value;casePrefs.edit().putBoolean("favorites-open",value).apply()}
    var continuousBlocked by remember{mutableStateOf(false)}
    var gesture by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    var axes by remember{mutableStateOf("本次启动尚未检测笔输入")}
    var view by remember{mutableStateOf<InkCanvasView?>(null)}
    var zoom by remember{mutableDoubleStateOf(.7)}
    var viewportHint by remember{mutableIntStateOf(0)}
    var hintJob by remember{mutableStateOf<Job?>(null)}
    fun showViewportHint(scale:Boolean){viewportHint=if(scale)2 else 1;hintJob?.cancel();hintJob=scope.launch{delay(850);viewportHint=0}}
    var selectionViewport by remember{mutableStateOf(CanvasViewport())}
    var settings by remember{mutableStateOf(false)}
    var more by remember{mutableStateOf(false)}
    val eraserStore=remember(context){EraserSettingsStore(context)}
    var eraser by remember{mutableStateOf(eraserStore.read())}
    var eraserDialog by remember{mutableStateOf(false)}
    var exportPending by remember{mutableStateOf<InkPageFile?>(null)}
    var confirmExport by remember{mutableStateOf(false)}
    var discard by remember{mutableStateOf(false)}
    val selectionStore=remember{SelectionStore(context)}
    var selectionOptions by remember{mutableStateOf(selectionStore.read())}
    var selectionSettings by remember{mutableStateOf(false)}
    var excerptSettings by remember{mutableStateOf(false)}
    var parameterAnchor by remember{mutableStateOf<IntRect?>(null)}
    val toolAnchors=remember{mutableMapOf<String,IntRect>()}
    fun anchorFor(id:String){parameterAnchor=toolAnchors[id]}
    fun Modifier.toolAnchor(id:String)=onGloballyPositioned{c->val b=c.boundsInWindow();toolAnchors[id]=IntRect(b.left.toInt(),b.top.toInt(),b.right.toInt(),b.bottom.toInt())}
    var pendingMixed by remember(page.id){mutableStateOf<Pair<Set<String>,Set<String>>?>(null)}
    var mixedSelection by remember(page.id){mutableStateOf<CanvasSelection?>(null)}
    val mixedWriter:ContinuousInkWriter=viewModel(key="mixed-writer-${page.id}")
    val mixedBlocked by mixedWriter.blocked.collectAsStateWithLifecycle()
    val mixedRetry by mixedWriter.needsRetry.collectAsStateWithLifecycle()
    var discardMixed by remember{mutableStateOf(false)}
    SideEffect{mixedWriter.onNotice={notice=it}}
    fun editMixed(s:CanvasSelection,change:Pair<InkMutation?,List<PageObject>>){
        if(mixedWriter.edit(vm,objectsVm,s.revision,s.snapshot,change.first,change.second,app.inkRepository)){
            val ink=(change.first as? InkMutation.Replace)?.added.orEmpty().map{it.id}.toSet()
            val copied=change.second.filter{o->s.snapshot.none{it.id==o.id}}.map{it.id}.toSet()
            val objects=if(copied.isNotEmpty())copied else change.second.filter{o->!o.hidden&&s.objects.any{it.id==o.id}}.map{it.id}.toSet()
            pendingMixed=if(ink.isEmpty()&&objects.isEmpty())null else ink to objects;mixedSelection=null
        }
    }
    fun moveMixed(s:CanvasSelection,dx:Float,dy:Float,copy:Boolean=false){runCatching{CanvasSelectionEdit.moved(s,dx,dy,copy,page.world)}.onSuccess{editMixed(s,it)}.onFailure{notice="调整超出页面或容量限制，原内容保留"}}
    var selected by remember(page.id){mutableStateOf<SelectedInk?>(null)}
    var pendingSelection by remember(page.id){mutableStateOf<Pair<InkRegion,List<String>>?>(null)}
    var freehand by remember{mutableStateOf(selectionOptions.freehand)}
    val editable=!mixedBlocked&&externalEnabled&&!readOnly&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!ui.processing
    LaunchedEffect(ui.revision,ui.queued,pendingSelection){
        if(ui.queued==0){
            pendingSelection?.let{(region,ids)->val found=ui.strokes.filter{it.id in ids};if(found.size==ids.size){selected=SelectedInk(region,ui.revision,found);pendingSelection=null}}
            if(selected?.revision!=ui.revision)selected=null
        }
    }
    LaunchedEffect(page.id,continuousPages==null,pendingObject){pendingObject?.let{(target,id)->if(target==page.id&&continuousPages==null){selectedObject=id;tool=5;pendingObject=null}}}
    LaunchedEffect(ui.revision,objectsUi.objects,mixedBlocked,pendingMixed){
        if(!mixedBlocked){
            val next=pendingMixed
            if(next!=null){
                val ink=ui.strokes.filter{it.id in next.first};val objects=objectsUi.objects.filter{it.id in next.second}
                if(ink.size==next.first.size&&objects.size==next.second.size){
                    val b=(ink.map{it.bounds()}+objects.map{it.bounds()}).reduce{a,v->a.union(v)}.padded(2.0)
                    val region=InkRegion(listOf(EraserPoint(b.left.toFloat(),b.top.toFloat()),EraserPoint(b.right.toFloat(),b.bottom.toFloat())))
                    mixedSelection=CanvasSelection(region,ui.revision,ink,objects,objectsUi.objects,ui.strokes);pendingMixed=null
                }
            }else{val s=mixedSelection;if(s!=null&&(s.revision!=ui.revision||s.snapshot!=objectsUi.objects))mixedSelection=null}
        }
    }
    LaunchedEffect(tool){if(tool!=4&&tool!=6){mixedSelection=null;selected=null;view?.selectionPreview(emptySet())}}
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
    val busy=capturingExcerpt||gesture||ui.processing||objectsBlocked||mixedBlocked
    val editingBlocked=busy||readOnly
    // Observe readiness in composition, not only inside a deferred SideEffect.
    val navigationReady=!busy&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!continuousBlocked
    SideEffect{onCanNavigate(navigationReady)}
    LaunchedEffect(ui.message){if(ui.message!=null){notice=ui.message;vm.clearMessage()}}
    fun savePreset(selected:Int,width:Float,color:Int,kind:InkPen){
        kinds=kinds.mapIndexed{i,p->if(i==selected)kind else p}
        widths=widths.mapIndexed{i,w->if(i==selected)width else w}
        colors=colors.mapIndexed{i,c->if(i==selected)color else c}
        penStore.applyPreset(selected,width,color,kind);penStore.savePen(kind,PenSettings(width,color,recipes[selected]))
    }
    fun choosePen(kind:InkPen){
        val slot=when(kind){InkPen.HIGHLIGHTER->2;InkPen.PEN->1;else->0};val value=penStore.readPen(kind)
        recipes=recipes.mapIndexed{i,r->if(i==slot)value.recipe else r};penStore.applyRecipe(slot,value.recipe)
        savePreset(slot,value.width,value.color,kind);tool=slot;settings=false
    }
    fun chooseSelection(free:Boolean=selectionOptions.freehand,excerpt:Boolean=false,erase:Boolean=false){mixedSelection=null;areaEraseMode=erase;if(continuousPages!=null)leaveContinuous();tool=4;freehand=free;excerptMode=excerpt;selected=null;if(excerpt)freehand=false}
    LaunchedEffect(excerptRequest){if(excerptRequest>0)chooseSelection(excerpt=true)}
    fun insertObject(action:String){if(continuousPages!=null)leaveContinuous();if(action=="shape"){shapePicker=true}else{tool=5;objectRequest=action}}
    fun beautify(selection:SelectedInk){
        val writing=selection.strokes.filterNot{it.pen==InkPen.HIGHLIGHTER}
        if(writing.isEmpty()){notice="请框选手写文字；荧光标注和 PDF 原文不能美化。";return}
        if(writing.size>256){notice="这段笔迹较多，请缩小到一两行再试。";return}
        val target=selection.copy(strokes=writing)
        if(beautyOptions.keepInk){val source=writing.filter{it.cuts.isEmpty()};if(source.isNotEmpty()){applySelected(selection.revision,InkMutation.Replace(source.map{it.id},InkSelectionEdit.beautify(source,beautyOptions.inkStrength)));selected=null;tool=0}}else if(beautyFont){objectsVm.beautify(target,beautyOptions,page.world,app);selected=null;tool=0} else if(writing.any{it.cuts.isNotEmpty()})notice="已局部擦除的笔迹暂不能润色，可以选择换字体。"else smoothSelection=target
    }
    fun captureExcerpt(selection:SelectedInk){
        val picture=runCatching{checkNotNull(view).excerptPreview(selection.region.bounds)}.getOrElse{notice=it.message?:"摘录未完成，请稍后重试";return}
        val revision=objectsVm.revision
        val strokes=selectable.filter{it.bounds().intersects(selection.region.bounds)}.take(256)
        if(!excerptTextMode){onExcerpt(selection.copy(preview=picture,objectRevision=revision));return}
        capturingExcerpt=true
        scope.launch{
            try{
                val pdfText=app.documentRendering.text(page.id,selection.region.bounds)
                val objectText=objectsUi.objects.filter{!it.hidden&&it.kind==PageObjectKind.TEXT&&it.bounds().intersects(selection.region.bounds)}.joinToString("\n"){it.visibleText()}
                val inkText=if(strokes.isEmpty())""else app.handwriting.recognize(strokes).text
                val text=listOf(pdfText,objectText,inkText).filter{it.isNotBlank()}.joinToString("\n").take(20000)
                onExcerpt(selection.copy(preview=picture,objectRevision=revision,excerptText=text))
                if(text.isBlank())notice="未提取到文字，已保留框选原貌，可添加备注"
            }catch(c:CancellationException){throw c}catch(_:Exception){onExcerpt(selection.copy(preview=picture,objectRevision=revision));notice="文字提取未完成，已保留框选原貌"}finally{capturingExcerpt=false}
        }
    }
    fun enterBeauty(){
        if(continuousPages!=null)leaveContinuous()
        tool=6
        if(selected?.strokes?.isNotEmpty()==true)beautify(selected!!)else{selected=null;freehand=false}
    }
    fun toggleFavorite(kind:InkPen,width:Float,color:Int){
        if(favoriteBusy)return
        val match=favorites.firstOrNull{it.matches(kind,width,color,recipes[tool.coerceIn(0,2)])}
        if(match==null&&favorites.size>=12){notice="收藏笔盒已满，可在参数卡片取消已有收藏。";return}
        val next=if(match!=null)favorites.filterNot{it.id==match.id} else favorites+FavoritePen(java.util.UUID.randomUUID().toString(),kind,width,color,recipes[tool.coerceIn(0,2)])
        favoriteStore.apply(next);favorites=next;if(match==null)showFavorites(true)
    }
    if(continuousPages==null&&!readOnly)AutomaticBeautyBinding(objectsVm,ui,gesture,beautyOptions,page.world,app)
    val toolbar:@Composable ()->Unit={
        FloatingPenCase(expandRequest=penOpenRequest) {
            val caseKinds=listOf(InkPen.PENCIL,InkPen.PEN,InkPen.BRUSH,InkPen.MARKER,InkPen.BALLPOINT,InkPen.HIGHLIGHTER)
            caseKinds.forEach{kind->
                val active=tool in 0..2&&kinds[tool]==kind
                val value=penStore.readPen(kind)
                Box{
                    IconToggleButton(active,{if(active)settings=true else choosePen(kind)},enabled=!editingBlocked,modifier=Modifier.size(96.dp,48.dp).testTag("pen-kind-${kind.name.lowercase()}").describedAs(PenKinds.title(kind)+"，再点调整")){
                        PenSilhouette(kind,if(active)colors[tool]else value.color,selected=active)
                    }
                    if(active)PenPresetMenu(settings,tool,widths[tool],colors[tool],kind,{settings=false},favorites,favoriteBusy,::toggleFavorite,recipe=recipes[tool],onRecipe={saveRecipe(tool,it)}){width,color,k->savePreset(tool,width,color,k)}
                }
            }
            Box {
            IconButton(onClick={if(tool==7)tapeSettings=true else{if(continuousPages!=null)leaveContinuous();selectedObject=null;tool=7}},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.size(96.dp,48.dp).testTag("object-tape").describedAs("胶带，拖动画胶带，再点调节")){CaseAccessory("tape",tool==7)}
                if(tapeSettings)TapeSettingsCard(tapeMode,{tapeMode=it;saveTape()},tapeWidth,{tapeWidth=it;saveTape()},tapeColor,{tapeColor=it;saveTape()},TapePattern.entries[tapePatternIndex],{tapePatternIndex=it.ordinal;saveTape()},objectsUi.objects.filter{it.kind==PageObjectKind.TAPE}.all{!it.revealed},
                    {shown->objectsVm.change(objectsUi.objects.map{if(it.kind==PageObjectKind.TAPE)it.copy(revealed=!shown)else it})},
                    {objectsVm.change(objectsUi.objects.filterNot{it.kind==PageObjectKind.TAPE})},{tapeSettings=false})
            }
            IconToggleButton(tool==3,{if(tool==3){anchorFor("eraser-case");eraserDialog=true}else tool=3},enabled=!editingBlocked,modifier=Modifier.size(96.dp,48.dp).toolAnchor("eraser-case").testTag("ink-tool-3").describedAs("橡皮，再点调整")){CaseAccessory("eraser",tool==3)}
            HorizontalDivider(Modifier.width(80.dp),color=Line)
            PenCaseColors(colors[lastWritingTool],lastWritingTool==2,!busy){color->savePreset(lastWritingTool,widths[lastWritingTool],color,kinds[lastWritingTool]);tool=lastWritingTool}
            Row(verticalAlignment=Alignment.CenterVertically){
            Box{
                IconButton(onClick={beautySettings=true},enabled=!gesture,modifier=Modifier.size(56.dp).testTag("auto-beauty-toggle").semantics{contentDescription="自动美化参数";stateDescription=if(beautyOptions.enabled)"已开启"else"已关闭"}){
                    Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph("beauty",if(beautyOptions.enabled)Forest else Quiet);Text("自动美化",fontSize=10.sp,color=if(beautyOptions.enabled)Forest else Quiet)}
                }
                BeautySettingsMenu(beautySettings,{beautySettings=false},beautyOptions,::saveBeauty,{beautySettings=false;beautyFont=true;enterBeauty()},{beautySettings=false;beautyFont=false;enterBeauty()})
            }

            }
        }
    }
    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize()){

        val status=when{ui.readFailed->"无法读取笔迹，原数据不会被空页覆盖";ui.loading||row==null->"正在读取笔迹和视图…";ui.blocked==InkCommitResult.Unknown->"保存结果待核对，未确认笔迹保留";ui.blocked!=null->"版本冲突或容量限制，未确认笔迹保留";gesture->"本笔尚未保存，抬笔后提交";ui.processing->"正在计算整笔擦除…";ui.queued>0->"正在提交 ${ui.queued} 项操作…";!ui.canStart->"达到采样预算，请导出或新建笔记继续";else->"已保存"}
        if(ui.blocked!=null||ui.readFailed)Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=17.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status,Modifier.weight(1f),fontSize=11.sp,color=if(ui.blocked!=null||ui.readFailed)Color(0xff984c24)else Forest)
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!editingBlocked){Text("核对重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!editingBlocked){Text("读取已保存页")}
            if(ui.readFailed&&ui.blocked==null)TextButton(onClick=vm::load){Text("重试")}
        }
        if(notice!=null)Row(Modifier.fillMaxWidth().background(Color.White).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(notice!!,Modifier.weight(1f),fontSize=12.sp);IconButton(onClick={notice=null},modifier=Modifier.describedAs("关闭提示")){Glyph("close")}}
        if(continuousPages!=null){
            Box(Modifier.fillMaxWidth().weight(1f)){ContinuousPages(continuousPages,page.id,
                ContinuousTools(kinds[tool.coerceIn(0,2)],colors[tool.coerceIn(0,2)],widths[tool.coerceIn(0,2)],tool==3,eraser.whole,eraser.onlyHighlighter,eraser.diameterDp,externalEnabled&&!readOnly&&tool<4&&!objectsBlocked,beautyOptions,recipes[tool.coerceIn(0,2)],eraser.onlyTape),
                gesture,onContinuousPage,{gesture=it;if(!it&&tool==3&&eraser.returnToPen)tool=lastWritingTool},{continuousBlocked=it},{notice=it},{id->onContinuousPage(id);leaveContinuous()},onScroll={showViewportHint(false)},onObjectTap={pageId,id->if(!readOnly){pendingObject=pageId to id;onContinuousPage(pageId);leaveContinuous()}})}
        }else if(row!=null){
            val initial=remember(page.id){workspace.cachedViewport(page.id)?:row.takeIf{it.zoom>0}?.let{runCatching{CanvasViewport(it.centerX,it.centerY,it.zoom)}.getOrNull()}}
            Box(Modifier.fillMaxWidth().weight(1f)){
            key(page.id){AndroidView(factory={ctx->InkCanvasView(ctx).also{v->
                view=v;v.onStroke=vm::accept;v.onErase={path,radius,whole,only->if(eraser.onlyTape)objectsVm.eraseTapes(path,radius)else{vm.erasePath(path,radius,whole,only);if(!only)objectsVm.eraseBeauty(path,radius,whole)}};v.onGesture={gesture=it;if(!it&&tool==3&&eraser.returnToPen)tool=lastWritingTool}
                v.onNotice={notice=it;app.diagnostics.event(DiagnosticCode.INK_UI,DiagnosticResult.REJECTED)}
                v.finishStroke={polishNewStroke(it,beautyOptions)};v.onViewportGesture={showViewportHint(it)};v.onAxes={pressure,tilt->app.diagnostics.inputAxes(pressure,tilt);axes="本次输入：压力${if(pressure)"已上报"else"未上报"} · 倾斜${if(tilt)"已上报"else"未上报"}"}
                v.onObjectTap={id->if(!readOnly){val o=objectsVm.ui.value.objects.find{it.id==id};if(o?.kind==PageObjectKind.TAPE)objectsVm.put(o.copy(revealed=!o.revealed))else{selectedObject=id;tool=5}}};v.onViewport={workspace.viewport(page.id,it)};v.onScale={zoom=it;selectionViewport=v.snapshotViewport()}
            }},update={v->
                v.configure(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},initial)
                v.allowInput=externalEnabled&&!readOnly&&tool<4&&!objectsBlocked&&(if(tool==3)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart);v.eraserTapeOnly=eraser.onlyTape;v.eraserWhole=eraser.whole;v.eraserHighlighterOnly=eraser.onlyHighlighter;v.eraserDiameterDp=eraser.diameterDp;v.fingerWrites=finger&&!readOnly;v.eraseMode=tool==3;v.pen=kinds[tool.coerceIn(0,2)]
                v.brushRecipe=recipes[tool.coerceAtMost(2)];v.penWidth=widths[tool.coerceAtMost(2)];v.penColor=colors[tool.coerceAtMost(2)]
                v.showDocument(page.id);v.showStrokes(ui.strokes);v.showObjects(objectsUi.objects)
                if(!gesture)v.selectionPreview((pendingSelection?.second?:selected?.strokes?.map{it.id}).orEmpty().toSet())
            },modifier=Modifier.fillMaxSize().testTag("ink-surface"))
            if(showExcerptMarkers)ExcerptMarkers(excerptRows.filter{it.pageId==page.id},selectionViewport)
            if(tool==7)AndroidView(factory={TapeOverlay(it)},update={v->
                v.canvasView=view;v.objects=objectsUi.objects;v.world=page.world;v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending
                v.pattern=TapePattern.entries[tapePatternIndex];v.mode=tapeMode;v.tapeWidth=tapeWidth;v.tapeColor=tapeColor;v.onActive={gesture=it};v.onCreate=objectsVm::put;v.onToggle={objectsVm.put(it.copy(revealed=!it.revealed))}
            },modifier=Modifier.fillMaxSize().testTag("tape-overlay"))
            if(tool==5)AndroidView(factory={PageObjectOverlay(it)},update={v->
                v.canvasView=view;v.objects=objectsUi.objects.filterNot{it.hidden};v.selected=selectedObject;v.world=page.world
                v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending&&!objectInteraction
                v.onSelect={selectedObject=it;if(it==null)tool=lastWritingTool};v.onChange=objectsVm::put;v.onActive={gesture=it};v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("object-overlay"))
            if(tool==5){
                val o=objectsUi.objects.find{it.id==selectedObject}
                val region=o?.let{InkRegion(listOf(EraserPoint(it.x,it.y),EraserPoint(it.x+it.width,it.y+it.height)))}
                SelectionToolbar(region,selectionViewport){
                PageObjectTools(objectsVm,objectsUi,page.id,page.world,tool==5,editable&&!gesture,selectedObject,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it},request=objectRequest.takeIf{continuousPages==null},onRequestConsumed={objectRequest=null},onDone={selectedObject=null;tool=lastWritingTool})
                }
            }
            if(tool!=5)PageObjectTools(objectsVm,objectsUi,page.id,page.world,false,editable&&!gesture,null,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it})
            if(tool==4||tool==6)AndroidView(factory={SelectionOverlayView(it)},update={v->
                v.geometry=visibleGeometry;v.selectedObjects=mixedSelection?.objects.orEmpty().map{it.bounds()};v.objectIds=mixedSelection?.objects.orEmpty().map{it.id}.toSet();v.worldSelection=page.world;v.canvasView=view;v.region=mixedSelection?.region?:selected?.region;v.selected=mixedSelection?.strokes?:selected?.strokes.orEmpty();v.freehand=freehand;v.enabledInput=editable
                v.onActive={gesture=it};v.onRegion={region->if(excerptMode&&region!=null){
                    val b=region.bounds;val clipped=runCatching{CanvasBounds(b.left.coerceAtLeast(if(page.world)-BoardLimits.WORLD.toDouble() else 0.0),b.top.coerceAtLeast(if(page.world)-BoardLimits.WORLD.toDouble() else 0.0),b.right.coerceAtMost(if(page.world)BoardLimits.WORLD.toDouble() else 1000.0),b.bottom.coerceAtMost(if(page.world)BoardLimits.WORLD.toDouble() else 1414.0))}.getOrNull()
                    if(clipped!=null&&clipped.right>clipped.left&&clipped.bottom>clipped.top){
                        val exact=InkRegion(listOf(EraserPoint(clipped.left.toFloat(),clipped.top.toFloat()),EraserPoint(clipped.right.toFloat(),clipped.bottom.toFloat())))
                        selected=SelectedInk(exact,ui.revision,emptyList());captureExcerpt(selected!!)
                    }
                }else if(areaEraseMode&&tool==4&&region!=null){val ids=selectable.filter{(!eraser.onlyHighlighter||it.pen==InkPen.HIGHLIGHTER)&&it.bounds().intersects(region.bounds)}.map{it.id};if(ids.isNotEmpty())applySelected(ui.revision,InkMutation.Cut(EraseSelection(region.mask(),ids)));selected=null;if(eraser.returnToPen)tool=lastWritingTool}else{val found=region?.let{CanvasSelectionEdit.query(it,ui.revision,ui.strokes,objectsUi.objects,if(excerptMode||tool==6)SelectionOptions(setOf(SelectionType.INK,SelectionType.HIGHLIGHTER))else selectionOptions,visibleGeometry)};mixedSelection=found?.takeIf{tool==4&&!excerptMode&&it.objects.isNotEmpty()};selected=found?.takeIf{mixedSelection==null}?.let{SelectedInk(it.region,it.revision,it.strokes)};if(tool==6)selected?.let{beautify(it)};}}
                v.onTap={x,y->
                    if(excerptMode&&excerptRows.any{it.pageId==page.id&&x>=it.left&&x<=it.right&&y>=it.top&&y<=it.bottom})onDocumentAction("excerpts")
                    else if(!areaEraseMode&&tool==4){
                        mixedSelection=null
                        val objectHit=objectsUi.objects.asReversed().firstOrNull{!it.hidden&&it.sourceStrokeIds.isEmpty()&&selectionOptions.accepts(it)&&ObjectGeometry.hit(it,x,y)}
                        if(objectHit!=null){selected=null;selectedObject=objectHit.id;tool=5}
                        val radius=(12/(selectionViewport.zoom*context.resources.displayMetrics.density)).toFloat()
                        val hit=selectable.filter{selectionOptions.accepts(it)}.asReversed().firstOrNull{s->
                            visibleGeometry.hits(s,listOf(InkSample(x,y,0,world=true)),radius) ||
                                (s.pen==InkPen.BALLPOINT&&s.cuts.isEmpty()&&s.samples.size>2&&s.samples.first().let{a->s.samples.last().let{b->a.x==b.x&&a.y==b.y}}&&s.bounds().let{b->x>=b.left&&x<=b.right&&y>=b.top&&y<=b.bottom})
                        }
                        selected=if(objectHit!=null)null else hit?.let{s->val b=s.bounds().padded(2.0);SelectedInk(InkRegion(listOf(EraserPoint(b.left.toFloat(),b.top.toFloat()),EraserPoint(b.right.toFloat(),b.bottom.toFloat()))),ui.revision,listOf(s))}
                    }
                }
                v.onShift={dx,dy->val mixed=mixedSelection;if(mixed!=null)moveMixed(mixed,dx,dy)else selected?.let{current->runCatching{InkSelectionEdit.copy(current.strokes,dx,dy)}.onSuccess{changed->if(applySelected(current.revision,InkMutation.Replace(current.strokes.map{it.id},changed)))selected=null}.onFailure{notice="移动超出画布或编辑预算，原笔迹保留。"}}}
                v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("selection-overlay"))
            if(tool==4&&!excerptMode&&!areaEraseMode&&!readOnly&&(selected!=null||mixedSelection!=null))SelectionToolbar(mixedSelection?.region?:selected?.region,selectionViewport){
                Column {

                    val mixed=mixedSelection
                    if(mixed!=null)MixedSelectionActions(mixed,editable&&!objectsBlocked,
                        copy={val b=(mixed.strokes.map{it.bounds()}+mixed.objects.map{it.bounds()}).reduce{a,v->a.union(v)}
                            val dx=if(page.world||b.right+24<=1000)24f else if(b.left>=24)-24f else 0f
                            val dy=if(page.world||b.bottom+24<=1414)24f else if(b.top>=24)-24f else 0f
                            moveMixed(mixed,dx,dy,true)},delete={editMixed(mixed,CanvasSelectionEdit.deleted(mixed))},edit={selectedObject=mixed.objects.single().id;mixedSelection=null;tool=5},scale={factor->runCatching{CanvasSelectionEdit.scaled(mixed,factor,page.world)}.onSuccess{editMixed(mixed,it)}.onFailure{notice="缩放超出页面、笔宽或对象尺寸限制，原内容保留"}},dismiss={mixedSelection=null})
                    else SelectionActions(selected,selectable.filter{selectionOptions.accepts(it)},editable&&!objectsBlocked,freehand,{freehand=it;selectionOptions=selectionOptions.copy(precise=false,freehand=it);selectionStore.save(selectionOptions)},::applySelected,{selected=null},::captureExcerpt,onAssociate,{beautify(it)},onMapExcerpt)
                    if(mixed==null&&selected==null)Row{
                        TextButton({selectionSettings=true},modifier=Modifier.testTag("selection-options")){Text("选择范围与方式")}
                        TextButton({
                            val bound=if(page.world)BoardLimits.WORLD else 0f
                            val region=InkRegion(listOf(EraserPoint(-bound,-bound),EraserPoint(if(page.world)bound else InkLimits.WIDTH,if(page.world)bound else InkLimits.HEIGHT)))
                            val candidates=selectable.count{selectionOptions.accepts(it)}
                            val found=if(candidates<=InkSelectionEdit.MAX_SELECTED)CanvasSelectionEdit.query(region,ui.revision,ui.strokes,objectsUi.objects,selectionOptions,visibleGeometry)else null
                            if(found==null)notice="本页笔迹较多，请分批选择（每次最多256笔）"
                            else if(found.count==0)notice="当前筛选范围内没有可选内容"
                            else {
                                val bounds=(found.strokes.map{it.bounds()}+found.objects.map{it.bounds()}).reduce{a,b->a.union(b)}
                                val tight=InkRegion(listOf(EraserPoint(bounds.left.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.top.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD)),EraserPoint(bounds.right.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD),bounds.bottom.toFloat().coerceIn(-BoardLimits.WORLD,BoardLimits.WORLD))))
                                if(found.objects.isNotEmpty())mixedSelection=found.copy(region=tight)else selected=SelectedInk(tight,found.revision,found.strokes)
                            }
                        },enabled=editable&&!objectsBlocked,modifier=Modifier.testTag("selection-all")){Text("全选本页")}
                    }
                }
            }
            }}
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}

    }
        if(viewportHint!=0)Surface(Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp),shape=RoundedCornerShape(20.dp),color=Color.White.copy(alpha=.9f),border=BorderStroke(1.dp,Line)){
            Row(Modifier.padding(horizontal=14.dp,vertical=6.dp)){if(viewportHint==2)Text("${(zoom*100).toInt()}%",fontSize=12.sp,modifier=Modifier.testTag("ink-zoom"))else pageNavigation()}
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(start=100.dp,end=8.dp,top=0.dp).widthIn(max=432.dp).fillMaxWidth(),shape=RoundedCornerShape(26.dp),color=Color.White,shadowElevation=3.dp,border=BorderStroke(1.dp,Line)){
            EditorToolbar { action -> when(action){
                "undo" -> IconButton(onClick={if(historyHeads.undo==EditDomain.OBJECT)objectsVm.undo()else vm.undo()},enabled=(if(historyHeads.undo==EditDomain.OBJECT)objectsUi.undo else ui.canUndo)&&!editingBlocked,modifier=Modifier.testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
                "redo" -> IconButton(onClick={if(historyHeads.redo==EditDomain.OBJECT)objectsVm.redo()else vm.redo()},enabled=(if(historyHeads.redo==EditDomain.OBJECT)objectsUi.redo else ui.canRedo)&&!editingBlocked,modifier=Modifier.testTag("ink-redo").describedAs("重做")){Glyph("redo")}
                "pen" -> IconButton(onClick={readOnly=false;selectedObject=null;if(tool !in 0..2)tool=lastWritingTool;penOpenRequest++;settings=true},enabled=!busy,modifier=Modifier.testTag("top-draw").describedAs("笔参数")){Glyph("pen",if(tool<3&&!readOnly)Forest else Quiet)}
                "eraser" -> IconToggleButton(tool==3,{if(tool==3){anchorFor("eraser");eraserDialog=true}else tool=3},enabled=!editingBlocked,modifier=Modifier.toolAnchor("eraser").testTag("top-eraser").describedAs("橡皮")){Glyph("eraser")}
                "lasso" -> IconToggleButton(tool==4&&!excerptMode&&!areaEraseMode,{if(tool==4&&!excerptMode&&!areaEraseMode){anchorFor("lasso");selectionSettings=true}else chooseSelection()},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.toolAnchor("lasso").testTag("ink-select").describedAs("套索")){Glyph("select")}
                "area" -> IconButton(onClick={chooseSelection(true,erase=true)},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("top-area-erase").describedAs("圈选擦除")){Glyph("area-erase")}
                "image" -> IconButton(onClick={insertObject("image")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-image").describedAs("插入图片")){Glyph("image")}
                "camera" -> IconButton(onClick={insertObject("camera")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-camera").describedAs("拍照")){Glyph("camera")}
                "text" -> IconButton(onClick={insertObject("text")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-text").describedAs("文本框")){Glyph("text")}
                "excerpt" -> IconButton(onClick={if(tool==4&&excerptMode){anchorFor("excerpt");excerptSettings=true}else chooseSelection(excerpt=true)},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.toolAnchor("excerpt").testTag("top-excerpt").describedAs("摘录")){Glyph("excerpt",if(excerptMode)Forest else TextInk)}
                "tag" -> IconButton(onClick=onTags,enabled=!editingBlocked,modifier=Modifier.testTag("top-tags").describedAs("笔记标签")){Glyph("tag")}
                "shape" -> IconButton(onClick={insertObject("shape")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-shape").describedAs("图形")){Glyph("shape")}
                "sticker" -> IconButton(onClick={insertObject("sticker")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-sticker").describedAs("贴纸与符号")){Glyph("sticker")}
                "objects" -> IconToggleButton(tool==5,{if(continuousPages!=null)leaveContinuous();selectedObject=null;tool=if(tool==5)lastWritingTool else 5},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("page-objects").describedAs("选择图片与文字")){Glyph("objects")}
                "favorites" -> IconToggleButton(favoritesOpen,{showFavorites(it)},enabled=!editingBlocked,modifier=Modifier.testTag("favorite-pens-toggle").describedAs("收藏笔")){Glyph("favorite-pens")}
                "beauty" -> IconButton(onClick={beautySettings=true;penOpenRequest++},enabled=!editingBlocked,modifier=Modifier.testTag("quick-beauty").describedAs("实时字迹调整")){Glyph("beauty")}
                "readonly" -> IconToggleButton(readOnly,{readOnly=it;selected=null;selectedObject=null;tool=lastWritingTool},enabled=!busy,modifier=Modifier.testTag("quick-readonly").describedAs("只读模式")){Glyph("readonly")}
                "finger" -> IconToggleButton(finger,{if(continuousPages!=null)leaveContinuous();finger=it;tool=lastWritingTool},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("quick-finger").describedAs("手指书写")){Glyph("finger")}
                "add-page" -> IconButton(onClick={onDocumentAction("add-page")},enabled=!editingBlocked&&canAddPage,modifier=Modifier.testTag("quick-add-page").describedAs("添加页面")){Glyph("add-page")}
                "overview" -> IconButton(onClick={onDocumentAction("overview")},enabled=!busy,modifier=Modifier.size(48.dp,if(LocalDensity.current.fontScale>1.3f)64.dp else 48.dp).testTag("quick-overview").describedAs("文档概览")){Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph("overview",modifier=Modifier.size(20.dp));Text("概览",fontSize=11.sp)}}
                "settings" -> IconButton(onClick={onDocumentAction("settings")},enabled=!busy,modifier=Modifier.size(48.dp,if(LocalDensity.current.fontScale>1.3f)64.dp else 48.dp).testTag("quick-settings").describedAs("其他设置")){Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph("settings",modifier=Modifier.size(20.dp));Text("设置",fontSize=11.sp)}}
                "fullscreen" -> IconToggleButton(fullScreen,{onFullScreen(it)},enabled=!busy,modifier=Modifier.testTag("quick-fullscreen").describedAs("全屏专注")){Glyph("fullscreen")}
                "export" -> IconButton(onClick={if(page.world)confirmExport=true else onDocumentAction("export")},enabled=!busy,modifier=Modifier.testTag("quick-export").describedAs("导出文档")){Glyph("export")}
                "timer" -> IconButton(onClick={timerOpen=true},modifier=Modifier.testTag("quick-timer").describedAs("计时器")){Glyph("timer")}
            }}
        }
        if(readOnly)Surface(Modifier.align(Alignment.TopCenter).padding(top=58.dp),shape=RoundedCornerShape(24.dp),color=Color.White,shadowElevation=3.dp,border=BorderStroke(1.dp,Line)){
            TextButton(onClick={readOnly=false},modifier=Modifier.testTag("exit-readonly")){Glyph("pen");Spacer(Modifier.width(8.dp));Text("只读浏览 · 返回书写")}
        }
        if(fullScreen)TextButton(onClick={onFullScreen(false)},modifier=Modifier.align(Alignment.TopStart).padding(top=if(readOnly)48.dp else 0.dp).testTag("exit-fullscreen")){Text("退出全屏")}
        if(!readOnly)toolbar()
        if(favoritesOpen&&!readOnly)FloatingPenCase("favorites",wide=true){
            if(favorites.isEmpty())Text("在笔参数卡片点星号收藏",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=Quiet)
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=8.dp,end=8.dp,bottom=12.dp)){
                favorites.forEach{p->Box{Column(horizontalAlignment=Alignment.CenterHorizontally){
                    val active=tool==p.slot&&kinds[p.slot]==p.kind&&widths[p.slot]==p.width&&colors[p.slot]==p.color&&recipes[p.slot]==p.recipe
                    Box(Modifier.size(64.dp,48.dp).testTag("favorite-pen-${p.id}").semantics{contentDescription="${PenKinds.title(p.kind)}，${PenWidthStore.label(p.width)}，长按调整";toggleableState=ToggleableState(active)}
                        .combinedClickable(enabled=!editingBlocked,onClick={if(active)favoriteSettings=p.id else{choosePen(p.kind);saveRecipe(p.slot,p.recipe);savePreset(p.slot,p.width,p.color,p.kind);tool=p.slot}},onLongClickLabel="调整收藏笔",onLongClick={choosePen(p.kind);saveRecipe(p.slot,p.recipe);savePreset(p.slot,p.width,p.color,p.kind);tool=p.slot;favoriteSettings=p.id}),contentAlignment=Alignment.Center){PenSilhouette(p.kind,p.color,selected=active)}
                    Text(PenWidthStore.label(p.width),style=MaterialTheme.typography.labelSmall,color=Quiet)
                }
                    if(favoriteSettings==p.id)PenPresetMenu(true,p.slot,p.width,p.color,p.kind,{favoriteSettings=null},favorites,onFavorite={_,_,_->favorites=favorites.filterNot{it.id==p.id};favoriteStore.apply(favorites);favoriteSettings=null},favoriteSelected=true,recipe=p.recipe,onRecipe={r->favorites=favorites.map{if(it.id==p.id)it.copy(recipe=r)else it};favoriteStore.apply(favorites);saveRecipe(p.slot,r)}){width,color,kind->
                        val updated=p.copy(kind=kind,width=width,color=color);favorites=favorites.map{if(it.id==p.id)updated else it};favoriteStore.apply(favorites);savePreset(updated.slot,width,color,kind);tool=updated.slot
                    }
                }}
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(start=72.dp,end=8.dp,top=56.dp).widthIn(max=620.dp),shape=RoundedCornerShape(12.dp),shadowElevation=3.dp){
            Column {
                if(tool==4&&areaEraseMode)Row(verticalAlignment=Alignment.CenterVertically){Text("圈住手写笔迹即可擦除",Modifier.padding(horizontal=12.dp));TextButton(onClick={tool=lastWritingTool}){Text("完成")}}
                if(tool==6)Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
                    Text("框选文字即可美化",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={freehand=!freehand}){Text(if(freehand)"自由圈选"else"矩形框选")}
                    TextButton(onClick={tool=0}){Text("完成")}
                }

            }
        }
    }
    if(timerOpen)NotebookTimer(note.base.id){timerOpen=false}
    if(shapePicker)ShapePicker({shapePicker=false}){kind->
        shapePicker=false
        val viewport=view?.snapshotViewport()?:CanvasViewport()
        val objectShape=PageObject(java.util.UUID.randomUUID().toString(),PageObjectKind.SHAPE,
            x=(viewport.centerX-120).toFloat().coerceIn(if(page.world)-BoardLimits.WORLD+4000 else 12f,if(page.world)BoardLimits.WORLD-4000 else 748f),
            y=(viewport.centerY-90).toFloat().coerceIn(if(page.world)-BoardLimits.WORLD+4000 else 12f,if(page.world)BoardLimits.WORLD-4000 else 1222f),
            width=240f,height=180f,color=colors[lastWritingTool] or 0xff000000.toInt(),lineWidth=widths[lastWritingTool].coerceIn(.5f,12f),shape=ObjectShape.valueOf(kind.uppercase()))
        objectsVm.put(objectShape);selectedObject=objectShape.id;tool=5

    }
    smoothSelection?.let{s->BeautifyDialog(s.strokes,{smoothSelection=null;selected=null}){changed->if(applySelected(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed))){smoothSelection=null;selected=null;tool=0}}}
    CompositionLocalProvider(LocalEditorAnchor provides parameterAnchor){
    if(excerptSettings)EditorPanel("摘要笔","",{excerptSettings=false},"excerpt-settings"){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(excerptTextMode,{excerptTextMode=true;excerptPrefs.edit().putBoolean("text",true).apply()},label={Text("文本摘录")},modifier=Modifier.testTag("excerpt-text-mode"))
        FilterChip(!excerptTextMode,{excerptTextMode=false;excerptPrefs.edit().putBoolean("text",false).apply()},label={Text("框选摘录")},modifier=Modifier.testTag("excerpt-region-mode"))
    };Row(verticalAlignment=Alignment.CenterVertically){Text("显示摘录标记",Modifier.weight(1f));Switch(showExcerptMarkers,{showExcerptMarkers=it;excerptPrefs.edit().putBoolean("markers",it).apply()},modifier=Modifier.testTag("excerpt-markers"))};Text(if(excerptTextMode)"框选 PDF 文字、文本框或手写内容，提取结果可在备注中修改"else"框选页面区域，保存原貌并添加备注",style=MaterialTheme.typography.bodySmall);TextButton({excerptSettings=false;onDocumentAction("excerpts")},modifier=Modifier.testTag("excerpt-open-list")){Text("查看本笔记摘录")}}
    if(selectionSettings)SelectionSettings(selectionOptions,freehand,{selectionSettings=false}){value,free->selectionOptions=value.copy(freehand=free);selectionStore.save(selectionOptions);freehand=free;selected=null;mixedSelection=null}
    if(mixedRetry)AlertDialog(onDismissRequest={},title={Text("编辑保存待核对")},text={Text("原操作已保留，请先核对保存结果。")},confirmButton={TextButton({mixedWriter.retry(app.inkRepository)}){Text("核对重试")}},dismissButton={TextButton({discardMixed=true}){Text("读取已保存页")}})
    if(discardMixed)AlertDialog(onDismissRequest={discardMixed=false},text={Text("放弃未确认的编辑草稿，重新读取已保存内容？")},confirmButton={TextButton({discardMixed=false;pendingMixed=null;mixedSelection=null;mixedWriter.readSaved()}){Text("读取")}},dismissButton={TextButton({discardMixed=false}){Text("取消")}})
    if(eraserDialog)EraserDialog(eraser,{eraserDialog=false},circle={eraserDialog=false;chooseSelection(erase=true)}){next->eraser=next;eraserStore.save(next)}
    }
    if(showPaperPicker)PaperPickerDialog(PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},row.world,{showPaperPicker=false}){style->workspace.paper(row.noteId,style);showPaperPicker=false}
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括图片、文本框、胶带状态、可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;scope.launch{try{val source=withContext(Dispatchers.IO){app.documents.read(page.id)};exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED},objectsUi.objects,source);launcher.launch("墨织页面.iwpage")}catch(c:CancellationException){throw c}catch(_:Exception){notice="页面源文件未能读取，没有导出残缺副本。"}}}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
