// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.*

/** Explicit local probe. Raw UI trees, credentials, author text and logcat never become artifacts. */
class PrivacyExitProbe {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()

    @Test fun oneSyntheticCorpusStaysOutOfEveryPublicExit(){
        val ins=InstrumentationRegistry.getInstrumentation()
        require(InstrumentationRegistry.getArguments().getString("privacyProbe")=="dedicated-emulator")
        require(InstrumentationRegistry.getArguments().getString("deviceGuard")=="verified-disposable-emulator")
        require(app.packageName in setOf("org.inkweft.app.a0.insertion","org.inkweft.app.a0.workspace")){"Dedicated debug package required"}
        require(app.backupEngine.connection()==null&&!app.backupEngine.ui.value.busy&&!app.backupEngine.hasPendingUpload()&&!app.backupEngine.hasPendingDeletion()){"Use an idle, disconnected test package"}
        val run=id();val report=JSONObject().put("format","inkweft.privacy-exits.v1").put("runId",run).put("status","FAIL")
        val checks=JSONArray();report.put("checks",checks)
        val corpus=linkedMapOf("password" to "synthetic-password-${id()}","token" to "synthetic-token-${id()}",
            "recovery-key" to EncryptedBackupFile.b64(EncryptedBackupFile.random(32)),"title" to "合成隐私标题-${id()}","body" to "合成隐私正文-${id()}","target-id" to id(),"widget-id" to id())
        fun hits(text:String)=corpus.filterValues{value->text.contains(value)||text.contains(JSONObject.quote(value).removeSurrounding("\""))}.keys
        check(hits(corpus.values.joinToString("\n")).size==corpus.size){"Scanner self-check failed"}
        fun scan(scope:String,text:String){
            val found=hits(text)
            checks.put(JSONObject().put("outlet",scope).put("bytes",text.toByteArray().size).put("hitTypes",JSONArray(found.toList())))
            check(found.isEmpty()){"Synthetic disclosure at $scope; inspect locally without exporting raw text"}
        }
        fun scanUi(scope:String){
            compose.waitForIdle()
            fun text(node:androidx.compose.ui.semantics.SemanticsNode):String=node.config.toString()+"\n"+node.children.joinToString("\n"){text(it)}
            scan(scope,compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().joinToString("\n"){text(it)})
        }
        fun await(action:()->Job?){var job:Job?=null;compose.runOnIdle{job=action()};runBlocking{checkNotNull(job){"Probe operation was not started"}.join()};compose.waitForIdle()}
        val root=File(app.cacheDir,"privacy-$run").apply{check(mkdirs())}
        val source=NoteDatabase.open(app,File(root,"source.db").absolutePath)
        val prefsName="privacy-$run-inkweft-learning"
        var snapshot:LibraryBackupRepository.Snapshot?=null
        var picker:Instrumentation.ActivityMonitor?=null
        var picked:Uri?=null
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val pool=Executors.newSingleThreadExecutor()
        val serverId=id();val issuer=id()
        val authenticatedRequest=java.util.concurrent.atomic.AtomicBoolean();val acceptedPassword=java.util.concurrent.atomic.AtomicBoolean()
        val serving=pool.submit{
            try{while(!server.isClosed)server.accept().use{socket->
                socket.soTimeout=10000
                val input=socket.getInputStream().buffered()
                fun line():String{val bytes=ByteArrayOutputStream();while(true){val b=input.read();check(b>=0&&bytes.size()<16384){"Fixture header limit"};if(b==10)break;if(b!=13)bytes.write(b)};return bytes.toString("UTF-8")}
                val request=line().split(' ');val headers=mutableMapOf<String,String>()
                while(true){val line=line();if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val length=headers["content-length"]?.toInt()?:0;check(length in 0..16384){"Fixture body limit"}
                val body=ByteArray(length);DataInputStream(input).readFully(body)
                val path=request[1];var status=200
                val answer=when{
                    path=="/v1/identity"->JSONObject().put("server",serverId).put("issuer",issuer).toString()
                    path=="/v1/sessions"->{acceptedPassword.set(JSONObject(body.toString(Charsets.UTF_8)).getString("password")==corpus.getValue("password"))
                        JSONObject().put("server",serverId).put("issuer",issuer).put("user",id()).put("token",corpus.getValue("token")).put("expires",System.currentTimeMillis()/1000+3600).toString()}
                    path=="/v1/sessions/current"->"{}"
                    else->{authenticatedRequest.set(headers["authorization"]=="Bearer ${corpus.getValue("token")}");status=403;corpus.values.joinToString("\n")}
                }.toByteArray()
                socket.getOutputStream().apply{write("HTTP/1.1 $status Fixture\r\nContent-Length: ${answer.size}\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n".toByteArray());write(answer);flush()}
            }}catch(e:java.net.SocketException){if(!server.isClosed)error("Synthetic fixture socket failed")}
            catch(_:Throwable){error("Synthetic fixture failed; request contents withheld")}
        }
        try{
            // Only the source DB receives author secrets; the actual application's author DB is untouched.
            runBlocking{
                val n=WorkspaceRepository(source).create(corpus.getValue("title"),false,PaperStyle.BLANK)
                corpus["target-id"]=n.id
                PageObjectRepository(source).save(n.id,0,id(),listOf(PageObject(id(),PageObjectKind.TEXT,30f,30f,500f,120f,text=corpus.getValue("body"))))
                snapshot=LibraryBackupRepository(app,source).snapshot()
            }
            check(hits(snapshot!!.file.readBytes().toString(Charsets.UTF_8)).containsAll(setOf("title","body","target-id"))){"Synthetic author corpus not present in archive"}
            val library=id();val encrypted=File(root,"source.iwbk")
            EncryptedBackupFile.encrypt(snapshot!!.file,encrypted,library,EncryptedBackupFile.unb64(corpus.getValue("recovery-key")))
            scan("encrypted-backup",encrypted.readBytes().toString(Charsets.UTF_8))
            await{app.backupEngine.login("http://127.0.0.1:${server.localPort}","synthetic-probe-user",corpus.getValue("password"))}
            check(acceptedPassword.get()&&app.backupEngine.ui.value.connected){"Synthetic login not reached"}
            await{app.backupEngine.list(library)}
            check(authenticatedRequest.get()&&app.backupEngine.ui.value.message.contains("目标不可用")){"Authenticated backup error not reached"}
            scan("backup-error-state",app.backupEngine.ui.value.toString())
            compose.activity.setContent{InkTheme.Content{BackupSettings{}}};scanUi("backup-error-ui")

            compose.activity.setContent{LibraryBackupHost{Text("合成出口验证")}}
            val vm=ViewModelProvider(compose.activity)[LibraryBackupViewModel::class.java]
            compose.runOnIdle{vm.inspect(Uri.fromFile(snapshot!!.file))}
            compose.waitUntil(30000){vm.ui.value.mode==BackupMode.RESTORE_READY}
            scan("restore-confirmation-state",vm.ui.value.toString());scanUi("restore-confirmation-ui")
            check(runBlocking{app.repository.read(corpus.getValue("target-id"))}==null){"Preview wrote to author database"}
            compose.runOnIdle{vm.close()}
            val invalid=File(root,"invalid.iwbackup").apply{writeText(corpus.values.joinToString("\n"))}
            compose.runOnIdle{vm.inspect(Uri.fromFile(invalid))};compose.waitUntil(15000){vm.ui.value.mode==BackupMode.RESULT}
            scan("restore-error-state",vm.ui.value.toString());scanUi("restore-error-ui");compose.runOnIdle{vm.close()}

            val beforePacks=runBlocking{app.resourcePacks.installed().map{it.pack.hash to it.enabled}}
            val manifest=JSONObject().put("format","inkweft.resource-pack.v1").put("id","example.privacy-probe").put("version",1)
                .put("title",corpus.getValue("title")).put("author",corpus.getValue("body"))
                .put("resources",JSONArray().put(JSONObject().put("id","paper").put("type","paper").put("title",corpus.getValue("title")).put("paper",corpus.getValue("password"))))
            val pack=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(manifest.toString().toByteArray());z.closeEntry()}}.toByteArray()
            picked=checkNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"privacy-$run.iwpack");put(MediaStore.MediaColumns.MIME_TYPE,"application/zip")}))
            app.contentResolver.openOutputStream(picked!!)!!.use{it.write(pack)}
            picker=ins.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addCategory(Intent.CATEGORY_DEFAULT);addDataType("*/*")},Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(picked).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)),true)
            compose.activity.setContent{InkTheme.Content{ResourcePackSettings{}}};compose.waitForIdle()
            compose.onNodeWithText("选择模板包").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("资源包无效、空间不足或操作未完成；已安装版本与笔记保留。").fetchSemanticsNodes().isNotEmpty()}
            scanUi("resource-error-ui")
            check(beforePacks==runBlocking{app.resourcePacks.installed().map{it.pack.hash to it.enabled}}){"Failed resource changed installed versions"}

            val learning=LearningStore(object:ContextWrapper(app){override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences("privacy-$run-$name",mode)})
            val target=StableTargetRef(LearningTargetKind.NOTE,corpus.getValue("target-id"))
            learning.configure(listOf(WidgetInstance(corpus.getValue("widget-id"),"org.inkweft/continue",targets=listOf(target))));learning.visit(target);learning.shortcut(target,true)
            val layout=learning.sharedLayout();scan("anonymous-layout",layout)
            check(JSONObject(layout).getJSONArray("widgets").getJSONObject(0).getJSONArray("targets").length()==0){"Layout retained targets"}
            val bundle=runBlocking{app.diagnostics.bundle()}
            ZipInputStream(bundle.inputStream()).use{z->var ordinal=0;while(z.nextEntry!=null)scan("diagnostic-zip-entry-${ordinal++}",z.readBytes().toString(Charsets.UTF_8));check(ordinal>0)}
            scan("diagnostic-journal",File(app.noBackupFilesDir,"diagnostics-v1.log").readText())
            val log=ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()} -v brief")).use{it.readBytesLimited(8*1024*1024).toString(Charsets.UTF_8)}
            scan("current-process-logcat",log)
            report.put("status","PASS").put("syntheticSecretTypes",JSONArray(corpus.keys.toList())).put("rawPayloadsExported",false)
                .put("logScope","current process logcat buffer; not complete system/kernel history").put("authorDatabaseUnchanged",true)
        }catch(t:Throwable){
            report.put("failureType",t.javaClass.simpleName)
            throw AssertionError("Privacy exit probe failed; see sanitized v47-privacy-exits.json")
        }finally{
            val cleanup=JSONArray()
            fun clean(name:String,action:()->Unit){try{action()}catch(_:Throwable){cleanup.put(name)}}
            clean("picker"){picker?.let(ins::removeMonitor);picked?.let{app.contentResolver.delete(it,null,null)}}
            clean("synthetic-session"){if(app.backupEngine.connection()?.token==corpus["token"]){compose.runOnIdle{app.backupEngine.logout()};compose.waitUntil(10000){!app.backupEngine.ui.value.busy}}}
            clean("fixture-server"){server.close();pool.shutdown();serving.get(12,TimeUnit.SECONDS)}
            clean("source-files"){snapshot?.close();source.close();check(root.canonicalFile.parentFile==app.cacheDir.canonicalFile);check(root.deleteRecursively())}
            clean("layout-preferences"){app.deleteSharedPreferences(prefsName)}
            report.put("cleanupFailures",cleanup);if(cleanup.length()>0)report.put("status","FAIL")
            File(app.getExternalFilesDir(null),"v47-privacy-exits.json").writeText(report.toString(2))
            check(cleanup.length()==0){"Probe cleanup incomplete; see sanitized report"}
        }
    }
}
