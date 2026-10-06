// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Opt-in observation of an already restored synthetic library. Never seeds, clears or repairs data. */
class ImageMemoryProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as InkWeftApplication
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private class Stop(val reason:String):RuntimeException(reason)
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun field(value:Any,name:String):Any? = value.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(value)
    private fun sha(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fileSha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true){val size=input.read(buffer);if(size<0)break;digest.update(buffer,0,size)} }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun <T> onMain(block:()->T):T {
        if(deadline>0&&now()>=deadline)throw Stop("TOTAL_WINDOW_LIMIT")
        val task=FutureTask<T>{block()}
        main.post(task)
        val allowance=if(deadline==0L)1500L else ((deadline-now())/1_000_000).coerceIn(1,750)
        return try { task.get(allowance,TimeUnit.MILLISECONDS) }
        catch(_:TimeoutException){main.removeCallbacks(task);task.cancel(false);throw Stop("MAIN_THREAD_NOT_RESPONDING_WITHIN_${allowance}MS")}
    }
    private fun views():List<InkCanvasView> {
        val out=mutableListOf<InkCanvasView>()
        fun visit(view:View){if(view is InkCanvasView&&!view.preview&&view.isShown&&view.isAttachedToWindow)out+=view
            if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))}
        visit(compose.activity.window.decorView);return out
    }
    private fun rect(value:Rect)=JSONArray(listOf(value.left,value.top,value.right,value.bottom))
    private fun viewport(value:CanvasViewport)=JSONObject().put("centerX",value.centerX).put("centerY",value.centerY).put("zoom",value.zoom)
    private fun bounds(value:CanvasBounds)=JSONArray(listOf(value.left,value.top,value.right,value.bottom))

    @Test fun observeRestoredSyntheticOriginalThroughScrollAndZoom() {
        val args=InstrumentationRegistry.getArguments()
        require(app.packageName.endsWith(".insertion")){"Image observation requires the isolated insertion package"}
        require(args.getString("imageProbeSample")=="condition-probability-12-v1"){"Explicit synthetic sample acknowledgement required"}
        val runId=requireNotNull(args.getString("imageProbeRunId")).also { require(it.matches(Regex("[a-z0-9-]{1,48}"))) }
        val directory=File(app.filesDir,"v74-closeout-image-probe/$runId")
        check(!directory.exists()&&directory.mkdirs()){ "Run directory must be new; previous evidence is never overwritten" }
        val report=JSONObject().put("status","PREFLIGHT").put("runId",runId)
            .put("scope","Production MainActivity and restored synthetic content; UI navigation and native zoomBy, no human pen claim")
            .put("assertionScope","Measurement capture and target author safety; an instrumentation success is not performance acceptance")
            .put("clock","SystemClock.elapsedRealtimeNanos").put("maximumMeasurementWindowMs",60_000)
            .put("package",app.packageName).put("appApkSha256",fileSha(File(app.applicationInfo.sourceDir)))
        val appBuild=Class.forName("org.inkweft.app.BuildConfig",false,app.classLoader)
        for(key in listOf("SOURCE_COMMIT","BUILD_COMMIT","VERSION_CODE","VERSION_NAME"))report.put(key,appBuild.getField(key).get(null))
        val info=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        report.put("signerSha256",JSONArray(info.signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }.orEmpty()))
        val stages=JSONArray();report.put("stages",stages)
        val reportFile=File(directory,"summary.json");fun save(){reportFile.writeText(report.toString(2))}
        save()

        val notes=runBlocking { app.repository.observeNotes().first() }
        val note=notes.single { it.title=="合成学习资料 · 条件概率十二讲" }
        val companion=notes.single { it.title=="合成知识本 · 复用与回忆" }
        val pages=runBlocking { app.pages.activePages(note.id) }
        val companionPages=runBlocking { app.pages.activePages(companion.id) }
        require(pages.size==12&&companionPages.size==1)
        val cards=runBlocking { app.study.cards(note.id).first().size+app.study.cards(companion.id).first().size }
        require(cards==96){"The specified full restored sample must still contain 96 cards"}
        val extraMap=runBlocking { app.knowledge.observeBook(note.id).first() }.single {
            !it.removed&&(it.data() as? KnowledgeData.MapDefinition)?.title=="同一内容的第二种组织"
        }.id
        val graphs=runBlocking { listOf(app.study.readGraph(note.id,null),app.study.readGraph(note.id,extraMap),app.study.readGraph(companion.id,null)) }
        require(graphs.all { graph->graph.nodes.count { !it.removed }==120 })
        val page=pages.first().id
        val objectsBefore=runBlocking { app.pageObjects.read(page) }
        val original=objectsBefore.objects.single { it.kind==PageObjectKind.IMAGE&&it.imageSource!=null }
        require(original.x==80f&&original.y==320f&&original.width==840f&&original.height==630f)
        val originalBytes=runBlocking { checkNotNull(app.pageObjects.originalSize(page,checkNotNull(original.imageSource))) }
        val document=runBlocking { checkNotNull(app.documents.read(page)) }
        require(document.page==0)
        val authorBefore=runBlocking { PageAuthoringCodec.fingerprint(app.authoring.readPage(page).state) }
        report.put("sample",JSONObject().put("title",note.title).put("pages",12).put("companionPages",1).put("cards",cards)
            .put("mapActiveNodes",JSONArray(graphs.map { g->g.nodes.count { !it.removed } })).put("originalSha256",original.imageSource)
            .put("originalCompressedBytes",originalBytes).put("expectedRawWidth",4096).put("expectedRawHeight",3072)
            .put("documentSha256",document.document.sha256).put("originalSourceReadDuringPreflight",false))
        val prefs=app.getSharedPreferences("inkweft-reading",0);val key="continuous-v20-${note.id}"
        val hadContinuous=prefs.contains(key);val priorContinuous=prefs.getBoolean(key,true)
        lateinit var notebook:NotebookViewModel
        lateinit var lock:BookReadLockViewModel
        var priorReadOnly=false
        val rows=File(directory,"samples.ndjson")
        val peaks=linkedMapOf<String,Long>()
        var anyDeferred=false;var sampleCount=0;var originalViewport:CanvasViewport?=null
        var measurementStart=0L;var lastRow:JSONObject?=null;var failure:Throwable?=null
        val power=app.getSystemService(PowerManager::class.java)
        fun sample(phase:String):JSONObject {
            if(now()>=deadline)throw Stop("TOTAL_WINDOW_LIMIT")
            // StateFlow changes need a test-clock frame before Android can lay out/draw the new Compose tree.
            compose.mainClock.advanceTimeByFrame()
            val row=onMain {
                val view=views().firstOrNull { it.embeddedPage&&field(it,"documentId")==page&&it.getLocalVisibleRect(Rect()) }
                val value=JSONObject().put("atNanos",now()).put("phase",phase).put("targetVisible",view!=null)
                if(view!=null){
                    val local=Rect();view.getLocalVisibleRect(local);local.intersect(0,0,view.width,view.height)
                    val global=Rect();view.getGlobalVisibleRect(global)
                    val vp=view.snapshotViewport();val density=view.resources.displayMetrics.density.toDouble()
                    val renderer=checkNotNull(field(view,"imageRendering")) as ImageRendering
                    @Suppress("UNCHECKED_CAST") val wanted=field(renderer,"wanted") as List<Any>
                    @Suppress("UNCHECKED_CAST") val frames=field(renderer,"frames") as Map<String,Any>
                    val request=wanted.firstOrNull { field(checkNotNull(field(it,"key")),"id")==original.id }
                    val requestKey=request?.let { field(it,"key") }
                    val savedKey=frames[original.id]?.let { field(it,"key") }
                    val frame=renderer.frame(original)
                    val matching=requestKey!=null&&requestKey==savedKey&&frame!=null&&frame.rawWidth==4096&&frame.rawHeight==3072
                    val drawn=matching&&!renderer.pending&&field(renderer,"failedRequest")!=true&&!view.rasterPending&&view.sourceContentReady&&
                        view.hasDrawnSourceFrame(page,vp,view.width,view.height,density)
                    value.put("viewWidthPx",view.width).put("viewHeightPx",view.height).put("density",density)
                        .put("localVisibleRect",rect(local)).put("screenVisibleRect",rect(global)).put("viewport",viewport(vp))
                        .put("imagePending",renderer.pending).put("budgetDeferred",renderer.budgetDeferred).put("decodeCount",renderer.decodeCount)
                        .put("failedRequest",field(renderer,"failedRequest")).put("matchingRequestAndFrame",matching).put("completeMatchingDrawnFrame",drawn)
                        .put("rasterPending",view.rasterPending).put("sourceContentReady",view.sourceContentReady).put("authorInputEnabled",view.allowInput)
                        .put("documentGeneration",field(view,"documentGeneration")).put("completedDocumentGeneration",field(view,"completedDocumentGeneration"))
                        .put("drawnDocumentGeneration",field(view,"drawnDocumentGeneration")).put("documentRequest",field(view,"documentRequest"))
                        .put("documentJobActive",(field(view,"documentJob") as? kotlinx.coroutines.Job)?.isActive==true)
                    requestKey?.let { k->value.put("requestedCrop",bounds(field(k,"crop") as CanvasBounds)).put("requestedFullWidthPx",field(k,"width")).put("requestedFullHeightPx",field(k,"height")) }
                    frame?.let { f->value.put("frame",JSONObject().put("source",f.source).put("sourcePixelRegion",rect(f.region)).put("rawWidth",f.rawWidth).put("rawHeight",f.rawHeight)
                        .put("decodedWidth",f.bitmap.width).put("decodedHeight",f.bitmap.height).put("allocationBytes",f.bitmap.allocationByteCount).put("orientation",f.orientation)) }
                }
                value.put("renderResources",JSONObject(RenderResources.snapshot()))
            }
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            val measured=mapOf("pssKiB" to memory.totalPss.toLong(),"javaUsedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),
                "nativeAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),"trackedBytes" to row.getJSONObject("renderResources").getLong("totalBytes"))
            measured.forEach { (key,value)->row.put(key,value);peaks[key]=maxOf(peaks[key]?:0,value) }
            val resources=row.getJSONObject("renderResources");val resourceKeys=resources.keys()
            while(resourceKeys.hasNext()){
                val key=resourceKeys.next()
                if(key!="peakTrackedBytes")peaks["render.$key"]=maxOf(peaks["render.$key"]?:0,resources.getLong(key))
            }
            row.put("thermalStatus",power.currentThermalStatus).put("sampleFinishedNanos",now())
            anyDeferred=anyDeferred||row.optBoolean("budgetDeferred");sampleCount++;lastRow=row
            rows.appendText(row.toString()+"\n");return row
        }
        fun geometry(row:JSONObject)=listOf(row.opt("viewport"),row.opt("localVisibleRect"),row.opt("viewWidthPx"),row.opt("viewHeightPx"),row.opt("requestedCrop")).joinToString("|")
        fun awaitFrame(phase:String,inputEnded:Long,priorGeometry:String?=null) {
            val stage=JSONObject().put("name",phase).put("inputEndedNanos",inputEnded).put("status","WAITING")
            stages.put(stage);save()
            val until=minOf(deadline,inputEnded+12_000_000_000L)
            var stableGeometry:String?=null;var firstMatching=0L
            while(now()<until){
                val row=sample(phase)
                val currentGeometry=geometry(row)
                if(row.optBoolean("completeMatchingDrawnFrame")&&(priorGeometry==null||currentGeometry!=priorGeometry)){
                    if(stableGeometry==currentGeometry){
                        stage.put("status","COMPLETE_MATCHING_FRAME").put("firstObservedMatchingDrawnNanos",firstMatching)
                            .put("recoveryMs",(firstMatching-inputEnded)/1_000_000.0).put("samplingResolutionMs",200)
                            .put("stableThroughNanos",row.getLong("atNanos")).put("observedGeometryChanged",priorGeometry!=null)
                        save();return
                    }
                    stableGeometry=currentGeometry;firstMatching=row.getLong("atNanos")
                }else{stableGeometry=null;firstMatching=0L}
                Thread.sleep(200)
            }
            stage.put("status","WAIT_LIMIT_REACHED").put("endedNanos",now());save()
            if(lastRow?.optBoolean("targetVisible")!=true)throw Stop("$phase:NO_VISIBLE_TARGET_WITHIN_12S")
        }
        fun nativeView()=views().single { it.embeddedPage&&field(it,"documentId")==page&&it.getLocalVisibleRect(Rect()) }
        fun scrollOnce() {
            val area=onMain { val view=nativeView();check(lock.readOnly.value&&!view.allowInput);Rect().also { check(view.getGlobalVisibleRect(it)) } }
            val down=SystemClock.uptimeMillis();val x=area.centerX().toFloat();val y=area.top+area.height()*.65f
            // A short finger drag in a production read-only continuous list; never a pen event.
            for(i in 0..8){
                if(now()>=deadline)throw Stop("TOTAL_WINDOW_LIMIT")
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),when(i){0->MotionEvent.ACTION_DOWN;8->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},
                    x,y-area.height()*.08f*i/8f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                try { check(instrumentation.uiAutomation.injectInputEvent(event,false)) } finally { event.recycle() }
                if(i<8)Thread.sleep(60)
            }
        }
        try {
            check(prefs.edit().putBoolean(key,true).commit())
            compose.runOnIdle {
                val provider=ViewModelProvider(compose.activity)
                notebook=provider[NotebookViewModel::class.java];notebook.back()
                lock=ViewModelProvider(compose.activity,BookReadLockViewModel.Factory())["read-lock-${note.id}",BookReadLockViewModel::class.java]
                priorReadOnly=lock.readOnly.value;check(lock.request(true))
            }
            compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
            runBlocking { app.pages.select(note.id,page) }
            report.put("setup",JSONArray(listOf("Existing sample identified by explicit opt-in, titles, counts, image geometry and source hashes",
                "Returned to library; enabled continuous reading for this synthetic book; selected its first page; activated production read lock",
                "No library creation/deletion, original decoding, cache trim, memory pressure injection or system setting change")))
            report.put("status","MEASURING").put("resourcesBeforeWindow",JSONObject(RenderResources.snapshot()));save()
            measurementStart=now();deadline=measurementStart+55_000_000_000L
            report.put("measurementStartNanos",measurementStart)
            onMain { notebook.select(note) }
            awaitFrame("open-first-continuous-page",now())
            originalViewport=onMain { nativeView().snapshotViewport() }
            val beforeScroll=geometry(sample("before-scroll"))
            scrollOnce();awaitFrame("short-continuous-scroll",now(),beforeScroll)
            repeat(5){sample("stationary-after-scroll");Thread.sleep(200)}
            val beforeZoom=geometry(sample("before-zoom"))
            onMain { nativeView().zoomBy(1.7) };awaitFrame("native-zoom-in-1.7",now(),beforeZoom)
            repeat(5){sample("stationary-after-zoom");Thread.sleep(200)}
            val beforeZoomOut=geometry(sample("before-zoom-out"))
            onMain { nativeView().zoomBy(1.0/1.7) };awaitFrame("native-zoom-out",now(),beforeZoomOut)
            report.put("status",if((0 until stages.length()).all{stages.getJSONObject(it).getString("status")=="COMPLETE_MATCHING_FRAME"})"MEASURED_ALL_FOUR_PHASES" else "MEASURED_INCOMPLETE_PHASES")
        } catch(stop:Stop){report.put("status","MEASURED_STOPPED_AT_BOUND").put("stopReason",stop.reason)}
        catch(error:Throwable){failure=error;report.put("status","PROBE_ERROR").put("errorType",error.javaClass.simpleName)}
        finally {
            val ended=now();deadline=0
            report.put("measurementEndNanos",ended).put("measurementElapsedMs",if(measurementStart==0L)0 else (ended-measurementStart)/1_000_000.0)
                .put("sampleCount",sampleCount).put("anyBudgetDeferredObserved",anyDeferred).put("sampledWindowPeaks",JSONObject(peaks as Map<*,*>))
                .put("peakScope","Max of observed samples; PSS/heap may miss sub-sample peaks. RenderResources is logical retention, not GPU memory; its peakTrackedBytes is process lifetime.")
                .put("lastSample",lastRow?:JSONObject.NULL)
                .put("zoomMethod","Actual InkCanvasView.zoomBy within the production continuous-page view; not a synthesized pinch gesture")
            save()
            // The screenshot is deliberately outside memory/timing sampling to avoid measuring capture allocations.
            runCatching { onMain { check(ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId==note.id) }
                val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(directory,"final-synthetic-view.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
            }.onFailure { report.put("screenshotError",it.javaClass.simpleName) }
            runCatching { originalViewport?.let { vp->onMain { views().firstOrNull { it.embeddedPage&&field(it,"documentId")==page }?.let { v->v.zoomBy(vp.zoom/v.snapshotViewport().zoom) } } }
                onMain { notebook.back();check(lock.request(priorReadOnly)) }
            }.onFailure { report.put("uiRestoreError",it.javaClass.simpleName) }
            val editor=prefs.edit();if(hadContinuous)editor.putBoolean(key,priorContinuous)else editor.remove(key)
            report.put("continuousPreferenceRestored",editor.commit())
            runCatching {
                assertEquals(objectsBefore,runBlocking { app.pageObjects.read(page) })
                assertEquals(authorBefore,runBlocking { PageAuthoringCodec.fingerprint(app.authoring.readPage(page).state) })
                report.put("targetObjectsAndAuthoringUnchanged",true)
            }.onFailure { report.put("targetObjectsAndAuthoringUnchanged",false);failure=it }
            save()
        }
        failure?.let { throw it }
    }
}
