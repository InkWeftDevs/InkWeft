package org.inkweft.app

import android.graphics.*
import android.os.*
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class ConcurrentWritingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id(text:String)=UUID.nameUUIDFromBytes(text.toByteArray()).toString()
    private fun canvas():InkCanvasView {
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    @Test fun saveWindowActuallyOverlapsBackgroundWork(){
        val args=InstrumentationRegistry.getArguments();val condition=args.getString("perfCondition")?:"writing";val content=args.getString("perfContent")?:"pencil"
        require(condition in listOf("writing","map","backup","install")&&content in listOf("ink","pencil","natural","pdf"))
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val n=runBlocking{if(content=="pdf"){
                val pdf=android.graphics.pdf.PdfDocument();val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,1414,1).create());page.canvas.drawColor(Color.WHITE)
                val paint=Paint().apply{color=Color.DKGRAY;textSize=20f};repeat(35){page.canvas.drawText("Probability chapter ${it+1}: P(A | B) = P(A & B) / P(B)",40f,60f+it*34,paint)};pdf.finishPage(page)
                val out=ByteArrayOutputStream();pdf.writeTo(out);pdf.close();app.resourceTemplates.instantiate("b".repeat(64),"合成并行负载",PaperStyle.BLANK,PdfPageSource(PdfDocumentSource(out.toByteArray(),1),0),null)
            }else app.workspaceRepository.create("合成并行负载",false,PaperStyle.BLANK)}
        val pen=if(content=="ink")InkPen.PEN else InkPen.PENCIL
        val strokes=List(600){i->InkStroke(id("overlap-$i"),pen,0xff3159b8.toInt(),4f,InkTool.STYLUS,List(32){j->InkSample(60f+(i%20)*43+j,80f+(i/20)*36+(kotlin.math.sin(j*.5)*6).toFloat(),j*8L,.6f,.3f)},appearance=StrokeAppearance(BrushRecipe(),123L+i,0f,0f))}
        runBlocking{strokes.chunked(200).forEachIndexed{i,batch->assertTrue(app.inkRepository.save(CommitInk(id("overlap-seed-$i"),n.id,i.toLong(),InkMutation.Replace(emptyList(),batch))) is InkCommitResult.Committed)}
            if(content=="natural"){TextStyles.initialize(app);app.pageObjects.save(n.id,0,id("natural"),listOf(beautyObject(strokes.take(4),RecognizedWriting("条件概率",1f,1),BeautyOptions(),false,revision=3)),3)}

        }
        compose.activity.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${n.id}",false).commit()
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();compose.waitForSavedInk()
        if(condition=="map"){compose.onNodeWithTag("quick-study").performClick();compose.onNodeWithTag("study-panel").assertIsDisplayed()}
        val times=CopyOnWriteArrayList<JSONObject>();val frames=CopyOnWriteArrayList<JSONObject>()
        val handler=HandlerThread("overlap-metrics").apply{start()};val listener=Window.OnFrameMetricsAvailableListener{_,m,_->frames.add(JSONObject().put("at",System.nanoTime()).put("ms",m.getMetric(FrameMetrics.TOTAL_DURATION)/1e6))}
        compose.runOnIdle{compose.activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handler.looper))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);var work:Job?=null;var start=0L;var finish=0L
        try{
            if(condition=="backup"){
                var login:Job?=null;compose.runOnIdle{app.backupEngine.sessions.meteredAllowed=true;login=app.backupEngine.login("http://127.0.0.1:18754","synthetic-alice","synthetic-alice-password-123")};runBlocking{login?.join()};assertTrue(app.backupEngine.ui.value.connected)
                start=System.nanoTime();compose.runOnIdle{work=app.backupEngine.backup(UUID.randomUUID().toString(),EncryptedBackupFile.b64(EncryptedBackupFile.random(32)))};work!!.invokeOnCompletion{finish=System.nanoTime()}
                val deadline=System.nanoTime()+30_000_000_000L
                while(true){val active=JSONObject(java.net.URL("http://127.0.0.1:18754/__fixture__/activity").readText()).getInt("uploads");if(active>0)break;check(System.nanoTime()<deadline);Thread.sleep(20)}
            }
            if(condition=="install"){
                val json="""{"format":"inkweft.resource-pack.v1","id":"example.overlap","title":"合成批量安装","author":"InkWeft","version":1,"resources":[{"id":"paper","type":"paper","title":"课堂记录","paper":"GRID"}]}"""
                val bytes=ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{z->z.putNextEntry(java.util.zip.ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry()}}.toByteArray()
                start=System.nanoTime();work=scope.async{repeat(256){val begin=System.nanoTime();app.resourcePacks.install(app.resourcePacks.inspect(bytes.inputStream()));times.add(JSONObject().put("kind","installation").put("start",begin).put("end",System.nanoTime()))};finish=System.nanoTime()}
            }
            val windowStart=System.nanoTime()
            repeat(8){i->
                if(work!=null)assertTrue("Background task ended before save $i",work!!.isActive)
                val before=runBlocking{app.inkRepository.read(n.id).revision};val begin=System.nanoTime()
                compose.runOnIdle{
                    val v=canvas();v.pen=pen;val now=SystemClock.uptimeMillis()
                    for(j in 0..2){val point=v.snapshotViewport().worldToScreen(300.0+j*25,350.0+i*18,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble())
                        val properties=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val coords=MotionEvent.PointerCoords().apply{x=point.x.toFloat();y=point.y.toFloat();pressure=.6f}
                        val event=MotionEvent.obtain(now,now+j*12L,when(j){0->MotionEvent.ACTION_DOWN;2->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                        try{assertTrue(v.dispatchTouchEvent(event))}finally{event.recycle()}
                    }
                }
                compose.waitUntil(15000){runBlocking{app.inkRepository.read(n.id).revision}>before}
                times.add(JSONObject().put("kind","save").put("start",begin).put("end",System.nanoTime()))
            }
            val windowEnd=System.nanoTime();if(work!=null)assertTrue("Background task did not span saves",work!!.isActive)
            if(condition=="map")compose.onNodeWithTag("study-panel").assertIsDisplayed()
            runBlocking{val job=work;if(job is Deferred<*>)job.await()else job?.join()}
            if(condition=="backup")assertEquals("PUBLISHED",JSONObject(File(app.filesDir,"encrypted-backup-jobs/queue.json").readText()).getString("state"))
            assertEquals(608,runBlocking{app.inkRepository.read(n.id).strokes.size})
            val report=JSONObject().put("source",BuildConfig.SOURCE_COMMIT).put("condition",condition).put("content",content).put("fixture",ContentTransfer.hash(InkPageFile("overlap","",strokes,false,PaperStyle.BLANK).encode())).put("backgroundStart",start).put("backgroundEnd",finish).put("windowStart",windowStart).put("windowEnd",windowEnd).put("events",JSONArray(times)).put("frames",JSONArray(frames)).put("backgroundScope",if(condition=="install")"256 actual idempotent installs, including explicit foreground yielding"else if(condition=="backup")"real encrypted transfer; fixture delays each PUT response 4 seconds"else"none")
            if(work!=null)assertTrue(start<=windowStart&&finish>=windowEnd)
            File(app.getExternalFilesDir(null),"overlap-$condition-$content.json").writeText(report.toString(2))
        }finally{scope.cancel();compose.runOnIdle{compose.activity.window.removeOnFrameMetricsAvailableListener(listener)};handler.quitSafely()}
    }
}
