package org.inkweft.app

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.app.ui.designsystem.InkTheme
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class BackupReliabilityUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun await(){compose.waitUntil(30000){!app.backupEngine.ui.value.busy};compose.waitForIdle()}
    private fun queue()=JSONObject(File(app.filesDir,"encrypted-backup-jobs/queue.json").readText())
    @Test fun tenVersionsCanBeManagedFromUiWithoutDeletingLocalNotes(){
        runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}};app.workspaceRepository.create("合成配额验收",false,PaperStyle.BLANK)}
        compose.activity.setContent{InkTheme.Content{BackupSettings{}}}
        compose.onNodeWithText("连接服务器").performClick()
        compose.onNodeWithTag("backup-server").performTextInput("http://127.0.0.1:18751")
        compose.onNodeWithText("管理员创建的账号").performTextInput("synthetic-alice")
        compose.onNodeWithText("密码").performTextInput("synthetic-alice-password-123")
        compose.onNodeWithText("连接",substring=false).performClick();await();assertTrue(app.backupEngine.ui.value.connected)
        compose.runOnIdle{compose.activity.window.decorView.clearFocus();compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime());app.backupEngine.sessions.meteredAllowed=true}
        compose.onNodeWithContentDescription("返回设置").performClick();compose.onNodeWithText("设置恢复密钥").performClick()
        val resolver=app.contentResolver;val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"inkweft-synthetic-key.json");put(MediaStore.MediaColumns.MIME_TYPE,"application/json")})!!
        val key=EncryptedBackupFile.b64(EncryptedBackupFile.random(32));val library=app.backupEngine.sessions.localLibrary
        resolver.openOutputStream(uri)!!.use{it.write(JSONObject().put("format","inkweft.recovery-key.v1").put("library",library).put("key",key).toString().toByteArray())}
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addCategory(Intent.CATEGORY_DEFAULT);addDataType("*/*")},Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)),true)
        try{
            compose.onNodeWithText("读取密钥文件").performClick();compose.waitUntil(10000){runCatching{compose.onAllNodesWithText("恢复密钥已读取").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithContentDescription("返回设置").performClick()
            repeat(10){compose.onNodeWithText("立即备份").performClick();compose.waitUntil(30000){!app.backupEngine.ui.value.busy&&runCatching{queue().getString("state")=="PUBLISHED"}.getOrDefault(false)}}
            compose.onNodeWithText("立即备份").performClick();await();assertEquals("PENDING",queue().getString("state"));assertTrue(app.backupEngine.ui.value.message.contains("配额"))
            compose.onNodeWithText("查看版本与恢复").performClick();await();assertEquals(10,app.backupEngine.ui.value.details.size)
            compose.onAllNodesWithText("删除",substring=false).onFirst().performScrollTo().performClick();compose.onNodeWithText("确认删除").performClick();await();assertEquals(9,app.backupEngine.ui.value.details.size)
            compose.onNodeWithContentDescription("返回设置").performClick();compose.onNodeWithText("继续未完成备份").performClick();await();assertEquals("PUBLISHED",queue().getString("state"))
            compose.onNodeWithText("查看版本与恢复").performClick();await();assertEquals(10,app.backupEngine.ui.value.details.size)
            compose.onAllNodesWithText("删除",substring=false).onFirst().performScrollTo().performClick();compose.onNodeWithText("确认删除").performClick();await()
            var job:kotlinx.coroutines.Job?=null;compose.runOnIdle{job=app.backupEngine.create(library,key)};runBlocking{job?.join()};val pending=queue();val identity=app.backupEngine.connection()!!
            runBlocking{BackupTransport(identity).json("PUT",library,"/uploads/${pending.getString("operation")}",pending.getJSONObject("manifest"))}
            compose.onNodeWithText("刷新").performScrollTo().performClick();await()
            compose.onNodeWithText("放弃上传",substring=false).performScrollTo().performClick();compose.onNodeWithText("确认删除").performClick();await()
            assertEquals("DELETED",queue().getString("state"));assertEquals(9,app.backupEngine.ui.value.details.size)
            // The confirmation was opened while pending; another actor publishes before confirming.
            compose.runOnIdle{job=app.backupEngine.create(library,key)};runBlocking{job?.join()}
            val racing=queue();val transport=BackupTransport(identity);val operation=racing.getString("operation")
            runBlocking{transport.json("PUT",library,"/uploads/$operation",racing.getJSONObject("manifest"))}
            compose.onNodeWithText("刷新").performScrollTo().performClick();await()
            compose.onNodeWithText("放弃上传",substring=false).performScrollTo().performClick()
            runBlocking{File(app.filesDir,"encrypted-backup-jobs/${racing.getString("file")}").inputStream().use{input->var ordinal=0;while(true){val bytes=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(bytes.isEmpty())break;transport.request("PUT",library,"/uploads/$operation/chunks/${ordinal++}",bytes=bytes)}};transport.json("POST",library,"/uploads/$operation/publish")}
            compose.onNodeWithText("确认删除").performClick();await();assertTrue(app.backupEngine.ui.value.message.contains("已发布"))
            assertEquals("PUBLISHED",runBlocking{transport.json("GET",library,"/operations/$operation").getString("state")})
            // Model a received server delete with the final acknowledgement lost, then recover through UI.
            val deletion=java.util.UUID.randomUUID().toString()
            runBlocking{transport.json("DELETE",library,"/versions/$operation?operation_id=$deletion")}
            File(app.filesDir,"encrypted-backup-jobs/delete.json").writeText(JSONObject().put("library",library).put("target",operation).put("operation",deletion).put("binding",identity.binding(library)).toString())
            compose.onNodeWithText("刷新").performScrollTo().performClick();await()
            compose.onNodeWithText("继续确认上一次删除").performScrollTo().performClick();await()
            assertFalse(app.backupEngine.hasPendingDeletion());assertEquals("DELETED",queue().getString("state"))
            runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{assertEquals(1,it.notes)}}}
            val bundle=runBlocking{app.diagnostics.bundle()};val entries=java.util.zip.ZipInputStream(bundle.inputStream())
            entries.use{z->while(z.nextEntry!=null){val text=z.readBytes().toString(Charsets.UTF_8);for(secret in listOf(key,identity.token,"synthetic-alice-password-123","合成配额验收"))assertFalse("Diagnostic disclosure",text.contains(secret))}}
            assertFalse(app.learningStore.sharedLayout().contains("合成配额验收"))
        }finally{instrumentation.removeMonitor(monitor);resolver.delete(uri,null,null)}
    }
}
