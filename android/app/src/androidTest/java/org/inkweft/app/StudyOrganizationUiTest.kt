// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StudyOrganizationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun vm(book:String)=ViewModelProvider(compose.activity)["study-$book",StudyViewModel::class.java]
    private fun nodes(book:String)=runBlocking{app.study.nodes(book).first()}.sortedBy{it.id}
    private fun cards(book:String)=runBlocking{app.study.cards(book).first()}.sortedBy{it.id}
    private fun order(book:String)=runBlocking{app.study.readGraph(book).orderedNodeIds}
    private data class AuthorSnapshot(val cards:List<StudyCardRow>,val nodes:List<StudyNodeRow>,val knowledge:List<String>,val graph:String)
    private fun author(book:String)=runBlocking{
        val snapshot=app.study.readGraph(book)
        AuthorSnapshot(snapshot.cards.sortedBy{it.id},snapshot.nodes.sortedBy{it.id},
            app.knowledge.observeBook(book).first().sortedBy{it.id}.map{"${it.id}:${it.revision}:${it.removed}:${ContentTransfer.hash(it.payload)}"},snapshot.graphFingerprint)
    }
    private fun settled(book:String){
        compose.waitUntil(15_000){
            val state=vm(book).ui.value
            !state.loading&&!state.busy&&!state.unknown&&state.graph?.graphFingerprint==runBlocking{app.study.readGraph(book).graphFingerprint}
        }
        compose.waitForIdle()
    }
    private fun tap(tag:String){
        compose.revealAction(tag)
        val target=compose.onNodeWithTag(tag)
        runCatching{target.performScrollTo()}
        target.assertIsDisplayed().assertIsEnabled().performTouchInput{click()}
        compose.waitForIdle()
    }
    private fun map():MindMapView {
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){
            val view=queue.removeFirst()
            if(view is MindMapView&&view.isShown)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)queue.add(view.getChildAt(i))
        }
        error("Visible study map missing")
    }
    private fun select(nodeId:String){
        var point=Offset.Zero
        compose.runOnIdle{map().focusNode(nodeId);val bounds=checkNotNull(map().nodeBounds(nodeId));point=Offset(bounds.centerX(),bounds.centerY())}
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput{advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong()+1);click(point)}
        compose.waitForIdle()
        compose.runOnIdle{assertEquals(nodeId,map().selectedNodeId);assertTrue("A user-selected topic owns hardware keyboard focus",map().hasFocus())}
    }
    private fun key(code:Int,modifiers:Int=0,releaseModifiers:Int=modifiers,canceled:Boolean=false,dispatchDirectlyToMap:Boolean=false){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val time=SystemClock.uptimeMillis()
        // Includes an auto-repeat and duplicate release; this is synthetic dispatch, not an IME test.
        val events=listOf(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0,modifiers),
            KeyEvent(time,time+20,KeyEvent.ACTION_DOWN,code,1,modifiers),
            KeyEvent(time,time+40,KeyEvent.ACTION_UP,code,0,releaseModifiers),
            KeyEvent(time,time+60,KeyEvent.ACTION_UP,code,0,releaseModifiers)).map{event->
                if(canceled&&event.action==KeyEvent.ACTION_UP)KeyEvent.changeFlags(event,KeyEvent.FLAG_CANCELED)else event
            }
        // Check rejected modifiers at the visible map boundary; Alt/Meta+Tab can switch system tasks.
        if(dispatchDirectlyToMap)compose.runOnIdle{val target=map();events.forEach{target.dispatchKeyEvent(it)}}
        else events.forEach{instrumentation.sendKeySync(it)}
        compose.waitForIdle()
    }
    private data class Fixture(val book:String,val root:String,val first:String,val firstChild:String,val second:String,val secondChild:String){
        val initialOrder get()=listOf(root,first,firstChild,second,secondChild)
    }
    private fun fixture(overlapping:Boolean=false):Fixture {
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("导图整理合成验证 ${id()}",false,PaperStyle.RULED)}
        val f=Fixture(note.id,id(),id(),id(),id(),id())
        val parents=listOf(null,f.root,f.first,f.root,f.second)
        runBlocking{f.initialOrder.forEachIndexed{index,nodeId->
            app.study.submit(StudyCommand(id(),f.book,StudyAction.CREATE,cardId=id(),nodeId=nodeId,parentId=parents[index],
                title=listOf("整理验证根主题","第一分支：保留整棵子树","第一分支的长标题子主题，用多行文字验证实际测量边界","第二分支：移动与缩进","第二分支的子主题")[index],
                body=if(index==2||index==4)"检查标题和正文的实际测量高度，并保留手动放置位置。".repeat(8)else "合成验证内容",
                x=if(overlapping)123.0+index*7 else if(index==0)40.0 else if(index==1||index==3)340.0 else 650.0,
                y=if(overlapping)91.0+index*5 else 80.0+index*190))
        }}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.frameCanvasFixture();tap("quick-study");settled(f.book)
        compose.onNodeWithTag("study-map").assertIsDisplayed()
        assertEquals(f.initialOrder,order(f.book))
        return f
    }

    @Test fun subtreeOrderSurvivesReopenAndUndoWhileKeyboardChangesOneSelectedBranch(){
        val f=fixture();val originalCards=cards(f.book);val originalNodes=nodes(f.book)
        select(f.second);tap("node-more");tap("node-organize");tap("node-order-up")
        val reordered=listOf(f.root,f.second,f.secondChild,f.first,f.firstChild)
        compose.waitUntil(15_000){order(f.book)==reordered};settled(f.book)
        assertEquals(originalNodes.associate{it.id to it.parentId},nodes(f.book).associate{it.id to it.parentId})
        assertEquals(originalCards,cards(f.book))
        tap("study-close");tap("quick-study");settled(f.book)
        assertEquals(reordered,order(f.book))
        tap("study-undo-organization");compose.waitUntil(15_000){order(f.book)==f.initialOrder};settled(f.book)

        select(f.second);val beforeCanceledTab=author(f.book);key(KeyEvent.KEYCODE_TAB,canceled=true)
        assertEquals(beforeCanceledTab,author(f.book))
        select(f.second);key(KeyEvent.KEYCODE_TAB)
        compose.waitUntil(15_000){nodes(f.book).single{it.id==f.second}.parentId==f.first};settled(f.book)
        assertEquals(f.second,nodes(f.book).single{it.id==f.secondChild}.parentId)
        assertEquals(f.initialOrder,order(f.book))
        select(f.second);key(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON,releaseModifiers=0)
        compose.waitUntil(15_000){nodes(f.book).single{it.id==f.second}.parentId==f.root};settled(f.book)
        assertEquals(f.second,nodes(f.book).single{it.id==f.secondChild}.parentId)
        for(modifier in listOf(KeyEvent.META_CTRL_ON,KeyEvent.META_ALT_ON,KeyEvent.META_META_ON)){
            select(f.second);val before=author(f.book);key(KeyEvent.KEYCODE_TAB,modifier,dispatchDirectlyToMap=true);assertEquals(before,author(f.book))
        }

        select(f.second);key(KeyEvent.KEYCODE_ENTER)
        compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithTag("node-title-input").assertIsFocused().performTextInput("键盘新增同级主题")
        compose.onNodeWithTag("node-title-input").assertIsFocused().assertTextContains("键盘新增同级主题")
        assertEquals(originalCards,cards(f.book));assertEquals(5,nodes(f.book).size)
        tap("node-title-save")
        compose.waitUntil(15_000){nodes(f.book).size==6};settled(f.book)
        val added=nodes(f.book).single{it.id !in f.initialOrder}
        assertEquals(f.root,added.parentId)
        assertEquals("键盘新增同级主题",cards(f.book).single{it.id==added.cardId}.title)
        assertEquals(originalCards,cards(f.book).filter{it.id!=added.cardId})
    }

    @Test fun layoutPreviewCancelKeepsAuthorStateAndApplyUndoUseMeasuredNodeBounds(){
        val f=fixture(overlapping=true);val before=author(f.book);val originalOrder=order(f.book)
        fun positions()=nodes(f.book).associate{it.id to Triple(it.parentId,it.x,it.y)}
        fun camera()=compose.runOnIdle{map().snapshotViewport()}
        val originalPositions=positions()
        val originalCamera=camera()
        tap("study-arrange")
        compose.onNodeWithTag("study-layout-preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("自动布局预览").assertIsDisplayed()
        assertEquals(before,author(f.book))
        tap("study-layout-cancel")
        compose.onNodeWithTag("study-layout-preview").assertDoesNotExist()
        assertEquals(before,author(f.book))
        assertEquals(originalCamera,camera())

        tap("study-arrange");tap("study-layout-apply")
        compose.waitUntil(15_000){positions()!=originalPositions};settled(f.book)
        compose.onNodeWithTag("study-layout-preview").assertDoesNotExist()
        val arranged=nodes(f.book);val cardById=cards(f.book).associateBy{it.id}
        val bounds=compose.runOnIdle{arranged.associate{node->
            val card=cardById.getValue(node.cardId)
            val measured=MapNodeMetrics.measure(card.title,card.body,fontScale=compose.activity.resources.configuration.fontScale)
            node.id to RectF(node.x.toFloat(),node.y.toFloat(),node.x.toFloat()+measured.width,node.y.toFloat()+measured.height)
        }}
        for((firstId,first) in bounds)for((secondId,second) in bounds)if(firstId<secondId){
            assertFalse("Applied layout overlaps $firstId and $secondId",RectF.intersects(first,second))
        }
        assertEquals(before.cards,cards(f.book));assertEquals(originalOrder,order(f.book))
        assertEquals(before.nodes.associate{it.id to it.parentId},arranged.associate{it.id to it.parentId})
        tap("study-undo-organization")
        compose.waitUntil(15_000){positions()==originalPositions};settled(f.book)
        compose.waitUntil(15_000){camera()==originalCamera}
        assertEquals(originalCamera,camera())
        assertEquals(originalOrder,order(f.book));assertEquals(before.cards,cards(f.book))
        tap("study-tab-1");tap("study-tab-2");settled(f.book)
        compose.waitUntil(15_000){camera()==originalCamera}
        assertEquals(originalCamera,camera())
        tap("study-close");tap("quick-study");settled(f.book)
        assertEquals(originalPositions,positions())
        compose.runOnIdle{
            val overview=MindMapView(app);overview.layout(0,0,1200,900)
            val card=before.cards.first()
            val large=(0 until 128).map{index->StudyNodeRow(id(),f.book,card.id,null,-40000.0+(index%16)*(80000.0/15),-40000.0+(index/16)*(80000.0/7))}
            overview.show(large,listOf(card));overview.fitOverview()
            val scale=overview.snapshotViewport().scale
            assertTrue("Whole-world overview must be allowed below the former 0.08 limit",scale<.08f)
            large.forEach{node->val box=checkNotNull(overview.nodeBounds(node.id));assertTrue(box.left>=0f&&box.top>=0f&&box.right<=1200f&&box.bottom<=900f)}
            overview.zoom(1.25f);assertEquals(scale*1.25f,overview.snapshotViewport().scale,.000001f)
            overview.zoom(.8f);assertEquals(scale,overview.snapshotViewport().scale,.000001f)
        }
    }
    @Test fun workModesPreserveSelectedGraphAndResumeTheSameUnrevealedQuestion(){
        val f=fixture();val before=author(f.book)
        val card=cards(f.book).single{it.id==nodes(f.book).single{it.id==f.second}.cardId}
        runBlocking{app.knowledge.submit(KnowledgeCommand(id(),f.book,id(),0,KnowledgeData.Question(card.id,"三态恢复合成问题")))}
        select(f.second)
        val camera=compose.runOnIdle{map().snapshotViewport()}
        tap("quick-readonly")
        compose.runOnIdle{assertTrue(ViewModelProvider(compose.activity)["read-lock-${f.book}",BookReadLockViewModel::class.java].readOnly.value)}
        tap("exit-readonly")
        compose.runOnIdle{assertEquals(f.second,map().selectedNodeId);assertEquals(camera,map().snapshotViewport())}
        tap("workspace-recall")
        compose.waitUntil(15_000){compose.onAllNodesWithTag("branch-review-start").fetchSemanticsNodes().isNotEmpty()}
        tap("branch-review-start")
        compose.waitUntil(15_000){compose.onAllNodesWithTag("review-question").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("review-question").assertTextEquals("三态恢复合成问题")
        compose.onNodeWithTag("review-clues-hidden").assertExists()
        // Choose the visible Dialog's mode control; the paper remains mounted underneath.
        compose.onNode(hasContentDescription("阅读资料") and hasAnyAncestor(hasTestTag("manual-review"))).performClick()
        compose.onNodeWithTag("manual-review").assertDoesNotExist()
        tap("workspace-recall")
        compose.waitUntil(15_000){compose.onAllNodesWithTag("review-question").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("review-question").assertTextEquals("三态恢复合成问题")
        compose.onNodeWithTag("review-clues-hidden").assertExists()
        compose.onNodeWithTag("branch-review-start").assertDoesNotExist()
        compose.onNode(hasContentDescription("书写批注") and hasAnyAncestor(hasTestTag("manual-review"))).performClick()
        compose.runOnIdle{assertEquals(f.second,map().selectedNodeId);assertEquals(camera,map().snapshotViewport())}
        assertEquals(before.cards,cards(f.book));assertEquals(before.nodes,nodes(f.book))
        compose.runOnIdle{
            val lock=ViewModelProvider(compose.activity)["read-lock-${f.book}",BookReadLockViewModel::class.java]
            lock.guard("synthetic-unraised-pen",true)
            assertFalse(lock.canChangeMode());assertFalse(lock.request(true));assertFalse(lock.readOnly.value)
            assertTrue(lock.reason.contains("抬笔"));lock.guard("synthetic-unraised-pen",false)
        }
    }

}
