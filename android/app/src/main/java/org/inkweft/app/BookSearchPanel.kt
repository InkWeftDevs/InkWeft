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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.core.*

internal data class PageSearchDraft(val pageId:String,val inkRevision:Long,val objectRevision:Long,val text:String,val stale:Boolean)

@Composable internal fun BookSearchPanel(bookId:String,title:String,currentPageId:String?,dismiss:()->Unit,
    openPage:(String)->Unit,correctPage:(PageSearchDraft)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val scope=rememberCoroutineScope()
    val searchFlow=remember(app){app.pages.observeSearch()}
    val allRows by searchFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val pagesFlow=remember(bookId){app.pages.observe(bookId)}
    val pages by pagesFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val rows=allRows.filter{it.notebookId==bookId}
    var query by remember{mutableStateOf("")}
    var automaticStarted by remember{mutableStateOf(false)}
    var job by remember{mutableStateOf<Job?>(null)}
    var running by remember{mutableStateOf(false)}
    var openingCorrection by remember{mutableStateOf(false)}
    var processed by remember{mutableIntStateOf(0)}
    var total by remember{mutableIntStateOf(0)}
    var message by remember{mutableStateOf<String?>(null)}
    val missing=(pages.size-rows.size).coerceAtLeast(0)
    fun recognize(){
        if(running)return
        running=true;processed=0;message=null
        job=scope.launch{
            var failed=0
            try{
                val currentPages=withContext(Dispatchers.IO){app.pages.activePages(bookId)};total=currentPages.size
                for((i,page)in currentPages.withIndex()){
                    ensureActive()
                    try{
                        val ink=withContext(Dispatchers.IO){app.inkRepository.read(page.id)}
                        val old=withContext(Dispatchers.IO){app.pages.searchText(page.id)}
                        if(old?.inkRevision==ink.revision){processed=i+1;continue}
                        val objects=withContext(Dispatchers.IO){app.pageObjects.read(page.id)}
                        val suppressed=objects.objects.flatMap{it.sourceStrokeIds}.toSet()
                        val result=app.handwriting.recognize(InkSession(ink).visibleDraft().filterNot{it.id in suppressed})
                        val text=(listOf(result.text)+objects.objects.filter{it.kind==PageObjectKind.TEXT}.map{it.text}).filter{it.isNotBlank()}.joinToString("\n")
                        require(text.length<=20_000)
                        if(!withContext(Dispatchers.IO){app.pages.saveSearchText(page.id,ink.revision,text,objects.revision,"OCR")})failed++
                    }catch(c:CancellationException){throw c}catch(_:Exception){failed++}
                    processed=i+1
                }
                message=if(failed==0)"手写识别完成，结果已更新"else"$failed 页暂未识别成功，可重试或校对文字"
            }catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法读取笔记，请重试"}finally{running=false}
        }
    }
    LaunchedEffect(query,pages.size){
        if(query.isNotBlank()&&pages.isNotEmpty()&&!automaticStarted){delay(350);automaticStarted=true;recognize()}
    }
    val term=query.trim()
    val hits=if(term.isEmpty())emptyList()else rows.filter{it.text.contains(term,ignoreCase=true)}.sortedBy{it.position}
    EditorPanel("查找手写",title,dismiss,"book-search-panel",footer={
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            if(running){LinearProgressIndicator(Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){
                Text("正在识别手写 $processed / $total 页",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={job?.cancel();message="已暂停，已识别的页面仍可查找"},modifier=Modifier.testTag("search-stop")){Text("暂停")}
            }}else{
                message?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("search-progress"))}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(if(missing>0)"$missing 页待识别"else"已识别 ${rows.size} 页",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(onClick={automaticStarted=true;recognize()},enabled=pages.isNotEmpty(),modifier=Modifier.testTag("recognize-book")){Text(if(message?.contains("未识别成功")==true)"重试"else"识别手写")}
                }
                if(currentPageId!=null)TextButton(onClick={openingCorrection=true;scope.launch{try{
                    val draft=withContext(Dispatchers.IO){val revision=app.pages.inkRevision(currentPageId);val objects=app.pageObjects.read(currentPageId);val old=app.pages.searchText(currentPageId)
                        PageSearchDraft(currentPageId,revision,objects.revision,old?.text.orEmpty(),old!=null&&old.inkRevision!=revision)}
                    correctPage(draft)
                }catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法打开校对，请重试"}finally{openingCorrection=false}}},enabled=!openingCorrection,modifier=Modifier.testTag("search-correct-page")){Text(if(openingCorrection)"正在打开…"else"校对当前页")}
            }
        }
    }){
        OutlinedTextField(query,{query=it},singleLine=true,label={Text("输入关键词")},leadingIcon={Glyph("search")},
            trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""},modifier=Modifier.describedAs("清空搜索")){Glyph("close")}},
            modifier=Modifier.fillMaxWidth().testTag("book-search-query"))
        Text("手写 · 文本框（暂不含 PDF 原文）",style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(vertical=6.dp))
        if(term.isEmpty()){
            Text("输入关键词，自动识别并查找手写。",style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(vertical=8.dp))
        }else if(hits.isEmpty()){
            Text(if(running)"正在查找你的手写…"else"没有找到“$term”",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=24.dp))
            Text(if(running)"可以继续输入，结果会陆续出现。"else"换一个词试试，或在下方校对识别结果。",style=MaterialTheme.typography.bodyMedium,color=Quiet,modifier=Modifier.padding(top=8.dp))
        }else{
            Text("${hits.size} 页包含“$term”",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("book-search-results")){
                items(hits,key={it.pageId}){hit->
                    Surface(onClick={openPage(hit.pageId)},modifier=Modifier.fillMaxWidth().testTag("book-search-hit-${hit.pageId}")){
                        Column(Modifier.padding(vertical=10.dp)){
                            Text("第 ${hit.position+1} 页",style=MaterialTheme.typography.labelLarge,color=Forest)
                            val start=hit.text.indexOf(term,ignoreCase=true);val from=(start-35).coerceAtLeast(0)
                            val snippet=hit.text.substring(from,(start+term.length+100).coerceAtMost(hit.text.length)).replace('\n',' ')
                            val offset=start-from
                            val annotated=AnnotatedString.Builder(snippet).apply{addStyle(SpanStyle(background=Leaf,fontWeight=FontWeight.Bold),offset,(offset+term.length).coerceAtMost(snippet.length))}.toAnnotatedString()
                            Text(annotated,maxLines=3,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=8.dp))
                        }
                    }
                    HorizontalDivider(color=Line)
                }
            }
        }
    }
}
