package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.PaperStyle
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Explicit ~120-second real slow-stream experiment, not part of ordinary per-change UI discovery. */
class BackupDeadlineProbe{
    @Test fun tricklingTransferCannotRenewItsDeadlineAfterReconstruction(){
        val i=InstrumentationRegistry.getInstrumentation();require(InstrumentationRegistry.getArguments().getString("deadlineProbe")=="dedicated-emulator")
        val app=i.targetContext.applicationContext as InkWeftApplication;val first=app.backupEngine
        fun await(action:()->Job?){var job:Job?=null;i.runOnMainSync{job=action()};runBlocking{job?.join()}}
        fun control(seconds:Int){(java.net.URL("http://127.0.0.1:18751/__fixture__/trickle/$seconds").openConnection() as java.net.HttpURLConnection).let{c->try{c.requestMethod="POST";assertEquals(200,c.responseCode)}finally{c.disconnect()}}}
        runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}};app.workspaceRepository.create("慢流期限合成笔记",false,PaperStyle.DOTS)}
        first.sessions.meteredAllowed=true;await{first.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")}
        val library=UUID.randomUUID().toString();val key=EncryptedBackupFile.random(32);await{first.create(library,EncryptedBackupFile.b64(key))}
        val file=File(app.filesDir,"encrypted-backup-jobs/queue.json");val q=JSONObject(file.readText());val cipher=File(file.parentFile,q.getString("file"))
        // Transport-only synthetic bytes: legal authenticated ciphertext, not a claimed author restore sample.
        val clear=File(app.cacheDir,"deadline-synthetic.bin");clear.outputStream().use{out->val b=ByteArray(1_048_576);java.util.Random(46).nextBytes(b);repeat(4){out.write(b)}}
        check(cipher.delete());EncryptedBackupFile.encrypt(clear,cipher,library,key);clear.delete();key.fill(0)
        val hashes=JSONArray();cipher.inputStream().use{input->while(true){val b=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(b.isEmpty())break;hashes.put(EncryptedBackupFile.hex(b))}}
        q.put("manifest",JSONObject().put("bytes",cipher.length()).put("chunks",hashes).put("format","inkweft.encrypted-backup.v1"))
        val start=System.currentTimeMillis();val deadline=start+120_000
        q.put("automatic",true).put("attempts",0).put("deadline",start+30*60_000).put("operationDeadline",deadline);file.writeText(q.toString())
        try{
            control(20);await{first.upload(background=true)};BackupScheduler.cancel(app)
            assertEquals(deadline,JSONObject(file.readText()).getLong("operationDeadline"))
            val second=BackupEngine(app);assertTrue(second.mayResume());await{second.upload(background=true)};BackupScheduler.cancel(app)
            val elapsed=System.currentTimeMillis()-start;assertTrue("Persistent total deadline overrun",elapsed in 118_000..126_000)
            assertEquals(deadline,JSONObject(file.readText()).getLong("operationDeadline"));assertFalse(second.mayResume())
            assertEquals("PENDING",JSONObject(file.readText()).getString("state"));assertTrue(second.ui.value.message.contains("超时"))
            val report=JSONObject().put("elapsedMs",elapsed).put("budgetMs",120_000).put("chunks",hashes.length()).put("reconstructed",true).put("expired",true).put("privateDataUsed",false)
            File(app.getExternalFilesDir(null),"v46-deadline.json").writeText(report.toString())
            i.runOnMainSync{second.pause()};assertFalse(BackupEngine(app).mayResume());assertTrue(JSONObject(file.readText()).getBoolean("paused"))
        }finally{control(0);BackupScheduler.cancel(app)}
    }
}
