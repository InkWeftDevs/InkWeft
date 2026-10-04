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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.inkweft.core.*
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PageLayersPanel(model:PageAuthoringViewModel,state:AuthoringUi,enabled:Boolean,selection:List<LayerContent> = emptyList(),dismiss:()->Unit){
    var rename by remember{mutableStateOf<UserLayer?>(null)}
    var name by remember{mutableStateOf("")}
    var deleting by remember{mutableStateOf<UserLayer?>(null)}
    var transferring by remember{mutableStateOf<List<LayerContent>?>(null)}
    val ready=enabled&&state.ready&&!model.writing
    EditorPanel("图层","仅当前页／画布；至少保留一个可写层",{if(!state.pending&&!model.writing)dismiss()},"page-layers",kind=PanelKind.SETTINGS){
        Column(Modifier.verticalScroll(rememberScrollState())){
            Text(if(state.state.layers.currentId==null)"已暂停落笔，请明确选择可写层" else "当前可写层：${state.state.layers.layers.first{it.id==state.state.layers.currentId}.name}",modifier=Modifier.testTag("current-writable-layer"))
            FlowRow {
                TextButton({model.layers{it.add(UserLayer(UUID.randomUUID().toString(),"图层 ${it.layers.size+1}"))}},enabled=ready,modifier=Modifier.testTag("layer-add")){Text("新建图层")}
                TextButton(model::undo,enabled=ready&&state.undo,modifier=Modifier.testTag("layer-undo")){Text("撤销图层／批注")}
                TextButton(model::redo,enabled=ready&&state.redo,modifier=Modifier.testTag("layer-redo")){Text("重做")}
            }
            Text("列表从底层到顶层。隐藏仅影响显示，锁定限制编辑；回忆遮罩另行处理。",style=MaterialTheme.typography.bodySmall)
            if(selection.isNotEmpty())TextButton({transferring=selection},enabled=ready&&selection.all{state.state.layers.editable(it)},modifier=Modifier.testTag("layer-transfer-selection")){Text("选中的 ${selection.size} 项转层（含关联原迹）")}
            state.state.layers.layers.forEachIndexed{index,layer->
                HorizontalDivider(Modifier.padding(vertical=6.dp))
                Text("${index+1}. ${layer.name}${if(layer.locked)" · 已锁定"else""}${if(!layer.visible)" · 已隐藏"else""}")
                FlowRow {
                    TextButton({model.layers{it.select(layer.id)}},enabled=ready&&layer.writable,modifier=Modifier.testTag("layer-select-${layer.id}")){Text(if(state.state.layers.currentId==layer.id)"当前层"else"选为可写层")}
                    TextButton({rename=layer;name=layer.name},enabled=ready){Text("命名")}
                    TextButton({model.layers{it.move(layer.id,index-1)}},enabled=ready&&index>0){Text("下移")}
                    TextButton({model.layers{it.move(layer.id,index+1)}},enabled=ready&&index<state.state.layers.layers.lastIndex){Text("上移")}
                }
                FlowRow {
                    TextButton({model.layers{it.update(layer.copy(visible=!layer.visible))}},enabled=ready,modifier=Modifier.testTag("layer-visible-${layer.id}")){Text(if(layer.visible)"隐藏"else"显示")}
                    TextButton({model.layers{it.update(layer.copy(locked=!layer.locked))}},enabled=ready,modifier=Modifier.testTag("layer-lock-${layer.id}")){Text(if(layer.locked)"解锁"else"锁定")}
                    TextButton({transferring=state.state.layers.memberships.filter{it.layerId==layer.id}.map{it.content}},enabled=ready&&layer.writable){Text("内容转层")}
                    TextButton({deleting=layer},enabled=ready,modifier=Modifier.testTag("layer-delete-${layer.id}")){Text("删除层")}
                }
            }
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
