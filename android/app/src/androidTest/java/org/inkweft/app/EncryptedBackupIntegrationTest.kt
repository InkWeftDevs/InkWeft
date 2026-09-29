package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real loopback reference service; only two isolated synthetic databases are ever read/restored. */
class EncryptedBackupIntegrationTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun durableClientQueueRejectsAccountSwitchAndResumesSameOperationAfterRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as InkWeftApplication
        // This test must refuse a populated app: no existing author library may enter the fixture server.
        runBlocking { app.libraryBackup.snapshot().use { snapshot ->
            snapshot.file.inputStream().use { app.libraryBackup.inspect(it) }.use { require(it.notes==0) }
        } }
        runBlocking { app.workspaceRepository.create("合成队列恢复测试",false,PaperStyle.DOTS) }
        var store=androidx.lifecycle.ViewModelStore()
        fun vm():BackupJobs { var result:BackupJobs?=null;instrumentation.runOnMainSync {
            result=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[BackupJobs::class.java]
        };return result!! }
        fun await(job:BackupJobs, action:()->Unit) {
            instrumentation.runOnMainSync(action)
            val deadline=System.currentTimeMillis()+30000
            while(job.ui.value.busy&&System.currentTimeMillis()<deadline)Thread.sleep(50)
            assertFalse("Task timed out: ${job.ui.value.message}",job.ui.value.busy)
        }
        var job=vm();val library=id();val key=EncryptedBackupFile.b64(EncryptedBackupFile.random(32))
        await(job){job.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")}
        assertTrue(job.ui.value.connected)
        await(job){job.create(library,key)}
        val file=File(app.filesDir,"encrypted-backup-jobs/queue.json")
        val queued=JSONObject(file.readText());val operation=queued.getString("operation")
        assertEquals("PENDING",queued.getString("state"))
        instrumentation.runOnMainSync{store.clear()};store=androidx.lifecycle.ViewModelStore();job=vm()
        assertTrue(job.ui.value.connected)
        await(job){job.login("http://127.0.0.1:18751","synthetic-bob","synthetic-bob-password-456")}
        await(job){job.upload()}
        assertTrue(job.ui.value.message.contains("另一账号"));assertEquals(queued.toString(),JSONObject(file.readText()).toString())
        await(job){job.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")}
        await(job){job.upload()};assertEquals("PUBLISHED",JSONObject(file.readText()).getString("state"))
        // Model the client not receiving the publish acknowledgement; query the receipt on restart.
        file.writeText(queued.toString())
        instrumentation.runOnMainSync{store.clear()};store=androidx.lifecycle.ViewModelStore();job=vm()
        await(job){job.upload()};assertTrue(job.ui.value.message.contains("无需重复上传"))
        await(job){job.list(library)};assertEquals(listOf(operation),job.ui.value.versions)
        await(job){job.inspect(library,operation,key)};assertEquals(1,job.ui.value.restoreNotes)
        await(job){job.restore()};assertTrue(job.ui.value.message.contains("无需重复导入"))
        instrumentation.runOnMainSync{store.clear()}
    }
    @Test fun encryptedBackupRoundTripPreservesAuthorClosureAndRejectsAnotherAccount()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"encrypted-protocol-${id()}").apply{check(mkdirs())}
        val first=NoteDatabase.open(context,File(root,"source.db").absolutePath)
        val second=NoteDatabase.open(context,File(root,"restored.db").absolutePath)
        val source=LibraryBackupRepository(context,first);val restored=LibraryBackupRepository(context,second)
        try{
            val n=WorkspaceRepository(first).create("合成云备份 · 条件概率",false,PaperStyle.DOTS)
            val stroke=InkStroke(id(),InkPen.PENCIL,0xff3159b8.toInt(),4f,InkTool.STYLUS,listOf(InkSample(100f,200f,0),InkSample(200f,220f,50)))
            assertTrue(InkRepository(first).save(CommitInk(id(),n.id,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            TextStyles.initialize(context)
            val natural=beautyObject(listOf(stroke),RecognizedWriting("条件概率",1f,1),BeautyOptions(),false,revision=1)
            PageObjectRepository(first).save(n.id,0,id(),listOf(natural),1)
            val map=id();val card=id();val node=id()
            KnowledgeRepository(first).submit(KnowledgeCommand(id(),n.id,map,0,KnowledgeData.MapDefinition("概率图")))
            StudyRepository(first).submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=card,nodeId=node,title="样本空间",body="P(A | B) = P(A ∩ B) / P(B)",mapId=map))
            val library=id();val recovery=EncryptedBackupFile.random(32);val encrypted=File(root,"source.iwbk")
            source.snapshot().use{EncryptedBackupFile.encrypt(it.file,encrypted,library,recovery)}
            val alice=BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",id())
            val transport=BackupTransport(alice);transport.verifyServer();transport.json("PUT",library)
            val chunks=encrypted.inputStream().use{input->buildList{while(true){val b=input.readBackupChunk(EncryptedBackupFile.WIRE_BLOCK);if(b.isEmpty())break;add(b)}}}
            val manifest=JSONObject().put("bytes",encrypted.length()).put("chunks",JSONArray(chunks.map(EncryptedBackupFile::hex))).put("format","inkweft.encrypted-backup.v1")
            val operation=id();transport.json("PUT",library,"/uploads/$operation",manifest)
            try{transport.json("POST",library,"/uploads/$operation/publish");fail("missing chunks published")}catch(e:BackupHttpError){assertEquals(409,e.status)}
            chunks.forEachIndexed{i,b->transport.request("PUT",library,"/uploads/$operation/chunks/$i",bytes=b)}
            val receipt=transport.json("POST",library,"/uploads/$operation/publish")
            assertEquals("PUBLISHED",receipt.getString("state"));assertEquals(receipt.toString(),transport.json("GET",library,"/operations/$operation").toString())
            val bob=BackupTransport(BackupTransport.login(alice.url,"synthetic-bob","synthetic-bob-password-456",id()))
            try{bob.request("GET",library,"/versions/$operation/chunks/0");fail("foreign attachment readable")}catch(e:BackupHttpError){assertEquals(404,e.status)}
            val other=BackupTransport(BackupTransport.login(alice.url,"synthetic-alice","synthetic-alice-password-123",id()))
            val downloaded=File(root,"download.iwbk");downloaded.outputStream().use{out->chunks.indices.forEach{i->val b=other.request("GET",library,"/versions/$operation/chunks/$i");assertEquals(EncryptedBackupFile.hex(chunks[i]),EncryptedBackupFile.hex(b));out.write(b)}}
            val clear=File(root,"restored.iwbackup");EncryptedBackupFile.decrypt(downloaded,clear,library,recovery)
            clear.inputStream().use{restored.inspect(it)}.use{preview->assertEquals(1,preview.notes);assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restored.restore(preview))}
            assertEquals(n.title,NoteRepository(second).read(n.id)!!.title)
            assertEquals(natural,PageObjectRepository(second).read(n.id).objects.single())
            assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(InkRepository(second).read(n.id).strokes.single().stroke))
            assertEquals(MapGraphAccess(first).read(n.id),MapGraphAccess(second).read(n.id))
            assertThrows(Exception::class.java){EncryptedBackupFile.decrypt(downloaded,File(root,"wrong.iwbackup"),library,EncryptedBackupFile.random(32))}
            assertFalse(File(root,"wrong.iwbackup").exists())
        }finally{first.close();second.close();check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator));root.deleteRecursively()}
    }
}
