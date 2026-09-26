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
    val whole:Boolean,val highlighterOnly:Boolean,val diameter:Float,val enabled:Boolean)

/** Only visible pages have native views. Visited writers remain observed until their saves settle. */
@Composable internal fun ContinuousPages(pages:List<NotebookPageRow>,selected:String,tools:ContinuousTools,
    writing:Boolean,onSelect:(String)->Unit,onGesture:(Boolean)->Unit,onBlocked:(Boolean)->Unit,onNotice:(String)->Unit,onRepair:(String)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val state=rememberLazyListState(initialFirstVisibleItemIndex=pages.indexOfFirst{it.id==selected}.coerceAtLeast(0))
    val models=remember{mutableStateMapOf<String,InkViewModel>()}
    var gestureOwner by remember{mutableStateOf<String?>(null)}
    var reported by remember{mutableStateOf(selected)}
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPages by rememberUpdatedState(pages)
    var pending=false
    models.values.forEach{model->val ui by model.ui.collectAsStateWithLifecycle();if(ui.loading||ui.queued>0||ui.processing||ui.blocked!=null||ui.readFailed)pending=true}
    SideEffect{onBlocked(pending)}
    LaunchedEffect(selected,pages.map{it.id}){
        val index=pages.indexOfFirst{it.id==selected}
        if(index>=0&&selected!=reported&&!writing){reported=selected;state.animateScrollToItem(index)}
    }
    LaunchedEffect(state){snapshotFlow{
        val info=state.layoutInfo;val mid=(info.viewportStartOffset+info.viewportEndOffset)/2
        info.visibleItemsInfo.minByOrNull{kotlin.math.abs(it.offset+it.size/2-mid)}?.key as? String
    }.distinctUntilChanged().collect{id->if(id!=null&&gestureOwner==null&&latestPages.any{it.id==id}){reported=id;latestSelect(id)}}}
    LazyColumn(state=state,userScrollEnabled=!writing,modifier=Modifier.fillMaxSize().background(Color(0xffeef1f3)).testTag("continuous-pages"),
        contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
        items(pages,key={it.id}){page->
            val model:InkViewModel=viewModel(key="ink-${page.id}",factory=InkViewModel.Factory(page.id,app.inkRepository))
            val ui by model.ui.collectAsStateWithLifecycle()
            SideEffect{models[page.id]=model}
            Column(Modifier.widthIn(max=760.dp).fillMaxWidth().padding(horizontal=16.dp)){
                Text("第 ${page.position+1} 页 · "+PaperTemplates.title(PaperStyle.entries[page.paper]),fontSize=11.sp,color=Quiet,modifier=Modifier.padding(bottom=6.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1000f/1414f).background(Color.White).testTag("continuous-page-${page.position+1}")){
                    AndroidView(factory={ctx->InkCanvasView(ctx).apply{embeddedPage=true;tag="ink-page-${page.id}"}},update={v->
                        v.configure(false,PaperStyle.entries[page.paper],null)
                        v.allowInput=tools.enabled&&(if(tools.erasing)!ui.loading&&!ui.readFailed&&ui.blocked==null&&!ui.processing&&ui.queued<16 else ui.canStart)&&(gestureOwner==null||gestureOwner==page.id)
                        v.pen=tools.pen;v.penColor=tools.color;v.penWidth=tools.width
                        v.eraseMode=tools.erasing;v.eraserWhole=tools.whole;v.eraserHighlighterOnly=tools.highlighterOnly;v.eraserDiameterDp=tools.diameter
                        v.onStroke=model::accept;v.onErase=model::erasePath
                        v.onGesture={active->if(active){gestureOwner=page.id;reported=page.id;latestSelect(page.id)}else if(gestureOwner==page.id)gestureOwner=null;onGesture(active)}
                        v.onAxes=app.diagnostics::inputAxes;v.onNotice=onNotice
                        v.showStrokes(ui.strokes)
                    },modifier=Modifier.fillMaxSize().testTag("continuous-ink-${page.position+1}"))
                    if(ui.loading)CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if(ui.readFailed)TextButton(onClick=model::load,modifier=Modifier.align(Alignment.Center)){Text("读取失败，重试此页")}
                }
                if(ui.blocked!=null)TextButton(onClick={onRepair(page.id)},enabled=!writing){Text("此页保存待核对 · 打开处理",fontSize=12.sp,color=Color(0xff984c24))}
            }
        }
    }
}
