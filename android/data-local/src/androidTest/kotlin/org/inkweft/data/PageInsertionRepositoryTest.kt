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
}
