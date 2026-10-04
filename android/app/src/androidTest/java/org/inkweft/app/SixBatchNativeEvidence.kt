// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only observations of the actual attached view, never a replacement renderer or VM injection. */
internal class SixBatchNativeEvidence(
    private val compose:AndroidComposeTestRule<ActivityScenarioRule<MainActivity>,MainActivity>,
) {
    private val app get()=compose.activity.application as InkWeftApplication
    private fun field(name:String)=InkCanvasView::class.java.getDeclaredField(name).apply{isAccessible=true}
    private val pageField=field("documentId")
    private val tileField=field("documentTile")
    private val strokesField=field("content")
    private val authoringField=field("authoring")
    private fun native(page:String,continuous:Boolean):InkCanvasView? {
        fun find(view:View):InkCanvasView? {
            if(view is InkCanvasView&&!view.preview&&view.embeddedPage==continuous&&view.isShown&&
                pageField.get(view)==page&&view.getGlobalVisibleRect(Rect()))return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        return find(compose.activity.window.decorView)
    }
    private fun drawn(view:InkCanvasView,page:String)=tileField.get(view)!=null&&view.documentContentReady&&view.sourceContentReady&&
        !view.rasterPending&&!view.imageFramesPending&&view.hasDrawnSourceFrame(page,view.snapshotViewport(),view.width,view.height,
            view.resources.displayMetrics.density.toDouble())

    private fun inkFingerprint(strokes:List<InkStroke>):String {
        val digest=MessageDigest.getInstance("SHA-256")
        strokes.forEach{digest.update(InkStrokeCodec.encode(it))}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }

    fun savedPageFingerprint(page:String):Pair<String,String> = runBlocking {
        PageAuthoringCodec.fingerprint(app.authoring.readPage(page).state) to
            inkFingerprint(InkSession(app.inkRepository.read(page)).visibleDraft())
    }

    /** Compose idle/loaded DB rows do not imply that asynchronous PDF/ink/image pixels were drawn. */
    fun awaitPage(f:SixBatchFixture,index:Int,continuous:Boolean):InkCanvasView {
        val page=f.documentPages[index]
        val source=runBlocking{checkNotNull(app.documents.read(page))}
        assertEquals(index,source.page);assertEquals(f.manifest.getString("documentSha256"),source.document.sha256)
        val expectedInk=inkFingerprint(runBlocking{InkSession(app.inkRepository.read(page)).visibleDraft()})
        val expectedAuthoring=runBlocking{app.authoring.readPage(page).state}
        val fingerprint=PageAuthoringCodec.fingerprint(expectedAuthoring)
        var observedAuthoring:PageAuthoring?=null;var observedFingerprint:String?=null
        var observedInk:List<InkStroke>?=null;var observedInkFingerprint:String?=null
        var result:InkCanvasView?=null
        compose.waitUntil("Native source page ${index+1}: correct identity, complete PDF/ink/image frame",60_000){compose.runOnIdle {
            val provider=ViewModelProvider(compose.activity)
            val selected=provider["book-${f.books[0]}",BookPagesViewModel::class.java].ui.value.selectedId
            val view=native(page,continuous)
            val actualAuthoring=view?.let{authoringField.get(it) as? PageAuthoring}
            if(actualAuthoring!==observedAuthoring){observedAuthoring=actualAuthoring;observedFingerprint=actualAuthoring?.let(PageAuthoringCodec::fingerprint)}
            @Suppress("UNCHECKED_CAST") val actualInk=view?.let{strokesField.get(it) as List<InkStroke>}
            if(actualInk!==observedInk){observedInk=actualInk;observedInkFingerprint=actualInk?.let(::inkFingerprint)}
            val ready=provider[NotebookViewModel::class.java].ui.value.selectedId==f.books[0]&&selected==page&&
                app.navigationReady.value&&view!=null&&drawn(view,page)&&observedFingerprint==fingerprint&&observedInkFingerprint==expectedInk
            if(ready)result=view
            ready
        }}
        return checkNotNull(result)
    }

    /** onDraw records commands; screenshot evidence must wait for their actual hardware frame submission. */
    private fun awaitCommittedFrame(view:InkCanvasView,page:String){
        val committed=AtomicBoolean(false)
        compose.runOnIdle {
            assertTrue("Native screenshot evidence requires a hardware-accelerated window",view.isHardwareAccelerated)
            assertTrue("The source frame changed before frame submission",drawn(view,page))
            view.viewTreeObserver.registerFrameCommitCallback{committed.set(true)}
            view.invalidate()
        }
        compose.waitUntil("Complete native source frame submitted to the window",15_000){committed.get()}
    }

    fun capturePage(f:SixBatchFixture,name:String,index:Int,continuous:Boolean,pressure:Boolean=false){
        val page=f.documentPages[index];val file=File(f.root,"$name.png")
        val observations=JSONObject();var bitmap:Bitmap?=null;var pixelsSaved=false;var stage="await-native-frame"
        fun savePixels(value:Bitmap){file.outputStream().use{check(value.compress(Bitmap.CompressFormat.PNG,100,it))};pixelsSaved=true}
        try{
            val view=awaitPage(f,index,continuous)
            stage="await-frame-commit";awaitCommittedFrame(view,page)
            stage="capture-window";val pixels=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            bitmap=pixels;savePixels(pixels)
            stage="assert-native-pixels"
            val proof=compose.runOnIdle {
                val location=IntArray(2);view.getLocationOnScreen(location)
                val viewport=view.snapshotViewport()
                observations.put("bitmapWidth",pixels.width).put("bitmapHeight",pixels.height)
                    .put("viewLeft",location[0]).put("viewTop",location[1]).put("viewWidth",view.width).put("viewHeight",view.height)
                    .put("viewportCenterX",viewport.centerX).put("viewportCenterY",viewport.centerY).put("viewportZoom",viewport.zoom)
                    .put("density",view.resources.displayMetrics.density).put("frameCommitted",true)
                assertTrue("The source frame changed during capture",drawn(view,page))
                @Suppress("UNCHECKED_CAST") val ink=strokesField.get(view) as List<InkStroke>
                val state=checkNotNull(authoringField.get(view) as? PageAuthoring)
                observations.put("pageId",page).put("sourcePage",index+1).put("documentSha256",f.manifest.getString("documentSha256"))
                    .put("nativeInkSha256",inkFingerprint(ink)).put("nativeStoredStrokes",ink.size).put("nativeStoredPoints",ink.sumOf{it.samples.size})
                    .put("nativeVisibleStrokes",ink.count{state.layers.visible(LayerContent(LayerContentKind.INK,it.id))})
                    .put("authoringFingerprint",PageAuthoringCodec.fingerprint(state)).put("layers",state.layers.layers.size)
                    .put("pdfTilePresent",true).put("sourceFrameDrawn",true).put("pendingRaster",false).put("pendingImages",false)
                    .put("continuous",continuous).also{if(pressure){
                        assertFalse("All pressure rows must be framed in the single-page view",continuous)
                        assertEquals(1000,ink.size);assertEquals(100000,ink.sumOf{s->s.samples.size})
                        assertEquals(1000,ink.count{s->state.layers.visible(LayerContent(LayerContentKind.INK,s.id))})
                        assertEquals(900,ink.count{s->state.layers.editable(LayerContent(LayerContentKind.INK,s.id))})
                        val base=bluePixels(pixels,view,CanvasBounds(10.0,14.0,986.0,60.0),observations,"base")
                        val locked=bluePixels(pixels,view,CanvasBounds(10.0,1182.0,986.0,1310.0),observations,"locked")
                        it.put("baseLayerBluePixels",base).put("lockedLayerBluePixels",locked)
                        assertTrue("No actual base-layer pressure ink pixels: $base",base>=20)
                        assertTrue("No actual locked-layer pressure ink pixels: $locked",locked>=20)
                    }}
            }
            record(f,name,proof)
        }catch(error:Throwable){
            // Preserve the single failed capture (or the window at readiness failure), never promote it to proof.
            if(bitmap==null)runCatching{
                bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());savePixels(checkNotNull(bitmap))
            }.onFailure{observations.put("diagnosticScreenshotFailure",it.javaClass.simpleName)}
            f.manifest.optJSONObject("nativePageCaptures")?.remove("$name.png")
            val failures=f.manifest.optJSONObject("failedNativePageCaptures")?:JSONObject().also{f.manifest.put("failedNativePageCaptures",it)}
            failures.put("$name.png",JSONObject().put("status","FAILED_NO_NATIVE_PROOF").put("stage",stage)
                .put("runId",f.runId).put("sourceCommit",BuildConfig.SOURCE_COMMIT).put("sha256",if(pixelsSaved)SixBatchFixture.sha(file)else JSONObject.NULL)
                .put("failureClass",error.javaClass.simpleName).put("failureMessage",error.message)
                .put("observations",observations).put("renderResources",JSONObject(RenderResources.snapshot())))
            f.save();throw error
        }finally{bitmap?.recycle()}
    }

    /** The PDF is teal (#183346); this blue-only predicate cannot count its printed source text. */
    private fun bluePixels(bitmap:Bitmap,view:InkCanvasView,region:CanvasBounds,observations:JSONObject,label:String):Int {
        val location=IntArray(2);view.getLocationOnScreen(location)
        val viewport=view.snapshotViewport();val density=view.resources.displayMetrics.density.toDouble()
        fun point(x:Double,y:Double)=viewport.worldToScreen(x,y,view.width.toDouble(),view.height.toDouble(),density)
        val a=point(region.left,region.top);val b=point(region.right,region.bottom)
        val left=(a.x+location[0]).toInt();val top=(a.y+location[1]).toInt()
        val right=(b.x+location[0]).toInt();val bottom=(b.y+location[1]).toInt()
        val visible=Rect();assertTrue(view.getGlobalVisibleRect(visible))
        observations.put(label+"Sample",JSONObject().put("left",left).put("top",top).put("right",right).put("bottom",bottom)
            .put("visibleRect",visible.toShortString()))
        assertTrue("Pressure layer sample is off screen",left>=visible.left&&top>=visible.top&&right<=visible.right&&bottom<=visible.bottom)
        var count=0
        for(y in top until bottom)for(x in left until right){val c=bitmap.getPixel(x,y)
            if(Color.blue(c)-Color.green(c)>25&&Color.blue(c)-Color.red(c)>35)count++}
        return count
    }

    fun record(f:SixBatchFixture,name:String,proof:JSONObject){
        val captures=f.manifest.optJSONObject("nativePageCaptures")?:JSONObject().also{f.manifest.put("nativePageCaptures",it)}
        captures.put("$name.png",proof.put("sha256",SixBatchFixture.sha(File(f.root,"$name.png")))
            .put("runId",f.runId).put("sourceCommit",BuildConfig.SOURCE_COMMIT).put("review","PENDING_VISUAL_REVIEW"));f.save()
    }
}
