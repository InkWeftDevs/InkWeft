package org.inkweft.app

import android.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PageObjectsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun hideKeyboard(){
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
    }
    private fun create():String {
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val title="页内对象-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        hideKeyboard();compose.onNodeWithTag("page-objects").performScrollTo().performClick()
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
    }
    private fun objects(id:String)=runBlocking{app.pageObjects.read(id).objects}
    private fun count(id:String,n:Int){compose.waitUntil(10_000){objects(id).size==n};compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("object-text").assertIsEnabled()}.isSuccess}}
    private fun capture(name:String){val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun position(o:PageObject,corner:Boolean=false):Offset {
        var result=Offset.Zero
        compose.runOnIdle {
            fun find(v:android.view.View):InkCanvasView? {
                if(v is InkCanvasView)return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                return null
            }
            val view=checkNotNull(find(compose.activity.window.decorView))
            val p=view.snapshotViewport().worldToScreen((o.x+o.width*(if(corner)1f else .5f)).toDouble(),(o.y+o.height*(if(corner)1f else .5f)).toDouble(),view.width.toDouble(),view.height.toDouble(),view.resources.displayMetrics.density.toDouble())
            result=Offset(p.x.toFloat(),p.y.toFloat())
        };return result
    }
    @Test fun textTapeUndoCopyDeleteAndReopenPreserveAuthorData(){
        val id=create()
        compose.onNodeWithTag("object-text").performClick();compose.onNodeWithTag("object-text-input").performTextInput("中文文本框\n第二行练习")
        compose.onNodeWithTag("object-text-save").performClick();count(id,1);hideKeyboard()
        assertEquals("中文文本框\n第二行练习",objects(id).single().text)
        val before=objects(id).single();val start=position(before)
        compose.onNodeWithTag("object-overlay").performTouchInput{swipe(start,start+Offset(90f,70f),240)}
        compose.waitUntil(10_000){objects(id).single().x>before.x+10}
        val moved=objects(id).single();val corner=position(moved,true)
        compose.onNodeWithTag("object-overlay").performTouchInput{swipe(corner,corner+Offset(120f,0f),240)}
        compose.waitUntil(10_000){objects(id).single().width>moved.width+20}
        compose.onNodeWithTag("object-edit-text").performClick();compose.onNodeWithTag("object-text-input").performTextReplacement("修改后文字\n独立保存")
        compose.onNodeWithTag("object-text-save").performClick();compose.waitUntil(10_000){objects(id).single().text.startsWith("修改后")};hideKeyboard()
        compose.onNodeWithTag("object-copy").performClick();count(id,2)
        compose.onNodeWithTag("object-delete").performClick();count(id,1)
        compose.onNodeWithTag("object-undo").performScrollTo().performClick();count(id,2)
        compose.onNodeWithTag("object-redo").performScrollTo().performClick();count(id,1)
        compose.onNodeWithTag("object-tape").performScrollTo().performClick();count(id,2)
        assertFalse(objects(id).last().revealed)
        compose.onNodeWithTag("object-reveal").performClick();compose.waitUntil(10_000){objects(id).last().revealed}
        compose.waitForIdle()
        capture("page-objects-editor.png")
        val expected=objects(id)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        assertEquals(expected,objects(id));assertTrue(runBlocking{app.inkRepository.read(id).strokes.isEmpty()})
    }
    @Test fun normalizedImageIsSelfContainedAndTapeActuallyCoversIt(){
        val bitmap=Bitmap.createBitmap(1400,700,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val jpeg=CoverImages.normalize(bytes,PageObjectCodec.MAX_IMAGE)
        val o=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,x=0f,y=0f,width=400f,height=200f,image=java.util.Base64.getEncoder().encodeToString(jpeg))
        val tape=PageObject(UUID.randomUUID().toString(),PageObjectKind.TAPE,x=20f,y=20f,width=100f,height=80f,color=Color.BLUE)
        val painter=PageObjectPainter();val result=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);val canvas=Canvas(result)
        try{
            val visible=CanvasBounds(0.0,0.0,400.0,200.0)
            painter.draw(canvas,listOf(o,tape),false,visible);painter.draw(canvas,listOf(o,tape),true,visible)
            assertEquals(Color.BLUE,result.getPixel(50,50))
            painter.draw(canvas,listOf(o,tape.copy(revealed=true)),false,visible);painter.draw(canvas,listOf(o,tape.copy(revealed=true)),true,visible)
            assertTrue(Color.red(result.getPixel(50,50))>245);assertTrue(Color.blue(result.getPixel(50,50))<10)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view=InkCanvasView(compose.activity)
                view.configure(false,PaperStyle.BLANK,CanvasViewport(200.0,100.0,1.0/compose.activity.resources.displayMetrics.density))
                view.layout(0,0,400,200)
                var selected:String?=null
                val overlay=PageObjectOverlay(compose.activity).apply{canvasView=view;objects=listOf(tape,o);onSelect={selected=it};layout(0,0,400,200)}
                val down=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,50f,50f,0)
                val up=android.view.MotionEvent.obtain(0,10,android.view.MotionEvent.ACTION_UP,50f,50f,0)
                try{overlay.onTouchEvent(down);overlay.onTouchEvent(up);assertEquals("Hit testing must follow tape-over-image rendering even when image was inserted later",tape.id,selected)}finally{down.recycle();up.recycle()}
            }
            val id=create();runBlocking{app.pageObjects.save(id,0,UUID.randomUUID().toString(),listOf(o,tape))}
            assertEquals(listOf(o,tape),objects(id))
            val exported=runBlocking{app.pages.exportBook(id)};assertEquals(o.image,NotebookFile.decode(exported.encode()).pages.single().objects.first().image)
            runBlocking{val row=app.workspaceRepository.get(id);assertTrue(app.workspaceRepository.changeCover(id,row.revision,NotebookCover.CONTENT))}
            compose.onNodeWithTag("back-library").performClick()
            compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("note-cover-$id").assertIsDisplayed()}.isSuccess}
            fun previewContainsRed():Boolean {
                val pixels=compose.onNodeWithTag("note-cover-$id").captureToImage().toPixelMap()
                var red=0
                for(y in 0 until pixels.height)for(x in 0 until pixels.width){val c=pixels[x,y];if(c.red>.85f&&c.green<.2f&&c.blue<.2f)red++}
                return red>20
            }
            compose.waitUntil(10_000){previewContainsRed()}
        }finally{painter.clear();result.recycle()}
    }
}
