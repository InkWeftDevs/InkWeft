// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.FrozenBranchReviewQuestion

/** Completed-round inspection uses the original frozen refs and ledger; it never marks or starts a round. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BranchReviewDetailsDialog(round:BranchReviewRound,selection:BranchReviewRetrySelection?,
    choose:(BranchReviewRetrySelection?)->Unit,dismiss:()->Unit) {
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var loaded by remember(round.roundId){mutableStateOf<List<FrozenBranchReviewQuestion>?>(null)}
    var failed by remember(round.roundId){mutableStateOf(false)}
    var attempt by remember(round.roundId){mutableIntStateOf(0)}
    val results=selection?.let(round::retryResults)?:round.results
    LaunchedEffect(round.roundId,attempt) {
        loaded=null;failed=false
        try { loaded=withContext(Dispatchers.IO){app.branchReview.load(round.plan)} }
        catch(c:CancellationException){throw c}
        catch(_:Exception){failed=true}
    }
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().testTag("branch-review-details")) {
            Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("本轮题目与结果",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(dismiss,modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-details-close")){Text("返回结果")}
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("branch-review-details-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item("intro"){Text("本轮固定题目版本与手工结果，不展示答案。",style=MaterialTheme.typography.bodySmall,color=Quiet)}
                    item("filters"){FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        FilterChip(selection==null,{choose(null)},label={Text("全部")},
                            modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-details-all"))
                        FilterChip(selection==BranchReviewRetrySelection.REVIEW,{choose(BranchReviewRetrySelection.REVIEW)},label={Text("仍需复习")},
                            modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-details-review"))
                        FilterChip(selection==BranchReviewRetrySelection.SKIPPED,{choose(BranchReviewRetrySelection.SKIPPED)},label={Text("跳过")},
                            modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-details-skipped"))
                    }}
                    item("count"){Text("本范围 ${results.size} / ${round.results.size} 道题",color=Quiet,modifier=Modifier.testTag("branch-review-details-count"))}
                    val questions=loaded
                    when {
                        failed -> item("error") {
                            Text("本轮固定题目暂未完整读取，结果记录保留；没有替换成当前新题。",
                                modifier=Modifier.testTag("branch-review-details-error"))
                            TextButton({attempt++},modifier=Modifier.heightIn(min=48.dp).testTag("branch-review-details-retry")){Text("重试读取本轮题目")}
                        }
                        questions==null -> item("loading"){Box(Modifier.height(120.dp).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
                        results.isEmpty() -> item("empty"){Text("这个范围没有题目。",color=Quiet,modifier=Modifier.testTag("branch-review-details-empty"))}
                        else -> items(results,key={round.plan.entries[it.index].questionId}) { result ->
                            val frozen=questions[result.index]
                            val question=frozen.question.data() as KnowledgeData.Question
                            val label=when(result.kind) {
                                BranchReviewResultKind.CONFIRMED_UNDERSTOOD -> "本次已理解"
                                BranchReviewResultKind.CONFIRMED_REVIEW -> "仍需复习"
                                BranchReviewResultKind.SKIPPED -> "普通跳过 · 未标记"
                                BranchReviewResultKind.REJECTED_SKIPPED -> "未提交后跳过 · 未标记"
                            }
                            OutlinedCard(Modifier.fillMaxWidth().testTag("branch-review-detail-${frozen.reference.questionId}")) {
                                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text("第 ${result.index+1} 题 · $label",style=MaterialTheme.typography.labelLarge,
                                        modifier=Modifier.testTag("branch-review-detail-outcome-${frozen.reference.questionId}"))
                                    Text(question.prompt,modifier=Modifier.testTag("branch-review-detail-prompt-${frozen.reference.questionId}"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
