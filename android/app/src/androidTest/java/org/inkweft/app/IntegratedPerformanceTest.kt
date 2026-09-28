package org.inkweft.app
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
/** Fixed legal author samples; timings are observations, never a debug product SLO. */
class IntegratedPerformanceTest {
 @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
 private fun id(s:String)=UUID.nameUUIDFromBytes(s.toByteArray()).toString()
 private fun sample(name:String,count:Int,pen:InkPen,erased:Boolean=false)=List(count){i->
  val cuts=if(erased)listOf(InkCut(id("$name-cut-$i"),3f,listOf(EraserPoint(80f+(i%20)*43,80f+(i/20)*36))))else emptyList()
  InkStroke(id("$name-$i"),pen,0xff3159b8.toInt(),4f,InkTool.STYLUS,List(32){j->InkSample(60f+(i%20)*43+j,80f+(i/20)*36+(kotlin.math.sin(j*.5)*6).toFloat(),j*8L,.6f,.3f)},cuts=cuts,appearance=StrokeAppearance(if(pen==InkPen.PENCIL)BrushRecipe()else BrushRecipe.LEGACY,123L+i,0f,0f))
 }
 @Test fun fixedScenesRecordPreviewFinalWarmDrawAndMemory(){
  val ins=InstrumentationRegistry.getInstrumentation();val rows=JSONArray();val frames=CopyOnWriteArrayList<Double>();val gpu=CopyOnWriteArrayList<Double>()
  val thread=HandlerThread("perf-frames").apply{start()};val listener=Window.OnFrameMetricsAvailableListener{_,m,_->frames.add(m.getMetric(FrameMetrics.TOTAL_DURATION)/1e6);gpu.add(m.getMetric(FrameMetrics.GPU_DURATION)/1e6)}
  rule.scenario.onActivity{it.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))}
  val cases=listOf("ordinary" to sample("ordinary",20,InkPen.PEN),"pencil" to sample("pencil",600,InkPen.PENCIL),"erased" to sample("erased",120,InkPen.PENCIL,true),"mixed" to sample("mixed",80,InkPen.PEN))
  try {for((name,strokes) in cases){
   val bytes=InkPageFile(name,"",strokes,false,PaperStyle.BLANK).encode();val readStart=System.nanoTime();val decoded=InkPageFile.decode(bytes);val decodeMs=(System.nanoTime()-readStart)/1e6
   val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);lateinit var raster:AsyncInkRaster
   var first=0.0;var start=0L;var callbacks=0;var complete=false
   val vp=CanvasViewport(500.0,707.0,1.0)
   ins.runOnMainSync{AsyncInkRaster.clearMemoryCache();start=System.nanoTime();raster=AsyncInkRaster({callbacks++;if(first==0.0)first=(System.nanoTime()-start)/1e6});raster.draw(Canvas(bitmap),1000,1414,vp,1.0,false,false,decoded.strokes)}
   val end=System.nanoTime()+40_000_000_000L
   while(!complete&&System.nanoTime()<end){Thread.sleep(10);ins.runOnMainSync{complete=!raster.pending}}
   assertTrue("scene did not finish: $name",complete);val finalMs=(System.nanoTime()-start)/1e6
   val warm=InkPageFile.decode(bytes).strokes
   val times=mutableListOf<Double>()
   ins.runOnMainSync{repeat(120){val t=System.nanoTime();raster.draw(Canvas(bitmap),1000,1414,vp,1.0,false,false,warm);times+=(System.nanoTime()-t)/1e6}}
   val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
   val row=JSONObject().put("name",name).put("sha256",ContentTransfer.hash(bytes)).put("strokes",strokes.size).put("points",strokes.sumOf{it.samples.size}).put("objects",if(name=="mixed")8 else 0).put("pages",1).put("decodeMs",decodeMs).put("firstCompletePreviewMs",first).put("finalMs",finalMs).put("warmDrawP50Ms",times.sorted()[60]).put("warmDrawP95Ms",times.sorted()[114]).put("javaHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize()).put("pssKb",memory.totalPss)
   if(name=="mixed")ins.runOnMainSync{val objects=List(8){i->PageObject(id("object-$i"),PageObjectKind.TEXT,100f,100f+i*80,300f,60f,text="Fixture $i")};PageObjectPainter().draw(Canvas(bitmap),objects,false,CanvasBounds(0.0,0.0,1000.0,1414.0));row.put("objectSha256",ContentTransfer.hash(PageObjectCodec.encode(objects)))}
   rows.put(row);ins.runOnMainSync{raster.clear();AsyncInkRaster.clearMemoryCache()};bitmap.recycle()
  }
  rule.scenario.onActivity{a->val map=MindMapView(a);val cards=List(64){i->StudyCardRow(id("card-$i"),id("book"),1,"节点 $i","正文")};val nodes=List(64){i->StudyNodeRow(id("node-$i"),id("book"),cards[i].id,if(i==0)null else id("node-${(i-1)/2}"),(i%8)*260.0,(i/8)*128.0)}
   a.setContentView(FrameLayout(a).apply{addView(map)});map.show(nodes,cards);val started=System.nanoTime();map.layout(0,0,1000,1000);val b=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888);map.draw(Canvas(b));rows.put(JSONObject().put("name","map").put("nodes",64).put("cards",64).put("sha256",StudyGraph.orderHash(nodes.map{it.model()})).put("firstDrawMs",(System.nanoTime()-started)/1e6));b.recycle();repeat(20){map.postDelayed({map.zoom(if(it%2==0)1.1f else 1/1.1f)},it*20L)}}
  val context=ins.targetContext;val pdf=android.graphics.pdf.PdfDocument();val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,800,1).create());page.canvas.drawText("InkWeft PDF fixture",30f,60f,Paint().apply{textSize=24f});pdf.finishPage(page)
  val file=File(context.cacheDir,"integrated-perf.pdf");file.outputStream().use(pdf::writeTo);pdf.close();val pdfStart=System.nanoTime()
  ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->android.graphics.pdf.PdfRenderer(fd).use{r->r.openPage(0).use{pg->val b=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);pg.render(b,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);InkBrushes.renderer().draw(Canvas(b),InkBrushes.stroke(cases.first().second.first()),Matrix());b.recycle()}}}
  rows.put(JSONObject().put("name","pdf-annotation").put("pages",1).put("strokes",1).put("points",32).put("sha256",ContentTransfer.hash(file.readBytes())).put("renderMs",(System.nanoTime()-pdfStart)/1e6));file.delete()
  Thread.sleep(550)
  var hz=0f;rule.scenario.onActivity{hz=it.display?.refreshRate?:0f}
  val report=JSONObject().put("build","debug-component-probe").put("refreshRate",hz).put("scenes",rows).put("windowFrameCount",frames.size).put("frameTotalMs",JSONArray(frames)).put("frameGpuDurationMs",JSONArray(gpu)).put("gpuMemoryBytes",JSONObject.NULL).put("uiThreadCpuMs",JSONObject.NULL).put("renderThreadCpuMs",JSONObject.NULL).put("coldAppStartMs",JSONObject.NULL)
  runCatching{val cls=Class.forName("org.inkweft.app.RenderResources");val result=cls.getDeclaredMethod("snapshot").invoke(cls.getField("INSTANCE").get(null)) as Map<*,*>;report.put("renderResources",JSONObject(result))}
  File(context.getExternalFilesDir(null),"integrated-performance.json").writeText(report.toString(2));println("PERFORMANCE_REPORT="+report)
  }finally{rule.scenario.onActivity{it.window.removeOnFrameMetricsAvailableListener(listener)};thread.quitSafely()}
 }
}
