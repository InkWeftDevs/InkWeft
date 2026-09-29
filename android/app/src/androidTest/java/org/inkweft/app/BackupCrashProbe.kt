package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.PaperStyle
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Invoked only by the dedicated-emulator harness; prepare deliberately terminates its own process. */
class BackupCrashProbe {
    @Test fun generationCutKeepsACompletePointer(){
        val i=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        require(args.getString("crashProbe")=="dedicated-emulator")
        val app=i.targetContext.applicationContext as InkWeftApplication
        val marker=File(app.filesDir,"crash-probe.json");val queue=File(app.filesDir,"encrypted-backup-jobs/queue.json")
        fun await(action:()->Job?){var j:Job?=null;i.runOnMainSync{j=action()};runBlocking{j?.join()}}
        if(args.getString("phase")=="prepare"){
            require(!marker.exists()&&!queue.exists())
            runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}};app.workspaceRepository.create("合成代际终止验收",false,PaperStyle.BLANK)}
            val engine=app.backupEngine;engine.sessions.meteredAllowed=true
            await{engine.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")};assertTrue(engine.ui.value.connected)
            val library=UUID.randomUUID().toString();val key=EncryptedBackupFile.b64(EncryptedBackupFile.random(32))
            await{engine.backup(library,key)};assertEquals("PUBLISHED",JSONObject(queue.readText()).getString("state"))
            marker.writeText(JSONObject().put("cut",args.getString("cut")).put("old",JSONObject(queue.readText())).toString())
            val faulty=BackupEngine(app){point->if(point==args.getString("cut"))android.os.Process.killProcess(android.os.Process.myPid())}
            await{faulty.create(library,key)};fail("Expected process termination at selected cut")
        }else{
            val m=JSONObject(marker.readText());val current=JSONObject(queue.readText());val cut=m.getString("cut")
            if(cut in listOf("after-cipher","after-fsync","before-pointer"))assertEquals(m.getJSONObject("old").getString("operation"),current.getString("operation"))
            else assertNotEquals(m.getJSONObject("old").getString("operation"),current.getString("operation"))
            val cipher=File(queue.parentFile,current.getString("file"));val manifest=current.getJSONObject("manifest");assertEquals(manifest.getLong("bytes"),cipher.length())
            cipher.inputStream().use{input->val hashes=manifest.getJSONArray("chunks");repeat(hashes.length()){assertEquals(hashes.getString(it),EncryptedBackupFile.hex(input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK)))};assertEquals(-1,input.read())}
            await{app.backupEngine.upload()};assertEquals("PUBLISHED",JSONObject(queue.readText()).getString("state"));assertEquals(current.getString("operation"),JSONObject(queue.readText()).getString("operation"))
            runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{assertEquals(1,it.notes)}}}
        }
    }
}
