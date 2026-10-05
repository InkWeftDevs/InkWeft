// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import org.inkweft.core.AnnotationTarget

/** Occurrence-local handwriting is deliberately separate from the card's shared text annotation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BoundAnnotationPanel(model:PageAuthoringViewModel,state:AuthoringUi,target:AnnotationTarget,
    title:String,targetBounds:CanvasBounds?,enabled:Boolean,dismiss:()->Unit){
    var layerPanel by remember{mutableStateOf(false)};var deleteRegion by remember{mutableStateOf(false)};var notice by remember{mutableStateOf<String?>(null)}
    val scopeLabel=if(model.scope.kind==AuthoringScopeKind.MAP)"当前导图画布"else"当前页"
    val referenceWidth=(targetBounds?.let{it.right-it.left}?:1000.0).coerceIn(1.0,4000.0)
    val members=state.state.annotations.filter{it.target==target}
    val region=state.state.regions.firstOrNull{it.target==target}
    val regionScale=if(region!=null&&targetBounds!=null)(targetBounds.right-targetBounds.left)/region.referenceWidth else 1.0
    EditorPanel("$title · 手写批注","${if(target.kind==AnnotationTargetKind.PAGE)"游离笔迹归属$scopeLabel"else"绑定当前稳定出现位置，移动和缩放时跟随"}",{if(!state.pending&&!model.writing)dismiss()},"bound-annotation"){
        Column(Modifier.verticalScroll(rememberScrollState())){
            FlowRow {
                TextButton({layerPanel=true},modifier=Modifier.testTag("annotation-layers")){Text("图层")}
                TextButton(model::undo,enabled=enabled&&state.ready&&!model.writing&&state.undo,modifier=Modifier.testTag("annotation-history-undo")){Text("撤销")}
                TextButton(model::redo,enabled=enabled&&state.ready&&!model.writing&&state.redo,modifier=Modifier.testTag("annotation-history-redo")){Text("重做")}
                if(targetBounds!=null)TextButton({model.change{s->
                    val loose=s.annotations.filter{it.target.kind==AnnotationTargetKind.PAGE&&s.layers.editable(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))}
                    loose.fold(s){current,a->current.replaceAnnotation(a.rebound(AnnotationFrame(0.0,0.0),target,AnnotationFrame(targetBounds.right+24.0,targetBounds.top),referenceWidth))}
                }},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-bind")){Text("将可编辑游离批注绑定到此处")}
            }
            Text("这里保存原笔记批注；共享卡片文字注释另行编辑。回忆辅助作答不会写入这里。",style=MaterialTheme.typography.bodySmall)
            if(targetBounds!=null){
                if(region==null)TextButton({model.change{it.withRegion(AnnotationRegion(target,referenceWidth=referenceWidth))}},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-region-create")){Text("创建此处批注区")}
                else {
                    FlowRow {
                        TextButton({model.change{it.withRegion(region.copy(collapsed=!region.collapsed))}},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-region-collapse")){Text(if(region.collapsed)"展开批注区"else"折叠批注区")}
                        TextButton({deleteRegion=true},enabled=enabled&&state.ready&&!model.writing){Text("删除批注区…")}
                    }
                    if(!region.collapsed){
                        var width by remember(region.width){mutableFloatStateOf(region.width.toFloat())};var height by remember(region.height){mutableFloatStateOf(region.height.toFloat())}
                        Text("区域大小随目标同比缩放；缩小只裁显示，溢出笔迹不会删除",style=MaterialTheme.typography.bodySmall)
                        Text("宽度 ${width.toInt()}");Slider(width,{width=it},valueRange=80f..2000f,onValueChangeFinished={model.change{it.withRegion(region.copy(width=width.toDouble()))}},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-region-width"))
                        Text("高度 ${height.toInt()}");Slider(height,{height=it},valueRange=80f..2000f,onValueChangeFinished={model.change{it.withRegion(region.copy(height=height.toDouble()))}},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-region-height"))
                        AnnotationPad(model,state,target,enabled,height=region.height*regionScale,width=region.width*regionScale,referenceWidth=referenceWidth,onNotice={notice=it})
                    }else Text("批注区已折叠，笔迹完整保留；展开或重开可恢复")
                }
            }else AnnotationPad(model,state,target,enabled,referenceWidth=referenceWidth,onNotice={notice=it})
            if(targetBounds!=null&&members.isNotEmpty())TextButton({model.change{s->
                s.annotations.filter{it.target==target&&s.layers.editable(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))}.fold(s){current,a->
                    current.replaceAnnotation(a.detached(a.targetFrame(targetBounds),model.scope.id))
                }
            };notice="解绑后全部笔迹（含原区域溢出）冻结在原画布坐标，归属$scopeLabel，不再跟随对象或原区域裁剪"},enabled=enabled&&state.ready&&!model.writing,modifier=Modifier.testTag("annotation-unbind")){Text("解绑可编辑笔迹（含溢出）至$scopeLabel")}
            notice?.let{Text(it,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)}
            state.message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            AuthoringRecoveryActions(model,state)
        }
    }
    if(deleteRegion&&region!=null)AlertDialog(onDismissRequest={deleteRegion=false},title={Text("删除此批注区？")},text={Text("将删除此区域及其 ${members.size} 笔批注（包括隐藏与溢出部分），可撤销。锁定层内容必须先解锁。目标卡片或对象本身不删除。")},confirmButton={TextButton({deleteRegion=false;model.change{s->
        val removed=s.annotations.filter{it.target==target}.map{LayerContent(LayerContentKind.ANNOTATION,it.stroke.id)}
        require(removed.none{s.layers.layer(it)?.locked==true}){"LAYER_UNLOCK_BEFORE_DELETE"}
        PageAuthoring(UserLayers(s.layers.layers,s.layers.currentId,s.layers.memberships.filterNot{it.content in removed},s.layers.deleted.filterNot{it in removed}),s.blanks,s.annotations.filterNot{it.target==target},s.regions.filterNot{it.target==target})
    }}){Text("删除区域及批注")}},dismissButton={TextButton({deleteRegion=false}){Text("取消")}})
    if(layerPanel)PageLayersPanel(model,state,enabled,members.filter{state.state.layers.visible(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))}.map{LayerContent(LayerContentKind.ANNOTATION,it.stroke.id)},dismiss={layerPanel=false})
}
