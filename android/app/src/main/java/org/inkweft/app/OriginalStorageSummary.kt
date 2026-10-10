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
    suspend fun refresh(){status=withContext(Dispatchers.IO){app.documents.storageStatus()}}
    LaunchedEffect(app){try{refresh()}catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法读取占用信息"}finally{busy=false}}
    fun size(bytes:Long)=Formatter.formatFileSize(context,bytes)
    Column(Modifier.fillMaxWidth().testTag("original-storage-summary"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        status?.let{s->
            Text("PDF 原件 ${size(s.pdfBytes)} · 图片原件 ${size(s.imageBytes)}")
            Text("原件缓存 ${size(s.cacheBytes)} · 设备可用空间 ${size(s.freeBytes)}",color=Quiet,style=MaterialTheme.typography.bodySmall)
            Text("笔迹、历史和临时文件另占空间。缓存可自动重建。",color=Quiet,style=MaterialTheme.typography.bodySmall)
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick={scope.launch{
            busy=true;message=null
            try{app.documentRendering.clearOriginalCache();refresh();message="已清理可重建缓存，正在使用的文件暂时保留。"}
            catch(c:CancellationException){throw c}catch(_:Exception){message="本次缓存清理未完成，可稍后重试"}finally{busy=false}
        }},enabled=!busy,modifier=Modifier.testTag("clear-original-cache")){Text("清理原件缓存")}
        message?.let{Text(it,color=Quiet,style=MaterialTheme.typography.bodySmall)}
    }
}
