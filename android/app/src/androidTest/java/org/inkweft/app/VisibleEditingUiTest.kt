package org.inkweft.app

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class VisibleEditingUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private lateinit var old:Map<String,*>
    @Before fun isolate(){val p=app.getSharedPreferences("inkweft-editor",0);old=p.all;p.edit().putBoolean("case-collapsed",true).putBoolean("favorites-open",false).commit()}
    @After fun restore(){val e=app.getSharedPreferences("inkweft-editor",0).edit().clear();old.forEach{(k,v)->when(v){is Float->e.putFloat(k,v);is Boolean->e.putBoolean(k,v);is String->e.putString(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v)}};e.commit()}
    private fun fixture():Note {
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("V24 图片与笔参数验收",false,PaperStyle.BLANK)}
        val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val image=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,x=350f,y=220f,width=240f,height=180f,image=java.util.Base64.getEncoder().encodeToString(CoverImages.normalize(bytes,PageObjectCodec.MAX_IMAGE)))
        runBlocking{app.pageObjects.save(note.id,0,UUID.randomUUID().toString(),listOf(image))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15_000){app.navigationReady.value&&compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        return note
    }
    private fun item(note:Note)=runBlocking{app.pageObjects.read(note.id).objects.single()}
    private fun canvas():InkCanvasView{fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};return checkNotNull(find(compose.activity.window.decorView))}
    private fun point(o:PageObject,corner:Boolean=false):Offset {var p=Offset.Zero;compose.runOnIdle{val v=canvas();val a=v.snapshotViewport().worldToScreen((o.x+o.width*(if(corner)1f else .5f)).toDouble(),(o.y+o.height*(if(corner)1f else .5f)).toDouble(),v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());p=Offset(a.x.toFloat(),a.y.toFloat())};return p}
    @Test fun continuousImageTapThenFreeStretchDeselectReselectMoveUndoAndReopen(){
        val note=fixture();var image=item(note);val center=point(image)
        compose.onNodeWithTag("continuous-ink-1").performTouchInput{click(center)}
        compose.waitUntil(10_000){compose.onAllNodesWithTag("object-deselect").fetchSemanticsNodes().isNotEmpty()}
        val corner=point(image,true)
        compose.onNodeWithTag("object-overlay").performTouchInput{swipe(corner,corner+Offset(100f,0f),250)}
        compose.waitUntil(10_000){item(note).width>image.width+20}
        val stretched=item(note);assertEquals(image.height,stretched.height,.5f)
        compose.onNodeWithTag("object-deselect").performScrollTo().performClick()
        val again=point(stretched);compose.onNodeWithTag("ink-surface").performTouchInput{click(again)}
        compose.onNodeWithTag("object-deselect").assertExists()
        compose.onNodeWithTag("object-overlay").performTouchInput{swipe(again,again+Offset(70f,65f),250)}
        compose.waitUntil(10_000){item(note).x>stretched.x+10}
        val moved=item(note);compose.onNodeWithTag("ink-undo").performClick()
        compose.waitUntil(10_000){item(note).x==stretched.x};compose.onNodeWithTag("ink-redo").performClick()
        compose.waitUntil(10_000){item(note).x==moved.x};compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000){app.navigationReady.value};assertEquals(moved,item(note))
    }
    @Test fun topPenExpandsCollapsedCaseAndEditsCurrentPreset(){
        val note=fixture();compose.openCurrentPen()
        compose.onNodeWithTag("pen-width-dialog").assertIsDisplayed()
        compose.selectPen("brush");compose.openCurrentPen()
        assertEquals(InkPen.BRUSH,PenWidthStore(app,"inkweft-pen-widths-book-"+note.id).readKinds()[0])
        compose.closePenSettings();compose.onNodeWithTag("pen-kind-ballpoint").assertIsDisplayed()
    }
}
