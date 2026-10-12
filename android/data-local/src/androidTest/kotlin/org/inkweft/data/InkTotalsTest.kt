// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class InkTotalsTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(count:Int=2)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        List(count){InkSample(20f+it,30f,it.toLong(),.5f)},false)
    private fun fixture(block:suspend(Context,NoteDatabase,String,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="ink-totals-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val pages=List(2){WorkspaceRepository(db).create("合成计数$it",false,PaperStyle.BLANK).id}
            InkTotals.install(db.openHelper.writableDatabase)
            pages.forEach{db.ink().insertPage(InkPageRow(it,3))};block(context,db,pages[0],pages[1])
        }finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun add(db:NoteDatabase,page:String,stroke:InkStroke)=db.ink().insertStroke(InkStrokeRow(stroke.id,page,InkStrokeCodec.encodeStored(stroke),stroke.samples.size,true,1))
    private fun assertMatchesRows(db:NoteDatabase,page:String):InkTotals.Counts {
        val sql=db.openHelper.writableDatabase
        val expected=sql.query("SELECT COUNT(*),COALESCE(SUM(pointCount),0),COALESCE(SUM(CASE WHEN typeof(pointCount)='integer' AND pointCount BETWEEN 1 AND ${InkLimits.MAX_POINTS} THEN 0 ELSE 1 END),0) FROM ink_strokes WHERE noteId=?",arrayOf(page)).use{c->
            c.moveToFirst();Triple(c.getInt(0),c.getLong(1),c.getInt(2))
        }
        val cuts=sql.query("SELECT COUNT(*) FROM ink_cuts WHERE noteId=?",arrayOf(page)).use{it.moveToFirst();it.getInt(0)}
        val found=InkTotals.read(sql,page)
        assertEquals(InkTotals.Counts(expected.first,expected.second,cuts,expected.third),found)
        return found
    }
    @Test fun sourceInsertsDeletesMovesAndPointCountChangesMaintainExactRetainedCounts()=fixture{_,db,a,b->
        val sql=db.openHelper.writableDatabase;val first=stroke();val second=stroke(3)
        val mask=InkCut(id(),2f,listOf(EraserPoint(20f,30f)))
        db.withTransaction{add(db,a,first);add(db,a,second);db.ink().insertCut(InkCutRow(mask.id,a,InkCutCodec.encode(mask),first.id,true,2))}
        assertEquals(InkTotals.Counts(2,5,1,0),assertMatchesRows(db,a));assertEquals(InkTotals.Counts(0,0,0,0),assertMatchesRows(db,b))
        db.ink().setVisibility(a,listOf(first.id),false);db.ink().cutVisibility(mask.id,a,false)
        assertEquals(InkTotals.Counts(2,5,1,0),assertMatchesRows(db,a))
        sql.execSQL("UPDATE ink_strokes SET pointCount=-1 WHERE id=?",arrayOf(first.id))
        assertEquals(1,assertMatchesRows(db,a).invalidPointRows)
        sql.execSQL("UPDATE ink_strokes SET pointCount=? WHERE id=?",arrayOf<Any>(InkLimits.MAX_POINTS+1,first.id))
        assertEquals(1,assertMatchesRows(db,a).invalidPointRows)
        sql.execSQL("UPDATE ink_strokes SET pointCount=2 WHERE id=?",arrayOf(first.id))
        db.withTransaction{
            sql.execSQL("UPDATE ink_strokes SET noteId=? WHERE id=?",arrayOf(b,first.id))
            sql.execSQL("UPDATE ink_cuts SET noteId=? WHERE id=?",arrayOf(b,mask.id))
        }
        assertEquals(InkTotals.Counts(1,3,0,0),assertMatchesRows(db,a));assertEquals(InkTotals.Counts(1,2,1,0),assertMatchesRows(db,b))
        sql.execSQL("DELETE FROM ink_cuts WHERE id=?",arrayOf(mask.id));sql.execSQL("DELETE FROM ink_strokes WHERE id=?",arrayOf(first.id))
        assertEquals(InkTotals.Counts(0,0,0,0),assertMatchesRows(db,b))
    }
    @Test fun rollbackAndDerivedTableRebuildPreserveTheSameCounters()=fixture{_,db,a,b->
        val sql=db.openHelper.writableDatabase;val first=stroke(3);add(db,a,first)
        val before=assertMatchesRows(db,a)
        try{db.withTransaction{add(db,a,stroke());assertEquals(2,InkTotals.read(sql,a).strokeCount);throw IOException("rollback")};fail()}catch(expected:IOException){assertEquals("rollback",expected.message)}
        assertEquals(before,assertMatchesRows(db,a))
        db.withTransaction{sql.execSQL("DROP TABLE ${InkTotals.TABLE}");InkTotals.install(sql)}
        assertEquals(before,assertMatchesRows(db,a));assertEquals(InkTotals.Counts(0,0,0,0),assertMatchesRows(db,b))
        sql.execSQL("UPDATE ${InkTotals.TABLE} SET pointCount=99 WHERE pageId=?",arrayOf(a))
        InkTotals.rebuild(sql);assertEquals(before,assertMatchesRows(db,a))
        sql.execSQL("DELETE FROM ink_pages WHERE noteId=?",arrayOf(b));sql.execSQL("DELETE FROM notebook_pages WHERE id=?",arrayOf(b))
        assertEquals(0L,sql.query("SELECT COUNT(*) FROM ${InkTotals.TABLE} WHERE pageId=?",arrayOf(b)).use{it.moveToFirst();it.getLong(0)})
    }
    @Test fun firstBackfillAggregatesOnlyMetadataAndNeverLoadsLargeGeometryBlobs()=fixture{_,db,a,b->
        val sql=db.openHelper.writableDatabase
        db.withTransaction{
            repeat(1200){add(db,if(it%2==0)a else b,stroke())}
            sql.execSQL("UPDATE ink_strokes SET payload=zeroblob(3000000) WHERE id=(SELECT id FROM ink_strokes WHERE noteId=? LIMIT 1)",arrayOf(a))
            sql.execSQL("DROP TABLE ${InkTotals.TABLE}");InkTotals.install(sql)
        }
        assertEquals(InkTotals.Counts(600,1200,0,0),assertMatchesRows(db,a));assertEquals(InkTotals.Counts(600,1200,0,0),assertMatchesRows(db,b))
        val details=sql.query("EXPLAIN QUERY PLAN SELECT strokeCount,pointCount,cutCount,invalidPointRows FROM ${InkTotals.TABLE} WHERE pageId=?",arrayOf(a)).use{c->buildString{while(c.moveToNext())append(c.getString(3))}}
        assertTrue(details,details.contains("SEARCH")&&details.contains("pageId=?"))
    }
    @Test fun archiveExcludesProjectionAndRestoreReconstructsCountersFromAuthorRows()=fixture{context,db,a,b->
        val first=stroke();val second=stroke(3)
        db.withTransaction{add(db,a,first);add(db,b,second)}
        val originalA=assertMatchesRows(db,a);val originalB=assertMatchesRows(db,b)
        assertFalse(LibraryBackupRepository.SCHEMA.any{it.name in InkTotals.tables})
        val name="ink-totals-restored-${id()}.db";val target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val restore=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->
                    assertEquals(originalA,assertMatchesRows(preview.stage,a));assertEquals(originalB,assertMatchesRows(preview.stage,b))
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))
                }
            }
            assertEquals(originalA,assertMatchesRows(target,a));assertEquals(originalB,assertMatchesRows(target,b))
        }finally{target.close();context.deleteDatabase(name)}
    }
}
