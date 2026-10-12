// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID

class InkEditingSnapshotTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(10f,20f,0,.2f,.3f,.4f),InkSample(30f,40f,50,.8f,.4f,.5f)))
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="editing-snapshot-${id()}.db"
        val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成按需历史",false,PaperStyle.BLANK).id)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun commit(session:InkSession,repo:InkRepository,change:InkMutation?=null){
        change?.let{session.enqueue(it)};val command=checkNotNull(session.nextCommand());val result=repo.save(command)
        assertTrue(result is InkCommitResult.Committed);session.complete(command,result)
        session.refreshWindow(repo.readForEditing(session.page.noteId,session.suspendHistory()))
    }

    @Test fun enteringPageSkipsUnrelatedHiddenInkAndMasksButPreservesTheirBudgets()=fixture{db,page->
        val repo=InkRepository(db);val hidden=stroke();val visible=stroke()
        repo.save(CommitInk(id(),page,0,InkMutation.Add(hidden)))
        repo.save(CommitInk(id(),page,1,InkMutation.Add(visible)))
        val cut=InkCut(id(),2f,listOf(EraserPoint(20f,30f)))
        repo.save(CommitInk(id(),page,2,InkMutation.Cut(EraseSelection(cut,listOf(hidden.id)))))
        repo.save(CommitInk(id(),page,3,InkMutation.Visibility(listOf(hidden.id),false)))
        // Poison only unused geometry in this disposable fixture to prove it is never decoded.
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),hidden.id))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_cuts SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),cut.id))
        val snapshot=repo.readForEditing(page)
        assertEquals(4L,snapshot.revision);assertEquals(listOf(visible.id),snapshot.strokes.map{it.stroke.id})
        assertEquals(1,snapshot.unloaded.strokeCount);assertEquals(2L,snapshot.unloaded.pointCount);assertEquals(1,snapshot.unloaded.cutCount)
        assertTrue(snapshot.unloaded.strokePoints.isEmpty()&&snapshot.unloaded.cutIds.isEmpty())
        assertTrue(runCatching{repo.read(page)}.isFailure)
        val session=InkSession(snapshot);commit(session,repo,InkMutation.Add(stroke()))
        assertEquals(3,session.page.retainedStrokeCount);assertEquals(1,session.page.retainedCutCount)
        val visibleCut=InkCut(id(),2f,listOf(EraserPoint(20f,30f)))
        commit(session,repo,InkMutation.Cut(EraseSelection(visibleCut,listOf(visible.id))))
        assertEquals(visibleCut.id,session.visibleDraft().first{it.id==visible.id}.cuts.single().id)
    }
    @Test fun resumeRetainsNoHiddenGeometryAndPreservesReplacementMaskUndoRedo()=fixture{db,page->
        val repo=InkRepository(db);val unrelated=stroke()
        repo.save(CommitInk(id(),page,0,InkMutation.Add(unrelated)))
        repo.save(CommitInk(id(),page,1,InkMutation.Visibility(listOf(unrelated.id),false)))
        val session=InkSession(repo.readForEditing(page));val original=stroke()
        commit(session,repo,InkMutation.Add(original))
        val moved=InkSelectionEdit.copy(listOf(original),10f,10f)
        commit(session,repo,InkMutation.Replace(listOf(original.id),moved))
        val mask=InkCut(id(),2f,listOf(EraserPoint(30f,40f)))
        commit(session,repo,InkMutation.Cut(EraseSelection(mask,moved.map{it.id})))
        session.requestUndo();commit(session,repo)
        val history=session.suspendHistory()
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),unrelated.id))
        val loaded=repo.readForEditing(page,history)
        assertEquals(setOf(moved.single().id),loaded.strokes.map{it.stroke.id}.toSet())
        assertTrue(loaded.cuts.isEmpty())
        assertEquals(2,loaded.unloaded.strokeCount);assertTrue(loaded.unloaded.strokePoints.isEmpty())
        val resumed=InkSession(loaded);resumed.restoreHistory(history)
        resumed.requestRedo();commit(resumed,repo);assertEquals(mask.id,resumed.visibleDraft().single().cuts.single().id)
        resumed.requestUndo();commit(resumed,repo);assertTrue(resumed.visibleDraft().single().cuts.isEmpty())
        resumed.requestUndo();commit(resumed,repo)
        assertArrayEquals(InkStrokeCodec.encode(original),InkStrokeCodec.encode(resumed.visibleDraft().single()))
    }
    @Test fun changedRevisionDoesNotReadOrRestoreStaleHistoryGeometry()=fixture{db,page->
        val repo=InkRepository(db);val session=InkSession(repo.readForEditing(page));val old=stroke()
        commit(session,repo,InkMutation.Add(old));session.requestUndo();commit(session,repo)
        val history=session.suspendHistory();val current=stroke()
        repo.save(CommitInk(id(),page,session.page.revision,InkMutation.Add(current)))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),old.id))
        val snapshot=repo.readForEditing(page,history)
        assertEquals(listOf(current.id),snapshot.strokes.map{it.stroke.id})
        assertThrows(IllegalArgumentException::class.java){InkSession(snapshot).restoreHistory(history)}
    }
    @Test fun restoringMaskRejectsPerStrokeOverflowWithoutPublishingRevisionOrReceipt()=fixture{db,page->
        val repo=InkRepository(db);val seed=stroke()
        val original=InkStroke(seed.id,seed.pen,seed.color,seed.width,seed.tool,seed.samples,
            cuts=List(InkLimits.MAX_CUTS-1){InkCut(id(),2f,listOf(EraserPoint(20f,30f)))})
        val session=InkSession(repo.readForEditing(page));commit(session,repo,InkMutation.Add(original))
        val old=InkCut(id(),2f,listOf(EraserPoint(20f,30f)))
        commit(session,repo,InkMutation.Cut(EraseSelection(old,listOf(original.id))))
        session.requestUndo();commit(session,repo)
        val current=InkCut(id(),2f,listOf(EraserPoint(25f,35f)))
        commit(session,repo,InkMutation.Cut(EraseSelection(current,listOf(original.id))))
        val rejected=CommitInk(id(),page,session.page.revision,InkMutation.CutVisibility(old.id,true))
        assertEquals(InkCommitResult.Rejected,repo.save(rejected))
        assertEquals(session.page.revision,db.ink().page(page)!!.revision)
        assertNull(db.ink().receipt(rejected.commandId));assertFalse(db.ink().cut(old.id)!!.visible)
        assertEquals(InkLimits.MAX_CUTS,repo.readVisible(page).single().cuts.size)
    }
    @Test fun selectedGeometryQueriesCrossSqliteBindChunksWithoutReordering()=fixture{db,page->
        val strokes=List(1002){stroke()}
        db.withTransaction {
            if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else assertEquals(1,db.ink().compareAndSet(page,0,1))
            strokes.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,true,1))}
        }
        val result=InkRepository(db).readForEditing(page)
        assertEquals(strokes.map{it.id}.sorted(),result.strokes.map{it.stroke.id})
        assertEquals(1002,result.retainedStrokeCount);assertTrue(result.unloaded.strokePoints.isEmpty())
    }
    @Test fun invalidHiddenMetadataAndBrokenMaskReferencesPreventEditingSnapshot()=fixture{db,page->
        val repo=InkRepository(db);val old=stroke()
        repo.save(CommitInk(id(),page,0,InkMutation.Add(old)))
        repo.save(CommitInk(id(),page,1,InkMutation.Visibility(listOf(old.id),false)))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET pointCount=-1 WHERE id=?",arrayOf(old.id))
        assertTrue(runCatching{repo.readForEditing(page)}.isFailure)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET pointCount=2 WHERE id=?",arrayOf(old.id))
        val mask=InkCut(id(),2f,listOf(EraserPoint(20f,30f)))
        db.ink().insertCut(InkCutRow(mask.id,page,InkCutCodec.encode(mask),id(),false,2))
        assertTrue(runCatching{repo.readForEditing(page)}.isFailure)
    }
    @Test fun fixedViewportDecodesConstantBytesAndPointsAcrossHundredfoldStoredHistory()=fixture{db,page->
        val repo=InkRepository(db);val sql=db.openHelper.writableDatabase
        if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))
        val measures=mutableListOf<ReadTiming>();repo.readObserver={measures.add(it)}
        var previous=0;var expectedBytes:Long?=null
        for(total in listOf(1_000,10_000,100_000)){
            db.withTransaction{
                sql.compileStatement("INSERT INTO ink_strokes VALUES(?,?,?,2,?,1)").use{insert->
                    for(index in previous until total){
                        val source=stroke();val stroke=if(index<8)source else InkStroke(source.id,source.pen,source.color,source.width,source.tool,
                            source.samples.map{it.copy(x=it.x+800,y=it.y+1000)})
                        insert.bindString(1,stroke.id);insert.bindString(2,page);insert.bindBlob(3,InkStrokeCodec.encodeStored(stroke));insert.bindLong(4,1);insert.executeInsert()
                    }
                }
            }
            previous=total
            // Prepare once; the following measured read covers the steady state at this scale.
            repo.readForEditing(page,bounds=CanvasBounds(0.0,0.0,50.0,60.0));measures.clear()
            val loaded=repo.readForEditing(page,bounds=CanvasBounds(0.0,0.0,50.0,60.0));val timing=measures.single()
            assertEquals(8,loaded.strokes.size);assertEquals(total,loaded.retainedStrokeCount)
            assertEquals(8L,timing.candidateRows);assertEquals(16L,timing.decodedPoints)
            expectedBytes?.let{assertEquals(it,timing.payloadBytes)};expectedBytes=timing.payloadBytes
            var actual:Pair<String,Array<out Any?>>?=null
            val observing=object:SupportSQLiteDatabase by sql {
                override fun query(query:String,bindArgs:Array<out Any?>):Cursor {
                    actual=query to bindArgs.copyOf();return sql.query(query,bindArgs)
                }
            }
            assertEquals(8,SpatialIndex.ink(observing,page,CanvasBounds(0.0,0.0,50.0,60.0)).size)
            val query=checkNotNull(actual)
            val plan=sql.query("EXPLAIN QUERY PLAN "+query.first,query.second).use{c->buildList{while(c.moveToNext())add(c.getString(3))}}
            assertTrue(plan.toString(),plan.first().contains("SCAN b VIRTUAL TABLE")&&plan.first().contains("B0D1B2D3B4D5"))
            assertTrue(plan.toString(),plan.any{it.contains("SEARCH s")&&it.contains("id=?")})
            assertFalse(plan.toString(),plan.any{it.contains("SEARCH s")&&!it.contains("id=?")})
            val evidence=org.json.JSONObject().put("storedInk",total).put("returnedCandidates",timing.candidateRows)
                .put("decodedPoints",timing.decodedPoints).put("payloadBytes",timing.payloadBytes).put("freezeMs",timing.freezeNanos/1_000_000.0)
                .put("decodeMs",timing.decodeNanos/1_000_000.0).put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("plan",org.json.JSONArray(plan))
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","INK_FIXED_VIEWPORT_EVIDENCE $evidence\n")})
        }
        repo.readObserver=null
    }
}
