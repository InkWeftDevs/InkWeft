package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ContinuousWritingUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    @After fun restorePreferences(){BeautyStore(app).save(BeautyOptions());app.getSharedPreferences("inkweft-editor",0).edit().remove("case-x").remove("case-y").commit()}
    private fun id()=UUID.randomUUID().toString()
    private fun open(pages:Int=1,seed:suspend(Note)->Unit={}):Note {
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        compose.runOnIdle{BeautyStore(app).save(BeautyOptions())}
        val note=runBlocking{app.workspaceRepository.create("连续书写验收",false,PaperStyle.BLANK).also{n->var previous=n.id;repeat(pages-1){previous=app.pages.addAfter(n.id,previous,id()).id};app.pages.select(n.id,n.id);seed(n)}}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15_000){app.navigationReady.value&&compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(10_000){var ready=false;compose.runOnIdle{ready=canvas(note.id).inputReady};ready}
        return note
    }
    private fun canvas(page:String):InkCanvasView=checkNotNull(compose.activity.window.decorView.findViewWithTag("ink-page-$page"))
    private fun single():InkCanvasView {
        compose.singlePageEditor();compose.waitForIdle()
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun line(v:InkCanvasView,points:List<Pair<Float,Float>>){
        val now=SystemClock.uptimeMillis();val density=v.resources.displayMetrics.density.toDouble()
        points.forEachIndexed{i,(x,y)->
            val p=v.snapshotViewport().worldToScreen(x.toDouble(),y.toDouble(),v.width.toDouble(),v.height.toDouble(),density)
            val properties=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val coords=MotionEvent.PointerCoords().apply{this.x=p.x.toFloat();this.y=p.y.toFloat();pressure=.5f;size=.1f}
            val action=when(i){0->MotionEvent.ACTION_DOWN;points.lastIndex->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val event=MotionEvent.obtain(now,now+i*35L,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            try{assertTrue(v.dispatchTouchEvent(event))}finally{event.recycle()}
        }
    }
    private fun hi(page:String){
        listOf(listOf(200f to 300f,200f to 400f),listOf(250f to 300f,250f to 400f),listOf(200f to 350f,250f to 350f),
            listOf(290f to 300f,340f to 300f),listOf(315f to 300f,315f to 400f),listOf(290f to 400f,340f to 400f)).forEachIndexed{index,points->compose.runOnIdle{line(canvas(page),points)};compose.waitUntil(10_000){runBlocking{app.inkRepository.read(page).strokes.size}==index+1}}
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}

    @Test fun tapeTapInContinuousPagesTogglesExactlyOnceForFingerAndStylus(){
        val tape=PageObject(id(),PageObjectKind.TAPE,300f,100f,400f,80f)
        val n=open{app.pageObjects.save(it.id,0,id(),listOf(tape))}
        compose.onNodeWithTag("continuous-page-1").performTouchInput{click(Offset(width*.5f,width*.14f))}
        compose.waitUntil(10000){runBlocking{app.pageObjects.read(n.id).objects.single().revealed}};compose.waitForIdle()
        compose.runOnIdle{line(canvas(n.id),listOf(500f to 140f,500f to 140f))}
        compose.waitUntil(10000){!runBlocking{app.pageObjects.read(n.id).objects.single().revealed}}
        assertTrue(runBlocking{app.inkRepository.read(n.id).strokes.isEmpty()})
    }

    @Test fun pressureOnlyDuplicateSamplesReopenWithoutChangingAuthorData(){
        val samples=listOf(InkSample(610.605f,774.094f,463,.3f,.2f,.1f),InkSample(610.605f,774.094f,463,.31f,.2f,.1f),InkSample(620f,780f,480,.5f,.2f,.1f))
        val original=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),3f,InkTool.STYLUS,samples)
        val bytes=InkStrokeCodec.encode(original)
        InkPen.entries.forEach{pen->InkBrushes.stroke(InkStroke(id(),pen,0xff222222.toInt(),3f,InkTool.STYLUS,samples))}
        val note=open{n->app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(original)))}
        compose.waitForIdle();compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000){app.navigationReady.value&&compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();single();compose.waitForIdle()
        assertArrayEquals(bytes,InkStrokeCodec.encode(runBlocking{app.inkRepository.read(note.id).strokes.single().stroke}))
    }

    @Test fun continuousSeamHasNoGapAndOneGesturePersistsOnBothSides(){
        val note=open(2);val pages=runBlocking{app.pages.activePages(note.id)}
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(1)
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.2f),Offset(centerX,height*.7f),500)}
        compose.waitUntil(10_000){var ready=false;compose.runOnIdle{ready=pages.all{canvas(it.id).inputReady}};app.navigationReady.value&&ready}
        compose.runOnIdle{
            val a=canvas(pages[0].id);val b=canvas(pages[1].id);val pa=IntArray(2);val pb=IntArray(2);a.getLocationOnScreen(pa);b.getLocationOnScreen(pb)
            assertEquals(pa[1]+a.height,pb[1]);assertEquals(0.0,a.snapshotViewport().visible(a.width.toDouble(),a.height.toDouble(),a.resources.displayMetrics.density.toDouble()).left,.01)
            line(a,listOf(400f to 1350f,420f to 1414f,440f to 1470f))
        }
        compose.waitUntil(15_000){runBlocking{pages.all{app.inkRepository.read(it.id).strokes.size==1}}}
        val a=runBlocking{app.inkRepository.read(pages[0].id).strokes.single().stroke};val b=runBlocking{app.inkRepository.read(pages[1].id).strokes.single().stroke}
        assertEquals(1414f,a.samples.last().y);assertEquals(0f,b.samples.first().y);assertEquals(a.samples.last().x,b.samples.first().x)
        shot("v20-seam.png")
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{pages.all{InkSession(app.inkRepository.read(it.id)).visibleDraft().isEmpty()}}}
        compose.onNodeWithTag("ink-redo").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{pages.all{InkSession(app.inkRepository.read(it.id)).visibleDraft().size==1}}}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(a.samples,runBlocking{app.inkRepository.read(pages[0].id).strokes.single().stroke.samples})
    }
    @Test fun crossPageEraserGroupsInkAndBeautyInBothDirections(){
        runBlocking{EraserSettingsStore(app).save(EraserSettings(whole=true))}
        val note=open(2){n->
            app.pages.activePages(n.id).forEachIndexed{i,p->
                val source=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),3f,InkTool.STYLUS,listOf(InkSample(10f,10f,0)))
                app.inkRepository.save(CommitInk(id(),p.id,0,InkMutation.Add(source)))
                val text=PageObject(id(),PageObjectKind.TEXT,x=380f,y=if(i==0)1340f else 20f,width=120f,height=60f,text="Seam",sourceStrokeIds=listOf(source.id))
                app.pageObjects.save(p.id,0,id(),listOf(text))
            }
        }
        val pages=runBlocking{app.pages.activePages(note.id)}
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(1)
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.2f),Offset(centerX,height*.7f),500)}
        compose.waitUntil(10_000){var ready=false;compose.runOnIdle{ready=pages.all{canvas(it.id).inputReady}};app.navigationReady.value&&ready}
        compose.runOnIdle{line(canvas(pages[0].id),listOf(430f to 1300f,430f to 1500f))}
        compose.waitUntil(10_000){runBlocking{pages.all{app.inkRepository.read(it.id).strokes.size==2}}}
        shot("v20-cross-erase-before.png")
        compose.runOnIdle{
            val v=canvas(pages[0].id);val origin=IntArray(2);v.getLocationOnScreen(origin)
            val p=v.snapshotViewport().worldToScreen(430.0,1414.0,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble())
            val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try{for(y in origin[1]+v.height-1..origin[1]+v.height){val color=image.getPixel((origin[0]+p.x).toInt(),y);assertTrue("Paper seam must not punch a white row through ink",android.graphics.Color.red(color)<120)}}finally{image.recycle()}
        }
        compose.onNodeWithTag("ink-tool-3").performScrollTo().performClick()
        fun erased(value:Boolean){compose.waitUntil(10_000){runBlocking{pages.all{p->app.pageObjects.read(p.id).objects.single().glyphs.any{it.hidden}==value&&app.inkRepository.read(p.id).strokes.count{it.visible}==if(value)1 else 2}}}}
        compose.runOnIdle{line(canvas(pages[0].id),listOf(430f to 1300f,430f to 1500f))};erased(true);shot("v20-cross-erase-after.png")
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();erased(false);shot("v20-cross-erase-undo.png")
        compose.onNodeWithTag("ink-redo").performScrollTo().performClick();erased(true)
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();erased(false)
        compose.runOnIdle{line(canvas(pages[1].id),listOf(430f to 100f,430f to -100f))};erased(true)
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick();erased(false)
        runBlocking{EraserSettingsStore(app).save(EraserSettings())}
    }
    @Test fun polygonSelectionHighlightsAndDeletesOnlyContainedBeauty(){
        var inside="";var outside=""
        val note=open{n->
            listOf(250f,430f).forEachIndexed{i,pos->
                val source=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),3f,InkTool.STYLUS,listOf(InkSample(pos,pos,0)))
                app.inkRepository.save(CommitInk(id(),n.id,i.toLong(),InkMutation.Add(source)))
                val o=PageObject(id(),PageObjectKind.TEXT,x=pos,y=pos,width=60f,height=60f,text=if(i==0)"IN"else"OUT",sourceStrokeIds=listOf(source.id))
                if(i==0)inside=o.id else outside=o.id
                val current=app.pageObjects.read(n.id);app.pageObjects.save(n.id,current.revision,id(),current.objects+o)
            }
        }
        val v=single();compose.onNodeWithTag("ink-select").performScrollTo().performClick();compose.onNodeWithTag("ink-select").performClick();compose.onNodeWithTag("lasso-free").performClick();compose.onNodeWithContentDescription("关闭套索").performClick()
        var points=emptyList<Offset>()
        compose.runOnIdle{points=listOf(200.0 to 200.0,600.0 to 200.0,200.0 to 600.0,200.0 to 200.0).map{(x,y)->val p=v.snapshotViewport().worldToScreen(x,y,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());Offset(p.x.toFloat(),p.y.toFloat())}}
        compose.onNodeWithTag("selection-overlay").performTouchInput{down(points[0]);points.drop(1).forEach{moveTo(it)};up()}
        shot("v20-lasso-contained.png")
        compose.onNodeWithTag("mixed-delete").assertIsDisplayed().performClick()
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(note.id).objects.first{it.id==inside}.hidden}}
        assertFalse(runBlocking{app.pageObjects.read(note.id).objects.first{it.id==outside}.hidden})
        shot("v20-lasso-deleted.png")
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(note.id).objects.none{it.hidden}}}
    }
    @Test fun automaticBeautyIsInlineErasableUndoableAndPersistent(){
        val note=open();compose.openBeautySettings();compose.onNodeWithTag("beauty-replace-font").performClick();compose.onNodeWithTag("beauty-enabled").performClick();compose.onNodeWithTag("beauty-close").performClick()
        hi(note.id)
        compose.waitUntil(45_000){runBlocking{app.pageObjects.read(note.id).objects.any{!it.hidden}}}
        val beauty=runBlocking{app.pageObjects.read(note.id).objects.single()}
        assertEquals(6,beauty.sourceStrokeIds.size);assertTrue(beauty.text.isNotBlank());assertEquals(TextFont.WENKAI,beauty.font)
        compose.onNodeWithTag("font-beauty-dialog").assertDoesNotExist();shot("v20-auto-beauty.png")
        compose.onNodeWithTag("ink-tool-3").performScrollTo().performClick()
        compose.runOnIdle{line(canvas(note.id),listOf((beauty.x+8) to (beauty.y+12),(beauty.x+beauty.width-8) to (beauty.y+12)))}
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(note.id).objects.single().erasures.isNotEmpty()}}
        compose.onNodeWithTag("ink-undo").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{!app.pageObjects.read(note.id).objects.single().erasures.isNotEmpty()}}
        compose.onNodeWithTag("ink-redo").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.pageObjects.read(note.id).objects.single().erasures.isNotEmpty()}}
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        assertTrue(runBlocking{app.pageObjects.read(note.id).objects.single().erasures.isNotEmpty()})
        assertEquals(6,runBlocking{app.inkRepository.read(note.id).strokes.size})
    }
    @Test fun floatingCaseMovesAndPageBoundsStayFinite(){
        open();val old=compose.onNodeWithTag("floating-pen-case").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("pen-case-handle").performTouchInput{swipe(center,center+Offset(280f,40f),500)}
        val moved=compose.onNodeWithTag("floating-pen-case").fetchSemanticsNode().boundsInRoot
        assertTrue(moved.left>old.left+100);shot("v20-floating-case.png")
        val v=single()
        compose.runOnIdle{
            v.zoomBy(4.0)
            val now=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,500f,300f,0);v.dispatchTouchEvent(down);down.recycle()
            val move=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_MOVE,-50000f,-50000f,0);v.dispatchTouchEvent(move);move.recycle()
            val up=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,-50000f,-50000f,0);v.dispatchTouchEvent(up);up.recycle()
            val bounds=v.snapshotViewport().visible(v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble())
            assertTrue("right=$bounds viewport=${v.snapshotViewport()} embedded=${v.embeddedPage} size=${v.width}x${v.height}",bounds.right<=1000.01);assertTrue("bottom=$bounds viewport=${v.snapshotViewport()} embedded=${v.embeddedPage} size=${v.width}x${v.height}",bounds.bottom<=1414.01)
        }
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        val restored=compose.onNodeWithTag("floating-pen-case").fetchSemanticsNode().boundsInRoot
        assertEquals(moved.left,restored.left,3f)
    }
    @Test fun manualSelectionUsesChosenFontWithoutConfirmationDialog(){
        val note=open();hi(note.id)
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(note.id).strokes.size}==6}
        compose.openBeautySettings();compose.onNodeWithTag("beauty-replace-font").performClick();compose.onNodeWithTag("beauty-font-picker").performClick();compose.onNodeWithTag("font-SERIF").performClick()
        compose.onNodeWithTag("beauty-select").performScrollTo().performClick();compose.waitForIdle()
        val strokes=runBlocking{InkSession(app.inkRepository.read(note.id)).visibleDraft()}
        val region=InkRegion(listOf(EraserPoint(150f,250f),EraserPoint(400f,450f)))
        compose.runOnIdle{
            val vm=ViewModelProvider(compose.activity,PageObjectViewModel.Factory(note.id,app.pageObjects))["objects-${note.id}",PageObjectViewModel::class.java]
            vm.beautify(SelectedInk(region,6,strokes),BeautyStore(app).read(),false,app)
        }
        compose.waitUntil(45_000){runBlocking{app.pageObjects.read(note.id).objects.isNotEmpty()}}
        assertEquals(TextFont.SERIF,runBlocking{app.pageObjects.read(note.id).objects.single().font})
        compose.onNodeWithTag("font-beauty-dialog").assertDoesNotExist()
    }
    @Test fun pencilAutomaticallyConvertsWithSelectedFont(){
        val note=open();compose.selectPen("pencil");compose.openBeautySettings();compose.onNodeWithTag("beauty-replace-font").performClick();compose.onNodeWithTag("beauty-enabled").performClick();compose.onNodeWithTag("beauty-close").performClick()
        hi(note.id)
        compose.waitUntil(45000){runBlocking{app.pageObjects.read(note.id).objects.any{!it.hidden}}}
        val result=runBlocking{app.pageObjects.read(note.id).objects.single()}
        assertTrue(result.text.isNotBlank());assertEquals(6,result.sourceStrokeIds.size);assertEquals(TextFont.WENKAI,result.font)
        assertTrue(runBlocking{app.inkRepository.read(note.id).strokes.all{it.stroke.pen==InkPen.PENCIL}})
    }

    @Test fun rapidSuccessiveStrokesRemainQueuedUntilAllAreSaved(){
        val note=open(2)
        compose.runOnIdle{repeat(6){i->line(canvas(note.id),listOf(200f+i*40 to 300f,200f+i*40 to 450f))}}
        compose.waitUntil(15000){runBlocking{app.inkRepository.read(note.id).strokes.size}==6&&app.navigationReady.value}
        compose.onNodeWithTag("ink-undo").performClick();compose.waitUntil(10000){runBlocking{InkSession(app.inkRepository.read(note.id)).visibleDraft().size}==5}
        compose.onNodeWithTag("ink-redo").performClick();compose.waitUntil(10000){runBlocking{InkSession(app.inkRepository.read(note.id)).visibleDraft().size}==6}
    }

    @Test fun erasingEmptyPaperDoesNotBlockTheNextStroke(){
        val note=open()
        compose.runOnIdle{canvas(note.id).eraseMode=true;line(canvas(note.id),listOf(200f to 300f,300f to 400f))}
        compose.waitUntil(10000){app.navigationReady.value}
        compose.runOnIdle{canvas(note.id).eraseMode=false;line(canvas(note.id),listOf(400f to 300f,500f to 400f))}
        compose.waitUntil(10000){runBlocking{app.inkRepository.read(note.id).strokes.size}==1}
    }

}
