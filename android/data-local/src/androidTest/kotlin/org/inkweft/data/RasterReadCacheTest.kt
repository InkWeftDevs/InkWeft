// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RasterReadCacheTest {
    private fun id(index:Int)=UUID(0,index.toLong()).toString()
    private fun stroke(index:Int,x:Float)=InkStroke(id(index),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,
        listOf(InkSample(x,100f,0,world=true),InkSample(x+12,112f,10,world=true)),true)
    private fun fixture(block:suspend(NoteDatabase,String,InkRepository)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="raster-cache-${UUID.randomUUID()}.db";val db=NoteDatabase.open(context,name)
        try{val page=WorkspaceRepository(db).create("合成栅格读取",true,PaperStyle.BLANK).id;block(db,page,InkRepository(db))}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun seed(db:NoteDatabase,page:String,values:List<InkStroke>)=db.withTransaction {
        if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
        values.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),it.samples.size,true,1))}
    }
    @Test fun separatedRegionsSkipEmptyMetadataBatchesAndDecodeCrossingStrokeOnce()=fixture {db,page,repo->
        val crossing=InkStroke(id(702),InkPen.PEN,0xff654321.toInt(),3f,InkTool.STYLUS,
            listOf(InkSample(90f,105f,0,world=true),InkSample(2100f,105f,10,world=true)),true)
        seed(db,page,listOf(stroke(1,100f))+List(700){stroke(it+2,1000f)}+crossing+stroke(703,2100f))
        val regions=listOf(CanvasBounds(80.0,80.0,150.0,150.0),CanvasBounds(2080.0,80.0,2140.0,150.0))
        val union=regions.reduce{a,b->a.union(b)};val cache=repo.rasterCache()
        var decoded=0;repo.readObserver={decoded+=it.entries}
        suspend fun read(omitted:Set<String> = emptySet()):List<String>{
            val ids=mutableListOf<String>()
            repo.visitVisible(page,1,union,regions=regions,omitted=omitted,cache=cache){ids+=it.map{it.id}}
            return ids
        }
        assertEquals(listOf(id(1),id(702),id(703)),read());assertEquals(3,decoded)
        decoded=0;assertEquals(listOf(id(1),id(702),id(703)),read());assertEquals(0,decoded);assertEquals(3,cache.hits)
        assertEquals(listOf(id(1),id(703)),read(setOf(id(702))))
        assertTrue(cache.retainedBytes in 1..InkRasterCache.MAX_BYTES)
        assertTrue(runCatching{InkRepository(db).visitVisible(page,1,union,cache=cache){}}.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun payloadMaskAndRevisionChangesCannotReuseStaleDecodedGeometry()=fixture {db,page,repo->
        val initial=stroke(1,100f);seed(db,page,listOf(initial))
        val bounds=CanvasBounds(0.0,0.0,500.0,500.0);val cache=repo.rasterCache()
        suspend fun read(revision:Long):InkStroke {
            val values=mutableListOf<InkStroke>();repo.visitVisible(page,revision,bounds,cache=cache){values+=it};return values.single()
        }
        val old=read(1);assertSame(old,read(1))
        // Repair/import SQL may replace payload at an unchanged page revision. Spatial
        // identity still changes, so the old decoded payload is never returned.
        val moved=stroke(1,200f)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf<Any>(InkStrokeCodec.encodeStored(moved),moved.id))
        assertEquals(200f,read(1).samples.first().x,0f)
        val cut=InkCut(id(10),5f,listOf(EraserPoint(205f,105f)))
        assertTrue(repo.save(CommitInk(id(11),page,1,InkMutation.Cut(EraseSelection(cut,listOf(initial.id))))) is InkCommitResult.Committed)
        assertEquals(cut.id,read(2).cuts.single().id)
        // The raster epoch guards even same-revision mask changes.
        assertEquals(1,db.ink().cutVisibility(cut.id,page,false));assertTrue(read(2).cuts.isEmpty())
        val failure=runCatching{repo.visitVisible(page,2,bounds,cache=cache){check(db.ink().compareAndSet(page,2,3)==1)}}.exceptionOrNull()
        assertTrue("A cached batch must still validate the final revision",failure is IllegalStateException)
        assertTrue(read(3).cuts.isEmpty())
    }
    @Test fun explicitAppendReuseSkipsOldPayloadsAcrossRevisionJumpsAndDefaultsToInvalidation()=fixture {db,page,repo->
        val values=listOf(stroke(1,100f),stroke(2,150f));seed(db,page,values)
        val cache=repo.rasterCache();val ordinary=repo.rasterCache();val bounds=CanvasBounds(0.0,0.0,500.0,500.0)
        var decoded=0;repo.readObserver={decoded+=it.entries}
        suspend fun read(cache:InkRasterCache,revision:Long,advance:Boolean=false):List<InkStroke>{
            val rows=mutableListOf<InkStroke>()
            repo.visitVisible(page,revision,bounds,cache=cache,allowRevisionAdvance=advance){rows+=it}
            return rows
        }
        val old=read(cache,1);read(ordinary,1)
        assertTrue(repo.save(CommitInk(id(10),page,1,InkMutation.Add(stroke(3,200f)))) is InkCommitResult.Committed)
        assertTrue(repo.save(CommitInk(id(11),page,2,InkMutation.Add(stroke(4,250f)))) is InkCommitResult.Committed)
        decoded=0;val added=read(cache,3,true)
        assertEquals(2,decoded);assertEquals(2,cache.hits)
        assertEquals(listOf(id(1),id(2),id(3),id(4)),added.map{it.id})
        old.forEachIndexed{i,stroke->assertSame(stroke,added[i])}
        decoded=0;read(ordinary,3);assertEquals("Ordinary callers still invalidate on revision changes",4,decoded)
        assertEquals(0,ordinary.hits)
        assertTrue(runCatching{read(cache,2,true)}.exceptionOrNull() is IllegalStateException)
        assertTrue(cache.retainedBytes in 1..InkRasterCache.MAX_BYTES)
    }
    @Test fun appendPermissionStillRefreshesChangedPayloadVisibilityUndoAndMasks()=fixture {db,page,repo->
        seed(db,page,listOf(stroke(1,100f),stroke(2,150f)))
        val cache=repo.rasterCache();val bounds=CanvasBounds(0.0,0.0,500.0,500.0)
        suspend fun read(revision:Long):List<InkStroke>{
            val rows=mutableListOf<InkStroke>()
            repo.visitVisible(page,revision,bounds,cache=cache,allowRevisionAdvance=true){rows+=it}
            return rows
        }
        val original=read(1)
        val moved=stroke(1,200f)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf<Any>(InkStrokeCodec.encodeStored(moved),moved.id))
        assertTrue(repo.save(CommitInk(id(10),page,1,InkMutation.Add(stroke(3,250f)))) is InkCommitResult.Committed)
        val appended=read(2)
        assertEquals(200f,appended.first().samples.first().x,0f)
        assertNotSame(original.first(),appended.first());assertSame(original[1],appended[1])
        assertTrue(repo.save(CommitInk(id(11),page,2,InkMutation.Visibility(listOf(id(1)),false))) is InkCommitResult.Committed)
        assertEquals(listOf(id(2),id(3)),read(3).map{it.id})
        assertTrue(repo.save(CommitInk(id(12),page,3,InkMutation.Visibility(listOf(id(1)),true))) is InkCommitResult.Committed)
        val restored=read(4);assertEquals(listOf(id(1),id(2),id(3)),restored.map{it.id})
        assertNotSame("Undo receives the new spatial identity",appended.first(),restored.first())
        val cut=InkCut(id(20),5f,listOf(EraserPoint(205f,105f)))
        assertTrue(repo.save(CommitInk(id(13),page,4,InkMutation.Cut(EraseSelection(cut,listOf(id(1)))))) is InkCommitResult.Committed)
        val masked=read(5);assertEquals(cut.id,masked.first().cuts.single().id)
        assertNotSame("A cut epoch change invalidates even unaffected cached strokes",restored[1],masked[1])
        assertEquals(1,db.ink().cutVisibility(cut.id,page,false));assertTrue(read(5).all{it.cuts.isEmpty()})
    }
    @Test fun appendReuseRejectsHeadOrMaskChangesDuringCachedBatches()=fixture {db,page,repo->
        seed(db,page,List(300){stroke(it+1,100f)})
        val cache=repo.rasterCache();val bounds=CanvasBounds(0.0,0.0,500.0,500.0)
        repo.visitVisible(page,1,bounds,cache=cache){}
        assertTrue(repo.save(CommitInk(id(1000),page,1,InkMutation.Add(stroke(301,150f)))) is InkCommitResult.Committed)
        var batches=0
        val failure=runCatching{repo.visitVisible(page,2,bounds,cache=cache,allowRevisionAdvance=true){
            batches++;if(batches==1)check(db.ink().compareAndSet(page,2,3)==1)
        }}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertEquals("INK_WINDOW_REVISION_CHANGED",failure?.message)
        assertEquals("Do not publish a second batch after the writer commits",1,batches)
        val cut=InkCut(id(2000),5f,listOf(EraserPoint(105f,105f)))
        assertTrue(repo.save(CommitInk(id(1001),page,3,InkMutation.Cut(EraseSelection(cut,listOf(id(1)))))) is InkCommitResult.Committed)
        repo.visitVisible(page,4,bounds,cache=cache){}
        assertTrue(repo.save(CommitInk(id(1002),page,4,InkMutation.Add(stroke(302,200f)))) is InkCommitResult.Committed)
        batches=0
        val maskFailure=runCatching{repo.visitVisible(page,5,bounds,cache=cache,allowRevisionAdvance=true){
            batches++;if(batches==1)assertEquals(1,db.ink().cutVisibility(cut.id,page,false))
        }}.exceptionOrNull()
        assertTrue(maskFailure is IllegalStateException);assertEquals("INK_RASTER_EPOCH_CHANGED",maskFailure?.message)
        assertEquals("Append permission cannot combine batches from different mask epochs",1,batches)
    }
    @Test fun decodedCacheRemainsBoundedForManyStrokesAndDenseMasks()=fixture {db,page,repo->
        val values=List(600){stroke(it+1,100f)};seed(db,page,values)
        val cache=repo.rasterCache();val bounds=CanvasBounds(0.0,0.0,500.0,500.0)
        repo.visitVisible(page,1,bounds,cache=cache){}
        assertEquals(512L*(512+2*64),cache.retainedBytes)
        val mask=InkCut(id(2000),1f,List(8192){EraserPoint(100f+it*.001f,100f)})
        val heavy=values.first().withCuts(List(3){InkCut(id(2001+it),mask.radius,mask.points)})
        cache.put(heavy,1)
        assertTrue(cache.retainedBytes<=InkRasterCache.MAX_BYTES)
        assertNull("Oversize masked strokes must not enter the cache",cache.get(heavy.id,1))
    }
    @Test fun sameRevisionMaskChangeDuringCachedVisitRejectsTheWholeRead()=fixture {db,page,repo->
        val values=List(300){stroke(it+1,100f)};seed(db,page,values)
        val cut=InkCut(id(1000),5f,listOf(EraserPoint(105f,105f)))
        assertTrue(repo.save(CommitInk(id(1001),page,1,InkMutation.Cut(EraseSelection(cut,values.map{it.id})))) is InkCommitResult.Committed)
        val cache=repo.rasterCache();val bounds=CanvasBounds(0.0,0.0,500.0,500.0)
        var batches=0
        val failure=runCatching{repo.visitVisible(page,2,bounds,cache=cache){batch->
            batches++;assertTrue(batch.all{it.cuts.single().id==cut.id})
            if(batches==1)assertEquals(1,db.ink().cutVisibility(cut.id,page,false))
        }}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertEquals("INK_RASTER_EPOCH_CHANGED",failure?.message)
        assertEquals("Never deliver a second batch from a different mask epoch",1,batches)
        var count=0
        repo.visitVisible(page,2,bounds,cache=cache){batch->assertTrue(batch.all{it.cuts.isEmpty()});count+=batch.size}
        assertEquals(300,count);assertEquals(2L,repo.revision(page))
    }
}
