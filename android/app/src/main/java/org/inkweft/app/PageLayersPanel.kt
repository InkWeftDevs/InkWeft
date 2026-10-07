// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.*
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PageLayersPanel(model:PageAuthoringViewModel,state:AuthoringUi,enabled:Boolean,selection:List<LayerContent> = emptyList(),
    strokes:List<InkStroke> = emptyList(),objects:List<PageObject> = emptyList(),dismiss:()->Unit){
    var showHelp by remember{mutableStateOf(false)}
    var rename by remember{mutableStateOf<UserLayer?>(null)}
    var name by remember{mutableStateOf("")}
    var deleting by remember{mutableStateOf<UserLayer?>(null)}
    var transferring by remember{mutableStateOf<List<LayerContent>?>(null)}
    val ready=enabled&&state.ready&&!model.writing
    val layers=state.state.layers
    // Inspecting a locked/hidden row never selects it as the author's writable layer.
    var inspectedId by rememberSaveable(model.scope){mutableStateOf(layers.currentId)}
    val selectedId=inspectedId?.takeIf{id->layers.layers.any{it.id==id}}?:layers.currentId
    EditorPanel("图层","",{if(!state.pending&&!model.writing)dismiss()},"page-layers",kind=PanelKind.LAYERS,actions={
        EditorAction("新建图层","add",ready&&layers.layers.size<UserLayers.MAX_LAYERS,"layer-add"){
            val layer=UserLayer(UUID.randomUUID().toString(),"图层 ${layers.layers.size+1}")
            if(model.layers{it.add(layer)})inspectedId=layer.id
        }
    },footer=if(state.undo||state.redo)({
        FlowRow {
            TextButton(model::undo,enabled=ready&&state.undo,modifier=Modifier.testTag("layer-undo")){Text("撤销图层／批注")}
            TextButton(model::redo,enabled=ready&&state.redo,modifier=Modifier.testTag("layer-redo")){Text("重做")}
        }
    })else null){
        Column(Modifier.verticalScroll(rememberScrollState())){
            Text(if(layers.currentId==null)"已暂停落笔，请明确选择可写层" else "当前可写层：${layers.layers.first{it.id==layers.currentId}.name}",
                style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("current-writable-layer").padding(bottom=8.dp))
            if(selection.isNotEmpty())TextButton({transferring=selection},enabled=ready&&selection.all{state.state.layers.editable(it)},modifier=Modifier.testTag("layer-transfer-selection")){Text("选中的 ${selection.size} 项转层（含关联原迹）")}
            layers.layers.forEachIndexed{index,layer->key(layer.id){
                var more by remember{mutableStateOf(false)}
                val members=remember(layers,layer.id){layers.memberships.filter{it.layerId==layer.id}.map{it.content}.toSet()}
                val previewInk=remember(strokes,state.state.annotations,members){
                    strokes.filter{LayerContent(LayerContentKind.INK,it.id) in members}+
                        state.state.annotations.filter{LayerContent(LayerContentKind.ANNOTATION,it.stroke.id) in members}.map{it.stroke}}
                val previewObjects=remember(objects,members){objects.filter{LayerContent(LayerContentKind.OBJECT,it.id) in members}}
                Surface(color=if(selectedId==layer.id)InkTheme.Selected else Color.Transparent,shape=RoundedCornerShape(10.dp),
                    modifier=Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("layer-row-${layer.id}")){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Row(Modifier.weight(1f).heightIn(min=64.dp).testTag("layer-select-${layer.id}")
                            .selectable(selectedId==layer.id,enabled=state.ready&&!model.writing,role=Role.Tab){
                                inspectedId=layer.id
                                if(ready&&layer.writable&&layers.currentId!=layer.id)model.layers{it.select(layer.id)}
                            }.describedAs(layer.name).semantics{stateDescription=when{
                                !layer.visible->"已隐藏";layer.locked->"已锁定";layers.currentId==layer.id->"当前可写层";else->"可写"}},
                            verticalAlignment=Alignment.CenterVertically){
                            LayerThumbnail(previewInk,previewObjects,Modifier.padding(start=4.dp,end=6.dp))
                            Text(layer.name,Modifier.weight(1f).testTag("layer-heading-${layer.id}"),
                                style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        EditorTool(if(layer.visible)"隐藏 ${layer.name}"else"显示 ${layer.name}",if(layer.visible)"eye"else"eye-off",!layer.visible,ready,"layer-visible-${layer.id}"){
                            model.layers{it.update(layer.copy(visible=!layer.visible))}}
                        EditorTool(if(layer.locked)"解锁 ${layer.name}"else"锁定 ${layer.name}",if(layer.locked)"lock"else"unlock",layer.locked,ready,"layer-lock-${layer.id}"){
                            model.layers{it.update(layer.copy(locked=!layer.locked))}}
                        Box{
                            EditorAction("${layer.name} 的更多操作","more",ready,"layer-more-${layer.id}"){inspectedId=layer.id;more=true}
                            DropdownMenu(more,{more=false}){
                                DropdownMenuItem(text={Text("命名")},onClick={more=false;rename=layer;name=layer.name},modifier=Modifier.testTag("layer-rename-${layer.id}"))
                                DropdownMenuItem(text={Text("上移")},onClick={more=false;model.layers{it.move(layer.id,index+1)}},enabled=ready&&index<layers.layers.lastIndex,modifier=Modifier.testTag("layer-up-${layer.id}"))
                                DropdownMenuItem(text={Text("下移")},onClick={more=false;model.layers{it.move(layer.id,index-1)}},enabled=ready&&index>0,modifier=Modifier.testTag("layer-down-${layer.id}"))
                                DropdownMenuItem(text={Text("内容转层")},onClick={more=false;transferring=members.toList()},enabled=ready&&layer.writable&&members.isNotEmpty(),modifier=Modifier.testTag("layer-transfer-${layer.id}"))
                                HorizontalDivider()
                                DropdownMenuItem(text={Text("删除层")},leadingIcon={Glyph("trash")},onClick={more=false;deleting=layer},enabled=ready,modifier=Modifier.testTag("layer-delete-${layer.id}"))
                            }
                        }
                    }
                }
            }}
            TextButton({showHelp=!showHelp},modifier=Modifier.testTag("layer-help")){Text(if(showHelp)"收起说明"else"图层说明")}
            if(showHelp)Text("至少保留一个可写层。列表从底层到顶层。隐藏仅影响显示，锁定限制编辑；回忆遮罩另行处理。点选隐藏或锁定层只浏览，不改变落笔目标。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("layer-help-content"))
            state.message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            AuthoringRecoveryActions(model,state)
        }
    }
    rename?.let{layer->AlertDialog(onDismissRequest={rename=null},title={Text("命名图层")},text={OutlinedTextField(name,{name=it.take(60)},singleLine=true,label={Text("图层名称")})},confirmButton={TextButton({model.layers{it.update(layer.copy(name=name.trim()))};rename=null},enabled=name.isNotBlank()){Text("保存")}},dismissButton={TextButton({rename=null}){Text("取消")}})}
    deleting?.let{layer->
        val count=state.state.layers.memberships.count{it.layerId==layer.id}
        AlertDialog(onDismissRequest={deleting=null},title={Text("删除“${layer.name}”？")},text={Column{
            Text(if(count==0)"空层可删除并撤销"else"该层含 $count 项归属（包含原迹历史）。请选择删除内容，或转至指定可写层后删除；可撤销。")
            if(count>0)state.state.layers.layers.filter{it.id!=layer.id&&it.writable}.forEach{target->TextButton({model.layers{it.remove(layer.id,LayerDelete.Transfer(target.id))};deleting=null},enabled=ready){Text("内容转至“${target.name}”后删除")}}
        }},confirmButton={TextButton({model.layers{it.remove(layer.id,if(count==0)null else LayerDelete.DeleteContents)};deleting=null},enabled=ready){Text(if(count==0)"删除空层"else"连同内容删除")}},dismissButton={TextButton({deleting=null}){Text("取消")}})
    }
    transferring?.let{ids->AlertDialog(onDismissRequest={transferring=null},title={Text("转移 ${ids.size} 项")},text={Column{
        Text("整组转移；隐藏或锁定成员会阻止整次操作。归属与叠放一同更改。")
        state.state.layers.layers.filter{it.writable}.forEach{target->TextButton({model.layers{it.transfer(ids,target.id)};transferring=null},enabled=ready&&ids.isNotEmpty()){Text("转至“${target.name}”")}}
    }},confirmButton={},dismissButton={TextButton({transferring=null}){Text("取消")}})}
}

/** Small geometry overview from already loaded author data; no image decoding or document writes. */
@Composable private fun LayerThumbnail(strokes:List<InkStroke>,objects:List<PageObject>,modifier:Modifier){
    val bounds=remember(strokes,objects){(strokes.map{it.bounds()}+objects.map{it.bounds()}).reduceOrNull{a,b->a.union(b)}}
    Surface(modifier.size(36.dp),color=Color.White,shape=RoundedCornerShape(4.dp),border=BorderStroke(1.dp,Line)){
        Canvas(Modifier.fillMaxSize().padding(3.dp)){
            val area=bounds?:return@Canvas
            val width=(area.right-area.left).toFloat().coerceAtLeast(1f)
            val height=(area.bottom-area.top).toFloat().coerceAtLeast(1f)
            val scale=minOf(size.width/width,size.height/height)
            val dx=(size.width-width*scale)/2
            val dy=(size.height-height*scale)/2
            val maskTransform=android.graphics.Matrix().apply{
                setScale(scale,scale);postTranslate(dx-area.left.toFloat()*scale,dy-area.top.toFloat()*scale)}
            fun point(x:Float,y:Float)=Offset(dx+(x-area.left.toFloat())*scale,dy+(y-area.top.toFloat())*scale)
            objects.takeLast(32).forEach{o->drawRect(Color(o.color).copy(alpha=.45f),point(o.x,o.y),Size(o.width*scale,o.height*scale),style=Stroke(1.dp.toPx()))}
            // Bound work even on a large layer; the full-resolution renderer remains on the page.
            strokes.takeLast(64).forEach{s->
                val path=Path();val first=s.samples.first();val origin=point(first.x,first.y);path.moveTo(origin.x,origin.y)
                val stride=((s.samples.size+127)/128).coerceAtLeast(1)
                s.samples.filterIndexed{i,_->i%stride==0||i==s.samples.lastIndex}.forEach{p->val v=point(p.x,p.y);path.lineTo(v.x,v.y)}
                drawIntoCanvas{canvas->
                    val native=canvas.nativeCanvas;val save=native.save()
                    try{
                        s.cuts.forEach{cut->native.clipOutPath(VisibleInkGeometry.cutPath(cut).apply{transform(maskTransform)})}
                        if(s.samples.size==1)drawCircle(Color(s.color),.75f,origin)
                        else drawPath(path,Color(s.color),style=Stroke((s.width*scale).coerceIn(.6f,2f)))
                    }finally{native.restoreToCount(save)}
                }
            }
        }
    }
}
