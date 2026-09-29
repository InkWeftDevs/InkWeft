// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

internal data class ContinuousTools(val pen:InkPen,val color:Int,val width:Float,val erasing:Boolean,
    val whole:Boolean,val highlighterOnly:Boolean,val diameter:Float,val enabled:Boolean,val beauty:BeautyOptions=BeautyOptions(),val recipe:BrushRecipe=BrushRecipe(),val onlyTape:Boolean=false,val fingerWrites:Boolean=false)

/** Only visible pages have native views. Visited writers remain observed until their saves settle. */
@Composable internal fun ContinuousPages(pages:List<NotebookPageRow>,selected:String,tools:ContinuousTools,
    writing:Boolean,onSelect:(String)->Unit,onGesture:(Boolean)->Unit,onBlocked:(Boolean)->Unit,onNotice:(String)->Unit,onRepair:(String)->Unit,onObjectTap:(String,String)->Unit={_,_->},onScroll:()->Unit={},onAppendPage:(()->Unit)?=null,onZoom:(Double)->Unit={}){
    if(pages.isEmpty())return
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val state=rememberLazyListState(initialFirstVisibleItemIndex=pages.indexOfFirst{it.id==selected}.coerceAtLeast(0))
    val dragged by state.interactionSource.collectIsDraggedAsState()
    val models=remember{mutableStateMapOf<String,InkViewModel>()}
    val objectModels=remember{mutableStateMapOf<String,PageObjectViewModel>()}
    val views=remember{mutableMapOf<String,InkCanvasView>()}
    val writer:ContinuousInkWriter=viewModel(key="continuous-writer-${pages.firstOrNull()?.notebookId}")
    val book=pages.first().notebookId
    val owner=checkNotNull(LocalViewModelStoreOwner.current)
    fun modelFor(id:String):InkViewModel=models.getOrPut(id){ViewModelProvider(owner,InkViewModel.Factory(id,app.inkRepository))["ink-$id",InkViewModel::class.java]}
    val recovery:ContinuousGroupSession=viewModel(key="continuous-recovery-$book",factory=ContinuousGroupSession.Factory(book,app.inkRepository))
    val recovering by recovery.busy.collectAsStateWithLifecycle()
    val recoveryProblem by recovery.problem.collectAsStateWithLifecycle()
    SideEffect{recovery.writer=writer;recovery.modelFor=::modelFor;recovery.notice=onNotice}
    LaunchedEffect(recovery){recovery.open()}
    val groupBlocked by writer.blocked.collectAsStateWithLifecycle()
    val groupRetry by writer.needsRetry.collectAsStateWithLifecycle()
    val drawingReady by writer.canWrite.collectAsStateWithLifecycle()
    val drafts by writer.drafts.collectAsStateWithLifecycle()
    var gestureOwner by remember{mutableStateOf<String?>(null)}
    var reported by remember{mutableStateOf(selected)}
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPages by rememberUpdatedState(pages)
    var pending=false
    models.values.forEach{model->val ui by model.ui.collectAsStateWithLifecycle();if(ui.loading||ui.queued>0||ui.processing||ui.blocked!=null||ui.readFailed)pending=true}
    objectModels.values.forEach{model->val ui by model.ui.collectAsStateWithLifecycle();if(ui.loading||ui.busy||ui.pending)pending=true}
    val latestAppend by rememberUpdatedState(onAppendPage)
    val latestAppendReady by rememberUpdatedState(tools.enabled&&!pending&&!groupBlocked&&!recovering&&recoveryProblem==null)
    val latestWriting by rememberUpdatedState(writing)
    val latestScroll by rememberUpdatedState(onScroll)
    val latestZoom by rememberUpdatedState(onZoom)
    val scope=rememberCoroutineScope()
    val density=LocalDensity.current
    val pull=remember(density){LastPagePull(with(density){72.dp.toPx()})}
    val gestureEpoch=remember{intArrayOf(0)}
    fun atEnd()=!state.canScrollForward&&state.layoutInfo.visibleItemsInfo.lastOrNull()?.key==latestPages.lastOrNull()?.id
    val pullConnection=remember(state,pull){object:NestedScrollConnection{
        override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset{
            if(source==NestedScrollSource.UserInput)pull.drag(-available.y,atEnd(),latestAppendReady&&!latestWriting)
            return Offset.Zero
        }
    }}
    SideEffect{onBlocked(pending||groupBlocked||recovering||recoveryProblem!=null);writer.onNotice=onNotice}
    LaunchedEffect(selected,pages.map{it.id}){
        val index=pages.indexOfFirst{it.id==selected}
        if(index>=0&&selected!=reported&&!writing){reported=selected;state.animateScrollToItem(index)}
    }
    LaunchedEffect(state){snapshotFlow{Triple(state.isScrollInProgress,state.firstVisibleItemIndex,state.firstVisibleItemScrollOffset)}.distinctUntilChanged().collect{if(it.first&&dragged)onScroll()}}
    LaunchedEffect(state){snapshotFlow{
        val info=state.layoutInfo;val mid=(info.viewportStartOffset+info.viewportEndOffset)/2
        info.visibleItemsInfo.minByOrNull{kotlin.math.abs(it.offset+it.size/2-mid)}?.key as? String
    }.distinctUntilChanged().collect{id->if(id!=null&&gestureOwner==null&&latestPages.any{it.id==id}){reported=id;latestSelect(id)}}}
    var paperZoom by remember(book){mutableFloatStateOf(1f)}
    var paperPanX by remember(book){mutableFloatStateOf(0f)}
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()){
    val pageWidth=maxWidth
    val viewportWidth=with(density){pageWidth.toPx()}
    Box(Modifier.fillMaxSize().nestedScroll(pullConnection).pointerInput(book,tools.fingerWrites,viewportWidth){
        awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            val epoch=++gestureEpoch[0]
            var stylus=down.type!=PointerType.Touch
            var twoFingers=false
            if(!stylus&&!tools.fingerWrites)pull.begin()else pull.cancel()
            try{
                while(true){
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    // Compose delivers ACTION_CANCEL as already-consumed synthetic releases.
                    if(event.changes.any{it.isConsumed}){pull.cancel();break}
                    val pressed=event.changes.filter{it.pressed}
                    if(event.changes.any{it.type!=PointerType.Touch})stylus=true
                    if(stylus)pull.cancel()
                    else if(pressed.size>=2){
                        if(!twoFingers){
                            // Cancel native ink before consuming the event, including any durable checkpoint.
                            views.values.forEach{it.cancelGesture()}
                            twoFingers=true;pull.begin();scope.launch{state.stopScroll()}
                        }
                        val common=event.changes.filter{it.pressed&&it.previousPressed}
                        if(common.size>=2){
                            val before=common.map{it.previousPosition}.reduce{a,b->a+b}/common.size.toFloat()
                            val after=common.map{it.position}.reduce{a,b->a+b}/common.size.toFloat()
                            val oldSpan=common.sumOf{(it.previousPosition-before).getDistance().toDouble()}
                            val newSpan=common.sumOf{(it.position-after).getDistance().toDouble()}
                            val nextZoom=(paperZoom*if(oldSpan>0.0)(newSpan/oldSpan).toFloat()else 1f).coerceIn(1f,3f)
                            val ratio=nextZoom/paperZoom
                            val limit=viewportWidth*(nextZoom-1f)/2f
                            paperPanX=(paperPanX*ratio+(before.x-viewportWidth/2f)*(1f-ratio)+after.x-before.x).coerceIn(-limit,limit)
                            if(kotlin.math.abs(ratio-1f)>.0001f){
                                val offset=((state.firstVisibleItemScrollOffset+before.y)*ratio-after.y).roundToInt()
                                paperZoom=nextZoom
                                state.requestScrollToItem(state.firstVisibleItemIndex,offset)
                                pull.cancel();pull.begin();latestZoom(nextZoom.toDouble())
                            }else{
                                val scroll=before.y-after.y
                                val consumed=state.dispatchRawDelta(scroll)
                                pull.drag(scroll-consumed,atEnd(),latestAppendReady)
                                if((after-before).getDistance()>1f)latestScroll()
                            }
                        }
                        event.changes.forEach{it.consume()}
                    }else if(twoFingers)event.changes.forEach{it.consume()}
                    if(pressed.isEmpty()){
                        val tail=latestPages.last().id
                        if(pull.release())scope.launch{
                            // Ink cancellation updates the parent's navigation gate through composition.
                            withFrameNanos{};withFrameNanos{}
                            if(gestureEpoch[0]==epoch&&latestPages.lastOrNull()?.id==tail&&atEnd()&&latestAppendReady&&!latestWriting)latestAppend?.invoke()
                        }
                        break
                    }
                }
            }finally{pull.cancel()}
        }
    }){
    LazyColumn(state=state,userScrollEnabled=!writing&&!tools.fingerWrites,modifier=Modifier.requiredWidth(pageWidth*paperZoom).fillMaxHeight().offset{IntOffset(paperPanX.roundToInt(),0)}.background(Color.White).testTag("continuous-pages"),
        contentPadding=PaddingValues(0.dp),verticalArrangement=Arrangement.spacedBy(0.dp),horizontalAlignment=Alignment.CenterHorizontally){
        items(pages,key={it.id}){page->
            val model:InkViewModel=viewModel(key="ink-${page.id}",factory=InkViewModel.Factory(page.id,app.inkRepository))
            val ui by model.ui.collectAsStateWithLifecycle()
            val objectModel:PageObjectViewModel=viewModel(key="objects-${page.id}",factory=PageObjectViewModel.Factory(page.id,app.pageObjects))
            val objectUi by objectModel.ui.collectAsStateWithLifecycle()
            val beautyReview by objectModel.beautyReview.collectAsStateWithLifecycle()
            SideEffect{models[page.id]=model;objectModels[page.id]=objectModel;objectModel.history=model.history;model.suppressedIds=objectUi.objects.flatMap{it.sourceStrokeIds}.toSet()}
            AutomaticBeautyBinding(objectModel,ui,writing||groupBlocked||recovering||recoveryProblem!=null,tools.beauty,false,app)
            Column(Modifier.fillMaxWidth()){

                Box(Modifier.fillMaxWidth().aspectRatio(1000f/1414f).background(Color.White).testTag("continuous-page-${page.position+1}").pointerInput(page.id,tools.enabled,tools.erasing,tools.fingerWrites){detectTapGestures{point->if(tools.enabled&&!tools.erasing&&!tools.fingerWrites)views[page.id]?.imageAt(point.x,point.y)?.let{id->val o=objectModel.ui.value.objects.find{it.id==id};if(o?.kind==PageObjectKind.TAPE)objectModel.put(o.copy(revealed=!o.revealed))else onObjectTap(page.id,id)}}}){
                    AndroidView(factory={ctx->InkCanvasView(ctx).apply{embeddedPage=true;seamWriting=true;tag="ink-page-${page.id}"}},update={v->
                        v.configure(false,PaperStyle.entries[page.paper],null)
                        v.fingerWrites=tools.fingerWrites
                        views[page.id]=v
                        v.allowInput=!recovering&&recoveryProblem==null&&(if(tools.erasing)!groupBlocked else drawingReady)&&tools.enabled&&!objectUi.loading&&!objectUi.pending&&!objectUi.busy&&(if(tools.erasing)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart)&&(gestureOwner==null||gestureOwner==page.id)
                        v.finishStroke={polishNewStroke(it,tools.beauty)}
                        v.pen=tools.pen;v.penColor=tools.color;v.penWidth=tools.width;v.brushRecipe=tools.recipe
                        v.eraserTapeOnly=tools.onlyTape;v.eraseMode=tools.erasing;v.eraserWhole=tools.whole;v.eraserHighlighterOnly=tools.highlighterOnly;v.eraserDiameterDp=tools.diameter
                        v.onObjectTap={id->val o=objectModel.ui.value.objects.find{it.id==id};if(o?.kind==PageObjectKind.TAPE)objectModel.put(o.copy(revealed=!o.revealed))else onObjectTap(page.id,id)}
                        v.onCheckpointCancel={id->model.cancelCheckpoint(id);recovery.cancel(id)}
                        v.onCheckpoint=recovery::checkpoint
                        v.onStroke={stroke->
                            val origin=pages.indexOfFirst{it.id==page.id}
                            val pieces=ContinuousInk.split(stroke,origin,pages.size)
                            if(pieces.size>1||stroke.samples.size>=128){recovery.finish(v.capturedStroke(),stroke)}
                            else{val targets=pieces.map{(index,ink)->checkNotNull(models[pages[index].id]) to ink};writer.accept(targets,app.inkRepository)}
                            pieces.forEach{(index,ink)->ink.forEach{views[pages[index].id]?.retainLiveStroke(it)}}
                        }
                        v.onLiveSamples={samples->
                            val origin=pages.indexOfFirst{it.id==page.id}
                            views.forEach{(id,other)->if(id!=page.id){val index=pages.indexOfFirst{it.id==id};val offset=(origin-index)*1414f
                                val crosses=samples.isNotEmpty()&&samples.any{it.y+offset in -tools.width*3..1414f+tools.width*3}
                                if(crosses)other.seamDraft=v.liveStroke(samples.map{it.copy(y=it.y+offset,world=true)},offset)
                                else if(other.seamDraft!=null)other.seamDraft=null}}
                        }
                        v.onErase={path,radius,whole,only->
                            val origin=pages.indexOfFirst{it.id==page.id}
                            val first=kotlin.math.floor((origin*1414f+path.minOf{it.y}-radius)/1414f).toInt().coerceIn(0,pages.lastIndex)
                            val last=kotlin.math.floor((origin*1414f+path.maxOf{it.y}+radius)/1414f).toInt().coerceIn(0,pages.lastIndex)
                            val targets=(first..last).mapNotNull{index->val target=pages[index]
                                val shifted=path.map{it.copy(y=it.y+(origin-index)*1414f,world=true)}
                                if(shifted.maxOf{it.y}+radius<0||shifted.minOf{it.y}-radius>1414f)null
                                else PageEraseTarget(checkNotNull(models[target.id]),checkNotNull(objectModels[target.id]),shifted)
                            }
                            writer.erase(targets,radius,whole,only,app.inkRepository,tools.onlyTape)
                        }
                        v.onGesture={active->if(active){recovery.begin(pages.map{it.id},page.id);gestureOwner=page.id;reported=page.id;latestSelect(page.id)}else if(gestureOwner==page.id)gestureOwner=null;onGesture(active)}
                        v.onAxes=app.diagnostics::inputAxes;v.onNotice=onNotice
                        v.showDocument(page.id);v.showStrokes(ui.strokes+drafts[model].orEmpty());v.showObjects(beautyPreviewObjects(objectUi.objects,beautyReview))
                    },modifier=Modifier.fillMaxSize().testTag("continuous-ink-${page.position+1}"))
                    DisposableEffect(page.id){onDispose{views.remove(page.id)}}
                    if(ui.loading)CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if(objectUi.error!=null)TextButton(onClick={if(objectUi.pending)onRepair(page.id)else objectModel.reload()},modifier=Modifier.align(Alignment.BottomCenter)){Text(if(objectUi.pending)"内容保存待核对 · 打开处理"else"对象读取失败，重试")}
                    if(ui.readFailed)TextButton(onClick=model::load,modifier=Modifier.align(Alignment.Center)){Text("读取失败，重试此页")}
                }
                if(ui.blocked!=null)TextButton(onClick={onRepair(page.id)},enabled=!writing){Text("此页保存待核对 · 打开处理",fontSize=12.sp,color=Color(0xff984c24))}
            }
        }
    }
    }
    var readSaved by remember{mutableStateOf(false)}
    var discardRecovery by remember{mutableStateOf(false)}
    if(recoveryProblem!=null)Column(Modifier.align(Alignment.BottomCenter).background(Color.White).padding(8.dp)){
        Text(recoveryProblem!!,style=MaterialTheme.typography.bodySmall)
        Row{TextButton(onClick=recovery::retry,enabled=!recovering,modifier=Modifier.testTag("group-recovery-retry")){Text("核对重试")};TextButton(onClick={discardRecovery=true},enabled=!recovering){Text("保留已保存内容")}}
    }
    if(discardRecovery)AlertDialog(onDismissRequest={discardRecovery=false},title={Text("放弃未确认的跨页草稿？")},text={Text("已提交的原内容保留，不复活回收页。未确认的恢复草稿将被持久取消。")},confirmButton={TextButton(onClick={discardRecovery=false;recovery.discard()}){Text("确认")}},dismissButton={TextButton(onClick={discardRecovery=false}){Text("取消")}})
    if(groupRetry)Row(Modifier.align(Alignment.BottomCenter).background(Color.White)){
        TextButton(onClick={writer.retry(app.inkRepository)},modifier=Modifier.testTag("seam-retry")){Text("跨页保存 · 核对重试")}
        TextButton(onClick={readSaved=true}){Text("读取已保存页")}
    }
    if(readSaved)AlertDialog(onDismissRequest={readSaved=false},title={Text("读取已保存页？")},text={Text("放弃这次未确认草稿，重新读取所有相关页。已提交的内容会保留。")},confirmButton={TextButton(onClick={readSaved=false;writer.readSaved()}){Text("读取")}},dismissButton={TextButton(onClick={readSaved=false}){Text("取消")}})
    }
}
