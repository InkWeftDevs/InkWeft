// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.io.File
import java.util.UUID

/** Only confirmed intents are written here. A pre-confirmation cancellation has zero author or intent writes. */
private class CardTransformPendingStore(context:Context,book:String) {
    private val file=AtomicFile(File(context.filesDir,"card-transform-$book.pending"))
    fun read():CardTransformPlan? = if(file.baseFile.exists())CardTransformCodec.decode(file.readFully())else null
    fun save(plan:CardTransformPlan){
        val current=read();require(current==null||current.digest()==plan.digest()){"TRANSFORM_PENDING_EXISTS"}
        val stream=file.startWrite()
        try{stream.write(CardTransformCodec.encode(plan));file.finishWrite(stream)}catch(t:Throwable){file.failWrite(stream);throw t}
    }
    fun clear(plan:CardTransformPlan){if(read()?.digest()==plan.digest())file.delete()}
}

/** Launch with one card for SPLIT and at least two explicitly selected cards for MERGE/SUMMARY. */
@Composable internal fun CardTransformDialog(repository:CardTransformRepository,notebookId:String,initialCardIds:List<String>,
    kind:CardTransformKind,onDismiss:()->Unit,onCommitted:(List<String>)->Unit,embedded:Boolean=false,
    authorAllowed:()->Boolean={true},onPendingChanged:(Boolean)->Unit={},mapId:String?=null) {
    val context=LocalContext.current;val store=remember(notebookId){CardTransformPendingStore(context.applicationContext,notebookId)}
    val scope=rememberCoroutineScope();val canAuthor by rememberUpdatedState(authorAllowed)
    var loaded by remember{mutableStateOf<CardTransformPreview?>(null)}
    var pending by remember{mutableStateOf<CardTransformPlan?>(null)}
    var proposal by remember{mutableStateOf<CardTransformPlan?>(null)}
    var busy by remember{mutableStateOf(false)}
    var unknown by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var result by remember{mutableStateOf<CardTransformOutcome.Success?>(null)}
    var attempt by remember{mutableIntStateOf(0)}
    var title by rememberSaveable{mutableStateOf("")}
    var secondTitle by rememberSaveable{mutableStateOf("")}
    var summary by rememberSaveable{mutableStateOf("")}
    var offsetText by rememberSaveable{mutableStateOf("")}
    var annotationDestination by rememberSaveable{mutableIntStateOf(0)}
    var sourceDestination by rememberSaveable{mutableIntStateOf(0)}
    val pendingState=busy||pending!=null
    SideEffect{onPendingChanged(pendingState)}
    DisposableEffect(Unit){onDispose{onPendingChanged(false)}}
    LaunchedEffect(notebookId,initialCardIds,mapId,attempt){
        busy=true;error=null
        try{
            val restored=withContext(Dispatchers.IO){store.read()}
            if(restored!=null){
                pending=restored;proposal=restored
                val receipt=withContext(Dispatchers.IO){repository.lookup(restored)}
                if(receipt!=null){result=receipt;withContext(Dispatchers.IO){store.clear(restored)};pending=null}else unknown=true
            }else{
                loaded=withContext(Dispatchers.IO){repository.preview(notebookId,initialCardIds,mapId)}
                loaded?.let{preview->
                    if(title.isEmpty())title=if(kind==CardTransformKind.SUMMARY)"" else preview.cards.first().title.take(112)+if(kind==CardTransformKind.MERGE)" · 合并"else" · 1"
                    if(secondTitle.isEmpty())secondTitle=preview.cards.first().title.take(112)+" · 2"
                    if(offsetText.isEmpty()){
                        val text=preview.cards.first().body;var offset=(text.length/2).coerceAtLeast(1)
                        if(offset<text.length&&text[offset-1].isHighSurrogate()&&text[offset].isLowSurrogate())offset++
                        offsetText=offset.toString()
                    }
                }
            }
        }catch(c:CancellationException){throw c}catch(e:Exception){error=transformMessage(e.message)}finally{busy=false}
    }
    fun preview(){
        try{
            val value=requireNotNull(loaded);val cards=value.cards
            val targets=when(kind){
                CardTransformKind.MERGE->listOf(CardTransforms.mergeTarget(cards,title))
                CardTransformKind.SUMMARY->listOf(CardTransforms.mergeTarget(cards,title).copy(body=summary))
                CardTransformKind.SPLIT->CardTransforms.splitTargets(cards.single(),requireNotNull(offsetText.toIntOrNull())).mapIndexed{i,target->
                    target.copy(title=if(i==0)title else secondTitle,
                        annotation=if(annotationDestination==0||annotationDestination==i+1)target.annotation else "",
                        sources=if(sourceDestination==0||sourceDestination==i+1)target.sources else emptyList())
                }
            }
            proposal=value.plan(kind,targets);error=null
        }catch(e:Exception){error=transformMessage(e.message)}
    }
    fun apply(plan:CardTransformPlan){
        if(busy||!canAuthor()){error="请先结束其他编辑，再确认这次操作";return}
        busy=true;error=null;pending=plan
        scope.launch{
            try{
                withContext(Dispatchers.IO){store.save(plan)}
                when(val outcome=withContext(Dispatchers.IO){repository.outcome(plan)}){
                    is CardTransformOutcome.Success->{result=outcome;withContext(Dispatchers.IO){store.clear(plan)};pending=null;unknown=false}
                    is CardTransformOutcome.Rejected->{withContext(Dispatchers.IO){store.clear(plan)};pending=null;unknown=false;error=transformMessage(outcome.reason);proposal=null}
                    CardTransformOutcome.Unknown->{unknown=true;error="保存结果尚未确认。请核对或重试同一次操作"}
                }
            }catch(c:CancellationException){throw c}catch(_:Exception){unknown=true;error="操作记录仍待核对。重试会沿用同一个操作编号"}finally{busy=false}
        }
    }
    val finalResult=result
    val effectiveKind=pending?.kind?:proposal?.kind?:kind
    StudyDialog(embedded,onDismissRequest={if(!pendingState)onDismiss()},modifier=Modifier.testTag("card-transform-dialog"),
        title={Text(if(finalResult!=null)"${effectiveKind.label}已保存"else"${effectiveKind.label}内容卡")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(finalResult!=null){
                Text("已创建 ${finalResult.targetIds.size} 张新内容卡。原卡、原图位置、引用及旧题保留；${if(effectiveKind==CardTransformKind.SUMMARY&&proposal?.summaryPlacement!=null)"总结节点已加入预览中指定的导图。"else"新卡尚未加入导图。"}没有自动创建复习题。")
                Text("从原卡的“${effectiveKind.label}结果”可找到新卡，并在内容和引用未变化时撤销。")
            }else if(proposal!=null){
                val plan=requireNotNull(proposal)
                Text("确认前核对以下去向",style=MaterialTheme.typography.titleMedium)
                Text("原卡保留为可恢复的旧身份，所有出现位置与局部批注不移动。旧链接仍可读原卡及固定版本，并显示新卡选择入口。旧题及进度不复制、不相加。",modifier=Modifier.testTag("transform-retention-policy"))
                loaded?.impact?.forEach{impact->
                    HorizontalDivider();Text("原卡：${impact.card.title} · 版本 ${impact.card.revision}")
                    Text("正文\n${impact.card.body.ifEmpty{"（空）"}}")
                    Text("共享注释\n${impact.card.presentation.annotation.ifEmpty{"（无）"}}")
                    Text("来源：${impact.sourceLabels.joinToString("\n").ifEmpty{"无"}}")
                    if(!impact.card.sourcesComplete)Text("此旧版本来源无法恢复，新卡将保留明确的来源不完整标记",color=MaterialTheme.colorScheme.error)
                    Text("出现位置（${impact.occurrences.size}）：${impact.occurrences.joinToString("\n").ifEmpty{"无"}}")
                    Text("引用（${impact.references.size}）：${impact.references.joinToString("\n").ifEmpty{"无"}}")
                    Text("题目（${impact.questions.size}）：${impact.questions.joinToString("\n").ifEmpty{"无"}}")
                }
                plan.targets.forEachIndexed{i,target->
                    HorizontalDivider();Text("新卡 ${i+1}：${target.title}",style=MaterialTheme.typography.titleMedium)
                    Text("正文\n${target.body}",modifier=Modifier.testTag("transform-target-body-$i"))
                    Text("共享注释\n${target.annotation.ifEmpty{"（无）"}}")
                    Text("来源（${target.sources.size}）："+target.sources.joinToString("\n"){"${it.sourceId} · 版本 ${it.revision}"})
                    Text("卡色：${target.cardColor.label}；标题栏：${target.titleBarColor.label}")
                }
                if(plan.kind==CardTransformKind.SUMMARY){
                    val placement=plan.summaryPlacement
                    Text("每张原卡 → 新总结节点：归纳总结关系。新位置：${if(placement?.mapId==null)"主图"else"所选导图"}，${if(placement?.parentId==null)"根级"else"所选卡的共同父级下"}；已有节点位置与层级不变。总结正文由你撰写。",modifier=Modifier.testTag("transform-summary-placement"))
                }
                if(!pendingState)TextButton({proposal=null},modifier=Modifier.testTag("transform-back")){Text("返回修改")}
                if(unknown)Text("此时不能丢弃待确认操作；请重试核对，避免生成重复卡片。")
            }else loaded?.let{preview->
                Text("已选择 ${preview.cards.size} 张内容卡：${preview.cards.joinToString("、"){it.title}}")
                OutlinedTextField(title,{title=it.take(120)},label={Text(if(kind==CardTransformKind.SPLIT)"第一张标题"else"新卡标题")},modifier=Modifier.fillMaxWidth().testTag("transform-title"),enabled=!busy)
                if(kind==CardTransformKind.SUMMARY)OutlinedTextField(summary,{summary=it},label={Text("你撰写的总结正文")},modifier=Modifier.fillMaxWidth().testTag("transform-summary"),minLines=4,enabled=!busy)
                if(kind==CardTransformKind.SPLIT){
                    OutlinedTextField(secondTitle,{secondTitle=it.take(120)},label={Text("第二张标题")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
                    OutlinedTextField(offsetText,{offsetText=it.filter(Char::isDigit)},label={Text("第一张保留前多少个字符")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("transform-split-offset"),enabled=!busy)
                    Text("正文按此位置完整分为两张，空格和换行逐字保留。总字符数：${preview.cards.single().body.length}")
                    Text("共享注释去向（默认两张均完整保留）")
                    DestinationChoice(annotationDestination,{annotationDestination=it})
                    Text("全部来源去向（默认两张均保留固定版本）")
                    DestinationChoice(sourceDestination,{sourceDestination=it})
                }
                Text("新卡颜色继承首卡；合并保留全部正文和共享注释。超出单卡容量时会保留原稿并提示，绝不截断。")
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("transform-error"))}
            if(loaded==null&&pending==null&&!busy&&finalResult==null)TextButton({attempt++}){Text("重新读取")}
        }},
        confirmButton={
            if(finalResult!=null)TextButton({onCommitted(finalResult.targetIds)},modifier=Modifier.testTag("transform-done")){Text("查看新卡")}
            else if(proposal!=null)TextButton({apply(requireNotNull(pending?:proposal))},enabled=!busy&&canAuthor(),modifier=Modifier.testTag("transform-confirm")){Text(if(unknown)"核对并重试同一次"else"确认${effectiveKind.label}")}
            else TextButton({preview()},enabled=loaded!=null&&!busy&&canAuthor(),modifier=Modifier.testTag("transform-preview")){Text("查看影响")}
        },dismissButton={if(finalResult==null)TextButton(onDismiss,enabled=!pendingState,modifier=Modifier.testTag("transform-cancel")){Text("取消")}})
}
@Composable private fun DestinationChoice(value:Int,onChange:(Int)->Unit){
    Column { listOf("两张均保留","仅第一张","仅第二张").forEachIndexed{i,label->
        Row { RadioButton(value==i,{onChange(i)});TextButton({onChange(i)}){Text(label)} }
    } }
}

/** Place beside the currently selected original card, including cards reached through old links. */
@Composable internal fun CardTransformResolutionPanel(repository:CardTransformRepository,cardId:String,onOpenCard:(String)->Unit,
    authorAllowed:()->Boolean={true},onPendingChanged:(Boolean)->Unit={}) {
    val resolutions by produceState<List<CardTransformResolution>>(emptyList(),repository,cardId){repository.observeResolution(cardId).collect{value=it}}
    var activeOperation by rememberSaveable{mutableStateOf<String?>(null)}
    var undoId by rememberSaveable{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val canAuthor by rememberUpdatedState(authorAllowed)
    // One root-owned pending intent prevents sibling resolutions from clearing each other's guard.
    SideEffect{onPendingChanged(busy||activeOperation!=null)}
    DisposableEffect(Unit){onDispose{onPendingChanged(false)}}
    LaunchedEffect(Unit){
        val operation=activeOperation;val undo=undoId
        if(operation!=null&&undo!=null){
            busy=true
            try{
                if(withContext(Dispatchers.IO){repository.lookupUndo(operation,1,undo)}!=null){activeOperation=null;undoId=null}
                else error="撤销结果待核对，请重试同一次撤销"
            }catch(c:CancellationException){throw c}catch(e:IllegalArgumentException){activeOperation=null;undoId=null;error=transformMessage(e.message)}catch(_:Exception){error="撤销结果待核对，请重试同一次撤销"}finally{busy=false}
        }
    }
    fun undo(operation:String){
        if(busy||!canAuthor()||activeOperation!=null&&activeOperation!=operation)return
        activeOperation=operation;if(undoId==null)undoId=UUID.randomUUID().toString()
        val frozenUndo=requireNotNull(undoId);busy=true;error=null
        scope.launch{
            try{when(val outcome=withContext(Dispatchers.IO){repository.undoOutcome(operation,1,frozenUndo)}){
                is CardTransformOutcome.Success->{activeOperation=null;undoId=null}
                is CardTransformOutcome.Rejected->{activeOperation=null;undoId=null;error=transformMessage(outcome.reason)}
                CardTransformOutcome.Unknown->error="撤销结果待核对，请重试同一次撤销"
            }}catch(c:CancellationException){throw c}catch(_:Exception){error="撤销结果待核对，请重试同一次撤销"}finally{busy=false}
        }
    }
    Column{
        resolutions.forEach{resolution->key(resolution.operationId){
            Column(Modifier.fillMaxWidth().testTag("transform-resolution-${resolution.operationId}")){
                Text("${resolution.kind.label}结果 · 原卡与旧固定版本仍保留",style=MaterialTheme.typography.labelLarge)
                if(resolution.targets.size>1)Text("请选择要定位的新卡；不会自动跳到第一张")
                resolution.targets.forEach{target->TextButton({onOpenCard(target.id)},enabled=target.trashedAt==null&&!busy&&activeOperation==null){Text(target.title+if(target.trashedAt!=null)"（已回收）"else"")}}
                TextButton({undo(resolution.operationId)},enabled=!busy&&canAuthor()&&(activeOperation==null||activeOperation==resolution.operationId),modifier=Modifier.testTag("transform-undo-${resolution.operationId}")){
                    Text(if(activeOperation==resolution.operationId)"重试同一次撤销"else"撤销这次${resolution.kind.label}")
                }
            }
        }}
        val pending=activeOperation
        if(pending!=null&&resolutions.none{it.operationId==pending})TextButton({undo(pending)},enabled=!busy&&canAuthor()){Text("核对待确认的撤销")}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

/** Frozen answer/source chooser. Calling code must also gate the component behind recall reveal permission. */
@Composable internal fun FrozenCardSources(sources:FrozenStudySources,cardId:String,onSourceSelected:(StudySourceRow?)->Unit,
    enabled:Boolean=true) {
    val key=sources.refs.joinToString{it.toString()}
    var selected by rememberSaveable(cardId,key){mutableStateOf(if(sources.complete&&sources.sources.size==1)0 else -1)}
    val value=remember(sources,cardId,selected,enabled){if(enabled&&sources.complete)sources.sources.getOrNull(selected)?.legacy(cardId)else null}
    SideEffect{onSourceSelected(value)}
    Column(Modifier.testTag("frozen-card-sources")){
        when{
            !sources.complete->Text("此旧版本来源无法恢复，需重新核对。固定答案文字仍可阅读。",color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("frozen-source-unavailable"))
            sources.sources.isEmpty()->Text("此内容版本没有来源")
            sources.sources.size>1->{Text("请选择一个固定来源（共 ${sources.sources.size} 个）")
                sources.sources.forEachIndexed{i,source->TextButton({selected=i},enabled=enabled,modifier=Modifier.testTag("frozen-source-$i")){
                    Text("${if(selected==i)"已选择 · "else""}来源 ${i+1} · 版本 ${source.revision}")
                }}
            }
        }
    }
}
private fun transformMessage(reason:String?)=when(reason){
    "TRANSFORM_BODY_TOO_LONG"->"合并正文超过单卡 20,000 字符，请减少所选卡片；原内容没有改动"
    "TRANSFORM_ANNOTATION_TOO_LONG"->"合并注释超过单卡 10,000 字符，请减少所选卡片；原注释没有改动"
    "TRANSFORM_VERSION_CHANGED"->"预览期间内容、来源或相关记录已变化，请重新读取并预览"
    "TRANSFORM_UNDO_OPERATION_MISMATCH","TRANSFORM_ALREADY_UNDONE"->"这次转换已由另一条撤销操作完成，请查看保留的原卡"
    "TRANSFORM_UNDO_DEPENDENCIES_CHANGED"->"新卡或相关引用已修改，不能覆盖后续编辑来撤销。原卡仍完整保留"
    "TRANSFORM_SAME_NOTEBOOK_REQUIRED"->"请在同一本笔记中选择未回收的内容卡"
    "SUMMARY_PLACEMENT_REQUIRED"->"旧版总结请求尚未创建位置；未重复创建，请重新打开预览以确定总结节点位置"
    "TRANSFORM_SUMMARY_REQUIRED"->"请先写下总结正文"
    "STUDY_CARD_BUDGET"->"本笔记已达到内容卡容量；原卡和草稿均保留"
    "KNOWLEDGE_BUDGET"->"本笔记已达到关系记录容量；原卡和草稿均保留"
    "TRANSFORM_PENDING_EXISTS"->"此笔记已有待核对的操作，请先完成核对"
    else->"暂时无法继续，请检查标题、拆分位置和选择，或重新读取。原内容未被丢弃"+(reason?.let{"（$it）"}?:"")
}
