package org.inkweft.app

import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Actual native pen input while a synthetic automatic save acknowledgement is unknown. */
class AutomaticBeautyCanvasTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun penWorksDuringUnknownReceipt(continuous:Boolean){
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("ABF 下一笔 ${if(continuous)"连续"else"单页"}",false,PaperStyle.BLANK)}
        val source=InkStroke(id(),InkPen.PEN,0xff2255aa.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,200f,0),InkSample(125f,240f,40)))
        runBlocking{app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))}
        val db=NoteDatabase.open(compose.activity);var fail=true
        try{
            val repository=PageObjectRepository(db){if(fail){fail=false;throw java.io.IOException("Synthetic lost acknowledgement")}}
            lateinit var objects:PageObjectViewModel
            compose.runOnIdle{
                BeautyStore(app).save(BeautyOptions(enabled=true))
                app.getSharedPreferences("inkweft-reading",android.content.Context.MODE_PRIVATE).edit().putBoolean("continuous-v20-${note.id}",continuous).apply()
                val factory=object:ViewModelProvider.Factory{override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return PageObjectViewModel(note.id,repository){strokes,_,_->
                        RecognizedWriting("甲",.95f,1,listOf(RecognizedLine("甲",strokes.map{it.bounds()}.reduce{a,b->a.union(b)},strokes.map{it.id},listOf(RecognizedToken("甲",.5f,.95f)),.95f)))} as T
                }}
                objects=ViewModelProvider(compose.activity,factory)["objects-${note.id}",PageObjectViewModel::class.java]
                objects.observeBeauty(InkUi(loading=false),false,BeautyOptions(enabled=true),false,app)
                ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)
            }
            // The deliberately unknown object receipt keeps navigation blocked. Await the
            // canvas itself; requiring navigationReady would hide the behavior under test.
            compose.waitUntil(10000){compose.onAllNodesWithTag(if(continuous)"continuous-pages"else"ink-surface").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(15000){objects.ui.value.pending&&!objects.ui.value.busy&&objects.ui.value.error!=null}
            assertTrue(objects.ui.value.automaticPending);assertTrue(objects.ui.value.objects.isEmpty())
            compose.runOnIdle{
                fun find(view:View):InkCanvasView? {
                    if(view is InkCanvasView&&!view.preview&&(continuous==view.embeddedPage))return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                    return null
                }
                val canvas=checkNotNull(find(compose.activity.window.decorView));assertTrue(canvas.allowInput)
                val now=SystemClock.uptimeMillis();val density=canvas.resources.displayMetrics.density.toDouble()
                listOf(250f to 450f,275f to 490f).forEachIndexed{index,(x,y)->
                    val point=canvas.snapshotViewport().worldToScreen(x.toDouble(),y.toDouble(),canvas.width.toDouble(),canvas.height.toDouble(),density)
                    val properties=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                    val coords=MotionEvent.PointerCoords().apply{this.x=point.x.toFloat();this.y=point.y.toFloat();pressure=.5f;size=.1f}
                    val event=MotionEvent.obtain(now,now+index*35L,if(index==0)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                    try{assertTrue(canvas.dispatchTouchEvent(event))}finally{event.recycle()}
                }
            }
            compose.waitUntil(10000){runBlocking{app.inkRepository.read(note.id).strokes.size}==2}
            compose.runOnIdle{objects.retry()}
            compose.waitUntil(10000){!objects.ui.value.pending&&!objects.ui.value.busy}
            assertEquals(2,runBlocking{app.inkRepository.read(note.id).strokes.size})
            assertEquals(listOf(source.id),runBlocking{app.pageObjects.read(note.id).objects.first().sourceStrokeIds})
        }finally{db.close()}
    }
    @Test fun singlePageAcceptsNextPenStrokeDuringUnknownAutomaticReceipt(){penWorksDuringUnknownReceipt(false)}
    @Test fun continuousPageAcceptsNextPenStrokeDuringUnknownAutomaticReceipt(){penWorksDuringUnknownReceipt(true)}
}
