// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.inkweft.core.*
import org.inkweft.data.*

internal fun CardTint.surface()=argb?.let { Color(it) } ?: Color.White
internal fun List<KnowledgeRow>.cardPresentations()=asSequence().filterNot{it.removed}
    .mapNotNull{it.data() as? KnowledgeData.CardPresentation}.associateBy{it.cardId}

/** One frozen author draft. A retry reuses its receipt; a newer revision never replaces this draft. */
@Composable internal fun CardPresentationEditor(
    book:String,cardId:String,embedded:Boolean=false,inline:Boolean=false,showColors:Boolean=true,
    inputTag:String="card-annotation-input",saveTag:String="card-presentation-save",cancelTag:String="card-presentation-cancel",
    dismiss:()->Unit,
){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val writer:KnowledgeViewModel=viewModel(key="card-presentation-$book-$cardId",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    BindKnowledgeReadLock(writer)
    val ui by writer.ui.collectAsStateWithLifecycle()
    val lock=rememberBookReadLock(book);val readOnly by lock.readOnly.collectAsStateWithLifecycle()
    val latest=ui.rows.firstOrNull{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==cardId}
    val available=ui.cards.any{it.id==cardId&&it.notebookId==book&&it.trashedAt==null}
    var loaded by rememberSaveable(cardId){mutableStateOf(false)}
    var baseId by rememberSaveable(cardId){mutableStateOf<String?>(null)}
    var baseRevision by rememberSaveable(cardId){mutableLongStateOf(0)}
    var annotation by rememberSaveable(cardId){mutableStateOf("")}
    var cardColor by rememberSaveable(cardId){mutableStateOf(CardTint.DEFAULT)}
    var titleColor by rememberSaveable(cardId){mutableStateOf(CardTint.DEFAULT)}
    var operation by rememberSaveable(cardId){mutableStateOf<String?>(null)}
    val waiting=ui.busy||ui.unknown
    val changed=loaded&&!ui.loading&&!ui.readFailed&&(baseId!=latest?.id||baseRevision!=(latest?.revision?:0L))
    val editable=loaded&&available&&!readOnly&&!ui.loading&&!ui.readFailed&&!waiting&&!changed
    ReadLockGuard(lock,"card-presentation-$cardId",blocked=true,draft=true)
    androidx.activity.compose.BackHandler{if(!waiting)dismiss()}
    LaunchedEffect(ui.loading,ui.readFailed,latest,loaded){
        if(!loaded&&!ui.loading&&!ui.readFailed){
            val data=latest?.data() as? KnowledgeData.CardPresentation
            baseId=latest?.id;baseRevision=latest?.revision?:0
            annotation=data?.annotation.orEmpty();cardColor=data?.cardColor?:CardTint.DEFAULT;titleColor=data?.titleBarColor?:CardTint.DEFAULT;loaded=true
        }
    }
    LaunchedEffect(ui.completedOperation,operation){
        val submitted=operation
        if(submitted!=null&&ui.completedOperation==submitted){writer.consumed(submitted);operation=null;dismiss()}
    }
    val form:@Composable ()->Unit={Column(if(inline)Modifier else Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("个人注释由这张卡的所有引用位置共享；不会改动正文、原迹或位置。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        if(ui.loading||ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(annotation,{if(it.length<=CardPresentationRules.MAX_ANNOTATION)annotation=it},enabled=editable,
            label={Text("个人注释")},supportingText={Text("${annotation.length} / ${CardPresentationRules.MAX_ANNOTATION}")},
            minLines=2,maxLines=if(inline)5 else 8,modifier=Modifier.fillMaxWidth().testTag(inputTag))
        if(showColors){
            @Composable fun palette(label:String,chosen:CardTint,tag:String,select:(CardTint)->Unit){
                Text(label,style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    CardTint.entries.forEach{tint->FilterChip(chosen==tint,{select(tint)},enabled=editable,
                        label={Text(tint.label)},leadingIcon={Box(Modifier.size(14.dp).background(tint.surface()))},modifier=Modifier.testTag("$tag-${tint.name}"))}
                }
            }
            palette("卡片颜色",cardColor,"card-color"){cardColor=it}
            palette("标题栏颜色",titleColor,"card-title-color"){titleColor=it}
            Surface(color=cardColor.surface(),modifier=Modifier.fillMaxWidth().testTag("card-color-preview")){
                Column{Text("标题栏预览",Modifier.fillMaxWidth().background(titleColor.argb?.let{Color(it)}?:Color.Transparent).padding(12.dp),color=Color(0xff20242d));Text("正文与个人注释保持深色可读",Modifier.padding(12.dp),color=Color(0xff20242d))}
            }
            TextButton({cardColor=CardTint.DEFAULT;titleColor=CardTint.DEFAULT},enabled=editable,modifier=Modifier.testTag("card-colors-reset")){Text("恢复默认配色")}
        }
        if(changed){
            Text("其他位置已更新此卡的注释或颜色。当前草稿保留，请先核对最新内容。",modifier=Modifier.testTag("card-presentation-conflict"))
            Text((latest?.data() as? KnowledgeData.CardPresentation)?.annotation.orEmpty().ifBlank{"最新个人注释为空"},style=MaterialTheme.typography.bodySmall)
            TextButton({baseId=latest?.id;baseRevision=latest?.revision?:0;operation=null},enabled=!waiting&&available&&!ui.readFailed,
                modifier=Modifier.testTag("card-presentation-rebase")){Text("保留草稿，按最新版重新保存")}
        }
        if(!ui.loading&&!available)Text("卡片已回收或不可用；草稿保留，未提交。")
        ui.message?.takeIf{operation!=null||waiting}?.let{Text(it,modifier=Modifier.testTag("card-presentation-message"))}
        if(ui.readFailed)TextButton(writer::reload,enabled=!ui.busy){Text("重新读取，保留草稿")}
    }}
    val save:@Composable ()->Unit={TextButton({
        if(ui.unknown)writer.retry()else if(editable){
            val value=KnowledgeData.CardPresentation(cardId,annotation,cardColor,titleColor)
            operation=writer.submit(book,value,latest)
        }
    },enabled=!ui.busy&&(ui.unknown||editable),modifier=Modifier.testTag(saveTag)){Text(if(ui.unknown)"核对原操作"else"保存")}}
    val cancel:@Composable ()->Unit={TextButton({if(!waiting)dismiss()},enabled=!waiting,modifier=Modifier.testTag(cancelTag)){Text("取消")}}
    if(inline)Column(Modifier.padding(12.dp).testTag("card-presentation-editor")){Text("个人注释",style=MaterialTheme.typography.titleSmall);form();FlowRow{cancel();save()}}
    else StudyDialog(embedded,{if(!waiting)dismiss()},title={Text(if(showColors)"个人注释与配色"else"个人注释")},text=form,confirmButton=save,dismissButton=cancel,modifier=Modifier.testTag("card-presentation-editor"))
}
