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
        if(uri!=null&&material!=null)scope.launch{try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(material)}};recoverySaved=false;message="密钥已保存，请读取文件确认可以找回"}catch(c:CancellationException){throw c}catch(_:Exception){message="密钥保存未确认，请重新保存"}}
    }
    val loadKey=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{
        val j=withContext(Dispatchers.IO){val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use{it.readBytesLimited(4096)};JSONObject(bytes.toString(Charsets.UTF_8))}
        require(j.getString("format")=="inkweft.recovery-key.v1");val value=j.getString("key");require(EncryptedBackupFile.unb64(value).size==32);java.util.UUID.fromString(j.getString("library"))
        recovery=value;library=j.getString("library");recoverySaved=true;message="恢复密钥已读取"
    }catch(c:CancellationException){throw c}catch(_:Exception){message="密钥文件无效"}}}
    var metered by remember{mutableStateOf(vm.sessions.meteredAllowed)}
    var screen by rememberSaveable{mutableStateOf("main")}
    var removing by remember{mutableStateOf<BackupVersion?>(null)}
    LaunchedEffect(ui.auth){vm.connection()?.let{url=it.url;username=it.name};if(ui.auth==BackupAuth.REAUTHENTICATE)screen="connection"}
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.padding(16.dp).widthIn(max=600.dp).fillMaxWidth().heightIn(max=760.dp).testTag("encrypted-backup-settings"),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),color=Side){Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={if(screen=="main")dismiss()else screen="main"},modifier=Modifier.describedAs("返回设置")){Glyph("back")};Text(when(screen){"connection"->"连接服务器";"key"->"恢复密钥";"versions"->"备份版本";"advanced"->"高级详情";else->"加密备份与恢复"},style=MaterialTheme.typography.titleLarge)}
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(ui.message,modifier=Modifier.testTag("backup-task-status"))
            message?.let{Text(it,color=Quiet)}
            when(screen){
                "main"->{
                    if(vm.identityLabel().isNotBlank())Text(vm.identityLabel())
                    Text("实验功能 · 自建服务器",style=MaterialTheme.typography.labelLarge,color=Quiet)
                    TextButton(onClick={screen="connection"}){Text(if(ui.connected)"连接设置"else"连接服务器")}
                    TextButton(onClick={screen="key"}){Text(if(recoverySaved)"恢复密钥已就绪"else"设置恢复密钥")}
                    Button(onClick={vm.backup(library,recovery)},enabled=ui.connected&&!ui.busy&&recoverySaved){Text("立即备份")}
                    OutlinedButton(onClick={screen="versions";vm.list(library)},enabled=ui.connected&&!ui.busy){Text("查看版本与恢复")}
                    TextButton(onClick=vm::upload,enabled=ui.connected&&!ui.busy&&vm.hasPendingUpload()){Text("继续未完成备份")}
                    if(ui.busy)TextButton(onClick=vm::pause){Text("暂停")}
                    TextButton(onClick={screen="advanced"}){Text("高级详情")}
                }
                "connection"->{
                    if(ui.auth==BackupAuth.REAUTHENTICATE)Text("请重新验证原账号。未完成备份与本地笔记仍保留。")
                    OutlinedTextField(url,{url=it},label={Text("服务器地址（HTTPS）")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("backup-server"))
                    OutlinedTextField(username,{username=it},label={Text("管理员创建的账号")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(password,{password=it},label={Text("密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={vm.login(url,username,password);password=""},enabled=!ui.busy&&url.isNotBlank()&&username.isNotBlank()&&password.isNotEmpty()){Text("连接")}
                    Row(verticalAlignment=Alignment.CenterVertically){Text("允许计量网络",Modifier.weight(1f));Switch(metered,{metered=it;vm.sessions.meteredAllowed=it;BackupScheduler.cancel(context);if((context.applicationContext as InkWeftApplication).backupEngine.mayResume())BackupScheduler.schedule(context)})}
                    TextButton(onClick=vm::logout){Text("断开本机连接")}
                }
                "key"->{
                    Text("密钥用于解密备份，登录密码不能替代。请把密钥文件保存在另一处安全位置。",color=Quiet)
                    Text("1. 生成并保存密钥文件",style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={recovery=EncryptedBackupFile.b64(EncryptedBackupFile.random(32));recoverySaved=false},enabled=!ui.busy){Text("生成新密钥")}
                    OutlinedButton(onClick={exportMaterial=JSONObject().put("format","inkweft.recovery-key.v1").put("library",library).put("key",recovery).toString(2);saveKey.launch("墨织恢复密钥.json")},enabled=!ui.busy&&runCatching{EncryptedBackupFile.unb64(recovery).size==32}.getOrDefault(false)){Text("保存密钥文件")}
                    Text("2. 读取文件，确认可以找回",style=MaterialTheme.typography.titleMedium)
                    Button(onClick={loadKey.launch(arrayOf("application/json","application/octet-stream"))},enabled=!ui.busy){Text("读取密钥文件")}
                    if(recoverySaved)Text("密钥已读取，可备份或选择一个版本校验恢复。")
                }
                "versions"->{
                    TextButton(onClick={vm.list(library)},enabled=ui.connected&&!ui.busy){Text("刷新")}
                    if(vm.hasPendingUpload())TextButton(onClick=vm::abandonLocal,enabled=!ui.busy){Text("放弃尚未上传的本机任务")}
                    if(vm.hasPendingDeletion())TextButton(onClick=vm::retryDeletion,enabled=!ui.busy){Text("继续确认上一次删除")}
                    ui.details.forEach{version->
                        HorizontalDivider(color=Line)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(version.created*1000)),style=MaterialTheme.typography.titleMedium)
                        Text("${android.text.format.Formatter.formatFileSize(context,version.bytes)} · 加密备份 v1 · ${if(version.status=="PUBLISHED")"已完成"else"未完成"}",color=Quiet)
                        Text(if(version.operation in ui.checked)"本次已校验"else"本次尚未校验",style=MaterialTheme.typography.bodySmall,color=Quiet)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            if(version.status=="PUBLISHED")TextButton(onClick={if(!recoverySaved)screen="key"else vm.inspect(library,version.operation,recovery)},enabled=!ui.busy){Text("校验与恢复")}
                            else TextButton(onClick=vm::upload,enabled=!ui.busy){Text("继续本机任务")}
                            TextButton(onClick={removing=version},enabled=!ui.busy){Text(if(version.status=="PUBLISHED")"删除"else"放弃上传")}
                        }
                    }
                    if(ui.details.isEmpty())Text("暂无备份版本")
                }
                "advanced"->{
                    OutlinedTextField(library,{library=it;recoverySaved=false},label={Text("资料库 ID")},singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
                    Text("恢复时由密钥文件读取对应资料库。",color=Quiet)
                    Text(vm.identityLabel(),style=MaterialTheme.typography.bodySmall)
                    Text("离开设置后继续；后台等待网络、电量和温度允许。强行停止应用后，重新打开才能恢复调度。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                }
            }
            if(ui.restoreNotes!=null){HorizontalDivider(color=Line);Text("已校验 ${ui.restoreNotes} 本笔记，确认导入？身份冲突会停止，原笔记保留。");Row{Button(onClick=vm::restore,enabled=!ui.busy){Text("确认恢复")};TextButton(onClick=vm::cancelRestore,enabled=!ui.busy){Text("取消")}}}
        }}
    }
    removing?.let{version->AlertDialog(onDismissRequest={removing=null},title={Text(if(version.status=="PENDING")"放弃未完成上传？"else if(ui.details.count{it.status=="PUBLISHED"}==1)"删除最后一份云端备份？"else"删除云端备份？")},text={Text("将删除此版本的云端密文，本地笔记与密钥文件保留。若发布结果尚未返回，会先核对同一任务。")},confirmButton={TextButton(onClick={vm.delete(library,version.operation,version.status=="PENDING");removing=null}){Text("确认删除")}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

internal fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
    while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n)};return out.toByteArray()
}
