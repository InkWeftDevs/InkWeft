// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import kotlinx.coroutines.*

@Composable internal fun BackupSettings(dismiss:()->Unit){
    val vm:BackupJobs=viewModel();val ui by vm.ui.collectAsStateWithLifecycle();val context=LocalContext.current;val scope=rememberCoroutineScope()
    var url by rememberSaveable{mutableStateOf("")};var username by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var library by rememberSaveable{mutableStateOf(vm.sessions.localLibrary)}
    var recovery by remember{mutableStateOf("")};var recoverySaved by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    var exportMaterial by remember{mutableStateOf<String?>(null)}
    val saveKey=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->val material=exportMaterial;exportMaterial=null
        if(uri!=null&&material!=null)scope.launch{try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(material)}};recoverySaved=true;message="恢复密钥已保存"}catch(c:CancellationException){throw c}catch(_:Exception){message="密钥保存未确认，请重新保存"}}
    }
    val loadKey=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{
        val j=withContext(Dispatchers.IO){val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use{it.readBytesLimited(4096)};JSONObject(bytes.toString(Charsets.UTF_8))}
        require(j.getString("format")=="inkweft.recovery-key.v1");val value=j.getString("key");require(EncryptedBackupFile.unb64(value).size==32);java.util.UUID.fromString(j.getString("library"))
        recovery=value;library=j.getString("library");recoverySaved=true;message="恢复密钥已读取"
    }catch(c:CancellationException){throw c}catch(_:Exception){message="密钥文件无效"}}}
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("encrypted-backup-settings"),color=Side){Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=dismiss,modifier=Modifier.describedAs("返回设置")){Glyph("back")};Text("加密备份与恢复",style=MaterialTheme.typography.titleLarge)}
            Text("实验功能 · 连接自己的服务器，手动备份已保存资料。恢复不会覆盖现有笔记。",color=Quiet)
            if(ui.connected){Text(vm.identityLabel(),style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::logout,enabled=!ui.busy){Text("退出此设备会话")}}
            else{
                OutlinedTextField(url,{url=it},label={Text("服务器地址（HTTPS）")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("backup-server"))
                OutlinedTextField(username,{username=it},label={Text("管理员创建的账号")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it},label={Text("密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(onClick={vm.login(url,username,password);password=""},enabled=!ui.busy&&url.isNotBlank()&&username.isNotBlank()&&password.isNotEmpty()){Text("连接")}
            }
            HorizontalDivider(color=Line)
            OutlinedTextField(library,{library=it;recoverySaved=false},label={Text("服务器资料库 ID")},supportingText={Text("默认对应本机完整资料库；恢复时从密钥文件读取。")},singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
            Text("恢复密钥",style=MaterialTheme.typography.titleMedium)
            Text("服务器和登录密码无法找回此密钥。请将密钥文件保存在另一个安全位置。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            OutlinedTextField(recovery,{recovery=it;recoverySaved=false},label={Text("恢复密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
            Row{
                TextButton(onClick={recovery=EncryptedBackupFile.b64(EncryptedBackupFile.random(32));recoverySaved=false},enabled=!ui.busy){Text("生成密钥")}
                TextButton(onClick={loadKey.launch(arrayOf("application/json","application/octet-stream"))},enabled=!ui.busy){Text("读取密钥文件")}
            }
            TextButton(onClick={exportMaterial=JSONObject().put("format","inkweft.recovery-key.v1").put("library",library).put("key",recovery).toString(2);saveKey.launch("墨织恢复密钥.json")},enabled=!ui.busy&&runCatching{java.util.UUID.fromString(library);EncryptedBackupFile.unb64(recovery).size==32}.getOrDefault(false)){Text("保存恢复密钥文件")}
            message?.let{Text(it,color=Quiet)}
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(ui.message,modifier=Modifier.testTag("backup-task-status"))
            Row{
                Button(onClick={vm.create(library,recovery)},enabled=ui.connected&&!ui.busy&&recoverySaved){Text("创建加密快照")}
                Spacer(Modifier.width(8.dp));OutlinedButton(onClick=vm::upload,enabled=ui.connected&&!ui.busy){Text("继续上传")}
            }
            if(ui.busy)TextButton(onClick=vm::pause){Text("暂停任务")}
            TextButton(onClick={vm.list(library)},enabled=ui.connected&&!ui.busy){Text("查看服务器备份")}
            ui.versions.forEach{version->OutlinedButton(onClick={vm.inspect(library,version,recovery)},enabled=!ui.busy&&recoverySaved,modifier=Modifier.fillMaxWidth()){Text("下载并校验 · ${version.take(8)}")}}
            if(ui.restoreNotes!=null){Text("已校验 ${ui.restoreNotes} 本笔记，确认导入？");Row{Button(onClick=vm::restore,enabled=!ui.busy){Text("确认恢复")};TextButton(onClick=vm::cancelRestore,enabled=!ui.busy){Text("取消")}}}
        }}
    }
}
internal fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
    while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n)};return out.toByteArray()
}
