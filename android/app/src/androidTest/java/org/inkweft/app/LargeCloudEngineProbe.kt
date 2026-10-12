// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.LibraryArchive
import org.inkweft.data.NoteDatabase
import org.inkweft.data.DocumentRepository
import org.inkweft.core.OriginalBytes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Explicit opt-in capacities. The host supplies an archive from the synthetic data bridge. */
class LargeCloudEngineProbe {
    @Test fun transferAndRestoreSyntheticLibraryWithDurableClientState()=runBlocking{
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue("Explicit capacity fixture required",args.containsKey("largeBytes"));val bytes=args.getString("largeBytes")!!.toLong()
        require(bytes in 1..LibraryArchive.MAX_BYTES)
        val phase=args.getString("largePhase")?:"prepare";val maxSlices=args.getString("largeSlices")?.toInt()?:100
        val i=InstrumentationRegistry.getInstrumentation();val app=i.targetContext.applicationContext as InkWeftApplication
        val cut=args.getString("downloadCut");require(cut==null||phase=="restore"&&cut in setOf("download-after-write","download-before-bitmap","download-after-bitmap"))
        val engine=if(cut==null)app.backupEngine else BackupEngine(app){point->if(point==cut){
            val marker=JSONObject().put("cut",cut).put("plainBytes",bytes).put("pid",android.os.Process.myPid())
            java.io.FileOutputStream(File(app.filesDir,"large-download-cut.json")).use{it.write(marker.toString().toByteArray());it.fd.sync()}
            evidence(marker.put("phase","expected-process-exit"));android.os.Process.killProcess(android.os.Process.myPid())
        }}
        suspend fun await(action:()->Job?){var job:Job?=null;i.runOnMainSync{job=action()};job?.join();assertFalse(engine.ui.value.busy)}
        engine.sessions.meteredAllowed=true
        await{engine.login(args.getString("goBase")?:"http://127.0.0.1:18851","synthetic-alice","synthetic-alice-password-123")};assertTrue(engine.ui.value.connected)
        val root=File(app.filesDir,"encrypted-backup-jobs").apply{mkdirs()};val taskFile=File(app.filesDir,"large-cloud-test.json");val queue=File(root,"queue.json")
        val started=System.nanoTime();var task:JSONObject
        if(phase=="prepare"){
            app.libraryBackup.snapshot().use{snapshot->snapshot.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}}
            require(!queue.exists()&&!taskFile.exists())
            val archive=File(app.filesDir,"v91-large/archive-$bytes.iwbackup");require(archive.isFile&&archive.length()==bytes){"Synthetic archive unavailable: ${archive.absolutePath}"}
            val checksum=RandomAccessFile(archive,"r").use{f->assertEquals(0x49574c42,f.readInt());f.seek(bytes-32);ByteArray(32).also(f::readFully).joinToString(""){"%02x".format(it.toInt() and 255)}}
            val key=EncryptedBackupFile.random(32);val library=UUID.randomUUID().toString();val operation=UUID.randomUUID().toString();val cipher=File(root,"${UUID.randomUUID()}.iwbk")
            try{
                val active=currentCoroutineContext();EncryptedBackupFile.encrypt(archive,cipher,library,key,format=2){active.ensureActive()}
                val hashes=JSONArray();cipher.inputStream().use{f->while(true){val data=f.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(data.isEmpty())break;hashes.put(EncryptedBackupFile.hex(data))}}
                val manifest=JSONObject().put("bytes",cipher.length()).put("chunks",hashes).put("format","inkweft.encrypted-backup.v2").put("plainBytes",bytes)
                val who=engine.connection()!!;val q=JSONObject().put("protocol",2).put("library",library).put("binding",who.binding(library)).put("operation",operation).put("file",cipher.name).put("manifest",manifest).put("state","PENDING").put("automatic",false)
                java.io.FileOutputStream(queue).use{it.write(q.toString().toByteArray());it.fd.sync()}
                task=JSONObject().put("bytes",bytes).put("library",library).put("operation",operation).put("key",EncryptedBackupFile.b64(key)).put("archiveChecksum",checksum)
                java.io.FileOutputStream(taskFile).use{it.write(task.toString().toByteArray());it.fd.sync()}
                evidence(JSONObject().put("phase","prepared").put("plainBytes",bytes).put("cipherBytes",cipher.length()).put("chunks",hashes.length()).put("archiveChecksum",checksum).put("seconds",(System.nanoTime()-started)/1e9))
            }finally{key.fill(0)}
            return@runBlocking
        }
        task=JSONObject(taskFile.readText());require(task.getLong("bytes")==bytes)
        val library=task.getString("library");val operation=task.getString("operation");val transport=BackupTransport(engine.connection()!!,2)
        if(phase=="upload"){
            var slices=0;var readBytes=0L
            while(JSONObject(queue.readText()).getString("state")=="PENDING"&&slices<maxSlices){
                val before=try{transport.json("GET",library,"/uploads/$operation").optInt("verified")}catch(e:BackupHttpError){if(e.status!=404)throw e;0}
                await{engine.upload()};BackupScheduler.cancel(app);readBytes+=engine.lastUploadReadBytes;slices++
                val state=JSONObject(queue.readText()).getString("state");require(state=="PUBLISHED"||engine.mayResume()){engine.ui.value.message}
                val inventory=transport.json("GET",library,"/uploads/$operation")
                evidence(JSONObject().put("phase","upload-slice").put("plainBytes",bytes).put("slice",slices).put("received",inventory.getJSONArray("received").length()).put("verified",inventory.optInt("verified")).put("state",state).put("localReadBytes",engine.lastUploadReadBytes).put("message",engine.ui.value.message))
                require(state=="PUBLISHED"||engine.lastUploadReadBytes>0||inventory.optInt("verified")>before){engine.ui.value.message}
            }
            val remote=transport.json("GET",library,"/uploads/$operation");assertEquals(JSONObject(queue.readText()).getString("state"),remote.getString("state"))
            if(maxSlices>1)assertEquals("A full run did not publish", "PUBLISHED",remote.getString("state"))
            evidence(JSONObject().put("phase","upload-finished-or-resumable").put("plainBytes",bytes).put("state",remote.getString("state")).put("readBytesThisProcess",readBytes).put("seconds",(System.nanoTime()-started)/1e9))
            i.runOnMainSync{engine.pause()} // The next explicit phase owns resumption after force-stop.
            return@runBlocking
        }
        if(phase=="restore"){
            var slices=0
            while(engine.ui.value.restoreNotes==null&&slices<maxSlices){
                await{engine.inspect(library,operation,task.getString("key"))};slices++
                if(engine.ui.value.restoreNotes==null){require(engine.ui.value.message.contains("下载进度")){engine.ui.value.message}
                    val journal=JSONObject(File(root,"download.json").readText());evidence(JSONObject().put("phase","download-slice").put("plainBytes",bytes).put("received",journal.getJSONArray("received").length()).put("slice",slices))}
            }
            if(engine.ui.value.restoreNotes==null){assertEquals("A full run did not validate the backup",1,maxSlices);return@runBlocking}
            val notes=engine.ui.value.restoreNotes!!;await{engine.restore()};assertEquals("恢复完成",engine.ui.value.message)
            val db=NoteDatabase.open(app)
            try{
                assertEquals(notes,db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use{it.moveToFirst();it.getInt(0)})
                val sources=db.openHelper.readableDatabase.query("SELECT MIN(notebookId),digest,byteCount FROM document_sources GROUP BY digest,byteCount").use{c->buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getInt(2)))}}
                for(source in sources){val doc=checkNotNull(DocumentRepository(db).read(source.first)).document;DocumentRepository(db).openFile(doc).use{assertEquals(source.second,OriginalBytes.file(it.file,source.third).sha256)}}
            }finally{db.close()}
            evidence(JSONObject().put("phase","native-client-empty-library-restored").put("plainBytes",bytes).put("notes",notes).put("slicesThisProcess",slices).put("archiveChecksum",task.getString("archiveChecksum")).put("seconds",(System.nanoTime()-started)/1e9).put("freeBytes",root.usableSpace))
        }
    }
    private fun evidence(j:JSONObject){InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","LARGE_CLOUD_EVIDENCE $j\n")})}
}
