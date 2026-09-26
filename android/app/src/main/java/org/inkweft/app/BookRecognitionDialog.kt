// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.inkweft.core.*

@Composable internal fun BookRecognitionDialog(bookId:String,dismiss:()->Unit) {
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var running by remember{mutableStateOf(false)};var done by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("逐页离线识别后建立搜索索引；保留仍有效的人工关键词。完成后在资料库输入文字可跳转原页。")}
    LaunchedEffect(running){if(running){var saved=0;var skipped=0;var failed=0
        try{val pages=withContext(Dispatchers.IO){app.pages.activePages(bookId)}
            for((i,page)in pages.withIndex()){
                ensureActive();status="正在识别第 ${i+1}/${pages.size} 页 · 已保存 $saved 页"
                try{
                    val ink=withContext(Dispatchers.IO){app.inkRepository.read(page.id)}
                    val old=withContext(Dispatchers.IO){app.pages.searchText(page.id)}
                    if(old?.method=="MANUAL"&&old.inkRevision==ink.revision){skipped++;continue}
                    val objects=withContext(Dispatchers.IO){app.pageObjects.read(page.id)}
                    val suppressed=objects.objects.flatMap{it.sourceStrokeIds}.toSet()
                    val result=app.handwriting.recognize(InkSession(ink).visibleDraft().filterNot{it.id in suppressed})
                    val text=(listOf(result.text)+objects.objects.filter{it.kind==PageObjectKind.TEXT}.map{it.text}).filter{it.isNotBlank()}.joinToString("\n").also{require(it.length<=20000)}
                    if(withContext(Dispatchers.IO){app.pages.saveSearchText(page.id,ink.revision,text,objects.revision,"OCR")})saved++ else failed++
                }catch(c:CancellationException){throw c}catch(_:Exception){failed++}
            }
            status="完成：$saved 页已建立索引，$skipped 页保留人工关键词，$failed 页未完成。识别可能有错字，可从单页“手写检索文字”中核对修改。"
        }catch(c:CancellationException){throw c}catch(_:Exception){status="读取中断，已完成页面的索引保留，可重新尝试。"}finally{running=false;done=true}
    }}
    AlertDialog(onDismissRequest=dismiss,title={Text("整本手写查找")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(status);if(running)LinearProgressIndicator(Modifier.fillMaxWidth())}},
        confirmButton={if(!running)TextButton(onClick={if(done)dismiss()else running=true}){Text(if(done)"完成"else"开始识别")}},
        dismissButton={TextButton(onClick=dismiss){Text(if(running)"停止，保留已完成页"else"取消")}})
}
