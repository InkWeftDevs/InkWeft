package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReadingHandwritingUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun saved(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}}
    private fun nativeCanvas():InkCanvasView{fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};return checkNotNull(find(compose.activity.window.decorView))}
    private fun hideKeyboard(){compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())};compose.waitForIdle()}
    private fun shot(name:String){compose.waitForIdle();val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
    @Test fun fontChoicePreviewApplyRestoreAndObjectUndoKeepOriginalSamples(){
        ready();val n=runBlocking{app.workspaceRepository.create("字体测试-${id().take(6)}",false,PaperStyle.BLANK)}
        val stroke=InkStroke(id(),InkPen.PEN,Color.BLACK,4f,InkTool.STYLUS,listOf(InkSample(200f,600f,0),InkSample(400f,600f,30)))
        runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(stroke)))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};saved()
        compose.onNodeWithTag("fit-page").performScrollTo().performClick();compose.onNodeWithTag("ink-select").performScrollTo().performClick();compose.waitForIdle()
        var a=Offset.Zero;var b=Offset.Zero
        compose.runOnIdle{val v=nativeCanvas();val d=v.resources.displayMetrics.density.toDouble();fun point(x:Double,y:Double):Offset{val p=v.snapshotViewport().worldToScreen(x,y,v.width.toDouble(),v.height.toDouble(),d);return Offset(p.x.toFloat(),p.y.toFloat())};a=point(170.0,560.0);b=point(440.0,640.0)}
        compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(a,b,300)}
        compose.onNodeWithTag("selection-font-beauty").performScrollTo().performClick()
        compose.waitUntil(30_000){runCatching{compose.onNodeWithTag("beauty-recognized-text").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("beauty-recognized-text").performTextReplacement("墨织手写美化\nBilingual notes")
        hideKeyboard();compose.onNodeWithTag("font-SERIF").performScrollTo().performClick();compose.onNodeWithTag("font-WENKAI").performScrollTo().performClick();shot("font-beauty-preview.png")
        compose.onNodeWithTag("apply-font-beauty").performClick()
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(n.id).objects.size}==1}
        val o=runBlocking{app.pageObjects.read(n.id).objects.single()};assertEquals(TextFont.WENKAI,o.font);assertEquals(listOf(stroke.id),o.sourceStrokeIds)
        assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(runBlocking{app.inkRepository.read(n.id)}.strokes.single().stroke))
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("object-delete").assertIsEnabled()}.isSuccess}
        shot("font-beauty-applied.png");compose.onNodeWithTag("object-delete").performScrollTo().assertTextContains("恢复原迹").performClick()
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(n.id).objects.isEmpty()}}
        compose.onNodeWithTag("object-undo").performScrollTo().performClick();compose.waitUntil(10_000){runBlocking{app.pageObjects.read(n.id).objects.size}==1}
    }
    @Test fun pdfImportOwnsSourceAndKeepsBackgroundAcrossZoomAndReopen(){
        ready();val file=File(app.cacheDir,"source-${id()}.pdf");val d=PdfDocument()
        try{repeat(2){i->val page=d.startPage(PdfDocument.PageInfo.Builder(1000,1414,i+1).create());page.canvas.drawRect(200f,500f,600f,800f,Paint().apply{color=Color.BLUE});page.canvas.drawText("InkWeft PDF page ${i+1}",100f,180f,Paint().apply{color=Color.BLACK;textSize=40f});d.finishPage(page)};file.outputStream().use(d::writeTo)}finally{d.close()}
        try{compose.runOnIdle{ViewModelProvider(compose.activity)[LibraryTransfersViewModel::class.java].readUri(Uri.fromFile(file))}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("confirm-content-import").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("confirm-content-import").performClick();compose.waitUntil(15_000){compose.onAllNodesWithTag("open-transfer-result").fetchSemanticsNodes().isNotEmpty()}
            val noteId=runBlocking{ViewModelProvider(compose.activity)[LibraryTransfersViewModel::class.java].ui.value.result!!.id}
            file.delete();compose.onNodeWithTag("open-transfer-result").performClick();saved();compose.onNodeWithTag("fit-page").performScrollTo().performClick()
            fun awaitBlue(){compose.waitUntil(10_000){var blue=false;compose.runOnIdle{val v=nativeCanvas();val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);try{v.draw(Canvas(bitmap));val p=v.snapshotViewport().worldToScreen(400.0,650.0,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());if(p.x>=0&&p.x<v.width&&p.y>=0&&p.y<v.height){val pixel=bitmap.getPixel(p.x.toInt(),p.y.toInt());blue=Color.blue(pixel)>200&&Color.red(pixel)<50}}finally{bitmap.recycle()}};blue}}
            awaitBlue();shot("pdf-reading.png")
            val source=runBlocking{app.documents.read(noteId)}!!
            val cached=File(app.cacheDir,"document-render/${source.document.sha256}.pdf");assertTrue(cached.delete())
            compose.onNodeWithTag("zoom-in").performScrollTo().performClick()
            compose.waitUntil(10_000){cached.isFile};awaitBlue()
            compose.activityRule.scenario.recreate();saved();awaitBlue()
        }finally{file.delete()}
    }
}
