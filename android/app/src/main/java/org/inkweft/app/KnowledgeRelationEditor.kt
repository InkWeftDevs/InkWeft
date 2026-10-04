// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.inkweft.core.*
import org.inkweft.data.*

@Composable internal fun KnowledgeRelationEditor(initial:KnowledgeRow,dismiss:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val writer:KnowledgeViewModel=viewModel(key="relation-edit-${initial.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    BindKnowledgeReadLock(writer)
    val ui by writer.ui.collectAsStateWithLifecycle()
    val lock=rememberBookReadLock(initial.notebookId);val readOnly by lock.readOnly.collectAsStateWithLifecycle()
    val original=initial.data() as KnowledgeData.Link
    val latest=ui.rows.firstOrNull{it.id==initial.id&&it.data() is KnowledgeData.Link}
    val link=(latest?.data() as? KnowledgeData.Link)?:original
    var baseRevision by rememberSaveable(initial.id){mutableLongStateOf(initial.revision)}
    var relation by rememberSaveable(initial.id){mutableStateOf(original.relation)}
    var style by rememberSaveable(initial.id){mutableStateOf(original.lineStyle)}
    var direction by rememberSaveable(initial.id){mutableStateOf(original.direction)}
    var annotation by rememberSaveable(initial.id){mutableStateOf(original.annotation)}
    var visible by rememberSaveable(initial.id){mutableStateOf(original.visible)}
    var operation by rememberSaveable(initial.id){mutableStateOf<String?>(null)}
    val waiting=ui.busy||ui.unknown
    val available=latest?.removed==false&&ui.notes.any{it.id==initial.notebookId}
    val conflict=latest!=null&&latest.revision!=baseRevision
    val editable=available&&!readOnly&&!ui.loading&&!ui.readFailed&&!waiting&&!conflict
    ReadLockGuard(lock,"relation-edit-${initial.id}",blocked=true,draft=true)
    LaunchedEffect(ui.completedOperation,operation){operation?.let{if(ui.completedOperation==it){writer.consumed(it);dismiss()}}}
    fun label(ref:TargetRef)=when(ref.kind){
        TargetKind.CARD->ui.cards.find{it.id==ref.id}?.title?:"卡片不可用"
        TargetKind.NOTE->ui.notes.find{it.id==ref.id}?.title?:"笔记不可用"
        TargetKind.PAGE->"来源页面"
        TargetKind.ANCHOR->"来源区域"
    }
    EditorPanel("编辑知识关系","",{if(!waiting)dismiss()},"relation-editor",footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            TextButton({if(!waiting)dismiss()},enabled=!waiting){Text("取消")}
            TextButton({if(ui.unknown)writer.retry()else if(editable){
                operation=writer.submit(initial.notebookId,link.copy(relation=relation,lineStyle=style,direction=direction,annotation=annotation,visible=visible),latest)
            }},enabled=!ui.busy&&(ui.unknown||editable),modifier=Modifier.testTag("relation-save")){Text(if(ui.unknown)"核对原操作"else"保存")}
        }
    }){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("${label(link.source)} → ${label(link.target)}",style=MaterialTheme.typography.titleMedium)
            Text("知识关系独立于父子层级。显示设置不删除关系。",style=MaterialTheme.typography.bodySmall)
            Text("关系类型")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){RelationKind.entries.forEach{value->FilterChip(relation==value,{relation=value},enabled=editable,label={Text(value.label)},modifier=Modifier.testTag("relation-kind-${value.name}"))}}
            Text("线型与方向")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                RelationLineStyle.entries.forEach{value->FilterChip(style==value,{style=value},enabled=editable,label={Text(value.label)},modifier=Modifier.testTag("relation-style-${value.name}"))}
                RelationDirection.entries.forEach{value->FilterChip(direction==value,{direction=value},enabled=editable,label={Text(value.label)},modifier=Modifier.testTag("relation-direction-${value.name}"))}
            }
            OutlinedTextField(annotation,{if(it.length<=2000)annotation=it},enabled=editable,label={Text("关系注释")},minLines=2,maxLines=6,modifier=Modifier.fillMaxWidth().testTag("relation-annotation"))
            Row{Checkbox(visible,{visible=it},enabled=editable,modifier=Modifier.testTag("relation-visible"));Text("在图中显示这条关系",Modifier.padding(top=12.dp))}
            link.pinnedRevision?.let{Text("目标固定为修订 $it；来源仍使用当前内容。",style=MaterialTheme.typography.bodySmall)}
            TextButton({style=RelationLineStyle.DASHED;direction=RelationDirection.FORWARD;visible=true},enabled=editable){Text("恢复默认显示")}
            if(conflict){
                Text("关系已被更新，草稿保留。请核对上方当前起点、目标和最新注释：",modifier=Modifier.testTag("relation-conflict"))
                Text(link.annotation.ifBlank{"最新注释为空"})
                TextButton({baseRevision=latest!!.revision;operation=null},enabled=!waiting&&available&&!ui.readFailed){Text("保留草稿，按新版重新保存")}
            }
            if(!ui.loading&&!available)Text("关系或所属笔记不可用，未提交修改。")
            ui.message?.takeIf{operation!=null||waiting}?.let{Text(it,modifier=Modifier.testTag("relation-message"))}
            if(ui.readFailed)TextButton(writer::reload,enabled=!ui.busy){Text("重新读取，保留草稿")}
        }
    }
}
