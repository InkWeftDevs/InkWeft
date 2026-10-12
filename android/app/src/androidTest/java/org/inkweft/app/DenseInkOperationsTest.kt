// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DenseInkOperationsTest {
    private fun id()=UUID.randomUUID().toString()
    private suspend fun ready(model:InkViewModel,revision:Long)=withTimeout(30_000){model.ui.first{!it.loading&&!it.windowLoading&&it.queued==0&&it.blocked==null&&it.revision==revision}}
    private fun fixture(count:Int=900,block:suspend(NoteDatabase,InkRepository,InkViewModel,String,List<String>)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="dense-operation-${id()}.db";val db=NoteDatabase.open(context,name);val store=ViewModelStore()
        try{
            val page=WorkspaceRepository(db).create("局部操作独立预算",false,PaperStyle.BLANK).id
            val ids=List(count){id()}
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                ids.forEachIndexed{index,identity->val stroke=InkStroke(identity,if(index>=count-10)InkPen.HIGHLIGHTER else InkPen.PEN,0xff102030.toInt(),2f,InkTool.STYLUS,List(80){InkSample(50f+it*.04f,50f,it.toLong())})
                    db.ink().insertStroke(InkStrokeRow(identity,page,InkStrokeCodec.encodeStored(stroke),80,true,1))}
            }
            val repo=InkRepository(db)
            val model=withContext(Dispatchers.Main){ViewModelProvider(store,InkViewModel.Factory(page,repo))[InkViewModel::class.java]}
            ready(model,1);assertFalse(model.ui.value.windowComplete)
            block(db,repo,model,page,ids)
        }finally{withContext(Dispatchers.Main){store.clear()};db.close();context.deleteDatabase(name)}
    }
    @Test fun denseLocalSelectionIncludesUnloadedStrokesAndDeletesInOneUndoUnit()=fixture{db,repo,model,page,ids->
        val region=InkRegion(listOf(EraserPoint(40f,40f),EraserPoint(65f,60f)))
        val found=withContext(Dispatchers.Main){model.prepareOperation(region.bounds){stroke,geometry->geometry.selects(region,stroke)}}!!
        assertEquals(ids.toSet(),found.ids.toSet());assertEquals(72_000,found.strokes.sumOf{it.samples.size})
        assertFalse(model.ui.value.windowComplete)
        withContext(Dispatchers.Main){assertTrue(model.selectedEdit(1,InkMutation.Visibility(found.ids,false)))}
        ready(model,2);assertEquals(0,db.ink().visibleCount(page));assertEquals(900,db.ink().count(page))
        withContext(Dispatchers.Main){model.undo()};ready(model,3)
        assertEquals(900,db.ink().visibleCount(page));assertFalse(model.ui.value.canUndo)
        withContext(Dispatchers.Main){model.redo()};ready(model,4);assertEquals(0,db.ink().visibleCount(page))
        assertEquals(4L,repo.revision(page))
    }
    @Test fun densePartialEraserKeepsOnlyIdsAndOneSharedMaskForAllTargets()=fixture{db,repo,model,page,ids->
        withContext(Dispatchers.Main){model.erasePath(listOf(InkSample(51f,50f,0)),3f,false)}
        ready(model,2)
        val mask=db.ink().cuts(page).single();assertEquals(ids.toSet(),mask.strokeIds.split(',').toSet())
        assertTrue(model.ui.value.operationStrokes.isEmpty())
        var count=0;repo.visitVisible(page,2){batch->count+=batch.size;assertTrue(batch.all{it.cuts.single().id==mask.id})}
        assertEquals(900,count)
        withContext(Dispatchers.Main){model.undo()};ready(model,3)
        repo.visitVisible(page,3){batch->assertTrue(batch.all{it.cuts.isEmpty()})}
        assertFalse(model.ui.value.canUndo)
    }
    @Test fun cancelledOrStaleOperationPublishesNoPartialSelectionOrEdit()=fixture{db,repo,model,page,_->coroutineScope {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val once=AtomicBoolean(true)
        repo.readObserver={if(once.compareAndSet(true,false)){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        val query=async(Dispatchers.Main){model.prepareOperation(CanvasBounds(40.0,40.0,65.0,60.0)){_,_->true}}
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));query.cancel();release.countDown();query.join()
            assertFalse(model.ui.value.processing);assertTrue(model.ui.value.operationStrokes.isEmpty())
            assertEquals(1L,repo.revision(page));assertEquals(900,db.ink().visibleCount(page))
        }finally{release.countDown();query.cancelAndJoin();repo.readObserver=null}
        val changed=AtomicBoolean(false)
        repo.readObserver={if(changed.compareAndSet(false,true))runBlocking{
            val added=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),2f,InkTool.STYLUS,listOf(InkSample(300f,300f,0)))
            assertTrue(repo.save(CommitInk(id(),page,1,InkMutation.Add(added))) is InkCommitResult.Committed)
        }}
        val stale=withContext(Dispatchers.Main){model.prepareOperation(CanvasBounds(40.0,40.0,65.0,60.0)){_,_->true}}
        assertNull(stale);assertTrue(model.ui.value.operationStrokes.isEmpty());assertEquals(901,db.ink().visibleCount(page))
        repo.readObserver=null
    }}
    @Test fun typeFilteringAndObjectOnlySelectionApplyBeforeTheGeometryBudget()=fixture(1200){db,repo,model,page,ids->
        val all=withContext(Dispatchers.Main){model.prepareSelectAll()}
        assertNull(all);assertTrue(model.ui.value.operationStrokes.isEmpty());assertEquals(1L,repo.revision(page))
        val selected=withContext(Dispatchers.Main){model.prepareSelectAll({it.pen==InkPen.HIGHLIGHTER})}!!
        assertEquals(ids.takeLast(10).toSet(),selected.map{it.id}.toSet())
        var reads=0;repo.readObserver={reads++}
        val objectsOnly=withContext(Dispatchers.Main){model.prepareSelectAll(readInk=false)}!!
        assertTrue(objectsOnly.isEmpty());assertEquals(0,reads)
        assertEquals(1200,db.ink().visibleCount(page));repo.readObserver=null
    }
    @Test fun localOperationQueriesExcludeHiddenLayersAndRespectLockedLayerWrites()=fixture{_,repo,_,page,ids->
        val editable=UserLayer(id(),"可编辑");val locked=UserLayer(id(),"锁定",locked=true);val hidden=UserLayer(id(),"隐藏",visible=false)
        val memberships=ids.mapIndexed{index,id->LayerMembership(LayerContent(LayerContentKind.INK,id),when{index<400->locked.id;index<800->hidden.id;else->editable.id})}
        repo.authoringRepository.storePage(page,page,0,PageAuthoring(UserLayers(listOf(editable,locked,hidden),editable.id,memberships)))
        val writable=InkOperationReader.read(repo,page,1,CanvasBounds(40.0,40.0,65.0,60.0),true,false){_,_->true}
        assertEquals(ids.drop(800).toSet(),writable.ids.toSet());assertTrue(writable.strokes.isEmpty())
        val readable=InkOperationReader.read(repo,page,1,CanvasBounds(40.0,40.0,65.0,60.0),false,true){_,_->true}
        assertEquals((ids.take(400)+ids.drop(800)).toSet(),readable.ids.toSet())
        assertEquals(1L,repo.revision(page))
    }
    @Test fun leavingDenseSelectionReleasesUiGeometryAndExternalHeadReloadClearsStaleUndo()=fixture{_,repo,model,page,_->
        withContext(Dispatchers.Main){model.attachPage();assertEquals(900,model.prepareSelectAll()!!.size)}
        withContext(Dispatchers.Main){model.detachPage();assertTrue(model.ui.value.suspended);assertTrue(model.ui.value.operationStrokes.isEmpty());model.attachPage()}
        ready(model,1)
        fun added(x:Float)=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),2f,InkTool.STYLUS,listOf(InkSample(x,300f,0),InkSample(x+10,300f,10)))
        withContext(Dispatchers.Main){model.accept(added(240f))};ready(model,2);assertTrue(model.ui.value.canUndo)
        val external=added(300f);assertTrue(repo.save(CommitInk(id(),page,2,InkMutation.Add(external))) is InkCommitResult.Committed)
        withContext(Dispatchers.Main){model.updateViewport(CanvasBounds(200.0,200.0,500.0,500.0))}
        ready(model,3)
        assertTrue(model.ui.value.strokes.any{it.id==external.id});assertFalse(model.ui.value.canUndo);assertFalse(model.ui.value.canRedo)
        assertTrue(model.ui.value.operationStrokes.isEmpty());assertTrue(model.ui.value.automaticSourceIds.orEmpty().isEmpty())
        assertTrue(model.ui.value.message.orEmpty().contains("页面已更新"))
    }
    @Test fun readingSavedAfterConflictedDenseEditDropsThePreparedGeometry()=fixture{db,repo,model,page,ids->
        withContext(Dispatchers.Main){assertEquals(900,model.prepareSelectAll()!!.size)}
        val added=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),2f,InkTool.STYLUS,listOf(InkSample(300f,300f,0)))
        assertTrue(repo.save(CommitInk(id(),page,1,InkMutation.Add(added))) is InkCommitResult.Committed)
        withContext(Dispatchers.Main){assertTrue(model.selectedEdit(1,InkMutation.Visibility(ids,false)))}
        withTimeout(15_000){model.ui.first{it.blocked==InkCommitResult.Conflict}}
        withContext(Dispatchers.Main){model.discardRejectedDraft()};ready(model,2)
        assertTrue(model.ui.value.operationStrokes.isEmpty());assertFalse(model.ui.value.canUndo)
        assertEquals(901,db.ink().visibleCount(page));assertEquals(2L,repo.revision(page))
    }
}
