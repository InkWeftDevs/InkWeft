// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.inkweft.data.LibraryBackupRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class BackupAuth { DISCONNECTED, CONNECTING, VALID, REAUTHENTICATE }
internal data class BackupVersion(val operation:String,val created:Long,val bytes:Long,val status:String,val format:String)
internal data class BackupTaskState(val connected:Boolean=false,val busy:Boolean=false,val message:String="尚未连接服务器",val versions:List<String> = emptyList(),val restoreNotes:Int?=null,
    val auth:BackupAuth=BackupAuth.DISCONNECTED,val details:List<BackupVersion> = emptyList(),val checked:Set<String> = emptySet())
/** UI facade; its disposal cannot cancel the application-owned, durable executor. */
internal class BackupJobs(application:Application):AndroidViewModel(application){
    private val engine=(application as InkWeftApplication).backupEngine
    val sessions get()=engine.sessions
    val ui get()=engine.ui
    fun identityLabel()=engine.identityLabel()
    fun login(url:String,name:String,password:String)=engine.login(url,name,password)
    fun logout()=engine.logout()
    fun pause()=engine.pause()
    fun create(library:String,key:String)=engine.create(library,key)
    fun upload()=engine.upload()
    fun backup(library:String,key:String)=engine.backup(library,key)
    fun list(library:String)=engine.list(library)
    fun inspect(library:String,operation:String,key:String)=engine.inspect(library,operation,key)
    fun restore()=engine.restore()
    fun cancelRestore()=engine.cancelRestore()
    fun delete(library:String,operation:String,pendingOnly:Boolean=false)=engine.delete(library,operation,pendingOnly)
    fun retryDeletion()=engine.retryDeletion()
    fun hasPendingDeletion()=engine.hasPendingDeletion()
    fun hasPendingUpload()=engine.hasPendingUpload()
    fun abandonLocal()=engine.abandonLocal()
    fun connection()=engine.connection()
}

internal class BackupEngine(private val app:InkWeftApplication,private val fault:(String)->Unit={}){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val sessions=BackupSessionStore(app)
    private var identity=sessions.read()
    private val directory=File(app.filesDir,"encrypted-backup-jobs").apply{mkdirs()}
    private val journal=AtomicFile(File(directory,"queue.json"))
    private fun cipherFile(queue:JSONObject):File {
        val name=queue.optString("file","upload.iwbk")
        require(name=="upload.iwbk" || name.matches(Regex("[0-9a-f-]{36}\\.iwbk")))
        return File(directory,name)
    }
    @Volatile private var manuallyPaused=false
    private val deletion=AtomicFile(File(directory,"delete.json"))
    private val state=MutableStateFlow(BackupTaskState(identity!=null&&!sessions.expired,auth=if(identity==null)BackupAuth.DISCONNECTED else if(sessions.expired)BackupAuth.REAUTHENTICATE else BackupAuth.VALID,message=if(journal.baseFile.exists())"有可继续的备份任务"else if(identity!=null)"已连接，可创建加密备份"else"尚未连接服务器"));val ui=state.asStateFlow()
    private var running:Job?=null
    private var preview:LibraryBackupRepository.Preview?=null
    fun identityLabel()=identity?.let{"${it.url}\n${it.name.ifBlank{it.user.take(8)}}"}.orEmpty()
    fun connection()=identity
    fun mayResume()=!manuallyPaused && identity!=null && !sessions.expired && runCatching{val q=readQueue();q.optBoolean("automatic")&&!q.optBoolean("paused")&&q.optLong("deadline")>System.currentTimeMillis()&&q.optLong("operationDeadline")>System.currentTimeMillis()&&q.optInt("attempts")<5&&q.getString("binding")==identity!!.binding(q.getString("library"))}.getOrDefault(false)
    @Synchronized private fun readQueue()=JSONObject(journal.openRead().bufferedReader().use{it.readText()})
    @Synchronized private fun writeQueue(j:JSONObject){if(manuallyPaused)j.put("automatic",false);val stream=journal.startWrite();try{stream.write(j.toString().toByteArray());journal.finishWrite(stream)}catch(t:Throwable){journal.failWrite(stream);throw t}}
    private fun work(message:String,action:suspend CoroutineScope.()->Unit):Job?{if(ui.value.busy)return null
        state.value=state.value.copy(busy=true,message=message)
        running=scope.launch{try{withTimeout(120_000){withContext(Dispatchers.IO){BackgroundBudget.memory(4L*1024*1024){action()}}}}catch(c:CancellationException){state.value=state.value.copy(message=when{c is TimeoutCancellationException->"本次任务已超时，待办与本地资料保留，可手动继续";c.message=="SYSTEM_STOP"->"系统暂缓任务，条件恢复后继续；本地资料保留";c.message=="SLICE_COMPLETE"->"本段执行已结束，后台按原期限继续";else->"任务已暂停，可继续；本地资料保留"});throw c}
            catch(e:BackupHttpError){
                if(e.status==401){sessions.markExpired();state.value=state.value.copy(auth=BackupAuth.REAUTHENTICATE,connected=false)}
                if(e.status==429){BackupScheduler.cancel(app);runCatching{readQueue().put("automatic",false).put("notBefore",(e.retryAfterMillis?:30_000).let{wait->if(wait>Long.MAX_VALUE-System.currentTimeMillis())Long.MAX_VALUE else System.currentTimeMillis()+wait}).also(::writeQueue)}}
                if(e.status in setOf(401,403,404,409,507)){BackupScheduler.cancel(app);runCatching{readQueue().put("automatic",false).also(::writeQueue)}}
                state.value=state.value.copy(message=when(e.status){401->"会话失效，请重新连接同一账号后继续";403,404->"目标不可用，请核对原服务器与账号";409->"任务内容或状态冲突，请查看版本后处理";429->"服务器繁忙，已有限重试，请稍后继续";507->"配额或空间不足，请管理旧版本或释放服务器空间";else->"服务器未确认（${e.status}），保留原任务"})
            }
            catch(e:javax.net.ssl.SSLException){state.value=state.value.copy(message="TLS 证书校验失败，请核对服务器证书与地址；待办保留");BackupScheduler.cancel(app);runCatching{readQueue().put("automatic",false).also(::writeQueue)}}
            catch(e:Exception){state.value=state.value.copy(message=when(e.message){"INPUT_UNSEALED"->"仍有未完成书写，请抬笔并等待保存；中断的笔迹请先打开原笔记恢复或确认，再创建备份";"QUEUE_BINDING"->"此任务属于另一账号、服务器或资料库，请切回原目标继续";"KEY_REQUIRED"->"请先保存或输入有效的恢复密钥";"QUEUE_PENDING"->"先继续或完成已有任务，再创建新备份";"DELETE_PENDING"->"先继续确认上一次删除，再删除其他版本";"TASK_LIMIT"->"自动续传已达到时限或重试上限，请手动继续";else->"操作未确认：请检查连接、密钥与剩余空间后重试。原资料保留"})}
            finally{state.value=state.value.copy(busy=false,connected=identity!=null&&state.value.auth==BackupAuth.VALID);if(mayResume())BackupScheduler.schedule(app)}}
        return running
    }
    fun login(url:String,name:String,password:String)=work("正在连接…"){
        state.value=state.value.copy(auth=BackupAuth.CONNECTING)
        val value=try{BackupTransport.login(url,name,password,sessions.device)}catch(e:Exception){state.value=state.value.copy(auth=if(identity==null)BackupAuth.DISCONNECTED else BackupAuth.REAUTHENTICATE);throw e};sessions.save(value);identity=value
        preview?.close();preview=null
        state.value=state.value.copy(connected=true,auth=BackupAuth.VALID,message="已连接。备份目标与恢复密钥独立于登录密码",versions=emptyList(),details=emptyList(),checked=emptySet(),restoreNotes=null)
    }
    fun logout(){
        pause();val old=identity
        sessions.clear();identity=null
        state.value=BackupTaskState(busy=true,message="已断开本机，正在确认远端撤销…")
        val previous=running
        running=scope.launch{
            previous?.cancelAndJoin();state.value=state.value.copy(busy=true);preview?.close();preview=null
            val revoked=try{withTimeout(6000){old?.let{BackupTransport(it).logout()}};true}catch(_:Exception){false}
            state.value=BackupTaskState(message=if(revoked)"已断开本机并确认撤销此会话；笔记和待办保留" else "已断开本机，远端撤销未确认；笔记和待办保留")
        }
    }
    fun pause(){
        manuallyPaused=true
        BackupScheduler.cancel(app)
        if(journal.baseFile.exists())runCatching{readQueue().put("automatic",false).put("paused",true).also(::writeQueue)}
        running?.cancel()
    }
    fun create(library:String,recovery:String)=work("正在生成已确认资料的一致备份…"){
        require(!BackgroundBudget.writing()&&!app.inkRepository.hasUnsealedInput()){"INPUT_UNSEALED"}
        BackgroundBudget.await(app)
        UUID.fromString(library);val who=checkNotNull(identity);val key=EncryptedBackupFile.unb64(recovery);require(key.size==32){"KEY_REQUIRED"}
        if(journal.baseFile.exists())require(readQueue().optString("state") in listOf("PUBLISHED","DELETED")){"QUEUE_PENDING"}
        val transport=BackupTransport(who);transport.verifyServer()
        val retained=if(journal.baseFile.exists())cipherFile(readQueue()).name else null
        directory.listFiles().orEmpty().filter{it.name!=retained&&it.name.matches(Regex("[0-9a-f-]{36}\\.iwbk"))}.forEach{it.delete()}
        val candidate=File(directory,"${UUID.randomUUID()}.iwbk");var registered=false
        try{
            val context=currentCoroutineContext()
            app.libraryBackup.snapshot().use{snapshot->EncryptedBackupFile.encrypt(snapshot.file,candidate,library,key){context.ensureActive()}}
            fault("after-cipher");val hashes=JSONArray();candidate.inputStream().use{input->while(true){context.ensureActive();val bytes=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(bytes.isEmpty())break;hashes.put(EncryptedBackupFile.hex(bytes))}}
            val manifest=JSONObject().put("bytes",candidate.length()).put("chunks",hashes).put("format","inkweft.encrypted-backup.v1")
            val queue=JSONObject().put("binding",who.binding(library)).put("library",library).put("operation",UUID.randomUUID().toString()).put("manifest",manifest).put("state","PENDING").put("file",candidate.name).put("automatic",false)
            // Immutable generation first, atomic pointer second. A crash leaves only an unreferenced file.
            java.io.RandomAccessFile(candidate,"rw").use{it.fd.sync()};fault("after-fsync")
            val previous=if(journal.baseFile.exists())cipherFile(readQueue())else null
            fault("before-pointer");writeQueue(queue);registered=true;fault("after-pointer")
            if(previous!=candidate)previous?.delete();fault("after-retire")
            state.value=state.value.copy(message="加密快照已就绪，点“继续上传”。书写可继续")
        }finally{key.fill(0);if(!registered)candidate.delete()}
    }
    fun backup(library:String,key:String)=scope.launch {
        val before=runCatching{readQueue().getString("operation")}.getOrNull()
        val preparation=create(library,key)?:return@launch
        preparation.join();if(preparation.isCancelled)return@launch
        val after=runCatching{readQueue().getString("operation")}.getOrNull()
        if(after!=null&&after!=before)upload()?.join()
    }
    fun upload(background:Boolean=false):Job? {
        if(ui.value.busy)return null
        if(!background)manuallyPaused=false
        return work("正在核对备份任务…"){
        val started=android.os.SystemClock.elapsedRealtime()
        val queue=readQueue()
        if(queue.optLong("notBefore")>System.currentTimeMillis()){state.value=state.value.copy(message="服务器要求稍后重试，原任务保留；请等待后再继续");return@work}
        if(background){
            require(mayResume()){"TASK_LIMIT"}
            queue.put("attempts",queue.optInt("attempts")+1);writeQueue(queue)
        }
        val library=queue.getString("library");val who=checkNotNull(identity)
        require(queue.getString("binding")==who.binding(library)){"QUEUE_BINDING"}
        if(!background){queue.put("automatic",true).put("paused",false).put("deadline",System.currentTimeMillis()+30*60_000).put("operationDeadline",System.currentTimeMillis()+120_000).put("attempts",0);writeQueue(queue);BackupScheduler.schedule(app)}
        if(!sessions.meteredAllowed&&app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered){
            state.value=state.value.copy(message="等待非计量网络，可在连接设置允许计量网络");return@work
        }
        if(app.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..14){
            state.value=state.value.copy(message="电量较低，备份等待充电后继续");return@work
        }
        val remaining=queue.optLong("operationDeadline")-System.currentTimeMillis();require(remaining>0){"TASK_LIMIT"}
        withTimeout(remaining){
        val transport=BackupTransport(who);transport.verifyServer();val operation=queue.getString("operation");UUID.fromString(operation)
        val manifest=queue.getJSONObject("manifest");val canonical="{\"bytes\":${manifest.getLong("bytes")},\"chunks\":${manifest.getJSONArray("chunks")},\"format\":\"inkweft.encrypted-backup.v1\"}"
        val fingerprint=EncryptedBackupFile.hex(canonical.toByteArray())
        fun verified(j:JSONObject):Boolean {require(j.getString("digest")==fingerprint&&j.getString("kind")=="UPLOAD");return j.getString("state")=="PUBLISHED"}
        val prior=try{transport.json("GET",library,"/operations/$operation")}catch(e:BackupHttpError){if(e.status!=404)throw e;null}
        if(prior!=null&&verified(prior)){queue.put("state","PUBLISHED").put("automatic",false);writeQueue(queue);BackupScheduler.cancel(app);state.value=state.value.copy(message="服务器已确认此备份，无需重复上传");return@withTimeout}
        val cipherFile=cipherFile(queue);require(cipherFile.length()==manifest.getLong("bytes"));transport.json("PUT",library);transport.json("PUT",library,"/uploads/$operation",manifest)
        val remote=transport.json("GET",library,"/uploads/$operation")
        verified(remote);require(remote.getString("state")=="PENDING"){"QUEUE_STATE"}
        val received=remote.getJSONArray("received");val present=(0 until received.length()).map{received.getInt(it)}.toSet()
        val hashes=manifest.getJSONArray("chunks");require(present.all{it in 0 until hashes.length()})
        cipherFile.inputStream().use{input->for(i in 0 until hashes.length()){
            currentCoroutineContext().ensureActive();val bytes=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);require(EncryptedBackupFile.hex(bytes)==hashes.getString(i))
            if(android.os.SystemClock.elapsedRealtime()-started>55_000)throw CancellationException("SLICE_COMPLETE")
            BackgroundBudget.await(app)
            if(i !in present)transport.request("PUT",library,"/uploads/$operation/chunks/$i",bytes=bytes);state.value=state.value.copy(message="正在上传 ${i+1} / ${hashes.length()} 块")
        };require(input.read()==-1)}
        currentCoroutineContext().ensureActive();check(verified(transport.json("POST",library,"/uploads/$operation/publish")))
        queue.put("state","PUBLISHED").put("automatic",false);writeQueue(queue);BackupScheduler.cancel(app);state.value=state.value.copy(message="加密备份已发布，可在其他设备用恢复密钥导入")
        }
    }
    }
    fun list(library:String)=work("读取备份版本…"){
        val transport=BackupTransport(checkNotNull(identity));transport.verifyServer();val versions=transport.versions(library)
        val details=List(versions.length()){val v=versions.getJSONObject(it);BackupVersion(v.getString("operation"),v.getLong("created"),v.getLong("bytes"),v.getString("state"),v.getString("format"))}
        state.value=state.value.copy(versions=details.map{it.operation},details=details,message="共 ${versions.length()} 个备份／未完成上传")
    }
    fun inspect(library:String,operation:String,recovery:String)=work("正在下载并隔离校验…"){
        preview?.close();preview=null;state.value=state.value.copy(restoreNotes=null)
        UUID.fromString(operation);val transport=BackupTransport(checkNotNull(identity));transport.verifyServer()
        val key=EncryptedBackupFile.unb64(recovery);require(key.size==32){"KEY_REQUIRED"}
        val encrypted=File(directory,"download-${UUID.randomUUID()}.iwbk");val clear=File(directory,"restore-${UUID.randomUUID()}.iwbackup")
        try{val m=transport.json("GET",library,"/versions/$operation");require(m.getString("format")=="inkweft.encrypted-backup.v1");val hashes=m.getJSONArray("chunks");require(hashes.length() in 1..512)
            require(m.getLong("bytes") in 1..512L*EncryptedBackupFile.WIRE_BLOCK);require(directory.usableSpace>m.getLong("bytes")*2+32L*1024*1024)
            encrypted.outputStream().use{out->for(i in 0 until hashes.length()){currentCoroutineContext().ensureActive();val chunk=transport.request("GET",library,"/versions/$operation/chunks/$i");require(EncryptedBackupFile.hex(chunk)==hashes.getString(i));out.write(chunk)}}
            require(encrypted.length()==m.getLong("bytes"));val context=currentCoroutineContext();EncryptedBackupFile.decrypt(encrypted,clear,library,key){context.ensureActive()}
            preview?.close();preview=clear.inputStream().use{app.libraryBackup.inspect(it)}
            state.value=state.value.copy(checked=state.value.checked+operation,restoreNotes=preview!!.notes,message="已校验 ${preview!!.notes} 本笔记。确认后导入；身份冲突会停止，不覆盖现有资料")
        }finally{key.fill(0);encrypted.delete();clear.delete()}
    }
    fun restore()=work("正在导入已校验备份…"){
        val p=checkNotNull(preview)
        try{val result=app.libraryBackup.restore(p);state.value=state.value.copy(restoreNotes=null,message=when(result){LibraryBackupRepository.RestoreResult.RESTORED->"恢复完成";LibraryBackupRepository.RestoreResult.ALREADY_PRESENT->"相同资料已存在，无需重复导入";LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT->"资料身份存在冲突，已停止；请使用空白资料库恢复"})}finally{p.close();preview=null;state.value=state.value.copy(restoreNotes=null)}
    }
    fun cancelRestore(){if(!ui.value.busy){preview?.close();preview=null;state.value=state.value.copy(restoreNotes=null,message="已取消恢复，原资料未改变")}}
    fun hasPendingUpload()=runCatching{readQueue().getString("state")=="PENDING"}.getOrDefault(false)
    fun abandonLocal()=work("核对未完成备份是否已到达服务器…"){
        val q=readQueue();val library=q.getString("library");val who=checkNotNull(identity)
        require(q.getString("binding")==who.binding(library)){"QUEUE_BINDING"}
        val transport=BackupTransport(who);transport.verifyServer()
        val remote=try{transport.json("GET",library,"/operations/${q.getString("operation")}")}catch(e:BackupHttpError){if(e.status!=404)throw e;null}
        if(remote==null){q.put("state","DELETED").put("automatic",false);writeQueue(q);cipherFile(q).delete();BackupScheduler.cancel(app);state.value=state.value.copy(message="未上传的任务已放弃，本地笔记保留")}
        else state.value=state.value.copy(message="服务器已有此任务，请在版本列表核对状态后继续或删除")
    }
    fun hasPendingDeletion()=deletion.baseFile.exists()
    fun retryDeletion(){if(deletion.baseFile.exists()){val j=JSONObject(deletion.openRead().bufferedReader().use{it.readText()});delete(j.getString("library"),j.getString("target"))}}
    fun delete(library:String,operation:String,pendingOnly:Boolean=false)=work("正在核对并删除云端版本…"){
        UUID.fromString(library);UUID.fromString(operation);val who=checkNotNull(identity);val transport=BackupTransport(who);transport.verifyServer()
        if(pendingOnly&&!deletion.baseFile.exists()){
            val latest=transport.json("GET",library,"/operations/$operation")
            if(latest.getString("state")=="PUBLISHED"){state.value=state.value.copy(message="此上传已发布，请刷新版本后重新确认删除");return@work}
        }
        val command=if(deletion.baseFile.exists())JSONObject(deletion.openRead().bufferedReader().use{it.readText()})else
            JSONObject().put("library",library).put("target",operation).put("pendingOnly",pendingOnly).put("operation",UUID.randomUUID().toString()).put("binding",who.binding(library)).also{j->
                val out=deletion.startWrite();try{out.write(j.toString().toByteArray());deletion.finishWrite(out)}catch(t:Throwable){deletion.failWrite(out);throw t}}
        require(command.getString("binding")==who.binding(library)){"QUEUE_BINDING"}
        require(command.getString("target")==operation){"DELETE_PENDING"}
        val op=command.getString("operation");val pending=command.optBoolean("pendingOnly");val expected=EncryptedBackupFile.hex((if(pending)"{\"expected\":\"PENDING\",\"target\":\"$operation\"}" else "{\"target\":\"$operation\"}").toByteArray())
        val receipt=try{transport.json("GET",library,"/operations/$op")}catch(e:BackupHttpError){if(e.status!=404)throw e;null}
            ?:try{transport.json("DELETE",library,"/versions/$operation?operation_id=$op"+if(pending)"&expected_state=PENDING"else"")}catch(e:BackupHttpError){
                if(e.status==409&&pending){deletion.delete();state.value=state.value.copy(message="上传状态已改变，请刷新版本后重新确认删除");return@work};throw e}
        require(receipt.getString("kind")=="DELETE" && receipt.getString("state")=="DELETED" && receipt.getString("digest")==expected)
        // Server serializes delete and publish. Only a confirmed delete retires local ciphertext.
        if(journal.baseFile.exists()){val q=readQueue();if(q.getString("binding")==who.binding(library)&&q.getString("operation")==operation){
            q.put("state","DELETED").put("automatic",false);writeQueue(q);cipherFile(q).delete();BackupScheduler.cancel(app)}}
        deletion.delete();state.value=state.value.copy(versions=state.value.versions-operation,details=state.value.details.filterNot{it.operation==operation},message="云端版本已删除，本地笔记与恢复密钥保留")
    }
}
