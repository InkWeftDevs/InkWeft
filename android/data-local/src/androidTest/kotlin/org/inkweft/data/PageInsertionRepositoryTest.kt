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

class PageInsertionRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private suspend fun operation(db:NoteDatabase,book:String,where:PageInsertLocation=PageInsertLocation.AFTER,
        anchor:String?=book,count:Int=1,open:Boolean=true,paper:PaperStyle=PaperStyle.GRID):InsertPages =
        InsertPages(id(),book,InsertPages.orderHash(db.pages().list(book).map{it.id}),where,anchor,paper,List(count){id()},open)
    private fun fixture(block:suspend (NoteDatabase,String)->Unit)=runBlocking{
        val name="insert-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("插页测试",false,PaperStyle.RULED);block(db,n.id)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun insertsBatchAtBeginningMiddleAndEndWithoutChangingOriginalBytes()=fixture{db,book->
        val stroke=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,listOf(InkSample(10f,20f,0),InkSample(30f,40f,10)))
        InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke)))
        NotebookPages(db).saveSearchText(book,1,"原页索引")
        val before=db.ink().strokes(book).single().payload
        val start=operation(db,book,PageInsertLocation.START,null,2,false)
        assertTrue(PageInsertionRepository(db).insert(start) is InsertPagesResult.Applied)
        val middle=operation(db,book,PageInsertLocation.BEFORE,book,2,false,paper=PaperStyle.DOTS)
        PageInsertionRepository(db).insert(middle)
        val end=operation(db,book,PageInsertLocation.END,null,2)
        PageInsertionRepository(db).insert(end)
        val rows=db.pages().list(book)
        assertEquals(start.pageIds+middle.pageIds+listOf(book)+end.pageIds,rows.map{it.id})
        assertEquals((0..6).toList(),rows.map{it.position})
        assertArrayEquals(before,db.ink().strokes(book).single().payload)
        assertEquals("原页索引",db.pages().search(book)?.text)
        assertEquals(end.pageIds.first(),db.workspace().get(book)?.selectedPageId)
        assertTrue(rows.filter{it.id in middle.pageIds}.all{it.paper==PaperStyle.DOTS.ordinal})
    }
    @Test fun beforeReceiptFaultRollsBackOrderPagesAndSelection()=fixture{db,book->
        val before=db.pages().list(book);val selected=db.workspace().get(book)?.selectedPageId
        val c=operation(db,book,count=3)
        try{PageInsertionRepository(db){if(it==PageInsertFault.BEFORE_RECEIPT)error("synthetic")}.insert(c);fail("no fault")}catch(_:IllegalStateException){}
        assertEquals(before,db.pages().list(book));assertNull(db.pageInsertions().receipt(c.commandId));assertEquals(selected,db.workspace().get(book)?.selectedPageId)
    }
    @Test fun afterTransactionFaultRetriesOriginalBatchOnly()=fixture{db,book->
        val c=operation(db,book,count=3)
        try{PageInsertionRepository(db){if(it==PageInsertFault.AFTER_TRANSACTION)error("synthetic")}.insert(c);fail("no fault")}catch(_:IllegalStateException){}
        assertEquals(4,db.pages().list(book).size);assertNotNull(db.pageInsertions().receipt(c.commandId))
        val applied=PageInsertionRepository(db).insert(c) as InsertPagesResult.Applied
        assertTrue(applied.replayed);assertEquals(c.pageIds,applied.pageIds);assertEquals(4,db.pages().list(book).size)
    }
    @Test fun repeatedCommandAfterOtherInsertStillReturnsItsOriginalReceipt()=fixture{db,book->
        val first=operation(db,book,count=2);val repo=PageInsertionRepository(db);repo.insert(first)
        repo.insert(operation(db,book));val all=db.pages().list(book)
        assertTrue((repo.insert(first) as InsertPagesResult.Applied).replayed);assertEquals(all,db.pages().list(book))
    }
    @Test fun sameIdDifferentTemplateIsRejected()=fixture{db,book->
        val c=operation(db,book);val repo=PageInsertionRepository(db);repo.insert(c)
        val changed=InsertPages(c.commandId,book,c.expectedOrder,c.location,c.anchorPageId,PaperStyle.DOTS,c.pageIds,c.openInserted)
        assertEquals(InsertPagesResult.CommandReused,repo.insert(changed));assertEquals(2,db.pages().list(book).size)
    }
    @Test fun stalePreviewNeverInsertsAtUnexpectedPosition()=fixture{db,book->
        val a=operation(db,book);val b=operation(db,book);val repo=PageInsertionRepository(db)
        repo.insert(a);assertEquals(InsertPagesResult.OrderChanged,repo.insert(b));assertEquals(2,db.pages().list(book).size)
    }
    @Test fun stayOnCurrentPageDoesNotOverwriteViewOrIndex()=fixture{db,book->
        val p=NotebookPages(db);p.select(book,book);val repo=PageInsertionRepository(db)
        repo.insert(operation(db,book,count=2,open=false));assertEquals(book,db.workspace().get(book)?.selectedPageId)
    }
    @Test fun worldNotebookCannotBecomePagedByInsert()=fixture{db,_->
        val n=WorkspaceRepository(db).create("无界",true,PaperStyle.DOTS);val c=operation(db,n.id)
        assertEquals(InsertPagesResult.Unavailable,PageInsertionRepository(db).insert(c));assertEquals(1,db.pages().list(n.id).size)
    }
    @Test fun capacityRejectsEntireBatch()=fixture{db,book->
        db.withTransaction{for(i in 1..498)db.pages().insert(NotebookPageRow(id(),book,i,false,1))}
        val c=operation(db,book,count=2)
        assertEquals(InsertPagesResult.CapacityReached,PageInsertionRepository(db).insert(c));assertEquals(499,db.pages().list(book).size);assertNull(db.pageInsertions().receipt(c.commandId))
    }
    @Test fun actualDatabaseCloseReopenPreservesReceiptAndOrder()=runBlocking{
        val name="insert-reopen-${id()}.db";var db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("重开",false,PaperStyle.RULED);val c=operation(db,n.id,PageInsertLocation.START,null,2)
            PageInsertionRepository(db).insert(c);val expected=db.pages().list(n.id);db.close();db=NoteDatabase.open(context,name)
            assertEquals(expected,db.pages().list(n.id));assertTrue((PageInsertionRepository(db).insert(c) as InsertPagesResult.Applied).replayed)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun generatedVersion4MigratesWithoutChangingInkOrPageOrder()=runBlocking {
        val name="insert-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val text=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("org.inkweft.data.NoteDatabase/4.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(text).getJSONObject("database")
        val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        val book=id();val payload=InkStrokeCodec.encode(InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,listOf(InkSample(10f,20f,0))))
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val e=entities.getJSONObject(i);sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indices=e.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))sql.execSQL(checkNotNull(indices).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length())sql.execSQL(queries.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'原笔记','正文',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace VALUES(?,0,1,'','',0,NULL,500,707,0,0,'forest',?)",arrayOf(book,book))
            sql.execSQL("INSERT INTO notebook_pages VALUES(?,?,0,0,1,500,707,0,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book))
            sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,1,1,1)",arrayOf(InkStrokeCodec.decode(payload).id,book,payload))
            sql.execSQL("INSERT INTO page_search_text VALUES(?,1,'测试关键词','MANUAL')",arrayOf(book))
            sql.version=4
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name)
        try {
            assertEquals("原笔记",db.notes().note(book)?.title);assertArrayEquals(payload,db.ink().strokes(book).single().payload)
            assertEquals("forest",db.workspace().get(book)?.coverKey);assertEquals("测试关键词",db.pages().search(book)?.text)
            assertEquals(0,db.pages().get(book)?.position)
            val c=operation(db,book,PageInsertLocation.START,null,2)
            assertTrue(PageInsertionRepository(db).insert(c) is InsertPagesResult.Applied)
            assertEquals(book,db.pages().list(book).last().id);assertArrayEquals(payload,db.ink().strokes(book).single().payload)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
