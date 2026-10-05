// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.inkweft.core.*
import org.inkweft.core.AnnotationTarget
import java.util.UUID

/** Native ink in local coordinates. The view crop changes, never the stored overflow samples. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AnnotationPad(model:PageAuthoringViewModel,state:AuthoringUi,target:AnnotationTarget,
    enabled:Boolean,height:Double=300.0,width:Double=1000.0,referenceWidth:Double=1000.0,onNotice:(String)->Unit={}) {
    var finger by remember{mutableStateOf(false)};var eraser by remember{mutableStateOf(false)}
    var writing by remember{mutableStateOf(false)};var captured by remember{mutableStateOf<LayerWriteScope?>(null)}
    val lock=rememberBookReadLock(model.scope.notebookId)
    ReadLockGuard(lock,"annotation-${model.scope.id}-${target.id}",writing||state.busy||state.pending,writing)
    val annotations=state.state.annotations.filter{it.target==target&&state.state.layers.visible(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))}
    val padAnnotations=annotations.map{a->val scale=if(target.kind==AnnotationTargetKind.PAGE)1.0 else referenceWidth/a.referenceWidth
        a.copy(target=AnnotationTarget(AnnotationTargetKind.PAGE,model.scope.id),localFrame=AnnotationFrame(0.0,0.0,scale).compose(a.localFrame))}
    val padState=PageAuthoring(state.state.layers,annotations=padAnnotations)
    Column {
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            FilterChip(finger,{finger=!finger},label={Text("手指书写")},enabled=enabled&&!writing)
            FilterChip(eraser,{eraser=!eraser},label={Text("橡皮")},enabled=enabled&&!writing)
            TextButton(model::undo,enabled=enabled&&state.undo&&!writing){Text("撤销批注／图层")}
        }
        BoxWithConstraints(Modifier.fillMaxWidth()){
            val region=CanvasBounds(0.0,0.0,width,height)
            AndroidView(factory={InkCanvasView(it).apply{
                configure(true,PaperStyle.BLANK,null)
            }},update={view->
                view.configure(true,PaperStyle.BLANK,null);view.fixedRegion(region)
                view.allowInput=enabled&&state.canWrite;view.fingerWrites=finger;view.eraseMode=eraser;view.eraserWhole=true;view.penWidth=3f
                view.showAuthoring(padState);view.showStrokes(emptyList());view.onNotice=onNotice
                view.onWriteStart={runCatching{captured=model.scopeForWrite();true}.getOrElse{false}}
                view.onGesture={writing=it;model.writing=it}
                view.onStroke={stroke->val scope=checkNotNull(captured);model.writing=false;check(model.change({s->s.layers.checkWrite(scope,scope.configurationRevision);s.add(BoundAnnotation(stroke,target,referenceWidth))},expectedRevision=scope.configurationRevision)){"ANNOTATION_NOT_ACCEPTED"}}
                view.onErase={path,radius,whole,_->val scope=checkNotNull(captured);model.writing=false;model.change({s->
                    s.layers.checkWrite(scope,scope.configurationRevision)
                    val touched=s.annotations.filter{it.target==target&&s.layers.editable(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))&&run{val a=it;val scale=if(target.kind==AnnotationTargetKind.PAGE)1.0 else referenceWidth/a.referenceWidth;val frame=AnnotationFrame(0.0,0.0,scale).compose(a.localFrame);val inverse=frame.inverse()
                        val local=path.map{p->inverse.project(CanvasPoint(p.x.toDouble(),p.y.toDouble())).let{point->p.copy(x=point.x.toFloat(),y=point.y.toFloat(),world=true)}}
                        InkHitTest.hits(a.stroke,local,(radius/frame.scale).toFloat())}}
                    if(whole){val removed=touched.map{LayerContent(LayerContentKind.ANNOTATION,it.stroke.id)};s.withLayers(UserLayers(s.layers.layers,s.layers.currentId,s.layers.memberships.filterNot{it.content in removed},s.layers.deleted+removed))}
                    else touched.fold(s){current,a->current.replaceAnnotation(a.copy(stroke=a.stroke.withCuts(listOf(InkCut(UUID.randomUUID().toString(),radius,path.map{EraserPoint(it.x,it.y)})))))}
                },expectedRevision=scope.configurationRevision)}
            },modifier=Modifier.fillMaxWidth().height((maxWidth.value*height/width).coerceAtLeast(70.0).dp).testTag("annotation-pad-${target.id}"))
        }
        if(!state.canWrite)Text(if(state.busy||state.pending||state.loading)"批注尚未保存或待核对，暂不能继续落笔"else"落笔已暂停，请选择可写图层",color=MaterialTheme.colorScheme.error)
        Text("原笔记批注 · 仅当前出现位置；隐藏或锁定层不会被擦除",style=MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DocumentWhitespacePanel(pageId:String,paper:PaperStyle,ink:List<InkStroke>,objects:List<PageObject>,model:PageAuthoringViewModel,
    state:AuthoringUi,enabled:Boolean,embedded:Boolean=false,dismiss:()->Unit){
    var anchor by remember{mutableFloatStateOf(707f)}
    var layers by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    val layout=remember(state.state.blanks){DocumentWhitespaceLayout(state.state.blanks)}
    val context=LocalContext.current
    val preferences=remember(context){context.getSharedPreferences("inkweft-document-view",0)}
    val listState=rememberLazyListState(preferences.getInt("$pageId.index",0),preferences.getInt("$pageId.offset",0))
    val writerDraft by rememberBookReadLock(model.scope.notebookId).hasDraft.collectAsStateWithLifecycle()
    DisposableEffect(pageId,listState){onDispose{preferences.edit().putInt("$pageId.index",listState.firstVisibleItemIndex).putInt("$pageId.offset",listState.firstVisibleItemScrollOffset).apply()}}
    val content:@Composable ()->Unit={
        Surface(Modifier.fillMaxSize().testTag("document-whitespace-panel")){
            Column(if(embedded)Modifier else Modifier.safeDrawingPadding()){
                FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("whitespace-header"),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.Center){
                    Text("含留白展开视图",Modifier.align(androidx.compose.ui.Alignment.CenterVertically).testTag("whitespace-title"))
                    Row {
                        TextButton({layers=true},modifier=Modifier.testTag("whitespace-layers")){Text("图层")}
                        TextButton(dismiss,enabled=!writerDraft&&!state.busy&&!state.pending,modifier=Modifier.testTag("whitespace-original")){Text("原页视图")}
                    }
                }
                Text("原 PDF 与源锚保持原坐标。此处展示展开留白，原页模式与原页尺寸的可见分享不含留白。",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                if(enabled)Row(Modifier.padding(horizontal=12.dp)){
                    Slider(anchor,{anchor=it},valueRange=0f..1414f,enabled=state.ready&&!writerDraft,modifier=Modifier.weight(1f))
                    TextButton({val y=anchor.toDouble();model.change{it.withBlanks(it.blanks+DocumentWhitespace(UUID.randomUUID().toString(),y))}},enabled=state.ready&&!writerDraft&&state.state.blanks.size<DocumentWhitespaceLayout.MAX_BLANKS,modifier=Modifier.testTag("whitespace-add")){Text("在 ${(anchor/14.14f).toInt()}% 处留白")}
                }
                notice?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                LazyColumn(Modifier.fillMaxWidth().weight(1f),state=listState){
                    var previous=0.0
                    for(blank in layout.blanks){
                        if(blank.beforeY>previous){val bounds=CanvasBounds(0.0,previous,1000.0,blank.beforeY);item("source-${blank.id}"){OriginalDocumentSlice(pageId,paper,ink,objects,state.state,bounds)}}
                        item(blank.id){
                            Column(Modifier.padding(horizontal=8.dp)){
                                Row {
                                    Text("留白 · 原页 ${(blank.beforeY/14.14).toInt()}%",Modifier.weight(1f))
                                    TextButton({model.change{s->s.withBlanks(s.blanks.map{if(it.id==blank.id)it.copy(collapsed=!it.collapsed)else it})}},enabled=enabled&&state.ready&&!writerDraft,modifier=Modifier.testTag("whitespace-collapse-${blank.id}")){Text(if(blank.collapsed)"展开"else"折叠")}
                                }
                                if(!blank.collapsed){
                                    var height by remember(blank.height){mutableFloatStateOf(blank.height.toFloat())}
                                    if(enabled)Slider(height,{height=it},valueRange=80f..2000f,onValueChangeFinished={model.change{s->s.withBlanks(s.blanks.map{if(it.id==blank.id)it.copy(height=height.toDouble())else it})}},enabled=state.ready&&!writerDraft,modifier=Modifier.testTag("whitespace-resize-${blank.id}"))
                                    AnnotationPad(model,state,AnnotationTarget(AnnotationTargetKind.WHITESPACE,blank.id),enabled,blank.height,onNotice={notice=it})
                                }else Text("笔迹仍完整保存，展开即可恢复",style=MaterialTheme.typography.bodySmall)
                            }
                        };previous=blank.beforeY
                    }
                    if(previous<1414.0){val bounds=CanvasBounds(0.0,previous,1000.0,1414.0);item("source-last"){OriginalDocumentSlice(pageId,paper,ink,objects,state.state,bounds)}}
                }
                state.message?.let{Text(it,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)}
                AuthoringRecoveryActions(model,state)
            }
        }
    }
    if(embedded)content()else Dialog(onDismissRequest={if(!writerDraft&&!state.pending)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){content()}
    if(layers)PageLayersPanel(model,state,enabled,dismiss={layers=false})
}

@Composable private fun OriginalDocumentSlice(pageId:String,paper:PaperStyle,ink:List<InkStroke>,objects:List<PageObject>,authoring:PageAuthoring,bounds:CanvasBounds){
    BoxWithConstraints(Modifier.fillMaxWidth()){
        AndroidView(factory={InkCanvasView(it).apply{embeddedPage=true;configure(false,paper,null)}},update={view->
            view.embeddedPage=true;view.allowInput=false;view.fixedRegion(bounds);view.showAuthoring(authoring)
            view.showDocument(pageId);view.showStrokes(ink);view.showObjects(objects)
        },modifier=Modifier.fillMaxWidth().height((maxWidth.value*(bounds.bottom-bounds.top)/1000).coerceAtLeast(1.0).dp))
    }
}
