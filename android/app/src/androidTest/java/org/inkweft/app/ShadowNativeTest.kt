package org.inkweft.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ShadowNativeTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun labReopensItsOwnSessionAndDiagnosticsExcludeItsKey(){
        val app=compose.activity.application as InkWeftApplication
        val identity=runBlocking{BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",UUID.randomUUID().toString())}
        compose.activity.setContent{InkTheme.Content{ShadowLabDialog(identity){}}}
        compose.onNodeWithText("开始合成实验").performClick()
        compose.waitUntil(20000){compose.onAllNodesWithText("合成资料已交换",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("shadow-paper").assertExists()
        val record=org.json.JSONObject(File(app.cacheDir,"shadow-ui-session.json").readText());val book=record.getString("book")
        assertNull(runBlocking{app.repository.read(book)})
        compose.activity.setContent{};compose.waitForIdle()
        compose.activity.setContent{InkTheme.Content{ShadowLabDialog(identity){}}}
        compose.waitUntil(15000){compose.onAllNodesWithTag("shadow-paper").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("交换修改").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("合成资料已交换",substring=true).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(book,org.json.JSONObject(File(app.cacheDir,"shadow-ui-session.json").readText()).getString("book"))
        val diagnostic=runBlocking{app.diagnostics.bundle()}
        java.util.zip.ZipInputStream(diagnostic.inputStream()).use{zip->while(zip.nextEntry!=null){val text=zip.readBytes().toString(Charsets.UTF_8);assertFalse(text.contains(record.getString("syntheticKey")));assertFalse(text.contains(identity.token))}}
        compose.activity.setContent{};compose.waitForIdle()
    }
    @Test fun relayReceiverRendersSourceEditsCardAndReturnsToAuthor(){
        val app=compose.activity.application as InkWeftApplication;fun id()=UUID.randomUUID().toString()
        val before=runBlocking{app.repository.observeNotes().first()}
        val identity=runBlocking{BackupTransport.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123",id())}
        val other=runBlocking{BackupTransport.login(identity.url,"synthetic-alice","synthetic-alice-password-123",id())};val tb=BackupTransport(other)
        val t=BackupTransport(identity);val library=id();runBlocking{t.json("PUT",library)}
        val binding=listOf(identity.server,identity.issuer,identity.user,library);val keys=mapOf(1 to EncryptedBackupFile.random(32))
        val a=ShadowReplica.open(app,binding,identity.device,keys);val b=ShadowReplica.open(app,binding,other.device,keys)
        val book=runBlocking{shadowSample(app,a).also{ShadowRelay.sync(a,t,library);ShadowRelay.sync(b,tb,library)}}
        val session=ShadowAuthorSession(app,b)
        try{
            compose.activity.setContent{InkTheme.Content{ShadowReplicaPanel(session,book)}}
            compose.waitUntil(15000){compose.onAllNodesWithTag("shadow-paper").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(15000){var ready=false;compose.runOnIdle{ready=compose.activity.window.decorView.findViewWithTag<InkCanvasView>("shadow-page-canvas")?.let{it.displayedStrokeCount>0&&!it.rasterPending&&it.documentContentReady}==true};ready}
            fun shot(name:String){compose.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
            shot("v46-shadow-paper.png")
            compose.onNodeWithTag("shadow-tab-1").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithTag("shadow-map").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle()
            val node=runBlocking{session.maps.read(book).first().nodes.single()}
            compose.runOnIdle{val view=compose.activity.window.decorView.findViewWithTag<MindMapView>("shadow-map-canvas");val box=view.nodeBounds(node.id)!!;val now=android.os.SystemClock.uptimeMillis()
                for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val e=android.view.MotionEvent.obtain(now,now+20,action,box.centerX(),box.centerY(),0);view.dispatchTouchEvent(e);e.recycle()}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("shadow-card-source").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("shadow-card-source").performClick();compose.onNodeWithTag("review-source").assertExists()
            compose.waitUntil(10000){compose.onAllNodesWithTag("review-source-canvas").fetchSemanticsNodes().isNotEmpty()};shot("v46-shadow-source.png")
            compose.onNodeWithTag("return-to-review").performClick()
            compose.onNodeWithTag("shadow-card-title").performTextReplacement("B 端原生修改")
            compose.onNodeWithTag("shadow-card-body").performTextReplacement("在接收端核对来源后修订，返回创作库。")
            compose.onNodeWithTag("shadow-card-save").performClick()
            compose.waitUntil(10000){runBlocking{session.maps.read(book).first().nodes.single().title=="B 端原生修改"}}
            shot("v46-shadow-map-updated.png")
            compose.onNodeWithTag("shadow-tab-0").performClick();compose.onNodeWithTag("shadow-paper").assertExists();shot("v46-shadow-live-embed.png")
            runBlocking{ShadowRelay.sync(b,tb,library);ShadowRelay.sync(a,t,library);assertEquals(a.authorFingerprint(),b.authorFingerprint());assertEquals(before,app.repository.observeNotes().first())}
            val cardId=node.cardId!!
            runBlocking{
                for((r,title)in listOf(a to "A 的后续解释",b to "B 的后续解释"))r.author{db->val card=db.study().card(cardId)!!;StudyRepository(db).submit(org.inkweft.core.StudyCommand(id(),book,org.inkweft.core.StudyAction.EDIT,cardId=cardId,expectedRevision=card.revision,title=title,body=title+"；保留完整修订"))}
                ShadowRelay.sync(a,t,library);ShadowRelay.sync(b,tb,library);ShadowRelay.sync(a,t,library)
            }
            compose.activity.setContent{InkTheme.Content{ShadowReplicaPanel(session,book,refresh=1)}}
            compose.onNodeWithTag("shadow-tab-2").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithTag("shadow-choice-other").fetchSemanticsNodes().isNotEmpty()};shot("v46-shadow-conflict.png")
            compose.onNodeWithTag("shadow-choice-other").performClick()
            compose.waitUntil(10000){runBlocking{b.semanticConflicts().isEmpty()}}
            runBlocking{ShadowRelay.sync(b,tb,library);ShadowRelay.sync(a,t,library);assertEquals(a.authorFingerprint(),b.authorFingerprint());assertEquals("A 的后续解释",a.db.study().card(cardId)!!.title)}
            runBlocking{
                a.author{db->val pages=NotebookPages(db);val next=pages.addAfter(book,book,id());val order=pages.activePages(book)
                    assertTrue(pages.edit(org.inkweft.core.EditPage(id(),book,book,org.inkweft.core.PageEditKind.TRASH,org.inkweft.core.InsertPages.orderHash(order.map{it.id}),pages.inkRevision(book),stayOnPageId=next.id)) is org.inkweft.core.EditPageResult.Applied)}
                ShadowRelay.sync(a,t,library);ShadowRelay.sync(b,tb,library)
            }
            val historical=runBlocking{session.study.source(cardId)!!}
            compose.activity.setContent{InkTheme.Content{ReviewSourceDialog(historical,{},session)}}
            compose.waitUntil(10000){compose.onAllNodesWithText("来源页已回收",substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertFalse(runBlocking{session.pages.activePages(book)}.any{it.id==historical.pageId});assertNotNull(runBlocking{b.db.study().card(cardId)})
            shot("v46-shadow-retired-source.png")
        }finally{compose.activity.setContent{};compose.waitForIdle();a.close();b.close()}
    }
}
