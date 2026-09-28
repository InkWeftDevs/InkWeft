// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.inkweft.core.*
import org.inkweft.data.*

/** Destination-only UI: never creates StudyContent or a hidden map canvas. */
@Composable internal fun CaptureDestination(draft:CaptureDraft,writer:StudyViewModel,dismiss:()->Unit,
    sent:(MapRef,String?)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val maxHeight=(LocalConfiguration.current.screenHeightDp*.82f).dp.coerceAtMost(560.dp)
    val prefs=remember{app.getSharedPreferences("inkweft-map-destinations",0)}
    val graphFlow=remember(draft.notebookId){app.mapGraphs.observe(draft.notebookId)}
    val scenes by graphFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val state by writer.ui.collectAsStateWithLifecycle()
    val mapWriter:KnowledgeViewModel=viewModel(key="destination-map-${draft.notebookId}",factory=KnowledgeViewModel.Factory(app.knowledge))
    val mapWrite by mapWriter.ui.collectAsStateWithLifecycle()
    var selected by rememberSaveable(draft){mutableStateOf<String?>(null)}
    var parent by rememberSaveable(draft){mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    var pinned by remember{mutableStateOf(prefs.getStringSet("${draft.notebookId}-pins",emptySet()).orEmpty().toSet())}
    var newTitle by rememberSaveable{mutableStateOf<String?>(null)}
    val lastKey=prefs.getString("${draft.notebookId}-last",null)
    val lastParent=prefs.getString("${draft.notebookId}-parent",null)
    val recent=prefs.getString("${draft.notebookId}-recent","").orEmpty().split(',')
    val scene=scenes.find{it.ref.key==selected&&it.available}
    val ready=!state.busy&&!state.unknown&&!mapWrite.busy&&!mapWrite.unknown
    fun submit(target:MapScene,branch:String?){
        if(!ready||!target.available||branch!=null&&target.nodes.none{it.id==branch})return
        val node=target.nodes.find{it.id==branch}
        val y=node?.let{n->target.nodes.filter{it.parentId==n.id}.maxOfOrNull{it.y+128}?:n.y}?:target.nodes.size*128.0+80
        val command=draft.command(target.ref,branch,target.graphHash,node?.let{if(it.x<=39740)it.x+260 else it.x-260}?:40.0,y.coerceIn(-40000.0,40000.0))
        sent(target.ref,branch);writer.submit(command)
    }
    LaunchedEffect(mapWrite.completed){mapWrite.completed?.let{selected=it;parent=null;newTitle=null;mapWriter.consumed()}}
    androidx.compose.ui.window.Popup(alignment=Alignment.CenterEnd,onDismissRequest={if(ready)dismiss()},properties=androidx.compose.ui.window.PopupProperties(focusable=true)){
        Surface(Modifier.imePadding().padding(12.dp).widthIn(max=380.dp).fillMaxWidth().heightIn(max=maxHeight).testTag("capture-destination"),color=Color.White,
            shape=RoundedCornerShape(16.dp),shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
            Column(Modifier.padding(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("添加到导图",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(dismiss,enabled=ready,modifier=Modifier.size(48.dp).describedAs("关闭目的地选择")){Glyph("close")}}
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())){
                    if(selected==null){
                        scenes.find{it.available&&it.ref.key==lastKey&&(lastParent==null||it.nodes.any{n->n.id==lastParent})}?.let{last->
                            TextButton({submit(last,lastParent)},enabled=ready,modifier=Modifier.fillMaxWidth().testTag("capture-last-destination")){Text("添加到上次分支：${last.title} / ${last.nodes.find{it.id==lastParent}?.title?:"根主题"}")}
                        }
                        OutlinedTextField(query,{query=it},singleLine=true,label={Text("按图名查找")},modifier=Modifier.fillMaxWidth().testTag("capture-map-query"))
                        scenes.filter{it.available&&it.title.contains(query,true)}.sortedWith(compareBy<MapScene>{if(it.ref.key in pinned)0 else 1}.thenBy{recent.indexOf(it.ref.key).let{i->if(i<0)100 else i}}.thenBy{it.title}).forEach{m->
                            Row(verticalAlignment=Alignment.CenterVertically){TextButton({selected=m.ref.key;parent=null},enabled=ready,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("capture-map-${m.ref.key}")){Text(m.title)}
                                IconButton({pinned=if(m.ref.key in pinned)pinned-m.ref.key else pinned+m.ref.key;prefs.edit().putStringSet("${draft.notebookId}-pins",pinned).apply()},modifier=Modifier.size(48.dp).describedAs(if(m.ref.key in pinned)"取消固定导图"else"固定导图")){Glyph("star",if(m.ref.key in pinned)Forest else Quiet)}}
                        }
                        TextButton({newTitle="新导图"},enabled=ready,modifier=Modifier.testTag("capture-new-map")){Text("新建导图")}
                    }else{
                        TextButton({selected=null;parent=null},enabled=ready){Text("‹ 返回导图列表")}
                        Text(scene?.title?:"此图已回收，请重新选择",style=MaterialTheme.typography.titleSmall)
                        if(scene!=null){
                            TextButton({parent=null},enabled=ready,modifier=Modifier.testTag("capture-branch-root")){Text((if(parent==null)"✓ "else"")+"根主题")}
                            val tree=StudyOutline.project(scene.nodes.map{StudyNode(it.id,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)},emptySet(),null)
                            tree.rows.forEach{row->val node=scene.nodes.first{it.id==row.node.id};TextButton({parent=node.id},enabled=ready,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=(row.depth.coerceAtMost(8)*12).dp).testTag("capture-branch-${node.id}")){Text((if(parent==node.id)"✓ "else"")+node.title,maxLines=2)}}
                            if(parent!=null&&scene.nodes.none{it.id==parent})Text("原分支已变化，请重新选择。",color=MaterialTheme.colorScheme.error)
                        }
                    }
                    newTitle?.let{title->OutlinedTextField(title,{newTitle=it.take(120)},label={Text("新图名称")},modifier=Modifier.fillMaxWidth());TextButton({mapWriter.submit(draft.notebookId,KnowledgeData.MapDefinition(title.trim()))},enabled=ready&&title.isNotBlank(),modifier=Modifier.testTag("capture-create-map")){Text("创建")}}
                    (state.message?:mapWrite.message)?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                }
                if(state.unknown)TextButton(writer::retry,enabled=!state.busy,modifier=Modifier.testTag("capture-retry")){Text("核对原操作")}
                if(mapWrite.unknown)TextButton(mapWriter::retry,enabled=!mapWrite.busy){Text("核对新建图")}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                    TextButton({writer.submit(StudyCommand(java.util.UUID.randomUUID().toString(),draft.notebookId,StudyAction.CREATE_EXCERPT,cardId=java.util.UUID.randomUUID().toString(),title="摘录",body=draft.text,source=draft.source));sent(MapRef(draft.notebookId),"inbox")},enabled=ready,modifier=Modifier.testTag("capture-inbox")){Text("存入待整理")}
                    TextButton({scene?.let{submit(it,parent)}},enabled=ready&&scene!=null&&(parent==null||scene.nodes.any{it.id==parent}),modifier=Modifier.testTag("capture-send")){Text("添加")}
                }
            }
        }
    }
}
