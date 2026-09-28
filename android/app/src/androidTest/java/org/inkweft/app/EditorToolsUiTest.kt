package org.inkweft.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class EditorToolsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private lateinit var old:Map<String,*>
    @Before fun isolate(){val p=app.getSharedPreferences("inkweft-editor",0);old=p.all;p.edit().clear().commit()}
    @After fun restore(){val e=app.getSharedPreferences("inkweft-editor",0).edit().clear();old.forEach{(k,v)->when(v){is String->e.putString(k,v);is Float->e.putFloat(k,v);is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Set<*>->e.putStringSet(k,v.filterIsInstance<String>().toSet())}};e.commit()}
    private fun open(seed:List<InkStroke> = emptyList()):Note{
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val n=runBlocking{app.workspaceRepository.create("V26 工具验收",false,PaperStyle.BLANK)}
        runBlocking{seed.forEachIndexed{i,stroke->app.inkRepository.save(CommitInk(java.util.UUID.randomUUID().toString(),n.id,i.toLong(),InkMutation.Add(stroke)))}}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
        compose.singlePageEditor();compose.waitUntil(15000){app.navigationReady.value};return n
    }
    private fun strokes(n:Note)=runBlocking{InkSession(app.inkRepository.read(n.id)).visibleDraft()}
    private fun objects(n:Note)=runBlocking{app.pageObjects.read(n.id).objects}
    private fun ready(){compose.waitUntil(15000){app.navigationReady.value}}
    private fun tap(tag:String){val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
    private fun screenshot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    @Test fun distinctPensRememberRecipesAndPaletteDoesNotRewriteOtherPens(){
        val n=open();val store=PenWidthStore(app,"inkweft-pen-widths-book-${n.id}")
        compose.onAllNodes(hasAnyAncestor(hasTestTag("floating-pen-case")) and hasTestTag("ink-select")).assertCountEquals(0)
        compose.onAllNodes(hasAnyAncestor(hasTestTag("floating-pen-case")) and hasTestTag("favorite-pens-toggle")).assertCountEquals(0)
        val penKinds=listOf(InkPen.PENCIL,InkPen.PEN,InkPen.BRUSH,InkPen.MARKER,InkPen.BALLPOINT)
        penKinds.forEachIndexed{i,kind->
            compose.selectPen(kind.name.lowercase());compose.openCurrentPen();tap("width-preset-${i%3}")
            compose.onNodeWithTag("brush-scratch-open").assertDoesNotExist();compose.closePenSettings()
            tap("case-color-"+listOf("b83239","2f53aa","126b50","e1ad19","7355a2")[i])
        }
        val before=penKinds.associateWith(store::readPen)
        penKinds.forEach{compose.selectPen(it.name.lowercase());assertEquals(before[it],store.readPen(it))}
        compose.selectPen("pencil");compose.openCurrentPen();tap("pencil-hardness-2");compose.closePenSettings()
        assertEquals(2,store.readPen(InkPen.PENCIL).recipe.hardness)
        assertEquals(before[InkPen.PEN],store.readPen(InkPen.PEN));assertTrue(strokes(n).isEmpty())
        compose.activityRule.scenario.recreate();ready()
        assertEquals(before[InkPen.BRUSH],store.readPen(InkPen.BRUSH));assertEquals(2,store.readPen(InkPen.PENCIL).recipe.hardness)
        screenshot("v26-pen-case.png")
    }
    @Test fun toolbarHidingAndOrderPersistAndAlwaysRemainRecoverable(){
        open();tap("toolbar-customize");compose.onNodeWithTag("toolbar-drag-camera").performScrollTo().performTouchInput{swipe(center,center-Offset(0f,height*1.6f),400)};tap("toolbar-visible-camera")
        val order=EditorToolOrder.read(app);assertTrue(order.indexOf("camera")<order.indexOf("image"))
        screenshot("v26-toolbar-settings.png");tap("toolbar-done");compose.onNodeWithTag("object-camera").assertDoesNotExist()
        compose.activityRule.scenario.recreate();ready();compose.onNodeWithTag("object-camera").assertDoesNotExist();assertEquals(order,EditorToolOrder.read(app))
        tap("toolbar-customize");EditorToolOrder.labels.keys.filter{it !in EditorToolOrder.fixed}.forEach{if(runCatching{compose.onNodeWithTag("toolbar-visible-$it").assertIsOn()}.isSuccess)tap("toolbar-visible-$it")}
        tap("toolbar-done");compose.onNodeWithTag("top-draw").assertDoesNotExist();tap("toolbar-customize");tap("toolbar-reset");tap("toolbar-done")
        tap("top-draw");compose.onNodeWithTag("pen-width-dialog").assertIsDisplayed();compose.closePenSettings()
    }
    @Test fun tapeStickerAndShapesSaveAndUndoThroughDirectTools(){
        val n=open();compose.selectPen("pen");tap("object-tape");compose.onNodeWithTag("tape-overlay").performTouchInput{swipe(Offset(width*.3f,height*.3f),Offset(width*.6f,height*.3f),250)};compose.waitUntil(10000){objects(n).size==1};ready()
        assertEquals(PageObjectKind.TAPE,objects(n).single().kind);compose.selectPen("pen");compose.onNodeWithTag("pen-kind-pen").assertIsOn()
        tap("object-sticker");tap("sticker-⭐");compose.waitUntil(10000){objects(n).size==2};ready();assertEquals("⭐",objects(n).last().text)
        tap("object-deselect");tap("ink-undo");compose.waitUntil(10000){objects(n).size==1};ready();tap("ink-redo");compose.waitUntil(10000){objects(n).size==2};ready()
        tap("object-shape");tap("shape-rectangle");compose.waitUntil(10000){objects(n).size==3};ready()
        assertEquals(ObjectShape.RECTANGLE,objects(n).last().shape)
        tap("ink-undo");compose.waitUntil(10000){objects(n).size==2};ready();tap("ink-redo");compose.waitUntil(10000){objects(n).size==3};ready()
        compose.activityRule.scenario.recreate();ready();assertEquals(3,objects(n).size);assertTrue(strokes(n).isEmpty())
    }
    @Test fun insertedShapeCanMoveTwiceAndUndo(){
        val n=open(ShapeInk.create("rectangle",CanvasViewport(500.0,260.0,1.0),false,android.graphics.Color.BLACK,3f));ready()
        fun drag(reselect:Boolean=false){
            var start=Offset.Zero
            compose.runOnIdle{
                fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
                val v=checkNotNull(find(compose.activity.window.decorView));val b=strokes(n).single().bounds()
                val p=v.snapshotViewport().worldToScreen((b.left+b.right)/2,(b.top+b.bottom)/2,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble())
                start=Offset(p.x.toFloat(),p.y.toFloat())
            }
            if(reselect)compose.onNodeWithTag("selection-overlay").performTouchInput{click(start)}
            compose.onNodeWithTag("selection-context-menu").assertIsDisplayed()
            compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(start,start+Offset(65f,90f),400)}
        }
        tap("ink-select");val first=strokes(n).single();drag(reselect=true);compose.waitUntil(10000){strokes(n).single().id!=first.id};ready()
        val second=strokes(n).single();assertTrue(second.samples.first().x>first.samples.first().x+10)
        val beforeMenu=compose.onNodeWithTag("selection-context-menu").fetchSemanticsNode().layoutInfo.coordinates.positionOnScreen()
        compose.onNodeWithContentDescription("取消选择").performClick()
        drag(reselect=true);compose.waitUntil(10000){strokes(n).single().id!=second.id};ready()
        val afterMenu=compose.onNodeWithTag("selection-context-menu").fetchSemanticsNode().layoutInfo.coordinates.positionOnScreen()
        assertTrue("Menu did not follow moved selection",afterMenu.y>beforeMenu.y+40f)
        screenshot("v27-selection-menu.png")
        tap("ink-undo");compose.waitUntil(10000){strokes(n).single().id==second.id}
    }
    @Test fun documentActionsReadOnlyFullScreenAndTimerAreOperational(){
        val n=open();tap("quick-overview");compose.onNodeWithTag("pages-directory-dialog").assertIsDisplayed();tap("pages-directory-dialog-close")
        tap("quick-settings");compose.onNodeWithTag("document-settings-dialog").assertIsDisplayed();tap("document-settings-dialog-close")
        tap("toolbar-customize");listOf("readonly","fullscreen","timer","add-page","export","beauty","finger").forEach{tap("toolbar-visible-$it")};tap("toolbar-done")
        tap("quick-readonly");compose.onNodeWithTag("floating-pen-case").assertDoesNotExist();compose.onNodeWithTag("top-eraser").assertIsNotEnabled()
        compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.4f),Offset(width*.6f,height*.5f),250)}
        assertTrue(strokes(n).isEmpty());tap("exit-readonly");compose.onNodeWithTag("floating-pen-case").assertExists()
        tap("quick-fullscreen");compose.onNodeWithTag("notebook-tabs").assertDoesNotExist();compose.onNodeWithTag("rename-from-editor").assertDoesNotExist()
        compose.activityRule.scenario.recreate();compose.waitUntil(10000){compose.onAllNodesWithTag("exit-fullscreen").fetchSemanticsNodes().isNotEmpty()};tap("exit-fullscreen");compose.onNodeWithTag("notebook-tabs").assertExists()
        tap("quick-timer");tap("timer-toggle");compose.waitUntil(5000){runCatching{compose.onNodeWithTag("timer-value").assertTextEquals("00:00:00")}.isFailure};tap("timer-toggle")
        val value=compose.onNodeWithTag("timer-value").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        compose.onNodeWithContentDescription("关闭计时器").performClick();tap("quick-timer");compose.onNodeWithTag("timer-value").assertTextEquals(value);tap("timer-reset");compose.onNodeWithContentDescription("关闭计时器").performClick()
        tap("quick-add-page");compose.onNodeWithTag("insert-pages-dialog").assertIsDisplayed();compose.onNodeWithText("取消").performClick()
        tap("quick-export");compose.onNodeWithText("导出整本内容副本").assertIsDisplayed();compose.onNodeWithText("取消").performClick()
        tap("quick-beauty");compose.onNodeWithTag("beauty-enabled").assertExists()
    }
    @Test fun compactCustomizationSupportsLargeText(){
        open();tap("toolbar-customize");compose.onNodeWithTag("toolbar-done").assertIsDisplayed()
        tap("toolbar-visible-camera");screenshot("v26-compact-large-type.png")
        tap("toolbar-drag-image");compose.onNodeWithText("上移").performClick()
        tap("toolbar-done");compose.onNodeWithTag("object-camera").assertDoesNotExist()
        compose.openCurrentPen();compose.onNodeWithTag("close-pen-settings").performScrollTo().assertIsDisplayed();compose.closePenSettings()
    }
    @Test fun circleEraseCutsInsideRegionAndUndoRestoresShape(){
        val n=open(ShapeInk.create("rectangle",CanvasViewport(500.0,260.0,1.0),false,android.graphics.Color.BLACK,3f));ready()
        val s=strokes(n).single();val x=s.samples[0].x;val y=s.samples[0].y
        fun point(a:Float,b:Float):Offset {var result=Offset.Zero;compose.runOnIdle{
            fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
            val v=checkNotNull(find(compose.activity.window.decorView));val p=v.snapshotViewport().worldToScreen(a.toDouble(),b.toDouble(),v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());result=Offset(p.x.toFloat(),p.y.toFloat())
        };return result}
        val points=listOf(point(x+70,y-30),point(x+170,y-30),point(x+170,y+30),point(x+70,y+30))
        tap("top-area-erase");compose.onNodeWithTag("selection-overlay").performTouchInput{down(points[0]);points.drop(1).forEach{moveTo(it,120)};moveTo(points[0],120);up()}
        compose.waitUntil(10000){strokes(n).single().cuts.isNotEmpty()};ready();val cut=strokes(n).single().cuts.single();assertEquals(InkCutShape.POLYGON,cut.shape)
        assertEquals(s.samples,strokes(n).single().samples);tap("ink-undo");compose.waitUntil(10000){strokes(n).single().cuts.isEmpty()}
    }
}
