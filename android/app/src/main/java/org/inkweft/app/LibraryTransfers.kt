// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class LibraryTransferUi(val mode:String="",val busy:Boolean=false,
    val message:String="",val needsFile:Boolean=false,val result:Note?=null,val export:ContentExport?=null)

/** Contains no author database path or credentials. Android saved state stores
 * request identity only; byte buffers live only in this ViewModel's lifetime. */
class LibraryTransfersViewModel(app:Application,private val saved:SavedStateHandle):AndroidViewModel(app){
    private val ownerApp=app as InkWeftApplication
    private val repo=ownerApp.libraryContent
    private val mutable=MutableStateFlow(LibraryTransferUi())
    internal val ui=mutable.asStateFlow()
    private var prepared:ContentTransfer.Prepared?=null
    private val keys=listOf("transfer-kind","transfer-id","transfer-dest","transfer-source","transfer-sha")
    private fun value(key:String)=saved.get<String>("transfer-$key").orEmpty()
    private fun hasPending()=value("kind").isNotEmpty()
    private fun remember(kind:String,source:String="",sha:String=""){
        saved["transfer-kind"]=kind;saved["transfer-id"]=UUID.randomUUID().toString()
        saved["transfer-dest"]=UUID.randomUUID().toString();saved["transfer-source"]=source;saved["transfer-sha"]=sha
    }
    private fun forget(){keys.forEach{saved.remove<String>(it)};prepared=null}
    private fun copyCommand()=CopyNotebook(value("id"),value("source"),value("dest"))
    private fun importCommand()=ImportNotebook(value("id"),value("dest"),value("sha"))
    private fun digest()=if(value("kind")=="COPY")copyCommand().digest()else importCommand().digest()
    init{if(hasPending())checkPending()}

    fun requestCopy(noteId:String){
        if(ui.value.busy)return
        if(hasPending()){checkPending();return}
        remember("COPY",source=noteId)
        mutable.value=LibraryTransferUi("COPY",false,"复制当前已保存的可见内容、纸面、封面、文件夹、标签和仍有效的人工索引。副本使用全新身份；摘要卡与脑图请使用资料库备份保存。本次不复制隐藏笔迹、历史回执、收藏、置顶或未保存草稿。")
    }
    fun readUri(uri:Uri){
        if(ui.value.busy)return
        if(hasPending()&&value("kind")!="IMPORT"){checkPending();return}
        mutable.value=LibraryTransferUi("READING",true,"正在校验副本，没有写入资料库…")
        viewModelScope.launch{
            try{
                val parsed=withContext(Dispatchers.IO){
                    val ctx=currentCoroutineContext()
                    checkNotNull(ownerApp.contentResolver.openInputStream(uri)).use{ContentTransfer.read(it){ctx.ensureActive()}}
                }
                if(hasPending())require(value("sha")==parsed.sha256){"IMPORT_CONTENT_CHANGED"}
                else remember("IMPORT",sha=parsed.sha256)
                prepared=parsed
                val count=when(val c=parsed.content){is ContentTransfer.Content.Page->1;is ContentTransfer.Content.Book->c.value.pages.size}
                mutable.value=LibraryTransferUi("IMPORT",message="已校验 ${parsed.byteCount} 字节、$count 页。将导入为新笔记，不覆盖现有资料。副本不是完整资料库备份；不包含账号或云服务配置。")
            }catch(c:CancellationException){throw c}
            catch(_:Exception){mutable.value=LibraryTransferUi("ERROR",message="无法读取副本：文件损坏、不受支持或超出上限。页面上限4MB，整本上限32MB；原资料未覆盖。待核对导入须重新选择同一份文件。",needsFile=hasPending())}
        }
    }
    fun commit(){
        if(ui.value.busy||!hasPending())return
        if(value("kind")=="IMPORT"&&prepared==null){mutable.value=LibraryTransferUi("IMPORT",message="请重新选择上次同一文件，命令身份保持不变。",needsFile=true);return}
        // Freeze identity on the main actor before dispatching any work. SavedStateHandle
        // is not a mutable request object for a background worker to re-read.
        val kind=value("kind")
        val copy=if(kind=="COPY")copyCommand()else null
        val imported=if(kind=="IMPORT")importCommand()else null
        val content=prepared
        mutable.value=ui.value.copy(busy=true,message="正在提交完整副本…")
        viewModelScope.launch{
            try{
                val note=withContext(Dispatchers.IO){if(copy!=null)repo.duplicate(copy)else repo.import(checkNotNull(imported),checkNotNull(content))}
                completed(note)
            }catch(c:CancellationException){throw c}
            catch(_:IllegalArgumentException){mutable.value=LibraryTransferUi("REJECTED",message="未完成复制或导入：源状态、容量或内容校验未通过。原资料保持不变，可取消后重新检查。")}
            catch(_:Exception){mutable.value=LibraryTransferUi("UNKNOWN",message="提交结果待核对，原操作身份已保留。请点核对，不要重复创建另一份副本。")}
        }
    }
    private fun completed(note:Note){forget();mutable.value=LibraryTransferUi("DONE",message="副本已提交至本机资料库。",result=note)}
    fun checkPending(){
        if(ui.value.busy||!hasPending())return
        val id=value("id");val kind=value("kind");val semanticDigest=digest()
        mutable.value=LibraryTransferUi("CHECKING",true,"正在查询原操作回执…")
        viewModelScope.launch{
            try{
                val note=withContext(Dispatchers.IO){repo.lookup(id,kind,semanticDigest)}
                if(note!=null)completed(note)
                else mutable.value=LibraryTransferUi(value("kind"),message="没有找到已提交回执。可按原操作重试，或取消；不会生成新的命令身份。",needsFile=value("kind")=="IMPORT"&&prepared==null)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){mutable.value=LibraryTransferUi("UNKNOWN",message="暂时无法核对存储，操作身份仍保留，请稍后重试。")}
        }
    }
    fun dismiss(){
        if(ui.value.busy)return
        if(ui.value.mode=="UNKNOWN"){checkPending();return}
        forget();mutable.value=LibraryTransferUi()
    }
    fun export(noteId:String){
        if(ui.value.busy)return
        if(hasPending()){checkPending();return}
        mutable.value=LibraryTransferUi("EXPORTING",true,"正在准备已保存内容的一致副本…")
        viewModelScope.launch{
            try{
                val content=withContext(Dispatchers.IO){repo.export(noteId)}
                mutable.value=LibraryTransferUi("EXPORT",message="导出已保存可见内容。${if(content.extension=="iwbook")"整本所有页面"else"无界页面"}，${content.bytes.size} 字节。明文，不含摘要卡/脑图、历史、分类、搜索索引或未保存草稿；不是完整备份。你选择的文件提供方可能是云盘。",export=content)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){mutable.value=LibraryTransferUi("ERROR",message="无法生成内容副本，可能超出容量或存在读取错误。原资料保留；可尝试逐页导出。")}
        }
    }
    fun writeExport(uri:Uri?){
        if(uri==null)return
        val content=ui.value.export
        if(content==null){mutable.value=LibraryTransferUi("ERROR",message="导出快照已不在本次进程中，请重新从笔记菜单导出。原资料未修改。" );return}
        mutable.value=ui.value.copy(busy=true,message="正在写入你选择的位置…")
        viewModelScope.launch{
            try{
                withContext(Dispatchers.IO){checkNotNull(ownerApp.contentResolver.openOutputStream(uri,"wt")).use{it.write(content.bytes);it.flush()}}
                mutable.value=LibraryTransferUi("DONE",message="已完成向所选文件提供方写入。请保管该副本；这不代表云提供方的远端上传已完成。")
            }catch(c:CancellationException){throw c}
            catch(_:Exception){mutable.value=LibraryTransferUi("EXPORT",message="写入未确认；目标可能存在不完整文件，原笔记未修改。请检查目标，或重新选择位置保存。",export=content)}
        }
    }
}

@Composable
internal fun LibraryTransferDialog(vm:LibraryTransfersViewModel,pickImport:()->Unit,open:(Note)->Unit){
    val ui by vm.ui.collectAsStateWithLifecycle()
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"),vm::writeExport)
    if(ui.mode.isBlank())return
    AlertDialog(onDismissRequest=vm::dismiss,modifier=Modifier.testTag("library-transfer-dialog"),
        title={Text(when(ui.mode){"COPY"->"复制笔记";"IMPORT"->"导入内容副本";"EXPORT"->"导出内容副本";"DONE"->"操作完成";else->"资料操作"})},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(ui.message,modifier=Modifier.testTag("library-transfer-message"))
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        confirmButton={when{
            ui.busy->{}
            ui.mode=="COPY"->TextButton(onClick=vm::commit,modifier=Modifier.testTag("confirm-copy")){Text("复制已保存内容")}
            ui.needsFile->TextButton(onClick=pickImport,modifier=Modifier.testTag("reselect-import")){Text("选择原文件")}
            ui.mode=="IMPORT"->TextButton(onClick=vm::commit,modifier=Modifier.testTag("confirm-content-import")){Text("导入为新笔记")}
            ui.mode=="UNKNOWN"->TextButton(onClick=vm::checkPending){Text("核对原操作")}
            ui.mode=="EXPORT"->TextButton(onClick={ui.export?.let{save.launch(ContentTransfer.safeSuggestedName(it.title,it.extension))}},modifier=Modifier.testTag("save-library-export")){Text("选择保存位置")}
            ui.result!=null->TextButton(onClick={ui.result?.let(open);vm.dismiss()},modifier=Modifier.testTag("open-transfer-result")){Text("打开副本")}
            else->TextButton(onClick=vm::dismiss){Text("完成")}
        }},
        dismissButton={if(!ui.busy&&ui.mode!="UNKNOWN")TextButton(onClick=vm::dismiss,modifier=Modifier.testTag("dismiss-library-transfer")){Text(if(ui.mode=="DONE")"留在资料库"else"取消")}})
}
