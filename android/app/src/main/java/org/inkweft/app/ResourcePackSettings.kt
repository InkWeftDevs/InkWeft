// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable internal fun ResourcePackSettings(dismiss:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val scope=rememberCoroutineScope();val store=app.resourcePacks
    var installed by remember{mutableStateOf<List<InstalledPack>>(emptyList())}
    var pending by remember{mutableStateOf<ResourcePack?>(null)}
    var copyTitles by remember{mutableStateOf<Map<String,String>>(emptyMap())}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    fun run(action:suspend ()->Unit){if(busy)return;busy=true;scope.launch{try{action();installed=store.installed();copyTitles=withContext(Dispatchers.IO){installed.flatMap{it.copies}.distinct().associateWith{app.repository.read(it)?.title?:"已不可用笔记"}}}catch(c:CancellationException){throw c}catch(_:Exception){message="资源包无效、空间不足或操作未完成；已安装版本与笔记保留。"}finally{busy=false}}}
    LaunchedEffect(Unit){run{}}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)run{pending=store.inspect(checkNotNull(app.contentResolver.openInputStream(uri)));message="请核对来源与模板，再安装。"}}
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.padding(16.dp).widthIn(max=720.dp).fillMaxWidth().heightIn(max=760.dp).testTag("resource-pack-settings"),shape=InkTheme.FloatingShape,color=Side,shadowElevation=InkTheme.FloatingElevation){Column(Modifier.padding(16.dp)){
            PanelHeading("本地模板包","返回设置",dismiss,back=true)
            OutlinedButton(onClick={pick.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!busy){Text("选择模板包")}
            if(message.isNotBlank())Text(message,color=Quiet)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f)){
                pending?.let{pack->item{
                    HorizontalDivider(color=Line);Text(pack.title,style=MaterialTheme.typography.titleMedium)
                    Text("作者声明：${pack.author} · 版本 ${pack.version}")
                    Text("本地未认证来源 · ${pack.resources.size} 个模板 · ${android.text.format.Formatter.formatFileSize(app,pack.bytes.size.toLong())}",color=Quiet)
                    pack.resources.forEach{r->Text("${if(r.map==null)"纸张"else"导图"} · ${r.title}");if(r.map!=null)Text(r.map.nodes.take(5).joinToString(" · "){it.title},style=MaterialTheme.typography.bodySmall)}
                    Row{Button(onClick={run{store.install(pack);pending=null;message="模板包已安装"}},enabled=!busy){Text("确认安装")};TextButton(onClick={pending=null},enabled=!busy){Text("取消")}}
                }}
                items(installed,key={it.pack.hash}){entry->val pack=entry.pack
                    HorizontalDivider(color=Line);Text("${pack.title} · v${pack.version}",style=MaterialTheme.typography.titleMedium)
                    Text("作者声明：${pack.author} · 本地未认证来源",color=Quiet)
                    if(entry.damaged)Text("归档损坏：重新导入原包可修复，已有笔记仍保留。",color=Quiet)
                    Row{
                        TextButton(onClick={run{store.enable(pack.hash,!entry.enabled)}},enabled=!busy&&!entry.damaged){Text(if(entry.enabled)"禁用"else"启用")}
                        TextButton(onClick={run{store.uninstall(pack.hash);message=if(entry.copies.isEmpty())"已卸载"else"已停用，保留已有内容使用的版本"}},enabled=!busy){Text("卸载")}
                    }
                    if(entry.copies.isNotEmpty())Text("使用此版本："+entry.copies.joinToString("、"){copyTitles[it].orEmpty()},style=MaterialTheme.typography.bodySmall,color=Quiet)
                    pack.resources.forEach{r->OutlinedButton(onClick={run{val note=store.instantiate(pack.hash,r.id);message="已新建「${note.title}」，可回资料库打开。"}},enabled=entry.enabled&&!busy){Text("从${if(r.map==null)"纸张"else"导图"}新建 · ${r.title}")}}
                }
                if(installed.isEmpty()&&pending==null)item{Text("安装纸张或导图结构模板后，在新建笔记、添加页面或新建导图时选择“我的模板”。模板不会改动已有笔记。",color=Quiet)}
            }
        }}
    }
}
