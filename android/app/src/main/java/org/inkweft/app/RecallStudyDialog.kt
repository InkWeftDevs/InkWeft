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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RecallStudyDialog(plan:BranchReviewPlan,dismiss:()->Unit,workModes:(@Composable (Boolean)->Unit)?=null,
    onOriginalGateChanged:(RecallOriginalGate?)->Unit={},consultedOriginal:Boolean=false){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication;val repository=remember(app){app.study.recall()}
    val vm:RecallStudyViewModel=viewModel(key="durable-recall-${plan.ref.notebookId}",factory=RecallStudyViewModel.Factory(repository,plan.ref.notebookId,context))
    val ui by vm.ui.collectAsStateWithLifecycle();val lock=rememberBookReadLock(plan.ref.notebookId)
    var writingInk by remember{mutableStateOf(false)}
    var history by rememberSaveable{mutableStateOf(false)}
    var config by rememberSaveable{mutableStateOf<String?>(null)}
    var sourceViewer by remember{mutableStateOf<StudySourceRow?>(null)}
    var endConfirm by remember{mutableStateOf(false)}
    var forcePractice by remember{mutableStateOf(false)}
    var priorExposure by rememberSaveable{mutableStateOf(consultedOriginal)}
    val busy=ui.loading||ui.busy||ui.pending||writingInk||!ui.draftSaved
    ReadLockGuard(lock,"durable-recall-${plan.ref.notebookId}",busy,draft=busy)
    LaunchedEffect(plan){vm.open(plan)}
    val gate:RecallOriginalGate=remember(vm){{continuation->vm.original(continuation)}}
    val latestGateCallback by rememberUpdatedState(onOriginalGateChanged)
    SideEffect{onOriginalGateChanged(gate.takeIf{ui.session?.current!=null})}
    DisposableEffect(vm){onDispose{vm.cancelNavigation();latestGateCallback(null)}}
    LaunchedEffect(ui.navigationReady,ui.busy,ui.pending){if(ui.navigationReady&&!ui.busy&&!ui.pending){withFrameNanos{};vm.finishNavigation()}}
    LaunchedEffect(ui.session?.current?.row?.id){forcePractice=false}
    LaunchedEffect(ui.session?.current?.row?.id,ui.busy,ui.pending){
        if(priorExposure&&ui.session?.current!=null&&!ui.busy&&!ui.pending)vm.original{priorExposure=false}
    }
    val shield=LocalRecallWindowShield.current
    SideEffect{shield?.setActive(ui.loading||ui.pending||ui.session?.current!=null)}
    DisposableEffect(shield){onDispose{shield?.setActive(false)}}
    fun exit(){if(!busy){if(ui.session?.current!=null)vm.original(dismiss)else dismiss()}}
    Dialog(onDismissRequest={exit()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        RecallWindowPermit()
        Surface(Modifier.fillMaxSize().testTag("durable-recall")){
            Column(Modifier.safeDrawingPadding().padding(12.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(if(ui.session?.current!=null)"本轮持久回忆"else"${ui.session?.row?.title?:plan.title} · 持久回忆",style=MaterialTheme.typography.titleLarge)
                    TextButton({exit()},enabled=!busy,modifier=Modifier.testTag("recall-pause")){Text(if(ui.session?.current?.row?.answerRevealed==false)"暂停并查看资料（计提示）"else"暂停并返回")}
                }
                workModes?.invoke(!busy)
                if(ui.busy||ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(ui.pending&&!ui.busy)TextButton(vm::retry,enabled=!writingInk,modifier=Modifier.testTag("recall-retry-operation")){Text("核对并重试同一次操作")}
                ui.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("recall-error"))}
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    val session=ui.session;val current=session?.current
                    if(session==null&&!ui.loading){
                        Text("正式复习更新到期排程；临时练习保留作答和提示记录，不改变排程。")
                        Text("题序：原导图作者顺序；未入图按标题；同卡旧题按问法，新题按创建顺序。",style=MaterialTheme.typography.bodySmall)
                        val due=ui.queue.count{it.dueAt<=System.currentTimeMillis()&&!it.needsReview}
                        Text("${ui.queue.size} 题 · 当前到期/首次 $due 题 · 待核对 ${ui.queue.count{it.needsReview}} 题")
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button({vm.start(RecallMode.DUE)},enabled=!busy&&due>0&&ui.queue.none{it.needsReview},modifier=Modifier.testTag("recall-start-due")){Text("开始到期复习")}
                            OutlinedButton({vm.start(RecallMode.PRACTICE)},enabled=!busy&&ui.queue.isNotEmpty()&&ui.queue.none{it.needsReview},modifier=Modifier.testTag("recall-start-practice")){Text("临时练习")}
                        }
                        ui.queue.forEach{item->Row(Modifier.fillMaxWidth()){
                            Text("${item.kind.label} · ${item.prompt}${if(item.needsReview)" · 知识已变，待核对"else""}",Modifier.weight(1f))
                            TextButton({config=item.reference.questionId},enabled=!busy&&lock.canWrite){Text("题型 / 固定答案")}
                        }}
                        if(!lock.canWrite)Text("题型与答案设置属于作者编辑；可返回书写状态设置后继续复习。",style=MaterialTheme.typography.bodySmall)
                    }else if(current!=null){
                        val row=current.row
                        Text("第 ${row.position+1} / ${session.row.size} 题 · ${RecallMode.valueOf(row.mode).label} · ${current.spec.kind.label}",style=MaterialTheme.typography.labelLarge)
                        Text(current.spec.prompt,style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("recall-fixed-prompt"))
                        val contextPlan=remember(row.id){BranchReviewPlan(MapRef(row.notebookId,session.row.mapId),session.row.branchId,session.row.title,1,0,
                            listOf(BranchReviewEntryRef(row.questionId,current.spec.knowledgeRevision,current.spec.cardId,current.spec.cardRevision)))}
                        RecallContextPanel(contextPlan,current,!busy){index->vm.hint(if(current.spec.kind==RecallQuestionKind.TEXT_CLOZE)RecallHint.TEXT else RecallHint.REGION,index)}
                        if(row.hintMask and RecallHint.HINT.bit!=0)Text("已记录提示 · 固定卡片标题：${current.card.title}\n固定共享注释：${current.presentation?.annotation.orEmpty().ifEmpty{if(current.spec.presentationKnown)"（空）"else"旧格式未固定，未读取当前注释"}}",modifier=Modifier.testTag("recall-title-hint"))
                        OutlinedTextField(ui.draftText,{vm.draft(text=it)},enabled=!ui.loading&&!ui.busy&&!ui.pending&&!writingInk&&!row.answerRevealed,label={Text("本次文字作答")},minLines=3,
                            modifier=Modifier.fillMaxWidth().testTag("recall-answer-text"))
                        RecallAnswerPad(row.id,ui.draftInk,!ui.loading&&!ui.busy&&!ui.pending&&!row.answerRevealed,{vm.draft(ink=it)},vm::checkpoint,vm::cancelCheckpoint,{writingInk=it})
                        Text(if(!ui.draftSaved)"正在保存本机草稿…"else if(ui.draftDirty)"本机草稿已保留；保存/对照时写入本次尝试"else"作答已保存到本次尝试",style=MaterialTheme.typography.bodySmall)
                        if(!row.answerRevealed){
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                OutlinedButton({vm.save()},enabled=!busy&&ui.draftDirty,modifier=Modifier.testTag("recall-save-answer")){Text("保存作答")}
                                Button(vm::reveal,enabled=!busy,modifier=Modifier.testTag("recall-seal-compare")){Text("我已作答，锁定并对照")}
                                TextButton({vm.hint(RecallHint.HINT)},enabled=!busy){Text("启用本题提示")}
                            }
                        }else{
                            Text("作答已锁定；正常答案对照不计为提前提示。",style=MaterialTheme.typography.bodySmall)
                            Text("固定共享注释："+current.presentation?.annotation.orEmpty().ifEmpty{if(current.spec.presentationKnown)"（空）"else"旧格式未固定，未读取当前注释"})
                            if(row.hintMask!=0)Text("本次已使用：${current.hints.filter{it.kind !in setOf("ANSWER_COMPARE","ORIGINAL_COMPARE")}.map{runCatching{RecallHint.valueOf(it.kind).label}.getOrDefault(it.kind)}.distinct().joinToString("、")}。正式有效评分最高 2；自评原值也会保留。",modifier=Modifier.testTag("recall-assisted-policy"))
                            if(row.mode==RecallMode.PRACTICE.name||forcePractice)Text("本次只保存临时练习，不改变到期时间。")
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                listOf("0 没想起","1 看到才熟悉","2 未想起","3 困难想起","4 稍犹豫","5 立即想起").forEachIndexed{quality,label->
                                    Button({vm.grade(quality,forcePractice);forcePractice=false},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("recall-grade-$quality")){Text(label)}
                                }
                            }
                            if(row.mode==RecallMode.DUE.name)FilterChip(forcePractice,{forcePractice=!forcePractice},enabled=!busy,label={Text("明确改存临时练习")})
                            if(current.sources.complete)current.sources.sources.forEachIndexed{i,source->TextButton({sourceViewer=source.legacy(current.spec.cardId)},enabled=!busy){Text("查看固定原迹 ${i+1}（已封存后的对照）")}}
                        }
                        TextButton(vm::skip,enabled=!busy,modifier=Modifier.testTag("recall-skip")){Text("跳过本题（不计成绩，不改排程）")}
                    }else if(session?.row?.closed==true){
                        Text("本轮已结束：${session.attempts.count{it.status==RecallAttemptStatus.GRADED.name}} 次已评分，${session.attempts.count{it.status==RecallAttemptStatus.SKIPPED.name}} 次跳过。",modifier=Modifier.testTag("recall-round-result"))
                        Text("每条作答、使用的提示、原自评和有效评分都保留；新一轮不会替换旧答案。")
                        Button({vm.newRound()},enabled=!busy){Text("选择新的到期 / 临时练习")}
                    }
                    Row{
                        TextButton({if(ui.session?.current!=null&&!ui.session!!.current!!.row.answerRevealed)vm.original{history=true}else history=true},enabled=!busy){Text("复习历史${if(ui.session?.current?.row?.answerRevealed==false)"（计为提示）"else""}")}
                        if(ui.session?.current!=null)TextButton({endConfirm=true},enabled=!busy,modifier=Modifier.testTag("recall-end-session")){Text("结束未完成轮次")}
                    }
                    Text("SM2-IW1 · SuperMemo2 3.0.1 MIT核心移植 © Alan Kan\nSM-2 algorithm © Piotr Wozniak / SuperMemo World\n初始EF 2.5、下限1.3、ceil取整、最大间隔100年；提示有效分≤2，临时练习不改排程。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    config?.let{id->ui.queue.find{it.reference.questionId==id}?.let{item->RecallQuestionEditor(repository,plan.ref.notebookId,item,{config=null},{config=null;vm.refreshQueue()})}}
    if(history)RecallHistoryDialog(repository,plan.ref.notebookId,busy,{if(!busy)history=false}){entry,reason->vm.withdraw(entry,reason)}
    sourceViewer?.let{source->StudySnapshotViewer(source){sourceViewer=null}}
    if(endConfirm)AlertDialog(onDismissRequest={endConfirm=false},title={Text("结束这轮复习？")},text={RecallWindowPermit();Text("本次作答先保存；已有作答与成绩保留。未做题保留为未完成，不伪造成绩。")},confirmButton={TextButton({endConfirm=false;vm.closeSession()},modifier=Modifier.testTag("recall-end-confirm")){Text("结束本轮")}},dismissButton={TextButton({endConfirm=false}){Text("继续")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RecallHistoryDialog(repository:RecallStudyRepository,book:String,busy:Boolean,dismiss:()->Unit,withdraw:(RecallHistoryEntry,String)->Unit){
    var due by rememberSaveable{mutableStateOf(RecallDueFilter.ALL)};var kind by rememberSaveable{mutableStateOf<RecallQuestionKind?>(null)}
    var hinted by rememberSaveable{mutableStateOf<Boolean?>(null)};var from by rememberSaveable{mutableStateOf("")};var through by rememberSaveable{mutableStateOf("")}
    var page by remember{mutableStateOf<RecallHistoryPage?>(null)};var error by remember{mutableStateOf<String?>(null)};var reload by remember{mutableIntStateOf(0)}
    var offset by rememberSaveable{mutableIntStateOf(0)};var detail by remember{mutableStateOf<RecallLoadedAttempt?>(null)};var correction by remember{mutableStateOf<RecallHistoryEntry?>(null)}
    var reason by rememberSaveable{mutableStateOf("更正误点评分")};val scope=rememberCoroutineScope();val zone=remember{ZoneId.systemDefault()}
    LaunchedEffect(book){repository.observeHistory(book).collect{reload++}}
    LaunchedEffect(due,kind,hinted,reload,offset){try{val range=RecallHistoryFilter.localDays(from,through,zone.id)
        page=withContext(Dispatchers.IO){repository.filteredHistory(book,RecallHistoryFilter(due,kind,hinted,range.first,range.second),System.currentTimeMillis(),offset)};error=null
    }catch(c:CancellationException){throw c}catch(e:Exception){error=recallError(e.message)}}
    AlertDialog(onDismissRequest=dismiss,title={Text("复习历史 · ${zone.id}")},text={Column(Modifier.heightIn(max=600.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        RecallWindowPermit()
        FlowRow{RecallDueFilter.entries.forEach{v->FilterChip(due==v,{due=v;offset=0},label={Text(v.label)})}}
        FlowRow{FilterChip(kind==null,{kind=null;offset=0},label={Text("全部题型")});RecallQuestionKind.entries.forEach{v->FilterChip(kind==v,{kind=v;offset=0},label={Text(v.label)})}}
        FlowRow{listOf(null,false,true).forEach{value->FilterChip(hinted==value,{hinted=value;offset=0},label={Text(when(value){null->"全部提示状态";true->"用过提示";false->"未用提示"})})}}
        OutlinedTextField(from,{from=it},label={Text("开始日期 YYYY-MM-DD")});OutlinedTextField(through,{through=it},label={Text("截至日期（包含当天）")})
        TextButton({offset=0;reload++}){Text("应用时间段 / 刷新")};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        page?.let{result->Text("${result.total} 条匹配记录 · 本页 ${result.entries.size} 条")
            result.entries.forEach{entry->
                HorizontalDivider();Text("${entry.kind.label} · ${entry.prompt}")
                val at=entry.row.completedAt?:entry.row.startedAt?:0
                Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(at)))
                Text("${if(entry.row.requestedQuality!=null&&entry.row.effectiveQuality==null)"临时练习（不改排程）"else RecallMode.valueOf(entry.row.mode).label} · ${entry.row.status}${if(entry.withdrawn)" · 已撤销评分（原记录保留）"else""}")
                Text("自评 ${entry.row.requestedQuality?:"未评分"} / 有效 ${entry.row.effectiveQuality?:"不改排程"}${if(entry.row.hintMask!=0)" · 已用提示，有效分最高2"else""}")
                TextButton({scope.launch{try{detail=withContext(Dispatchers.IO){repository.loadAttempt(entry.row.id)}}catch(c:CancellationException){throw c}catch(e:Exception){error=recallError(e.message)}}}){Text("固定答案与本次作答")}
                if(entry.row.status==RecallAttemptStatus.GRADED.name&&!entry.withdrawn)TextButton({correction=entry},enabled=!busy){Text("更正：撤销这次评分")}
            }
            Row{TextButton({offset=(offset-100).coerceAtLeast(0)},enabled=offset>0){Text("上一页")};TextButton({offset+=100},enabled=offset+result.entries.size<result.total){Text("下一页")}}
        }
    }},confirmButton={TextButton(dismiss){Text("返回复习")}})
    detail?.let{current->AlertDialog(onDismissRequest={detail=null},title={Text("固定版本 · 本次历史")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())){
        RecallWindowPermit()
        Text(current.spec.prompt);Text("知识版本 ${current.spec.cardRevision} · 题义版本 ${current.row.specRevision}")
        Text("固定答案：${current.card.body}");Text("固定共享注释："+current.presentation?.annotation.orEmpty().ifEmpty{if(current.spec.presentationKnown)"（空）"else"旧格式未固定"});Text("本次文字作答：${current.row.answerText.ifEmpty{"（无文字）"}}")
        RecallAnswerPad(current.row.id,current.row.answerInk,false,{})
        Text("提示事实："+current.hints.joinToString("、"){when(it.kind){"ANSWER_COMPARE"->"封存作答后正常对照";"ORIGINAL_COMPARE"->"对照阶段查看原文";else->runCatching{RecallHint.valueOf(it.kind).label}.getOrDefault(it.kind)}})
        current.correction?.let{Text("更正理由：${it.reason}")}
    }},confirmButton={TextButton({detail=null}){Text("返回历史")}})}
    correction?.let{entry->AlertDialog(onDismissRequest={correction=null},title={Text("撤销并保留更正记录")},text={Column{RecallWindowPermit();Text("恢复此评分之前的排程；有后继有效评分时拒绝覆盖。原作答和评分保留。")
        OutlinedTextField(reason,{reason=it},label={Text("更正理由")})}},confirmButton={TextButton({withdraw(entry,reason);correction=null;reload++},enabled=!busy&&reason.isNotBlank()&&reason.length<=500){Text("确认撤销")}},dismissButton={TextButton({correction=null}){Text("取消")}})}
}
