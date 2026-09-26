// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.data.LibraryBackupRepository
import java.io.Closeable
import java.util.concurrent.Executors

internal enum class BackupMode { CLOSED, HOME, EXPORT_CONFIRM, EXPORT_READY, RESTORE_READY, BUSY, RESULT, UNKNOWN }
internal data class BackupUi(val mode:BackupMode=BackupMode.CLOSED,val message:String="",val consent:Boolean=false)

class LibraryBackupViewModel(app:Application):AndroidViewModel(app){
    private val owner=app as InkWeftApplication
    private val repo=owner.libraryBackup
    private val state=MutableStateFlow(BackupUi());internal val ui=state.asStateFlow()
    private var snapshot:LibraryBackupRepository.Snapshot?=null
    private var preview:LibraryBackupRepository.Preview?=null
    private fun dispose(){val resources=listOfNotNull(snapshot,preview);snapshot=null;preview=null
        if(resources.isNotEmpty()){val executor=Executors.newSingleThreadExecutor();executor.execute{resources.forEach{runCatching{it.close()}}};executor.shutdown()}}
    fun open(){if(ui.value.mode==BackupMode.CLOSED)state.value=BackupUi(BackupMode.HOME)}
    fun close(){if(ui.value.mode==BackupMode.BUSY)return;dispose();state.value=BackupUi()}
    fun consent(value:Boolean){state.value=ui.value.copy(consent=value)}
    fun confirmExport(){dispose();state.value=BackupUi(BackupMode.EXPORT_CONFIRM)}
    fun create(){
        if(ui.value.mode!=BackupMode.EXPORT_CONFIRM||!ui.value.consent)return
        state.value=BackupUi(BackupMode.BUSY,"正在生成已保存资料的一致备份，暂不编辑…")
        viewModelScope.launch{try{
            snapshot=repo.snapshot()
            state.value=BackupUi(BackupMode.EXPORT_READY,"备份已生成并校验记录流：${snapshot!!.summary.bytes} 字节。请选择你自己的保存位置。")
        }catch(c:CancellationException){throw c}catch(_:Exception){dispose();state.value=BackupUi(BackupMode.RESULT,"无法生成备份：空间不足、资料读取异常或版本尚未支持。原资料没有被修改。")}}
    }
    fun save(uri:Uri?){
        if(uri==null)return
        val copy=snapshot?:run{state.value=BackupUi(BackupMode.RESULT,"本次导出快照已不在进程中，请重新生成备份。没有修改原资料。");return}
        state.value=BackupUi(BackupMode.BUSY,"正在向所选文件位置写入…")
        viewModelScope.launch{try{
            withContext(Dispatchers.IO){
                val ctx=currentCoroutineContext()
                copy.file.inputStream().use{input->checkNotNull(owner.contentResolver.openOutputStream(uri,"wt")).use{out->
                    val bytes=ByteArray(8192);while(true){ctx.ensureActive();val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n)};out.flush()
                }}
            }
            dispose();state.value=BackupUi(BackupMode.RESULT,"已完成备份写入。文件提供方接受写入不代表远端云同步已完成；请妥善保管明文备份。")
        }catch(c:CancellationException){throw c}catch(_:Exception){state.value=BackupUi(BackupMode.EXPORT_READY,"目标写入未确认，可能留下不完整文件。原资料保留，可重新选择位置保存。")}}
    }
    fun inspect(uri:Uri){
        if(ui.value.mode==BackupMode.BUSY)return
        dispose();state.value=BackupUi(BackupMode.BUSY,"正在隔离区校验备份；此阶段不写入现有资料库…")
        viewModelScope.launch{try{
            preview=withContext(Dispatchers.IO){checkNotNull(owner.contentResolver.openInputStream(uri)).use{repo.inspect(it)}}
            val p=preview!!
            state.value=BackupUi(BackupMode.RESTORE_READY,"${p.notes} 本笔记、${p.pages} 页，其中回收站 ${p.trashed} 本；${p.summary.bytes} 字节。\n校验通过：完整摘要、数据表、引用及笔迹格式。\n恢复保留原身份，不覆盖已有同一身份的笔记；发生冲突时整批停止。完全相同的备份重复恢复不会产生副本。")
        }catch(c:CancellationException){throw c}catch(_:Exception){dispose();state.value=BackupUi(BackupMode.RESULT,"备份校验失败，可能损坏、超出预算、引用不完整或版本不兼容。未写入现有资料库。")}}
    }
    fun restore(){
        if(ui.value.mode !in listOf(BackupMode.RESTORE_READY,BackupMode.UNKNOWN)||!ui.value.consent)return
        val candidate=preview?:return
        state.value=BackupUi(BackupMode.BUSY,"正在原子恢复；保持页面开启…",true)
        viewModelScope.launch{try{
            val result=repo.restore(candidate)
            state.value=BackupUi(BackupMode.RESULT,when(result){
                LibraryBackupRepository.RestoreResult.RESTORED->"已恢复资料库记录，原有不冲突的笔记保留。重开后的撤销栈仍按当前版本能力处理，备份不生成原本不存在的撤销历史。"
                LibraryBackupRepository.RestoreResult.ALREADY_PRESENT->"备份记录已存在且完全一致，没有重复恢复或创建副本。"
                LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT->"已有同一身份但内容不同的资料，本次没有恢复任何记录。暂不支持覆盖或自动合并；请保留当前资料与备份。"
            });dispose()
        }catch(c:CancellationException){throw c}
        catch(_:Exception){state.value=BackupUi(BackupMode.UNKNOWN,"恢复结果待核对。可以用同一份备份核对重试；不会覆盖已有记录或重复创建。存储或历史命令身份冲突也会阻止整批写入。",true)}}
    }
    override fun onCleared(){dispose();super.onCleared()}
}

/** Activity-level launchers remain stable when the shelf changes width or mode. */
@Composable
fun LibraryBackupHost(content:@Composable ()->Unit){
    val vm:LibraryBackupViewModel=viewModel();val ui by vm.ui.collectAsStateWithLifecycle()
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::inspect)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"),vm::save)
    val notebook:NotebookViewModel=viewModel()
    val notebookUi by notebook.ui.collectAsStateWithLifecycle()
    InkWeftTheme {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            if(notebookUi.selectedId==null)Surface(color=androidx.compose.ui.graphics.Color.White){
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp)){
                    TextButton(onClick=vm::open,modifier=Modifier.testTag("library-backup-open")){
                        Glyph("export");Spacer(Modifier.width(8.dp));Text("资料库备份与恢复")
                    }
                }
            }
        }
    }
    if(ui.mode==BackupMode.CLOSED)return
    InkWeftTheme{
        AlertDialog(onDismissRequest=vm::close,modifier=Modifier.testTag("library-backup-dialog"),title={Text("资料库备份与恢复")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                when(ui.mode){
                    BackupMode.HOME->{
                        Text("备份已保存的笔记、页面、原笔迹、局部擦除、回收站、分类、封面、阅读位置、人工索引、修订与操作回执。")
                        Text("不含未保存草稿、尚未抬笔的内容、内存撤销栈、笔盒设置、缓存或密钥。不是诊断包，也不同于仅可见内容副本。")
                        OutlinedButton(onClick=vm::confirmExport,modifier=Modifier.fillMaxWidth().testTag("backup-create")){Text("创建资料库备份")}
                        OutlinedButton(onClick={open.launch(arrayOf("application/octet-stream","*/*"))},modifier=Modifier.fillMaxWidth().testTag("backup-select")){Text("选择备份并校验")}
                    }
                    BackupMode.EXPORT_CONFIRM->{
                        Text("备份为明文，包含回收站、隐藏笔迹和已保存历史。任何获得文件的人都可能读取这些内容。当前仅支持已实现的资料库格式，最大128MiB；请先保存草稿。")
                        Row{Checkbox(checked=ui.consent,onCheckedChange=vm::consent,modifier=Modifier.testTag("backup-consent"));Text("我了解明文与历史数据的范围，并自行选择保存位置。")}
                    }
                    else->Text(ui.message,modifier=Modifier.testTag("backup-message"))
                }
                if(ui.mode==BackupMode.RESTORE_READY)Row{Checkbox(checked=ui.consent,onCheckedChange=vm::consent,modifier=Modifier.testTag("restore-consent"));Text("按以上范围恢复；不覆盖冲突内容。")}
                if(ui.mode==BackupMode.BUSY)LinearProgressIndicator(Modifier.fillMaxWidth())
            }},confirmButton={when(ui.mode){
                BackupMode.EXPORT_CONFIRM->TextButton(onClick=vm::create,enabled=ui.consent,modifier=Modifier.testTag("backup-generate")){Text("生成备份")}
                BackupMode.EXPORT_READY->TextButton(onClick={save.launch("墨织资料库-${System.currentTimeMillis()}.iwbackup")},modifier=Modifier.testTag("backup-save")){Text("选择保存位置")}
                BackupMode.RESTORE_READY,BackupMode.UNKNOWN->TextButton(onClick=vm::restore,enabled=ui.consent,modifier=Modifier.testTag("backup-restore")){Text(if(ui.mode==BackupMode.UNKNOWN)"核对原备份"else"确认恢复")}
                BackupMode.BUSY->{}
                else->TextButton(onClick=vm::close){Text("关闭")}
            }},dismissButton={if(ui.mode !in listOf(BackupMode.HOME,BackupMode.RESULT,BackupMode.BUSY))TextButton(onClick=vm::close){Text("取消")}})
    }
}
