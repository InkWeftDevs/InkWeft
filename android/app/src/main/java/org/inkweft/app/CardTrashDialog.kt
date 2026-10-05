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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.data.*

internal fun cardTrashRejection(reason:String):String?=when(reason){
    "TRASH_PREVIEW_REQUIRED"->"旧回收操作没有影响预览，未执行。请重新打开回收预览并确认。"
    "TRASH_IMPACT_CHANGED"->"引用或题目已变化，未回收。请刷新影响清单后重新确认。"
    "TRASH_READ_ONLY"->"当前为阅读模式，原操作尚未提交。返回书写后重新预览回收。"
    else->null
}

/** All three recycle entries share this read-only preview and the same frozen StudyCommand. */
@Composable internal fun CardTrashDialog(vm:StudyViewModel,cardId:String,ready:Boolean,onDismiss:()->Unit){
    val ui by vm.ui.collectAsStateWithLifecycle()
    val readLock=rememberBookReadLock(vm.book)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    val latestReady by rememberUpdatedState(ready)
    var preview by remember(vm,cardId){mutableStateOf<CardTrashPreview?>(null)}
    var reload by remember(vm,cardId){mutableIntStateOf(0)}
    var loading by remember(vm,cardId){mutableStateOf(true)}
    var message by remember(vm,cardId){mutableStateOf<String?>(null)}
    var submitted by rememberSaveable(vm.book,cardId){mutableStateOf(false)}
    val waiting=ui.busy||ui.unknown
    ReadLockGuard(readLock,"trash-preview-$cardId",blocked=true,draft=true)
    LaunchedEffect(vm,cardId,reload){
        loading=true;preview=null;message=null
        try{preview=withContext(Dispatchers.IO){vm.repo.previewTrash(vm.book,cardId)}}
        catch(c:CancellationException){throw c}
        catch(_:Exception){message="无法读取回收影响，卡片保持不变。请重新读取。"}
        finally{loading=false}
    }
    val recycled=ui.cards.any{it.id==cardId&&it.trashedAt!=null}
    LaunchedEffect(submitted,ui.busy,ui.unknown,ui.completed,ui.message,recycled){
        if(submitted&&!waiting){
            if(ui.completed==cardId||recycled){onDismiss()}
            else if(ui.message!=null){message=ui.message;preview=null;submitted=false}
        }
    }
    AlertDialog(onDismissRequest={if(!waiting&&!submitted)onDismiss()},modifier=Modifier.testTag("card-trash-dialog"),
        title={Text("移入卡片回收区？")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("回收的是整张内容卡，不是删除一处展示。卡片正文、个人注释、摘录快照、原页笔迹、双链及题目记录都会保留。")
            Text("回收后，这张卡暂不能从引用打开或参加复习；固定版本的历史快照仍保留。")
            Text("恢复：打开此笔记的导图 → 导图管理 → 整理 → 容量与整理 → 查看卡片回收区 → 选择此卡 → 恢复卡片。",modifier=Modifier.testTag("card-trash-recovery"))
            if(loading||ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let{p->
                Text("${p.card.title} · 卡片修订 ${p.card.revision}")
                @Composable fun affected(title:String,rows:List<CardTrashAffected>){
                    Text("$title · ${rows.size}",style=MaterialTheme.typography.titleSmall)
                    if(rows.isEmpty())Text("无",style=MaterialTheme.typography.bodySmall)
                    rows.forEach{row->Text("${row.label}\n记录 ${row.id} · 修订 ${row.revision}",modifier=Modifier.testTag("card-trash-impact-${row.id}"),style=MaterialTheme.typography.bodySmall)}
                }
                affected("受影响的双链（含其他笔记的引用）",p.references)
                affected("受影响的题目",p.questions)
                if(p.occurrences.isNotEmpty()){
                    Text("以下展示仍在使用此卡，请先移除展示位置，再重新预览；移除展示会保留卡片。")
                    affected("阻止回收的展示位置",p.occurrences)
                }
            }
            message?.let{Text(it,modifier=Modifier.testTag("card-trash-message"))}
            if(ui.unknown)TextButton(vm::retry,enabled=!ui.busy,modifier=Modifier.testTag("card-trash-retry")){Text("核对原回收操作")}
            if(!waiting&&!submitted)TextButton({vm.clear();reload++},enabled=!loading,modifier=Modifier.testTag("card-trash-refresh")){Text("刷新影响清单")}
        }},
        confirmButton={TextButton({
            val p=preview
            if(p!=null&&latestReady&&readLock.canWrite&&!loading&&!waiting&&!submitted&&p.occurrences.isEmpty()){
                if(vm.trash(p))submitted=true
            }
        },enabled=ready&&!readOnly&&!loading&&!waiting&&!submitted&&!ui.loading&&!ui.readFailed&&preview?.occurrences?.isEmpty()==true,
            modifier=Modifier.testTag("card-trash-confirm")){Text("确认回收")}},
        dismissButton={TextButton(onDismiss,enabled=!waiting&&!submitted,modifier=Modifier.testTag("card-trash-cancel")){Text("取消")}})
}
