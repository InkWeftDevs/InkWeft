package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class BeautyQualityTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float=100f,y:Float=200f)=InkStroke(id(),InkPen.PEN,0xff2255aa.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,y,0),InkSample(x+25,y+40,40)))
    private fun result(s:List<InkStroke>,text:String="甲")=RecognizedWriting(text,.95f,1,listOf(RecognizedLine(text,s.map{it.bounds()}.reduce{a,b->a.union(b)},s.map{it.id},text.mapIndexed{i,c->RecognizedToken(c.toString(),(i+.5f)/text.length,.95f)},.95f)))
    @Test fun stableCandidatesDoNotOverrideAmbiguityFormulaOrSpatialEvidence(){
        val s=listOf(stroke());val good=result(s)
        assertTrue(BeautyQuality.decide(s,good,good,true).automatic)
        assertFalse(BeautyQuality.decide(s,good,good,false).automatic)
        assertFalse(BeautyQuality.decide(s,good,result(s,"乙"),true).automatic)
        for(text in listOf("H工","a²+b²","x=2")){val r=result(s,text);assertFalse(BeautyQuality.decide(s,r,r,true).automatic)}
        val low=good.copy(confidence=.4f);assertFalse(BeautyQuality.decide(s,low,low,true).automatic)
        val rival=good.copy(regions=good.regions.map{it.copy(tokens=listOf(RecognizedToken("甲",.5f,.8f,"申",.75f)))})
        assertFalse(BeautyQuality.decide(s,good,rival,true).automatic)
    }
    @Test fun lateStrokeReplacesOnlyItsFragmentAndRefusesErasedFragment(){
        TextStyles.initialize(ins.targetContext)
        val a=stroke();val b=stroke(250f);val dot=stroke(105f,190f)
        val first=beautyObject(listOf(a),"甲",BeautyOptions(),false)
        val second=beautyObject(listOf(b),"乙",BeautyOptions(),false)
        val old=checkNotNull(appendBeauty(first,second));val run=old.textRuns.first()
        val fresh=beautyObject(listOf(a,dot),"申",BeautyOptions(),false)
        val next=checkNotNull(replaceBeautyFragment(old,fresh,setOf(run.id)))
        assertEquals("申乙",next.text)
        val oldOther=old.glyphs.last();val newOther=next.glyphs.last()
        assertEquals(old.x+oldOther.x,next.x+newOther.x,.001f);assertEquals(old.y+oldOther.y,next.y+newOther.y,.001f)
        assertEquals(old.textRuns.last().id,next.textRuns.last().id)
        assertEquals(setOf(a.id,b.id,dot.id),next.sourceStrokeIds.toSet())
        assertNull(replaceBeautyFragment(old.copy(erasures=listOf(TextErasePath(0,1,2f,listOf(TextErasePoint(1f,1f))))),fresh,setOf(run.id)))
    }
    @Test fun delayedRecognitionCannotCommitAfterNewInkAndManualReviewIsRecoverable()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("FQ 迟到结果",false,PaperStyle.BLANK)
        val source=stroke();app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var block=true
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){s,_,_->if(block){entered.complete(Unit);release.await()};result(s)}}
        withTimeout(10000){while(vm.ui.value.loading)delay(20)}
        val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app);vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){entered.await()}
        val second=stroke(200f);app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Add(second)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source,second),loading=false,revision=2),true,options,false,app)}
        release.complete(Unit);delay(400);assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        block=false
        ins.runOnMainSync{
            vm.observeBeauty(InkUi(strokes=listOf(source,second),loading=false,revision=2),false,options.copy(enabled=false),false,app)
            vm.beautify(SelectedInk(InkRegion(listOf(EraserPoint(95f,195f),EraserPoint(130f,245f))),2,listOf(source)),options,false,app)
        }
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertTrue(vm.beautyReview.value!!.open);assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        ins.runOnMainSync{vm.acceptBeauty()}
        withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.objects.isEmpty())delay(20)}
        val saved=app.pageObjects.read(note.id).objects.single();assertEquals(2L,saved.textRuns.single().sourceRevision)
        ins.runOnMainSync{vm.undo()};withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.objects.isNotEmpty())delay(20)}
        assertEquals(2,app.inkRepository.read(note.id).strokes.size)
        ins.runOnMainSync{vm.redo()};withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.objects.isEmpty())delay(20)}
        assertEquals(saved,app.pageObjects.read(note.id).objects.single())
    }
}
