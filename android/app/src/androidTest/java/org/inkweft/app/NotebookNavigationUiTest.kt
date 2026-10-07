package org.inkweft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class NotebookNavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private var hadFingerWrites=false
    private var originalFingerWrites=false
    @Before fun captureFingerWrites(){
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        hadFingerWrites=prefs.contains("finger-writes");originalFingerWrites=prefs.getBoolean("finger-writes",false)
        assertTrue(prefs.edit().putBoolean("finger-writes",false).commit())
    }
    @After fun restoreFingerWrites(){
        val editor=app.getSharedPreferences("inkweft-editor",0).edit()
        if(hadFingerWrites)editor.putBoolean("finger-writes",originalFingerWrites)else editor.remove("finger-writes")
        assertTrue(editor.commit())
    }
    private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
    private fun open(pageCount:Int=1):Note {
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("末页追加与输入模式",false,PaperStyle.RULED).also{n->
            var previous=n.id
            repeat(pageCount-1){previous=app.pages.addAfter(n.id,previous,UUID.randomUUID().toString()).id}
            app.pages.select(n.id,n.id)
        }}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()};ready();return note
    }
    private fun append(){
        compose.onNodeWithTag("continuous-pages").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)};ready()
        compose.revealAction("document-add-page")
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("document-add-page").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("document-more-menu").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000){compose.onAllNodesWithTag("document-more-menu").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.8f),Offset(centerX,height*.15f),650)}
    }
    @Test fun continuousOverviewJumpKeepsRequestedPageUntilScrollingSettles(){
        val note=open(12)
        val pages=runBlocking{app.pages.activePages(note.id)}
        compose.revealAction("quick-readonly");compose.onNodeWithTag("quick-readonly").performClick()
        fun jump(number:Int){
            compose.openOverviewGrid()
            compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-$number"))
            compose.onNodeWithTag("jump-page-$number").assertIsDisplayed().performTouchInput{click()}
            compose.waitForIdle()
            compose.onNodeWithTag("pages-directory-dialog-close").performClick()
            compose.waitForIdle()
            compose.assertCurrentPage("第 $number / 12 页")
            compose.onNodeWithTag("continuous-page-$number").assertIsDisplayed()
            compose.waitUntil(10000){runBlocking{app.workspaceRepository.get(note.id).selectedPageId}==pages[number-1].id}
        }
        jump(12)
        jump(1)
        // Scrolling the list directly must still report the settled reading page.
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(5)
        compose.waitForIdle()
        compose.assertCurrentPage("第 6 / 12 页")
        assertEquals(pages.map{it.id},runBlocking{app.pages.activePages(note.id).map{it.id}})
        assertTrue(runBlocking{pages.all{app.inkRepository.read(it.id).strokes.isEmpty()}})
    }
    @Test fun continuousManualViewportSurvivesOverviewResize(){
        val note=open(3)
        compose.revealAction("quick-readonly");compose.onNodeWithTag("quick-readonly").performClick();ready()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.85f),Offset(centerX,height*.25f),650)}
        ready();compose.assertCurrentPage("第 1 / 3 页")
        data class Region(val bounds:androidx.compose.ui.geometry.Rect,val y:Int,val width:Int,val height:Int)
        fun region():Region {
            compose.waitForIdle()
            val bounds=compose.onNodeWithTag("continuous-pages").fetchSemanticsNode().boundsInWindow
            return compose.runOnIdle{
                val canvas=checkNotNull(compose.activity.window.decorView.findViewWithTag<InkCanvasView>("ink-page-${note.id}"))
                val position=IntArray(2);canvas.getLocationInWindow(position)
                Region(bounds,position[1],canvas.width,canvas.height)
            }
        }
        val before=region()
        assertTrue("Fixture must leave the page top",before.bounds.top-before.y>200)
        compose.openOverviewGrid();compose.waitForIdle()
        assertTrue("Fixture requires a docked overview that resizes the paper",compose.onNodeWithTag("continuous-pages").fetchSemanticsNode().boundsInWindow.width<before.bounds.width)
        compose.onNodeWithTag("pages-directory-dialog-close").performClick();ready()
        compose.assertCurrentPage("第 1 / 3 页")
        val after=region()
        assertEquals(before.bounds,after.bounds);assertEquals(before.width,after.width);assertEquals(before.height,after.height)
        assertEquals("Opening and closing overview must retain the reading position",before.y.toDouble(),after.y.toDouble(),4.0)
        val pages=runBlocking{app.pages.activePages(note.id)}
        assertEquals(3,pages.size);assertTrue(runBlocking{pages.all{app.inkRepository.read(it.id).strokes.isEmpty()}})
    }
    @Test fun continuousCrossBookReturnRestoresNonTopViewport(){
        val origin=open(3)
        val provider=ViewModelProvider(compose.activity)
        val notebook=provider[NotebookViewModel::class.java]
        val workspace=provider[WorkspaceViewModel::class.java]
        fun settled(note:Note){
            compose.waitUntil(15000){compose.runOnIdle{
                val canvas=compose.activity.window.decorView.findViewWithTag<InkCanvasView>("ink-page-${note.id}")
                if(notebook.ui.value.selectedId!=note.id||canvas==null)false else{
                    val ink=provider["ink-${note.id}",InkViewModel::class.java].ui.value
                    val objects=provider["objects-${note.id}",PageObjectViewModel::class.java].ui.value
                    val recovery=provider["continuous-recovery-${note.id}",ContinuousGroupSession::class.java]
                    app.openKnowledgeTarget.value==null&&app.navigationReady.value&&!ink.loading&&!ink.readFailed&&ink.queued==0&&!ink.processing&&ink.blocked==null&&!objects.loading&&!objects.busy&&!objects.pending&&!recovery.busy.value&&recovery.problem.value==null
                }
            }};compose.waitForIdle()
        }
        data class Region(val bounds:androidx.compose.ui.geometry.Rect,val windowX:Int,val windowY:Int,val screenY:Int,
            val width:Int,val height:Int,val density:Double,val native:CanvasViewport,val world:CanvasViewport,val cached:CanvasViewport?)
        fun near(expected:CanvasViewport,actual:CanvasViewport?)=actual!=null&&kotlin.math.abs(expected.centerX-actual.centerX)<=2.0&&kotlin.math.abs(expected.centerY-actual.centerY)<=2.0&&kotlin.math.abs(expected.zoom-actual.zoom)<=.001
        fun region(page:String):Region {
            var last:Region?=null
            compose.waitForIdle()
            try{compose.waitUntil(15000){
                val node=compose.onNodeWithTag("continuous-pages").assertIsDisplayed().fetchSemanticsNode()
                last=compose.runOnIdle{
                    val canvas=checkNotNull(compose.activity.window.decorView.findViewWithTag<InkCanvasView>("ink-page-$page"))
                    val bounds=node.boundsInWindow
                    val window=IntArray(2);canvas.getLocationInWindow(window)
                    val screen=IntArray(2);canvas.getLocationOnScreen(screen)
                    val visible=android.graphics.Rect();assertTrue(canvas.getGlobalVisibleRect(visible))
                    assertTrue(bounds.width>0&&bounds.height>0&&canvas.width>0&&canvas.height>0)
                    val native=canvas.snapshotViewport();val density=canvas.resources.displayMetrics.density.toDouble()
                    val center=native.screenToWorld((bounds.center.x-window[0]).toDouble(),(bounds.center.y-window[1]).toDouble(),canvas.width.toDouble(),canvas.height.toDouble(),density)
                    Region(bounds,window[0],window[1],screen[1],canvas.width,canvas.height,density,native,
                        CanvasViewport(center.x,center.y,native.zoom),workspace.cachedViewport(page))
                }
                near(checkNotNull(last).world,last?.cached)
            }}catch(failure:Exception){throw AssertionError("Native viewport/cache did not settle: $last",failure)}
            return checkNotNull(last)
        }
        fun sameViewport(expected:CanvasViewport,actual:CanvasViewport,details:String=""){
            assertEquals("Viewport center X: $details",expected.centerX,actual.centerX,2.0)
            assertEquals("Viewport center Y: $details",expected.centerY,actual.centerY,2.0)
            assertEquals("Viewport zoom: $details",expected.zoom,actual.zoom,.001)
        }
        val originPages=runBlocking{app.pages.activePages(origin.id).map{it.id}}
        val other=runBlocking{app.workspaceRepository.create("连续页跨本返回",false,PaperStyle.RULED)}
        // Warm both real editors so a late data load cannot hide a lost return request.
        compose.runOnIdle{notebook.select(other)};settled(other)
        compose.runOnIdle{notebook.select(origin)};settled(origin)
        compose.revealAction("quick-readonly");compose.onNodeWithTag("quick-readonly").performClick();ready()
        val atTop=region(origin.id)
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.85f),Offset(centerX,height*.25f),650)}
        ready();compose.assertCurrentPage("第 1 / 3 页")
        val before=region(origin.id)
        assertTrue("Fixture must leave the page top",before.world.centerY-atTop.world.centerY>80.0)
        val wanted=checkNotNull(before.cached)
        compose.runOnIdle{assertTrue(workspace.knowledgeReturns.value.isEmpty());app.openKnowledgeTarget.value=TargetRef(TargetKind.PAGE,other.id)}
        settled(other)
        compose.runOnIdle{
            val back=checkNotNull(workspace.peekKnowledgeReturn())
            assertEquals(origin.id,back.book);assertEquals(origin.id,back.page);sameViewport(wanted,checkNotNull(back.viewport))
        }
        compose.onNodeWithTag("knowledge-return").assertIsDisplayed().assertIsEnabled().performClick()
        settled(origin)
        compose.waitUntil(15000){compose.runOnIdle{origin.id !in workspace.pendingReturnViewport.value&&workspace.knowledgeReturns.value.isEmpty()}}
        compose.waitForIdle();compose.assertCurrentPage("第 1 / 3 页")
        val after=region(origin.id)
        assertEquals(before.width,after.width)
        sameViewport(before.world,after.world,"before=$before; after=$after")
        sameViewport(wanted,checkNotNull(after.cached),"before=$before; after=$after")
        assertEquals(originPages,runBlocking{app.pages.activePages(origin.id).map{it.id}})
        assertTrue(runBlocking{(originPages+other.id).all{app.inkRepository.read(it).strokes.isEmpty()}})
    }
    @Test fun realEditorAppendPersistsOneBlankPageAndRejectsStaleTail(){
        val note=open()
        compose.onNodeWithTag("quick-finger").assertIsDisplayed().assertIsOff()
        val preference=NewPagePaperPreference(app.getSharedPreferences("inkweft-reading",0),note.id)
        assertNull(preference.read());append()
        compose.waitUntil(15000){runBlocking{app.pages.activePages(note.id).size}==2};ready()
        val inherited=runBlocking{app.pages.activePages(note.id)}
        assertEquals(PaperStyle.RULED.ordinal,inherited.last().paper)
        assertTrue(runBlocking{app.inkRepository.read(inherited.last().id).strokes.isEmpty()})
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].appendBlankPage(note.id,PaperStyle.GRID)}
        ready();assertEquals(2,runBlocking{app.pages.activePages(note.id).size})
        compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick()
        compose.onNodeWithTag("settings-new-page-paper").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-grid").performScrollTo().performClick()
        compose.onNodeWithTag("new-page-paper-save").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("new-page-paper-dialog").fetchSemanticsNodes().isEmpty()}
        assertEquals(PaperStyle.GRID,preference.read())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()};ready();append()
        compose.waitUntil(15000){runBlocking{app.pages.activePages(note.id).size}==3};ready()
        val pages=runBlocking{app.pages.activePages(note.id)}
        assertEquals(inherited.map{it.id to it.paper},pages.take(2).map{it.id to it.paper})
        assertEquals(PaperStyle.GRID.ordinal,pages.last().paper)
        assertTrue(runBlocking{app.inkRepository.read(pages.last().id).strokes.isEmpty()})
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].appendBlankPage(inherited.last().id,PaperStyle.BLANK)}
        ready();assertEquals(pages.map{it.id},runBlocking{app.pages.activePages(note.id).map{it.id}})
        compose.onNodeWithTag("quick-finger").performClick();compose.onNodeWithTag("quick-finger").assertIsOn()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(width*.4f,height*.35f),Offset(width*.6f,height*.4f),350)}
        compose.waitUntil(15000){runBlocking{app.inkRepository.read(pages.last().id).strokes.size}==1};ready()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithTag("quick-finger").fetchSemanticsNodes().isNotEmpty()};ready()
        compose.onNodeWithTag("quick-finger").assertIsOn();compose.onNodeWithTag("continuous-pages").assertExists()
        assertEquals(pages.map{it.id},runBlocking{app.pages.activePages(note.id).map{it.id}})
        assertEquals(1,runBlocking{app.inkRepository.read(pages.last().id).strokes.size})
    }
}
