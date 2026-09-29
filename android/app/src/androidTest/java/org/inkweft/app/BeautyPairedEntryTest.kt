package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Same source identities, recognized text and options; exercise both entry commands through persistence. */
class BeautyPairedEntryTest {
    private fun id()=UUID.randomUUID().toString()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as InkWeftApplication
    private fun pixels(o:PageObject):IntArray {
        val b=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
        PageObjectPainter().draw(Canvas(b),listOf(o),false,CanvasBounds(0.0,0.0,1000.0,700.0))
        return IntArray(700000).also{b.getPixels(it,0,1000,0,0,1000,700);b.recycle()}
    }
    @Test fun pairedEntriesKeepPreviewPixelsPersistenceEraseAndUndoIdentical()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val text="与其寻找 Aa e\u0301，123 + √"
        val strokes=List(18){i->InkStroke(id(),if(i%2==0)InkPen.PENCIL else InkPen.PEN,
            if(i%3==0)0xff2468ad.toInt()else 0xffb73546.toInt(),2f,InkTool.STYLUS,
            listOf(InkSample(80f+i*35,200f,0),InkSample(100f+i*35,240f,20),InkSample(85f+i*35,235f,40)))}
        val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
        val result=RecognizedWriting(text,.99f,1,listOf(RecognizedLine(text,bounds,strokes.map{it.id},emptyList(),.99f)))
        val n=app.workspaceRepository.create("相同原迹 · 手动与自动入口",false,PaperStyle.BLANK)
        assertTrue(app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Replace(emptyList(),strokes))) is InkCommitResult.Committed)
        for(preserve in listOf(true,false))for(font in listOf(TextFont.WENKAI,TextFont.entries.first())){
            val options=BeautyOptions(enabled=true,font=font,preserveLayout=preserve,size=30f)
            suspend fun convert(automatic:Boolean):Pair<PageObject,IntArray>{
                lateinit var vm:PageObjectViewModel
                ins.runOnMainSync{vm=PageObjectViewModel(n.id,app.pageObjects){_,_,_->result}}
                withTimeout(10000){while(vm.ui.value.loading)delay(10)}
                ins.runOnMainSync{
                    vm.observeBeauty(InkUi(loading=false),false,options,false,app)
                    vm.observeBeauty(InkUi(loading=false,revision=1,strokes=strokes),false,options,false,app)
                    if(!automatic)vm.beautify(SelectedInk(InkRegion(listOf(EraserPoint(70f,190f),EraserPoint(740f,255f))),1,strokes),options,false,app)
                }
                withTimeout(10000){while(vm.beautyReview.value==null)delay(10)}
                val review=checkNotNull(vm.beautyReview.value)
                val preview=checkNotNull(review.candidate){review.reason.orEmpty()}
                assertTrue("Formula must remain reviewable",review.reason!=null)
                ins.runOnMainSync{vm.acceptBeauty()}
                withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.pending||app.pageObjects.read(n.id).objects.isEmpty())delay(10)}
                val saved=app.pageObjects.read(n.id).objects.single()
                assertArrayEquals(pixels(preview),pixels(saved))
                val g=saved.glyphs.first{it.width>4&&it.height>4}
                ins.runOnMainSync{vm.eraseBeauty(listOf(InkSample(saved.x+g.x+g.width/2,saved.y+g.y,0),InkSample(saved.x+g.x+g.width/2,saved.y+g.y+g.height,20)),2f,false)}
                withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.pending||app.pageObjects.read(n.id).objects.single().erasures.isEmpty())delay(10)}
                val erased=app.pageObjects.read(n.id).objects.single()
                assertFalse(pixels(saved).contentEquals(pixels(erased)))
                assertArrayEquals(pixels(erased),pixels(PageObjectCodec.decode(PageObjectCodec.encode(listOf(erased))).single()))
                ins.runOnMainSync{vm.undo()};withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.pending||app.pageObjects.read(n.id).objects.single().erasures.isNotEmpty())delay(10)}
                assertArrayEquals(pixels(saved),pixels(app.pageObjects.read(n.id).objects.single()))
                ins.runOnMainSync{vm.restoreOriginal(saved.id)}
                withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.pending||app.pageObjects.read(n.id).objects.isNotEmpty())delay(10)}
                return saved to pixels(erased)
            }
            val manual=convert(false);val automatic=convert(true)
            assertArrayEquals(pixels(manual.first),pixels(automatic.first))
            assertArrayEquals(manual.second,automatic.second)
            assertEquals(manual.first.sourceStrokeIds,automatic.first.sourceStrokeIds)
            assertEquals(manual.first.textRuns.map{listOf(it.x,it.baseline,it.size)},automatic.first.textRuns.map{listOf(it.x,it.baseline,it.size)})
        }
    }
}
