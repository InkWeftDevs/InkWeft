// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.flow.distinctUntilChanged
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

internal data class ContinuousTools(val pen:InkPen,val color:Int,val width:Float,val erasing:Boolean,
    val whole:Boolean,val highlighterOnly:Boolean,val diameter:Float,val enabled:Boolean,val beauty:BeautyOptions=BeautyOptions())

/** Only visible pages have native views. Visited writers remain observed until their saves settle. */
@Composable internal fun ContinuousPages(pages:List<NotebookPageRow>,selected:String,tools:ContinuousTools,
    writing:Boolean,onSelect:(String)->Unit,onGesture:(Boolean)->Unit,onBlocked:(Boolean)->Unit,onNotice:(String)->Unit,onRepair:(String)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val state=rememberLazyListState(initialFirstVisibleItemIndex=pages.indexOfFirst{it.id==selected}.coerceAtLeast(0))
    val models=remember{mutableStateMapOf<String,InkViewModel>()}
    val objectModels=remember{mutableStateMapOf<String,PageObjectViewModel>()}
    val views=remember{mutableMapOf<String,InkCanvasView>()}
    val writer:ContinuousInkWriter=viewModel(key="continuous-writer-${pages.firstOrNull()?.notebookId}")
    val groupBlocked by writer.blocked.collectAsStateWithLifecycle()
    val groupRetry by writer.needsRetry.collectAsStateWithLifecycle()
    var gestureOwner by remember{mutableStateOf<String?>(null)}
    var reported by remember{mutableStateOf(selected)}
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPages by rememberUpdatedState(pages)
    var pending=false
    models.values.forEach{model->val ui by model.ui.collectAsStateWithLifecycle();if(ui.loading||ui.queued>0||ui.processing||ui.blocked!=null||ui.readFailed)pending=true}
    objectModels.values.forEach{model->val ui by model.ui.collectAsStateWithLifecycle();if(ui.loading||ui.busy||ui.pending)pending=true}
    SideEffect{onBlocked(pending||groupBlocked);writer.onNotice=onNotice}
    LaunchedEffect(selected,pages.map{it.id}){
        val index=pages.indexOfFirst{it.id==selected}
        if(index>=0&&selected!=reported&&!writing){reported=selected;state.animateScrollToItem(index)}
    }
    LaunchedEffect(state){snapshotFlow{
        val info=state.layoutInfo;val mid=(info.viewportStartOffset+info.viewportEndOffset)/2
        info.visibleItemsInfo.minByOrNull{kotlin.math.abs(it.offset+it.size/2-mid)}?.key as? String
    }.distinctUntilChanged().collect{id->if(id!=null&&gestureOwner==null&&latestPages.any{it.id==id}){reported=id;latestSelect(id)}}}
    Box(Modifier.fillMaxSize()){
    LazyColumn(state=state,userScrollEnabled=!writing,modifier=Modifier.fillMaxSize().background(Color(0xffeef1f3)).testTag("continuous-pages"),
        contentPadding=PaddingValues(0.dp),verticalArrangement=Arrangement.spacedBy(0.dp),horizontalAlignment=Alignment.CenterHorizontally){
        items(pages,key={it.id}){page->
            val model:InkViewModel=viewModel(key="ink-${page.id}",factory=InkViewModel.Factory(page.id,app.inkRepository))
            val ui by model.ui.collectAsStateWithLifecycle()
            val objectModel:PageObjectViewModel=viewModel(key="objects-${page.id}",factory=PageObjectViewModel.Factory(page.id,app.pageObjects))
            val objectUi by objectModel.ui.collectAsStateWithLifecycle()
            SideEffect{models[page.id]=model;objectModels[page.id]=objectModel;objectModel.history=model.history;model.suppressedIds=objectUi.objects.flatMap{it.sourceStrokeIds}.toSet()}
            AutomaticBeautyBinding(objectModel,ui,writing,tools.beauty,false,app)
            Column(Modifier.fillMaxWidth()){

                Box(Modifier.fillMaxWidth().aspectRatio(1000f/1414f).background(Color.White).testTag("continuous-page-${page.position+1}")){
                    AndroidView(factory={ctx->InkCanvasView(ctx).apply{embeddedPage=true;seamWriting=true;tag="ink-page-${page.id}"}},update={v->
                        v.configure(false,PaperStyle.entries[page.paper],null)
                        views[page.id]=v
                        v.allowInput=!groupBlocked&&tools.enabled&&!objectUi.loading&&!objectUi.pending&&!objectUi.busy&&(if(tools.erasing)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart)&&(gestureOwner==null||gestureOwner==page.id)
                        v.pen=tools.pen;v.penColor=tools.color;v.penWidth=tools.width
                        v.eraseMode=tools.erasing;v.eraserWhole=tools.whole;v.eraserHighlighterOnly=tools.highlighterOnly;v.eraserDiameterDp=tools.diameter
                        v.onStroke={stroke->
                            val origin=pages.indexOfFirst{it.id==page.id}
                            val pieces=ContinuousInk.split(stroke,origin,pages.size)
                            val targets=pieces.map{(index,ink)->checkNotNull(models[pages[index].id]) to ink}
                            writer.accept(targets,app.inkRepository)
                        }
                        v.onLiveSamples={samples->
                            val origin=pages.indexOfFirst{it.id==page.id}
                            views.forEach{(id,other)->if(id!=page.id){val index=pages.indexOfFirst{it.id==id};other.seamDraft=if(samples.isEmpty())emptyList()else samples.map{it.copy(y=it.y+(origin-index)*1414f,world=true)}}}
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
                            writer.erase(targets,radius,whole,only,app.inkRepository)
                        }
                        v.onGesture={active->if(active){gestureOwner=page.id;reported=page.id;latestSelect(page.id)}else if(gestureOwner==page.id)gestureOwner=null;onGesture(active)}
                        v.onAxes=app.diagnostics::inputAxes;v.onNotice=onNotice
                        v.showDocument(page.id);v.showStrokes(ui.strokes);v.showObjects(objectUi.objects)
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
    var readSaved by remember{mutableStateOf(false)}
    if(groupRetry)Row(Modifier.align(Alignment.BottomCenter).background(Color.White)){
        TextButton(onClick={writer.retry(app.inkRepository)},modifier=Modifier.testTag("seam-retry")){Text("跨页保存 · 核对重试")}
        TextButton(onClick={readSaved=true}){Text("读取已保存页")}
    }
    if(readSaved)AlertDialog(onDismissRequest={readSaved=false},title={Text("读取已保存页？")},text={Text("放弃这次未确认草稿，重新读取所有相关页。已提交的内容会保留。")},confirmButton={TextButton(onClick={readSaved=false;writer.readSaved()}){Text("读取")}},dismissButton={TextButton(onClick={readSaved=false}){Text("取消")}})
    }
}
