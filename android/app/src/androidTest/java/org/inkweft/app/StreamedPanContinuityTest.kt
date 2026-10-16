// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.math.roundToInt

/** Synthetic Room-backed ink. Replacement reads are held until pending-frame pixels are checked. */
class StreamedPanContinuityTest {
    @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
    private fun id()=UUID.randomUUID().toString()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync{action()}
    private fun field(owner:Any,name:String)=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)

    private inner class Fixture(val db:NoteDatabase,val page:String,val ink:List<InkStroke>,val authoring:PageAuthoring) {
        val repository=InkRepository(db)
        val source=InkRenderSource(repository,page,1,setOf(ink[0].id),1,mapOf(UserLayers.DEFAULT_ID to 2L))
        val viewport=CanvasViewport(500.0,707.0,1.0)
        val bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        val rasters=mutableListOf<StreamedInkRaster>()
        val layouts=mutableListOf<StreamedLayerLayout>()
        val jobs=linkedSetOf<Job>()
        val baselineJobs=RenderResources.inFlightJobs.get()
        val baseline=RenderResources.snapshot()
        var failures=0
        var view:InkCanvasView?=null
        fun raster()=StreamedInkRaster({},{failures++}).also{rasters.add(it)}
        fun layout()=StreamedLayerLayout({},{failures++}).also{layouts.add(it)}
        fun remember(){
            view?.let{v->
                val layout=field(v,"streamedLayerLayout") as StreamedLayerLayout
                if(layout !in layouts)layouts.add(layout)
                @Suppress("UNCHECKED_CAST") val layers=field(v,"streamedLayers") as Map<String,StreamedInkRaster>
                layers.values.forEach{if(it !in rasters)rasters.add(it)}
            }
            (rasters+layouts).forEach{(field(it,"job") as Job?)?.let(jobs::add)}
        }
        fun draw(raster:StreamedInkRaster,source:InkRenderSource=this.source,viewport:CanvasViewport=this.viewport,
            width:Int=300,height:Int=300,layer:String?=UserLayers.DEFAULT_ID,allowed:Set<String>?=null,omitted:Set<String> = emptySet(),crop:Rect?=null){
            remember();bitmap.eraseColor(Color.WHITE)
            try{raster.draw(Canvas(bitmap),width,height,viewport,1.0,false,false,source,allowed,omitted,layer,crop)}finally{remember()}
        }
        fun bounds(layout:StreamedLayerLayout,source:InkRenderSource=this.source,viewport:CanvasViewport=this.viewport,width:Int=300,height:Int=300):Map<String,Rect>? {
            remember()
            return try{layout.request(source,viewport,width,height,1.0)}finally{remember()}
        }
        fun assertInk(viewport:CanvasViewport=this.viewport,width:Int=300,height:Int=300,x:Double=500.0,y:Double=707.0){
            val point=viewport.worldToScreen(x,y,width.toDouble(),height.toDouble(),1.0)
            val px=point.x.roundToInt();val py=point.y.roundToInt()
            assertTrue("Fixture anchor must be onscreen",px in 3 until width-3&&py in 3 until height-3)
            assertTrue("Retained ink disappeared at ($px, $py)",(py-2..py+2).any{yy->(px-2..px+2).any{xx->Color.blue(bitmap.getPixel(xx,yy))>180&&Color.red(bitmap.getPixel(xx,yy))<100}})
        }
        suspend fun awaitReady(draw:()->Boolean){withTimeout(15_000){while(true){var ready=false;main{ready=draw()};if(ready)break;delay(10)}};assertEquals(0,failures)}
        suspend fun blocked(block:suspend()->Unit)=coroutineScope {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val transaction=launch(Dispatchers.IO){db.withTransaction{entered.complete(Unit);release.await()}}
            try{withTimeout(5000){entered.await()};block()}finally{release.complete(Unit);withContext(NonCancellable){transaction.join()}}
        }
        suspend fun close(){
            withContext(NonCancellable){
                main{remember();view?.let{(it.parent as FrameLayout).removeView(it)};rasters.forEach{it.clear()};layouts.forEach{it.clear()}}
                jobs.joinAll()
                assertEquals("No owned worker remains",baselineJobs,RenderResources.inFlightJobs.get())
                for(category in listOf("category.ink-frame","category.ink-geometry-reservation"))
                    assertEquals("Released $category",baseline[category]?:0L,RenderResources.snapshot()[category]?:0L)
                bitmap.recycle()
            }
        }
    }
    private fun fixture(block:suspend Fixture.()->Unit)=runBlocking {
        rule.scenario.onActivity{it.setContentView(FrameLayout(it))}
        val context=ApplicationProvider.getApplicationContext<Context>();val name="pan-continuity-${id()}.db";val db=NoteDatabase.open(context,name)
        var fixture:Fixture?=null
        try{
            val page=WorkspaceRepository(db).create("合成拖动画布",false,PaperStyle.BLANK).id
            fun line(x:Float,color:Int)=InkStroke(id(),InkPen.PEN,color,8f,InkTool.STYLUS,listOf(InkSample(x-10,707f,0),InkSample(x+10,707f,20)))
            val ink=listOf(line(500f,Color.BLUE),line(680f,Color.RED))
            val authoring=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"拖动图层")),UserLayers.DEFAULT_ID,
                ink.map{LayerMembership(LayerContent(LayerContentKind.INK,it.id),UserLayers.DEFAULT_ID)}))
            assertFalse("The fixture must exercise the nonlegacy layered renderer",authoring.legacy)
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                ink.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
                PageAuthoringRepository(db).storePage(page,page,1,authoring)
            }
            fixture=Fixture(db,page,ink,authoring);fixture.block()
        }finally{fixture?.close();db.close();context.deleteDatabase(name)}
    }

    @Test fun attachedLayeredInkStaysVisibleDuringMoveBeforeReleaseAndWindowRefresh()=fixture {
        rule.scenario.onActivity{activity->
            view=InkCanvasView(activity).also{v->
                activity.setContentView(FrameLayout(activity).apply{addView(v,FrameLayout.LayoutParams(300,300))})
                v.configure(false,PaperStyle.BLANK,viewport.copy(zoom=1.0/activity.resources.displayMetrics.density))
                v.showAuthoring(authoring);v.showInkSource(source,false)
                // No editor geometry: only the repository-backed layer can paint the anchor.
                v.showStrokes(emptyList())
            }
        }
        fun drawView():Boolean {
            remember();bitmap.eraseColor(Color.WHITE)
            try{view!!.draw(Canvas(bitmap))}finally{remember()}
            return view!!.width==300&&view!!.height==300&&!view!!.rasterPending
        }
        awaitReady{drawView()};main{assertInk()}
        var time=android.os.SystemClock.uptimeMillis();val down=time
        fun touch(action:Int,x:Float,y:Float){
            val e=MotionEvent.obtain(down,time,action,1,
                arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}),
                arrayOf(MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f}),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try{assertTrue(view!!.onTouchEvent(e))}finally{e.recycle()};time+=20
        }
        blocked {
            main{touch(MotionEvent.ACTION_DOWN,150f,150f)}
            for((index,dx) in listOf(20f,45f,100f).withIndex()){
                main{
                    touch(MotionEvent.ACTION_MOVE,150f-dx,150f)
                    view!!.showInkSource(source.copy(committedIds=if(index==1)emptySet()else setOf(ink[1].id)),false)
                    assertFalse(drawView());assertTrue(view!!.rasterPending);assertFalse(view!!.viewportFrameReady)
                    assertInk(viewport.copy(centerX=500.0+dx))
                    assertTrue("The completed layer owner must survive MOVE",rasters.first().pending)
                    assertFalse("A preview cannot acknowledge either editing window",rasters.first().contains(ink[0].id)||rasters.first().contains(ink[1].id))
                }
            }
            // Still held, including finger release: no completed replacement can rescue the check.
            main{touch(MotionEvent.ACTION_UP,50f,150f);assertFalse(drawView());assertInk(viewport.copy(centerX=600.0))}
        }
        awaitReady{drawView()}
        main{
            assertInk(viewport.copy(centerX=600.0));assertTrue(rasters.first().contains(ink[1].id));assertFalse(rasters.first().contains(ink[0].id))
            assertEquals("Newly revealed ink is supplied by the completed replacement",Color.RED,bitmap.getPixel(230,150))
        }
    }

    @Test fun pendingPanZoomAndCroppedResizeProjectFromTheLastCompletedDimensions()=fixture {
        lateinit var raster:StreamedInkRaster;lateinit var layout:StreamedLayerLayout
        main{raster=raster();layout=layout()}
        awaitReady{val r=bounds(layout);if(r==null)false else{draw(raster,crop=r.getValue(UserLayers.DEFAULT_ID));!layout.pending&&raster.frameReady}}
        blocked {
            main{
                // Each request supersedes pending work, but every projection starts at the
                // original completed frame, never the previous request's dimensions.
                for((vp,size) in listOf(viewport.copy(centerX=510.375) to 300,viewport.copy(centerX=505.25,zoom=1.5) to 361,viewport.copy(centerY=700.5,zoom=.75) to 240)){
                    val next=source.copy(committedIds=emptySet());val r=checkNotNull(bounds(layout,next,vp,size,size))
                    val anchor=vp.worldToScreen(500.0,707.0,size.toDouble(),size.toDouble(),1.0)
                    assertTrue(r.getValue(UserLayers.DEFAULT_ID).contains(anchor.x.toInt(),anchor.y.toInt()))
                    draw(raster,next,vp,size,size,crop=r.getValue(UserLayers.DEFAULT_ID))
                    assertTrue(layout.pending);assertTrue(raster.pending);assertFalse(raster.frameReady);assertFalse(raster.contains(ink[0].id));assertInk(vp,size,size)
                }
            }
        }
        val final=viewport.copy(centerY=700.5,zoom=.75);val next=source.copy(committedIds=emptySet())
        awaitReady{val r=bounds(layout,next,final,240,240);if(r==null)false else{draw(raster,next,final,240,240,crop=r.getValue(UserLayers.DEFAULT_ID));!layout.pending&&raster.frameReady}}
        main{assertInk(final,240,240);assertFalse(raster.contains(ink[0].id))}
    }

    @Test fun changedRenderIdentityNeverReplaysOldInkWhileReplacementIsHeld()=fixture {
        // A separate complete frame for each identity change avoids passing merely because
        // an earlier case already discarded the only old frame.
        val variants=listOf<(InkRenderSource)->InkRenderSource>(
            {it.copy(repository=InkRepository(db))},{it.copy(page=id())},{it.copy(revision=2)},
            {it.copy(authoringRevision=2)},{it.copy(layerCounts=mapOf(UserLayers.DEFAULT_ID to 3L))},{it.copy(appendGeneration=Any())})
        for(change in variants){
            lateinit var raster:StreamedInkRaster;lateinit var layout:StreamedLayerLayout
            main{raster=raster();layout=layout()}
            awaitReady{val r=bounds(layout);draw(raster);r!=null&&!layout.pending&&raster.frameReady}
            blocked {main{
                val next=change(source);assertNull(bounds(layout,next,viewport.copy(centerX=510.0)))
                draw(raster,next,viewport.copy(centerX=510.0));assertFalse(raster.frameReady)
                assertEquals(Color.WHITE,bitmap.getPixel(140,150));raster.clear();layout.clear()
            }}
        }
        for(kind in listOf("allowed","omitted","layer")){
            lateinit var raster:StreamedInkRaster;main{raster=raster()};awaitReady{draw(raster);raster.frameReady}
            blocked {main{
                draw(raster,viewport=viewport.copy(centerX=510.0),allowed=if(kind=="allowed")emptySet()else null,
                    omitted=if(kind=="omitted")setOf(ink[0].id)else emptySet(),layer=if(kind=="layer")id()else UserLayers.DEFAULT_ID)
                assertFalse(raster.frameReady);assertEquals("Changed $kind must not replay old pixels",Color.WHITE,bitmap.getPixel(140,150));raster.clear()
            }}
        }
    }

    @Test fun editingWindowOnlyChangeDrawsAnAppendPreviewExactlyOnce()=fixture {
        val marker=InkStroke(id(),InkPen.HIGHLIGHTER,0x70ffff00,20f,InkTool.STYLUS,
            listOf(InkSample(490f,760f,0),InkSample(510f,760f,20)))
        assertTrue(repository.save(CommitInk(id(),page,1,InkMutation.Add(marker))) is InkCommitResult.Committed)
        // Legacy layer selection keeps this check about acknowledgement identity only.
        val initial=source.copy(revision=2,authoringRevision=null,appendGeneration=Any(),committedIds=setOf(marker.id))
        lateinit var raster:StreamedInkRaster;main{raster=raster()}
        awaitReady{draw(raster,initial,layer=null);raster.frameReady}
        val expected=bitmap.getPixel(150,203);assertNotEquals(Color.WHITE,expected)
        val next=initial.copy(committedIds=emptySet())
        blocked {main{
            draw(raster,next,layer=null);assertFalse(raster.frameReady);assertFalse(raster.contains(marker.id))
            assertEquals("A translucent frame must not be drawn as both compatible and transformed preview",expected,bitmap.getPixel(150,203))
        }}
        awaitReady{draw(raster,next,layer=null);raster.frameReady}
        main{assertEquals(expected,bitmap.getPixel(150,203));assertFalse(raster.contains(marker.id))}
    }

    @Test fun failedBoundsRefreshKeepsPreviewIncomplete()=fixture {
        lateinit var layout:StreamedLayerLayout;main{layout=layout()}
        awaitReady{bounds(layout)!=null&&!layout.pending}
        assertEquals(1,db.ink().compareAndSet(page,1,2))
        val moved=viewport.copy(centerX=510.0)
        main{assertNotNull(bounds(layout,viewport=moved));assertTrue(layout.pending)}
        jobs.joinAll()
        main{
            assertEquals(1,failures);assertNull(field(layout,"job"))
            assertNotNull(bounds(layout,viewport=moved));assertTrue("Retained bounds are still only a preview after failure",layout.pending)
        }
    }

    @Test fun clearDuringSupersededPanReleasesWorkersAndCannotPublishStaleLayout()=fixture {
        lateinit var raster:StreamedInkRaster;lateinit var layout:StreamedLayerLayout
        main{raster=raster();layout=layout()}
        awaitReady{val r=bounds(layout);draw(raster);r!=null&&!layout.pending&&raster.frameReady}
        blocked {main{
            for(dx in listOf(10.0,20.0,10.0)){
                val vp=viewport.copy(centerX=500+dx);assertNotNull(bounds(layout,viewport=vp));draw(raster,viewport=vp);assertInk(vp)
            }
            raster.clear();layout.clear();assertFalse(raster.pending);assertFalse(layout.pending)
        }}
        jobs.joinAll()
        main{
            assertNull(raster.lastBuild);assertFalse(raster.frameReady);assertNull(field(layout,"rectangles"));assertNull(field(layout,"completedKey"))
        }
        assertEquals(baselineJobs,RenderResources.inFlightJobs.get())
        // The same instances can render again after cancellation.
        awaitReady{val r=bounds(layout);draw(raster);r!=null&&!layout.pending&&raster.frameReady}
        main{assertInk()}
    }
}
