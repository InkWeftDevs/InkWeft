// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SpatialInkRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float=100f,y:Float=100f,count:Int=2)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        List(count){InkSample(x+(it%10)*.1f,y,it.toLong(),.2f+(it%5)*.1f,.3f,.4f,true)},true)
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="spatial-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成空间索引",true,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun seed(db:NoteDatabase,page:String,values:List<InkStroke>){db.withTransaction{
        if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
        values.forEach{s->db.ink().insertStroke(InkStrokeRow(s.id,page,InkStrokeCodec.encodeStored(s),s.samples.size,true,1))}
    }}

    @Test fun viewportCandidatesRespectBoundaryVisibilityPayloadChangesAndDeletion()=fixture{db,page->
        val near=stroke();val far=stroke(10_000f,10_000f);seed(db,page,listOf(near,far));val repo=InkRepository(db)
        val bounds=CanvasBounds(98.0,98.0,103.0,103.0)
        assertEquals(listOf(near.id),repo.readForEditing(page,bounds=bounds).strokes.map{it.stroke.id})
        val full=repo.visibleBounds(page,1)!!;assertTrue(full.left<=near.bounds().left&&full.right>=far.bounds().right)
        db.ink().setVisibility(page,listOf(near.id),false)
        assertTrue(repo.readForEditing(page,bounds=bounds).strokes.isEmpty())
        assertTrue(repo.visibleBounds(page,1)!!.left>9000)
        db.ink().setVisibility(page,listOf(near.id),true)
        val moved=InkStroke(near.id,near.pen,near.color,near.width,near.tool,far.samples,true)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf<Any>(InkStrokeCodec.encodeStored(moved),near.id))
        assertTrue(repo.readForEditing(page,bounds=bounds).strokes.isEmpty())
        val edge=far.bounds();assertEquals(2,repo.readForEditing(page,bounds=CanvasBounds(edge.left,edge.top,edge.left,edge.top)).strokes.size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM ink_strokes WHERE id=?",arrayOf(far.id))
        assertEquals(listOf(near.id),repo.readForEditing(page,bounds=edge).strokes.map{it.stroke.id})
    }

    @Test fun boundedWindowNeverPretendsDenseCandidatesAreCompleteAndStreamingHasNoMissingRows()=fixture{db,page->
        val values=List(1001){stroke(count=96)};seed(db,page,values);val repo=InkRepository(db)
        val bounds=CanvasBounds(0.0,0.0,200.0,200.0)
        val window=repo.readForEditing(page,bounds=bounds,pointBudget=8192)
        assertFalse(window.windowComplete);assertTrue(window.strokes.sumOf{it.stroke.samples.size}<=8192)
        assertEquals(1001,window.retainedStrokeCount)
        val ids=mutableListOf<String>();var batches=0
        repo.visitVisible(page,1,bounds){batch->batches++;assertTrue(batch.size<=256);assertTrue(batch.sumOf{it.samples.size}<=65_536);ids.addAll(batch.map{it.id})}
        assertTrue(batches>1);assertEquals(values.map{it.id}.sorted(),ids)
        val small=repo.readForEditing(page,bounds=CanvasBounds(500.0,500.0,600.0,600.0),pointBudget=8192)
        assertTrue(small.windowComplete);assertTrue(small.strokes.isEmpty())
    }

    @Test fun pageChangeAndCancellationAbortStreamingRatherThanReturningACompletePrefix()=fixture{db,page->
        seed(db,page,List(520){stroke()});val repo=InkRepository(db);var batches=0
        val failure=runCatching{repo.visitVisible(page,1){if(++batches==1)check(db.ink().compareAndSet(page,1,2)==1)}}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertEquals(1,batches)
        batches=0
        assertTrue(runCatching{repo.visitVisible(page,2){batches++;throw CancellationException("synthetic cancellation")}}.exceptionOrNull() is CancellationException)
        assertEquals(1,batches);assertEquals(2L,repo.revision(page))
    }

    @Test fun panningRetainsOnlyUndoGeometryAndPreservesMaskUndoRedo()=fixture{db,page->
        val repo=InkRepository(db);val old=stroke();val far=stroke(10_000f,10_000f)
        seed(db,page,listOf(old,far));val session=InkSession(repo.readForEditing(page,bounds=old.bounds()))
        val cut=InkCut(id(),2f,listOf(EraserPoint(100f,100f)))
        session.enqueue(InkMutation.Cut(EraseSelection(cut,listOf(old.id))));val command=session.nextCommand()!!;session.complete(command,repo.save(command))
        val history=session.suspendHistory();val snapshot=repo.readForEditing(page,history,far.bounds())
        assertEquals(listOf(far.id),snapshot.strokes.map{it.stroke.id});assertEquals(setOf(cut.id),snapshot.cuts.map{it.selection.cut.id}.toSet())
        val identity=session.undoIdentity;session.refreshWindow(snapshot);assertSame(identity,session.undoIdentity)
        session.requestUndo();val undo=session.nextCommand()!!;session.complete(undo,repo.save(undo))
        val returned=repo.readForEditing(page,session.suspendHistory(),old.bounds());session.refreshWindow(returned)
        assertTrue(session.visibleDraft().single().cuts.isEmpty())
        session.requestRedo();val redo=session.nextCommand()!!;session.complete(redo,repo.save(redo))
        assertEquals(cut.id,session.visibleDraft().single().cuts.single().id)
    }

    @Test fun validationIncludesHiddenInkAndHiddenMasks()=fixture{db,page->
        val repo=InkRepository(db);val s=stroke();seed(db,page,listOf(s))
        val cut=InkCut(id(),2f,listOf(EraserPoint(100f,100f)))
        db.ink().insertCut(InkCutRow(cut.id,page,InkCutCodec.encode(cut),s.id,false,1))
        db.ink().setVisibility(page,listOf(s.id),false);assertEquals(1L,repo.validateStored(page))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_cuts SET payload=? WHERE id=?",arrayOf<Any>(byteArrayOf(0),cut.id))
        assertTrue(runCatching{repo.validateStored(page)}.isFailure)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_cuts SET payload=? WHERE id=?",arrayOf<Any>(InkCutCodec.encode(cut),cut.id))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf<Any>(byteArrayOf(0),s.id))
        assertTrue(runCatching{repo.validateStored(page)}.isFailure)
    }

    @Test fun fortyThousandStrokesAndTwoMillionPointsRemainWritableCopyableAndVerifiable()=fixture{db,page->
        // Generate one stroke at a time: this fixture itself must not load a large page into memory.
        db.withTransaction{
            if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
            repeat(40_001){i->val s=stroke((i%200)*20f,(i/200)*20f,51)
                db.ink().insertStroke(InkStrokeRow(s.id,page,InkStrokeCodec.encodeStored(s),51,true,1))}
        }
        val repo=InkRepository(db);val added=stroke();assertEquals(InkCommitResult.Committed(2),repo.save(CommitInk(id(),page,1,InkMutation.Add(added))))
        val window=repo.readForEditing(page,bounds=CanvasBounds(0.0,0.0,180.0,180.0),pointBudget=8192)
        assertTrue(window.windowComplete);assertTrue(window.strokes.size<200);assertEquals(40_002,window.retainedStrokeCount)
        var count=0;var points=0L;repo.visitVisible(page,2){count+=it.size;points+=it.sumOf{s->s.samples.size.toLong()}}
        assertEquals(40_002,count);assertEquals(40_001L*51+2,points);assertEquals(2L,repo.validateStored(page))
        val target=WorkspaceRepository(db).create("大页复制",true,PaperStyle.BLANK).id
        val copied=db.withTransaction{repo.copyVisibleTo(page,target)}
        assertEquals(40_002,copied.strokeIds.size);assertEquals(40_002L,repo.validateStored(target))
        val newId=copied.strokeIds[added.id]!!;assertEquals(added.samples,repo.readVisibleSnapshot(target,listOf(newId)).strokes.single().samples)
    }
}
