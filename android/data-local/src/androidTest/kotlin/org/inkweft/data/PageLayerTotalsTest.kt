// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.core.AnnotationTarget
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class PageLayerTotalsTest {
    private fun id()=UUID.randomUUID().toString()
    private data class Bucket(val page:String,val layer:String,val kind:String)
    private class Fixture(val context:Context,val name:String,var db:NoteDatabase,val pages:List<String>,val secondLayer:String) {
        fun reopen(){db.close();db=NoteDatabase.open(context,name)}
    }
    private fun fixture(block:suspend(Fixture)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="layer-totals-${id()}.db"
        val db=NoteDatabase.open(context,name);var state:Fixture?=null
        try{
            val pages=List(2){WorkspaceRepository(db).create("合成图层计数 $it",false,PaperStyle.BLANK).id}
            val second=id();state=Fixture(context,name,db,pages,second)
            for(page in pages){
                val annotation=BoundAnnotation(InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
                    listOf(InkSample(20f,30f,0,world=true)),true),AnnotationTarget(AnnotationTargetKind.PAGE,page))
                val header=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),UserLayer(second,"第二层")))).add(annotation)
                PageAuthoringRepository(db).storePage(page,page,0,header)
            }
            block(state)
        }finally{(state?.db?:db).close();context.deleteDatabase(name)}
    }
    private fun row(page:String,layer:String?=UserLayers.DEFAULT_ID,kind:String="INK",content:String=id())=
        PageLayerMembershipRow(page,kind,content,layer)
    private fun insert(db:NoteDatabase,row:PageLayerMembershipRow,policy:String="ABORT") {
        require(policy in listOf("ABORT","REPLACE","IGNORE"))
        db.openHelper.writableDatabase.execSQL("INSERT OR $policy INTO page_layer_memberships(pageId,kind,contentId,layerId) VALUES(?,?,?,?)",
            arrayOf(row.pageId,row.kind,row.contentId,row.layerId))
    }
    private fun buckets(db:NoteDatabase,derived:Boolean):Map<Bucket,Long> {
        val query=if(derived)"SELECT pageId,layerId,kind,entries FROM ${PageLayerTotals.TABLE}"
            else "SELECT pageId,layerId,kind,COUNT(*) FROM page_layer_memberships WHERE layerId IS NOT NULL GROUP BY pageId,layerId,kind"
        return db.openHelper.readableDatabase.query(query).use{c->buildMap{
            while(c.moveToNext()){assertTrue("No empty or negative count buckets",c.getLong(3)>0);put(Bucket(c.getString(0),c.getString(1),c.getString(2)),c.getLong(3))}
        }}
    }
    private suspend fun authorRows(f:Fixture)=f.pages.flatMap{f.db.authoring().memberships(it)}
    private suspend fun assertMatches(f:Fixture)=f.db.withTransaction {
        val expected=buckets(f.db,false);assertEquals(expected,buckets(f.db,true))
        for(page in f.pages){
            fun counts(inkOnly:Boolean)=expected.filterKeys{it.page==page&&(!inkOnly||it.kind=="INK")}
                .entries.groupBy{it.key.layer}.mapValues{(_,entries)->entries.sumOf{it.value}}
            val all=counts(false);val ink=counts(true);val sql=f.db.openHelper.readableDatabase
            assertEquals(all,PageLayerTotals.read(sql,page,false));assertEquals(ink,PageLayerTotals.read(sql,page,true))
            val header=PageAuthoringCodec.decodeHeader(checkNotNull(f.db.authoring().get("PAGE",page)).payload)
            val withAnnotations=all.toMutableMap()
            header.layers.memberships.filter{it.content.kind==LayerContentKind.ANNOTATION}.forEach{
                withAnnotations[it.layerId]=(withAnnotations[it.layerId]?:0L)+1L
            }
            val projected=PageAuthoringRepository(f.db).readEditor(AuthoringScope.page(page,page))
            assertEquals(withAnnotations,projected.layerCounts);assertEquals(ink,projected.inkLayerCounts)
            assertTrue("Counter projection must not materialize unrequested membership rows",
                projected.state.layers.memberships.all{it.content.kind==LayerContentKind.ANNOTATION})
        }
    }
    @Test fun insertsMovesKindChangesTombstonesAndDeletesMatchAuthorRows()=fixture{f->
        val db=f.db;val sql=db.openHelper.writableDatabase;val a=f.pages[0];val b=f.pages[1]
        val first=row(a);val second=row(a);val objectRow=row(a,f.secondLayer,"OBJECT");val deleted=row(a,null)
        // Room emits INSERT OR ABORT; two rows share an already-existing derived bucket.
        db.withTransaction{listOf(first,second,objectRow,deleted).forEach{db.authoring().assign(it)}}
        assertMatches(f);assertEquals(2L,PageLayerTotals.read(sql,a,true)[UserLayers.DEFAULT_ID])
        sql.execSQL("UPDATE page_layer_memberships SET layerId=layerId WHERE pageId=?",arrayOf(a));assertMatches(f)
        sql.execSQL("UPDATE page_layer_memberships SET layerId=? WHERE contentId=?",arrayOf(f.secondLayer,first.contentId));assertMatches(f)
        sql.execSQL("UPDATE page_layer_memberships SET kind='OBJECT' WHERE contentId=?",arrayOf(first.contentId));assertMatches(f)
        sql.execSQL("UPDATE page_layer_memberships SET pageId=? WHERE contentId=?",arrayOf(b,first.contentId));assertMatches(f)
        sql.execSQL("UPDATE page_layer_memberships SET layerId=NULL WHERE contentId=?",arrayOf(second.contentId));assertMatches(f)
        sql.execSQL("UPDATE page_layer_memberships SET layerId=? WHERE contentId=?",arrayOf(f.secondLayer,deleted.contentId));assertMatches(f)
        sql.execSQL("DELETE FROM page_layer_memberships WHERE contentId=?",arrayOf(first.contentId));assertMatches(f)
        sql.execSQL("DELETE FROM page_layer_memberships");assertMatches(f)
        assertTrue(buckets(db,true).isEmpty())
    }

    @Test fun abortConflictAndTransactionRollbackLeaveNoCounterPrefix()=fixture{f->
        val db=f.db;val sql=db.openHelper.writableDatabase;val a=f.pages[0];val b=f.pages[1]
        val existing=row(a);db.authoring().assign(existing)
        val fresh=row(a)
        db.withTransaction{
            val prior=row(b,f.secondLayer,"OBJECT");db.authoring().assign(prior)
            val before=authorRows(f)
            try{
                sql.execSQL("INSERT OR ABORT INTO page_layer_memberships VALUES(?,?,?,?),(?,?,?,?)",
                    arrayOf(fresh.pageId,fresh.kind,fresh.contentId,fresh.layerId,existing.pageId,existing.kind,existing.contentId,f.secondLayer))
                fail("Duplicate primary key must abort the entire statement")
            }catch(_:SQLiteConstraintException){}
            assertEquals("ABORT must preserve prior statements but roll back the inserted prefix",before,authorRows(f))
            assertMatches(f)
        }
        val before=authorRows(f);val counts=buckets(db,true)
        try{
            db.withTransaction{
                insert(db,row(a,f.secondLayer,"OBJECT"))
                sql.execSQL("UPDATE page_layer_memberships SET pageId=?,layerId=NULL WHERE contentId=?",arrayOf(b,existing.contentId))
                sql.execSQL("DELETE FROM page_layer_memberships WHERE pageId=? AND kind='OBJECT'",arrayOf(b))
                assertMatches(f);throw IOException("synthetic rollback")
            }
            fail("Expected transaction rollback")
        }catch(expected:IOException){assertEquals("synthetic rollback",expected.message)}
        assertEquals(before,authorRows(f));assertEquals(counts,buckets(db,true));assertMatches(f)
    }

    @Test fun replaceAndIgnorePreserveCountsOnWriterConnection()=fixture{f->
        val db=f.db;val a=f.pages[0];val sql=db.openHelper.writableDatabase
        db.withTransaction{
            // Do not depend on the SQLite build's default: installation establishes the writer contract.
            sql.execSQL("PRAGMA recursive_triggers=OFF");PageLayerTotals.install(sql)
            assertEquals(1,sql.query("PRAGMA recursive_triggers").use{it.moveToFirst();it.getInt(0)})
            val original=row(a);insert(db,original);insert(db,row(a));assertMatches(f)
            insert(db,original,"REPLACE");assertMatches(f)
            insert(db,original.copy(layerId=f.secondLayer),"REPLACE");assertMatches(f)
            insert(db,original.copy(layerId=null),"REPLACE");assertMatches(f)
            insert(db,original,"REPLACE");assertMatches(f)
            val before=authorRows(f);val counts=buckets(db,true)
            insert(db,original.copy(layerId=f.secondLayer),"IGNORE")
            assertEquals(before,authorRows(f));assertEquals(counts,buckets(db,true));assertMatches(f)
            // UPDATE OR REPLACE may delete a different kind with the same content identity.
            insert(db,original.copy(kind="OBJECT",layerId=f.secondLayer));assertMatches(f)
            sql.execSQL("UPDATE OR REPLACE page_layer_memberships SET kind='OBJECT' WHERE pageId=? AND kind='INK' AND contentId=?",
                arrayOf(a,original.contentId));assertMatches(f)
        }
    }

    @Test fun reopenAndMissingDerivedTableRebuildPreserveAuthorRows()=fixture{f->
        val a=f.pages[0];val b=f.pages[1]
        f.db.withTransaction{
            repeat(128){i->insert(f.db,row(if(i%2==0)a else b,if(i%5==0)null else if(i%3==0)f.secondLayer else UserLayers.DEFAULT_ID,
                if(i%4==0)"INK"else"OBJECT"))}
        }
        val before=authorRows(f);val counts=buckets(f.db,true);assertMatches(f)
        f.reopen();assertEquals(before,authorRows(f));assertEquals(counts,buckets(f.db,true));assertMatches(f)
        f.db.withTransaction{
            assertEquals(1,f.db.openHelper.writableDatabase.query("PRAGMA recursive_triggers").use{it.moveToFirst();it.getInt(0)})
            f.db.openHelper.writableDatabase.execSQL("DROP TABLE ${PageLayerTotals.TABLE}")
        }
        // Triggers on the authority table survive DROP of their target; onOpen must rebuild safely.
        f.reopen();assertEquals(before,authorRows(f));assertEquals(counts,buckets(f.db,true));assertMatches(f)
        PageLayerTotals.install(f.db.openHelper.writableDatabase);PageLayerTotals.install(f.db.openHelper.writableDatabase)
        assertEquals(counts,buckets(f.db,true));assertMatches(f)
        val retained=before.first{it.layerId!=null}
        insert(f.db,retained,"REPLACE");assertEquals(before,authorRows(f));assertMatches(f)
    }
}
