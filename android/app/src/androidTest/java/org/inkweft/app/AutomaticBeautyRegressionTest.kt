package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.NoteDatabase
import org.inkweft.data.PageObjectRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Injected candidates test ownership and interaction, never recognition accuracy. */
class AutomaticBeautyRegressionTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float=100f,y:Float=200f)=InkStroke(id(),InkPen.PEN,0xff2255aa.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(x,y,0),InkSample(x+25,y+40,40)))
    private fun result(strokes:List<InkStroke>,text:String="甲")=RecognizedWriting(text,.95f,1,
        listOf(RecognizedLine(text,strokes.map{it.bounds()}.reduce{a,b->a.union(b)},strokes.map{it.id},
            text.mapIndexed{i,c->RecognizedToken(c.toString(),(i+.5f)/text.length,.95f)},.95f)))
    private suspend fun ready(vm:PageObjectViewModel){withTimeout(10000){while(vm.ui.value.loading||vm.ui.value.busy||vm.ui.value.pending)delay(20)}}

    @Test fun missingOrDuplicateSourcesCannotAutomaticallyHideInk(){
        val sources=listOf(stroke(),stroke(y=350f));val missing=result(sources.take(1))
        assertFalse(BeautyQuality.decide(sources,missing,missing,true).automatic)
        val duplicate=missing.copy(regions=missing.regions+missing.regions)
        assertFalse(BeautyQuality.decide(sources,duplicate,duplicate,true).automatic)
        TextStyles.initialize(ins.targetContext)
        val review=prepareBeautyReview(sources,missing,BeautyOptions(),false,1,0,null,emptySet(),false,sources,emptyList())
        assertNull(review.candidate)
    }

    @Test fun reviewDefaultsToOriginalAndPreviewIsExplicit(){
        TextStyles.initialize(ins.targetContext)
        val sources=listOf(stroke());val review=prepareBeautyReview(sources,result(sources),BeautyOptions(),false,1,0,null,emptySet(),false,sources,emptyList())
        assertFalse(review.preview)
        assertTrue(beautyPreviewObjects(emptyList(),review.copy(open=true)).isEmpty())
        assertEquals(listOf(review.candidate),beautyPreviewObjects(emptyList(),review.copy(open=true,preview=true)))
    }

    @Test fun shortCandidateCannotAutomaticallyCoverWideSourceAndPaddingTokensAreRejected(){
        TextStyles.initialize(ins.targetContext)
        val sources=listOf(stroke(),stroke(230f),stroke(360f));val short=result(sources)
        for(preserve in listOf(true,false)){
            val review=prepareBeautyReview(sources,short,BeautyOptions(preserveLayout=preserve),false,1,0,null,emptySet(),true,sources,emptyList())
            assertNotNull(review.reason);assertTrue(review.candidate!!.width<100f)
        }
        val padding=short.copy(regions=short.regions.map{it.copy(tokens=listOf(RecognizedToken("甲",1.2f,.95f)))})
        assertFalse(BeautyQuality.decide(sources,padding,padding,true).automatic)
    }

    @Test fun appendedInPlaceRunKeepsSettledSizeBaselineAndUnrelatedGlyphs(){
        TextStyles.initialize(ins.targetContext)
        val first=stroke();val next=InkStroke(id(),InkPen.PEN,first.color,2f,InkTool.STYLUS,listOf(InkSample(155f,207f,0),InkSample(177f,235f,40)))
        val old=beautyObject(listOf(first),result(listOf(first)),BeautyOptions(),false)
        val review=prepareBeautyReview(listOf(next),result(listOf(next),"乙"),BeautyOptions(),false,2,1,old,emptySet(),true,listOf(first,next),listOf(old))
        val combined=checkNotNull(review.candidate);val a=combined.textRuns.first();val b=combined.textRuns.last()
        assertEquals(a.size,b.size,.001f);assertEquals(a.baseline,b.baseline,.001f)
        assertEquals(old.glyphs.first().x+old.x,combined.glyphs.first().x+combined.x,.001f)
        assertEquals(old.glyphs.first().y+old.y,combined.glyphs.first().y+combined.y,.001f)
        assertEquals(old.textRuns.first().id,a.id)
    }

    @Test fun alignedAppendOutsidePageKeepsOriginalButWorldCanvasAllowsIt(){
        TextStyles.initialize(ins.targetContext)
        val options=BeautyOptions()
        val first=InkStroke(id(),InkPen.PEN,0xff2255aa.toInt(),2f,InkTool.STYLUS,
            listOf(InkSample(100f,200f,0),InkSample(125f,280f,40)))
        val small=InkStroke(id(),InkPen.PEN,first.color,2f,InkTool.STYLUS,
            listOf(InkSample(100f,200f,0),InkSample(110f,220f,40)))
        val oldTemplate=beautyObject(listOf(first),result(listOf(first)),options,false)
        val smallTemplate=beautyObject(listOf(small),result(listOf(small),"乙"),options,false)
        val nextX=1000f-smallTemplate.width-1f
        val oldX=nextX-oldTemplate.width-8f
        fun moved(s:InkStroke,dx:Float,dy:Float)=InkStroke(s.id,s.pen,s.color,s.width,s.tool,
            s.samples.map{it.copy(x=it.x+dx,y=it.y+dy)})
        val settled=moved(first,oldX-first.samples.first().x,0f)
        val old=beautyObject(listOf(settled),result(listOf(settled)),options,false)
        val next=moved(small,nextX-small.samples.first().x,
            old.y+old.textRuns.single().baseline-smallTemplate.y-smallTemplate.textRuns.single().baseline)
        val fresh=beautyObject(listOf(next),result(listOf(next),"乙"),options,false)
        assertTrue(fresh.x+fresh.width<=1000f)
        val aligned=NaturalText.alignToLine(old,fresh,listOf(next))
        assertTrue("Inherited size must exercise the page edge",aligned.x+aligned.width>1000.01f)
        val page=prepareBeautyReview(listOf(next),result(listOf(next),"乙"),options,false,2,1,old,emptySet(),true,listOf(settled,next),listOf(old))
        assertNull(page.candidate);assertNotNull(page.reason)
        val world=prepareBeautyReview(listOf(next),result(listOf(next),"乙"),options,true,2,1,old,emptySet(),true,listOf(settled,next),listOf(old))
        assertNotNull(world.candidate);assertNull(world.reason)
    }

    @Test fun rejectedPartialUnitIsRetriedAfterSupplementWithoutConfirmation()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 补写",false,PaperStyle.BLANK);val body=stroke()
        val dot=InkStroke(id(),InkPen.PEN,body.color,2f,InkTool.STYLUS,listOf(InkSample(108f,194f,0)))
        var calls=0;lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){sources,_,_->calls++;result(sources).let{if(sources.size==1)it.copy(confidence=.3f)else it}}}
        ready(vm);val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(body)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(body),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Add(dot)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(body,dot),loading=false,revision=2),false,options,false,app)}
        withTimeout(10000){while(vm.ui.value.objects.isEmpty()||vm.ui.value.busy){if(calls==4&&vm.beautyReview.value!=null)assertNull(vm.beautyReview.value!!.reason);delay(20)}}
        assertEquals(setOf(body.id,dot.id),app.pageObjects.read(note.id).objects.single().sourceStrokeIds.toSet())
        assertEquals(4,calls);assertNull(vm.beautyReview.value)
    }

    @Test fun multipleStableUnitsUseSuccessiveObjectReceipts()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 两行",false,PaperStyle.BLANK);val sources=listOf(stroke(),stroke(y=450f))
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){unit,_,_->result(unit)}}
        ready(vm);val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Replace(emptyList(),sources)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=sources,loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.ui.value.objects.size<2||vm.ui.value.busy)delay(20)}
        val saved=app.pageObjects.read(note.id);assertEquals(2L,saved.revision)
        assertEquals(sources.map{it.id}.toSet(),saved.objects.flatMap{it.sourceStrokeIds}.toSet())
        ins.runOnMainSync{vm.undo()};ready(vm)
        assertEquals(listOf(saved.objects.first()),app.pageObjects.read(note.id).objects)
        assertEquals(2,app.inkRepository.read(note.id).strokes.size)
    }

    @Test fun unrelatedWritingDoesNotRetryRejectedUnitAndCanConvert()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 片段隔离",false,PaperStyle.BLANK)
        val rejected=stroke();val remote=stroke(y=450f);val calls=mutableListOf<Set<String>>()
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){sources,_,padded->
            calls.add(sources.map{it.id}.toSet());result(sources,if(rejected.id in sources.map{it.id}&&padded)"乙"else"甲")}}
        ready(vm);val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(rejected)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(rejected),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertFalse(vm.beautyReview.value!!.open)
        app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Add(remote)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(rejected,remote),loading=false,revision=2),false,options,false,app)}
        withTimeout(10000){while(calls.size<4)delay(20)}
        assertEquals(setOf(remote.id),calls[2]);assertEquals(setOf(remote.id),calls[3])
        withTimeout(10000){while(vm.ui.value.objects.isEmpty()||vm.ui.value.busy)delay(20)}
        assertEquals(listOf(remote.id),app.pageObjects.read(note.id).objects.single().sourceStrokeIds)
        assertEquals(2,app.inkRepository.read(note.id).strokes.size)
        ins.runOnMainSync{vm.dismissBeauty();vm.observeBeauty(InkUi(strokes=listOf(rejected,remote),loading=false,revision=2),false,options,false,app)}
        delay(1200);assertEquals(4,calls.size)
    }

    @Test fun reliableParagraphAutomaticallyCommitsAndIsUndoable()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 自动段落",false,PaperStyle.BLANK);val source=stroke()
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){sources,_,_->result(sources)}}
        ready(vm);val options=BeautyOptions(enabled=true,preserveLayout=false)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.ui.value.objects.isEmpty()&&vm.beautyReview.value==null)delay(20)}
        ready(vm);assertNull(vm.beautyReview.value)
        assertEquals(listOf(source.id),app.pageObjects.read(note.id).objects.single().sourceStrokeIds)
        ins.runOnMainSync{vm.undo()};ready(vm)
        assertTrue(app.pageObjects.read(note.id).objects.isEmpty())
        assertEquals(1,app.inkRepository.read(note.id).strokes.size)
    }

    @Test fun unknownAutomaticReceiptKeepsOriginalVisibleAndRetriesWithoutDuplicating()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 未确认回执",false,PaperStyle.BLANK);val source=stroke();val next=stroke(y=450f)
        val db=NoteDatabase.open(ins.targetContext);var fail=true
        try{
            val repository=PageObjectRepository(db){if(fail){fail=false;throw java.io.IOException("Synthetic lost acknowledgement")}}
            lateinit var vm:PageObjectViewModel
            ins.runOnMainSync{vm=PageObjectViewModel(note.id,repository){sources,_,_->result(sources)}}
            ready(vm);val options=BeautyOptions(enabled=true)
            ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
            app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
            ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
            withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.error==null)delay(20)}
            assertTrue(vm.ui.value.pending);assertTrue(vm.ui.value.automaticPending);assertTrue(vm.ui.value.objects.isEmpty())
            assertEquals(1,app.pageObjects.read(note.id).objects.size)
            app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Add(next)))
            ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source,next),loading=false,revision=2),false,options,false,app);vm.retry()}
            ready(vm);assertFalse(vm.ui.value.automaticPending)
            assertEquals(1L,app.pageObjects.read(note.id).revision)
            assertEquals(listOf(source.id),vm.ui.value.objects.single().sourceStrokeIds)
            assertEquals(2,app.inkRepository.read(note.id).strokes.size)
        }finally{db.close()}
    }

    @Test fun manualApplyOfAutomaticReviewRetainsExplicitTransaction()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 主动校对事务",false,PaperStyle.BLANK);val source=stroke()
        val db=NoteDatabase.open(ins.targetContext);var fail=true
        try{
            val repository=PageObjectRepository(db){if(fail){fail=false;throw java.io.IOException("Synthetic lost acknowledgement")}}
            lateinit var vm:PageObjectViewModel
            ins.runOnMainSync{vm=PageObjectViewModel(note.id,repository){sources,_,_->result(sources).copy(confidence=.3f)}}
            ready(vm);val options=BeautyOptions(enabled=true)
            ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
            app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
            ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
            withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
            assertTrue(vm.beautyReview.value!!.automatic)
            ins.runOnMainSync{vm.openBeauty();vm.reviseBeauty("乙",options);vm.acceptBeauty()}
            withTimeout(10000){while(vm.ui.value.busy||vm.ui.value.error==null)delay(20)}
            assertTrue(vm.ui.value.pending);assertFalse(vm.ui.value.automaticPending)
            ins.runOnMainSync{vm.retry()};ready(vm)
            assertEquals("乙",app.pageObjects.read(note.id).objects.single().text)
            assertEquals(1,app.inkRepository.read(note.id).strokes.size)
        }finally{db.close()}
    }

    @Test fun cachedCandidateIsInvalidatedWhenSameSourceIdHasNewCuts()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 缓存原迹版本",false,PaperStyle.BLANK);val source=stroke();val remote=stroke(y=80f)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){sources,_,_->
            if(sources.any{it.id==source.id&&it.cuts.isNotEmpty()}){entered.complete(Unit);release.await()}
            result(sources).let{if(sources.any{it.id==source.id})it.copy(confidence=.3f)else it}
        }}
        ready(vm);val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        val cut=InkCut(id(),2f,listOf(EraserPoint(112f,213f),EraserPoint(118f,225f)))
        app.inkRepository.save(CommitInk(id(),note.id,1,InkMutation.Cut(EraseSelection(cut,listOf(source.id)))))
        app.inkRepository.save(CommitInk(id(),note.id,2,InkMutation.Add(remote)))
        val edited=source.withCuts(listOf(cut))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(edited,remote),loading=false,revision=3),false,options,false,app)}
        try{
            withTimeout(10000){entered.await()}
            assertNull("Old candidate must not be rebased onto changed ink",vm.beautyReview.value)
            assertEquals(listOf(remote.id),app.pageObjects.read(note.id).objects.single().sourceStrokeIds)
        }finally{release.complete(Unit)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertEquals(cut.id,vm.beautyReview.value!!.strokes.single().cuts.single().id)
    }

    @Test fun fullObjectBudgetKeepsOriginalAndOffersReviewInsteadOfStayingBusy()=runBlocking {
        TextStyles.initialize(ins.targetContext)
        val note=app.workspaceRepository.create("ABF 对象容量",false,PaperStyle.BLANK);val source=stroke()
        val neighbours=List(32){PageObject(id(),PageObjectKind.TEXT,x=0f,y=0f,width=24f,height=24f,text="A",fontSize=12f)}
        app.pageObjects.save(note.id,0,id(),neighbours)
        lateinit var vm:PageObjectViewModel
        ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects){sources,_,_->result(sources)}}
        ready(vm);val options=BeautyOptions(enabled=true)
        ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
        app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=listOf(source),loading=false,revision=1),false,options,false,app)}
        withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
        assertFalse(vm.ui.value.pending);assertFalse(vm.ui.value.busy)
        assertEquals(neighbours,vm.ui.value.objects);assertEquals(neighbours,app.pageObjects.read(note.id).objects)
        assertEquals("美化待校对",vm.beautyStatus.value);assertNotNull(vm.beautyReview.value!!.reason)
        assertEquals(1,app.inkRepository.read(note.id).strokes.size)
    }
}
