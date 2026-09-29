package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Real encodings and real Room transactions; no production database is accepted by ShadowReplica. */
class ShadowSyncTest {
    private fun id()=UUID.randomUUID().toString()
    private suspend fun sync(r:ShadowReplica,t:BackupTransport,library:String){
        val pending=r.outgoing()
        pending.forEach{t.json("POST",library,"/shadow/events",JSONObject(it))}
        do{val batch=t.json("GET",library,"/shadow/events?cursor=${r.cursor()}");val a=batch.getJSONArray("envelopes")
            r.receive(batch.getLong("cursor"),List(a.length()){a.getString(it)})
        }while(batch.getLong("cursor")<batch.getLong("latest"))
        pending.forEach{r.acknowledged(JSONObject(it).getString("operation"))}
    }
    @Test fun damagedOrIncompleteRealPayloadNeverAdvancesReceiver()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val scope=listOf("test-server","test-issuer","test-account",id());val keys=mapOf(1 to EncryptedBackupFile.random(32))
        val a=ShadowReplica.open(context,scope,id(),keys);val b=ShadowReplica.open(context,scope,id(),keys);val missing=ShadowReplica.open(context,scope,id(),emptyMap())
        try{
            a.author{db->
                val pdf=android.graphics.pdf.PdfDocument();val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,1414,1).create());page.canvas.drawColor(android.graphics.Color.WHITE);pdf.finishPage(page)
                val bytes=java.io.ByteArrayOutputStream().also{pdf.writeTo(it)}.toByteArray();pdf.close()
                ResourceTemplates(db).instantiate("c".repeat(64),"真实PDF格式",PaperStyle.BLANK,PdfPageSource(PdfDocumentSource(bytes,1),0),null)
            }
            val original=a.outgoing().single();val before=b.authorFingerprint()
            suspend fun rejects(replica:ShadowReplica,text:String){try{replica.receive(1,listOf(text));fail("Invalid batch accepted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){};assertEquals(0L,replica.cursor())}
            rejects(missing,original)
            rejects(b,JSONObject(original).put("format","unknown.v9").toString())
            rejects(b,JSONObject(original).put("schema","f".repeat(64)).toString())
            rejects(b,JSONObject(original).put("scope",org.json.JSONArray(listOf("other","issuer","account","library"))).toString())
            val e=JSONObject(original);val header=JSONObject(original).apply{remove("nonce");remove("ciphertext")};val nonce=java.util.Base64.getDecoder().decode(e.getString("nonce"))
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.DECRYPT_MODE,javax.crypto.spec.SecretKeySpec(keys.getValue(1),"AES"),javax.crypto.spec.GCMParameterSpec(128,nonce));cipher.updateAAD(header.toString().toByteArray())
            val changes=org.json.JSONArray(cipher.doFinal(java.util.Base64.getDecoder().decode(e.getString("ciphertext"))).toString(Charsets.UTF_8))
            val cut=org.json.JSONArray();repeat(changes.length()){i->val change=changes.getJSONObject(i);if(change.getInt("table")!=25)cut.put(change)}
            assertTrue(cut.length()<changes.length())
            val fresh=EncryptedBackupFile.random(12);cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(keys.getValue(1),"AES"),javax.crypto.spec.GCMParameterSpec(128,fresh));cipher.updateAAD(header.toString().toByteArray())
            e.put("nonce",java.util.Base64.getEncoder().encodeToString(fresh)).put("ciphertext",java.util.Base64.getEncoder().encodeToString(cipher.doFinal(cut.toString().toByteArray())))
            try{b.receive(1,listOf(e.toString()));fail("Missing document chunk accepted")}catch(_:Exception){}
            assertEquals(0L,b.cursor());assertEquals(before,b.authorFingerprint())
            b.receive(1,listOf(original));assertEquals(a.authorFingerprint(),b.authorFingerprint())
            WorkspaceRepository(b.db).create("未纳入发件箱的写入",false,PaperStyle.BLANK)
            try{b.receive(1,emptyList());fail("Unjournaled author data overwritten")}catch(e:IllegalArgumentException){assertEquals("SHADOW_UNJOURNALED_WRITE",e.message)}
        }finally{for(r in listOf(a,b,missing)){r.close();require(r.directory.canonicalFile.parentFile==java.io.File(context.cacheDir,"shadow-sync").canonicalFile);r.directory.deleteRecursively()}}
    }
    @Test fun actualRowsRoundTripThenOfflineConflictsRemainDurable()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val alice=BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",id())
        val other=BackupTransport.login(alice.url,"synthetic-alice","synthetic-alice-password-123",id())
        val library=id();val ta=BackupTransport(alice);val tb=BackupTransport(other);ta.json("PUT",library)
        val scope=listOf(alice.server,alice.issuer,alice.user,library);val keys=mapOf(1 to EncryptedBackupFile.random(32))
        var a=ShadowReplica.open(context,scope,alice.device,keys);var b=ShadowReplica.open(context,scope,other.device,keys)
        val ar=a.directory;val br=b.directory
        var book="";var map="";var stroke:InkStroke?=null
        try{
            a.author{db->
                val note=WorkspaceRepository(db).create("合成影子同步 · 条件概率",false,PaperStyle.GRID);book=note.id
                val ink=InkStroke(id(),InkPen.PENCIL,0xff3159b8.toInt(),4f,InkTool.STYLUS,List(32){InkSample(100f+it*4,200f+it%3,it*10L)})
                stroke=ink;val repo=InkRepository(db);repo.save(CommitInk(id(),book,0,InkMutation.Add(ink)))
                repo.save(CommitInk(id(),book,1,InkMutation.Cut(EraseSelection(InkCut(id(),4f,listOf(EraserPoint(140f,190f),EraserPoint(140f,210f))),listOf(ink.id)))))
                map=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition("概率图")))
                val graph=MapGraphAccess(db).read(book).first{it.ref.mapId==map}
                StudyRepository(db).submit(CaptureDraft(book,StudySourceDraft(book,2,ink.bounds(),listOf(ink.id)),"条件概率\n已确认来源").command(MapRef(book,map),null,graph.graphHash))
                val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE)
                val png=java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,it)}.toByteArray();bitmap.recycle()
                PageObjectRepository(db).save(book,0,id(),listOf(PageObject(id(),PageObjectKind.IMAGE,50f,400f,160f,160f,image=java.util.Base64.getEncoder().encodeToString(png)),PageObject(id(),PageObjectKind.MAP,250f,400f,600f,380f,mapEmbed=MapEmbed(MapRef(book,map)))))
                ResourceTemplates(db).instantiate("a".repeat(64),"合成资源实例",PaperStyle.CORNELL,null,null)
            }
            val envelope=a.outgoing().single();assertFalse(envelope.contains("条件概率"));sync(a,ta,library);sync(b,tb,library)
            assertEquals(a.authorFingerprint(),b.authorFingerprint())
            assertArrayEquals(InkStrokeCodec.encode(stroke!!),InkStrokeCodec.encode(InkRepository(b.db).read(book).strokes.single().stroke))
            assertEquals(1,InkRepository(b.db).read(book).cuts.size)
            assertEquals(MapGraphAccess(a.db).read(book),MapGraphAccess(b.db).read(book))
            assertEquals(PageObjectRepository(a.db).read(book).objects,PageObjectRepository(b.db).read(book).objects)
            val before=b.authorFingerprint();val cursor=b.cursor()
            a.author{db->WorkspaceRepository(db).changePaper(book,PaperStyle.DOTS)}
            val next=a.outgoing().single()
            b.close();b=ShadowReplica.open(context,scope,other.device,keys,directory=br,fault={if(it=="before-receive-commit")error("injected")})
            try{b.receive(cursor+1,listOf(next));fail("fault ignored")}catch(_:IllegalStateException){}
            assertEquals(cursor,b.cursor());assertEquals(before,b.authorFingerprint())
            b.close();b=ShadowReplica.open(context,scope,other.device,keys,directory=br)
            sync(a,ta,library);sync(b,tb,library);assertEquals(a.authorFingerprint(),b.authorFingerprint())
            a.author{db->val w=WorkspaceRepository(db).get(book);WorkspaceRepository(db).organize(book,w.revision,"A人工整理","",false,false)}
            b.author{db->val w=WorkspaceRepository(db).get(book);WorkspaceRepository(db).organize(book,w.revision,"B人工整理","",false,true)}
            // Reopen with an unacknowledged outbox before any upload.
            a.close();a=ShadowReplica.open(context,scope,alice.device,keys,directory=ar);assertTrue(a.outgoing().isNotEmpty())
            sync(a,ta,library);sync(b,tb,library);sync(a,ta,library)
            assertTrue(a.conflicts()>0);assertTrue(b.conflicts()>0)
            assertEquals("A人工整理",WorkspaceRepository(a.db).get(book).folder)
            assertNotNull(WorkspaceRepository(b.db).get(book).trashedAt)
            a.author("derived"){db->val w=WorkspaceRepository(db).get(book);WorkspaceRepository(db).organize(book,w.revision,"派生识别结果","",false,false)}
            assertEquals("A人工整理",WorkspaceRepository(a.db).get(book).folder)
            sync(a,ta,library);sync(b,tb,library)
            assertNotNull(WorkspaceRepository(b.db).get(book).trashedAt)
            assertEquals("B人工整理",WorkspaceRepository(b.db).get(book).folder)
            val bob=BackupTransport(BackupTransport.login(alice.url,"synthetic-bob","synthetic-bob-password-456",id()))
            try{bob.json("GET",library,"/shadow/events");fail("Wrong account read relay")}catch(e:BackupHttpError){assertEquals(404,e.status)}
        }finally{a.close();b.close();for(dir in listOf(ar,br)){require(dir.canonicalFile.parentFile==java.io.File(context.cacheDir,"shadow-sync").canonicalFile);dir.deleteRecursively()}}
    }
}
