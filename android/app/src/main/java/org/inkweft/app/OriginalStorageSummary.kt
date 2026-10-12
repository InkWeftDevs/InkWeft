// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.inkweft.data.OriginalStorageStatus

@Composable internal fun OriginalStorageSummary() {
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var status by remember{mutableStateOf<OriginalStorageStatus?>(null)};var busy by remember{mutableStateOf(true)};var message by remember{mutableStateOf<String?>(null)}
    var history by remember{mutableStateOf<org.inkweft.data.HistoryStorageReport?>(null)}
    suspend fun refresh(){status=withContext(Dispatchers.IO){app.documents.storageStatus()}}
    LaunchedEffect(app){try{refresh()}catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法读取占用信息"}finally{busy=false}}
    fun size(bytes:Long)=Formatter.formatFileSize(context,bytes)
    Column(Modifier.fillMaxWidth().testTag("original-storage-summary"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        status?.let{s->
            Text("PDF 原件 ${size(s.pdfBytes)} · 图片原件 ${size(s.imageBytes)}")
            Text("去重原件文件 ${size(s.originalFileBytes)} · 数据库 ${size(s.databaseBytes)}",color=Quiet,style=MaterialTheme.typography.bodySmall)
            Text("原件缓存 ${size(s.cacheBytes)} · 设备可用空间 ${size(s.freeBytes)}",color=Quiet,style=MaterialTheme.typography.bodySmall)
            Text("原件归属量可重复计数；文件量按实际占用计算。整理会保留笔记、回收站和历史所需原件。",color=Quiet,style=MaterialTheme.typography.bodySmall)
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick={scope.launch{
            busy=true;message=null
            try{history=app.historyStorage.audit()}
            catch(c:CancellationException){throw c}catch(_:Exception){message="历史引用检查未完成，资料保留；请通过诊断与导出记录问题。"}finally{busy=false}
        }},enabled=!busy,modifier=Modifier.testTag("audit-history-storage")){Text("检查历史占用")}
        history?.let{h->
            Text("历史与引用载荷 ${size(h.payloadBytes)} · 可安全整理 ${size(h.reclaimableBytes)}",style=MaterialTheme.typography.bodySmall)
            h.groups.filter{it.rows>0}.forEach{g->Text("${g.label} · ${g.rows} 条 · ${size(g.payloadBytes)}",color=Quiet,style=MaterialTheme.typography.bodySmall)}
            Text("保留撤销、固定关联、来源、复习和待核对操作所需历史及回执。载荷量不含索引和 SQLite 空闲页。"+(if(h.shadowProtected)"实验同步资料正在保护来源副本。"else""),color=Quiet,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={scope.launch{
                busy=true;message=null
                try{val removed=app.historyStorage.collectDetached();history=app.historyStorage.audit();message="已安全整理 ${removed.detachedSources} 份无引用来源副本、${removed.detachedPreviews} 份孤立预览，回收载荷 ${size(removed.reclaimableBytes)}。"+if(removed.collectionDeferred)"资料在检查期间有更新，剩余内容保留，可稍后再次整理。"else"磁盘空间可继续通过原件与磁盘整理回收。"}
                catch(c:CancellationException){throw c}catch(_:Exception){message="引用闭包尚未确认，本次历史整理停止；请通过诊断与导出记录问题。"}finally{busy=false}
            }},enabled=!busy&&h.reclaimableBytes>0,modifier=Modifier.testTag("collect-detached-history")){Text("安全整理历史")}
        }
        TextButton(onClick={scope.launch{
            busy=true;message=null
            try{app.documentRendering.clearOriginalCache();refresh();message="已清理可重建缓存，正在使用的文件暂时保留。"}
            catch(c:CancellationException){throw c}catch(_:Exception){message="本次缓存清理未完成，可稍后重试"}finally{busy=false}
        }},enabled=!busy,modifier=Modifier.testTag("clear-original-cache")){Text("清理原件缓存")}
        TextButton(onClick={scope.launch{
            busy=true;message=null
            try{
                val result=app.originalMaintenance.reclaim();refresh()
                message="已整理 ${result.migratedOriginals} 份原件，释放 ${size(result.reclaimedBytes)}。"+
                    when(result.compactionDeferred){
                        "SQLITE_COMPACTION_LOW_SPACE"->"可用空间不足，数据库压缩留待下次整理。"
                        null->""
                        else->"数据库正在使用，剩余空间回收留待下次整理。"
                    }
            }catch(c:CancellationException){throw c}catch(_:Exception){message="整理未全部完成，已保存资料保留；请通过诊断与导出记录问题。"}finally{busy=false}
        }},enabled=!busy,modifier=Modifier.testTag("reclaim-original-storage")){Text("整理原件与磁盘空间")}
        message?.let{Text(it,color=Quiet,style=MaterialTheme.typography.bodySmall)}
    }
}
