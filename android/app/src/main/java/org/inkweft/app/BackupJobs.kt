// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.inkweft.data.LibraryBackupRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class BackupTaskState(val connected:Boolean=false,val busy:Boolean=false,val message:String="尚未连接服务器",val versions:List<String> = emptyList(),val restoreNotes:Int?=null)
internal class BackupJobs(application:Application):AndroidViewModel(application){
    private val app=application as InkWeftApplication
    val sessions=BackupSessionStore(app)
    private var identity=sessions.read()
    private val directory=File(app.filesDir,"encrypted-backup-jobs").apply{mkdirs()}
    private val journal=AtomicFile(File(directory,"queue.json"))
    private val cipherFile=File(directory,"upload.iwbk")
    private val state=MutableStateFlow(BackupTaskState(identity!=null,message=if(journal.baseFile.exists())"有可继续的备份任务"else if(identity!=null)"已连接，可创建加密备份"else"尚未连接服务器"));val ui=state.asStateFlow()
    private var running:Job?=null
    private var preview:LibraryBackupRepository.Preview?=null
    fun identityLabel()=identity?.let{"${it.url}\n账号 ${it.user.take(8)} · 设备 ${it.device.take(8)}"}.orEmpty()
    private fun readQueue()=JSONObject(journal.openRead().bufferedReader().use{it.readText()})
    private fun writeQueue(j:JSONObject){val stream=journal.startWrite();try{stream.write(j.toString().toByteArray());journal.finishWrite(stream)}catch(t:Throwable){journal.failWrite(stream);throw t}}
    private fun work(message:String,action:suspend CoroutineScope.()->Unit){if(ui.value.busy)return
        state.value=state.value.copy(busy=true,message=message)
        running=viewModelScope.launch{try{withContext(Dispatchers.IO,action)}catch(c:CancellationException){state.value=state.value.copy(message="任务已暂停，可继续；本地资料保留");throw c}
            catch(e:BackupHttpError){state.value=state.value.copy(message=if(e.status==401)"会话失效，请重新连接同一账号后继续"else"服务器未确认（${e.status}），本地资料保留，可重试同一任务")}
            catch(e:Exception){state.value=state.value.copy(message=when(e.message){"QUEUE_BINDING"->"此任务属于另一账号、服务器或资料库，请切回原目标继续";"KEY_REQUIRED"->"请先保存或输入有效的恢复密钥";"QUEUE_PENDING"->"先继续或完成已有任务，再创建新备份";else->"操作未确认：请检查连接、密钥与剩余空间后重试。原资料保留"})}
            finally{state.value=state.value.copy(busy=false,connected=identity!=null)}}
    }
    fun login(url:String,name:String,password:String)=work("正在连接…"){
        val value=BackupTransport.login(url,name,password,sessions.device);sessions.save(value);identity=value
        preview?.close();preview=null
        state.value=state.value.copy(connected=true,message="已连接。备份目标与恢复密钥独立于登录密码",versions=emptyList(),restoreNotes=null)
    }
    fun logout()=work("正在撤销此设备会话…"){
        identity?.let{BackupTransport(it).logout()};sessions.clear();identity=null;preview?.close();preview=null;state.value=BackupTaskState(message="已退出；本地笔记与未完成任务保留")
    }
    fun pause(){running?.cancel()}
    fun create(library:String,recovery:String)=work("正在生成已确认资料的一致备份…"){
        UUID.fromString(library);val who=checkNotNull(identity);val key=EncryptedBackupFile.unb64(recovery);require(key.size==32){"KEY_REQUIRED"}
        if(journal.baseFile.exists())require(readQueue().optString("state")=="PUBLISHED"){"QUEUE_PENDING"}
        val transport=BackupTransport(who);transport.verifyServer()
        val candidate=File(directory,"candidate-${UUID.randomUUID()}.iwbk")
        try{
            val context=currentCoroutineContext()
            app.libraryBackup.snapshot().use{snapshot->EncryptedBackupFile.encrypt(snapshot.file,candidate,library,key){context.ensureActive()}}
            val hashes=JSONArray();candidate.inputStream().use{input->while(true){context.ensureActive();val bytes=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(bytes.isEmpty())break;hashes.put(EncryptedBackupFile.hex(bytes))}}
            val manifest=JSONObject().put("bytes",candidate.length()).put("chunks",hashes).put("format","inkweft.encrypted-backup.v1")
            val queue=JSONObject().put("binding",who.binding(library)).put("library",library).put("operation",UUID.randomUUID().toString()).put("manifest",manifest).put("state","PENDING")
            // Retire only our previous completed ciphertext; author data lives outside this directory.
            if(cipherFile.exists())check(cipherFile.delete());check(candidate.renameTo(cipherFile));writeQueue(queue)
            state.value=state.value.copy(message="加密快照已就绪，点“继续上传”。书写可继续")
        }finally{key.fill(0);candidate.delete()}
    }
    fun upload()=work("正在核对备份任务…"){
        val queue=readQueue();val library=queue.getString("library");val who=checkNotNull(identity)
        require(queue.getString("binding")==who.binding(library)){"QUEUE_BINDING"}
        val transport=BackupTransport(who);transport.verifyServer();val operation=queue.getString("operation");UUID.fromString(operation)
        val manifest=queue.getJSONObject("manifest");val canonical="{\"bytes\":${manifest.getLong("bytes")},\"chunks\":${manifest.getJSONArray("chunks")},\"format\":\"inkweft.encrypted-backup.v1\"}"
        val fingerprint=EncryptedBackupFile.hex(canonical.toByteArray())
        fun verified(j:JSONObject):Boolean {require(j.getString("digest")==fingerprint&&j.getString("kind")=="UPLOAD");return j.getString("state")=="PUBLISHED"}
        val prior=try{transport.json("GET",library,"/operations/$operation")}catch(e:BackupHttpError){if(e.status!=404)throw e;null}
        if(prior!=null&&verified(prior)){queue.put("state","PUBLISHED");writeQueue(queue);state.value=state.value.copy(message="服务器已确认此备份，无需重复上传");return@work}
        require(cipherFile.length()==manifest.getLong("bytes"));transport.json("PUT",library);transport.json("PUT",library,"/uploads/$operation",manifest)
        val hashes=manifest.getJSONArray("chunks");cipherFile.inputStream().use{input->for(i in 0 until hashes.length()){
            currentCoroutineContext().ensureActive();val bytes=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);require(EncryptedBackupFile.hex(bytes)==hashes.getString(i))
            transport.request("PUT",library,"/uploads/$operation/chunks/$i",bytes=bytes);state.value=state.value.copy(message="正在上传 ${i+1} / ${hashes.length()} 块")
        };require(input.read()==-1)}
        currentCoroutineContext().ensureActive();check(verified(transport.json("POST",library,"/uploads/$operation/publish")))
        queue.put("state","PUBLISHED");writeQueue(queue);state.value=state.value.copy(message="加密备份已发布，可在其他设备用恢复密钥导入")
    }
    fun list(library:String)=work("读取备份版本…"){
        val transport=BackupTransport(checkNotNull(identity));transport.verifyServer();val versions=transport.versions(library)
        state.value=state.value.copy(versions=List(versions.length()){versions.getJSONObject(it).getString("operation")},message="共 ${versions.length()} 个已发布版本")
    }
    fun inspect(library:String,operation:String,recovery:String)=work("正在下载并隔离校验…"){
        UUID.fromString(operation);val transport=BackupTransport(checkNotNull(identity));transport.verifyServer()
        val key=EncryptedBackupFile.unb64(recovery);require(key.size==32){"KEY_REQUIRED"}
        val encrypted=File(directory,"download-${UUID.randomUUID()}.iwbk");val clear=File(directory,"restore-${UUID.randomUUID()}.iwbackup")
        try{val m=transport.json("GET",library,"/versions/$operation");require(m.getString("format")=="inkweft.encrypted-backup.v1");val hashes=m.getJSONArray("chunks");require(hashes.length() in 1..512)
            require(m.getLong("bytes") in 1..512L*EncryptedBackupFile.WIRE_BLOCK);require(directory.usableSpace>m.getLong("bytes")*2+32L*1024*1024)
            encrypted.outputStream().use{out->for(i in 0 until hashes.length()){currentCoroutineContext().ensureActive();val chunk=transport.request("GET",library,"/versions/$operation/chunks/$i");require(EncryptedBackupFile.hex(chunk)==hashes.getString(i));out.write(chunk)}}
            require(encrypted.length()==m.getLong("bytes"));val context=currentCoroutineContext();EncryptedBackupFile.decrypt(encrypted,clear,library,key){context.ensureActive()}
            preview?.close();preview=clear.inputStream().use{app.libraryBackup.inspect(it)}
            state.value=state.value.copy(restoreNotes=preview!!.notes,message="已校验 ${preview!!.notes} 本笔记。确认后导入；身份冲突会停止，不覆盖现有资料")
        }finally{key.fill(0);encrypted.delete();clear.delete()}
    }
    fun restore()=work("正在导入已校验备份…"){
        val p=checkNotNull(preview)
        try{val result=app.libraryBackup.restore(p);state.value=state.value.copy(restoreNotes=null,message=when(result){LibraryBackupRepository.RestoreResult.RESTORED->"恢复完成";LibraryBackupRepository.RestoreResult.ALREADY_PRESENT->"相同资料已存在，无需重复导入";LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT->"资料身份存在冲突，已停止；请使用空白资料库恢复"})}finally{p.close();preview=null}
    }
    fun cancelRestore(){if(!ui.value.busy){preview?.close();preview=null;state.value=state.value.copy(restoreNotes=null,message="已取消恢复，原资料未改变")}}
    override fun onCleared(){
        // Inspection may still be leaving its IO block after cancellation. Close only once it exits.
        val job=running
        job?.cancel()
        if(job==null)preview?.close() else job.invokeOnCompletion{preview?.close();preview=null}
        super.onCleared()
    }
}
