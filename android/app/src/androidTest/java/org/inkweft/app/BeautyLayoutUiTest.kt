package org.inkweft.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class BeautyLayoutUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float,y:Float,w:Float,h:Float)=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(x,y,0),InkSample(x+w,y,10),InkSample(x+w,y+h,20),InkSample(x,y+h,30)))
    @Test fun originalCharacterBoxesAndSettledRunsStayInPlace(){
        val strokes=listOf(stroke(100f,500f,30f,40f),stroke(180f,510f,25f,25f))
        val line=HandwritingLines.split(strokes).single();val range=line.bounds.right-line.bounds.left
        val result=RecognizedWriting("甲乙",1f,1,listOf(RecognizedLine("甲乙",line.bounds,strokes.map{it.id},
            listOf(RecognizedToken("甲",((115-line.bounds.left)/range).toFloat()),RecognizedToken("乙",((192.5-line.bounds.left)/range).toFloat())))))
        val first=beautyObject(strokes,result,BeautyOptions(size=96f),false)
        assertEquals(2,first.glyphs.size)
        first.glyphs.forEachIndexed{i,g->val b=strokes[i].bounds()
            assertEquals(b.left,(first.x+g.x).toDouble(),.01);assertEquals(b.top,(first.y+g.y).toDouble(),.01)
            assertEquals(b.right-b.left,g.width.toDouble(),.01);assertEquals(b.bottom-b.top,g.height.toDouble(),.01)
        }
        val next=beautyObject(listOf(stroke(250f,510f,28f,32f)),"丙",BeautyOptions(size=96f),false)
        val merged=checkNotNull(appendBeauty(first.copy(glyphs=first.glyphs.mapIndexed{i,g->g.copy(hidden=i==0)}),next))
        first.glyphs.forEachIndexed{i,g->assertEquals(first.x+g.x,merged.x+merged.glyphs[i].x,.01f);assertEquals(first.y+g.y,merged.y+merged.glyphs[i].y,.01f)}
        assertTrue(merged.glyphs[0].hidden);assertEquals("乙\n丙",merged.visibleText())
    }
    @Test fun erasingOneLegacyCharacterKeepsOthersAndSurvivesUndoRedoAndReopen(){
        val n=runBlocking{app.workspaceRepository.create("逐字擦除验收",false,PaperStyle.BLANK)}
        val source=stroke(100f,500f,180f,40f)
        val before=PageObject(id(),PageObjectKind.TEXT,x=100f,y=500f,width=180f,height=60f,text="甲乙丙",fontSize=40f,sourceStrokeIds=listOf(source.id))
        runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(source)));app.pageObjects.save(n.id,0,id(),listOf(before))}
        val vm=ViewModelProvider(compose.activity,PageObjectViewModel.Factory(n.id,app.pageObjects))["objects-${n.id}",PageObjectViewModel::class.java]
        compose.waitUntil(10_000){!vm.ui.value.loading}
        val boxes=TextStyles.positioned(before);assertEquals(3,boxes.size);val g=boxes[1]
        compose.runOnIdle{vm.eraseBeauty(listOf(InkSample(before.x+g.x+g.width/2,before.y+g.y+g.height/2,0)),1f,true)}
        compose.waitUntil(10_000){!vm.ui.value.busy&&!vm.ui.value.pending&&vm.ui.value.objects.single().glyphs.any{it.hidden}}
        val erased=runBlocking{app.pageObjects.read(n.id).objects.single()}
        assertFalse(erased.hidden);assertEquals("甲丙",erased.visibleText());assertEquals(boxes[0],erased.glyphs[0]);assertEquals(boxes[2],erased.glyphs[2])
        compose.runOnIdle{vm.undo()};compose.waitUntil(10_000){vm.ui.value.objects.single()==before&&!vm.ui.value.busy}
        compose.runOnIdle{vm.redo()};compose.waitUntil(10_000){vm.ui.value.objects.single()==erased&&!vm.ui.value.busy}
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        assertEquals(erased,runBlocking{app.pageObjects.read(n.id).objects.single()})
        assertArrayEquals(InkStrokeCodec.encode(source),InkStrokeCodec.encode(runBlocking{app.inkRepository.read(n.id).strokes.single().stroke}))
    }
    @Test fun localEraserSubtractsPixelsAndPersistsWithoutTouchingAdjacentLetters(){
        val n=runBlocking{app.workspaceRepository.create("局部字形擦除验收",false,PaperStyle.BLANK)}
        val source=stroke(100f,500f,180f,80f)
        val before=PageObject(id(),PageObjectKind.TEXT,x=100f,y=500f,width=180f,height=80f,text="田田",fontSize=60f,
            sourceStrokeIds=listOf(source.id),glyphs=listOf(TextGlyph(0,1,0f,0f,60f,60f),TextGlyph(1,2,100f,0f,60f,60f)))
        fun pixels(o:PageObject):IntArray {
            val bitmap=android.graphics.Bitmap.createBitmap(300,650,android.graphics.Bitmap.Config.ARGB_8888)
            PageObjectPainter().draw(android.graphics.Canvas(bitmap),listOf(o),false,CanvasBounds(0.0,0.0,300.0,650.0))
            return IntArray(300*650).also{bitmap.getPixels(it,0,300,0,0,300,650);bitmap.recycle()}
        }
        runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(source)));app.pageObjects.save(n.id,0,id(),listOf(before))}
        val vm=ViewModelProvider(compose.activity,PageObjectViewModel.Factory(n.id,app.pageObjects))["objects-${n.id}",PageObjectViewModel::class.java]
        compose.waitUntil(10_000){!vm.ui.value.loading}
        compose.runOnIdle{vm.eraseBeauty(listOf(InkSample(125f,495f,0),InkSample(125f,565f,10)),5f,false)}
        compose.waitUntil(10_000){!vm.ui.value.busy&&!vm.ui.value.pending&&vm.ui.value.objects.single().erasures.isNotEmpty()}
        val after=runBlocking{app.pageObjects.read(n.id).objects.single()};val originalPixels=pixels(before);val cutPixels=pixels(after)
        assertFalse(after.hidden);assertEquals(before.glyphs,after.glyphs)
        var removed=0;var retained=0
        for(y in 500 until 560)for(x in 100 until 260){
            val index=y*300+x
            if(x in 121..128&&originalPixels[index]!=0){assertEquals(0,cutPixels[index]);removed++}
            if(x<119||x>131){assertEquals(originalPixels[index],cutPixels[index]);if(x<160&&cutPixels[index]!=0)retained++}
        }
        assertTrue(removed>0);assertTrue(retained>0)
        val next=beautyObject(listOf(stroke(110f,510f,30f,30f)),"甲",BeautyOptions(font=before.font,size=before.fontSize,spacing=before.lineSpacing),false)
        val merged=checkNotNull(appendBeauty(after,next.copy(color=after.color)));assertEquals(before.text.length,merged.erasures.single().end)
        compose.runOnIdle{vm.undo()};compose.waitUntil(10_000){!vm.ui.value.busy&&vm.ui.value.objects.single().erasures.isEmpty()}
        assertArrayEquals(originalPixels,pixels(vm.ui.value.objects.single()))
        compose.runOnIdle{vm.redo()};compose.waitUntil(10_000){!vm.ui.value.busy&&vm.ui.value.objects.single().erasures.isNotEmpty()}
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        val reopened=runBlocking{app.pageObjects.read(n.id).objects.single()}
        assertEquals(after,reopened);assertArrayEquals(cutPixels,pixels(reopened))
        assertArrayEquals(cutPixels,pixels(PageObjectCodec.decode(PageObjectCodec.encode(listOf(reopened))).single()))
    }

}
