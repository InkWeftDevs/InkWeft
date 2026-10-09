package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BeautyReviewUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun shot(name:String){compose.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun reviewUsesPaperCoordinatesPreservesNeighboursAndOffersOriginalComparison(){
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("FQ · 排版与识别分开验证",false,PaperStyle.RULED)}
        val source=(0..4).map{i->InkStroke(id(),InkPen.PEN,0xff305ca2.toInt(),2f,InkTool.STYLUS,listOf(InkSample(180f+i*55,410f,0),InkSample(212f+i*55,452f,20)))}
        val neighbour=PageObject(id(),PageObjectKind.TEXT,x=120f,y=220f,width=680f,height=150f,text="条件概率与独立性\nP(A | B) = P(A ∩ B) / P(B)\n先核对识别文字，再检查字体比例与纸面落点。",fontSize=26f,lineSpacing=1.4f)
        runBlocking{source.forEachIndexed{i,s->app.inkRepository.save(CommitInk(id(),note.id,i.toLong(),InkMutation.Add(s)))};app.pageObjects.save(note.id,0,id(),listOf(neighbour))}
        lateinit var vm:PageObjectViewModel
        compose.runOnIdle{
            val factory=object:ViewModelProvider.Factory{override fun <T:ViewModel> create(c:Class<T>):T {
                @Suppress("UNCHECKED_CAST") return PageObjectViewModel(note.id,app.pageObjects){s,_,_->RecognizedWriting("复习 English，123。",.7f,1,listOf(RecognizedLine("复习 English，123。",s.map{it.bounds()}.reduce{a,b->a.union(b)},s.map{it.id},emptyList(),.7f)))} as T
            }}
            vm=ViewModelProvider(compose.activity,factory)["objects-${note.id}",PageObjectViewModel::class.java]
            ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)
        }
        compose.singlePageEditor();compose.waitForSavedInk();compose.frameCanvasFixture()
        compose.waitUntil(10000){!vm.ui.value.loading}
        shot("fq-paper-before.png")
        compose.runOnIdle{vm.beautify(SelectedInk(InkRegion(listOf(EraserPoint(170f,400f),EraserPoint(480f,460f))),5,source),BeautyOptions(),false,app)}
        compose.waitUntil(10000){vm.beautyReview.value!=null}
        compose.onNodeWithTag("beauty-review").assertIsDisplayed();assertFalse(vm.beautyReview.value!!.preview);shot("fq-paper-unapplied-original.png")
        val candidate=vm.beautyReview.value!!.candidate!!
        // Ink bounds include the 1-unit radius of the two-unit pen.
        assertEquals(179f,candidate.x,.01f);assertEquals(409f,candidate.y,.01f)
        assertEquals(listOf(neighbour),runBlocking{app.pageObjects.read(note.id).objects})
        compose.onNodeWithTag("beauty-review-compare").performScrollTo().performClick();assertTrue(vm.beautyReview.value!!.preview)
        shot("fq-paper-explicit-preview.png")
        compose.onNodeWithTag("beauty-review-compare").performClick()
        assertFalse(vm.beautyReview.value!!.preview)
        shot("fq-paper-original-comparison.png")
        compose.onNodeWithTag("beauty-review-font-SERIF").performScrollTo().performClick()
        assertEquals(TextFont.SERIF,vm.beautyReview.value!!.candidate!!.font)
        compose.onNodeWithTag("beauty-review-apply").performClick()
        compose.waitUntil(10000){!vm.ui.value.busy&&vm.beautyReview.value==null}
        assertEquals(neighbour,runBlocking{app.pageObjects.read(note.id).objects.first()})
        assertEquals(source.map{it.id},vm.ui.value.objects.last().sourceStrokeIds)
        compose.onNodeWithTag("object-overlay").assertExists()
        val saved=vm.ui.value.objects.last()
        var from=Offset.Zero
        compose.runOnIdle{
            fun find(v:android.view.View):InkCanvasView? {
                if(v is InkCanvasView)return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                return null
            }
            val view=checkNotNull(find(compose.activity.window.decorView))
            val p=view.snapshotViewport().worldToScreen((saved.x+saved.width/2).toDouble(),(saved.y+saved.height/2).toDouble(),view.width.toDouble(),view.height.toDouble(),view.resources.displayMetrics.density.toDouble())
            from=Offset(p.x.toFloat(),p.y.toFloat())
        }
        compose.onNodeWithTag("object-overlay").performTouchInput{swipe(from,from+Offset(50f,60f),240)}
        compose.waitUntil(10000){vm.ui.value.objects.last().y>saved.y+5&&!vm.ui.value.busy}
        compose.onNodeWithTag("object-overlay").performTouchInput{click(Offset(20f,height-30f))}
        compose.onNodeWithTag("object-overlay").assertExists()
        assertEquals(source.size,runBlocking{app.inkRepository.read(note.id).strokes.size})
        shot("fq-paper-applied.png")
    }
}
