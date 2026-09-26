// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

@Composable
internal fun PageEditDialog(source:NotebookPageRow,kind:PageEditKind,pages:List<NotebookPageRow>,dismiss:()->Unit,
    apply:(PageInsertLocation,String?,String,Long)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    // The preview is an explicit snapshot, not silently rebased to another order.
    val original=remember(source.id,kind){pages.toList()}
    val originalTrash=remember(source.id,kind){source.trashedAt}
    val order=remember(original){InsertPages.orderHash(original.map{it.id})}
    val candidates=original.filter{kind!=PageEditKind.MOVE||it.id!=source.id}
    var where by remember{mutableStateOf(if(kind==PageEditKind.COPY)PageInsertLocation.AFTER else PageInsertLocation.END)}
    var anchor by remember{mutableStateOf(if(kind==PageEditKind.COPY)source.id else candidates.firstOrNull()?.id)}
    var head by remember(source.id,kind){mutableStateOf<Long?>(null)}
    var error by remember(source.id,kind){mutableStateOf<String?>(null)}
    var readAttempt by remember(source.id,kind){mutableIntStateOf(0)}
    LaunchedEffect(source.id,kind,readAttempt){
        head=null;error=null
        try{
            val value=withContext(Dispatchers.IO){withTimeoutOrNull(8_000){app.pages.inkRevision(source.id)}}
            if(value==null)error="页面版本读取超时，未执行操作。可以重试核对或取消。" else head=value
        }catch(c:CancellationException){throw c}
        catch(_:Exception){error="无法读取页面版本，未修改原资料。请重试核对。"}
    }
    val index=when(where){PageInsertLocation.START->0;PageInsertLocation.END->candidates.size;else->candidates.indexOfFirst{it.id==anchor}.let{if(it<0)-1 else it+if(where==PageInsertLocation.AFTER)1 else 0}}
    val verb=when(kind){PageEditKind.MOVE->"移动页面";PageEditKind.COPY->"复制此页";PageEditKind.TRASH->"移入页面回收区";PageEditKind.RESTORE->"恢复页面"}
    val stale=order!=InsertPages.orderHash(pages.map{it.id}) || source.trashedAt!=originalTrash ||
        ((kind==PageEditKind.RESTORE)!=(source.trashedAt!=null))
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("page-edit-dialog"),title={Text(verb)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                PageThumb(source);Text("${if(kind==PageEditKind.RESTORE)"原"else""}第 ${source.position+1} 页",fontSize=14.sp)
            }
            if(kind==PageEditKind.TRASH)Text("只将此页移出阅读顺序。笔迹、局部擦除、历史与页面身份仍保留，可从页目录的页面回收区恢复。当前页被回收时将打开相邻页；最后一张可用页不可回收。",fontSize=13.sp)
            else {
                Text("放置位置",fontSize=14.sp)
                for(pair in listOf(listOf(PageInsertLocation.BEFORE,PageInsertLocation.AFTER),listOf(PageInsertLocation.START,PageInsertLocation.END))){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        pair.forEach{option->FilterChip(selected=where==option,onClick={where=option},modifier=Modifier.weight(1f).testTag("page-edit-${option.name.lowercase()}"),
                            label={Text(when(option){PageInsertLocation.BEFORE->"目标页之前";PageInsertLocation.AFTER->"目标页之后";PageInsertLocation.START->"笔记本开头";PageInsertLocation.END->"笔记本末尾"},fontSize=12.sp)})}
                    }
                }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
                    items(candidates,key={it.id}){p->FilterChip(selected=anchor==p.id,onClick={anchor=p.id},label={Text("第 ${p.position+1} 页")},modifier=Modifier.testTag("page-edit-target-${p.position+1}"))}
                }
                Text(if(index>=0)"${verb}后位于第 ${index+1} 页；"+(if(kind==PageEditKind.MOVE)"保持正在阅读的页面。"else"完成后打开此页。")else"请选择有效目标页。",color=Forest,fontSize=13.sp,modifier=Modifier.testTag("page-edit-preview"))
                Text(if(kind==PageEditKind.COPY)"复制已保存可见笔迹及有效人工索引，保留擦除效果；副本使用新身份，不复制隐藏历史。"else"仅改变页序与可用状态，不改写笔迹、页面身份或搜索定位。",fontSize=12.sp,color=Quiet)
            }
            Text(when{error!=null->"页面版本核对失败";head==null->"正在核对页面版本…";stale->"页面状态已变化";else->"页面版本已核对"},
                modifier=Modifier.testTag("page-edit-version"),fontSize=12.sp,color=Quiet)
            if(stale)Text("页序或页面回收状态已变化，请取消并重新核对。",color=Forest)
            error?.let{
                Text(it,fontSize=12.sp)
                TextButton(onClick={readAttempt++},modifier=Modifier.testTag("retry-page-version")){Text("重新核对版本")}
            }
        }
    },confirmButton={Button(onClick={apply(if(kind==PageEditKind.TRASH)PageInsertLocation.END else where,
        if(kind!=PageEditKind.TRASH&&where in listOf(PageInsertLocation.BEFORE,PageInsertLocation.AFTER))anchor else null,order,checkNotNull(head))},
        enabled=head!=null&&!stale&&error==null&&(kind==PageEditKind.TRASH&&pages.size>1||kind!=PageEditKind.TRASH&&index>=0),modifier=Modifier.testTag("confirm-page-edit")){Text("确认$verb")}},
        dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
