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
    val keyboard=LocalSoftwareKeyboardController.current;val focus=LocalFocusManager.current;val scope=rememberCoroutineScope()
    var showCreate by remember{mutableStateOf(false)};var newTitle by remember{mutableStateOf("")}
    var newWorld by remember{mutableStateOf(false)};var newPaper by remember{mutableStateOf(PaperStyle.RULED)};var newCover by remember{mutableStateOf(NotebookCover.AUTO)}
    var inkMode by rememberSaveable(ui.selectedId){mutableStateOf(true)}
    var confirmExport by remember{mutableStateOf(false)};var exportText by remember{mutableStateOf<String?>(null)};var importing by remember{mutableStateOf(false)}
    val textExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        val text=exportText;exportText=null
        if(uri!=null&&text!=null)scope.launch{val ok=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false};Toast.makeText(context,if(ok)"文字已写入所选位置"else"导出失败，原文仍保留",Toast.LENGTH_LONG).show()}
    }
    val pageImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null&&!importing)scope.launch{
            importing=true
            try{val n=withContext(Dispatchers.IO){val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val size=input.read(buffer);if(size<0)break;require(out.size()+size<=InkPageFile.MAX_BYTES);out.write(buffer,0,size)};out.toByteArray()};if(NotebookFile.isBook(bytes))app.pages.importBook(NotebookFile.decode(bytes))else app.inkRepository.importCopy(InkPageFile.decode(bytes))};if(vm.ui.value.selectedId==null)vm.select(n)}
            catch(c:CancellationException){throw c}catch(_:Exception){Toast.makeText(context,"无法导入此页面副本；原资料未覆盖",Toast.LENGTH_LONG).show()}finally{importing=false}
        }
    }
    fun openCreate(){newTitle="";newWorld=false;newPaper=PaperStyle.RULED;newCover=NotebookCover.AUTO;showCreate=true}
    BackHandler(enabled=ui.selectedId!=null){vm.back()}
    LaunchedEffect(ui.selectedId,inkMode){if(ui.selectedId!=null&&inkMode){focus.clearFocus(force=true);keyboard?.hide()}}
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().imePadding()){
        if(ui.readFailed)Surface(color=Color(0xffffeee7)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("资料读取失败，原数据不会被空库覆盖。",Modifier.weight(1f),fontSize=13.sp);TextButton(onClick=vm::retryRead){Text("重试")};if(ui.current!=null)TextButton(onClick=onDiagnostics,modifier=Modifier.testTag("open-diagnostics-error")){Text("诊断")}}}
        if(workspaceError!=null)Surface(color=Color(0xfffff4e3)){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(workspaceError!!,Modifier.weight(1f),fontSize=12.sp);TextButton(onClick=workspace::clearError){Text("知道了")}}}
        if(importing||busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        val draft=ui.current
        if(draft==null)Box(Modifier.weight(1f)){LibraryScreen(ui,workspace,vm::select,::openCreate,{pageImport.launch(arrayOf("application/octet-stream","*/*"))},onDiagnostics,vm::openRename)}
        else{
            Row(Modifier.fillMaxWidth().heightIn(min=58.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick=vm::back,modifier=Modifier.testTag("back-library")){Glyph("back");Spacer(Modifier.width(5.dp));Text("资料库",fontSize=13.sp)}
                VerticalDivider(Modifier.height(23.dp),color=Line)
                Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=draft.base.revision>0){vm.openRename(draft.base)}.testTag("rename-from-editor").describedAs("重命名笔记"),verticalAlignment=Alignment.CenterVertically){
                    Text(draft.title,fontSize=17.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f,false),maxLines=1,overflow=TextOverflow.Ellipsis);Spacer(Modifier.width(7.dp));Glyph("pen",Quiet,Modifier.size(14.dp))
                }
                FilterChip(selected=inkMode,onClick={inkMode=true},enabled=draft.base.revision>0,label={Text("手写",fontSize=12.sp)},modifier=Modifier.testTag("mode-ink"))
                FilterChip(selected=!inkMode,onClick={inkMode=false},label={Text("文字",fontSize=12.sp)},modifier=Modifier.testTag("mode-text"))
                IconButton(onClick=onDiagnostics,modifier=Modifier.testTag("open-diagnostics").describedAs("诊断与导出")){Glyph("diagnostics",Quiet)}
            }
            HorizontalDivider(color=Line)
            if(inkMode&&draft.base.revision>0)Box(Modifier.weight(1f)){key(draft.base.id){InkScreen(draft,workspace)}}
            else TextPage(draft,vm,Modifier.weight(1f)){confirmExport=true}
        }
    }
    if(showCreate)AlertDialog(onDismissRequest={if(!busy)showCreate=false},title={Text("新建笔记")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
            OutlinedTextField(newTitle,{if(it.length<=120)newTitle=it},singleLine=true,label={Text("笔记标题")},placeholder={Text("未命名笔记")},modifier=Modifier.fillMaxWidth().testTag("new-title"))
            Text("笔记形式",fontSize=13.sp,color=Quiet)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                FilterChip(selected=!newWorld,onClick={newWorld=false},label={Text("纸张笔记")},leadingIcon={Glyph("note")},modifier=Modifier.testTag("create-page"))
                FilterChip(selected=newWorld,onClick={newWorld=true;newPaper=PaperStyle.DOTS},label={Text("无界笔记")},leadingIcon={Glyph("board")},modifier=Modifier.testTag("create-world"))
            }
            Text(if(newWorld)"向四周展开，支持负坐标、平移和缩放。"else"分页笔记本，创建后可插入新页，逐页书写和导出整本。",fontSize=12.sp,color=Quiet,lineHeight=20.sp)
            Text("纸面样式",fontSize=13.sp,color=Quiet)
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                PaperStyle.entries.forEach{style->Column(Modifier.weight(1f).clickable{newPaper=style},horizontalAlignment=Alignment.CenterHorizontally){
                    Box(Modifier.fillMaxWidth().height(70.dp).border(if(newPaper==style)2.dp else 1.dp,if(newPaper==style)Forest else Line,androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(5.dp)){PaperThumbnail(newWorld,style)}
                    Text(when(style){PaperStyle.BLANK->"空白";PaperStyle.RULED->"横线";PaperStyle.GRID->"方格";PaperStyle.DOTS->"点阵"},fontSize=11.sp,color=if(newPaper==style)Forest else Quiet,modifier=Modifier.padding(top=6.dp))
                }}
            }
            Text("封面 · 可横向滑动选择",fontSize=13.sp,color=Quiet)
            CoverChoices(newCover,newTitle.ifBlank{"我的笔记"},newWorld){newCover=it}
            Text("封面不占一页，可在笔记菜单中更换；未选择时按笔记身份自动配色。",fontSize=11.sp,color=Quiet)
        }
    },confirmButton={Button(onClick={focus.clearFocus(force=true);keyboard?.hide();workspace.create(newTitle.ifBlank{"未命名笔记"},newWorld,newPaper,newCover){vm.select(it)};showCreate=false},enabled=!busy,modifier=Modifier.testTag("create-note")){Text("创建")}},dismissButton={TextButton(onClick={showCreate=false},enabled=!busy){Text("取消")}})
    if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出文字")},text={Text("明文文字副本，不包含手写、封面、历史或回执。所选位置可能由云盘提供方管理。")},confirmButton={TextButton(onClick={confirmExport=false;ui.current?.let{exportText=it.title+"\n\n"+it.text;textExport.launch("墨织笔记.txt")}}){Text("选择位置")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    rename?.let{RenameNoteDialog(it,vm::editRename,vm::saveRename,vm::closeRename)}
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
