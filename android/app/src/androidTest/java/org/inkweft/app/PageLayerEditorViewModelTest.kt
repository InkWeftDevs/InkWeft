// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.core.AnnotationTarget
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PageLayerEditorViewModelTest {
    private fun id()=UUID.randomUUID().toString()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync{action()}
    private suspend fun ready(model:PageAuthoringViewModel,revision:Long)=withTimeout(15_000){model.ui.first{it.ready&&it.revision==revision}}
    @Test fun pendingUndoAfterModelRecreationCanBeUndoneAndTenHeaderHistoryStepsStayUsable()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="layer-vm-${id()}.db";val db=NoteDatabase.open(context,name)
        var store=ViewModelStore();lateinit var model:PageAuthoringViewModel
        val fail=AtomicBoolean(false)
        try{
            val page=WorkspaceRepository(db).create("合成图层会话恢复",false,PaperStyle.BLANK).id;val scope=AuthoringScope.page(page,page)
            val first=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0)))
            val second=InkStroke(id(),InkPen.PEN,first.color,2f,InkTool.STYLUS,listOf(InkSample(800f,900f,0)))
            InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Replace(emptyList(),listOf(first,second))))
            val repository=PageAuthoringRepository(db){if(it==AuthoringFault.AFTER_TRANSACTION&&fail.compareAndSet(true,false))error("lost author response")}
            fun create(repo:PageAuthoringRepository){main{model=ViewModelProvider(store,PageAuthoringViewModel.Factory(scope,repo))[PageAuthoringViewModel::class.java];model.contents(listOf(first))}}
            create(repository);ready(model,0)
            assertEquals(1,model.ui.value.state.layers.memberships.size)
            val annotation=BoundAnnotation(InkStroke(id(),InkPen.PEN,first.color,2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0,world=true)),true),AnnotationTarget(AnnotationTargetKind.PAGE,page))
            val withAnnotation=model.ui.value.state.add(annotation);val historyHeader=model.historical(withAnnotation)
            assertSame(annotation,historyHeader.annotations.single());assertSame(annotation.stroke,historyHeader.annotations.single().stroke)
            assertSame(annotation.stroke.samples,historyHeader.annotations.single().stroke.samples)
            assertEquals(listOf(LayerContentKind.ANNOTATION),historyHeader.layers.memberships.map{it.content.kind})
            val target=UserLayer(id(),"目标")
            main{assertTrue(model.layers{it.add(target)})};ready(model,1)
            main{assertTrue(model.transferLayer(UserLayers.DEFAULT_ID,target.id))};ready(model,2)
            val refs=listOf(first,second).map{LayerContent(LayerContentKind.INK,it.id)}
            assertTrue(repository.read(scope,refs).state.layers.memberships.all{it.layerId==target.id})
            fail.set(true);main{model.undo()}
            withTimeout(15_000){model.ui.first{it.pending&&!it.busy}}
            main{store.clear()};store=ViewModelStore()
            val reopened=PageAuthoringRepository(db);create(reopened);ready(model,3)
            assertTrue(reopened.read(scope,refs).state.layers.memberships.all{it.layerId==UserLayers.DEFAULT_ID})
            // Recovery has no old in-memory stack: its single undo must invert the recovered undo.
            main{model.undo()};ready(model,4)
            assertTrue(reopened.read(scope,refs).state.layers.memberships.all{it.layerId==target.id})
            main{model.redo()};ready(model,5)
            assertTrue(reopened.read(scope,refs).state.layers.memberships.all{it.layerId==UserLayers.DEFAULT_ID})
            repeat(12){index->main{assertTrue(model.layers{it.update(target.copy(name="层 $index"))})};ready(model,6L+index)}
            repeat(10){index->main{model.undo()};ready(model,18L+index)}
            assertFalse(model.ui.value.undo);assertTrue(model.ui.value.redo)
            assertEquals("层 1",model.ui.value.state.layers.layers.single{it.id==target.id}.name)
            val retained=db.openHelper.readableDatabase.query("SELECT COUNT(DISTINCT commandId) FROM page_layer_changes WHERE pageId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}
            assertTrue("Session history must prune old row deltas",retained<=11)
        }finally{main{store.clear()};db.close();context.deleteDatabase(name)}
    }
    @Test fun blockedMembershipRefreshKeepsNewStrokeAdmissionButBlocksAuthorEdits()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="layer-admission-${id()}.db";val db=NoteDatabase.open(context,name)
        val store=ViewModelStore();val release=CompletableDeferred<Unit>();var gate:Job?=null
        try{
            val page=WorkspaceRepository(db).create("合成连续落笔",false,PaperStyle.BLANK).id;val scope=AuthoringScope.page(page,page)
            fun stroke(x:Float)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,30f,0)))
            val first=stroke(20f);val second=stroke(400f);val inkRepository=InkRepository(db)
            inkRepository.save(CommitInk(id(),page,0,InkMutation.Replace(emptyList(),listOf(first,second))))
            lateinit var author:PageAuthoringViewModel;lateinit var ink:InkViewModel
            main{
                author=ViewModelProvider(store,PageAuthoringViewModel.Factory(scope,PageAuthoringRepository(db)))[PageAuthoringViewModel::class.java]
                ink=ViewModelProvider(store,InkViewModel.Factory(page,inkRepository))[InkViewModel::class.java]
                author.contents(listOf(first));ink.layerScopeProvider=author::scopeForWrite
            }
            ready(author,0);withTimeout(15_000){ink.ui.first{!it.loading&&it.canStart}}
            val entered=CompletableDeferred<Unit>()
            gate=launch(Dispatchers.IO){db.withTransaction{entered.complete(Unit);release.await()}}
            entered.await();main{author.contents(listOf(first,second))}
            val addition=stroke(200f)
            main{
                assertFalse("Existing-content edits must wait for the exact identity projection",author.ui.value.ready)
                assertFalse(author.layers{it.update(it.layers.first().copy(name="不可抢先提交"))})
                assertTrue("An unchanged writable layer must continue accepting the next pen",ink.beginLayerWrite())
                ink.accept(addition)
                assertEquals(1,ink.ui.value.queued)
            }
            release.complete(Unit);checkNotNull(gate).join()
            withTimeout(15_000){ink.ui.first{it.revision==2L&&it.queued==0&&it.blocked==null}}
            ready(author,0)
            assertNotNull(db.ink().stroke(addition.id));assertEquals(3,inkRepository.readVisible(page).size)
            assertEquals("基础层",author.ui.value.state.layers.layers.first().name)
        }finally{release.complete(Unit);gate?.join();main{store.clear()};db.close();context.deleteDatabase(name)}
    }

    @Test fun fullSelectionProjectsOffscreenVisibleAndLockedLayersAndRejectsAStaleInkHead()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="layer-select-all-${id()}.db";val db=NoteDatabase.open(context,name);val store=ViewModelStore()
        try{
            val page=WorkspaceRepository(db).create("合成离屏全选",true,PaperStyle.BLANK).id;val scope=AuthoringScope.page(page,page)
            fun stroke(x:Float)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,50f,0,world=true)),true)
            val strokes=listOf(stroke(20f),stroke(5000f),stroke(7000f),stroke(9000f));val inkRepository=InkRepository(db)
            inkRepository.save(CommitInk(id(),page,0,InkMutation.Replace(emptyList(),strokes)))
            val other=UserLayer(id(),"离屏可写");val hidden=UserLayer(id(),"离屏隐藏",visible=false);val locked=UserLayer(id(),"离屏锁定",locked=true)
            val repository=PageAuthoringRepository(db);val before=repository.read(scope)
            val layers=listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),other,hidden,locked)
            repository.save(scope,before,id(),PageAuthoring(UserLayers(layers,UserLayers.DEFAULT_ID,strokes.mapIndexed{i,s->LayerMembership(LayerContent(LayerContentKind.INK,s.id),layers[i].id)})))
            lateinit var author:PageAuthoringViewModel;lateinit var ink:InkViewModel
            main{
                author=ViewModelProvider(store,PageAuthoringViewModel.Factory(scope,repository))[PageAuthoringViewModel::class.java]
                ink=ViewModelProvider(store,InkViewModel.Factory(page,inkRepository))[InkViewModel::class.java]
                author.contents(listOf(strokes.first()))
            }
            ready(author,1);withTimeout(15_000){ink.ui.first{!it.loading&&it.canStart}}
            assertEquals(setOf(strokes.first().id),ink.ui.value.strokes.map{it.id}.toSet())
            val all=withContext(Dispatchers.Main.immediate){checkNotNull(ink.prepareSelectAll())}
            assertEquals(setOf(strokes[0].id,strokes[1].id,strokes[3].id),all.map{it.id}.toSet())
            val projected=withContext(Dispatchers.Main.immediate){checkNotNull(author.prepareContents(all,1,0,1))}
            val region=InkRegion(listOf(EraserPoint(-10_000f,-10_000f),EraserPoint(10_000f,10_000f)))
            val selected=CanvasSelectionEdit.query(region,1,all,emptyList(),SelectionOptions(),VisibleInkGeometry(),projected.state.layers)
            assertEquals(setOf(strokes[0].id,strokes[1].id,strokes[3].id),selected.strokes.map{it.id}.toSet())
            assertEquals(listOf(LayerContent(LayerContentKind.INK,strokes[3].id)),selected.locked)
            assertEquals(3,author.ui.value.state.layers.memberships.size)
            assertNull(withContext(Dispatchers.Main.immediate){author.prepareContents(all,0,0,1)})
        }finally{main{store.clear()};db.close();context.deleteDatabase(name)}
    }

}
