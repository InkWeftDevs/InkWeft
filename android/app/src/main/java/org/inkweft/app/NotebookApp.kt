// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*

@Composable
fun WorkspaceApp(){
    val vm:NotebookViewModel=viewModel();val ui by vm.ui.collectAsStateWithLifecycle()
    val app=LocalContext.current.applicationContext as InkWeftApplication;val current=ui.current
    val result=when{ui.readFailed->DiagnosticResult.READ_FAILED;ui.loading->DiagnosticResult.LOADING;current==null->DiagnosticResult.OBSERVED;current.phase==SavePhase.UNKNOWN->DiagnosticResult.UNKNOWN;current.phase==SavePhase.CONFLICT->DiagnosticResult.CONFLICT;current.phase==SavePhase.REJECTED->DiagnosticResult.REJECTED;current.phase==SavePhase.SAVING->DiagnosticResult.SAVING;current.dirty->DiagnosticResult.EDITING;else->DiagnosticResult.SAVED}
    SideEffect{app.diagnostics.notebook(ui.loading,ui.readFailed,ui.notes.size,ui.drafts.size,ui.drafts.values.count{it.dirty},result)}
    var diagnostic by remember{mutableStateOf(false)}
    InkWeftTheme{NotebookApp(vm){diagnostic=true};if(diagnostic)DiagnosticDialog{diagnostic=false}}
}

@Composable
fun NotebookApp(vm:NotebookViewModel=viewModel(),onDiagnostics:()->Unit={}){
    val ui by vm.ui.collectAsStateWithLifecycle();val rename by vm.renaming.collectAsStateWithLifecycle()
    val workspace:WorkspaceViewModel=viewModel();val workspaceError by workspace.error.collectAsStateWithLifecycle();val busy by workspace.busy.collectAsStateWithLifecycle()
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val transfers:LibraryTransfersViewModel=viewModel();val transferUi by transfers.ui.collectAsStateWithLifecycle()
    val keyboard=LocalSoftwareKeyboardController.current;val focus=LocalFocusManager.current;val scope=rememberCoroutineScope()
    val pendingCreate by workspace.pendingCreate.collectAsStateWithLifecycle()
    val defaults=remember(context){context.getSharedPreferences("inkweft-new-notebook",android.content.Context.MODE_PRIVATE)}
    val target by app.openKnowledgeTarget.collectAsStateWithLifecycle()
    val navigationReady by app.navigationReady.collectAsStateWithLifecycle()
    SideEffect{if(ui.selectedId==null)app.navigationReady.value=true}
    LaunchedEffect(target,navigationReady){val ref=target?:return@LaunchedEffect;if(!navigationReady)return@LaunchedEffect
        try{val (destination,anchor)=withContext(Dispatchers.IO){app.knowledge.resolve(ref)}
            if(ref.kind==TargetKind.CARD)workspace.studyCardRequest.value=ref.id
            if(anchor!=null){workspace.focusAnchor.value=anchor;workspace.openSearchPage(destination.id,anchor.pageId){vm.select(destination)}}
            else if(ref.kind==TargetKind.PAGE)workspace.openSearchPage(destination.id,ref.id){vm.select(destination)}else vm.select(destination)
        }catch(c:CancellationException){throw c}catch(_:Exception){Toast.makeText(context,"来源已回收或无法读取，未切换到其他页面。",Toast.LENGTH_LONG).show()}
        finally{app.openKnowledgeTarget.value=null}
    }
    var showCreate by rememberSaveable{mutableStateOf(false)};var newTitle by rememberSaveable{mutableStateOf("")}
    var newWorld by rememberSaveable{mutableStateOf(false)};var newPaper by rememberSaveable{mutableStateOf(PaperStyle.RULED)};var newCover by rememberSaveable{mutableStateOf(NotebookCover.AUTO)}
    var newCustomCover by rememberSaveable{mutableStateOf<ByteArray?>(null)}
    var inkMode by rememberSaveable(ui.selectedId){mutableStateOf(true)}
    SideEffect{if(!inkMode)app.navigationReady.value=true}
    var confirmExport by remember{mutableStateOf(false)};var exportText by remember{mutableStateOf<String?>(null)}
    val textExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        val text=exportText;exportText=null
        if(uri!=null&&text!=null)scope.launch{val ok=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false};Toast.makeText(context,if(ok)"文字已写入所选位置"else"导出失败，原文仍保留",Toast.LENGTH_LONG).show()}
    }
    val pageImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)transfers.readUri(uri)
    }
    // Leave the previous text field before opening another input window. Clearing
    // Compose focus does not discard the NoteDraft; it prevents the body IME from
    // reopening behind the rename dialog when that dialog is dismissed.
    fun beginRename(note:Note){focus.clearFocus(force=true);keyboard?.hide();vm.openRename(note)}
    fun openCreate(){if(pendingCreate!=null)return;newTitle="";newWorld=defaults.getBoolean("world",false);newPaper=runCatching{PaperStyle.valueOf(defaults.getString("paper","RULED")!!)}.getOrDefault(PaperStyle.RULED);newCover=NotebookCover.fromKey(defaults.getString("cover","auto")!!).let{if(it==NotebookCover.CUSTOM)NotebookCover.AUTO else it};newCustomCover=null;showCreate=true}
    BackHandler(enabled=ui.selectedId!=null){vm.back()}
    LaunchedEffect(ui.selectedId,inkMode){if(ui.selectedId!=null&&inkMode){focus.clearFocus(force=true);keyboard?.hide()}}
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().imePadding()){
        if(ui.readFailed)Surface(color=Color(0xffffeee7)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("资料读取失败，原数据不会被空库覆盖。",Modifier.weight(1f),fontSize=13.sp);TextButton(onClick=vm::retryRead){Text("重试")};if(ui.current!=null)TextButton(onClick=onDiagnostics,modifier=Modifier.testTag("open-diagnostics-error")){Text("诊断")}}}
        if(workspaceError!=null)Surface(color=Color(0xfffff4e3)){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(workspaceError!!,Modifier.weight(1f),fontSize=12.sp);TextButton(onClick=workspace::clearError){Text("知道了")}}}
        if(pendingCreate!=null&&!busy)TextButton(onClick={workspace.retryCreate{vm.select(it)}},modifier=Modifier.testTag("retry-create-notebook")){Text("核对原创建请求")}
        if(transferUi.busy||busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        val draft=ui.current
        if(draft==null)Box(Modifier.weight(1f)){LibraryScreen(ui,workspace,vm::select,::openCreate,{pageImport.launch(arrayOf("application/octet-stream","*/*"))},onDiagnostics,::beginRename,{transfers.requestCopy(it.id)},{transfers.export(it.id)})}
        else{
            if(!inkMode)Row(Modifier.fillMaxWidth().heightIn(min=58.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick=vm::back,modifier=Modifier.testTag("back-library")){Glyph("back");Spacer(Modifier.width(5.dp));Text("资料库",fontSize=13.sp)}
                VerticalDivider(Modifier.height(23.dp),color=Line)
                Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=draft.base.revision>0){beginRename(draft.base)}.testTag("rename-from-editor").describedAs("重命名笔记"),verticalAlignment=Alignment.CenterVertically){
                    Text(draft.title,fontSize=17.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f,false),maxLines=1,overflow=TextOverflow.Ellipsis);Spacer(Modifier.width(7.dp));Glyph("pen",Quiet,Modifier.size(14.dp))
                }
                FilterChip(selected=inkMode,onClick={inkMode=true},enabled=draft.base.revision>0,label={Text("手写",fontSize=12.sp)},modifier=Modifier.testTag("mode-ink"))
                FilterChip(selected=!inkMode,onClick={inkMode=false},label={Text("文字",fontSize=12.sp)},modifier=Modifier.testTag("mode-text"))
                IconButton(onClick=onDiagnostics,modifier=Modifier.testTag("open-diagnostics").describedAs("诊断与导出")){Glyph("diagnostics",Quiet)}
            }
            HorizontalDivider(color=Line)
            if(inkMode&&draft.base.revision>0)Box(Modifier.weight(1f)){key(draft.base.id){InkScreen(draft,workspace,vm::back,{beginRename(draft.base)},{inkMode=false},onDiagnostics)}}
            else TextPage(draft,vm,Modifier.weight(1f)){confirmExport=true}
        }
    }
    if(showCreate)NewNotebookScreen(newTitle,{newTitle=it},newWorld,{newWorld=it;if(it&&newPaper.ordinal>=4)newPaper=PaperStyle.DOTS},
        newPaper,{newPaper=it},newCover,{newCover=it},newCustomCover,{newCustomCover=it},busy,{showCreate=false}){
        focus.clearFocus(force=true);keyboard?.hide()
        workspace.create(newTitle.ifBlank{"未命名笔记"},newWorld,newPaper,newCover,if(newCover==NotebookCover.CUSTOM)newCustomCover else null){defaults.edit().putBoolean("world",newWorld).putString("paper",newPaper.name).putString("cover",newCover.key).apply();vm.select(it)}
        showCreate=false
    }
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出文字")},text={Text("明文文字副本，不包含手写、封面、历史或回执。所选位置可能由云盘提供方管理。")},confirmButton={TextButton(onClick={confirmExport=false;ui.current?.let{exportText=it.title+"\n\n"+it.text;textExport.launch("墨织笔记.txt")}}){Text("选择位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    rename?.let{RenameNoteDialog(it,vm::editRename,vm::saveRename,vm::closeRename)}
    LibraryTransferDialog(transfers,{pageImport.launch(arrayOf("application/octet-stream","*/*"))},vm::select)
}

@Composable
private fun TextPage(d:NoteDraft,vm:NotebookViewModel,modifier:Modifier,export:()->Unit){
    val status=when(d.phase){SavePhase.SAVING->"正在保存…";SavePhase.UNKNOWN->"保存结果待核对，草稿仍保留";SavePhase.CONFLICT->"内容已有更新，请保留草稿后处理";SavePhase.REJECTED->"保存被拒绝，草稿仍保留";SavePhase.EDITING->if(d.dirty)"文字草稿待保存"else"已提交至本机数据库"}
    Column(modifier.fillMaxWidth().background(Color(0xfff2f4f3))){
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
            Text(status,Modifier.weight(1f),fontSize=12.sp,color=if(d.dirty)Color(0xff8a4a1b)else Forest);TextButton(onClick=export){Text("导出文字")}
            Button(onClick=vm::save,enabled=d.dirty&&d.title.isNotBlank()&&d.phase in listOf(SavePhase.EDITING,SavePhase.UNKNOWN),modifier=Modifier.testTag("save-text")){Text(if(d.phase==SavePhase.UNKNOWN)"核对重试"else"保存")}
        }
        if(d.phase in listOf(SavePhase.CONFLICT,SavePhase.REJECTED))TextButton(onClick=vm::discardDraftAndRead){Text("放弃此草稿，读取已保存版本")}
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp),contentAlignment=Alignment.TopCenter){
            Surface(Modifier.widthIn(max=860.dp).fillMaxSize(),color=Color.White,shadowElevation=1.dp){Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)){
                OutlinedTextField(d.title,{vm.edit(it,d.text)},enabled=d.canEdit,singleLine=true,label={Text("标题")},modifier=Modifier.fillMaxWidth(),textStyle=TextStyle(fontSize=22.sp,fontWeight=FontWeight.SemiBold))
                Spacer(Modifier.height(22.dp));BasicTextField(d.text,{vm.edit(d.title,it)},enabled=d.canEdit,textStyle=TextStyle(fontSize=18.sp,lineHeight=30.sp,color=TextInk),modifier=Modifier.fillMaxWidth().heightIn(min=420.dp).testTag("note-body"),decorationBox={inner->Box{if(d.text.isEmpty())Text("写下你的理解。文字需要点击保存；手写抬笔后自动提交。",fontSize=16.sp,lineHeight=27.sp,color=Quiet);inner()}})
            }}
        }
    }
}
