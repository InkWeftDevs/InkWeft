package org.inkweft.app

import android.graphics.*
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Release-like profileable component load, separate from user APK and real-pen claims. */
class ComputePerformanceProbe{
    @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
    @Test fun mixedPageUnderActualComputeLoad(){
        val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as InkWeftApplication
        require(app.packageName.endsWith(".performance")&&!BuildConfig.DEBUG)
        val condition=InstrumentationRegistry.getArguments().getString("compute")?:"baseline";require(condition in listOf("baseline","png","map","encryption"))
        fun id()=UUID.randomUUID().toString()
        val note=runBlocking{app.workspaceRepository.create("合成计算负载",false,PaperStyle.GRID)}
        val strokes=List(600){i->InkStroke(id(),InkPen.PENCIL,0xff224fa1.toInt(),4f,InkTool.STYLUS,List(32){j->InkSample(50f+i%20*43+j,50f+i/20*38+j%5,j*9L,.6f,.3f)})}
        runBlocking{strokes.chunked(200).forEachIndexed{i,batch->app.inkRepository.save(CommitInk(id(),note.id,i.toLong(),InkMutation.Replace(emptyList(),batch)))}}
        val text=strokes.take(16).mapIndexed{i,s->beautyObject(listOf(s),RecognizedWriting("条件概率与样本空间 ${i+1}",1f,1),BeautyOptions(),false,revision=3)}
        val events=CopyOnWriteArrayList<JSONObject>();val done=AtomicBoolean(false);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val jobs=CopyOnWriteArrayList<Job>();val lock=Mutex()
        var view:InkCanvasView?=null;var map:MindMapView?=null
        rule.scenario.onActivity{a->view=InkCanvasView(a).apply{configure(false,PaperStyle.GRID,null);allowInput=true;showStrokes(strokes);showObjects(text)
            onStroke={stroke->val queued=System.nanoTime();jobs.add(scope.launch{lock.withLock{
                val begin=System.nanoTime();events.add(JSONObject().put("kind","author-queue-wait").put("start",queued).put("end",begin))
                val page=app.inkRepository.read(note.id);val commit=System.nanoTime();events.add(JSONObject().put("kind","author-read").put("start",begin).put("end",commit))
                assertTrue(app.inkRepository.save(CommitInk(id(),note.id,page.revision,InkMutation.Add(stroke))) is InkCommitResult.Committed)
                events.add(JSONObject().put("kind","author-transaction").put("start",commit).put("end",System.nanoTime()))
            }})}}
            val frame=FrameLayout(a);frame.addView(view,FrameLayout.LayoutParams(800,1000));
            if(condition=="map"){map=MindMapView(a);frame.addView(map,FrameLayout.LayoutParams(800,700).apply{leftMargin=820});val cards=List(200){i->StudyCardRow(id(),note.id,1,"工程节点 $i","分支与来源说明")};val nodes=cards.mapIndexed{i,c->StudyNodeRow(id(),note.id,c.id,null,(i%10)*250.0,(i/10)*100.0)};map!!.show(nodes,cards)}
            a.setContentView(frame)
        }
        val bitmap=Bitmap.createBitmap(1600,1600,Bitmap.Config.ARGB_8888);val pixels=IntArray(1600*1600){0xff000000.toInt() or java.lang.Integer.rotateLeft(it*265443576,7)};bitmap.setPixels(pixels,0,1600,0,0,1600,1600)
        val png=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val manifest=JSONObject().put("format","inkweft.resource-pack.v1").put("id","example.compute-load").put("title","合成大图纸张").put("author","InkWeft").put("version",1)
            .put("files",JSONObject().put("paper.png",JSONObject().put("bytes",png.size).put("sha256",ContentTransfer.hash(png))))
            .put("resources",JSONArray().put(JSONObject().put("id","paper").put("title","大图纸张").put("type","paper").put("image","paper.png")))
        val archive=ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{z->for((name,bytes)in listOf("manifest.json" to manifest.toString().toByteArray(),"paper.png" to png)){z.putNextEntry(java.util.zip.ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()
        val plain=File(app.cacheDir,"compute.bin");plain.outputStream().use{out->val block=ByteArray(1048576);java.util.Random(46).nextBytes(block);repeat(16){out.write(block)}}
        val background=scope.launch{
            while(!done.get()){
                val begin=System.nanoTime();val cpu=Debug.threadCpuTimeNanos()
                when(condition){
                    "png"->{val pack=ResourcePackCodec.inspect(archive);val decoded=System.nanoTime();events.add(JSONObject().put("kind","png-decode-verify").put("start",begin).put("end",decoded).put("workerCpuNs",Debug.threadCpuTimeNanos()-cpu));BackgroundBudget.await(app);val ready=System.nanoTime();events.add(JSONObject().put("kind","resource-yield").put("start",decoded).put("end",ready));app.resourcePacks.install(pack);events.add(JSONObject().put("kind","install-verify-io").put("start",ready).put("end",System.nanoTime()))}
                    "encryption"->{val out=File(app.cacheDir,"compute-${id()}.iwbk");try{EncryptedBackupFile.encrypt(plain,out,note.id,ByteArray(32){7})}finally{out.delete()}}
                    else->delay(10)
                }
                events.add(JSONObject().put("kind",condition).put("start",begin).put("end",System.nanoTime()).put("workerCpuNs",if(condition=="encryption")Debug.threadCpuTimeNanos()-cpu else JSONObject.NULL));yield()
            }
        }
        try{
            val windowStart=System.nanoTime()
            repeat(12){i->
                val begin=System.nanoTime()
                rule.scenario.onActivity{a->val drawStart=System.nanoTime();events.add(JSONObject().put("kind","automation-dispatch-wait").put("start",begin).put("end",drawStart));val v=view!!;val now=SystemClock.uptimeMillis();val cpu=Debug.threadCpuTimeNanos()
                    repeat(3){j->val p=v.snapshotViewport().worldToScreen(250.0+j*25,700.0+i*12,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());val prop=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val point=MotionEvent.PointerCoords().apply{x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
                        MotionEvent.obtain(now,now+j*10,if(j==0)MotionEvent.ACTION_DOWN else if(j==2)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,1,arrayOf(prop),arrayOf(point),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0).also{v.dispatchTouchEvent(it);it.recycle()}}
                    val b=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));b.recycle()
                    if(map!=null){val m=Bitmap.createBitmap(800,700,Bitmap.Config.ARGB_8888);map!!.draw(Canvas(m));m.recycle()}
                    events.add(JSONObject().put("kind","main-input-draw").put("start",drawStart).put("end",System.nanoTime()).put("mainCpuNs",Debug.threadCpuTimeNanos()-cpu))
                }
                Thread.sleep(20);events.add(JSONObject().put("kind","automation-pacing").put("start",begin).put("end",System.nanoTime()))
            }
            done.set(true);runBlocking{background.join();jobs.forEach{it.join()}}
            assertEquals(612,runBlocking{app.inkRepository.read(note.id).strokes.size})
            val report=JSONObject().put("condition",condition).put("profileable",true).put("debuggable",BuildConfig.DEBUG).put("windowStart",windowStart).put("windowEnd",System.nanoTime()).put("samples",19200).put("naturalObjects",16).put("mapNodes",if(condition=="map")200 else 0).put("mapScope","engineering renderer sample; author graph limit remains 128").put("pngBytes",png.size).put("pngPixels",1600*1600).put("encryptedPlainBytes",plain.length()).put("networkWaitMs",0).put("events",JSONArray(events)).put("resources",JSONObject(RenderResources.snapshot()))
            File(app.getExternalFilesDir(null),"v46-compute-$condition.json").writeText(report.toString())
        }finally{done.set(true);scope.cancel();plain.delete()}
    }
}
