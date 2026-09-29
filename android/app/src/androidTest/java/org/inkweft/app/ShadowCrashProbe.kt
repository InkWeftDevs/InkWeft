package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.PaperStyle
import org.inkweft.data.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.UUID

/** Native kill/reopen at actual encrypted inbox, author/cursor, and outbox acknowledgement boundaries. */
class ShadowCrashProbe{
    @Test fun durableReceiveAndAck()=runBlocking<Unit>{
        val i=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments();require(args.getString("shadowProbe")=="dedicated-emulator")
        val context=i.targetContext;val marker=File(context.filesDir,"shadow-crash-probe.json");val cut=args.getString("cut")!!
        fun id()=UUID.randomUUID().toString()
        fun persist(m:JSONObject){FileOutputStream(marker).use{it.write(m.toString().toByteArray());it.fd.sync()}}
        fun fault(point:String){if(point==cut){persist(JSONObject(marker.readText()).put("reached",point));android.os.Process.killProcess(android.os.Process.myPid())}}
        if(args.getString("phase")=="prepare"){
            require(!marker.exists());val aWho=BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",id())
            val bWho=BackupTransport.login(aWho.url,"synthetic-alice","synthetic-alice-password-123",id());val library=id();BackupTransport(aWho).json("PUT",library)
            val binding=listOf(aWho.server,aWho.issuer,aWho.user,library);val key=EncryptedBackupFile.random(32)
            val ack=cut.contains("outbox");val who=if(ack)aWho else bWho
            val a=ShadowReplica.open(context,binding,aWho.device,mapOf(1 to key),fault={if(ack)fault(it)})
            val b=ShadowReplica.open(context,binding,bWho.device,mapOf(1 to key),fault={if(!ack)fault(it)})
            a.author{db->WorkspaceRepository(db).create("合成接收终止",false,PaperStyle.DOTS)}
            persist(JSONObject().put("directory",(if(ack)a else b).directory.path).put("scope",JSONArray(binding)).put("device",who.device).put("key",EncryptedBackupFile.b64(key)).put("cut",cut))
            val ta=BackupTransport(aWho);val envelope=a.outgoing().single();ta.json("POST",library,"/shadow/events",JSONObject(envelope))
            val replica=if(ack)a else b;val batch=BackupTransport(who).json("GET",library,"/shadow/events?cursor=0");val events=batch.getJSONArray("envelopes")
            persist(JSONObject(marker.readText()).put("cursor",batch.getLong("cursor")).put("operation",JSONObject(envelope).getString("operation")))
            replica.receive(batch.getLong("cursor"),List(events.length()){events.getString(it)})
            if(ack)replica.acknowledged(JSONObject(envelope).getString("operation"))
            fail("Expected native termination")
        }else{
            val m=JSONObject(marker.readText());assertEquals(cut,m.getString("reached"));val scope=m.getJSONArray("scope");val binding=List(scope.length()){scope.getString(it)}
            val replica=ShadowReplica.open(context,binding,m.getString("device"),mapOf(1 to EncryptedBackupFile.unb64(m.getString("key"))),directory=File(m.getString("directory")))
            try{
                val committed=cut in listOf("after-receive-commit","before-outbox-ack","after-outbox-ack")
                assertEquals(if(committed)m.getLong("cursor")else 0L,replica.cursor())
                val notes=NoteRepository(replica.db)
                if(!cut.contains("outbox"))assertEquals(if(committed)1 else 0,notes.observeNotes().first().size)
                val who=BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",m.getString("device"))
                ShadowRelay.sync(replica,BackupTransport(who),binding.last())
                assertEquals(1,notes.observeNotes().first().size);assertEquals(m.getLong("cursor"),replica.cursor());assertTrue(replica.outgoing().isEmpty())
                persist(JSONObject().put("cut",cut).put("reached",cut).put("verified",true).put("cursor",replica.cursor()))
            }finally{replica.close()}
        }
    }
}
