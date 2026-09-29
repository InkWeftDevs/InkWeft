package org.inkweft.app

import android.os.*
import android.graphics.Bitmap
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual document session timings; fixed synthetic content, never the owner's notes. */
class WorkflowPerformanceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id(s:String)=UUID.nameUUIDFromBytes(s.toByteArray()).toString()
 private fun canvas():InkCanvasView? {
  val q=java.util.ArrayDeque<View>();q.add(compose.activity.window.decorView)
  while(q.isNotEmpty()){val v=q.removeFirst();if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)q.add(v.getChildAt(i))};return null
 }
 private fun threadTicks():Map<String,Long> = File("/proc/self/task").listFiles().orEmpty().mapNotNull{f->runCatching{
  val name=File(f,"comm").readText().trim();val label=if(f.name==Process.myPid().toString())"ui"else if(name=="RenderThread")"render"else return@runCatching null
  val stat=File(f,"stat").readText().substringAfterLast(") ").split(' ');label to (stat[11].toLong()+stat[12].toLong())
 }.getOrNull()}.toMap()
 @Test fun documentOpenReopenPanelAndEraseHaveSeparateMeasurements(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val n=runBlocking{app.workspaceRepository.create("集成性能固定样本",false,PaperStyle.BLANK)}
  val strokes=List(600){i->InkStroke(id("workflow-$i"),InkPen.PENCIL,0xff3159b8.toInt(),4f,InkTool.STYLUS,List(32){j->InkSample(60f+(i%20)*43+j,80f+(i/20)*36+(kotlin.math.sin(j*.5)*6).toFloat(),j*8L,.6f,.3f)},appearance=StrokeAppearance(BrushRecipe(),123L+i,0f,0f))}
  val fixture=InkPageFile("workflow","",strokes,false,PaperStyle.BLANK).encode()
  runBlocking{strokes.chunked(200).forEachIndexed{i,batch->assertTrue(app.inkRepository.save(CommitInk(id("workflow-seed-$i"),n.id,i.toLong(),InkMutation.Replace(emptyList(),batch))) is InkCommitResult.Committed)}}
  compose.activity.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${n.id}",false).commit()
  val frames=CopyOnWriteArrayList<Double>();val gpu=CopyOnWriteArrayList<Double>()
  val handler=HandlerThread("workflow-metrics").apply{start()}
  val listener=Window.OnFrameMetricsAvailableListener{_,m,_->frames.add(m.getMetric(FrameMetrics.TOTAL_DURATION)/1e6);val v=m.getMetric(FrameMetrics.GPU_DURATION);if(v>=0)gpu.add(v/1e6)}
  compose.runOnIdle{compose.activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handler.looper))}
  val report=JSONObject().put("mode","debug-actual-document-session").put("sha256",ContentTransfer.hash(fixture)).put("strokes",600).put("points",19200).put("pages",1)
  val condition=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("perfCondition")?:"writing"
  report.put("condition",condition)
  var background:kotlinx.coroutines.Job?=null
  val backgroundScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  if(condition=="backup"){
   runBlocking{app.libraryBackup.snapshot().use{snapshot->snapshot.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==1){"Synthetic library required"}}}}
   var login:Job?=null;compose.runOnIdle{app.backupEngine.sessions.meteredAllowed=true;login=app.backupEngine.login("http://127.0.0.1:18751","synthetic-alice","synthetic-alice-password-123")};runBlocking{login?.join()}
   assertTrue(app.backupEngine.ui.value.connected)
   val started=System.nanoTime();compose.runOnIdle{background=app.backupEngine.backup(UUID.randomUUID().toString(),EncryptedBackupFile.b64(EncryptedBackupFile.random(32)))}
   background?.invokeOnCompletion{report.put("backgroundMs",(System.nanoTime()-started)/1e6)}
  }
  if(condition=="install"){
   val manifest="""{"format":"inkweft.resource-pack.v1","id":"example.perf-pack","title":"固定性能模板","author":"InkWeft","version":1,"resources":[{"id":"paper","type":"paper","title":"方格记录","paper":"GRID"}]}"""
   val bytes=java.io.ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{z->z.putNextEntry(java.util.zip.ZipEntry("manifest.json"));z.write(manifest.toByteArray());z.closeEntry()}}.toByteArray()
   val started=System.nanoTime();background=backgroundScope.launch{app.resourcePacks.install(ResourcePackCodec.inspect(bytes));report.put("backgroundMs",(System.nanoTime()-started)/1e6)}
  }
  val cpuBefore=threadTicks()
  try{
   fun open(prefix:String){
    val start=System.nanoTime();var data=0.0;var preview=0.0;var final=0.0
    Trace.beginAsyncSection("InkWeft.workflow.$prefix",1)
    compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
    compose.waitForIdle()
    val end=System.nanoTime()+40_000_000_000L
    while(final==0.0&&System.nanoTime()<end){
     compose.waitForIdle()
     androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{
      canvas()?.takeIf{it.displayedStrokeCount==600}?.let{v->
       val ms=(System.nanoTime()-start)/1e6;if(data==0.0)data=ms
       val raster=InkCanvasView::class.java.getDeclaredField("asyncRaster").apply{isAccessible=true}.get(v) as AsyncInkRaster
       // Opening fits the paper width: offscreen rows are intentionally culled.
       // AsyncInkRaster publishes complete visible coverage, not the entire document.
       val frame=AsyncInkRaster::class.java.getDeclaredField("frame").apply{isAccessible=true}.get(raster)
       if(preview==0.0&&frame!=null)preview=ms
       if(preview>0&&!v.rasterPending)final=ms
      }
     };Thread.sleep(8)
    }
    Trace.endAsyncSection("InkWeft.workflow.$prefix",1)
    assertTrue("Document never reached complete raster: data=$data preview=$preview",final>0)
    report.put(prefix,JSONObject().put("dataReadyMs",data).put("completePreviewMs",preview).put("finalMs",final))
   }
   open("firstOpen")
   var started=System.nanoTime();compose.onNodeWithTag("quick-study").performClick();compose.onNodeWithTag("study-panel").assertIsDisplayed();compose.waitForIdle()
   report.put("panelOpenMs",(System.nanoTime()-started)/1e6)
   started=System.nanoTime();compose.onNodeWithTag("study-close").performClick();compose.onNodeWithTag("study-panel").assertDoesNotExist();compose.waitForIdle();report.put("panelCloseMs",(System.nanoTime()-started)/1e6)
   compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].back()};compose.waitForIdle();open("hotReopen")
   fun measureViewport(label:String, gesture:()->Unit){
    var before:CanvasViewport?=null;compose.runOnIdle{before=checkNotNull(canvas()).snapshotViewport()}
    val start=System.nanoTime();Trace.beginAsyncSection("InkWeft.workflow.$label",2);gesture();compose.waitForIdle()
    report.put(label+"GestureMs",(System.nanoTime()-start)/1e6)
    compose.waitUntil(15000){var done=false;compose.runOnIdle{done=canvas()?.let{!it.rasterPending}==true};done}
    compose.runOnIdle{assertNotEquals("Viewport gesture must move the actual document",before,checkNotNull(canvas()).snapshotViewport())}
    report.put(label+"SettledMs",(System.nanoTime()-start)/1e6);Trace.endAsyncSection("InkWeft.workflow.$label",2)
    assertEquals(3L,runBlocking{app.inkRepository.read(n.id).revision})
   }
   measureViewport("scroll"){compose.onNodeWithTag("ink-surface").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.7f,height*.65f),androidx.compose.ui.geometry.Offset(width*.7f,height*.35f),300)}}
   measureViewport("pinch"){compose.pinchCanvasOut()}
   if(condition=="map"){compose.onNodeWithTag("quick-study").performClick();compose.onNodeWithTag("study-panel").assertIsDisplayed()}
   compose.runOnIdle{checkNotNull(canvas()).fitWidth()};compose.waitForIdle()
   started=System.nanoTime();compose.runOnIdle{ViewModelProvider(compose.activity)["ink-${n.id}",InkViewModel::class.java].erase(listOf(strokes.first().id))}
   compose.waitUntil(15000){runBlocking{app.inkRepository.read(n.id).revision}==4L}
   report.put("eraseCommandCommittedMs",(System.nanoTime()-started)/1e6)
   compose.waitUntil(15000){var visible=false;compose.runOnIdle{canvas()?.let{visible=it.displayedStrokeCount==599&&!it.rasterPending}};visible}
   report.put("eraseVisibleMs",(System.nanoTime()-started)/1e6)
   val saveStart=System.nanoTime();val extra=InkStroke(id("workflow-save"),InkPen.PEN,0xff314159.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(150f,150f,20)))
   val revision=runBlocking{app.inkRepository.read(n.id).revision}
   assertTrue(runBlocking{app.inkRepository.save(CommitInk(id("workflow-save-command"),n.id,revision,InkMutation.Add(extra)))} is InkCommitResult.Committed)
   report.put("saveConfirmedMs",(System.nanoTime()-saveStart)/1e6)
   val textObjects=List(8){i->PageObject(id("workflow-text-$i"),PageObjectKind.TEXT,100f,100f+i*120,600f,100f,text="条件概率 P(A | B) · 第 ${i+1} 节学习记录")}
   val mixedStart=System.nanoTime();val mixedBitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888)
   compose.runOnIdle{val painter=PageObjectPainter();painter.draw(android.graphics.Canvas(mixedBitmap),textObjects,false,CanvasBounds(0.0,0.0,1000.0,1414.0));painter.clear()}
   mixedBitmap.recycle();report.put("mixedTextDrawMs",(System.nanoTime()-mixedStart)/1e6).put("textFixtureSha256",ContentTransfer.hash(PageObjectCodec.encode(textObjects)))
   runBlocking{background?.join()}
   if(condition=="backup")compose.waitUntil(30000){!app.backupEngine.ui.value.busy}
   val ticks=android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toDouble();val after=threadTicks()
   for(name in listOf("ui","render"))report.put(name+"CpuMs",if(cpuBefore[name]!=null&&after[name]!=null)(after.getValue(name)-cpuBefore.getValue(name))*1000/ticks else JSONObject.NULL)
   val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
   report.put("pssKb",memory.totalPss).put("javaHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize()).put("gpuMemoryBytes",JSONObject.NULL)
   report.put("frameTotalMs",JSONArray(frames)).put("frameGpuDurationMs",JSONArray(gpu)).put("refreshRate",compose.activity.display?.refreshRate?:JSONObject.NULL)
   File(app.getExternalFilesDir(null),"workflow-performance.json").writeText(report.toString(2))
  }finally{backgroundScope.cancel();compose.runOnIdle{compose.activity.window.removeOnFrameMetricsAvailableListener(listener)};handler.quitSafely()}
 }
}
