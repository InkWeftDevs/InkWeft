// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.UUID

class DenseExcerptUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun overlay(view:View):SelectionOverlayView? {
        if(view is SelectionOverlayView&&view.isShown)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)overlay(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun canvas(view:View):InkCanvasView? {
        if(view is InkCanvasView&&!view.preview&&!view.embeddedPage&&view.isShown)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)canvas(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun denseImageExcerptCrossesSelectionLimitClipsPaperAndKeepsPictureWhenOcrIsOverBudget(){
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val preferences=app.getSharedPreferences("inkweft-excerpts",0)
        val oldMap=preferences.getBoolean("to-map",true);val oldText=preferences.getBoolean("text",false)
        try{
            assertTrue(preferences.edit().putBoolean("to-map",false).putBoolean("text",true).commit())
            val note=runBlocking{app.workspaceRepository.create("密集图片摘录与纸边",false,PaperStyle.BLANK)}
            val strokes=List(2000){index->
                val last=index==1999;val x=if(last)20f else 200f+(index%40)*10;val y=if(last)700f else 500f+(index/40)*5
                InkStroke(id(),InkPen.PEN,if(last)Color.RED else Color.BLUE,6f,InkTool.STYLUS,listOf(InkSample(x,y,0),InkSample(x+20,y,20)))
            }
            runBlocking{strokes.chunked(1000).forEachIndexed{index,batch->
                assertTrue(app.inkRepository.save(CommitInk(id(),note.id,index.toLong(),InkMutation.Replace(emptyList(),batch))) is InkCommitResult.Committed)
            }}
            compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
            compose.singlePageEditor();compose.waitForSavedInk();compose.frameCanvasFixture()
            compose.waitUntil(20_000){compose.runOnIdle{canvas(compose.activity.window.decorView)?.viewportFrameReady==true}}
            compose.runOnIdle{
                val native=checkNotNull(canvas(compose.activity.window.decorView));val region=CanvasBounds(0.0,450.0,650.0,850.0)
                native.focusRegion(region);assertFalse(native.viewportFrameReady)
                // The first software draw after invalidation starts new jobs; it cannot be saved.
                assertThrows(IllegalStateException::class.java){native.excerptPreview(region)}
            }
            compose.revealAction("top-excerpt");compose.onNodeWithTag("top-excerpt").performClick()
            compose.waitUntil(10_000){compose.onAllNodesWithTag("selection-overlay").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{checkNotNull(overlay(compose.activity.window.decorView)).onRegion(InkRegion(listOf(EraserPoint(-50f,450f),EraserPoint(650f,850f))))}
            compose.waitUntil(20_000){runCatching{compose.onNodeWithTag("capture-confirm").assertIsDisplayed().assertIsEnabled()}.isSuccess}
            compose.onNodeWithText("已保留原貌与可提取文字；手写识别请缩小范围").assertExists()
            compose.onNodeWithTag("capture-confirm").performClick()
            compose.waitUntil(15_000){runBlocking{app.study.cards(note.id).first()}.size==1}
            val card=runBlocking{app.study.cards(note.id).first()}.single();val source=runBlocking{app.study.source(card.id)}!!
            assertEquals(0.0,source.left,0.0);assertEquals(650.0,source.right,0.0);assertEquals(2L,source.inkRevision)
            val saved=InkPageFile.decode(source.snapshot);assertTrue(saved.strokes.isEmpty())
            val bytes=Base64.getDecoder().decode(saved.objects.single().image)
            val picture=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try{
                val color=picture.getPixel((picture.width*30.0/650).toInt(),(picture.height*250.0/400).toInt())
                assertTrue("The last visible stroke is retained in the saved picture",Color.red(color)>150&&Color.blue(color)<150)
            }finally{picture.recycle()}
            assertEquals(2L,runBlocking{app.inkRepository.revision(note.id)})
        }finally{preferences.edit().putBoolean("to-map",oldMap).putBoolean("text",oldText).commit()}
    }
}
