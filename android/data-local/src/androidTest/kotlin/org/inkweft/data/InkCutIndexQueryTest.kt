// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InkCutIndexQueryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(20f,30f,0,.5f),InkSample(21f,31f,1,.5f)),false)
    private fun cut(id:String=id())=InkCut(id,2f,listOf(EraserPoint(20f,30f)))
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="cut-index-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val page=WorkspaceRepository(db).create("合成擦除索引",false,PaperStyle.BLANK).id;db.ink().insertPage(InkPageRow(page,3));block(db,page)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun add(db:NoteDatabase,page:String,stroke:InkStroke)=db.ink().insertStroke(InkStrokeRow(stroke.id,page,InkStrokeCodec.encodeStored(stroke),2,true,1))
    private suspend fun mask(db:NoteDatabase,page:String,cut:InkCut,ids:List<String>)=db.ink().insertCut(InkCutRow(cut.id,page,InkCutCodec.encode(cut),ids.joinToString(","),true,2))
    @Test fun realSelectionUsesStrokeIndexAndLoadsOneCutDespiteThousandsOfUnrelatedCuts()=fixture{db,page->
        val a=stroke();val b=stroke();val selectedCut=cut();val payload=InkCutCodec.encode(selectedCut)
        db.withTransaction{
            add(db,page,a);add(db,page,b)
            repeat(2000){val other=stroke();add(db,page,other);mask(db,page,cut(),listOf(other.id))}
            mask(db,page,selectedCut,listOf(a.id,b.id))
        }
        val sql=db.openHelper.writableDatabase
        var captured:Pair<String,Array<out Any?>>?=null
        var pendingQuery:Pair<String,Array<out Any?>>?=null
        val observing=object:SupportSQLiteDatabase by sql {
            override fun query(query:String,bindArgs:Array<out Any?>):Cursor {
                if("payload" in query&&"ink_cut_targets" in query)captured=query to bindArgs.copyOf()
                if("SELECT c.id,c.strokeIds FROM ink_cut_pending" in query)pendingQuery=query to bindArgs.copyOf()
                return sql.query(query,bindArgs)
            }
        }
        // Duplicate matches for a and b must be deduplicated before fetching the large payload.
        val budget=payload.size+2*80L
        val found=db.withTransaction{InkCutIndex.selected(observing,page,setOf(a.id,b.id),budget)}
        assertEquals(listOf(selectedCut.id),found.map{it.id});assertArrayEquals(payload,found.single().payload)
        assertEquals(setOf(a.id,b.id),found.single().strokeIds.split(',').toSet())
        val query=checkNotNull(captured)
        val plan=sql.query("EXPLAIN QUERY PLAN "+query.first,query.second).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(3))}}
        val targetSearch=plan.indexOfFirst{"SEARCH" in it&&"ink_cut_targets_stroke" in it&&"strokeId" in it}
        val authorSearch=plan.indexOfFirst{"SEARCH c " in it}
        assertTrue(plan.toString(),targetSearch>=0&&authorSearch>targetSearch)
        assertFalse(plan.toString(),plan.any{"CORRELATED" in it||"SCAN c " in it})
        assertTrue(plan.toString(),"id=?" in plan[authorSearch])
        val pending=checkNotNull(pendingQuery)
        val pendingPlan=sql.query("EXPLAIN QUERY PLAN "+pending.first,pending.second).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(3))}}
        val pendingSearch=pendingPlan.indexOfFirst{"SEARCH p " in it&&"ink_cut_pending_page" in it&&"noteId=?" in it}
        assertTrue(pendingPlan.toString(),pendingSearch>=0&&pendingPlan.indexOfFirst{"SEARCH c " in it}>pendingSearch)
        assertFalse(pendingPlan.toString(),pendingPlan.any{"SCAN" in it||"TEMP B-TREE" in it})
        val evidence=org.json.JSONObject().put("kind","cut-index-query").put("unrelatedCuts",2000)
            .put("selectedCuts",found.size).put("workingBytes",budget).put("plan",org.json.JSONArray(plan)).put("pendingPlan",org.json.JSONArray(pendingPlan))
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","INK_CUT_INDEX_EVIDENCE $evidence\n")})
    }
    @Test fun targetUpdatesVisibilityDeleteAndRebuildRemainEquivalentToAuthorRows()=fixture{db,page->
        val a=stroke();val b=stroke();val cut=cut();val sql=db.openHelper.writableDatabase
        db.withTransaction{add(db,page,a);add(db,page,b);mask(db,page,cut,listOf(a.id))}
        suspend fun selected(id:String)=db.withTransaction{InkCutIndex.selected(sql,page,setOf(id),10000).map{it.id}}
        assertEquals(listOf(cut.id),selected(a.id));assertTrue(selected(b.id).isEmpty())
        sql.execSQL("UPDATE ink_cuts SET strokeIds=? WHERE id=?",arrayOf(b.id,cut.id))
        assertTrue(selected(a.id).isEmpty());assertEquals(listOf(cut.id),selected(b.id))
        db.ink().cutVisibility(cut.id,page,false);assertTrue(selected(b.id).isEmpty())
        db.ink().cutVisibility(cut.id,page,true);assertEquals(listOf(cut.id),selected(b.id))
        db.withTransaction{
            InkCutIndex.tables.forEach{sql.execSQL("DROP TABLE $it")}
            InkCutIndex.install(sql)
        }
        assertTrue(selected(a.id).isEmpty());assertEquals(listOf(cut.id),selected(b.id))
        sql.execSQL("DELETE FROM ink_cuts WHERE id=?",arrayOf(cut.id));assertTrue(selected(b.id).isEmpty())
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_targets").use{it.moveToFirst();it.getLong(0)})
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
    }
    @Test fun failedRebuildRetainsPendingRowsAndRollsBackPartialProjection()=fixture{db,page->
        val a=stroke();val valid=cut("00000000-0000-0000-0000-000000000001");val invalid=cut("00000000-0000-0000-0000-000000000002")
        val sql=db.openHelper.writableDatabase
        db.withTransaction{add(db,page,a);mask(db,page,valid,listOf(a.id));mask(db,page,invalid,listOf(id()))}
        assertTrue(runCatching{db.withTransaction{InkCutIndex.prepare(sql,page)}}.isFailure)
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_targets").use{it.moveToFirst();it.getLong(0)})
        assertEquals(2L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
        sql.execSQL("UPDATE ink_cuts SET strokeIds=? WHERE id=?",arrayOf(a.id,invalid.id))
        val repaired=db.withTransaction{InkCutIndex.selected(sql,page,setOf(a.id),10000)}
        assertEquals(setOf(valid.id,invalid.id),repaired.map{it.id}.toSet())
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
    }
    private fun rejectsInvalidatedTarget(change:suspend(NoteDatabase,String,InkStroke)->Unit)=fixture{db,page->
        val a=stroke();val replacement=stroke();val cut=cut();val sql=db.openHelper.writableDatabase
        db.withTransaction{add(db,page,a);add(db,page,replacement);mask(db,page,cut,listOf(a.id))}
        suspend fun selected()=db.withTransaction{InkCutIndex.selected(sql,page,setOf(a.id,replacement.id),10000)}
        assertEquals(listOf(cut.id),selected().map{it.id})
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
        change(db,page,a)
        val failure=runCatching{selected()}.exceptionOrNull()
        assertEquals("INK_CUT_TARGET_MISSING",failure?.message)
        // A failed validation must preserve both pending work and the old membership for retry.
        assertEquals(1L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
        assertEquals(a.id,sql.query("SELECT strokeId FROM ink_cut_targets WHERE cutId=?",arrayOf(cut.id)).use{assertTrue(it.moveToFirst());it.getString(0)})
        sql.execSQL("UPDATE ink_cuts SET strokeIds=? WHERE id=?",arrayOf(replacement.id,cut.id))
        assertEquals(replacement.id,selected().single().strokeIds)
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ink_cut_pending").use{it.moveToFirst();it.getLong(0)})
    }
    @Test fun deletingIndexedTargetRejectsReadUntilCutIsRepaired()=rejectsInvalidatedTarget{db,_,a->
        db.openHelper.writableDatabase.execSQL("DELETE FROM ink_strokes WHERE id=?",arrayOf(a.id))
    }
    @Test fun movingIndexedTargetToAnotherPageRejectsReadUntilCutIsRepaired()=rejectsInvalidatedTarget{db,_,a->
        val other=WorkspaceRepository(db).create("目标移动",false,PaperStyle.BLANK).id
        db.ink().insertPage(InkPageRow(other,3))
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET noteId=? WHERE id=?",arrayOf(other,a.id))
    }
    @Test fun renamingIndexedTargetRejectsReadUntilCutIsRepaired()=rejectsInvalidatedTarget{db,_,a->
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET id=? WHERE id=?",arrayOf(id(),a.id))
    }
    @Test fun cancelledCutBuildKeepsCommittedBatchesAndAllowsAuthorWritesBeforeResuming()=fixture{db,page->coroutineScope{
        val strokes=List(700){stroke()};val sql=db.openHelper.writableDatabase
        db.withTransaction{strokes.forEach{add(db,page,it);mask(db,page,cut(),listOf(it.id))}}
        fun pending()=sql.query("SELECT COUNT(*) FROM ink_cut_pending WHERE noteId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}
        var published=false
        try{InkCutIndex.read(db,page,onBatch={assertEquals(128,it);throw CancellationException("cancel at committed seam")}){published=true};fail()}
        catch(_:CancellationException){}
        assertFalse(published);assertEquals(572,pending())
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        val reading=async(Dispatchers.IO){InkCutIndex.read(db,page,onBatch={
            assertTrue(it in 1..128)
            if(first){first=false;entered.complete(Unit);release.await()}
        }){InkCutIndex.selected(it,page,setOf(strokes.first().id),10_000)}}
        try{
            withTimeout(10_000){entered.await()}
            val added=stroke()
            withTimeout(5_000){assertTrue(InkRepository(db).save(CommitInk(id(),page,3,InkMutation.Add(added))) is InkCommitResult.Committed)}
            assertEquals(444,pending());release.complete(Unit)
            assertEquals(1,withTimeout(10_000){reading.await()}.size);assertEquals(0,pending());assertEquals(4L,InkRepository(db).revision(page))
        }finally{release.complete(Unit);reading.cancelAndJoin()}
    }}
}
