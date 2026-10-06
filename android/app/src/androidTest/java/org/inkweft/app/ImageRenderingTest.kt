// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.media.ExifInterface
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic originals only; native codec, pixels and lifecycle checks require Android. */
class ImageRenderingTest {
    @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private var renderer:ImageRendering?=null
    private val full=CanvasBounds(0.0,0.0,400.0,200.0)
    private fun bytes(bitmap:Bitmap,format:Bitmap.CompressFormat=Bitmap.CompressFormat.PNG)=ByteArrayOutputStream().also{bitmap.compress(format,100,it)}.toByteArray()
    private fun source(width:Int=400,height:Int=200,paint:(Bitmap)->Unit={it.eraseColor(Color.RED)}):ImageSource {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        return try{paint(bitmap);ImageSource(bytes(bitmap))}finally{bitmap.recycle()}
    }
    private fun item(source:ImageSource?,id:String=UUID.randomUUID().toString()):PageObject {
        val preview=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        return try{PageObject(id,PageObjectKind.IMAGE,0f,0f,400f,200f,image=Base64.getEncoder().encodeToString(bytes(preview,Bitmap.CompressFormat.JPEG)),imageSource=source?.sha256)}finally{preview.recycle()}
    }
    private fun waitUntil(message:String="Image rendering did not finish",condition:()->Boolean){
        val end=System.nanoTime()+20_000_000_000L
        while(System.nanoTime()<end){var done=false;ins.runOnMainSync{done=condition()};if(done)return;Thread.sleep(20)}
        fail(message)
    }
    private fun request(o:PageObject,source:ImageSource,visible:CanvasBounds=full,scale:Double=1.0){
        ins.runOnMainSync{if(renderer==null)renderer=ImageRendering(context,{});renderer!!.request(listOf(o),visible,scale,readSize={source.size}){source}}
        waitUntil{!renderer!!.pending}
    }
    @Before fun emptyActivity(){rule.scenario.onActivity{it.setContentView(FrameLayout(it));RenderResources.trim();BackgroundBudget.lastInput=0}}
    @After fun release(){rule.scenario.onActivity{renderer?.clear();it.setContentView(FrameLayout(it));RenderResources.trim()}}

    @Test fun regionRetainsNativeDetailAndTransparentPixelsWithoutRedecodingEachDraw(){
        // Paint() enables antialiasing on Android S+: integer-centred 1px lines create
        // half-alpha pixels. Literal pixels keep this native-detail/alpha fixture exact.
        // https://developer.android.com/reference/android/graphics/Paint#Paint()
        val original=source(4000,2000){b->
            val row=IntArray(b.width){x->if(x%2==0)Color.RED else Color.TRANSPARENT}
            for(y in 0 until b.height)b.setPixels(row,0,b.width,0,y,b.width,1)
            assertEquals(Color.RED,b.getPixel(0,100));assertEquals(0,Color.alpha(b.getPixel(1,100)))
        }
        val o=item(original);val visible=CanvasBounds(180.0,80.0,220.0,120.0)
        request(o,original,visible,10.0)
        ins.runOnMainSync{
            val frame=checkNotNull(renderer!!.frame(o))
            assertTrue(frame.bitmap.width in 400..404);assertTrue(frame.bitmap.height in 400..404)
            assertTrue(frame.bitmap.width<4000);assertTrue(frame.bitmap.height<2000)
            val target=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
            val painter=PageObjectPainter().apply{originalImage=renderer!!::frame}
            try{
                val canvas=Canvas(target).apply{scale(10f,10f);translate(-180f,-80f)}
                painter.draw(canvas,listOf(o),false,visible)
                val row=(20 until 380).map{target.getPixel(it,200)}
                val blueCount=row.count{Color.blue(it)>200&&Color.red(it)<40};val redCount=row.count{Color.red(it)>200&&Color.blue(it)<40}
                val sample=row.take(12).joinToString{Integer.toHexString(it)}
                assertTrue("Transparent source pixels must reveal blue, not JPEG: blue=$blueCount red=$redCount pixels=$sample sourceHasAlpha=${frame.bitmap.hasAlpha()} region=${frame.region}",blueCount>100)
                assertTrue("One-pixel source stripes must remain distinct: blue=$blueCount red=$redCount pixels=$sample",redCount>100)
                repeat(20){renderer!!.request(listOf(o),visible,10.0){error("A stable frame must not reload the original")};painter.draw(canvas,listOf(o),false,visible)}
                assertEquals(1,renderer!!.decodeCount);assertFalse(renderer!!.pending)
            }finally{painter.clear();target.recycle()}
        }
    }

    @Test fun nativeRegionAndDrawingHonorAllEightExifOrientations(){
        val raw=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(raw);val paint=Paint()
        listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW).forEachIndexed{i,color->paint.color=color;canvas.drawRect((i%2*40).toFloat(),(i/2*20).toFloat(),(i%2*40+40).toFloat(),(i/2*20+20).toFloat(),paint)}
        val jpeg=try{bytes(raw,Bitmap.CompressFormat.JPEG)}finally{raw.recycle()}
        val expected=listOf(listOf(0,1,2,3),listOf(1,0,3,2),listOf(3,2,1,0),listOf(2,3,0,1),listOf(0,2,1,3),listOf(2,0,3,1),listOf(3,1,2,0),listOf(1,3,0,2))
        val colors=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        val file=File.createTempFile("image-orientation-",".jpg",context.cacheDir)
        try{for(orientation in 1..8){
            file.writeBytes(jpeg);ExifInterface(file).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
            val original=ImageSource(file.readBytes());val o=item(original)
            request(o,original)
            ins.runOnMainSync{
                val target=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
                try{renderer!!.frame(o)!!.draw(Canvas(target),o,Paint());expected[orientation-1].forEachIndexed{i,index->
                    val pixel=target.getPixel(i%2*200+100,i/2*100+50);val color=colors[index]
                    assertTrue("EXIF $orientation quadrant $i",kotlin.math.abs(Color.red(pixel)-Color.red(color))<15&&kotlin.math.abs(Color.green(pixel)-Color.green(color))<15&&kotlin.math.abs(Color.blue(pixel)-Color.blue(color))<15)
                }}finally{target.recycle()}
            }
        }}finally{file.delete()}
    }

    @Test fun oldRegionFollowsObjectWhileZoomLoadsAndDeletionRejectsLateResults(){
        val original=source();val o=item(original);request(o,original)
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        ins.runOnMainSync{
            val old=renderer!!.frame(o)
            renderer!!.request(listOf(o),CanvasBounds(100.0,50.0,300.0,150.0),2.0){started.complete(Unit);gate.await();original}
            assertSame(old,renderer!!.frame(o))
            val moved=o.copy(x=100f,y=40f,width=800f,height=400f)
            val target=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888)
            try{old!!.draw(Canvas(target),moved,Paint());assertEquals(Color.RED,target.getPixel(500,240));assertEquals(Color.TRANSPARENT,target.getPixel(10,10))}finally{target.recycle()}
        }
        runBlocking{withTimeout(20_000){started.await()}}
        ins.runOnMainSync{renderer!!.retain(emptyList());assertNull(renderer!!.frame(o));assertFalse(renderer!!.pending)}
        gate.complete(Unit);Thread.sleep(150)
        ins.runOnMainSync{assertNull(renderer!!.frame(o));assertEquals(1,renderer!!.decodeCount)}
    }

    @Test fun clearingForANewPageCannotPublishThePreviousSource(){
        val red=source();val blue=source{it.eraseColor(Color.BLUE)};val a=item(red);val b=item(blue,a.id)
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        ins.runOnMainSync{renderer=ImageRendering(context,{});renderer!!.request(listOf(a),full,1.0){started.complete(Unit);gate.await();red}}
        runBlocking{withTimeout(20_000){started.await()}}
        ins.runOnMainSync{renderer!!.clear();renderer!!.request(listOf(b),full,1.0){blue}}
        gate.complete(Unit);waitUntil{!renderer!!.pending}
        ins.runOnMainSync{assertNull(renderer!!.frame(a));assertEquals(blue.sha256,renderer!!.frame(b)?.source);assertEquals(Color.BLUE,renderer!!.frame(b)!!.bitmap.getPixel(50,50))}
    }

    @Test fun offscreenAndLegacyImagesAndHiddenAuthorTextNeverReadOriginals(){
        val original=source();val o=item(original);val reads=AtomicInteger()
        ins.runOnMainSync{
            renderer=ImageRendering(context,{})
            // hidden is the existing source-text replacement flag, not a user image-layer switch.
            val hiddenText=PageObject(UUID.randomUUID().toString(),PageObjectKind.TEXT,0f,0f,400f,200f,text="隐藏的转换原文",sourceStrokeIds=listOf(UUID.randomUUID().toString()),hidden=true)
            renderer!!.request(listOf(hiddenText,o.copy(id=UUID.randomUUID().toString(),x=2000f),item(null)),full,1.0){reads.incrementAndGet();original}
            assertFalse(renderer!!.pending);assertEquals(0,reads.get());assertNull(renderer!!.frame(o))
        }
    }

    @Test fun budgetPressureRetainsTheOldFrameAndRetriesWhenReleased(){
        val original=source();val o=item(original);request(o,original)
        val held=Any()
        try{
            ins.runOnMainSync{
                RenderResources.track(held,RenderResources.BUDGET,"fixture","original-image-pressure",RenderResources.Role.ACTIVE)
                renderer!!.request(listOf(o),CanvasBounds(80.0,40.0,320.0,160.0),2.0){original}
            }
            Thread.sleep(250)
            ins.runOnMainSync{assertTrue(renderer!!.budgetDeferred);assertTrue(renderer!!.pending);assertNotNull(renderer!!.frame(o));assertEquals(1,renderer!!.decodeCount)}
        }finally{RenderResources.release(held,"original-image-pressure")}
        waitUntil{!renderer!!.pending}
        ins.runOnMainSync{assertEquals(2,renderer!!.decodeCount)}
    }

    @Test fun obsoleteRegionMakesRoomForItsReplacementWithoutExternalPressureRelease(){
        val original=source(1000,1000);val o=item(original)
        assertTrue("Use a small owned PNG lease so this reaches bitmap admission",original.size.toLong()*3<256*1024)
        request(o,original,scale=2.5)
        val held=Any();lateinit var old:ImageFrame
        try{
            ins.runOnMainSync{
                old=checkNotNull(renderer!!.frame(o));assertEquals(4_000_000,old.bitmap.allocationByteCount)
                RenderResources.trim()
                val before=RenderResources.snapshot().getValue("totalBytes");val free=3L*1024*1024
                assertTrue(before<RenderResources.BUDGET-free)
                RenderResources.track(held,RenderResources.BUDGET-before-free,"fixture","original-image-replacement-pressure",RenderResources.Role.ACTIVE)
                renderer!!.request(listOf(o),CanvasBounds(4.0,2.0,396.0,198.0),2.5,readSize={original.size}){original}
            }
            // The pressure owner stays pinned until finally; only the obsolete region can make room.
            waitUntil("An obsolete region must not permanently block its own replacement"){renderer!!.decodeCount==2&&!renderer!!.pending}
            ins.runOnMainSync{
                val next=checkNotNull(renderer!!.frame(o))
                assertNotSame(old,next);assertEquals(original.sha256,next.source)
                assertEquals(1000,next.rawWidth);assertEquals(1000,next.rawHeight)
                assertTrue(next.bitmap.width in 980..984);assertTrue(next.bitmap.height in 980..984)
                assertEquals(Color.RED,next.bitmap.getPixel(next.bitmap.width/2,next.bitmap.height/2))
                assertFalse("Published frames may still be held by a display list",old.bitmap.isRecycled)
                assertFalse(renderer!!.budgetDeferred)
                assertTrue(RenderResources.snapshot().getValue("totalBytes")<=RenderResources.BUDGET)
            }
        }finally{RenderResources.release(held,"original-image-replacement-pressure")}
    }

    @Test fun explicitFileSourcesRenderWithoutLookingUpAnApplicationPage(){
        val original=source(2000,1000);val o=item(original)
        lateinit var view:InkCanvasView
        rule.scenario.onActivity{activity->
            view=InkCanvasView(activity).apply{configure(true,PaperStyle.BLANK,CanvasViewport(200.0,100.0,1.0));showImageSources(listOf(original));showObjects(listOf(o))}
            activity.setContentView(FrameLayout(activity).apply{addView(view,FrameLayout.LayoutParams(400,200))})
        }
        waitUntil{view.width>0&&!view.imageFramesPending&&field(view,"imageRendering").let{it as ImageRendering}.frame(o)!=null}
        rule.scenario.onActivity{
            assertEquals(original.sha256,(field(view,"imageRendering") as ImageRendering).frame(o)?.source)
            view.showImageSources(emptyList());assertNull((field(view,"imageRendering") as ImageRendering).frame(o))
        }
    }
    @Test fun authorSessionReadsItsOwnDatabaseWithoutUsingTheApplicationRepository(){
        val original=source(2000,1000);val o=item(original)
        val replica=org.inkweft.data.ShadowReplica.open(context,listOf("synthetic","image-render","isolated","fixture"),UUID.randomUUID().toString(),mapOf(1 to ByteArray(32){7}))
        val session=ShadowAuthorSession(context,replica)
        val page=runBlocking{org.inkweft.data.WorkspaceRepository(replica.db).create("合成原图隔离",true,PaperStyle.BLANK).also{
            session.objects.save(it.id,0,UUID.randomUUID().toString(),listOf(o),originals=listOf(original))
        }}
        lateinit var view:InkCanvasView
        try{
            assertNull(runBlocking{(context.applicationContext as InkWeftApplication).repository.read(page.id)})
            rule.scenario.onActivity{activity->
                view=InkCanvasView(activity).apply{authorSession=session;configure(true,PaperStyle.BLANK,CanvasViewport(200.0,100.0,1.0));showDocument(page.id);showObjects(listOf(o))}
                activity.setContentView(FrameLayout(activity).apply{addView(view,FrameLayout.LayoutParams(400,200))})
            }
            waitUntil{view.width>0&&!view.imageFramesPending&&(field(view,"imageRendering") as ImageRendering).frame(o)!=null}
            rule.scenario.onActivity{assertEquals(original.sha256,(field(view,"imageRendering") as ImageRendering).frame(o)?.source)}
        }finally{rule.scenario.onActivity{it.setContentView(FrameLayout(it))};replica.close();replica.directory.deleteRecursively()}
    }
    @Test fun byteReservationPrecedesRepositoryReconstructionAndCancellationReleasesIt(){
        val original=source();val o=item(original);val reads=AtomicInteger();val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val held=Any()
        try{
            ins.runOnMainSync{
                renderer=ImageRendering(context,{})
                val baseline=RenderResources.snapshot().getValue("totalBytes")
                RenderResources.track(held,RenderResources.BUDGET-baseline-4_000_000,"fixture","original-read-pressure",RenderResources.Role.ACTIVE)
                renderer!!.request(listOf(o),full,1.0,readSize={ImageSource.MAX_BYTES}){reads.incrementAndGet();entered.complete(Unit);gate.await();original}
            }
            waitUntil{renderer!!.budgetDeferred}
            assertEquals("No chunks may be rebuilt before their lease is admitted",0,reads.get())
        }finally{RenderResources.release(held,"original-read-pressure")}
        runBlocking{withTimeout(20_000){entered.await()}}
        assertTrue(RenderResources.snapshot().getOrDefault("category.background-work",0L)>=ImageSource.MAX_BYTES.toLong()*3)
        ins.runOnMainSync{renderer!!.clear()};gate.complete(Unit)
        waitUntil{RenderResources.snapshot().getOrDefault("category.background-work",0L)==0L}
        assertEquals(1,reads.get())
    }
    @Test fun visibleOriginalsAboveTheFormerSoftLimitAreNotPermanentlyDowngraded(){
        val original=source(2200,2000);val a=item(original);val b=item(original).copy(x=400f)
        ins.runOnMainSync{
            renderer=ImageRendering(context,{})
            renderer!!.request(listOf(a,b),CanvasBounds(0.0,0.0,800.0,200.0),5.5,readSize={original.size}){original}
        }
        waitUntil{!renderer!!.pending}
        ins.runOnMainSync{
            val first=checkNotNull(renderer!!.frame(a));val second=checkNotNull(renderer!!.frame(b))
            assertTrue(first.bitmap.allocationByteCount.toLong()+second.bitmap.allocationByteCount>32L*1024*1024)
            assertEquals(2,renderer!!.decodeCount)
            renderer!!.request(listOf(a,b),CanvasBounds(0.0,0.0,800.0,200.0),5.5,readSize={original.size}){error("Stable visible originals must not be downgraded or reloaded")}
            assertNotNull(renderer!!.frame(a));assertNotNull(renderer!!.frame(b));assertFalse(renderer!!.pending)
        }
    }
    @Test fun unchangedObjectsCanRetryAfterAnOriginalImportFinishes(){
        val original=source();val o=item(original);val reads=AtomicInteger()
        ins.runOnMainSync{renderer=ImageRendering(context,{});renderer!!.request(listOf(o),full,1.0,readSize={null}){reads.incrementAndGet();original}}
        waitUntil{!renderer!!.pending}
        ins.runOnMainSync{
            assertNull(renderer!!.frame(o));assertEquals(0,reads.get())
            assertTrue(renderer!!.retryFailed())
            renderer!!.request(listOf(o),full,1.0,readSize={original.size}){reads.incrementAndGet();original}
        }
        waitUntil{!renderer!!.pending}
        ins.runOnMainSync{assertNotNull(renderer!!.frame(o));assertEquals(1,reads.get());assertFalse("Healthy frames must not reload on unrelated UI updates",renderer!!.retryFailed())}
    }
    private fun field(value:Any,name:String):Any=value.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(value)!!
}
