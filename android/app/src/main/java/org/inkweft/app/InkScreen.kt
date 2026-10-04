// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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

private data class SourceFocusPulse(
    val bookId:String,val pageId:String,val request:Int,val view:InkCanvasView,
    val bounds:CanvasBounds,val viewport:CanvasViewport,val size:IntSize,val density:Double,val interactionEpoch:Long
)

/** A sibling decoration never enters native paper snapshots or takes their input. */
@Composable private fun SourceFocusHighlight(pulse:SourceFocusPulse,onFinished:(SourceFocusPulse)->Unit){
    val opacity=remember(pulse){Animatable(1f)}
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(pulse){opacity.animateTo(0f,tween(240));finish(pulse)}
    Canvas(Modifier.fillMaxSize().clipToBounds().testTag("source-focus-highlight")){
        val native=pulse.view;val alpha=opacity.value
        if(alpha<=0f||!native.isAttachedToWindow||native.isLayoutRequested||
            native.width!=pulse.size.width||native.height!=pulse.size.height||
            size.width!=pulse.size.width.toFloat()||size.height!=pulse.size.height.toFloat()||
            native.resources.displayMetrics.density.toDouble()!=pulse.density||native.snapshotViewport()!=pulse.viewport||native.sourceInteractionEpoch!=pulse.interactionEpoch)return@Canvas
        val a=pulse.viewport.worldToScreen(pulse.bounds.left,pulse.bounds.top,size.width.toDouble(),size.height.toDouble(),pulse.density)
        val b=pulse.viewport.worldToScreen(pulse.bounds.right,pulse.bounds.bottom,size.width.toDouble(),size.height.toDouble(),pulse.density)
        if(!a.x.isFinite()||!a.y.isFinite()||!b.x.isFinite()||!b.y.isFinite())return@Canvas
        val left=a.x.toFloat().coerceIn(0f,size.width);val top=a.y.toFloat().coerceIn(0f,size.height)
        val right=b.x.toFloat().coerceIn(0f,size.width);val bottom=b.y.toFloat().coerceIn(0f,size.height)
        if(right<=left||bottom<=top)return@Canvas
        val corner=CornerRadius(6.dp.toPx().coerceAtMost(minOf(right-left,bottom-top)/2f))
        val origin=Offset(left,top);val extent=Size(right-left,bottom-top)
        drawRoundRect(InkTheme.Accent.copy(alpha=.12f*alpha),origin,extent,corner)
        drawRoundRect(InkTheme.Accent.copy(alpha=.8f*alpha),origin,extent,corner,style=Stroke(2.dp.toPx()))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InkPageScreen(note:NoteDraft,workspace:WorkspaceViewModel,page:NotebookPageRow,onCanNavigate:(Boolean)->Unit,onSearch:(Long)->Unit,externalEnabled:Boolean=true,onExcerpt:(SelectedInk)->Unit={},onMapExcerpt:(SelectedInk)->Unit={},onAssociate:(SelectedInk)->Unit={},focusRegion:CanvasBounds?=null,focusRequest:Int=0,onFocusConsumed:()->Unit={},pageNavigation:@Composable ()->Unit={},continuousPages:List<NotebookPageRow>?=null,onContinuousPage:(String)->Unit={},leaveContinuous:()->Unit={},onTags:()->Unit={},onDocumentAction:(String)->Unit={},canAddPage:Boolean=false,excerptRequest:Int=0,fullScreen:Boolean=false,embedRequest:EmbedInsertion?=null,onEmbedConsumed:()->Unit={},onEditMap:(MapEmbed)->Unit={},onFullScreen:(Boolean)->Unit={},onAppendPage:(()->Unit)?=null,readOnlyRequest:MutableState<((Boolean)->Boolean)?>?=null,pageToolRequest:MutableState<((String)->Unit)?>?=null,onAuthorDraft:(Boolean)->Unit={}){
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
    val beautyReview by objectsVm.beautyReview.collectAsStateWithLifecycle()
    val visibleGeometry=remember{VisibleInkGeometry()}
    var pendingObject by remember(note.base.id){mutableStateOf<Pair<String,String>?>(null)}
    var penOpenRequest by remember{mutableIntStateOf(0)}
    var selectedObject by remember(page.id){mutableStateOf<String?>(null)}
    var objectInteraction by remember{mutableStateOf(false)}
    val objectsBlocked=objectsUi.loading||objectsUi.busy||objectsUi.pending||objectInteraction||smoothSelection!=null||beautyReview?.open==true
    val inkObjectsBlocked=objectsUi.loading||((objectsUi.busy||objectsUi.pending)&&!objectsUi.automaticPending)||objectInteraction||smoothSelection!=null||beautyReview?.open==true
    SideEffect{app.diagnostics.pageObjects(objectsUi.loading,objectsUi.busy,objectsUi.pending,objectsUi.objects.size,when{objectsUi.loading->DiagnosticResult.LOADING;objectsUi.busy->DiagnosticResult.SAVING;objectsUi.pending->DiagnosticResult.UNKNOWN;objectsUi.error!=null->DiagnosticResult.REJECTED;else->DiagnosticResult.SAVED})}
    val row=WorkspaceRow(page.id,page.world,page.paper,centerX=page.centerX,centerY=page.centerY,zoom=page.zoom)
    val scope=rememberCoroutineScope()
    var showPaperPicker by remember { mutableStateOf(false) }
    var tool by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    val readLock=rememberBookReadLock(note.base.id)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    SideEffect{vm.authorAllowed={readLock.canWrite};objectsVm.authorAllowed={readLock.canWrite};readLock.observeObjects(page.id,objectsVm)}
    var timerOpen by remember{mutableStateOf(false)}
    val inputPrefs=remember(context){context.getSharedPreferences("inkweft-editor",0)}
    var finger by rememberSaveable(note.base.id){mutableStateOf(inputPrefs.getBoolean("finger-writes",false))}
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
    var selectedExcerpt by rememberSaveable(page.id){mutableStateOf<String?>(null)}
    val toolbarDensity=LocalDensity.current
    var toolbarHeight by remember{mutableStateOf(56.dp)}
    var excerptDraft by remember{mutableStateOf(false)}
    val excerptVm:StudyViewModel=viewModel(key="excerpt-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val mapSession:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    SideEffect{excerptVm.authorAllowed={readLock.canWrite};mapSession.authorAllowed={readLock.canWrite}}
    var excerptMode by rememberSaveable{mutableStateOf(false)}
    val excerptPrefs=remember{context.getSharedPreferences("inkweft-excerpts",0)}
    var excerptMarkMode by rememberSaveable{mutableStateOf(false)}
    var captureToMap by rememberSaveable{mutableStateOf(excerptPrefs.getBoolean("to-map",true))}
    var excerptTextMode by rememberSaveable{mutableStateOf(excerptPrefs.getBoolean("text",false))}
    var capturingExcerpt by remember{mutableStateOf(false)}
    var excerptJob by remember(page.id){mutableStateOf<Job?>(null)}
    DisposableEffect(page.id){onDispose{excerptJob?.cancel()}}
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
    var view by remember(page.id){mutableStateOf<InkCanvasView?>(null)}
    var canvasSize by remember(page.id){mutableStateOf(IntSize.Zero)}
    var sourcePulse by remember(note.base.id,page.id){mutableStateOf<SourceFocusPulse?>(null)}
    var pendingSourcePulse by remember(note.base.id,page.id){mutableStateOf<SourceFocusPulse?>(null)}
    var lastSourcePulseRequest by remember(note.base.id,page.id){mutableIntStateOf(0)}
    val latestFocusRequest by rememberUpdatedState(focusRequest)
    fun cancelSourcePulse(){pendingSourcePulse?.let{lastSourcePulseRequest=it.request};pendingSourcePulse=null;sourcePulse=null}
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
    LaunchedEffect(mapSession.captureGeneration){if(mapSession.captureGeneration>0)selected=null}
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
    LaunchedEffect(tool){if(tool!=4)selectedExcerpt=null;if(tool!=4&&tool!=6){mixedSelection=null;selected=null;view?.selectionPreview(emptySet())}}
    LaunchedEffect(note.base.id,page.id,focusRequest,focusRegion,view,canvasSize){
        pendingSourcePulse?.let{pulse->
            if(pulse.request!=focusRequest||pulse.view!==view||pulse.size!=canvasSize||
                (focusRegion!=null&&pulse.bounds!=focusRegion))pendingSourcePulse=null
        }
        sourcePulse?.let{pulse->
            if(pulse.request!=focusRequest||pulse.view!==view||pulse.size!=canvasSize||
                (focusRegion!=null&&pulse.bounds!=focusRegion))sourcePulse=null
        }
    }
    LaunchedEffect(gesture,objectInteraction){if(gesture||objectInteraction)cancelSourcePulse()}
    LaunchedEffect(page.id,focusRegion,focusRequest,view,ui.loading,canvasSize){
        val targetView=view;val targetBounds=focusRegion
        if(targetView!=null&&targetBounds!=null&&!ui.loading&&canvasSize.width>0&&canvasSize.height>0){
            // Own the work and wait for AndroidView's reserved paper size, rather than posting to an old page.
            do{withFrameNanos{}}while(!targetView.isAttachedToWindow||targetView.isLayoutRequested||targetView.width!=canvasSize.width||targetView.height!=canvasSize.height)
            currentCoroutineContext().ensureActive()
            if(targetView===view&&targetView.isAttachedToWindow){
                targetView.focusRegion(targetBounds)
                if(focusRequest>0&&lastSourcePulseRequest!=focusRequest){
                    sourcePulse=null
                    pendingSourcePulse=SourceFocusPulse(note.base.id,page.id,focusRequest,targetView,targetBounds,
                        targetView.snapshotViewport(),IntSize(targetView.width,targetView.height),targetView.resources.displayMetrics.density.toDouble(),targetView.sourceInteractionEpoch)
                }
                onFocusConsumed()
            }
        }
    }
    // Consuming one-shot focus may clear focusRegion. Frame readiness belongs to this frozen request.
    LaunchedEffect(pendingSourcePulse){
        val pulse=pendingSourcePulse?:return@LaunchedEffect
        val native=pulse.view
        val ready=withTimeoutOrNull(8_000){
            var drawn=false
            while(!drawn){
                withFrameNanos{}
                if(pendingSourcePulse!==pulse)return@withTimeoutOrNull false
                if(native.sourceInteractionEpoch!=pulse.interactionEpoch){cancelSourcePulse();return@withTimeoutOrNull false}
                if(pulse.request!=latestFocusRequest||view!==native||canvasSize!=pulse.size||!native.isAttachedToWindow||
                    native.width!=pulse.size.width||native.height!=pulse.size.height||native.snapshotViewport()!=pulse.viewport||
                    native.resources.displayMetrics.density.toDouble()!=pulse.density||native.sourceFrameFailed)return@withTimeoutOrNull false
                drawn=!native.isLayoutRequested&&native.hasDrawnSourceFrame(pulse.pageId,pulse.viewport,pulse.size.width,pulse.size.height,pulse.density)
            }
            true
        }
        if(pendingSourcePulse===pulse){
            lastSourcePulseRequest=pulse.request
            if(ready==true)sourcePulse=pulse
            pendingSourcePulse=null
        }
    }
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
    val busy=excerptDraft||capturingExcerpt||gesture||ui.processing||objectsBlocked||mixedBlocked
    val editingBlocked=excerptDraft||busy||readOnly
    // Observe readiness in composition, not only inside a deferred SideEffect.
    val navigationReady=!busy&&!ui.loading&&!ui.readFailed&&ui.queued==0&&ui.blocked==null&&!continuousBlocked
    SideEffect{onCanNavigate(navigationReady)}
    // A saved object's selection is browse state; its unsaved tools and gestures
    // report their own interaction/operation guards.
    val authorDraft=excerptDraft||objectInteraction||selected?.preview!=null||shapePicker||objectRequest!=null||smoothSelection!=null||beautyReview?.open==true
    SideEffect{onAuthorDraft(authorDraft)}
    DisposableEffect(page.id){val reporter=onAuthorDraft;onDispose{reporter(false)}}
    ReadLockGuard(readLock,"ink-${page.id}",!navigationReady||authorDraft||embedRequest!=null,authorDraft)
    ReadLockGuard(readLock,"ink-gesture-${page.id}",gesture)
    fun changeReadOnly(value:Boolean):Boolean{
        val ink=vm.ui.value;val objects=objectsVm.ui.value
        val accepted=readLock.request(value,navigationReady&&externalEnabled&&!gesture&&!ink.processing&&ink.queued==0&&ink.blocked==null&&!objects.busy&&!objects.pending&&!objectsVm.authorOperationActive)
        if(!accepted)notice=readLock.reason
        return accepted
    }
    val currentReadOnlyChange by rememberUpdatedState<(Boolean)->Boolean>({value->changeReadOnly(value)})
    DisposableEffect(page.id,readOnlyRequest){
        val slot=readOnlyRequest
        val handler:(Boolean)->Boolean={currentReadOnlyChange(it)}
        slot?.value=handler
        onDispose{if(slot!=null&&slot.value===handler)slot.value=null}
    }
    val currentPageTool by rememberUpdatedState<(String)->Unit>({action->when(action){
        "export"->if(page.world)confirmExport=true else onDocumentAction("export")
        "timer"->timerOpen=true
    }})
    DisposableEffect(page.id,pageToolRequest){
        val slot=pageToolRequest
        val handler:(String)->Unit={currentPageTool(it)}
        slot?.value=handler
        onDispose{if(slot!=null&&slot.value===handler)slot.value=null}
    }
    // Reading hides transient parameter UI, while keeping tools and all saved preferences.
    LaunchedEffect(readOnly){if(readOnly){
        settings=false;eraserDialog=false;selectionSettings=false;excerptSettings=false
        tapeSettings=false;favoriteSettings=null;beautySettings=false;penOpenRequest=0
    }}
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
    fun selectAll(){
        selectionSettings=false
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
    }
    fun chooseSelection(free:Boolean=selectionOptions.freehand,excerpt:Boolean=false,erase:Boolean=false){selectedExcerpt=null;mixedSelection=null;areaEraseMode=erase;if(continuousPages!=null)leaveContinuous();tool=4;freehand=free;excerptMode=excerpt;selected=null;if(excerpt)freehand=excerptMarkMode}
    LaunchedEffect(excerptRequest){if(excerptRequest>0)chooseSelection(excerpt=true)}
    LaunchedEffect(embedRequest,objectsUi.loading,objectsUi.busy,continuousPages==null,readOnly){
        val request=embedRequest
        if(request!=null&&readLock.canWrite&&continuousPages==null&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending){
            val viewport=view?.snapshotViewport()?:CanvasViewport()
            val x=(viewport.centerX-300).toFloat();val y=(viewport.centerY-190).toFloat()
            val value=PageObject(request.objectId,PageObjectKind.MAP,if(page.world)x else x.coerceIn(0f,400f),if(page.world)y else y.coerceIn(0f,1034f),width=600f,height=380f,mapEmbed=request.embed)
            objectsVm.put(value);selectedObject=value.id;tool=5;onEmbedConsumed()
        }
    }
    fun insertObject(action:String){if(!readLock.canWrite)return;if(continuousPages!=null)leaveContinuous();if(action=="shape"){shapePicker=true}else{tool=5;objectRequest=action}}
    fun beautify(selection:SelectedInk){
        val writing=selection.strokes.filterNot{it.pen==InkPen.HIGHLIGHTER}
        if(writing.isEmpty()){notice="请框选手写文字；荧光标注和 PDF 原文不能美化。";return}
        if(writing.size>256){notice="这段笔迹较多，请缩小到一两行再试。";return}
        val target=selection.copy(strokes=writing)
        if(beautyOptions.keepInk){val source=writing.filter{it.cuts.isEmpty()};if(source.isNotEmpty()){applySelected(selection.revision,InkMutation.Replace(source.map{it.id},InkSelectionEdit.beautify(source,beautyOptions.inkStrength)));selected=null;tool=0}}else if(beautyFont){objectsVm.beautify(target,beautyOptions,page.world,app);selected=null;tool=0} else if(writing.any{it.cuts.isNotEmpty()})notice="已局部擦除的笔迹暂不能润色，可以选择换字体。"else smoothSelection=target
    }
    fun captureExcerpt(selection:SelectedInk){
        if(!readLock.canWrite)return
        excerptJob?.cancel();excerptJob=null;capturingExcerpt=false
        // A completed read belongs only to the exact selection that started it.
        fun current()=selected===selection&&readLock.canWrite
        fun prepared(value:SelectedInk){if(current())selected=value}
        val picture=runCatching{checkNotNull(view).excerptPreview(selection.region.bounds)}.getOrElse{notice=it.message?:"摘录未完成，请稍后重试";return}
        val revision=objectsVm.revision
        val strokes=selectable.filter{it.bounds().intersects(selection.region.bounds)}
        val recognizeInk=excerptTextMode&&strokes.size<=256
        capturingExcerpt=true
        excerptJob=scope.launch{
            val request=currentCoroutineContext().job
            try{
                // PDF/object text is cheap and deterministic; handwriting recognition stays opt-in.
                val pdfText=app.documentRendering.text(page.id,selection.region.bounds)
                val objectText=objectsUi.objects.filter{!it.hidden&&it.kind==PageObjectKind.TEXT&&it.bounds().intersects(selection.region.bounds)}.joinToString("\n"){it.visibleText()}
                val inkText=if(recognizeInk&&strokes.isNotEmpty())app.handwriting.recognize(strokes).text else ""
                val text=listOf(pdfText,objectText,inkText).filter{it.isNotBlank()}.joinToString("\n").take(20000)
                if(current()){
                    notice=if(excerptTextMode&&strokes.size>256)"已保留原貌与可提取文字；手写识别请缩小范围"else if(text.isBlank())"已保留图片 / 手写原貌，可添加备注"else "已保留文字摘要与原貌"
                    prepared(selection.copy(preview=picture,objectRevision=revision,excerptText=text))
                }
            }catch(c:CancellationException){throw c}catch(_:Exception){if(current()){notice="文字提取未完成，已保留框选原貌";prepared(selection.copy(preview=picture,objectRevision=revision))}}
            finally{if(excerptJob===request){capturingExcerpt=false;excerptJob=null}}
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
        FloatingPenCase(expandRequest=penOpenRequest,topInset=toolbarHeight) {
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
                IconButton(onClick={beautySettings=true},enabled=!busy,modifier=Modifier.size(56.dp).testTag("auto-beauty-toggle").semantics{contentDescription="自动美化参数";stateDescription=if(beautyOptions.enabled)"已开启"else"已关闭"}){
                    Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph("beauty",if(beautyOptions.enabled)Forest else Quiet);Text("自动美化",fontSize=10.sp,color=if(beautyOptions.enabled)Forest else Quiet)}
                }
                BeautySettingsMenu(beautySettings,{beautySettings=false},beautyOptions,::saveBeauty,{beautySettings=false;beautyFont=true;enterBeauty()},{beautySettings=false;beautyFont=false;enterBeauty()})
            }

            }
        }
    }
    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().padding(top=toolbarHeight)){

        val status=when{ui.readFailed->"无法读取笔迹，原数据不会被空页覆盖";ui.loading||row==null->"正在读取笔迹和视图…";ui.blocked==InkCommitResult.Unknown->"保存结果待核对，未确认笔迹保留";ui.blocked!=null->"版本冲突或容量限制，未确认笔迹保留";gesture->"本笔尚未保存，抬笔后提交";ui.processing->"正在计算整笔擦除…";ui.queued>0->"正在提交 ${ui.queued} 项操作…";!ui.canStart->"达到采样预算，请导出或新建笔记继续";else->"已保存"}
        if(ui.blocked!=null||ui.readFailed)Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=17.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status,Modifier.weight(1f),fontSize=11.sp,color=if(ui.blocked!=null||ui.readFailed)Color(0xff984c24)else Forest)
            if(ui.blocked==InkCommitResult.Unknown)TextButton(onClick=vm::retry,enabled=!ui.processing&&!gesture){Text("核对重试")}
            if(ui.blocked in listOf(InkCommitResult.Conflict,InkCommitResult.Rejected))TextButton(onClick={discard=true},enabled=!editingBlocked){Text("读取已保存页")}
            if(ui.readFailed&&ui.blocked==null)TextButton(onClick=vm::load){Text("重试")}
        }
        if(notice!=null)Row(Modifier.fillMaxWidth().background(Color.White).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(notice!!,Modifier.weight(1f),fontSize=12.sp);IconButton(onClick={notice=null},modifier=Modifier.describedAs("关闭提示")){Glyph("close")}}
        if(continuousPages!=null){
            Box(Modifier.fillMaxWidth().weight(1f)){ContinuousPages(continuousPages,page.id,
                ContinuousTools(kinds[tool.coerceIn(0,2)],colors[tool.coerceIn(0,2)],widths[tool.coerceIn(0,2)],tool==3,eraser.whole,eraser.onlyHighlighter,eraser.diameterDp,externalEnabled&&!readOnly&&tool<4&&!(if(tool==3)objectsBlocked else inkObjectsBlocked),beautyOptions,recipes[tool.coerceIn(0,2)],eraser.onlyTape,finger&&!readOnly),
                gesture,onContinuousPage,{gesture=it;readLock.guard("ink-gesture-${page.id}",it);if(!it&&tool==3&&eraser.returnToPen)tool=lastWritingTool},{continuousBlocked=it},{notice=it},{id->onContinuousPage(id);leaveContinuous()},onAppendPage=onAppendPage.takeIf{!readOnly},authorAllowed={readLock.canWrite},onZoom={zoom=it;showViewportHint(true)},onScroll={showViewportHint(false)},onObjectTap={pageId,id->if(!readOnly){pendingObject=pageId to id;onContinuousPage(pageId);leaveContinuous()}})}
        }else if(row!=null){
            val initial=remember(page.id){workspace.cachedViewport(page.id)?:row.takeIf{it.zoom>0}?.let{runCatching{CanvasViewport(it.centerX,it.centerY,it.zoom)}.getOrNull()}}
            Box(Modifier.fillMaxWidth().weight(1f)){
            key(page.id){AndroidView(factory={ctx->InkCanvasView(ctx).also{v->
                view=v;v.onStroke=vm::accept;v.onCheckpoint=vm::checkpoint;v.onCheckpointCancel=vm::cancelCheckpoint;v.onErase={path,radius,whole,only->if(eraser.onlyTape)objectsVm.eraseTapes(path,radius)else{vm.erasePath(path,radius,whole,only);if(!only)objectsVm.eraseBeauty(path,radius,whole)}};v.onGesture={if(it)cancelSourcePulse();gesture=it;readLock.guard("ink-gesture-${page.id}",it);if(!it&&tool==3&&eraser.returnToPen)tool=lastWritingTool}
                v.onNotice={notice=it;app.diagnostics.event(DiagnosticCode.INK_UI,DiagnosticResult.REJECTED)}
                v.finishStroke={polishNewStroke(it,beautyOptions)};v.onViewportGesture={cancelSourcePulse();showViewportHint(it)};v.onAxes={pressure,tilt->app.diagnostics.inputAxes(pressure,tilt);axes="本次输入：压力${if(pressure)"已上报"else"未上报"} · 倾斜${if(tilt)"已上报"else"未上报"}"}
                v.onObjectTap={id->if(readLock.canWrite){val o=objectsVm.ui.value.objects.find{it.id==id};if(o?.kind==PageObjectKind.TAPE)objectsVm.put(o.copy(revealed=!o.revealed))else{selectedObject=id;tool=5}}};v.onViewport={workspace.viewport(page.id,it)};v.onScale={zoom=it;val viewport=v.snapshotViewport();selectionViewport=viewport
                    pendingSourcePulse?.let{pulse->if(pulse.view!==v||pulse.viewport!=viewport){if(v.sourceInteractionEpoch!=pulse.interactionEpoch)cancelSourcePulse()else pendingSourcePulse=null}}
                    sourcePulse?.let{pulse->if(pulse.view!==v||pulse.viewport!=viewport)sourcePulse=null}}
            }},update={v->
                v.configure(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},initial)
                v.allowInput=externalEnabled&&!readOnly&&tool<4&&!(if(tool==3)objectsBlocked else inkObjectsBlocked)&&(if(tool==3)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart);v.eraserTapeOnly=eraser.onlyTape;v.eraserWhole=eraser.whole;v.eraserHighlighterOnly=eraser.onlyHighlighter;v.eraserDiameterDp=eraser.diameterDp;v.fingerWrites=finger&&!readOnly;v.eraseMode=tool==3;v.pen=kinds[tool.coerceIn(0,2)]
                v.brushRecipe=recipes[tool.coerceAtMost(2)];v.penWidth=widths[tool.coerceAtMost(2)];v.penColor=colors[tool.coerceAtMost(2)]
                v.showDocument(page.id);v.showStrokes(ui.strokes);v.showObjects(beautyPreviewObjects(objectsUi.objects,beautyReview))
                if(!gesture)v.selectionPreview((pendingSelection?.second?:selected?.strokes?.map{it.id}).orEmpty().toSet())
            },modifier=Modifier.fillMaxSize().onSizeChanged{canvasSize=it}.testTag("ink-surface"))
            sourcePulse?.takeIf{pulse->pulse.bookId==note.base.id&&pulse.pageId==page.id&&pulse.request==focusRequest&&
                pulse.view===view&&pulse.size==canvasSize&&pulse.viewport==selectionViewport&&!gesture&&!objectInteraction&&
                (focusRegion==null||pulse.bounds==focusRegion)}?.let{pulse->
                SourceFocusHighlight(pulse){finished->if(sourcePulse===finished)sourcePulse=null}
            }
            if(showExcerptMarkers)ExcerptMarkers(excerptRows.filter{it.pageId==page.id&&(tool!=4||!excerptMode||it.id!=selectedExcerpt)},selectionViewport)
            if(tool==7)AndroidView(factory={TapeOverlay(it)},update={v->
                v.canvasView=view;v.objects=objectsUi.objects;v.world=page.world;v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending
                v.pattern=TapePattern.entries[tapePatternIndex];v.mode=tapeMode;v.tapeWidth=tapeWidth;v.tapeColor=tapeColor;v.onActive={gesture=it;readLock.guard("ink-gesture-${page.id}",it)};v.onCreate=objectsVm::put;v.onToggle={objectsVm.put(it.copy(revealed=!it.revealed))}
            },modifier=Modifier.fillMaxSize().testTag("tape-overlay"))
            if(tool==5)AndroidView(factory={PageObjectOverlay(it)},update={v->
                v.canvasView=view;v.objects=objectsUi.objects.filterNot{it.hidden};v.selected=selectedObject;v.world=page.world
                v.enabledInput=editable&&!objectsUi.loading&&!objectsUi.busy&&!objectsUi.pending&&!objectInteraction
                v.onSelect={selectedObject=it;if(it==null)tool=lastWritingTool};v.onChange=objectsVm::put;v.onActive={gesture=it;readLock.guard("ink-gesture-${page.id}",it)};v.invalidate()
            },modifier=Modifier.fillMaxSize().testTag("object-overlay"))
            if(tool==5&&!readOnly){
                val o=objectsUi.objects.find{it.id==selectedObject}
                val region=o?.let{InkRegion(listOf(EraserPoint(it.x,it.y),EraserPoint(it.x+it.width,it.y+it.height)))}
                SelectionToolbar(region,selectionViewport){
                PageObjectTools(objectsVm,objectsUi,page.id,page.world,tool==5,editable&&!gesture,selectedObject,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it},request=objectRequest.takeIf{continuousPages==null},onRequestConsumed={objectRequest=null},onDone={selectedObject=null;tool=lastWritingTool},onEditMap={selectedObject=null;tool=lastWritingTool;onEditMap(it)})
                }
            }
            if(tool!=5)PageObjectTools(objectsVm,objectsUi,page.id,page.world,false,editable&&!gesture,null,{selectedObject=it},{objectInteraction=it},{view?.snapshotViewport()},{notice=it})
            if((tool==4||tool==6)&&selectedExcerpt==null)AndroidView(factory={SelectionOverlayView(it)},update={v->
                v.geometry=visibleGeometry;v.selectedObjects=mixedSelection?.objects.orEmpty().map{it.bounds()};v.objectIds=mixedSelection?.objects.orEmpty().map{it.id}.toSet();v.worldSelection=page.world;v.canvasView=view;v.region=mixedSelection?.region?:selected?.region;v.selected=mixedSelection?.strokes?:selected?.strokes.orEmpty();v.freehand=freehand;v.marker=excerptMode&&excerptMarkMode;v.enabledInput=editable
                v.onCaptureDrag=if(excerptMode&&captureToMap){{selected?.takeIf{it.preview!=null}?.let{s->CaptureTransfer(note.base.id,StudySourceDraft(page.id,s.revision,s.region.bounds,s.strokes.map{it.id},s.preview,s.objectRevision),s.excerptText)}}}else null
                v.onActive={gesture=it;readLock.guard("ink-gesture-${page.id}",it)};v.onRegion={region->if(excerptMode&&region!=null){
                    val b=region.bounds;val clipped=runCatching{CanvasBounds(b.left.coerceAtLeast(if(page.world)-BoardLimits.WORLD.toDouble() else 0.0),b.top.coerceAtLeast(if(page.world)-BoardLimits.WORLD.toDouble() else 0.0),b.right.coerceAtMost(if(page.world)BoardLimits.WORLD.toDouble() else 1000.0),b.bottom.coerceAtMost(if(page.world)BoardLimits.WORLD.toDouble() else 1414.0))}.getOrNull()
                    if(clipped!=null&&clipped.right>clipped.left&&clipped.bottom>clipped.top){
                        val exact=InkRegion(listOf(EraserPoint(clipped.left.toFloat(),clipped.top.toFloat()),EraserPoint(clipped.right.toFloat(),clipped.bottom.toFloat())))
                        selected=SelectedInk(exact,ui.revision,emptyList());captureExcerpt(selected!!)
                    }
                }else if(areaEraseMode&&tool==4&&region!=null){val ids=selectable.filter{(!eraser.onlyHighlighter||it.pen==InkPen.HIGHLIGHTER)&&it.bounds().intersects(region.bounds)}.map{it.id};if(ids.isNotEmpty())applySelected(ui.revision,InkMutation.Cut(EraseSelection(region.mask(),ids)));selected=null;if(eraser.returnToPen)tool=lastWritingTool}else{val found=region?.let{CanvasSelectionEdit.query(it,ui.revision,ui.strokes,objectsUi.objects,if(excerptMode||tool==6)SelectionOptions(setOf(SelectionType.INK,SelectionType.HIGHLIGHTER))else selectionOptions,visibleGeometry)};mixedSelection=found?.takeIf{tool==4&&!excerptMode&&it.objects.isNotEmpty()};selected=found?.takeIf{mixedSelection==null}?.let{SelectedInk(it.region,it.revision,it.strokes)};if(tool==6)selected?.let{beautify(it)};}}
                v.onTap={x,y->
                    if(excerptMode){selected=null;selectedExcerpt=excerptRows.lastOrNull{it.pageId==page.id&&x>=it.left&&x<=it.right&&y>=it.top&&y<=it.bottom}?.id}
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
            if(tool==4&&excerptMode&&!readOnly)selectedExcerpt?.let{id->
                ExcerptEditor(id,excerptVm,view,selectionViewport,page.world,ui.revision,objectsVm.revision,editable&&!objectsBlocked,{selectedExcerpt=null},{gesture=it},{excerptDraft=it})
            }
            if(tool==4&&excerptMode&&selected?.preview!=null&&!readOnly)SelectionToolbar(selected?.region,selectionViewport){
                Column(Modifier.padding(horizontal=8.dp,vertical=6.dp)){Text(if(selected?.excerptText.isNullOrBlank())"图片 / 手写摘录 · 保留原貌"else"文字摘要 · 同时保留原貌",style=MaterialTheme.typography.labelMedium,color=Quiet)
                    Row{TextButton({selected?.let{if(captureToMap)onMapExcerpt(it)else{onExcerpt(it);selected=null}}},enabled=editable,modifier=Modifier.testTag("capture-confirm")){Text(if(captureToMap)"摘录到导图"else"存入摘录匣")};TextButton({excerptJob?.cancel();selected=null}){Text("取消")}}}
            }
            if(tool==4&&!excerptMode&&!areaEraseMode&&!readOnly&&(selected!=null||mixedSelection!=null))SelectionToolbar(mixedSelection?.region?:selected?.region,selectionViewport){
                Column {

                    val mixed=mixedSelection
                    if(mixed!=null)MixedSelectionActions(mixed,editable&&!objectsBlocked,
                        copy={val b=(mixed.strokes.map{it.bounds()}+mixed.objects.map{it.bounds()}).reduce{a,v->a.union(v)}
                            val dx=if(page.world||b.right+24<=1000)24f else if(b.left>=24)-24f else 0f
                            val dy=if(page.world||b.bottom+24<=1414)24f else if(b.top>=24)-24f else 0f
                            moveMixed(mixed,dx,dy,true)},delete={editMixed(mixed,CanvasSelectionEdit.deleted(mixed))},edit={selectedObject=mixed.objects.single().id;mixedSelection=null;tool=5},scale={factor->runCatching{CanvasSelectionEdit.scaled(mixed,factor,page.world)}.onSuccess{editMixed(mixed,it)}.onFailure{notice="缩放超出页面、笔宽或对象尺寸限制，原内容保留"}},dismiss={mixedSelection=null})
                    else SelectionActions(selected,selectable.filter{selectionOptions.accepts(it)},editable&&!objectsBlocked,freehand,{freehand=it;selectionOptions=selectionOptions.copy(precise=false,freehand=it);selectionStore.save(selectionOptions)},::applySelected,{selected=null},::captureExcerpt,onAssociate,{beautify(it)},onMapExcerpt)

                }
            }
            }}
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}

    }
        if(viewportHint!=0)Surface(Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp),shape=RoundedCornerShape(20.dp),color=Color.White.copy(alpha=.9f),border=BorderStroke(1.dp,Line)){
            Row(Modifier.padding(horizontal=14.dp,vertical=6.dp)){if(viewportHint==2)Text("${(zoom*100).toInt()}%",fontSize=12.sp,modifier=Modifier.testTag("ink-zoom"))else pageNavigation()}
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(horizontal=4.dp).widthIn(max=960.dp).fillMaxWidth().onSizeChanged{toolbarHeight=with(toolbarDensity){it.height.toDp()}},shape=InkTheme.FloatingShape,color=InkTheme.Navigation,shadowElevation=InkTheme.ToolElevation){
            Column {
            if(readOnly&&fullScreen)ReadingToolbar(
                enabled=navigationReady&&externalEnabled,fullScreen=fullScreen,
                onMap={onDocumentAction("map")},onExcerpts={onDocumentAction("excerpts")},onAssociate={onDocumentAction("associations")},onWrite={changeReadOnly(false)},
                onSearch={onSearch(ui.revision)},onOverview={onDocumentAction("overview")},
                onFullScreen={onFullScreen(!fullScreen)},onExport={if(page.world)confirmExport=true else onDocumentAction("export")},onTimer={timerOpen=true})
            else if(!readOnly)EditorToolbar(fullScreen=fullScreen) { action,closeOverflow -> when(action){
                "undo" -> IconButton(onClick={closeOverflow();if(historyHeads.undo==EditDomain.OBJECT)objectsVm.undo()else vm.undo()},enabled=(if(historyHeads.undo==EditDomain.OBJECT)objectsUi.undo else ui.canUndo)&&!editingBlocked,modifier=Modifier.size(48.dp).testTag("ink-undo").describedAs("撤销")){Glyph("undo")}
                "redo" -> IconButton(onClick={closeOverflow();if(historyHeads.redo==EditDomain.OBJECT)objectsVm.redo()else vm.redo()},enabled=(if(historyHeads.redo==EditDomain.OBJECT)objectsUi.redo else ui.canRedo)&&!editingBlocked,modifier=Modifier.size(48.dp).testTag("ink-redo").describedAs("重做")){Glyph("redo")}
                "pen" -> EditorTool("笔","pen",tool<3&&!readOnly,!busy,"top-draw",Modifier.describedAs("笔参数")){closeOverflow();if(!readOnly||changeReadOnly(false)){selectedObject=null;if(tool in 0..2){settings=true;penOpenRequest++}else{tool=lastWritingTool;penOpenRequest++}}}
                "eraser" -> EditorTool("橡皮","eraser",tool==3,!editingBlocked,"top-eraser",Modifier.toolAnchor("eraser").describedAs("橡皮").semantics{toggleableState=ToggleableState(tool==3)}){closeOverflow();if(tool==3){anchorFor("eraser");eraserDialog=true}else tool=3}
                "lasso" -> EditorTool("套索","select",tool==4&&!excerptMode&&!areaEraseMode,!editingBlocked&&!continuousBlocked,"ink-select",Modifier.toolAnchor("lasso").describedAs("套索").semantics{toggleableState=ToggleableState(tool==4&&!excerptMode&&!areaEraseMode)}){closeOverflow();if(tool==4&&!excerptMode&&!areaEraseMode){anchorFor("lasso");selectionSettings=true}else chooseSelection()}
                "area" -> IconButton(onClick={closeOverflow();chooseSelection(true,erase=true)},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("top-area-erase").describedAs("圈选擦除")){Glyph("area-erase")}
                "image" -> IconButton(onClick={closeOverflow();insertObject("image")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-image").describedAs("插入图片")){Glyph("image")}
                "camera" -> IconButton(onClick={closeOverflow();insertObject("camera")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-camera").describedAs("拍照")){Glyph("camera")}
                "text" -> IconButton(onClick={closeOverflow();insertObject("text")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-text").describedAs("文本框")){Glyph("text")}
                "map" -> EditorTool("导图","mindmap",false,!busy,"quick-study",Modifier.describedAs("笔记导图")){closeOverflow();onDocumentAction("map")}
                "excerpt" -> EditorTool("摘录","excerpt",tool==4&&excerptMode,!editingBlocked&&!continuousBlocked,"top-excerpt",Modifier.toolAnchor("excerpt").describedAs("摘录")){closeOverflow();if(tool==4&&excerptMode){anchorFor("excerpt");excerptSettings=true}else chooseSelection(excerpt=true)}
                "tag" -> IconButton(onClick=onTags,enabled=!editingBlocked,modifier=Modifier.testTag("top-tags").describedAs("笔记标签")){Glyph("tag")}
                "shape" -> IconButton(onClick={closeOverflow();insertObject("shape")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-shape").describedAs("图形")){Glyph("shape")}
                "sticker" -> IconButton(onClick={closeOverflow();insertObject("sticker")},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("object-sticker").describedAs("贴纸与符号")){Glyph("sticker")}
                "objects" -> IconToggleButton(tool==5,{closeOverflow();if(continuousPages!=null)leaveContinuous();selectedObject=null;tool=if(tool==5)lastWritingTool else 5},enabled=!editingBlocked&&!continuousBlocked,modifier=Modifier.testTag("page-objects").describedAs("选择图片与文字")){Glyph("objects")}
                "favorites" -> IconToggleButton(favoritesOpen,{closeOverflow();showFavorites(it)},enabled=!editingBlocked,modifier=Modifier.testTag("favorite-pens-toggle").describedAs("收藏笔")){Glyph("favorite-pens")}
                "beauty" -> IconButton(onClick={closeOverflow();beautySettings=true;penOpenRequest++},enabled=!editingBlocked,modifier=Modifier.testTag("quick-beauty").describedAs("实时字迹调整")){Glyph("beauty")}
                "readonly" -> IconToggleButton(readOnly,{closeOverflow();changeReadOnly(it)},modifier=Modifier.testTag("quick-readonly").describedAs("只读模式")){Glyph("readonly")}
                "finger" -> EditorTool(if(finger)"手指书写"else"手指移动","finger",finger,!editingBlocked&&!continuousBlocked,"quick-finger",Modifier.describedAs(if(finger)"手指书写，单指书写，双指移动和缩放；点击切换手指移动"else"手指移动，单指移动，双指缩放；点击切换手指书写").semantics{toggleableState=ToggleableState(finger)}){closeOverflow();finger=!finger;inputPrefs.edit().putBoolean("finger-writes",finger).apply();tool=lastWritingTool}
                "add-page" -> IconButton(onClick={closeOverflow();onDocumentAction("add-page")},enabled=!editingBlocked&&canAddPage,modifier=Modifier.testTag("quick-add-page").describedAs("添加页面")){Glyph("add-page")}
                "overview" -> IconButton(onClick={closeOverflow();onDocumentAction("overview")},enabled=!busy,modifier=Modifier.size(48.dp).testTag("quick-overview").describedAs("文档概览")){Glyph("overview",modifier=Modifier.size(24.dp))}
                "settings" -> IconButton(onClick={closeOverflow();onDocumentAction("settings")},enabled=!busy,modifier=Modifier.size(48.dp).testTag("quick-settings").describedAs("其他设置")){Glyph("settings",modifier=Modifier.size(24.dp))}
                "fullscreen" -> IconToggleButton(fullScreen,{closeOverflow();onFullScreen(it)},enabled=!busy,modifier=Modifier.testTag("quick-fullscreen").describedAs("全屏专注")){Glyph("fullscreen")}
                "export" -> IconButton(onClick={closeOverflow();if(page.world)confirmExport=true else onDocumentAction("export")},enabled=!busy,modifier=Modifier.testTag("quick-export").describedAs("导出文档")){Glyph("export")}
                "timer" -> IconButton(onClick={closeOverflow();timerOpen=true},modifier=Modifier.testTag("quick-timer").describedAs("计时器")){Glyph("timer")}
            }}
            val inkStatus=when {
                ui.readFailed||ui.blocked!=null->"笔迹需核对"
                readOnly->"阅读模式"
                continuousPages!=null->"连续页模式"
                ui.loading->"正在读取笔迹"
                gesture->"待抬笔"
                ui.processing||ui.queued>0->"正在保存笔迹"
                else->"本页笔迹已保存"
            }
            // Reserve one line in every state. This does not report object or beauty-review saves.
            Box(Modifier.fillMaxWidth().height(maxOf(24.dp,with(toolbarDensity){18.sp.toDp()})).padding(horizontal=12.dp),contentAlignment=Alignment.CenterEnd){
                Text(inkStatus,Modifier.testTag("ink-save-status"),fontSize=11.sp,lineHeight=14.sp,maxLines=1,
                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(ui.readFailed||ui.blocked!=null)MaterialTheme.colorScheme.error else Quiet)
            }
            }
        }
        if(fullScreen)TextButton(onClick={onFullScreen(false)},modifier=Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("exit-fullscreen")){Text("退出全屏")}
        if(!readOnly)toolbar()
        if(favoritesOpen&&!readOnly)FloatingPenCase("favorites",wide=true,topInset=toolbarHeight){
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
        if(!readOnly)Surface(Modifier.align(Alignment.TopCenter).padding(start=72.dp,end=8.dp,top=toolbarHeight).widthIn(max=620.dp),shape=RoundedCornerShape(12.dp),shadowElevation=3.dp){
            Column {
                if(beautyReview!=null&&beautyReview?.open!=true)TextButton(objectsVm::openBeauty,modifier=Modifier.testTag("beauty-review-open")){Text("美化待校对")}
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
    beautyReview?.takeIf{it.open}?.let{BeautyReviewPanel(it,objectsVm)}
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
    if(excerptSettings)EditorPanel("摘要笔","",{excerptSettings=false},"excerpt-settings",kind=PanelKind.SETTINGS){
        Column(Modifier.verticalScroll(rememberScrollState())){
            Row{FilterChip(captureToMap,{captureToMap=true;excerptPrefs.edit().putBoolean("to-map",true).apply()},label={Text("导图")},modifier=Modifier.heightIn(min=48.dp).testTag("capture-destination-map"));FilterChip(!captureToMap,{captureToMap=false;excerptPrefs.edit().putBoolean("to-map",false).apply()},label={Text("摘录匣")},modifier=Modifier.heightIn(min=48.dp).testTag("capture-destination-inbox"))}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                FilterChip(!excerptTextMode&&!excerptMarkMode,{excerptTextMode=false;excerptMarkMode=false;freehand=false;excerptPrefs.edit().putBoolean("text",false).apply()},label={Text("圈选")},modifier=Modifier.heightIn(min=48.dp).testTag("excerpt-region-mode"))
                FilterChip(excerptMarkMode,{excerptTextMode=false;excerptMarkMode=true;freehand=true;excerptPrefs.edit().putBoolean("text",false).apply()},label={Text("标记")},modifier=Modifier.heightIn(min=48.dp).testTag("excerpt-mark-mode"))
                FilterChip(excerptTextMode,{excerptTextMode=true;excerptMarkMode=false;freehand=false;excerptPrefs.edit().putBoolean("text",true).apply()},label={Text("文字")},modifier=Modifier.heightIn(min=48.dp).testTag("excerpt-text-mode"))
            }
            Row(verticalAlignment=Alignment.CenterVertically){Text("显示摘录标记",Modifier.weight(1f));Switch(showExcerptMarkers,{showExcerptMarkers=it;excerptPrefs.edit().putBoolean("markers",it).apply()},modifier=Modifier.testTag("excerpt-markers"))}
            TextButton({excerptSettings=false;onDocumentAction("excerpts")},modifier=Modifier.testTag("excerpt-open-list")){Text("查看本笔记摘录")}
        }
    }

    if(selectionSettings)SelectionSettings(selectionOptions,freehand,{selectionSettings=false},all={selectAll()}){value,free->selectionOptions=value.copy(freehand=free);selectionStore.save(selectionOptions);freehand=free;selected=null;mixedSelection=null}
    if(mixedRetry)AlertDialog(onDismissRequest={},title={Text("编辑保存待核对")},text={Text("原操作已保留，请先核对保存结果。")},confirmButton={TextButton({mixedWriter.retry(app.inkRepository)}){Text("核对重试")}},dismissButton={TextButton({discardMixed=true}){Text("读取已保存页")}})
    if(discardMixed)AlertDialog(onDismissRequest={discardMixed=false},text={Text("放弃未确认的编辑草稿，重新读取已保存内容？")},confirmButton={TextButton({discardMixed=false;pendingMixed=null;mixedSelection=null;mixedWriter.readSaved()}){Text("读取")}},dismissButton={TextButton({discardMixed=false}){Text("取消")}})
    if(eraserDialog)EraserDialog(eraser,{eraserDialog=false},circle={eraserDialog=false;chooseSelection(erase=true)}){next->eraser=next;eraserStore.save(next)}
    }
    if(showPaperPicker)PaperPickerDialog(PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},row.world,{showPaperPicker=false}){style->if(readLock.canWrite)workspace.paper(row.noteId,style);showPaperPicker=false}
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出可编辑页面副本")},text={Text("包括图片原件（可能带有拍摄元数据）、文本框、胶带状态、可见笔迹、纸张/无界形式、纸面样式和当前文字（可能含未确认内容）。明文 .iwpage，不是整库备份，不包含隐藏笔迹、撤销历史、分类、视图位置和回执。所选位置可能属于云盘。")},confirmButton={TextButton(onClick={confirmExport=false;val r=row?:return@TextButton;scope.launch{try{val source=withContext(Dispatchers.IO){app.documents.read(page.id)};require(objectsUi.objects.none{it.mapEmbed?.policy==MapEmbedPolicy.LIVE}){"LIVE_MAP_REQUIRES_FULL_BACKUP_OR_SNAPSHOT"};exportPending=InkPageFile(note.title.ifBlank{"笔记"},note.text,ui.strokes,r.world,PaperStyle.entries.getOrElse(r.paper){PaperStyle.RULED},objectsUi.objects,source,withContext(Dispatchers.IO){app.pageObjects.originals(page.id,objectsUi.objects)});launcher.launch("墨织页面.iwpage")}catch(c:CancellationException){throw c}catch(e:Exception){notice=e.mapExportExplanation()?:"页面源文件未能读取，没有导出残缺副本。"}}}){Text("选择保存位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认笔迹？")},text={Text("只重新读取已保存内容。建议先导出副本，已保存笔迹不会删除。")},confirmButton={TextButton(onClick={discard=false;vm.discardRejectedDraft()}){Text("放弃草稿并读取")}},dismissButton={TextButton(onClick={discard=false}){Text("取消")}})
}
