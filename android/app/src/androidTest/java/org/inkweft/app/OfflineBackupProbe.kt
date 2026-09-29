package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.inkweft.core.PaperStyle
import org.json.JSONObject
import java.io.File

class OfflineBackupProbe {
    @Test fun disconnectWithoutNetworkThenResumeOriginalQueue(){
        val i=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments();require(args.getString("offlineProbe")=="dedicated-emulator")
        val app=i.targetContext.applicationContext as InkWeftApplication;val engine=app.backupEngine;val queue=File(app.filesDir,"encrypted-backup-jobs/queue.json")
        fun await(action:()->Unit){i.runOnMainSync(action);val end=System.currentTimeMillis()+30000;while(engine.ui.value.busy&&System.currentTimeMillis()<end)Thread.sleep(20);assertFalse(engine.ui.value.busy)}
        when(args.getString("phase")){
            "prepare"->{require(!queue.exists());runBlocking{app.workspaceRepository.create("合成离线断开",false,PaperStyle.BLANK)}
                await{engine.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")};assertTrue(engine.ui.value.connected);engine.sessions.meteredAllowed=true
                await{engine.create(engine.sessions.localLibrary,EncryptedBackupFile.b64(EncryptedBackupFile.random(32)))};assertEquals("PENDING",JSONObject(queue.readText()).getString("state"))
            }
            "offline"->{val before=JSONObject(queue.readText()).getString("operation");await{engine.logout()};assertNull(engine.sessions.read());assertFalse(engine.ui.value.connected)
                assertTrue(engine.ui.value.message.contains("远端撤销未确认"));assertEquals(before,JSONObject(queue.readText()).getString("operation"));assertFalse(JSONObject(queue.readText()).optBoolean("automatic"));assertFalse(engine.mayResume())
            }
            "online"->{val before=JSONObject(queue.readText()).getString("operation");await{engine.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")};await{engine.upload()}
                assertEquals(before,JSONObject(queue.readText()).getString("operation"));assertEquals("PUBLISHED",JSONObject(queue.readText()).getString("state"))
            }
            else->error("Probe phase required")
        }
    }
}
