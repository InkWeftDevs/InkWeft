package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.PaperStyle
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Explicit slow-stream experiment proving v2 slices survive the former total deadline. */
class BackupDeadlineProbe{
    @Test fun v2TricklingTransferResumesAfterReconstructionBeyondFormerDeadline(){
        val i=InstrumentationRegistry.getInstrumentation();require(InstrumentationRegistry.getArguments().getString("deadlineProbe")=="dedicated-emulator")
        val app=i.targetContext.applicationContext as InkWeftApplication;val first=app.backupEngine
        fun await(action:()->Job?){var job:Job?=null;i.runOnMainSync{job=action()};runBlocking{job?.join()}}
        fun control(seconds:Int){(java.net.URL("http://127.0.0.1:18751/__fixture__/trickle/$seconds").openConnection() as java.net.HttpURLConnection).let{c->try{c.requestMethod="POST";assertEquals(200,c.responseCode)}finally{c.disconnect()}}}
        runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}};app.workspaceRepository.create("慢流期限合成笔记",false,PaperStyle.DOTS)}
        first.sessions.meteredAllowed=true;await{first.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")}
        val library=UUID.randomUUID().toString();val key=EncryptedBackupFile.random(32);await{first.create(library,EncryptedBackupFile.b64(key))}
        val file=File(app.filesDir,"encrypted-backup-jobs/queue.json");val q=JSONObject(file.readText());val cipher=File(file.parentFile,q.getString("file"))
        // Transport-only synthetic bytes: legal authenticated ciphertext, not a claimed author restore sample.
        val clear=File(app.cacheDir,"deadline-synthetic.bin");clear.outputStream().use{out->val b=ByteArray(1_048_576);java.util.Random(46).nextBytes(b);repeat(6){out.write(b)}}
        check(cipher.delete());EncryptedBackupFile.encrypt(clear,cipher,library,key,format=2);clear.delete();key.fill(0)
        val hashes=JSONArray();cipher.inputStream().use{input->while(true){val b=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(b.isEmpty())break;hashes.put(EncryptedBackupFile.hex(b))}}
        q.put("manifest",JSONObject().put("bytes",cipher.length()).put("chunks",hashes).put("format","inkweft.encrypted-backup.v2").put("plainBytes",6L*1_048_576))
        val start=System.currentTimeMillis();val deadline=start+120_000
        q.put("automatic",true).put("attempts",0).put("deadline",start+30*60_000).put("operationDeadline",deadline);file.writeText(q.toString())
        try{
            control(20);await{first.upload(background=true)};BackupScheduler.cancel(app)
            assertEquals(deadline,JSONObject(file.readText()).getLong("operationDeadline"))
            val second=BackupEngine(app);assertTrue(second.mayResume());await{second.upload(background=true)};BackupScheduler.cancel(app)
            val elapsed=System.currentTimeMillis()-start;assertTrue("Slow slices did not cross the former deadline",elapsed>=120_000)
            assertEquals(deadline,JSONObject(file.readText()).getLong("operationDeadline"));assertTrue(second.mayResume())
            assertEquals("PENDING",JSONObject(file.readText()).getString("state"));assertTrue(second.ui.value.message.contains("本段"))
            i.runOnMainSync{second.pause()};assertFalse(BackupEngine(app).mayResume());assertTrue(JSONObject(file.readText()).getBoolean("paused"))
            control(0);await{second.upload()};assertEquals("PUBLISHED",JSONObject(file.readText()).getString("state"))
            val report=JSONObject().put("elapsedMs",elapsed).put("formerBudgetMs",120_000).put("chunks",hashes.length()).put("reconstructed",true).put("published",true).put("privateDataUsed",false)
            File(app.getExternalFilesDir(null),"v91-deadline.json").writeText(report.toString())
        }finally{control(0);BackupScheduler.cancel(app)}
    }
}
