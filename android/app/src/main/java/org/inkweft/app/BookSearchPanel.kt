// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

internal data class PageSearchDraft(val pageId:String,val inkRevision:Long,val objectRevision:Long,val text:String,val stale:Boolean,val authoringRevision:Long)

@Composable internal fun BookSearchPanel(bookId:String,title:String,currentPageId:String?,dismiss:()->Unit,
    openPage:(String,CanvasBounds?)->Unit,correctPage:(PageSearchDraft)->Unit,onMapSearch:(()->Unit)?=null){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val scope=rememberCoroutineScope()
    val requestVersion=remember(bookId){SearchRequestVersion()}
    DisposableEffect(requestVersion){onDispose{requestVersion.close()}}
    val resultListState=rememberLazyListState()
    val searchFlow=remember(app){app.pages.observeSearch()}
    val allRows by searchFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val pagesFlow=remember(bookId){app.pages.observe(bookId)}
    val pages by pagesFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val rows=allRows.filter{it.notebookId==bookId}
    var query by rememberSaveable(bookId){mutableStateOf("")}
    var returnPage by rememberSaveable(bookId){mutableStateOf<String?>(null)}
    var restorePage by remember{mutableStateOf(returnPage)}
    var automaticStarted by remember{mutableStateOf(false)}
    var job by remember{mutableStateOf<Job?>(null)}
    var running by remember{mutableStateOf(false)}
    var openingCorrection by remember{mutableStateOf(false)}
    var processed by remember{mutableIntStateOf(0)}
    var total by remember{mutableIntStateOf(0)}
    var message by remember{mutableStateOf<String?>(null)}
    var pdfHits by remember(bookId){mutableStateOf<Map<String,PdfTextHit>>(emptyMap())}
    var pdfTerm by remember(bookId){mutableStateOf("")}
    var pdfJob by remember{mutableStateOf<Job?>(null)}
    var pdfRunning by remember{mutableStateOf(false)}
    var pdfProcessed by remember{mutableIntStateOf(0)}
    var pdfTotal by remember{mutableIntStateOf(0)}
    var pdfImageOnly by remember{mutableIntStateOf(0)}
    var pdfFailed by remember{mutableIntStateOf(0)}
    var pdfPaused by remember{mutableStateOf(false)}
    var pdfAttempt by remember{mutableIntStateOf(0)}
    var openingPage by remember{mutableStateOf<String?>(null)}
    val missing=(pages.size-rows.size).coerceAtLeast(0)
    fun recognize(reportCompletion:Boolean=true){
        if(running)return
        running=true;processed=0;message=null
        job=scope.launch{
            var failed=0
            try{
                val currentPages=withContext(Dispatchers.IO){app.pages.activePages(bookId)};total=currentPages.size
                for((i,page)in currentPages.withIndex()){
                    ensureActive()
                    try{
                        // Object/layer edits invalidate the index. Check its cheap revision first,
                        // so reopening search never decodes complete pages that are already indexed.
                        val indexed=withContext(Dispatchers.IO){
                            app.pages.hasCurrentSearchText(page.id)
                        }
                        if(indexed){processed=i+1;continue}
                        val frozen=withContext(Dispatchers.IO){app.authoring.exportPage(page.id)}
                        val layers=frozen.authoring.state.layers
                        val visibleObjects=frozen.objects.filter{layers.visible(LayerContent(LayerContentKind.OBJECT,it.id))}
                        val suppressed=visibleObjects.flatMap{it.sourceStrokeIds}.toSet()
                        val result=app.handwriting.recognize(frozen.ink.filter{layers.visible(LayerContent(LayerContentKind.INK,it.id))&&it.id !in suppressed})
                        val text=(listOf(result.text)+visibleObjects.filter{!it.hidden&&it.kind in setOf(PageObjectKind.TEXT,PageObjectKind.FORMULA)}.map{it.visibleText()}).filter{it.isNotBlank()}.joinToString("\n")
                        require(text.length<=20_000)
                        if(!withContext(Dispatchers.IO){app.pages.saveSearchText(page.id,frozen.authoring.inkRevision,text,frozen.authoring.objectRevision,"OCR",frozen.authoring.revision)})failed++
                    }catch(c:CancellationException){throw c}catch(_:Exception){failed++}
                    processed=i+1
                }
                message=if(failed==0){if(reportCompletion)"手写识别完成，结果已更新"else null}else"$failed 页暂未识别成功，可重试或校对文字"
            }catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法读取笔记，请重试"}finally{running=false}
        }
    }
    LaunchedEffect(query,pages.size){
        if(query.isNotBlank()&&pages.isNotEmpty()&&!automaticStarted){delay(350);automaticStarted=true;recognize(false)}
    }
    val term=query.trim()
    LaunchedEffect(bookId,term,pages.map{it.id},pdfAttempt){
        val worker=currentCoroutineContext()[Job]!!
        val request=requestVersion.invalidate()
        pdfJob=worker;pdfTerm=term;pdfHits=emptyMap();pdfProcessed=0;pdfTotal=pages.size
        pdfImageOnly=0;pdfFailed=0;pdfPaused=false;pdfRunning=term.isNotEmpty()&&pages.isNotEmpty()
        try{
            if(!pdfRunning)return@LaunchedEffect
            delay(250)
            val sources=mutableMapOf<String,PdfDocumentSource>()
            for((i,page)in pages.withIndex()){
                ensureActive()
                try{
                    val result=app.documentRendering.search(page.id,term,sources)
                    ensureActive()
                    if(!requestVersion.isCurrent(request))return@LaunchedEffect
                    if(result!=null){
                        if(!result.hasText)pdfImageOnly++
                        result.hit?.let{pdfHits=pdfHits+(page.id to it)}
                    }
                }catch(c:CancellationException){throw c}catch(_:Exception){if(requestVersion.isCurrent(request))pdfFailed++}
                if(!requestVersion.isCurrent(request))return@LaunchedEffect
                pdfProcessed=i+1
            }
        }finally{if(pdfJob===worker){pdfRunning=false;pdfJob=null}}
    }
    val indexedHits=if(term.isEmpty())emptyMap()else rows.filter{it.text.contains(term,ignoreCase=true)}.associateBy{it.pageId}
    val nativeHits=if(pdfTerm==term)pdfHits else emptyMap()
    val hits=if(term.isEmpty())emptyList()else pages.filter{it.id in indexedHits||it.id in nativeHits}.sortedBy{it.position}
    LaunchedEffect(hits.map{it.id},restorePage){
        restorePage?.let{pageId->
            val index=hits.indexOfFirst{it.id==pageId}
            if(index>=0){resultListState.scrollToItem(index);restorePage=null}
        }
    }
    fun openResult(pageId:String,hit:PdfTextHit?){
        if(openingPage!=null)return
        openingPage=pageId;message=null
        val requested=term
        val request=requestVersion.current()
        scope.launch{
            try{
                val valid=withContext(Dispatchers.IO){
                    app.pages.activePages(bookId).any{it.id==pageId}&&(hit==null||app.documents.read(pageId)?.let{
                        it.document.sha256==hit.documentSha256&&it.page==hit.sourcePage
                    }==true)
                }
                if(!requestVersion.isCurrent(request)||query.trim()!=requested)return@launch
                if(valid){returnPage=pageId;openPage(pageId,hit?.bounds)}else message="页面或原文已变化，请重新查找"
            }catch(c:CancellationException){throw c}catch(_:Exception){if(requestVersion.isCurrent(request))message="暂时无法打开结果，请重试"}
            finally{openingPage=null}
        }
    }
    @Composable fun Snippet(text:String,label:String,tag:String){
        val start=text.indexOf(term,ignoreCase=true)
        val from=(start-35).coerceAtLeast(0)
        val snippet=text.substring(from,if(start>=0)(start+term.length+100).coerceAtMost(text.length)else text.length.coerceAtMost(160)).replace('\n',' ')
        val annotated=AnnotatedString.Builder("$label · $snippet").apply{
            if(start>=0){val offset=label.length+3+start-from;addStyle(SpanStyle(background=Leaf,fontWeight=FontWeight.Bold),offset,(offset+term.length).coerceAtMost(length))}
        }.toAnnotatedString()
        Text(annotated,maxLines=3,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=6.dp).testTag(tag))
    }
    EditorPanel("查找页内文字",title,{returnPage=hits.getOrNull(resultListState.firstVisibleItemIndex)?.id;dismiss()},"book-search-panel",footer={
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            if(pdfRunning){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("正在查找 PDF $pdfProcessed / $pdfTotal 页",Modifier.weight(1f).testTag("pdf-search-progress"),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={pdfPaused=true;pdfJob?.cancel()},modifier=Modifier.testTag("pdf-search-stop")){Text("暂停")}
                }
            }else if(pdfPaused||pdfFailed>0){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(if(pdfPaused)"PDF 查找已暂停"else"$pdfFailed 页 PDF 暂未查找成功",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(onClick={requestVersion.invalidate();pdfAttempt++},modifier=Modifier.testTag("pdf-search-retry")){Text("重试")}
                }
            }
            if(pdfImageOnly>0)Text("$pdfImageOnly 页 PDF 没有原生文字，可用区域摘录",style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("pdf-search-image-only"))
            if(running){LinearProgressIndicator(Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){
                Text("正在识别手写 $processed / $total 页",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={job?.cancel();message="已暂停，已识别的页面仍可查找"},modifier=Modifier.testTag("search-stop")){Text("暂停")}
            }}else{
                message?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("search-progress"))}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(if(missing>0)"$missing 页待识别"else"已识别 ${rows.size} 页",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(onClick={automaticStarted=true;recognize()},enabled=pages.isNotEmpty(),modifier=Modifier.testTag("recognize-book")){Text(if(message?.contains("未识别成功")==true)"重试"else"识别手写")}
                    if(currentPageId!=null)TextButton(onClick={openingCorrection=true;scope.launch{try{
                    val draft=withContext(Dispatchers.IO){val frozen=app.authoring.readPage(currentPageId);val old=app.pages.searchText(currentPageId)
                        check(app.authoring.readPage(currentPageId).let{it.revision==frozen.revision&&it.inkRevision==frozen.inkRevision&&it.objectRevision==frozen.objectRevision})
                        PageSearchDraft(currentPageId,frozen.inkRevision,frozen.objectRevision,old?.text.orEmpty(),old!=null&&old.inkRevision!=frozen.inkRevision,frozen.revision)}
                    correctPage(draft)
                }catch(c:CancellationException){throw c}catch(_:Exception){message="暂时无法打开校对，请重试"}finally{openingCorrection=false}}},enabled=!openingCorrection&&openingPage==null,modifier=Modifier.testTag("search-correct-page")){Text(if(openingCorrection)"正在打开…"else"校对当前页")}
                }
            }
        }
    }){
        OutlinedTextField(query,{val value=it.take(256);if(value.trim()!=query.trim()){requestVersion.invalidate();pdfHits=emptyMap();returnPage=null;restorePage=null};query=value},singleLine=true,label={Text("输入关键词")},leadingIcon={Glyph("search")},
            trailingIcon={if(query.isNotEmpty())IconButton(onClick={requestVersion.invalidate();pdfHits=emptyMap();returnPage=null;restorePage=null;query=""},modifier=Modifier.describedAs("清空搜索")){Glyph("close")}},
            modifier=Modifier.fillMaxWidth().testTag("book-search-query"))
        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text("PDF 原文 · 手写 · 文本框",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Quiet)
            onMapSearch?.let{IconButton(it,modifier=Modifier.testTag("book-map-search").describedAs("查找导图内容")){Glyph("mindmap")}}
        }
        if(term.isEmpty()){
            Text("输入关键词，查找 PDF 原文和识别文字。",style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(vertical=8.dp))
        }else if(hits.isEmpty()){
            Text(if(running||pdfRunning)"正在查找文字…"else"没有找到“$term”",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=24.dp))
            Text(if(running||pdfRunning)"可以继续输入，结果会陆续出现。"else"换一个词试试，或在下方校对识别结果。",style=MaterialTheme.typography.bodyMedium,color=Quiet,modifier=Modifier.padding(top=8.dp))
        }else{
            Text("${hits.size} 页包含“$term”",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("book-search-results"),state=resultListState){
                items(hits,key={it.id}){page->
                    Surface(onClick={openResult(page.id,nativeHits[page.id])},enabled=openingPage==null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("book-search-hit-${page.id}")){
                        Column(Modifier.padding(vertical=10.dp)){
                            Text("第 ${page.position+1} 页",style=MaterialTheme.typography.labelLarge,color=Forest)
                            nativeHits[page.id]?.let{Snippet(it.text,"PDF 原文（源页 ${it.sourcePage+1}）","book-search-pdf-${page.id}")}
                            indexedHits[page.id]?.let{Snippet(it.text,"手写 / 文本框","book-search-ink-${page.id}")}
                        }
                    }
                    HorizontalDivider(color=Line)
                }
            }
        }
    }
}
