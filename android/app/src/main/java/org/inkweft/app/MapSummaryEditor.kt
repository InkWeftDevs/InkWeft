// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import org.inkweft.data.*

internal data class MapSummaryDraft(val stamp:String,val value:KnowledgeData.MapSummaryGroup,val old:KnowledgeRow?)
internal val MapSummaryDraftSaver=androidx.compose.runtime.saveable.Saver<MapSummaryDraft?,List<String>>(
    save={d->d?.let{listOf(it.stamp,android.util.Base64.encodeToString(KnowledgeCodec.encode(it.value),android.util.Base64.NO_WRAP),it.old?.id.orEmpty(),it.old?.notebookId.orEmpty(),it.old?.revision?.toString().orEmpty(),it.old?.let{r->android.util.Base64.encodeToString(r.payload,android.util.Base64.NO_WRAP)}.orEmpty())}?:emptyList()},
    restore={v->if(v.isEmpty())null else{require(v.size==6);MapSummaryDraft(v[0],KnowledgeCodec.decode(android.util.Base64.decode(v[1],android.util.Base64.NO_WRAP)) as KnowledgeData.MapSummaryGroup,
        v[2].takeIf{it.isNotEmpty()}?.let{KnowledgeRow(it,v[3],v[4].toLong(),android.util.Base64.decode(v[5],android.util.Base64.NO_WRAP))})}}
)


@Composable internal fun MapSummaryEditor(book:String,value:KnowledgeData.MapSummaryGroup,old:KnowledgeRow?,writer:KnowledgeViewModel,ui:KnowledgeUi,current:Boolean,embedded:Boolean,dismiss:()->Unit){
    var label by rememberSaveable(old?.id,value.memberIds){mutableStateOf(value.label)}
    var operation by rememberSaveable{mutableStateOf<String?>(null)}
    val waiting=ui.busy||ui.unknown
    val latest=old?.let{base->ui.rows.find{it.id==base.id}}
    val conflict=old!=null&&(latest==null||latest.removed||latest.revision!=old.revision)
    val enabled=current&&!conflict&&!ui.loading&&!ui.readFailed&&!waiting
    LaunchedEffect(operation,ui.completedOperation){operation?.let{if(ui.completedOperation==it){writer.consumed(it);dismiss()}}}
    StudyDialog(embedded,onDismissRequest={if(!waiting)dismiss()},title={Text(if(old==null)"新增括号归纳"else"编辑括号归纳")},modifier=Modifier.testTag("map-summary-editor"),text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("归纳当前图中 ${value.memberIds.size} 个连续同级主题；保留原主题、卡片和出处。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(label,{label=it.take(120)},label={Text("归纳标题")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("map-summary-label"))
            Text("成员保持完整，移动整组请多选所有成员；若要拆开层级或删除成员，请先解除归纳。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            if(!current||conflict)Text("图或归纳已变化，草稿保留。请取消后重新核对。",color=MaterialTheme.colorScheme.error)
            if(old!=null)TextButton({operation=writer.submit(book,old.data(),old,remove=true)},enabled=enabled,modifier=Modifier.testTag("map-summary-dissolve")){Text("解除归纳，保留全部主题")}
            ui.message?.takeIf{operation!=null||waiting}?.let{Text(it)}
        }
    },confirmButton={TextButton({if(ui.unknown)writer.retry()else operation=writer.submit(book,value.copy(label=label.trim()),old)},enabled=!ui.busy&&(ui.unknown||(enabled&&label.isNotBlank())),modifier=Modifier.testTag("map-summary-save")){Text(if(ui.unknown)"核对原操作"else"保存归纳")}},dismissButton={TextButton(dismiss,enabled=!waiting){Text("取消")}})
}
