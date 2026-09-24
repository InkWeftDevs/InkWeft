// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.inkweft.core.*

private val Green=Color(0xff236653)
private val Desk=Color(0xfff3f5f4)
private val Ink=Color(0xff24342f)

@Composable
fun NotebookApp(vm: NotebookViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val app=context.applicationContext as InkWeftApplication
    val scope=rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var inkMode by rememberSaveable(ui.selectedId) { mutableStateOf(true) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text=exportSnapshot;exportSnapshot=null
        if(uri!=null&&text!=null)scope.launch {
            val ok=runCatching { withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            } }.isSuccess
            Toast.makeText(context,if(ok)R.string.export_success else R.string.export_failed,Toast.LENGTH_LONG).show()
        }
    }
    val importPage=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !importing)scope.launch {
            importing=true
            val result=runCatching { withContext(Dispatchers.IO) {
                val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true){ val n=input.read(buffer);if(n<0)break;require(out.size()+n<=InkPageFile.MAX_BYTES);out.write(buffer,0,n) }
                    out.toByteArray()
                }
                app.inkRepository.importCopy(InkPageFile.decode(bytes))
            } }
            importing=false
            result.onSuccess { note -> if(vm.ui.value.selectedId==null)vm.select(note) }
                .onFailure { Toast.makeText(context,"无法导入此页面副本；原资料未改动",Toast.LENGTH_LONG).show() }
        }
    }
    MaterialTheme(colorScheme=lightColorScheme(primary=Green,onPrimary=Color.White,
        background=Desk,surface=Color.White,onSurface=Ink,onBackground=Ink)) {
        BackHandler(enabled=ui.selectedId!=null) { vm.back() }
        Scaffold(containerColor=Desk,topBar={
            Surface {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(ui.selectedId!=null)TextButton(onClick=vm::back,modifier=Modifier.testTag("back-library")){Text("资料库")}
                        Text(if(ui.selectedId==null)"墨织" else ui.current?.title.orEmpty(),fontSize=20.sp,
                            fontWeight=FontWeight.SemiBold,color=Green,maxLines=1,modifier=Modifier.weight(1f))
                        if(ui.selectedId==null) {
                            TextButton(onClick={importPage.launch(arrayOf("application/octet-stream","*/*"))},enabled=!importing&&!ui.readFailed){Text("导入页面")}
                            Button(onClick={newTitle="";showCreate=true},enabled=!ui.loading&&!ui.readFailed,modifier=Modifier.testTag("new-note")){Text("新建")}
                        } else {
                            FilterChip(selected=inkMode,onClick={inkMode=true},enabled=ui.current?.base?.revision?.let { it>0 }==true,label={Text("手写")},modifier=Modifier.testTag("mode-ink"))
                            FilterChip(selected=!inkMode,onClick={inkMode=false},label={Text("文字")},modifier=Modifier.testTag("mode-text"))
                            if(!inkMode || ui.current?.base?.revision==0L) {
                                val d=ui.current
                                Button(onClick=vm::save,enabled=d!=null&&d.dirty&&d.title.isNotBlank()&&d.phase in listOf(SavePhase.EDITING,SavePhase.UNKNOWN),modifier=Modifier.testTag("save-text")){
                                    Text(if(d?.phase==SavePhase.UNKNOWN)"核对重试" else "保存")
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=Color(0xffe2e8e4))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if(ui.readFailed)Surface(color=Color(0xffffede8)){
                    Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(stringResource(R.string.error_load),Modifier.weight(1f),fontSize=14.sp)
                        TextButton(onClick=vm::retryRead){Text("重试读取")}
                    }
                }
                if(importing)LinearProgressIndicator(Modifier.fillMaxWidth())
                val draft=ui.current
                when {
                    draft==null -> Library(ui,vm::select,Modifier.weight(1f))
                    inkMode&&draft.base.revision>0 -> Box(Modifier.weight(1f)) { key(draft.base.id) { InkScreen(draft) } }
                    else -> TextPage(draft,vm,Modifier.weight(1f)) { confirmExport=true }
                }
                if(draft==null)Text("A1 开发预览 · 原生手写与文字 · 本地优先，无账号和联网功能",
                    Modifier.fillMaxWidth().background(Color.White).padding(16.dp),fontSize=12.sp,color=Color(0xff596c61))
            }
        }
        if(showCreate)AlertDialog(onDismissRequest={showCreate=false},title={Text("新建笔记")},
            text={OutlinedTextField(value=newTitle,onValueChange={if(it.length<=120)newTitle=it},singleLine=true,
                label={Text("笔记标题")},modifier=Modifier.testTag("new-title"))},
            confirmButton={TextButton(onClick={vm.create(newTitle);vm.save();showCreate=false},enabled=newTitle.isNotBlank(),modifier=Modifier.testTag("create-note")){Text("创建")}},
            dismissButton={TextButton(onClick={showCreate=false}){Text("取消")}})
        if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("导出文字")},
            text={Text(stringResource(R.string.export_notice))},
            confirmButton={TextButton(onClick={confirmExport=false;ui.current?.let { exportSnapshot=it.title+"\n\n"+it.text;export.launch("墨织笔记.txt") }}){Text("选择位置")}},
            dismissButton={TextButton(onClick={confirmExport=false}){Text("取消")}})
    }
}

@Composable
private fun TextPage(d: NoteDraft,vm: NotebookViewModel,modifier: Modifier,export:()->Unit) {
    val status=when(d.phase){
        SavePhase.SAVING->R.string.saving;SavePhase.UNKNOWN->R.string.unknown
        SavePhase.CONFLICT->R.string.conflict;SavePhase.REJECTED->R.string.rejected
        SavePhase.EDITING->if(d.dirty)R.string.draft else R.string.saved
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(status),Modifier.weight(1f),fontSize=13.sp,color=if(d.dirty)Color(0xff8a4a1b) else Green)
            TextButton(onClick=export){Text("导出文字")}
        }
        if(d.phase in listOf(SavePhase.CONFLICT,SavePhase.REJECTED))TextButton(onClick=vm::discardDraftAndRead){Text(stringResource(R.string.load_saved))}
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp),contentAlignment=Alignment.TopCenter) {
            Surface(shape=RoundedCornerShape(topStart=12.dp,topEnd=12.dp),shadowElevation=2.dp,
                modifier=Modifier.widthIn(max=860.dp).fillMaxSize()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    OutlinedTextField(d.title,{vm.edit(it,d.text)},enabled=d.canEdit,singleLine=true,label={Text("标题")},
                        modifier=Modifier.fillMaxWidth(),textStyle=TextStyle(fontSize=24.sp,fontWeight=FontWeight.SemiBold))
                    Spacer(Modifier.height(24.dp))
                    BasicTextField(d.text,{vm.edit(d.title,it)},enabled=d.canEdit,
                        textStyle=TextStyle(fontSize=18.sp,lineHeight=30.sp,color=Ink),
                        modifier=Modifier.fillMaxWidth().heightIn(min=420.dp).testTag("note-body"),
                        decorationBox={inner->Box { if(d.text.isEmpty())Text("写下你的理解。文字需要点击保存；手写页抬笔后自动提交。",color=Color(0xff65746d),fontSize=17.sp,lineHeight=30.sp);inner() }})
                }
            }
        }
    }
}

@Composable
private fun Library(ui: NotebookUi,open:(Note)->Unit,modifier: Modifier) {
    val display=LinkedHashMap<String,Note>()
    ui.notes.forEach { display[it.id]=it }
    ui.drafts.values.forEach { d->display[d.base.id]=d.base.copy(title=d.title,text=d.text) }
    Column(modifier.padding(horizontal=24.dp)) {
        Text("我的笔记",fontSize=30.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=30.dp,bottom=8.dp))
        Text("从一页白纸开始，记录、推导，再整理。",color=Color(0xff586e62),fontSize=16.sp)
        Spacer(Modifier.height(24.dp))
        if(ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(display.isEmpty()&&!ui.loading) {
            Text("开始第一份笔记",fontSize=22.sp,modifier=Modifier.padding(top=50.dp))
            Text("新建后进入原生手写页，也可以切换到文字编辑。\n重要内容请主动导出页面副本。",fontSize=15.sp,lineHeight=25.sp,modifier=Modifier.padding(top=12.dp))
        } else LazyVerticalGrid(GridCells.Adaptive(220.dp),contentPadding=PaddingValues(bottom=24.dp),
            horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(display.values.toList(),key={it.id}){note->
                Card(onClick={open(note)},shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp).heightIn(min=190.dp)) {
                        Text("本地笔记",color=Green,fontSize=12.sp)
                        Spacer(Modifier.height(22.dp))
                        Text(note.title,fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
                        Spacer(Modifier.height(10.dp))
                        Text(note.text.ifBlank { "打开手写与文字页面" },maxLines=3,fontSize=14.sp,lineHeight=22.sp,color=Color(0xff586e62))
                        Spacer(Modifier.height(22.dp))
                        Text(if(ui.drafts[note.id]?.dirty==true)"文字草稿待保存" else "点击继续",fontSize=12.sp,color=Green)
                    }
                }
            }
        }
    }
}
