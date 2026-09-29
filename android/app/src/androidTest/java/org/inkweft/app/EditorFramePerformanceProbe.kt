package org.inkweft.app

import android.graphics.Bitmap
import android.os.*
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in real-editor measurements. Only the separate, empty performance application is accepted. */
class EditorFramePerformanceProbe {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private fun id(value:String) = UUID.nameUUIDFromBytes("editor-frame-v1:$value".toByteArray()).toString()
    private inline fun <reified T:View> view():T {
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()) {
            val item=queue.removeFirst()
            if(item is T && item.isShown && (item !is InkCanvasView || !item.preview&&!item.embeddedPage)) return item
            if(item is ViewGroup) for(i in 0 until item.childCount) queue.add(item.getChildAt(i))
        }
        error("Visible ${T::class.java.simpleName} missing")
    }
    private data class Frame(val metrics:FrameMetrics,val dropped:Int)

    @Test fun realEditorUnderBoundedComputeLoad() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val runId=requireNotNull(args.getString("runId")).also{require(it.matches(Regex("[a-z0-9-]{1,48}")))}
        val condition=args.getString("perfCondition")?:"writing"
        require(condition in listOf("writing","map","encryption","png"))
        val actualFlags=app.applicationInfo.flags
        val debuggable=actualFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val profileable=app.applicationInfo.isProfileableByShell
        require(app.packageName.endsWith(".performance")&&!debuggable&&profileable)
        // A new instrumentation APK may run against the old baseline app; do not
        // inline the instrumentation compiler's BuildConfig source as the app's identity.
        val source=Class.forName("org.inkweft.app.BuildConfig",false,app.classLoader).getField("SOURCE_COMMIT").get(null) as String
        require(runBlocking{app.repository.observeNotes().first().isEmpty()}){"Empty performance application required"}
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("真实编辑器性能合成样本",false,PaperStyle.GRID)}
        val strokes=List(600){i->InkStroke(id("stroke-$i"),InkPen.PENCIL,0xff224fa1.toInt(),4f,InkTool.STYLUS,
            List(32){j->InkSample(50f+i%20*43+j,50f+i/20*38+j%5,j*9L,.6f,.3f)},
            appearance=StrokeAppearance(BrushRecipe(),123L+i,0f,0f))}
        val seedDigest=ContentTransfer.hash(InkPageFile("editor-frame-seed","",strokes.sortedBy{it.id},false,PaperStyle.GRID).encode())
        val objects=strokes.take(16).mapIndexed{i,stroke->
            beautyObject(listOf(stroke),RecognizedWriting("条件概率与样本空间 ${i+1}",1f,1),BeautyOptions(),false,id=id("object-$i"),revision=3)
                .let{value->value.copy(textRuns=value.textRuns.mapIndexed{j,run->run.copy(id=id("run-$i-$j"),lineId=id("line-$i"))})}
        }
        runBlocking {
            strokes.chunked(200).forEachIndexed{i,batch->assertTrue(app.inkRepository.save(CommitInk(id("seed-$i"),note.id,i.toLong(),InkMutation.Replace(emptyList(),batch))) is InkCommitResult.Committed)}
            app.pageObjects.save(note.id,0,id("objects"),objects,3)
            repeat(64){i->app.study.submit(StudyCommand(id("create-node-$i"),note.id,StudyAction.CREATE,
                cardId=id("card-$i"),nodeId=id("node-$i"),parentId=if(i==0)null else id("node-${(i-1)/2}"),
                title="条件概率 ${i+1}",body="P(A | B) 与样本空间",x=(i%8)*260.0,y=(i/8)*128.0))}
        }
        app.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${note.id}",false).commit()
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.waitForSavedInk();compose.frameCanvasFixture()
        if(condition=="map") {
            compose.onNodeWithTag("quick-study").performClick()
            compose.waitUntil(15000){ViewModelProvider(compose.activity)["study-${note.id}",StudyViewModel::class.java].ui.value.nodes.size==64}
            compose.onNodeWithTag("study-panel").assertIsDisplayed()
            compose.runOnIdle{repeat(64){assertNotNull(view<MindMapView>().nodeBounds(id("node-$it")))}}
        }
        // Same legal PNG and 16 MiB input as ComputePerformanceProbe, prepared outside every window.
        val bitmap=Bitmap.createBitmap(1600,1600,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(1600*1600){0xff000000.toInt() or Integer.rotateLeft(it*265443576,7)},0,1600,0,0,1600,1600)
        val png=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val manifest=JSONObject().put("format","inkweft.resource-pack.v1").put("id","example.editor-frame-load")
            .put("title","合成大图纸张").put("author","InkWeft").put("version",1)
            .put("files",JSONObject().put("paper.png",JSONObject().put("bytes",png.size).put("sha256",ContentTransfer.hash(png))))
            .put("resources",JSONArray().put(JSONObject().put("id","paper").put("title","大图纸张").put("type","paper").put("image","paper.png")))
        val archive=ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{zip->
            for((name,bytes)in listOf("manifest.json" to manifest.toString().toByteArray(),"paper.png" to png)) {
                zip.putNextEntry(java.util.zip.ZipEntry(name).apply{time=0});zip.write(bytes);zip.closeEntry()
            }
        }}.toByteArray()
        ResourcePackCodec.inspect(archive) // Validate the shared fixture even in the baseline.
        val plain=File(app.cacheDir,"editor-frame-$runId.bin")
        plain.outputStream().use{out->val block=ByteArray(1024*1024);java.util.Random(46).nextBytes(block);repeat(16){out.write(block)}}
        val rasterDeadline=System.nanoTime()+40_000_000_000L
        while(true) {
            var ready=false;instrumentation.runOnMainSync{ready=!view<InkCanvasView>().rasterPending}
            if(ready)break
            check(System.nanoTime()<rasterDeadline){"Initial editor raster did not settle"};Thread.sleep(20)
        }
        val events=ConcurrentLinkedQueue<JSONObject>();val frames=ConcurrentLinkedQueue<Frame>()
        val handler=HandlerThread("editor-frame-metrics").apply{start()}
        val listener=Window.OnFrameMetricsAvailableListener{_,metrics,dropped->frames.add(Frame(FrameMetrics(metrics),dropped))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val done=AtomicBoolean(false)
        val prefix="InkWeft.perf.$runId"
        val report=JSONObject().put("status","FAIL").put("runId",runId).put("condition",condition)
            .put("package",app.packageName).put("source",source).put("profileable",profileable).put("debuggable",debuggable).put("actualAppFlags",actualFlags)
            .put("fixtureSha256",ContentTransfer.hash(InkPageFile("editor-frame-v1","",strokes,false,PaperStyle.GRID,objects).encode()))
            .put("pngSha256",ContentTransfer.hash(png)).put("pngBytes",png.size).put("pngPixels",1600*1600).put("encryptedPlainBytes",plain.length())
            .put("seedStrokes",600).put("naturalObjects",16).put("authorMapNodes",64).put("mapVisible",condition=="map")
            .put("tracePrefix",prefix).put("clock","System.nanoTime / Android monotonic; elapsedRealtime anchor also recorded")
            .put("measurementScope","Real editor injected stylus and persisted save; no human pen latency claim")
        compose.runOnIdle {
            val canvas=view<InkCanvasView>();val viewport=canvas.snapshotViewport();val configuration=canvas.resources.configuration
            report.put("viewport",JSONObject().put("centerX",viewport.centerX).put("centerY",viewport.centerY).put("zoom",viewport.zoom))
                .put("canvasWidthPx",canvas.width).put("canvasHeightPx",canvas.height).put("density",canvas.resources.displayMetrics.density)
                .put("fontScale",configuration.fontScale).put("refreshRate",compose.activity.display?.refreshRate?:0f)
                .put("windowWidthPx",compose.activity.window.decorView.width).put("windowHeightPx",compose.activity.window.decorView.height)
            compose.activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handler.looper))
        }
        var worker:Deferred<Unit>?=null
        var windowStart=0L;var windowEnd=0L
        try {
            if(condition in listOf("encryption","png")) {
                worker=scope.async {
                    var cycle=0
                    while(!done.get()) {
                        val start=System.nanoTime();val cpu=Debug.threadCpuTimeNanos()
                        val cookie=cycle++;Trace.beginAsyncSection("$prefix.$condition",cookie)
                        try {
                            if(condition=="encryption") {
                                val cipher=File(app.cacheDir,"editor-frame-$runId.iwbk")
                                try{EncryptedBackupFile.encrypt(plain,cipher,note.id,ByteArray(32){7})}finally{cipher.delete()}
                            } else {
                                val pack=ResourcePackCodec.inspect(archive)
                                events.add(JSONObject().put("kind","png-decode-verify").put("start",start).put("end",System.nanoTime()).put("workerCpuNs",Debug.threadCpuTimeNanos()-cpu))
                                BackgroundBudget.await(app)
                                app.resourcePacks.install(pack)
                            }
                        } finally {Trace.endAsyncSection("$prefix.$condition",cookie)}
                        events.add(JSONObject().put("kind",condition).put("start",start).put("end",System.nanoTime()).put("workerCpuNs",if(condition=="encryption")Debug.threadCpuTimeNanos()-cpu else JSONObject.NULL))
                        yield()
                    }
                }
                val readyDeadline=System.nanoTime()+30_000_000_000L
                while(events.none{it.getString("kind")==condition}) {
                    worker?.let{task->if(task.isCompleted)runBlocking{task.await()}}
                    check(System.nanoTime()<readyDeadline){"Background compute did not start"};Thread.sleep(10)
                }
            }
            windowStart=System.nanoTime();report.put("windowStart",windowStart).put("elapsedRealtimeStart",SystemClock.elapsedRealtimeNanos())
            Trace.beginAsyncSection("$prefix.window",1)
            repeat(24){strokeIndex->
                worker?.let{assertTrue("Background compute ended before input",it.isActive)}
                val revision=runBlocking{app.inkRepository.read(note.id).revision}
                val start=System.nanoTime();val down=SystemClock.uptimeMillis()
                Trace.beginAsyncSection("$prefix.save",strokeIndex)
                try {
                    repeat(8){pointIndex->
                        instrumentation.runOnMainSync {
                            Trace.beginSection("$prefix.input")
                            try {
                                val canvas=view<InkCanvasView>();canvas.pen=InkPen.PENCIL
                                val point=canvas.snapshotViewport().worldToScreen(140.0+pointIndex*12,450.0+strokeIndex*15,canvas.width.toDouble(),canvas.height.toDouble(),canvas.resources.displayMetrics.density.toDouble())
                                val properties=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                                val coords=MotionEvent.PointerCoords().apply{x=point.x.toFloat();y=point.y.toFloat();pressure=.6f}
                                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),when(pointIndex){0->MotionEvent.ACTION_DOWN;7->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                                try{assertTrue(canvas.dispatchTouchEvent(event))}finally{event.recycle()}
                            } finally {Trace.endSection()}
                        }
                        if(pointIndex<7)Thread.sleep(16)
                    }
                    val deadline=System.nanoTime()+15_000_000_000L
                    while(runBlocking{app.inkRepository.read(note.id).revision}==revision) {
                        check(System.nanoTime()<deadline){"Ink save did not commit"};Thread.sleep(10)
                    }
                } finally {Trace.endAsyncSection("$prefix.save",strokeIndex)}
                events.add(JSONObject().put("kind","input-and-confirmed-save").put("start",start).put("end",System.nanoTime()))
            }
            windowEnd=System.nanoTime();report.put("windowEnd",windowEnd)
            Trace.endAsyncSection("$prefix.window",1)
            done.set(true);runBlocking{worker?.await()}
            if(worker!=null) {
                val work=events.filter{it.getString("kind")==condition}
                assertTrue(events.filter{it.getString("kind")=="input-and-confirmed-save"}.all{input->
                    work.any{it.getLong("start")<input.getLong("end")&&it.getLong("end")>input.getLong("start")}
                })
                report.put("computeOverlapsEveryInput",true)
            }
            val saved=runBlocking{app.inkRepository.read(note.id)}
            val seedIds=strokes.map{it.id}.toSet()
            val persistedSeed=saved.strokes.filter{it.visible&&it.stroke.id in seedIds}.map{it.stroke}.sortedBy{it.id}
            val persistedDigest=ContentTransfer.hash(InkPageFile("editor-frame-seed","",persistedSeed,false,PaperStyle.GRID).encode())
            report.put("seedContentBeforeSha256",seedDigest).put("seedContentAfterSha256",persistedDigest)
            assertEquals(624,saved.strokes.size);assertEquals("Stored author ink changed",seedDigest,persistedDigest)
            assertEquals(64,runBlocking{app.study.nodes(note.id).first().size})
            assertEquals(objects,runBlocking{app.pageObjects.read(note.id).objects})
            compose.waitForIdle();Thread.sleep(100)
            assertTrue("No rendered frames captured",frames.any{it.metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) in windowStart..windowEnd})
            report.put("status","PASS").put("savedStrokes",saved.strokes.size)
        } finally {
            done.set(true);runBlocking{worker?.cancelAndJoin()};scope.cancel()
            if(windowStart>0&&windowEnd==0L)Trace.endAsyncSection("$prefix.window",1)
            instrumentation.runOnMainSync{compose.activity.window.removeOnFrameMetricsAvailableListener(listener)}
            handler.quitSafely();handler.join(3000)
            val metrics=linkedMapOf("intendedVsyncNs" to FrameMetrics.INTENDED_VSYNC_TIMESTAMP,"vsyncNs" to FrameMetrics.VSYNC_TIMESTAMP,
                "totalNs" to FrameMetrics.TOTAL_DURATION,"inputNs" to FrameMetrics.INPUT_HANDLING_DURATION,"layoutNs" to FrameMetrics.LAYOUT_MEASURE_DURATION,
                "drawNs" to FrameMetrics.DRAW_DURATION,"syncNs" to FrameMetrics.SYNC_DURATION,"gpuNs" to FrameMetrics.GPU_DURATION,"deadlineNs" to FrameMetrics.DEADLINE,
                "unknownDelayNs" to FrameMetrics.UNKNOWN_DELAY_DURATION,"firstDraw" to FrameMetrics.FIRST_DRAW_FRAME)
            report.put("events",JSONArray(events.toList())).put("frames",JSONArray(frames.map{frame->
                JSONObject().put("droppedReports",frame.dropped).also{row->metrics.forEach{(name,key)->row.put(name,frame.metrics.getMetric(key))}}
            })).put("droppedReports",frames.sumOf{it.dropped})
            File(app.getExternalFilesDir(null),"editor-frame-$runId.json").writeText(report.toString(2));plain.delete()
        }
    }
}
