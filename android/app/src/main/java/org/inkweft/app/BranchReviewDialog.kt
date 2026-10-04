// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*
import java.io.*
import java.util.UUID

/** Only bounded identities and revisions enter the saved-state Bundle, never answer bodies. */
internal val BranchReviewPlanSaver = Saver<BranchReviewPlan?, ByteArray>(
    save = { plan -> plan?.let {
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { d ->
            d.writeInt(2); d.writeUTF(it.ref.notebookId); d.writeUTF(it.ref.mapId.orEmpty())
            d.writeUTF(it.branchId.orEmpty()); d.writeUTF(it.title)
            d.writeInt(it.cardCount); d.writeInt(it.withoutQuestionCount); d.writeInt(it.entries.size)
            it.entries.forEach { entry ->
                d.writeUTF(entry.questionId); d.writeLong(entry.questionRevision)
                d.writeUTF(entry.cardId); d.writeLong(entry.cardRevision)
            }
            d.writeUTF(it.scope.name); d.writeInt(it.totalQuestionCount); d.writeInt(it.otherStateOnlyCardCount)
        } }.toByteArray().also { bytes -> require(bytes.size <= 256 * 1024) }
    } },
    restore = { bytes -> runCatching {
        require(bytes.size <= 256 * 1024)
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val version = d.readInt(); require(version in 1..2)
            val ref = MapRef(d.readUTF(), d.readUTF().ifEmpty { null })
            val branch = d.readUTF().ifEmpty { null }; val title = d.readUTF()
            val cards = d.readInt(); val unasked = d.readInt(); val count = d.readInt()
            require(count in 0..2000)
            val entries = List(count) { BranchReviewEntryRef(d.readUTF(), d.readLong(), d.readUTF(), d.readLong()) }
            val scope = if(version == 2) ReviewQuestionScope.valueOf(d.readUTF()) else ReviewQuestionScope.ALL
            val total = if(version == 2) d.readInt() else count
            val otherStates = if(version == 2) d.readInt() else 0
            require(d.read() == -1)
            BranchReviewPlan(ref, branch, title, cards, unasked, entries, scope, total, otherStates)
        }
    }.getOrNull() }
)

/** Bounded refs plus a compact ledger; question/answer bodies never enter saved state. */
internal val BranchReviewRoundSaver = Saver<BranchReviewRound, ByteArray>(
    save = { round ->
        val plan = checkNotNull(with(BranchReviewPlanSaver) { save(round.plan) })
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { d ->
            fun uuid(value:String) { val id=UUID.fromString(value);d.writeLong(id.mostSignificantBits);d.writeLong(id.leastSignificantBits) }
            d.writeInt(1);uuid(round.roundId);d.writeInt(plan.size);d.write(plan)
            d.writeInt(round.results.size)
            round.results.forEach { result -> d.writeByte(result.kind.ordinal);result.operationId?.let(::uuid) }
            d.writeBoolean(round.pending!=null)
            round.pending?.let { pending -> uuid(pending.operationId);d.writeByte(pending.state.ordinal);d.writeBoolean(pending.rejected) }
        } }.toByteArray().also { require(it.size<=256*1024) }
    },
    restore = { bytes ->
        require(bytes.size<=256*1024)
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            fun uuid()=UUID(d.readLong(),d.readLong()).toString()
            require(d.readInt()==1);val roundId=uuid();val size=d.readInt()
            require(size in 1..256*1024 && size<=d.available())
            val planBytes=ByteArray(size);d.readFully(planBytes)
            val plan=checkNotNull(BranchReviewPlanSaver.restore(planBytes))
            val count=d.readInt();require(count in 0..plan.entries.size)
            val results=List(count) { index ->
                val kind=BranchReviewResultKind.entries[d.readUnsignedByte()]
                BranchReviewResult(index,kind,if(kind==BranchReviewResultKind.SKIPPED)null else uuid())
            }
            val pending=if(d.readBoolean())BranchReviewPending(count,uuid(),ManualState.entries[d.readUnsignedByte()],d.readBoolean())else null
            require(d.read()==-1)
            BranchReviewRound(roundId,plan,results,pending)
        }
    }
)

/** The four recall entries share labels and touch sizes; the chosen scope belongs to their workspace. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReviewScopeSelector(scope:ReviewQuestionScope,enabled:Boolean,prefix:String,choose:(ReviewQuestionScope)->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            FilterChip(scope==ReviewQuestionScope.ALL,{choose(ReviewQuestionScope.ALL)},enabled=enabled,
                label={Text("全部问题")},modifier=Modifier.heightIn(min=48.dp).testTag("$prefix-scope-all"))
            FilterChip(scope==ReviewQuestionScope.REVIEW_ONLY,{choose(ReviewQuestionScope.REVIEW_ONLY)},enabled=enabled,
                label={Text("仅待复习")},modifier=Modifier.heightIn(min=48.dp).testTag("$prefix-scope-review"))
        }
        Text(if(scope==ReviewQuestionScope.REVIEW_ONLY)"仅复习标为「待复习」的问题"else"复习这个范围内的全部问题",
            style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("$prefix-scope-description"))
    }
}

/** Fixed recall identities with session-only permission to inspect related clues. */
@Composable internal fun BranchReviewDialog(plan:BranchReviewPlan, dismiss:()->Unit, showSummary:Boolean=true,showCollectionScope:Boolean=false,showCardScope:Boolean=false,consultedOriginal:Boolean=false,workModes:(@Composable (Boolean)->Unit)?=null) {
    val sessionKey=remember(plan){plan.entries.joinToString(";"){"${it.questionId}:${it.questionRevision}:${it.cardId}:${it.cardRevision}"}}
    key(plan.ref.notebookId,plan.ref.mapId,plan.branchId,plan.scope,sessionKey,showCardScope){
        RecallWindowIsolation { BranchReviewContent(plan,dismiss,showSummary,showCollectionScope,showCardScope,consultedOriginal,workModes) }
    }
}

@Composable private fun BranchReviewContent(plan:BranchReviewPlan,dismiss:()->Unit,showSummary:Boolean,showCollectionScope:Boolean,showCardScope:Boolean,consultedOriginal:Boolean,workModes:(@Composable (Boolean)->Unit)?) {
    val round=rememberSaveable(stateSaver=BranchReviewRoundSaver){mutableStateOf(BranchReviewRound(UUID.randomUUID().toString(),plan))}
    var retryKind by rememberSaveable{mutableStateOf<String?>(null)}
    key(round.value.roundId){
        BranchReviewRoundContent(round,dismiss,showSummary&&retryKind==null,showCollectionScope,showCardScope,retryKind,consultedOriginal,workModes){next,selection->
            retryKind=selection.name
            round.value=BranchReviewRound(UUID.randomUUID().toString(),next)
        }
    }
}

@Composable private fun BranchReviewRoundContent(roundState:MutableState<BranchReviewRound>,dismiss:()->Unit,
    showSummary:Boolean,showCollectionScope:Boolean,showCardScope:Boolean,retryKind:String?,consultedOriginal:Boolean,workModes:(@Composable (Boolean)->Unit)?,retryReady:(BranchReviewPlan,BranchReviewRetrySelection)->Unit) {
    var round by roundState
    val visibleRoundId=round.roundId
    val visiblePendingOperation=round.pending?.operationId
    val plan=round.plan
    val index=round.index
    val context=LocalContext.current
    val app=context.applicationContext as InkWeftApplication
    val vm:KnowledgeViewModel=viewModel(key="branch-review-${plan.ref.notebookId}",
        factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    BindKnowledgeReadLock(vm)
    val readLock=rememberBookReadLock(plan.ref.notebookId)
    val ui by vm.ui.collectAsStateWithLifecycle()
    var started by rememberSaveable { mutableStateOf(!showSummary) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var answerEnterEvent by remember(index){mutableIntStateOf(0)}
    var answerEnterPending by remember(index){mutableStateOf(false)}
    var hints by rememberSaveable { mutableStateOf(false) }
    var sourceOpen by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<List<FrozenBranchReviewQuestion>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var checking by remember{mutableStateOf(false)}
    var checkError by remember{mutableStateOf(false)}
    var checkAttempt by remember{mutableIntStateOf(0)}
    var retryPreparing by remember{mutableStateOf(false)}
    var retryError by remember{mutableStateOf(false)}
    var resultsOpen by rememberSaveable { mutableStateOf(false) }
    var resultSelection by rememberSaveable { mutableStateOf<BranchReviewRetrySelection?>(null) }
    val scope=rememberCoroutineScope()
    val unresolved=round.pending?.rejected==false
    val rejected=round.pending?.rejected==true
    val busy=ui.busy||ui.unknown||ui.completedOperation!=null||unresolved||checking||retryPreparing
    ReadLockGuard(readLock,"branch-review-${plan.ref.notebookId}",busy)
    fun requestDismiss(){val live=vm.ui.value;if(round.roundId==visibleRoundId&&!live.busy&&!live.unknown&&live.completedOperation==null&&
        round.pending?.rejected!=false&&!checking&&!retryPreparing)dismiss()}
    fun accept(next:BranchReviewRound){
        if(round.roundId!=visibleRoundId||next.roundId!=visibleRoundId)return
        if(next.index!=round.index){revealed=false;hints=false;sourceOpen=false;answerEnterPending=false}
        round=next
    }
    fun retry(selection:BranchReviewRetrySelection){
        if(round.roundId!=visibleRoundId||!round.complete||vm.ui.value.busy||vm.ui.value.unknown||vm.ui.value.completedOperation!=null||retryPreparing)return
        val original=round;retryPreparing=true;retryError=false
        scope.launch{
            try{
                val next=withContext(Dispatchers.IO){app.branchReview.prepareRetry(original,selection)}
                if(round===original&&round.roundId==visibleRoundId)retryReady(next,selection)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){retryError=true}
            finally{retryPreparing=false}
        }
    }
    val shield=LocalRecallWindowShield.current
    SideEffect { shield?.setActive(plan.entries.isNotEmpty()&&(!started||index<plan.entries.size)) }

    LaunchedEffect(plan,started,loadAttempt) {
        if(!started||round.complete)return@LaunchedEffect
        loaded=null;loadError=false
        try { loaded=withContext(Dispatchers.IO){app.branchReview.load(plan)} }
        catch(c:CancellationException){throw c}
        catch(_:Exception){loadError=true}
    }
    LaunchedEffect(round.pending,ui.completedOperation,ui.rejectedOperation,ui.busy,ui.unknown,checkAttempt) {
        if(round.roundId!=visibleRoundId)return@LaunchedEffect
        val original=round
        val pending=original.pending
        // A recreation after saving a result but before consuming the writer only acknowledges that same op.
        ui.completedOperation?.let { operation ->
            if(original.results.any{it.operationId==operation&&(it.kind==BranchReviewResultKind.CONFIRMED_REVIEW||it.kind==BranchReviewResultKind.CONFIRMED_UNDERSTOOD)})vm.consumed(operation)
        }
        if(pending==null)return@LaunchedEffect
        if(pending.rejected){vm.consumedRejection(pending.operationId);return@LaunchedEffect}
        if(ui.busy||ui.unknown)return@LaunchedEffect
        if(ui.rejectedOperation==pending.operationId){
            accept(original.reject(pending.operationId));vm.consumedRejection(pending.operationId);return@LaunchedEffect
        }
        if(ui.completedOperation!=pending.operationId&&checkAttempt==0)return@LaunchedEffect
        checking=true;checkError=false
        try{
            val confirmed=withContext(Dispatchers.IO){app.branchReview.confirmRoundResult(original)}
            if(round===original&&round.roundId==visibleRoundId&&round.pending==pending){accept(confirmed);vm.consumed(pending.operationId)}
        }catch(c:CancellationException){throw c}
        catch(_:Exception){checkError=true}
        finally{checking=false}
    }
    val current=loaded?.getOrNull(index)
    val cluesVisible=hints||revealed
    var source by remember { mutableStateOf<StudySourceRow?>(null) }
    LaunchedEffect(current?.reference?.cardId) {
        source=null
        if(current!=null)try { source=withContext(Dispatchers.IO){app.study.source(current.reference.cardId)} }
        catch(c:CancellationException){throw c}
        catch(_:Exception){source=null}
    }

    Dialog(onDismissRequest={requestDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        RecallWindowPermit()
        Surface(Modifier.fillMaxSize().testTag("manual-review")) {
            Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(if(started)"手动回忆 · ${(index+1).coerceAtMost(plan.entries.size)} / ${plan.entries.size}" else "复习范围",
                        Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={requestDismiss()},enabled=!busy,modifier=Modifier.testTag("branch-review-close")){Text("退出回忆")}
                }
                workModes?.invoke(!busy)
                if(consultedOriginal)Text("已离开回忆查看资料；本轮按使用提示记录，重新进入不会清除此标记。",
                    style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("review-consulted-original"))
                retryKind?.let{Text(if(it==BranchReviewRetrySelection.REVIEW.name)"再练本轮仍需复习 · 固定题目与答案版本"else"再练本轮跳过 · 固定题目与答案版本",
                    style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("branch-review-retry-origin"))}
                key(started,index) {
                    val questionContent:@Composable ColumnScope.()->Unit={
                        if(ui.busy||checking||retryPreparing)LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(ui.unknown){
                            Text("标记结果待核对，仍保留此题。")
                            TextButton(onClick={if(round.roundId==visibleRoundId&&round.pending?.operationId==visiblePendingOperation&&
                                vm.pendingOperationId==visiblePendingOperation)vm.retry()},
                                enabled=!ui.busy&&vm.pendingOperationId==round.pending?.operationId,
                                modifier=Modifier.testTag("branch-review-retry")){Text("核对原操作")}
                        }else if(unresolved&&!ui.busy&&!checking){
                            Text(if(checkError)"原回执或固定版本暂未核准，仍保留此题与原操作。"else"正在核对本题原结果。",
                                modifier=Modifier.testTag("branch-review-confirm-pending"))
                            TextButton(onClick={checkAttempt++},modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-confirm-retry")){Text("重新核对原结果")}
                        }
                        if(!started){
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                Text("本轮回忆范围",style=MaterialTheme.typography.headlineSmall)
                                if(showCollectionScope||showCardScope)Text(plan.title,modifier=Modifier.testTag("branch-review-scope"),style=MaterialTheme.typography.titleMedium)
                                Text(if(plan.scope==ReviewQuestionScope.REVIEW_ONLY)"仅复习标为「待复习」的问题"else"全部问题",
                                    modifier=Modifier.testTag("branch-review-filter"))
                                Text("${plan.cardCount} 张卡片 · ${plan.entries.size} 道问题 · ${plan.withoutQuestionCount} 张未设题卡片",
                                    modifier=Modifier.testTag("branch-review-counts"))
                                Text("范围内共 ${plan.totalQuestionCount} 道问题",modifier=Modifier.testTag("branch-review-total-questions"))
                                Text("仅有已理解或待整理问题：${plan.otherStateOnlyCardCount} 张卡片",modifier=Modifier.testTag("branch-review-other-state-cards"))
                                Text(if(showCardScope)"仅此卡片的问题。同一卡片的不同问题分别保留。"else if(showCollectionScope)"相同问题只出现一次，同一卡片的不同问题分别保留。"else"包含折叠下级。相同问题只出现一次，同一卡片的不同问题分别保留。")
                                Text("本轮固定问题与答案版本。标题、原页和图中文字先遮挡；查看提示可能包含答案。",color=Quiet)
                                Text("查看提示、显示答案均不写标记；手工标记不安排到期时间。",color=Quiet)
                                if(plan.entries.isEmpty()){
                                    if(plan.totalQuestionCount>0)Text("本轮没有待复习问题。可退出后选择“全部问题”。",
                                        modifier=Modifier.testTag("branch-review-empty-filter"))
                                    else Text("这部分尚无问题。可从摘要卡的“属性与回忆”添加问题，原内容不变。")
                                }
                            }
                            Button(onClick={started=true},enabled=plan.entries.isNotEmpty()&&!busy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("branch-review-start")){Text("开始回忆")}
                        }else if(round.complete){
                            val counts=round.counts
                            Text("本轮已结束",Modifier.testTag("branch-review-ended"))
                            Text("本轮共 ${counts.total} 题",Modifier.testTag("branch-review-result-total"))
                            Text("本次已理解 · ${counts.understood}",Modifier.testTag("branch-review-result-understood"))
                            Text("仍需复习 · ${counts.review}",Modifier.testTag("branch-review-result-review"))
                            Text("未标记跳过 · ${counts.unmarked}",Modifier.testTag("branch-review-result-skipped"))
                            Text("其中普通跳过 ${counts.skipped} · 未提交后跳过 ${counts.rejectedSkipped}",
                                Modifier.testTag("branch-review-result-rejected"))
                            Text("仅记录这一轮的手工选择；不计算准确率，也不安排到期时间。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                            TextButton(onClick={resultsOpen=true},enabled=!busy,
                                modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-open-details")){Text("查看本轮题目与结果")}
                            if(retryError)Text("固定题目、答案或原回执未能完整核准，保留本轮结果，请重试。",
                                modifier=Modifier.testTag("branch-review-retry-error"))
                            OutlinedButton(onClick={retry(BranchReviewRetrySelection.REVIEW)},enabled=!busy&&counts.review>0,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("branch-review-retry-review")){Text("再练本轮仍需复习 · ${counts.review}")}
                            OutlinedButton(onClick={retry(BranchReviewRetrySelection.SKIPPED)},enabled=!busy&&counts.unmarked>0,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("branch-review-retry-skipped")){Text("再练本轮跳过 · ${counts.unmarked}")}
                        }else if(loadError){
                            Text("本轮固定版本读取失败，未替换成新题或新答案。")
                            TextButton(onClick={loadAttempt++},modifier=Modifier.testTag("branch-review-load-retry")){Text("重试读取此轮")}
                        }else if(loaded==null){
                            Box(Modifier.heightIn(min=120.dp)){CircularProgressIndicator()}
                        }else if(current==null){
                            Text("本题固定版本未能读取，未将本轮标为完成。")
                        }else{
                            val question=current.question.data() as KnowledgeData.Question
                            Text(question.prompt,fontSize=24.sp,modifier=Modifier.testTag("review-question"))
                            if(cluesVisible)Text(current.card.title,style=MaterialTheme.typography.titleMedium,
                                modifier=Modifier.testTag("review-card-title"))
                            else Text("线索已隐藏",color=Quiet,modifier=Modifier.testTag("review-clues-hidden"))
                            if(revealed){
                                val enterEvent=answerEnterEvent
                                ClickEnterContent(enterEvent,answerEnterPending,{if(answerEnterEvent==enterEvent)answerEnterPending=false},
                                    Modifier.testTag("review-answer-enter")){
                                    Text(current.card.body.ifBlank{if(source!=null)"此卡未填写文字答案；请在下方「摘录」查看保存原迹，或打开来源核对。"else"此卡未填写文字答案。结束本轮后，可在卡片详情中补充。"},fontSize=18.sp,modifier=Modifier.testTag("review-answer"))
                                }
                            }
                            if(!revealed){
                                if(hints){
                                    Text("提示已开放 · 可能含答案，未写标记",
                                        color=Quiet,modifier=Modifier.testTag("review-hint-status"))
                                }
                                Button(onClick={answerEnterEvent++;answerEnterPending=true;revealed=true},enabled=!busy,
                                    modifier=Modifier.widthIn(max=260.dp).fillMaxWidth().heightIn(min=48.dp).testTag("reveal-answer")){Text("显示答案")}
                            }
                            if(rejected)Text("题目或答案版本已变化，或资料不可用，标记未提交。本轮仍显示固定版本，可跳过此题。",
                                modifier=Modifier.testTag("branch-review-conflict"))
                            if(cluesVisible&&source!=null)OutlinedButton(onClick={sourceOpen=true},enabled=!busy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("review-open-source")){Text("查看当前原页 · 返回此题")}
                            if(revealed){
                                Text("手工标记，不安排到期时间",style=MaterialTheme.typography.bodySmall,color=Quiet)
                                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    listOf(ManualState.REVIEW to "仍需复习",ManualState.UNDERSTOOD to "本次已理解").forEach { (state,label)->
                                        OutlinedButton(onClick={
                                            if(round.roundId==visibleRoundId&&round.index==index&&round.pending==null){
                                                vm.submitReview(plan.ref.notebookId,current.question,question.copy(state=state),current.reference.cardRevision)?.let{operation->
                                                    checkAttempt=0;checkError=false;round=round.begin(index,operation,state)
                                                }
                                            }
                                        },enabled=!busy&&!rejected&&round.pending==null,
                                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("branch-review-mark-${state.name}")){Text(label)}
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                                if(!revealed){
                                    if(hints)TextButton(onClick={hints=false;sourceOpen=false},enabled=!busy,
                                        modifier=Modifier.weight(1f,fill=false).heightIn(min=48.dp).testTag("review-hide-hint")){Text("收起提示")}
                                    else TextButton(onClick={hints=true},enabled=!busy,
                                        modifier=Modifier.weight(1f,fill=false).heightIn(min=48.dp).testTag("review-show-hint")){Text("查看提示（可能含答案）")}
                                }
                                TextButton(onClick={if(round.roundId==visibleRoundId&&!vm.ui.value.busy&&!vm.ui.value.unknown&&
                                    round.pending?.rejected!=false&&!checking&&!retryPreparing)accept(round.skip(index))},enabled=!busy,
                                    modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-skip")){Text("跳过此题")}
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide=maxWidth>=840.dp&&LocalDensity.current.fontScale<1.5f
                        if(wide&&started&&current!=null){
                            Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement=Arrangement.spacedBy(12.dp),content=questionContent)
                                RecallContextPanel(plan,current,cluesVisible,source,
                                    Modifier.weight(1.1f).fillMaxHeight())
                            }
                        }else{
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                questionContent()
                                if(started&&current!=null)RecallContextPanel(plan,current,cluesVisible,source,
                                    Modifier.fillMaxWidth().height(460.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if(resultsOpen&&round.complete)BranchReviewDetailsDialog(round,resultSelection,{resultSelection=it}){resultsOpen=false}
    if(sourceOpen&&cluesVisible)source?.let{ReviewSourceDialog(it,dismiss={sourceOpen=false},recallNotebookId=plan.ref.notebookId)}
}
