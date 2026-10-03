// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
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

class StudyRelationsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun row(book:String,data:KnowledgeData,id:String=id(),removed:Boolean=false)=
        KnowledgeRow(id,book,1,KnowledgeCodec.encode(data),removed)
    private fun link(book:String,from:String,to:String,kind:RelationKind)=
        row(book,KnowledgeData.Link(TargetRef(TargetKind.CARD,from),TargetRef(TargetKind.CARD,to),kind))
    private fun node(book:String,card:String=id(),parent:String?=null,x:Double=40.0,y:Double=80.0)=
        StudyNodeRow(id(),book,card,parent,x,y)

    @Test fun projectionIsEmptyWithoutSelectionAndNeverSynthesizesHierarchyOrDecorations(){
        val book=id();val parent=node(book);val child=node(book,parent=parent.id)
        val nodes=listOf(parent,child)
        val decoration=row(book,KnowledgeData.Decoration(parent.cardId,child.cardId))
        assertEquals(StudyRelationProjection(emptyList(),0),projectStudyRelations(null,nodes,listOf(decoration)))
        assertEquals(StudyRelationProjection(emptyList(),0),projectStudyRelations(parent.id,nodes,listOf(decoration)))
        val removed=link(book,parent.cardId,child.cardId,RelationKind.REFERENCE).copy(removed=true)
        assertEquals(StudyRelationProjection(emptyList(),0),projectStudyRelations(parent.id,nodes,listOf(removed)))
        assertEquals(StudyRelationProjection(emptyList(),0),projectStudyRelations(parent.id,
            listOf(parent.copy(removed=true),child),listOf(removed.copy(removed=false))))
    }

    @Test fun projectionKeepsDirectionTypesAndEveryVisibleOtherOccurrence(){
        val book=id();val a=node(book);val anotherA=node(book,a.cardId)
        val b=node(book,parent=a.id);val anotherB=node(book,b.cardId)
        val application=link(book,a.cardId,b.cardId,RelationKind.APPLICATION)
        val derivation=link(book,a.cardId,b.cardId,RelationKind.DERIVATION)
        val incoming=link(book,b.cardId,a.cardId,RelationKind.REFERENCE)
        val shown=listOf(a,anotherA,b,anotherB)
        val result=projectStudyRelations(a.id,shown,listOf(application,derivation,incoming,application))
        assertEquals(0,result.outOfScopeCount);assertEquals(4,result.edges.size)
        assertTrue(result.edges.none{it.fromNodeId==anotherA.id||it.toNodeId==anotherA.id})
        for(other in listOf(b,anotherB)){
            val outgoing=result.edges.single{it.fromNodeId==a.id&&it.toNodeId==other.id}
            assertEquals(listOf("推导","应用"),outgoing.labels)
            assertEquals(setOf(application.id,derivation.id),outgoing.originalLinkIds.toSet())
            val reverse=result.edges.single{it.fromNodeId==other.id&&it.toNodeId==a.id}
            assertEquals(listOf("内容引用"),reverse.labels);assertEquals(listOf(incoming.id),reverse.originalLinkIds)
        }
        val switched=projectStudyRelations(anotherA.id,shown,listOf(application))
        assertEquals(setOf(anotherA.id to b.id,anotherA.id to anotherB.id),switched.edges.map{it.fromNodeId to it.toNodeId}.toSet())
    }

    @Test fun projectionCountsEachHiddenOrNonCardLinkOnceWithoutCountingVisibleCopies(){
        val book=id();val a=node(book);val b=node(book);val hidden=node(book)
        val outside=link(book,a.cardId,hidden.cardId,RelationKind.CONTRAST)
        val page=row(id(),KnowledgeData.Link(TargetRef(TargetKind.PAGE,id()),TargetRef(TargetKind.CARD,a.cardId)))
        val visible=link(book,a.cardId,b.cardId,RelationKind.APPLICATION)
        val unrelated=link(book,b.cardId,hidden.cardId,RelationKind.REFERENCE)
        val shown=listOf(a,b,node(book,b.cardId),hidden.copy(removed=true),node(id(),hidden.cardId))
        val result=projectStudyRelations(a.id,shown,listOf(outside,outside,page,visible,unrelated))
        assertEquals(2,result.outOfScopeCount);assertEquals(2,result.edges.size)
        assertTrue(result.edges.all{it.originalLinkIds==listOf(visible.id)})
        assertEquals(3,projectStudyRelations(a.id,listOf(a),listOf(outside,page,visible)).outOfScopeCount)
    }

    private data class Fixture(val book:String,val sourceBook:String,val a:StudyNodeRow,val anotherA:StudyNodeRow,
        val b:StudyNodeRow,val anotherB:StudyNodeRow,val isolated:StudyNodeRow,val links:List<KnowledgeRow>)
    private fun fixture():Fixture {
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val note=runBlocking{app.workspaceRepository.create("知识关联合成验证 ${id()}",false,PaperStyle.RULED)}
        val sourceNote=runBlocking{app.workspaceRepository.create("知识关联跨本来源 ${id()}",false,PaperStyle.RULED)}
        val a=node(note.id);val anotherA=node(note.id,a.cardId,x=40.0,y=500.0)
        val b=node(note.id,parent=a.id,x=340.0);val anotherB=node(note.id,b.cardId,x=340.0,y=500.0)
        val isolated=node(note.id,x=680.0,y=300.0)
        val outsideSource=id()
        val links=listOf(link(note.id,a.cardId,b.cardId,RelationKind.APPLICATION),
            link(note.id,a.cardId,b.cardId,RelationKind.DERIVATION),
            link(note.id,b.cardId,a.cardId,RelationKind.REFERENCE),
            row(note.id,KnowledgeData.Link(TargetRef(TargetKind.CARD,a.cardId),TargetRef(TargetKind.NOTE,note.id))),
            link(sourceNote.id,outsideSource,isolated.cardId,RelationKind.APPLICATION))
        runBlocking{
            for((index,n) in listOf(a,b,isolated).withIndex())app.study.submit(StudyCommand(id(),note.id,StudyAction.CREATE,
                cardId=n.cardId,nodeId=n.id,parentId=n.parentId,title=listOf("关联起点","应用图表","独立主题")[index],
                body="只读关联合成测试",x=n.x,y=n.y))
            for(n in listOf(anotherA,anotherB))app.study.submit(StudyCommand(id(),note.id,StudyAction.REUSE,
                cardId=n.cardId,nodeId=n.id,x=n.x,y=n.y))
            app.study.submit(StudyCommand(id(),sourceNote.id,StudyAction.CREATE,cardId=outsideSource,nodeId=id(),
                title="跨本的应用来源",body="APPLICATION 来源正文：只读预览后回到原图。"))
            for(link in links)app.knowledge.submit(KnowledgeCommand(id(),link.notebookId,link.id,0,link.data()))
        }
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.frameCanvasFixture();tap("quick-study")
        compose.waitUntil(15_000){
            val state=ViewModelProvider(compose.activity)["study-${note.id}",StudyViewModel::class.java].ui.value
            !state.loading&&!state.busy&&!state.unknown&&state.graph?.graphFingerprint==runBlocking{app.study.readGraph(note.id).graphFingerprint}
        }
        compose.waitForIdle();compose.onNodeWithTag("study-map").assertIsDisplayed()
        return Fixture(note.id,sourceNote.id,a,anotherA,b,anotherB,isolated,links)
    }
    private fun tap(tag:String){
        compose.revealAction(tag)
        val target=compose.onNodeWithTag(tag);runCatching{target.performScrollTo()}
        target.assertIsDisplayed().assertIsEnabled().performTouchInput{click()};compose.waitForIdle()
    }
    private fun toggleRelations(){tap("study-management");tap("map-menu-group-0");tap("study-knowledge-relations-toggle")}
    private fun map():MindMapView {
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){
            val view=queue.removeFirst()
            if(view is MindMapView&&view.isShown)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)queue.add(view.getChildAt(i))
        }
        error("Visible study map missing")
    }
    @Suppress("UNCHECKED_CAST")
    private fun edges()=compose.runOnIdle{
        MindMapView::class.java.getDeclaredField("knowledgeRelations").apply{isAccessible=true}.get(map()) as List<StudyRelationEdge>
    }
    private fun select(nodeId:String){
        val point=compose.runOnIdle{map().focusNode(nodeId);val b=checkNotNull(map().nodeBounds(nodeId));Offset(b.centerX(),b.centerY())}
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput{advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong()+1);click(point)}
        compose.waitForIdle();compose.runOnIdle{assertEquals(nodeId,map().selectedNodeId)}
    }
    private data class AuthorSnapshot(val cards:List<StudyCardRow>,val nodes:List<StudyNodeRow>,val knowledge:List<String>,val graph:String)
    private fun author(book:String)=runBlocking{
        val graph=app.study.readGraph(book)
        AuthorSnapshot(app.study.cards(book).first().sortedBy{it.id},graph.nodes.sortedBy{it.id},
            app.knowledge.observeBook(book).first().sortedBy{it.id}.map{"${it.id}:${it.revision}:${it.removed}:${ContentTransfer.hash(it.payload)}"},graph.graphFingerprint)
    }

    @Test fun actualToggleAndSelectedOccurrenceChangeOnlyTheReadOnlyOverlay(){
        val f=fixture();val outgoingOnly=node(f.book,parent=f.b.id,x=680.0,y=680.0)
        val outgoingLink=row(f.book,KnowledgeData.Link(TargetRef(TargetKind.CARD,outgoingOnly.cardId),TargetRef(TargetKind.NOTE,f.book)))
        val branchLink=link(f.book,outgoingOnly.cardId,f.b.cardId,RelationKind.APPLICATION)
        runBlocking{
            app.study.submit(StudyCommand(id(),f.book,StudyAction.CREATE,cardId=outgoingOnly.cardId,nodeId=outgoingOnly.id,
                parentId=outgoingOnly.parentId,title="只有出链的主题",body="打开范围外关联后应先显示已有出链。",x=outgoingOnly.x,y=outgoingOnly.y))
            for(link in listOf(outgoingLink,branchLink))app.knowledge.submit(KnowledgeCommand(id(),f.book,link.id,0,link.data()))
        }
        compose.waitUntil(15_000){compose.runOnIdle{
            val state=ViewModelProvider(compose.activity)["study-map-writer-${f.book}",KnowledgeViewModel::class.java].ui.value
            map().nodeBounds(outgoingOnly.id)!=null&&!state.loading&&!state.readFailed&&
                state.rows.any{it.id==outgoingLink.id}&&state.rows.any{it.id==branchLink.id}
        }}
        val before=author(f.book);val sourceBefore=author(f.sourceBook)
        select(f.a.id);assertTrue(edges().isEmpty())
        compose.onNodeWithTag("study-knowledge-relations-legend").assertDoesNotExist()
        toggleRelations()
        compose.onNodeWithTag("study-knowledge-relations-legend").assertTextContains("虚线：知识关联")
        val shown=listOf(f.a,f.anotherA,f.b,f.anotherB,f.isolated,outgoingOnly)
        val links=f.links+listOf(outgoingLink,branchLink)
        val expected=projectStudyRelations(f.a.id,shown,links).edges
        compose.waitUntil(15_000){edges()==expected}
        compose.onNodeWithTag("study-knowledge-relations-outside").assertIsDisplayed()
        assertEquals(expected,edges())
        select(f.anotherA.id)
        assertEquals(projectStudyRelations(f.anotherA.id,shown,links).edges,edges())
        assertTrue(edges().none{it.fromNodeId==f.a.id||it.toNodeId==f.a.id})
        select(f.anotherB.id)
        assertEquals(projectStudyRelations(f.anotherB.id,shown,links).edges,edges())
        compose.onNodeWithTag("study-knowledge-relations-outside").assertTextContains("范围外 0",substring=true)
        select(f.b.id)
        fun foldGeometry()=compose.runOnIdle{
            val view=map();val position=IntArray(2);view.getLocationInWindow(position)
            val canvas=RectF(position[0].toFloat(),position[1].toFloat(),(position[0]+view.width).toFloat(),(position[1]+view.height).toFloat())
            val parent=RectF(checkNotNull(view.nodeBounds(f.b.id))).apply{offset(position[0].toFloat(),position[1].toFloat())}
            canvas to parent
        }
        val expandedGeometry=foldGeometry()
        compose.onNodeWithTag("node-fold").assertContentDescriptionEquals("收起下级主题")
        tap("node-fold")
        compose.waitUntil(15_000){compose.runOnIdle{map().nodeBounds(outgoingOnly.id)==null}}
        compose.onNodeWithTag("study-knowledge-relations-outside").assertTextContains("范围外 1",substring=true)
        assertEquals("A newly out-of-scope relation never moves or resizes the native canvas",expandedGeometry.first,foldGeometry().first)
        assertEquals("Folding keeps the selected parent at the same window coordinates",expandedGeometry.second,foldGeometry().second)
        compose.onNodeWithTag("node-fold").assertContentDescriptionEquals("展开下级主题")
        tap("node-fold")
        compose.waitUntil(15_000){compose.runOnIdle{map().nodeBounds(outgoingOnly.id)!=null}}
        compose.onNodeWithTag("study-knowledge-relations-outside").assertTextContains("范围外 0",substring=true)
        assertEquals("Expanding restores the child without moving the canvas or parent",expandedGeometry,foldGeometry())
        select(f.isolated.id);assertTrue(edges().isEmpty())
        val camera=compose.runOnIdle{map().snapshotViewport()}
        compose.onNodeWithTag("study-knowledge-relations-outside").assertTextContains("范围外 1",substring=true)
        tap("study-knowledge-relations-outside")
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("关联到这里 · 1")
        }.isSuccess}
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected().assertTextContains("关联到这里",substring=true)
        val incoming="knowledge-incoming-${f.links.last().id}"
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag(incoming))
            compose.onNodeWithTag(incoming).assertExists()
        }.isSuccess}
        compose.onNodeWithTag(incoming).assertTextContains("应用",substring=true)
        tap(incoming)
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("card-link-preview-body").assertTextEquals("APPLICATION 来源正文：只读预览后回到原图。")
        }.isSuccess}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("card-link-preview-body").assertTextEquals("APPLICATION 来源正文：只读预览后回到原图。")
        }.isSuccess}
        tap("card-link-close-preview");tap("knowledge-close")
        compose.onNodeWithTag("study-map").assertIsDisplayed()
        compose.runOnIdle{assertEquals(f.isolated.id,map().selectedNodeId);assertEquals(camera,map().snapshotViewport())}
        tap("node-links");tap("knowledge-links-incoming")
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("引用我的 · 0")
        }.isSuccess}
        compose.onNodeWithTag(incoming).assertDoesNotExist()
        compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("引用我的 · 0")
        tap("knowledge-close")
        select(outgoingOnly.id)
        val outgoingCamera=compose.runOnIdle{map().snapshotViewport()}
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("study-knowledge-relations-outside").assertTextContains("范围外 1",substring=true)
        }.isSuccess}
        tap("study-knowledge-relations-outside")
        compose.waitUntil(15_000){runCatching{
            compose.onNodeWithTag("knowledge-links-outgoing").assertIsSelected().assertTextContains("从这里关联 · 2")
        }.isSuccess}
        compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("关联到这里 · 0")
        val outgoing="knowledge-outgoing-${outgoingLink.id}"
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag(outgoing))
        compose.onNodeWithTag(outgoing).assertIsDisplayed()
        tap("knowledge-links-incoming")
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000){
            val state=ViewModelProvider(compose.activity)["knowledge-${f.book}",KnowledgeViewModel::class.java].ui.value
            !state.loading&&!state.readFailed
        }
        compose.waitForIdle()
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected().assertTextContains("关联到这里 · 0")
        compose.runOnIdle{ViewModelProvider(compose.activity)["knowledge-${f.book}",KnowledgeViewModel::class.java].reload()}
        compose.waitUntil(15_000){
            val state=ViewModelProvider(compose.activity)["knowledge-${f.book}",KnowledgeViewModel::class.java].ui.value
            !state.loading&&!state.readFailed
        }
        compose.waitForIdle()
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected().assertTextContains("关联到这里 · 0")
        compose.onNodeWithTag(outgoing).assertDoesNotExist()
        tap("knowledge-links-outgoing")
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag(outgoing))
        compose.onNodeWithTag(outgoing).assertIsDisplayed()
        tap("knowledge-close")
        compose.runOnIdle{assertEquals(outgoingOnly.id,map().selectedNodeId);assertEquals(outgoingCamera,map().snapshotViewport())}
        select(f.a.id);toggleRelations();assertTrue(edges().isEmpty())
        compose.onNodeWithTag("study-knowledge-relations-legend").assertDoesNotExist()
        assertEquals("Overlay controls and selection preserve all authored cards, occurrences and knowledge payloads",before,author(f.book))
        assertEquals("Cross-notebook source previews preserve their author's data",sourceBefore,author(f.sourceBook))
    }

    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}

    @Test fun nativeSceneReplacementTouchAndZeroScaleKeepFinalGeometryImmediately(){
        val originalScale=shell("settings get global animator_duration_scale")
        var holder:FrameLayout?=null
        var native:MindMapView?=null
        val book=id();val card=StudyCardRow(id(),book,1,"动画最终节点","合成场景")
        val start=node(book,card.id,x=40.0,y=80.0)
        val second=start.copy(x=260.0,y=100.0);val latest=start.copy(x=100.0,y=280.0)
        try{
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(10_000){compose.runOnIdle{ValueAnimator.areAnimatorsEnabled()}}
            compose.runOnIdle{
                val host=FrameLayout(compose.activity);holder=host
                val view=MindMapView(compose.activity);native=view
                host.addView(view,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                compose.activity.addContentView(host,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            }
            compose.waitUntil(10_000){compose.runOnIdle{checkNotNull(native).let{it.isAttachedToWindow&&it.width>0&&it.height>0}}}
            compose.runOnIdle{
                val view=checkNotNull(native);view.captureBook=book;view.captureMapKey="motion-test"
                view.restoreViewport(MapViewport(1f,0f,0f))
                view.transitionScene(setOf(start.id)){view.show(listOf(start),listOf(card))}
                assertFalse("A first scene has no outgoing geometry to animate",view.isSceneTransitionRunning)
                view.selectedNodeId=start.id
                var selected:String?=null;view.onSelect={selected=it?.id}
                view.enabledInput=false
                view.transitionScene(setOf(start.id)){view.show(listOf(second),listOf(card))}
                assertTrue("Committed geometry may arrive while author input is still busy",view.isSceneTransitionRunning)
                assertFalse(view.enabledInput);view.enabledInput=true
                assertEquals(second.x.toFloat()*view.resources.displayMetrics.density,checkNotNull(view.nodeBounds(start.id)).left,.01f)
                view.transitionScene(setOf(start.id)){view.show(listOf(latest),listOf(card))}
                assertTrue("The latest request replaces a running transition",view.isSceneTransitionRunning)
                val bounds=checkNotNull(view.nodeBounds(start.id))
                assertEquals(latest.x.toFloat()*view.resources.displayMetrics.density,bounds.left,.01f)
                assertEquals(latest.y.toFloat()*view.resources.displayMetrics.density,bounds.top,.01f)
                val outlines=MindMapView::class.java.getDeclaredField("previousOutlines").apply{isAccessible=true}.get(view) as List<*>
                assertEquals("Only the immediately previous scene is retained",1,outlines.size)
                assertEquals(second.x.toFloat()*view.resources.displayMetrics.density,(outlines.single() as RectF).left,.01f)
                val time=SystemClock.uptimeMillis()
                MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0).let{event->
                    try{view.dispatchTouchEvent(event)}finally{event.recycle()}
                }
                assertFalse("Touch cancels decoration before hit testing",view.isSceneTransitionRunning)
                MotionEvent.obtain(time,time+40,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0).let{event->
                    try{view.dispatchTouchEvent(event)}finally{event.recycle()}
                }
                assertEquals("Selection uses final coordinates without waiting for the animator",start.id,selected)
                view.transitionScene(setOf(start.id)){view.show(listOf(latest),listOf(card))}
                assertFalse("An unchanged scene never restarts motion",view.isSceneTransitionRunning)
                view.transitionScene(setOf(start.id)){view.show(listOf(start),listOf(card))}
                assertTrue(view.isSceneTransitionRunning);view.restoreViewport(view.snapshotViewport());view.fitOverview()
                assertTrue("Programmatic camera updates preserve the explicit scene transition",view.isSceneTransitionRunning)
                view.captureMapKey="another-map";assertFalse(view.isSceneTransitionRunning)
                view.transitionScene(setOf(start.id)){view.show(listOf(latest),listOf(card))}
                assertTrue(view.isSceneTransitionRunning);checkNotNull(holder).removeView(view)
                assertFalse("Detach releases transient outlines",view.isSceneTransitionRunning)
                checkNotNull(holder).addView(view)
            }
            shell("settings put global animator_duration_scale 0")
            compose.waitUntil(10_000){compose.runOnIdle{!ValueAnimator.areAnimatorsEnabled()}}
            compose.runOnIdle{
                val view=checkNotNull(native);view.restoreViewport(MapViewport(1f,0f,0f))
                view.transitionScene(setOf(start.id)){view.show(listOf(second),listOf(card))}
                assertFalse("Real Android zero duration disables the native animator",view.isSceneTransitionRunning)
                assertEquals(second.x.toFloat()*view.resources.displayMetrics.density,checkNotNull(view.nodeBounds(start.id)).left,.01f)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                val reference=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                try{
                    view.draw(Canvas(bitmap))
                    val direct=MindMapView(compose.activity);direct.layout(0,0,view.width,view.height)
                    direct.restoreViewport(view.snapshotViewport());direct.show(listOf(second),listOf(card));direct.selectedNodeId=start.id
                    direct.draw(Canvas(reference))
                    assertTrue("Zero-scale first frame equals the fully drawn final scene",bitmap.sameAs(reference))
                }finally{bitmap.recycle();reference.recycle()}
            }
        }finally{
            try{compose.runOnIdle{holder?.let{(it.parent as? ViewGroup)?.removeView(it)}}}finally{
                shell(if(originalScale=="null")"settings delete global animator_duration_scale"else "settings put global animator_duration_scale $originalScale")
            }
        }
    }
}
