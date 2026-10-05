// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LayerAuthoringNativeTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun hiddenOriginalImageDoesNotReturnWhenItsPendingDecodeFinishes(){
        fun png(width:Int,height:Int):ByteArray {val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffc92835.toInt());return try{java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{bitmap.recycle()}}
        val source=ImageSource(png(2400,1800));val image=PageObject(id(),PageObjectKind.IMAGE,300f,300f,300f,300f,image=java.util.Base64.getEncoder().encodeToString(png(240,180)),imageSource=source.sha256)
        val layer=UserLayer(id(),"原图答案");val layers=UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),layer),UserLayers.DEFAULT_ID,listOf(LayerMembership(LayerContent(LayerContentKind.OBJECT,image.id),layer.id)))
        lateinit var view:InkCanvasView
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{a->view=InkCanvasView(a);a.setContentView(FrameLayout(a).apply{addView(view,FrameLayout.LayoutParams(1000,1414))});view.configure(false,PaperStyle.BLANK,CanvasViewport());view.fixedRegion(CanvasBounds(0.0,0.0,1000.0,1414.0));view.showImageSources(listOf(source));view.showObjects(listOf(image));view.showAuthoring(PageAuthoring(layers))}
            fun draw():Bitmap {val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);scenario.onActivity{view.draw(Canvas(bitmap))};return bitmap}
            fun settle(){val limit=android.os.SystemClock.uptimeMillis()+15000;var pending=true;while(pending&&android.os.SystemClock.uptimeMillis()<limit){draw().recycle();android.os.SystemClock.sleep(80);scenario.onActivity{pending=view.imageFramesPending||view.rasterPending}};assertFalse("original decode did not finish",pending)}
            settle();draw().let{assertNotEquals(0x00ffffff,it.getPixel(450,450) and 0x00ffffff);it.recycle()}
            scenario.onActivity{view.zoomBy(2.0)};draw().recycle()
            scenario.onActivity{view.showAuthoring(PageAuthoring(layers.update(layer.copy(visible=false))));view.fixedRegion(CanvasBounds(0.0,0.0,1000.0,1414.0))}
            settle();android.os.SystemClock.sleep(250);draw().let{assertEquals(0x00ffffff,it.getPixel(450,450) and 0x00ffffff);it.recycle()}
        }
    }
    @Test fun thousandStrokeHiddenLayerCannotReappearAfterDelayedViewportFrame(){
        val secret=UserLayer(id(),"隐藏答案");val locked=UserLayer(id(),"锁定层",locked=true)
        val strokes=List(1000){i->val group=i%3;val x=if(group==0)50f else if(group==1)350f else 690f;val y=70f+(i/3%95)*12f
            InkStroke(id(),InkPen.PEN,0xff9d1925.toInt(),3f,InkTool.STYLUS,List(100){p->InkSample(x+p*1.8f,y,p.toLong())})}
        val memberships=strokes.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),when(i%3){0->UserLayers.DEFAULT_ID;1->secret.id;else->locked.id})}
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),secret,locked),UserLayers.DEFAULT_ID,memberships),listOf(DocumentWhitespace(id(),300.0),DocumentWhitespace(id(),800.0)))
        lateinit var view:InkCanvasView
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{a->view=InkCanvasView(a);a.setContentView(FrameLayout(a).apply{addView(view,FrameLayout.LayoutParams(1000,1414))});view.configure(false,PaperStyle.BLANK,CanvasViewport());view.fixedRegion(CanvasBounds(0.0,0.0,1000.0,1414.0));view.showAuthoring(state);view.showStrokes(strokes)}
            fun draw():Bitmap {val bitmap=Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888);scenario.onActivity{view.draw(Canvas(bitmap))};return bitmap}
            fun settle(){val limit=android.os.SystemClock.uptimeMillis()+15000;var pending=true;while(pending&&android.os.SystemClock.uptimeMillis()<limit){draw().recycle();android.os.SystemClock.sleep(60);scenario.onActivity{pending=view.rasterPending||view.imageFramesPending}};assertFalse("viewport frame did not finish",pending)}
            fun inkPixels(bitmap:Bitmap):Int {var count=0;for(y in 80..1100)for(x in 360..520)if((bitmap.getPixel(x,y) and 0x00ffffff)!=0x00ffffff)count++;return count}
            settle();draw().let{assertTrue(inkPixels(it)>1000);it.recycle()}
            scenario.onActivity{view.zoomBy(1.4);view.showAuthoring(state.withLayers(state.layers.update(secret.copy(visible=false))))}
            settle();scenario.onActivity{view.fixedRegion(CanvasBounds(0.0,0.0,1000.0,1414.0))};settle()
            draw().let{assertEquals(0,inkPixels(it));it.recycle()}
            scenario.onActivity{view.showAuthoring(state.withLayers(state.layers.remove(secret.id,LayerDelete.DeleteContents)))}
            settle();android.os.SystemClock.sleep(200);draw().let{assertEquals(0,inkPixels(it));it.recycle()}
            assertEquals(1000,strokes.size);assertEquals(100_000,strokes.sumOf{it.samples.size});assertEquals(2,state.blanks.size)
        }
    }
}
