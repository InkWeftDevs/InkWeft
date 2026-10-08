package org.inkweft.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Injected candidates exercise review/author transactions, not model recognition accuracy. */
class FormulaFlowTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float=100f)=InkStroke(id(),InkPen.PEN,Color.BLACK,2f,InkTool.STYLUS,listOf(InkSample(x,200f,0),InkSample(x+60f,260f,20)))
    private fun result(s:List<InkStroke>)=RecognizedWriting("\\frac{1}{x^2}=\\alpha",.999f,1,listOf(RecognizedLine("\\frac{1}{x^2}=\\alpha",s.map{it.bounds()}.reduce{a,b->a.union(b)},s.map{it.id},emptyList(),.999f)))
    private suspend fun ready(vm:PageObjectViewModel){withTimeout(10000){while(vm.ui.value.loading||vm.ui.value.busy||vm.ui.value.pending)delay(20)}}
    @Test fun confirmationCorrectionUndoAndRestoreKeepOriginalInkByteExact()=runBlocking{
        val note=app.workspaceRepository.create("公式事务回归",false,PaperStyle.BLANK);val source=stroke()
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        lateinit var vm:PageObjectViewModel;ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){s,_,_->result(s)}};ready(vm)
        val options=BeautyOptions(formula=true,preserveLayout=false,size=24f)
        ins.runOnMainSync{
            vm.observeBeauty(InkUi(strokes=listOf(source),revision=1,loading=false),false,options,false,app)
            vm.beautify(SelectedInk(InkRegion(listOf(EraserPoint(90f,190f),EraserPoint(180f,280f))),1,listOf(source)),options,false,app)
        }
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertTrue(vm.beautyReview.value!!.open);assertEquals(PageObjectKind.FORMULA,vm.beautyReview.value!!.candidate!!.kind)
        assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        ins.runOnMainSync{vm.reviseBeauty("\\frac{",options)};assertNull(vm.beautyReview.value!!.candidate)
        val corrected="\\frac{1}{\\sin\\alpha}=\\csc\\alpha"
        ins.runOnMainSync{vm.reviseBeauty(corrected,options);vm.acceptBeauty()};ready(vm)
        val saved=app.pageObjects.read(note.id).objects.single();assertEquals(corrected,saved.text);assertEquals(listOf(source.id),saved.sourceStrokeIds)
        ins.runOnMainSync{vm.undo()};ready(vm);assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        ins.runOnMainSync{vm.redo()};ready(vm);assertEquals(saved,app.pageObjects.read(note.id).objects.single())
        ins.runOnMainSync{vm.restoreOriginal(saved.id)};ready(vm);assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        assertArrayEquals(InkStrokeCodec.encode(source),InkStrokeCodec.encode(app.inkRepository.read(note.id).strokes.single().stroke))
    }
    @Test fun automaticFormulaNeverCommitsWithoutReviewAndStaleReviewIsRejected()=runBlocking{
        val note=app.workspaceRepository.create("公式自动候选回归",false,PaperStyle.BLANK);val source=stroke()
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        lateinit var vm:PageObjectViewModel;ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){s,_,_->result(s)}};ready(vm)
        val options=BeautyOptions(enabled=true,formula=true,preserveLayout=false,size=24f)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app);vm.observeBeauty(InkUi(strokes=listOf(source),revision=1,loading=false),false,options,false,app)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertFalse(vm.beautyReview.value!!.open);assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        val next=stroke(220f);app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Add(next)))
        ins.runOnMainSync{vm.openBeauty();vm.observeBeauty(InkUi(strokes=listOf(source,next),revision=2,loading=false),true,options,false,app);vm.acceptBeauty()}
        assertTrue(app.pageObjects.read(note.id).objects.isEmpty());assertEquals(2,app.inkRepository.read(note.id).strokes.size)
    }
    @Test fun fractionsGreekAndPowersRenderWithPersistentPartialErase(){
        val o=PageObject(id(),PageObjectKind.FORMULA,x=30f,y=30f,width=320f,height=150f,fontSize=30f,text="\\frac{x^2}{y}=\\alpha")
        val painter=PageObjectPainter(true);val bitmap=Bitmap.createBitmap(400,220,Bitmap.Config.ARGB_8888)
        fun pixels(item:PageObject):IntArray{bitmap.eraseColor(Color.WHITE);painter.draw(Canvas(bitmap),listOf(item),false,CanvasBounds(0.0,0.0,400.0,220.0));return IntArray(400*220).also{bitmap.getPixels(it,0,400,0,0,400,220)}}
        try{
            val before=pixels(o);assertTrue(before.count{it!=Color.WHITE}>500)
            val cut=o.copy(erasures=listOf(TextErasePath(0,o.text.length,60f,listOf(TextErasePoint(100f,65f)))))
            val after=pixels(cut);assertTrue(after.count{it!=Color.WHITE}<before.count{it!=Color.WHITE});assertTrue(after.count{it!=Color.WHITE}>0)
            for(y in 0 until 220)for(x in 200 until 400)assertEquals(before[y*400+x],after[y*400+x])
            assertEquals(cut,PageObjectCodec.decode(PageObjectCodec.encode(listOf(cut))).single())
            pixels(o);File(app.filesDir,"formula-test").apply{mkdirs()}.resolve("fraction-power-greek.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }finally{painter.clear();bitmap.recycle()}
    }
}
