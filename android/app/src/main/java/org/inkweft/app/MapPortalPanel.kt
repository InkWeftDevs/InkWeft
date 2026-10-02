// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*

/** A portal belongs to one occurrence; its map or branch target never becomes a new tree parent. */
@Composable internal fun MapPortalPanel(source:MapRef,nodeId:String,bookTitle:String,scenes:List<MapScene>,rows:List<KnowledgeRow>,
    embedded:Boolean,enabled:Boolean,writer:KnowledgeViewModel,write:KnowledgeUi,dismiss:()->Unit,open:(MapPortalPreview)->Boolean){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val readLock=rememberBookReadLock(source.notebookId)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    val editable=enabled&&!readOnly
    BindKnowledgeReadLock(writer)
    val busy=write.busy||write.unknown
    var creating by rememberSaveable{mutableStateOf(false)}
    var targetKey by rememberSaveable{mutableStateOf<String?>(null)}
    var onlyBranch by rememberSaveable{mutableStateOf(false)}
    var targetBranchId by rememberSaveable{mutableStateOf<String?>(null)}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var capturedRevision by rememberSaveable{mutableLongStateOf(0L)}
    var removing by rememberSaveable{mutableStateOf(false)}
    var localMessage by remember{mutableStateOf<String?>(null)}
    val guardKey=remember(source,nodeId){"map-portal-${java.util.UUID.randomUUID()}"}
    ReadLockGuard(readLock,guardKey,blocked=busy||creating||removing,draft=creating||removing)
    val own=rows.filter{r->!r.removed&&r.notebookId==source.notebookId&&(r.data() as? KnowledgeData.MapPortal)?.let{it.sourceMapId==source.mapId&&it.sourceNodeId==nodeId}==true}
    LaunchedEffect(write.completed){if(write.completed!=null){creating=false;targetKey=null;onlyBranch=false;targetBranchId=null;selected=null;removing=false;writer.consumed()}}
    fun closePreview(){if(!busy){selected=null;removing=false;localMessage=null}}
    if(selected==null&&!creating){
        StudyDialog(embedded,{if(!busy)dismiss()},modifier=Modifier.testTag("map-portal-manager"),title={Text("跨图入口")},text={
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("$bookTitle · ${scenes.find{it.ref==source}?.title?:"源图不可用"}")
                Text("入口只附着在这个主题位置，指向另一张图或指定分支。打开后可返回原视野。",style=MaterialTheme.typography.bodySmall)
                if(own.isEmpty())Text("还没有跨图入口")
                own.forEach{row->val p=row.data() as KnowledgeData.MapPortal;val target=scenes.find{it.ref==MapRef(source.notebookId,p.targetMapId)}
                    val branch=target?.nodes?.find{it.id==p.targetBranchId}
                    val destination=if(target?.available!=true)"已回收或不可用"else if(p.targetBranchId==null)"整图"else "分支 · ${branch?.title?:"已移除或不可用"}"
                    TextButton({selected=row.id;capturedRevision=row.revision;localMessage=null},enabled=enabled&&!busy,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-portal-item-${row.id}")){
                        Text((target?.title?:"目标图不可用")+" · "+destination)
                    }
                }
                (localMessage?:write.message)?.let{Text(it)}
                if(write.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(write.unknown)TextButton(writer::retry,enabled=!write.busy,modifier=Modifier.testTag("map-portal-retry")){Text("核对原操作")}
            }
        },confirmButton={TextButton({creating=true;targetKey=null;onlyBranch=false;targetBranchId=null;localMessage=null},enabled=editable&&!busy,modifier=Modifier.testTag("map-portal-create")){Text("创建入口")}},
            dismissButton={TextButton(dismiss,enabled=!busy,modifier=Modifier.testTag("map-portal-close-manager")){Text("返回导图")}})
    }else if(creating){
        val choices=scenes.filter{it.available&&it.ref.notebookId==source.notebookId&&it.ref!=source}
        val chosen=choices.find{it.ref.key==targetKey}
        val branch=chosen?.nodes?.find{it.id==targetBranchId}
        StudyDialog(embedded,{if(!busy){creating=false;targetKey=null;onlyBranch=false;targetBranchId=null}},modifier=Modifier.testTag("map-portal-destination-picker"),title={Text("选择目标图与范围")},text={
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("$bookTitle · 同一笔记")
                if(choices.isEmpty())Text("请先在这本笔记中创建另一张导图。")
                choices.forEach{scene->TextButton({if(targetKey!=scene.ref.key){targetKey=scene.ref.key;onlyBranch=false;targetBranchId=null}},enabled=editable&&!busy,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-portal-destination-${scene.ref.key}")){
                    Text((if(scene.ref.key==targetKey)"✓ "else"")+scene.title)
                }}
                chosen?.let{scene->
                    Row(Modifier.fillMaxWidth()){
                        TextButton({onlyBranch=false;targetBranchId=null},enabled=editable&&!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("map-portal-target-whole")){Text(if(onlyBranch)"整图"else"✓ 整图")}
                        TextButton({onlyBranch=true},enabled=editable&&!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("map-portal-target-branch")){Text(if(onlyBranch)"✓ 指定分支"else"指定分支")}
                    }
                    if(onlyBranch){
                        if(scene.nodes.isEmpty())Text("目标图还没有可选主题。可返回整图范围，或取消后补充主题。")
                        val byId=scene.nodes.associateBy{it.id}
                        val outline=StudyOutline.project(scene.nodes.map{StudyNode(it.id,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)})
                        outline.rows.forEach{row->val node=byId.getValue(row.node.id)
                            TextButton({targetBranchId=node.id},enabled=editable&&!busy,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-portal-branch-${node.id}")){
                                Column(Modifier.fillMaxWidth().padding(start=(row.depth.coerceAtMost(5)*8).dp)){
                                    Text((if(node.id==targetBranchId)"✓ "else"")+node.title)
                                    node.parentId?.let{parent->Text("上级：${byId[parent]?.title.orEmpty()}",style=MaterialTheme.typography.bodySmall)}
                                }
                            }
                        }
                        if(branch==null)Text(if(targetBranchId==null)"请选择一个目标分支。"else"所选分支已变化，请重新选择；不会改成整图入口。")
                        else Text("将打开“${scene.title} / ${branch.title}”分支，可返回全部主题。",style=MaterialTheme.typography.bodySmall)
                    }else Text("将创建通往“${scene.title}”的整图入口。",style=MaterialTheme.typography.bodySmall)
                }
                Text("目标内容不会复制到当前树；仅支持同一笔记。",style=MaterialTheme.typography.bodySmall)
                write.message?.let{Text(it)}
                if(write.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(write.unknown)TextButton(writer::retry,enabled=!write.busy,modifier=Modifier.testTag("map-portal-retry")){Text("核对原操作")}
            }
        },confirmButton={TextButton({chosen?.let{if(!onlyBranch||branch!=null)writer.submit(source.notebookId,KnowledgeData.MapPortal(source.mapId,nodeId,it.ref.mapId,if(onlyBranch)branch?.id else null))}},enabled=editable&&!busy&&chosen!=null&&(!onlyBranch||branch!=null),modifier=Modifier.testTag("map-portal-save")){Text("保存入口")}},
            dismissButton={TextButton({creating=false;targetKey=null;onlyBranch=false;targetBranchId=null},enabled=!busy,modifier=Modifier.testTag("map-portal-cancel-create")){Text("取消")}})
    }else selected?.let{id->key(id,capturedRevision){
        var preview by remember{mutableStateOf<MapPortalPreview?>(null)}
        var failure by remember{mutableStateOf<String?>(null)}
        var reading by remember{mutableStateOf(true)}
        var opening by remember{mutableStateOf(false)}
        var attempt by remember{mutableIntStateOf(0)}
        val scope=rememberCoroutineScope()
        fun failed(e:Exception){preview=null;failure=if(e is KnowledgeRejected)"入口已变化，请返回列表重新核对。"else"预览读取失败，请重试。"}
        LaunchedEffect(rows,scenes,attempt){
            withContext(Dispatchers.Main.immediate){reading=true;failure=null;preview=null}
            try{val value=withContext(Dispatchers.IO){app.mapPortals.preview(source.notebookId,id,capturedRevision)}
                withContext(Dispatchers.Main.immediate){preview=value;reading=false}}
            catch(c:CancellationException){throw c}
            catch(e:Exception){withContext(Dispatchers.Main.immediate){failed(e);reading=false}}
        }
        StudyDialog(embedded,{if(!opening)closePreview()},modifier=Modifier.testTag("map-portal-preview"),title={Text(if(removing)"移除跨图入口"else if(preview?.targetBranchId!=null)"分支入口预览"else"整图入口预览")},text={
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(reading)CircularProgressIndicator(Modifier.size(24.dp))
                preview?.let{p->
                    Text(p.targetTitle,modifier=Modifier.testTag("map-portal-preview-target"))
                    p.targetBranchTitle?.let{Text(it,modifier=Modifier.testTag("map-portal-preview-branch"))}
                    Text("${p.bookTitle} · ${if(p.targetBranchId==null)"整图"else"指定分支"} · "+if(p.canOpen)"可打开"else"不可打开")
                    Text("来自 ${p.sourceMapTitle} / ${p.sourceTitle}",style=MaterialTheme.typography.bodySmall)
                    if(!p.canOpen)Text("源主题、目标图或指定分支已回收或不可用。入口保留原身份，不会改指另一张图或同名主题。",modifier=Modifier.testTag("map-portal-preview-unavailable"))
                    if(removing)Text("只移除这个入口；保留源主题、目标图和共享卡片。")
                    else Text("在当前导图窗口打开，不翻动笔记页面。预览只显示目标名称和状态。",style=MaterialTheme.typography.bodySmall)
                }
                failure?.let{Text(it,modifier=Modifier.testTag("map-portal-preview-error"));TextButton({attempt++},enabled=!opening&&!busy){Text("重新核对")}}
                (localMessage?:write.message)?.let{Text(it)}
                if(!removing)TextButton({removing=true},enabled=editable&&!busy&&!opening&&!reading&&failure==null,modifier=Modifier.testTag("map-portal-remove")){Text("移除入口")}
                if(write.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(write.unknown)TextButton(writer::retry,enabled=!write.busy,modifier=Modifier.testTag("map-portal-retry")){Text("核对原操作")}
            }
        },confirmButton={
            if(removing)TextButton({
                val row=rows.find{it.id==id&&it.revision==capturedRevision&&!it.removed}
                if(row==null){localMessage="入口已变化，请返回列表重新核对。";removing=false}
                else writer.submit(source.notebookId,row.data(),row,remove=true)
            },enabled=editable&&!busy&&!opening&&!reading&&failure==null,modifier=Modifier.testTag("map-portal-confirm-remove")){Text("确认移除")}
            else TextButton({if(!opening){opening=true;scope.launch{
                try{val checked=withContext(Dispatchers.IO){app.mapPortals.preview(source.notebookId,id,capturedRevision)}
                    withContext(Dispatchers.Main.immediate){preview=checked;if(checked.canOpen){if(open(checked))dismiss()else failure="源视图已变化，请返回列表重新核对。"}}}
                catch(c:CancellationException){throw c}
                catch(e:Exception){withContext(Dispatchers.Main.immediate){failed(e)}}
                finally{if(currentCoroutineContext().isActive)withContext(Dispatchers.Main.immediate){opening=false}}
            }}},enabled=enabled&&!busy&&!reading&&!opening&&failure==null&&preview?.canOpen==true,modifier=Modifier.testTag("map-portal-open")){Text(if(preview?.targetBranchId==null)"打开整图"else"打开分支")}
        },dismissButton={TextButton({if(removing&&!busy)removing=false else closePreview()},enabled=!busy&&!opening,modifier=Modifier.testTag("map-portal-close-preview")){Text(if(removing)"取消移除"else"返回列表")}})
    }}
}
