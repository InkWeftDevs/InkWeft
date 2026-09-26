// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageSelectionPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend (NoteDatabase,String)->Unit)=runBlocking {
        val name="insert-selection-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("停留页测试",false,PaperStyle.RULED);block(db,n.id)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun stayingAfterPrependingKeepsOriginalPageEvenWithNoStoredSelection()=fixture{db,book->
        assertEquals("",db.workspace().get(book)?.selectedPageId)
        val c=InsertPages(id(),book,InsertPages.orderHash(db.pages().list(book).map{it.id}),PageInsertLocation.START,null,PaperStyle.GRID,List(2){id()},false)
        PageInsertionRepository(db).insert(c)
        assertEquals(book,db.workspace().get(book)?.selectedPageId)
        assertEquals(book,db.pages().list(book).last().id)
    }
    @Test fun frozenStayTargetIsAtomicAndReceiptRetryDoesNotMoveReadingPosition()=fixture{db,book->
        val other=NotebookPages(db).addAfter(book,book,id());NotebookPages(db).select(book,book)
        val c=InsertPages(id(),book,InsertPages.orderHash(db.pages().list(book).map{it.id}),PageInsertLocation.START,null,PaperStyle.GRID,listOf(id()),false,other.id)
        val repo=PageInsertionRepository(db);repo.insert(c)
        assertEquals(other.id,db.workspace().get(book)?.selectedPageId)
        NotebookPages(db).select(book,book)
        assertTrue((repo.insert(c) as InsertPagesResult.Applied).replayed)
        assertEquals(book,db.workspace().get(book)?.selectedPageId)
    }
}
