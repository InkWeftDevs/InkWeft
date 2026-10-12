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
    @Test fun viewportCompletesBeforeOffscreenGeometryAndNewInvalidationsNeverEscapeCoverage()=fixture{db,page->
        val near=stroke();val far=List(600){stroke(10_000f,10_000f,512)}
        seed(db,page,far+near);val repo=InkRepository(db);val region=CanvasBounds(90.0,90.0,110.0,110.0)
        val sql=db.openHelper.writableDatabase
        fun pending()=sql.query("SELECT COUNT(*) FROM page_spatial_pending WHERE pageId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}
        val window=repo.readForEditing(page,bounds=region)
        assertTrue(window.windowComplete);assertEquals(listOf(near.id),window.strokes.map{it.stroke.id});assertEquals(600,pending())
        assertEquals(600,sql.query("SELECT COUNT(*) FROM page_spatial_hints WHERE pageId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)})
        val moved=InkStroke(far[0].id,far[0].pen,far[0].color,far[0].width,far[0].tool,near.samples,true)
        sql.execSQL("UPDATE ink_strokes SET payload=?,pointCount=2 WHERE id=?",arrayOf<Any>(InkStrokeCodec.encodeStored(moved),moved.id))
        assertEquals(setOf(near.id,moved.id),repo.readForEditing(page,bounds=region).strokes.map{it.stroke.id}.toSet())
        // A prior-format payload has no coverage header and must be fully decoded, including
        // the possibility of returning from outside the previously covered region.
        val legacy=stroke(102f,102f)
        db.ink().insertStroke(InkStrokeRow(legacy.id,page,InkStrokeCodec.encode(legacy),2,true,1))
        assertEquals(3,repo.readForEditing(page,bounds=region).strokes.size)
        assertTrue(pending()>0)
        assertTrue(repo.visibleBounds(page,1)!!.right>9000);assertEquals(0,pending())
        println("VIEWPORT_PRIORITY current=3 offscreen=600 complete=true pendingBeforeFull=599")
    }
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

    @Test fun cancelledIndexBuildKeepsCommittedProgressAndYieldsToAuthorWrites()=fixture{db,page->coroutineScope {
        seed(db,page,List(700){stroke(count=512)})
        fun pending()=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM page_spatial_pending WHERE pageId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}
        var published=false
        val cancelled=runCatching{SpatialIndex.read(db,page,onBatch={batch->
            assertTrue(batch.rows in 1..128);assertTrue(batch.payloadBytes<=InkLimits.MAX_STROKE_BYTES)
            throw CancellationException("cancel after committed progress")
        }){published=true}}
        assertTrue(cancelled.exceptionOrNull() is CancellationException);assertFalse(published)
        val retained=pending();assertTrue(retained in 1 until 700)
        val reached=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();var batches=0
        val build=async(Dispatchers.IO){SpatialIndex.read(db,page,onBatch={batch->
            batches++;assertTrue(batch.rows<=128);assertTrue(batch.payloadBytes<=InkLimits.MAX_STROKE_BYTES)
            if(batches==1){reached.complete(Unit);resume.await()}
        }){sql->assertEquals(0,pending());sql.query("SELECT COUNT(*) FROM page_spatial_entries WHERE pageId=? AND kind=0",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}}}
        try{
            withTimeout(5000){reached.await()}
            val added=stroke(300f,300f)
            val result=withTimeout(5000){withContext(Dispatchers.IO){InkRepository(db).save(CommitInk(id(),page,1,InkMutation.Add(added)))}}
            assertTrue(result is InkCommitResult.Committed)
            resume.complete(Unit);assertEquals(701,withTimeout(15_000){build.await()})
            assertEquals(2L,InkRepository(db).revision(page))
        }finally{resume.complete(Unit);build.cancelAndJoin()}
    }}
    @Test fun pageBucketCollisionStillFiltersFullIdentityAndOldOrMissingRtreeRebuilds()=fixture{db,book->
        val a="00000000-0000-0000-0000-0000000000Aa";val b="00000000-0000-0000-0000-0000000000BB"
        assertEquals(SpatialIndex.pageBucket(a),SpatialIndex.pageBucket(b));assertNotEquals(a,b)
        val first=stroke();val second=stroke()
        db.withTransaction{
            db.pages().insert(NotebookPageRow(a,book,1,true,0));db.pages().insert(NotebookPageRow(b,book,2,true,0))
            seed(db,a,listOf(first));seed(db,b,listOf(second))
        }
        val repo=InkRepository(db);val region=CanvasBounds(90.0,90.0,110.0,110.0)
        assertEquals(listOf(first.id),repo.readForEditing(a,bounds=region).strokes.map{it.stroke.id})
        assertEquals(listOf(second.id),repo.readForEditing(b,bounds=region).strokes.map{it.stroke.id})
        val sql=db.openHelper.writableDatabase;val payload=checkNotNull(db.ink().stroke(first.id)).payload
        for(oldTable in listOf(true,false)){
            db.withTransaction{
                sql.execSQL("DROP TABLE page_spatial_bounds")
                if(oldTable)sql.execSQL("CREATE VIRTUAL TABLE page_spatial_bounds USING rtree(key,minX,maxX,minY,maxY)")
                SpatialIndex.install(sql)
            }
            assertEquals(2,sql.query("SELECT COUNT(*) FROM page_spatial_pending WHERE pageId IN (?,?)",arrayOf(a,b)).use{it.moveToFirst();it.getInt(0)})
            assertEquals(listOf(first.id),repo.readForEditing(a,bounds=region).strokes.map{it.stroke.id})
            assertEquals(listOf(second.id),repo.readForEditing(b,bounds=region).strokes.map{it.stroke.id})
            assertArrayEquals(payload,checkNotNull(db.ink().stroke(first.id)).payload)
            assertEquals(1L,repo.revision(a));assertEquals(1L,repo.revision(b))
        }
    }

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

    @Test fun panningDropsUndoGeometryAndPreservesMaskUndoRedo()=fixture{db,page->
        val repo=InkRepository(db);val old=stroke();val far=stroke(10_000f,10_000f)
        seed(db,page,listOf(old,far));val session=InkSession(repo.readForEditing(page,bounds=old.bounds()))
        val cut=InkCut(id(),2f,listOf(EraserPoint(100f,100f)))
        session.enqueue(InkMutation.Cut(EraseSelection(cut,listOf(old.id))));val command=session.nextCommand()!!;session.complete(command,repo.save(command))
        val history=session.suspendHistory();val snapshot=repo.readForEditing(page,history,far.bounds())
        assertEquals(listOf(far.id),snapshot.strokes.map{it.stroke.id});assertTrue(snapshot.cuts.isEmpty());assertEquals(1,snapshot.unloaded.cutCount)
        val identity=session.undoIdentity;session.refreshWindow(snapshot);assertSame(identity,session.undoIdentity)
        session.requestUndo();val undo=session.nextCommand()!!;session.complete(undo,repo.save(undo))
        val returned=repo.readForEditing(page,session.suspendHistory(),old.bounds());session.refreshWindow(returned)
        assertTrue(session.visibleDraft().single().cuts.isEmpty())
        session.requestRedo();val redo=session.nextCommand()!!;session.complete(redo,repo.save(redo))
        session.refreshWindow(repo.readForEditing(page,session.suspendHistory(),old.bounds()))
        assertEquals(cut.id,session.visibleDraft().single().cuts.single().id)
    }

    @Test fun storedValidationUsesPageAndIdentityIndexWithoutRepeatedSorting()=fixture{db,page->
        for(table in listOf("ink_strokes","ink_cuts")){
            val details=mutableListOf<String>()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT id,payload FROM $table WHERE noteId=? AND id>? ORDER BY id LIMIT 256",arrayOf(page,"")).use{cursor->
                while(cursor.moveToNext())details+=cursor.getString(3)
            }
            assertTrue(details.joinToString(),details.any{it.contains("index_${table}_noteId_id")})
            assertFalse(details.joinToString(),details.any{it.contains("TEMP B-TREE",ignoreCase=true)})
        }
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
        val sourceHeader=checkNotNull(db.authoring().get("PAGE",page)).payload
        val runtime=Runtime.getRuntime()
        fun heap()=runtime.totalMemory()-runtime.freeMemory()
        fun retainedHeap():Long {System.gc();System.runFinalization();System.gc();return heap()}
        val before=retainedHeap();var firstQuarter:Long?=null;var peak=before;var largestBatch=0;var visited=0
        repo.readObserver={timing->
            visited+=timing.entries;largestBatch=maxOf(largestBatch,timing.entries);peak=maxOf(peak,heap())
            if(firstQuarter==null&&visited>=8192)firstQuarter=retainedHeap()
        }
        val copied=try{db.withTransaction{PageCopy(db,repo).copy(page,target)}}finally{repo.readObserver=null}
        val after=retainedHeap();val growth=after-checkNotNull(firstQuarter)
        assertEquals(40_002L,copied.strokeCount);assertEquals(2L,copied.sourceRevision);assertEquals(page,copied.source);assertEquals(target,copied.target)
        assertTrue("Retained copy state grew by $growth bytes after the first 8192 strokes",growth<=3L*1024*1024)
        assertTrue(largestBatch<=256);assertEquals(40_002,visited)
        assertEquals(40_002L,db.authoring().membershipCount(target));assertEquals(40_002L,repo.validateStored(target))
        assertArrayEquals(sourceHeader,checkNotNull(db.authoring().get("PAGE",page)).payload)
        val newId=copied.strokeId(added.id);assertNotEquals(added.id,newId)
        assertEquals(newId,copied.strokeId(added.id));assertNotEquals(newId,copied.cutId(added.id))
        assertEquals(added.samples,repo.readVisibleSnapshot(target,listOf(newId)).strokes.single().samples)
        val beforeFullAuthoring=retainedHeap()
        val fullAuthoring=PageAuthoringRepository(db).readPage(target)
        val fullAuthoringBytes=retainedHeap()-beforeFullAuthoring
        assertEquals(40_002,fullAuthoring.state.layers.memberships.size)
        val evidence=org.json.JSONObject().put("kind","large-page-copy").put("strokes",copied.strokeCount).put("points",points)
            .put("largestGeometryBatch",largestBatch).put("heapBeforeBytes",before).put("heapAfterFirst8192Bytes",firstQuarter)
            .put("heapAfterBytes",after).put("observedHeapPeakBytes",peak).put("retainedGrowthAfter8192Bytes",growth)
            .put("copiedMemberships",db.authoring().membershipCount(target)).put("fullAuthoringStateRetainedBytes",fullAuthoringBytes)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","PAGE_COPY_EVIDENCE $evidence\n")})
    }
}
