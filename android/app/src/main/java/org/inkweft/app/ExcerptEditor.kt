// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.inkweft.core.*
import java.util.UUID

/** The card owns the comment; resizing replaces only its source snapshot after confirmation. */
@Composable internal fun ExcerptEditor(
    id:String,vm:StudyViewModel,view:InkCanvasView?,viewport:CanvasViewport,world:Boolean,
    inkRevision:Long,objectRevision:Long,ready:Boolean,dismiss:()->Unit,onActive:(Boolean)->Unit,onDraft:(Boolean)->Unit
){
    val ui by vm.ui.collectAsStateWithLifecycle()
    val readLock=rememberBookReadLock(vm.book)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    SideEffect{vm.authorAllowed={readLock.canWrite}}
    val guardKey=remember(vm,id){"excerpt-editor-${UUID.randomUUID()}"}
    val source by remember(id){vm.repo.observeSource(id)}.collectAsStateWithLifecycle(initialValue=null)
    val card=ui.cards.find{it.id==id&&it.trashedAt==null}
    var mode by rememberSaveable(id){mutableStateOf("actions")}
    var coordinates by rememberSaveable(id){mutableStateOf<List<Double>?>(null)}
    val draft=coordinates?.let{CanvasBounds(it[0],it[1],it[2],it[3])}
    fun setDraft(b:CanvasBounds?){coordinates=b?.let{listOf(it.left,it.top,it.right,it.bottom)}}
    var revision by rememberSaveable(id){mutableLongStateOf(0)}
    var text by rememberSaveable(id){mutableStateOf("")}
    var message by remember(id){mutableStateOf<String?>(null)}
    var submitted by rememberSaveable(id){mutableStateOf(false)}
    val waiting=ui.busy||ui.unknown
    val enabled=ready&&!readOnly&&!waiting&&!ui.loading&&!ui.readFailed
    ReadLockGuard(readLock,guardKey,blocked=waiting||mode!="actions",draft=mode!="actions")
    SideEffect{onDraft(mode!="actions")}
    DisposableEffect(readLock,guardKey){onDispose{onDraft(false);onActive(false);readLock.guard("$guardKey-gesture",false)}}
    LaunchedEffect(ui.completed,ui.busy,card?.revision){if(submitted&&!waiting&&ui.message==null&&(ui.completed==id||(card?.revision?:0)>revision)){submitted=false;mode="actions";setDraft(null);vm.clear()}}
    LaunchedEffect(card,ui.loading){if(card==null&&!ui.loading&&!ui.readFailed&&!waiting)dismiss()}
    fun cancel(){if(!waiting){mode="actions";setDraft(null);message=null;vm.clear()}}
    BackHandler{if(mode!="actions")cancel()else if(!waiting)dismiss()}
    if(card==null||source==null)return
    val original=source!!
    val b=draft?:CanvasBounds(original.left,original.top,original.right,original.bottom)
    val region=InkRegion(listOf(EraserPoint(b.left.toFloat(),b.top.toFloat()),EraserPoint(b.right.toFloat(),b.bottom.toFloat())))
    AndroidView(factory={ExcerptResizeOverlay(it)},update={overlay->
        overlay.canvasView=view;overlay.bounds=b;overlay.world=world;overlay.editing=mode=="resize";overlay.enabledInput=enabled
        overlay.onChange={if(readLock.canWrite)setDraft(it)};overlay.onActive={active->readLock.guard("$guardKey-gesture",active);onActive(active)};overlay.onDismiss={if(!waiting&&mode=="actions")dismiss()};overlay.invalidate()
    },modifier=Modifier.fillMaxSize().testTag("excerpt-edit-overlay"))
    SelectionToolbar(region,viewport,focusable=mode=="comment"){
        Column(Modifier.widthIn(max=320.dp)){
            if(mode=="comment"){
                Column(Modifier.padding(12.dp)){
                    Text("摘录备注",style=MaterialTheme.typography.titleSmall)
                    OutlinedTextField(text,{if(it.length<=20000)text=it},enabled=enabled,maxLines=5,placeholder={Text("写下你的理解")},modifier=Modifier.fillMaxWidth().testTag("excerpt-inline-input"))
                    Row{TextButton(::cancel,enabled=!waiting){Text("取消")};Spacer(Modifier.weight(1f));TextButton({
                        submitted=true;vm.submit(StudyCommand(UUID.randomUUID().toString(),vm.book,StudyAction.EDIT,cardId=id,expectedRevision=revision,title=card.title,body=text))
                    },enabled=enabled,modifier=Modifier.testTag("excerpt-inline-save")){Text("保存")}}
                }
            }else if(mode=="resize"){
                Row{TextButton(::cancel,enabled=!waiting,modifier=Modifier.testTag("excerpt-resize-cancel")){Text("取消")};TextButton({
                    runCatching{checkNotNull(view).excerptPreview(b)}.onSuccess{picture->
                        message=null;submitted=true;vm.submit(StudyCommand(UUID.randomUUID().toString(),vm.book,StudyAction.RECROP_EXCERPT,cardId=id,expectedRevision=revision,
                            source=StudySourceDraft(original.pageId,inkRevision,b,emptyList(),picture,objectRevision)))
                    }.onFailure{message=it.message?:"范围未保存，请重试"}
                },enabled=enabled&&draft!=null,modifier=Modifier.testTag("excerpt-resize-save")){Text("保存范围")}}
            }else{
                Row{
                    TextButton({revision=card.revision;text=card.body;mode="comment";vm.clear()},enabled=enabled,modifier=Modifier.testTag("excerpt-inline-comment")){Text("备注")}
                    TextButton({
                        val clipped=if(world)b else ExcerptBounds.inside(b,CanvasBounds(0.0,0.0,1000.0,1414.0))
                        if(clipped==null)message="来源范围不在当前纸张内，请重新摘录"
                        else{revision=card.revision;setDraft(clipped);mode="resize";vm.clear()}
                    },enabled=enabled,modifier=Modifier.testTag("excerpt-resize")){Text("调整范围")}
                    TextButton({submitted=true;vm.submit(StudyCommand(UUID.randomUUID().toString(),vm.book,StudyAction.TRASH_CARD,cardId=id,expectedRevision=card.revision))},enabled=enabled,modifier=Modifier.testTag("excerpt-inline-delete")){Text("删除")}
                    IconButton(dismiss,enabled=!waiting,modifier=Modifier.describedAs("取消摘录选择")){Glyph("close")}
                }
            }
            if(waiting)LinearProgressIndicator(Modifier.fillMaxWidth())
            (message?:ui.message)?.let{Text(it,Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)}
            if(ui.unknown)TextButton(vm::retry,enabled=!ui.busy){Text("核对本次操作")}
            if(ui.readFailed)TextButton(vm::refresh){Text("重新读取")}
        }
    }
}
