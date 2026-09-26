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
fun InkScreen(note:NoteDraft,workspace:WorkspaceViewModel=viewModel()){
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
    var canNavigate by remember{mutableStateOf(false)};var directory by remember{mutableStateOf(false)}
    var searchTarget by remember{mutableStateOf<Pair<String,Long>?>(null)}
    var exportBytes by remember{mutableStateOf<ByteArray?>(null)};var exporting by remember{mutableStateOf(false)}
    var confirmBook by remember{mutableStateOf(false)}
    var insertion by remember{mutableStateOf<Pair<String,PageInsertLocation>?>(null)}
    var pageActionId by rememberSaveable{mutableStateOf<String?>(null)}
    var pageActionKind by rememberSaveable{mutableStateOf(PageEditKind.MOVE.name)}
    var showRecycled by rememberSaveable{mutableStateOf(false)}
    fun requestAction(p:NotebookPageRow,kind:PageEditKind){directory=false;pageActionKind=kind.name;pageActionId=p.id}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val bytes=exportBytes;exportBytes=null
        if(uri!=null&&bytes!=null)scope.launch{val ok=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false};Toast.makeText(context,if(ok)"整本内容副本已导出"else"导出失败，原笔记保留",Toast.LENGTH_LONG).show()}
    }
    val page=ui.pages.firstOrNull{it.id==ui.selectedId}
    Column(Modifier.fillMaxSize()){
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
        if(page!=null&&!page.world)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={directory=true},enabled=canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown,modifier=Modifier.testTag("page-directory")){Text("第 ${page.position+1} / ${ui.pages.size} 页",modifier=Modifier.testTag("page-counter"))}
            TextButton(onClick={ui.pages.getOrNull(page.position-1)?.let{vm.select(it.id)}},enabled=canNavigate&&page.position>0&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown,modifier=Modifier.testTag("previous-page")){Text("上一页")}
            TextButton(onClick={ui.pages.getOrNull(page.position+1)?.let{vm.select(it.id)}},enabled=canNavigate&&page.position<ui.pages.lastIndex&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown,modifier=Modifier.testTag("next-page")){Text("下一页")}
            OutlinedButton(onClick={insertion=page.id to PageInsertLocation.AFTER},enabled=canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown&&ui.pages.size+ui.recycled.size<500,modifier=Modifier.testTag("add-page")){Text(if(ui.busy)"正在核对…"else"＋ 添加页 ▾")}
            TextButton(onClick={confirmBook=true},enabled=canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown&&!exporting,modifier=Modifier.testTag("export-book")){Text("导出整本")}
        }
        if(page==null)Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){if(ui.loading)CircularProgressIndicator()else Text("页面未能载入，原数据保留")}
        else Box(Modifier.weight(1f)){key(page.id){InkPageScreen(note,workspace,page,{canNavigate=it},{rev->searchTarget=page.id to rev},!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown&&pageActionId==null)}}
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
    if(confirmBook)AlertDialog(onDismissRequest={confirmBook=false},title={Text("导出整本内容副本")},text={Text("包括本笔记所有可用页面、局部擦除效果和已保存键入文字；不含页面回收区。明文 .iwbook，不含撤销历史、账号或密钥；不是完整资料库备份。目标可能由云盘提供。")},confirmButton={TextButton(onClick={confirmBook=false;exporting=true;scope.launch{try{val bytes=withContext(Dispatchers.IO){app.pages.exportBook(note.base.id).encode()};exportBytes=bytes;export.launch("墨织笔记本.iwbook")}catch(c:CancellationException){throw c}catch(_:Exception){Toast.makeText(context,"无法导出整本内容，原数据保留；可尝试逐页导出",Toast.LENGTH_LONG).show()}finally{exporting=false}}}){Text("选择位置")}},dismissButton={TextButton(onClick={confirmBook=false}){Text("取消")}})
    searchTarget?.let{(id,revision)->PageSearchDialog(id,revision){searchTarget=null}}
}
@Composable
internal fun PageThumb(page:NotebookPageRow){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val strokes by produceState<List<InkStroke>?>(null,page.id){value=withContext(Dispatchers.IO){runCatching{InkSession(app.inkRepository.read(page.id)).visibleDraft()}.getOrNull()}}
    Box(Modifier.size(84.dp,110.dp)){
        val loaded=strokes
        if(loaded==null||loaded.isEmpty())PaperThumbnail(false,PaperStyle.entries[page.paper])
        else AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={it.configure(false,PaperStyle.entries[page.paper],null);it.showStrokes(loaded)},modifier=Modifier.fillMaxSize())
    }
}
@Composable
private fun PageSearchDialog(pageId:String,revision:Long,dismiss:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var text by remember{mutableStateOf("")};var loading by remember{mutableStateOf(true)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(pageId){try{val old=withContext(Dispatchers.IO){app.pages.searchText(pageId)};text=old?.text.orEmpty();if(old!=null&&old.inkRevision!=revision)message="本页笔迹已变，旧检索文字已失效；确认修改后重新保存。"}catch(c:CancellationException){throw c}catch(_:Exception){message="读取检索文字失败；原笔迹没有改变。"}finally{loading=false}}
    AlertDialog(onDismissRequest={if(!busy)dismiss()},modifier=Modifier.testTag("page-search-dialog"),title={Text("本页手写检索文字")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("当前没有自动手写识别模型。可手动填入本页关键词或转录内容，保存后可从资料库搜索并定位本页。",fontSize=12.sp,color=Quiet)
        OutlinedTextField(text,{if(it.length<=20_000)text=it},enabled=!loading&&!busy,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp).testTag("page-search-text"),label={Text("关键词 / 人工转录")})
        Text("任何擦写或撤销都会让此版本的索引失效，旧文字不继续冒充当前手写；需重新核对并保存。原笔迹不被转换或上传。",fontSize=12.sp,color=Quiet)
        message?.let{Text(it,fontSize=12.sp)}
    }},confirmButton={TextButton(onClick={busy=true;scope.launch{try{if(withContext(Dispatchers.IO){app.pages.saveSearchText(pageId,revision,text)})dismiss()else message="笔迹已更新，没有套用到新版本。请关闭后重新核对。"}catch(c:CancellationException){throw c}catch(_:Exception){message="索引保存待核对，原笔迹保留。"}finally{busy=false}}},enabled=!loading&&!busy,modifier=Modifier.testTag("save-page-search")){Text("保存检索文字")}},dismissButton={TextButton(onClick=dismiss,enabled=!busy){Text("取消")}})
}
