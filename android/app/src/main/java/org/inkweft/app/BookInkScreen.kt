// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

@Composable
fun InkScreen(note:NoteDraft,workspace:WorkspaceViewModel=viewModel(),onBack:()->Unit={},onRename:()->Unit={},onText:()->Unit={},onDiagnostics:()->Unit={}){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val vm:BookPagesViewModel=viewModel(key="book-${note.base.id}",factory=BookPagesViewModel.Factory(note.base.id,app.pages,app.workspaceRepository))
    val ui by vm.ui.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    val requestedPages by workspace.pendingPageNavigation.collectAsStateWithLifecycle()
    val requested=requestedPages[note.base.id]
    LaunchedEffect(requested,ui.loading,ui.pages.size){
        if(requested!=null&&!ui.loading&&ui.pages.any{it.id==requested}){
            vm.select(requested);workspace.consumePageNavigation(note.base.id,requested)
        }
    }
    val readingPrefs=remember(context){context.getSharedPreferences("inkweft-reading",android.content.Context.MODE_PRIVATE)}
    var continuous by rememberSaveable(note.base.id){mutableStateOf(readingPrefs.getBoolean("continuous-v20-${note.base.id}",true))}
    fun readingMode(value:Boolean){continuous=value;readingPrefs.edit().putBoolean("continuous-v20-${note.base.id}",value).apply()}
    var canNavigate by remember{mutableStateOf(false)};var directory by remember{mutableStateOf(false)}
    SideEffect{app.navigationReady.value=canNavigate&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown}
    androidx.activity.compose.BackHandler(enabled=!canNavigate||ui.busy||ui.actionUnknown||ui.insertionUnknown){
        Toast.makeText(context,"请先抬笔或核对当前操作，笔记仍保持在当前页。",Toast.LENGTH_SHORT).show()
    }
    var searchTarget by remember{mutableStateOf<PageSearchDraft?>(null)}
    var exportBytes by remember{mutableStateOf<ByteArray?>(null)};var exporting by remember{mutableStateOf(false)}
    var confirmBook by remember{mutableStateOf(false)}
    var insertion by remember{mutableStateOf<Pair<String,PageInsertLocation>?>(null)}
    var pageActionId by rememberSaveable{mutableStateOf<String?>(null)}
    var pageActionKind by rememberSaveable{mutableStateOf(PageEditKind.MOVE.name)}
    var showRecycled by rememberSaveable{mutableStateOf(false)}
    var knowledgeOpen by remember{mutableStateOf(false)}
    var knowledgeAnchor by remember{mutableStateOf<KnowledgeData.Anchor?>(null)}
    var searchOpen by remember{mutableStateOf(false)}
    var documentMore by remember{mutableStateOf(false)}
    var documentSettings by remember{mutableStateOf(false)}
    var classify by remember{mutableStateOf(false)}
    val entries by workspace.entries.collectAsStateWithLifecycle()
    var initialStudyCard by remember{mutableStateOf<String?>(null)}
    var studyOpen by remember{mutableStateOf(false)}
    var studySource by remember{mutableStateOf<StudySourceDraft?>(null)}
    var sourceFocus by remember{mutableStateOf<Pair<String,CanvasBounds>?>(null)}

    val requestedCard by workspace.studyCardRequest.collectAsStateWithLifecycle()
    LaunchedEffect(requestedCard){requestedCard?.let{initialStudyCard=it;studyOpen=true;workspace.studyCardRequest.value=null}}
    val externalAnchor by workspace.focusAnchor.collectAsStateWithLifecycle()
    LaunchedEffect(externalAnchor,ui.loading,ui.selectedId){externalAnchor?.let{anchor->if(ui.selectedId==anchor.pageId&&!ui.loading){readingMode(false);sourceFocus=anchor.pageId to anchor.bounds;workspace.focusAnchor.value=null}}}

    fun requestAction(p:NotebookPageRow,kind:PageEditKind){directory=false;pageActionKind=kind.name;pageActionId=p.id}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val bytes=exportBytes;exportBytes=null
        if(uri!=null&&bytes!=null)scope.launch{val ok=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false};Toast.makeText(context,if(ok)"整本内容副本已导出"else"导出失败，原笔记保留",Toast.LENGTH_LONG).show()}
    }
    val page=ui.pages.firstOrNull{it.id==ui.selectedId}
    Column(Modifier.fillMaxSize()){
        val navigationEnabled=canNavigate&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack,enabled=navigationEnabled,modifier=Modifier.testTag("back-library").describedAs("返回资料库")){Glyph("back")}
            TextButton(onClick=onRename,enabled=navigationEnabled,modifier=Modifier.weight(1f).testTag("rename-from-editor")){Text(note.title,modifier=Modifier.fillMaxWidth(),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontSize=16.sp)}
            if(page!=null&&!page.world){
                IconButton(onClick={directory=true},enabled=navigationEnabled,modifier=Modifier.testTag("page-directory").describedAs("页面")){Glyph("list")}
                IconButton(onClick={insertion=page.id to PageInsertLocation.AFTER},enabled=navigationEnabled&&ui.pages.size+ui.recycled.size<500,modifier=Modifier.testTag("add-page").describedAs("加页")){Glyph("add")}
            }
            IconButton(onClick={searchOpen=true},enabled=navigationEnabled,modifier=Modifier.testTag("book-search").describedAs("查找")){Glyph("search")}
            Box{IconButton(onClick={documentMore=true},enabled=navigationEnabled,modifier=Modifier.testTag("document-more").describedAs("文档选项")){Glyph("more")}
                DropdownMenu(documentMore,{documentMore=false}){
                    if(page!=null&&!page.world)DropdownMenuItem(text={Text(if(continuous)"切换单页"else"连续书写")},onClick={documentMore=false;readingMode(!continuous)},modifier=Modifier.testTag("toggle-continuous"))
                    DropdownMenuItem(text={Text("整理与复习")},onClick={documentMore=false;studySource=null;studyOpen=true},modifier=Modifier.testTag("study-open"))
                    DropdownMenuItem(text={Text("关联与思维导图")},onClick={documentMore=false;knowledgeAnchor=null;knowledgeOpen=true},modifier=Modifier.testTag("knowledge-open"))
                    DropdownMenuItem(text={Text("阅读与笔记设置")},onClick={documentMore=false;documentSettings=true},modifier=Modifier.testTag("document-settings"))
                    DropdownMenuItem(text={Text("编辑键入文字")},onClick={documentMore=false;onText()},modifier=Modifier.testTag("mode-text"))
                    if(page!=null&&!page.world)DropdownMenuItem(text={Text("导出整本内容副本")},onClick={documentMore=false;confirmBook=true},enabled=!exporting,modifier=Modifier.testTag("export-book"))
                    DropdownMenuItem(text={Text("诊断与导出")},onClick={documentMore=false;onDiagnostics()},modifier=Modifier.testTag("open-diagnostics"))
                }
            }
        }
        HorizontalDivider(color=Line)
        if(ui.error!=null)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(ui.error!!,Modifier.weight(1f),fontSize=12.sp);if(!ui.insertionUnknown&&!ui.actionUnknown)TextButton(onClick=vm::clearError){Text("知道了")}}
        if(ui.actionUnknown)Surface(color=androidx.compose.ui.graphics.Color(0xfffff4e3)){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("页面整理结果尚待核对，原操作身份已保留。",Modifier.weight(1f),fontSize=12.sp)
                TextButton(onClick=vm::retryPageEdit,enabled=!ui.busy,modifier=Modifier.testTag("retry-page-edit")){Text("核对页面操作")}
            }
        }
        if(ui.insertionUnknown)Surface(color=androidx.compose.ui.graphics.Color(0xfffff4e3)){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("上次插页尚待核对；先核对原操作，勿另建一批。",Modifier.weight(1f),fontSize=12.sp)
                TextButton(onClick=vm::retryInsertion,enabled=!ui.busy,modifier=Modifier.testTag("retry-page-insertion")){Text("核对原插页")}
            }
        }
        if(page==null)Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){if(ui.loading)CircularProgressIndicator()else Text("页面未能载入，原数据保留")}
        else Box(Modifier.weight(1f)){InkPageScreen(note,workspace,page,{canNavigate=it},{_->searchOpen=true},!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown&&pageActionId==null,
            onAssociate={selection->knowledgeAnchor=KnowledgeData.Anchor(page.id,selection.revision,selection.region.bounds,selection.strokes.map{it.id});knowledgeOpen=true},
            onExcerpt={selection->studySource=StudySourceDraft(page.id,selection.revision,selection.region.bounds,selection.strokes.map{it.id});studyOpen=true},
            focusRegion=sourceFocus?.takeIf{it.first==page.id}?.second,onFocusConsumed={sourceFocus=null},pageNavigation={
                if(!page.world){
                    TextButton(onClick={ui.pages.getOrNull(page.position-1)?.let{vm.select(it.id)}},enabled=navigationEnabled&&page.position>0,modifier=Modifier.testTag("previous-page")){Text("‹")}
                    Text("第 ${page.position+1} / ${ui.pages.size} 页",fontSize=12.sp,modifier=Modifier.testTag("page-counter"))
                    TextButton(onClick={ui.pages.getOrNull(page.position+1)?.let{vm.select(it.id)}},enabled=navigationEnabled&&page.position<ui.pages.lastIndex,modifier=Modifier.testTag("next-page")){Text("›")}
                }
            },continuousPages=if(continuous&&!page.world)ui.pages else null,onContinuousPage={if(it!=ui.selectedId)vm.select(it)},leaveContinuous={readingMode(false)})}
    }
    if(searchOpen)BookSearchPanel(note.base.id,note.title,page?.id,{searchOpen=false},{id->
        if(ui.pages.any{it.id==id}){vm.select(id);searchOpen=false}
    },{draft->searchTarget=draft;searchOpen=false})
    if(documentSettings)AlertDialog(onDismissRequest={documentSettings=false},modifier=Modifier.testTag("document-settings-dialog"),title={Text("阅读与笔记设置")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){

            if(page!=null&&!page.world){
                Row(verticalAlignment=Alignment.CenterVertically){Text("上下连续翻页",Modifier.weight(1f));Switch(continuous,{readingMode(it)},modifier=Modifier.testTag("continuous-setting"))}
                TextButton(onClick={documentSettings=false;directory=true},modifier=Modifier.testTag("settings-page-directory")){Text("跳转页面与页面整理")}
            }
            TextButton(onClick={documentSettings=false;classify=true},enabled=entries[note.base.id]!=null,modifier=Modifier.testTag("settings-tags")){Text("文件夹与标签")}
            HorizontalDivider()
            TextButton(onClick={documentSettings=false;onDiagnostics()}){Text("诊断与导出")}
        }
    },confirmButton={TextButton(onClick={documentSettings=false}){Text("完成")}})
    if(classify)entries[note.base.id]?.let{row->NotebookClassificationDialog(row,{classify=false}){folder,tags->workspace.organize(row,folder=folder,tags=tags);classify=false}}
    if(knowledgeOpen)KnowledgeWorkspace(note.base.id,page?.let{TargetRef(TargetKind.PAGE,it.id)}?:TargetRef(TargetKind.NOTE,note.base.id),knowledgeAnchor,dismiss={knowledgeOpen=false}){target->
        app.openKnowledgeTarget.value=target;knowledgeOpen=false
    }
    if(studyOpen)StudyWorkspace(note,studySource,{studyOpen=false;studySource=null;initialStudyCard=null},initialCardId=initialStudyCard){source->
        if(ui.pages.any{it.id==source.pageId}){readingMode(false);vm.select(source.pageId);sourceFocus=source.pageId to CanvasBounds(source.left,source.top,source.right,source.bottom);true}else false
    }
    if(directory)AlertDialog(onDismissRequest={directory=false},modifier=Modifier.testTag("pages-directory-dialog"),title={Text("页面 · ${ui.pages.size} 页")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!showRecycled,onClick={showRecycled=false},label={Text("可用页面")},modifier=Modifier.testTag("pages-active"))
                FilterChip(selected=showRecycled,onClick={showRecycled=true},label={Text("页面回收区 ${ui.recycled.size}")},modifier=Modifier.testTag("pages-recycled"))
            }
            if(showRecycled)Text("回收页不参与阅读、搜索和内容副本导出；资料库备份仍会保留。这里的回收不是永久删除。",fontSize=12.sp,color=Quiet)
            LazyVerticalGrid(columns=GridCells.Adaptive(100.dp),modifier=Modifier.heightIn(max=400.dp).testTag("page-grid"),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(if(showRecycled)ui.recycled else ui.pages,key={it.id}){p->
                    var menu by remember{mutableStateOf(false)}
                    Column(horizontalAlignment=Alignment.CenterHorizontally){
                        Column(Modifier.clickable(enabled=!showRecycled&&canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown){directory=false;vm.select(p.id)}.testTag(if(showRecycled)"recycled-page-${p.id}"else"jump-page-${p.position+1}"),horizontalAlignment=Alignment.CenterHorizontally){
                            PageThumb(p);Text(if(showRecycled)"原第 ${p.position+1} 页"else"第 ${p.position+1} 页",fontSize=12.sp)
                        }
                        if(showRecycled)TextButton(onClick={requestAction(p,PageEditKind.RESTORE)},modifier=Modifier.testTag("restore-page-${p.id}")){Text("恢复到…",fontSize=12.sp)}
                        else Box{
                            TextButton(onClick={menu=true},enabled=canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown,modifier=Modifier.testTag("page-menu-${p.position+1}")){Text("页面操作 ⋯",fontSize=11.sp)}
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                                DropdownMenuItem(text={Text("在此页之前插入")},onClick={menu=false;directory=false;insertion=p.id to PageInsertLocation.BEFORE})
                                DropdownMenuItem(text={Text("在此页之后插入")},onClick={menu=false;directory=false;insertion=p.id to PageInsertLocation.AFTER})
                                HorizontalDivider()
                                DropdownMenuItem(text={Text("移动页面…")},onClick={menu=false;requestAction(p,PageEditKind.MOVE)},modifier=Modifier.testTag("move-page-${p.position+1}"))
                                DropdownMenuItem(text={Text("复制此页…")},onClick={menu=false;requestAction(p,PageEditKind.COPY)},modifier=Modifier.testTag("copy-page-${p.position+1}"))
                                HorizontalDivider()
                                DropdownMenuItem(text={Text("移入页面回收区",color=androidx.compose.ui.graphics.Color(0xffab3939))},enabled=ui.pages.size>1,
                                    onClick={menu=false;requestAction(p,PageEditKind.TRASH)},modifier=Modifier.testTag("recycle-page-${p.position+1}"))
                            }
                        }
                    }
                }
            }
            if(showRecycled&&ui.recycled.isEmpty())Text("页面回收区为空",fontSize=13.sp,color=Quiet)
        }
    },confirmButton={TextButton(onClick={directory=false}){Text("关闭")}})
    pageActionId?.let{id->
        val source=(ui.pages+ui.recycled).firstOrNull{it.id==id}
        if(source==null)LaunchedEffect(id){pageActionId=null}
        else PageEditDialog(source,PageEditKind.valueOf(pageActionKind),ui.pages,{pageActionId=null}){where,anchor,order,head->
            pageActionId=null
            if(canNavigate&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown)vm.editPage(PageEditKind.valueOf(pageActionKind),id,where,anchor,order,head,source.trashedAt)
        }
    }
    insertion?.let{(anchor,location)->
        InsertPagesDialog(ui.pages,anchor,location,{insertion=null},ui.recycled.size){where,id,paper,count,open,order->
            insertion=null
            if(canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown)vm.insert(where,id,paper,count,open,order)
        }
    }
    if(confirmBook)AlertDialog(onDismissRequest={confirmBook=false},title={Text("导出整本内容副本")},text={Text("包括本笔记所有可用页面、图片、文本框、胶带状态、局部擦除效果和已保存键入文字；不含页面回收区。明文 .iwbook，不含摘要卡/脑图、撤销历史、账号或密钥；不是完整资料库备份。目标可能由云盘提供。")},confirmButton={TextButton(onClick={confirmBook=false;exporting=true;scope.launch{try{val bytes=withContext(Dispatchers.IO){app.pages.exportBook(note.base.id).encode()};exportBytes=bytes;export.launch("墨织笔记本.iwbook")}catch(c:CancellationException){throw c}catch(_:Exception){Toast.makeText(context,"无法导出整本内容，原数据保留；可尝试逐页导出",Toast.LENGTH_LONG).show()}finally{exporting=false}}}){Text("选择位置")}},dismissButton={TextButton(onClick={confirmBook=false}){Text("取消")}})
    searchTarget?.let{draft->PageSearchDialog(draft){searchTarget=null}}
}
@Composable
internal fun PageThumb(page:NotebookPageRow){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val strokes by produceState<List<InkStroke>?>(null,page.id){value=withContext(Dispatchers.IO){runCatching{InkSession(app.inkRepository.read(page.id)).visibleDraft()}.getOrNull()}}
    val objects by produceState<List<PageObject>>(emptyList(),page.id){value=withContext(Dispatchers.IO){runCatching{app.pageObjects.read(page.id).objects}.getOrDefault(emptyList())}}
    Box(Modifier.size(84.dp,110.dp)){
        val loaded=strokes
        AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={it.configure(false,PaperStyle.entries[page.paper],null);it.showDocument(page.id);it.showStrokes(loaded.orEmpty());it.showObjects(objects)},modifier=Modifier.fillMaxSize())
    }
}
@Composable
private fun PageSearchDialog(draft:PageSearchDraft,dismiss:()->Unit){
    val pageId=draft.pageId;val revision=draft.inkRevision
    val app=LocalContext.current.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var objectRevision by remember(draft){mutableLongStateOf(draft.objectRevision)};var ocr by remember{mutableStateOf(false)}
    var text by remember(draft){mutableStateOf(draft.text)};var busy by remember{mutableStateOf(false)}
    var message by remember(draft){mutableStateOf(if(draft.stale)"本页手写已有变化，请重新识别并校对。"else null)}
    EditorPanel("校对手写识别","",{if(!busy)dismiss()},"page-search-dialog",footer={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=dismiss,enabled=!busy){Text("取消")};TextButton(onClick={busy=true;scope.launch{try{if(withContext(Dispatchers.IO){app.pages.saveSearchText(pageId,revision,text,objectRevision,if(ocr)"OCR"else"MANUAL")})dismiss()else message="笔迹已更新，没有套用到新版本。请关闭后重新核对。"}catch(c:CancellationException){throw c}catch(_:Exception){message="校对尚未保存，请重试。"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("save-page-search")){Text("保存校对")}}}){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("只修正查找用的文字，保留纸面手写。",fontSize=12.sp,color=Quiet)
        TextButton(onClick={busy=true;message="正在离线识别…";scope.launch{try{
            val pair=withContext(Dispatchers.IO){app.inkRepository.read(pageId) to app.pageObjects.read(pageId)}
            check(pair.first.revision==revision);objectRevision=pair.second.revision
            val suppressed=pair.second.objects.flatMap{it.sourceStrokeIds}.toSet()
            val result=app.handwriting.recognize(InkSession(pair.first).visibleDraft().filterNot{it.id in suppressed})
            text=(listOf(result.text)+pair.second.objects.filter{!it.hidden&&it.kind==PageObjectKind.TEXT}.map{it.text}).filter{it.isNotBlank()}.joinToString("\n").also{require(it.length<=20000)}
            ocr=true;message=if(text.isBlank())"未识别到文字，可手动补充关键词。"else"识别完成，请核对后保存。"
        }catch(c:CancellationException){throw c}catch(_:Exception){message="识别未完成或页面已经变化。原笔迹保留，可关闭后重试。"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("recognize-page")){Text("识别本页手写")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(text,{if(it.length<=20_000)text=it},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=96.dp,max=160.dp).testTag("page-search-text"),label={Text("识别出的文字")})
        message?.let{Text(it,fontSize=12.sp)}
    }
    }
}
