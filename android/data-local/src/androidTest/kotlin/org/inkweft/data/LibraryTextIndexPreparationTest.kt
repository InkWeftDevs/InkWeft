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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LibraryTextIndexPreparationTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="library-index-prepare-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    private class Pause(private val stage:String){
        private val used=AtomicBoolean();private val entered=CompletableDeferred<Unit>()
        private val release=CountDownLatch(1)
        fun step(value:String){if(value==stage&&used.compareAndSet(false,true)){
            entered.complete(Unit);check(release.await(30,TimeUnit.SECONDS)){"Timed out releasing $stage"}
        }}
        suspend fun await()=withTimeout(10_000){entered.await()}
        fun resume(){release.countDown()}
    }
    private suspend fun page(db:NoteDatabase,text:String):Note{
        val note=WorkspaceRepository(db).create("Synthetic index source",false,PaperStyle.BLANK)
        db.pages().putSearch(PageSearchRow(note.id,0,text));return note
    }
    private suspend fun deleteSource(db:NoteDatabase,page:String){db.withTransaction{
        db.openHelper.writableDatabase.execSQL("DELETE FROM page_search_text WHERE pageId=?",arrayOf(page))
    }}
    private fun count(db:NoteDatabase,query:String):Int=db.openHelper.readableDatabase.query(query).use{check(it.moveToFirst());it.getInt(0)}
    private fun pending(db:NoteDatabase)=count(db,"SELECT COUNT(*) FROM library_search_pending")
    private fun key(db:NoteDatabase,kind:Int,target:String):Long?=db.openHelper.readableDatabase.query(
        "SELECT key FROM library_search_docs WHERE kind=? AND target=?",arrayOf<Any>(kind,target)
    ).use{if(it.moveToFirst())it.getLong(0)else null}
    private fun terms(db:NoteDatabase,kind:Int,target:String):String?=db.openHelper.readableDatabase.query(
        "SELECT f.terms FROM library_search_docs d JOIN library_search_fts f ON f.docid=d.key WHERE d.kind=? AND d.target=?",arrayOf<Any>(kind,target)
    ).use{if(it.moveToFirst())it.getString(0)else null}

    /** Pause on both sides of the first guarded commit to prove that stale work neither
     * clears pending nor reports committed rows, then let the same builder retry. */
    private suspend fun staleRetry(db:NoteDatabase,expectedRows:Int=1,change:suspend()->Unit,unchanged:()->Unit)=coroutineScope{
        val prepared=Pause("batch-precomputed");val committed=Pause("batch-committed")
        val indexed=AtomicInteger()
        val work=async(Dispatchers.IO){LibraryTextIndex.build(db,onFirstBatchStep={prepared.step(it);committed.step(it)}){indexed.addAndGet(it)}}
        try{
            prepared.await();change();prepared.resume();committed.await()
            assertEquals(0,indexed.get());assertEquals(expectedRows,pending(db));unchanged()
            committed.resume();withTimeout(10_000){work.await()}
            assertEquals(expectedRows,indexed.get());assertEquals(0,pending(db))
        }finally{
            prepared.resume();committed.resume();withContext(NonCancellable){work.cancelAndJoin()}
        }
    }

    @Test fun authorSaveCommitsWhileTokenizationIsPausedOutsideWriter()=fixture{db->coroutineScope{
        val note=page(db,"Synthetic source");val pause=Pause("first-tokenizing")
        val work=async(Dispatchers.IO){LibraryTextIndex.build(db,onFirstBatchStep={step->
            if(step=="first-tokenizing")assertFalse(db.openHelper.readableDatabase.inTransaction())
            pause.step(step)
        })}
        try{
            pause.await()
            val saved=withTimeout(5_000){NoteRepository(db).save(SaveNote(id(),note.id,note.revision,note.title,"Concurrent author edit"))}
            assertTrue(saved is SaveResult.Committed)
            assertEquals("Concurrent author edit",NoteRepository(db).read(note.id)?.text)
            pause.resume();withTimeout(10_000){work.await()}
            assertEquals(0,pending(db));assertEquals(LibrarySearchTerms.document("","Synthetic source"),terms(db,0,note.id))
        }finally{pause.resume();withContext(NonCancellable){work.cancelAndJoin()}}
    }}

    @Test fun changedPageStaysPendingAndAllStaleBatchRetries()=fixture{db->
        val note=page(db,"Old source")
        staleRetry(db,change={db.pages().putSearch(PageSearchRow(note.id,0,"New authoritative source"))}){
            assertNull(terms(db,0,note.id))
        }
        assertEquals(LibrarySearchTerms.document("","New authoritative source"),terms(db,0,note.id))
    }

    @Test fun deletedSourceIsRemovedOnlyAfterItsFreshMissingSnapshot()=fixture{db->
        val note=page(db,"Old source");LibraryTextIndex.build(db)
        val old=terms(db,0,note.id);db.pages().putSearch(PageSearchRow(note.id,0,"Old source"))
        staleRetry(db,change={deleteSource(db,note.id)}){assertEquals(old,terms(db,0,note.id))}
        assertNull(key(db,0,note.id));assertNull(terms(db,0,note.id))
        assertEquals(0,count(db,"SELECT COUNT(*) FROM library_search_fts"))
    }

    @Test fun preparedDeletionCannotClearRecreatedEmptySource()=fixture{db->
        val note=page(db,"Old source");LibraryTextIndex.build(db)
        val old=terms(db,0,note.id);deleteSource(db,note.id)
        staleRetry(db,change={db.pages().putSearch(PageSearchRow(note.id,0,""))}){assertEquals(old,terms(db,0,note.id))}
        assertNotNull(key(db,0,note.id));assertEquals("",terms(db,0,note.id))
    }

    @Test fun changedCardTitleAndChangedCardBodyEachInvalidatePreparedTerms()=fixture{db->
        val note=WorkspaceRepository(db).create("Synthetic cards",true,PaperStyle.BLANK)
        val title=StudyCardRow(id(),note.id,1,"Old title","Unchanged body")
        val body=StudyCardRow(id(),note.id,1,"Unchanged title","Old body")
        db.withTransaction{db.study().addCard(title);db.study().addCard(body)}
        staleRetry(db,expectedRows=2,change={db.withTransaction{
            db.study().updateCard(title.copy(title="New title"))
            db.study().updateCard(body.copy(body="New body"))
        }}){assertNull(terms(db,1,title.id));assertNull(terms(db,1,body.id))}
        assertEquals(LibrarySearchTerms.document("New title",title.body),terms(db,1,title.id))
        assertEquals(LibrarySearchTerms.document(body.title,"New body"),terms(db,1,body.id))
    }

    @Test fun sameTextDeleteRecreateUsesCurrentDocKeyAfterAnotherBuilder()=fixture{db->coroutineScope{
        val note=page(db,"Same source");LibraryTextIndex.build(db);val oldKey=key(db,0,note.id)
        db.pages().putSearch(PageSearchRow(note.id,0,"Same source"))
        val pause=Pause("batch-precomputed");val indexed=AtomicInteger()
        val work=async(Dispatchers.IO){LibraryTextIndex.build(db,onFirstBatchStep=pause::step){indexed.addAndGet(it)}}
        try{
            pause.await();deleteSource(db,note.id);LibraryTextIndex.build(db);assertNull(key(db,0,note.id))
            db.pages().putSearch(PageSearchRow(note.id,0,"Same source"));LibraryTextIndex.build(db)
            val currentKey=key(db,0,note.id);assertNotNull(currentKey);assertNotEquals(oldKey,currentKey)
            db.pages().putSearch(PageSearchRow(note.id,0,"Same source"))
            pause.resume();withTimeout(10_000){work.await()}
            assertEquals(1,indexed.get());assertEquals(0,pending(db));assertEquals(currentKey,key(db,0,note.id))
            assertEquals(LibrarySearchTerms.document("","Same source"),terms(db,0,note.id))
            assertEquals(1,count(db,"SELECT COUNT(*) FROM library_search_fts"))
        }finally{pause.resume();withContext(NonCancellable){work.cancelAndJoin()}}
    }}

    @Test fun anotherBuilderClearingPendingDoesNotPublishDuplicateProgress()=fixture{db->coroutineScope{
        val note=page(db,"Synthetic source");val pause=Pause("batch-precomputed");val indexed=AtomicInteger()
        val work=async(Dispatchers.IO){LibraryTextIndex.build(db,onFirstBatchStep=pause::step){indexed.addAndGet(it)}}
        try{
            pause.await();LibraryTextIndex.build(db);val currentKey=key(db,0,note.id)
            pause.resume();withTimeout(10_000){work.await()}
            assertEquals(0,indexed.get());assertEquals(0,pending(db));assertEquals(currentKey,key(db,0,note.id))
            assertEquals(LibrarySearchTerms.document("","Synthetic source"),terms(db,0,note.id))
        }finally{pause.resume();withContext(NonCancellable){work.cancelAndJoin()}}
    }}

    private suspend fun cancellationRetainsPending(db:NoteDatabase,stage:String)=coroutineScope{
        val note=page(db,"Old source");LibraryTextIndex.build(db)
        val oldKey=key(db,0,note.id);val oldTerms=terms(db,0,note.id)
        db.pages().putSearch(PageSearchRow(note.id,0,"New source"))
        val pause=Pause(stage);val indexed=AtomicInteger()
        val work=launch(Dispatchers.IO){LibraryTextIndex.build(db,onFirstBatchStep=pause::step){indexed.addAndGet(it)}}
        try{
            pause.await();work.cancel();pause.resume();withTimeout(10_000){work.join()}
            assertTrue(work.isCancelled);assertEquals(0,indexed.get());assertEquals(1,pending(db))
            assertEquals(oldKey,key(db,0,note.id));assertEquals(oldTerms,terms(db,0,note.id))
            LibraryTextIndex.build(db);assertEquals(0,pending(db))
            assertEquals(LibrarySearchTerms.document("","New source"),terms(db,0,note.id))
        }finally{pause.resume();withContext(NonCancellable){work.cancelAndJoin()}}
    }
    @Test fun cancelledPreparedBatchLeavesPendingAndExistingFtsUntouched()=fixture{db->cancellationRetainsPending(db,"batch-precomputed")}
    @Test fun cancelledFtsWriteRollsBackBeforeClearingPending()=fixture{db->cancellationRetainsPending(db,"first-fts-written")}

    @Test fun preparedBatchesStillCommitAtMost64Rows()=fixture{db->
        val note=WorkspaceRepository(db).create("Synthetic batch",false,PaperStyle.BLANK)
        db.withTransaction{repeat(65){i->
            val page="page-${i.toString().padStart(3,'0')}"
            db.pages().insert(NotebookPageRow(page,note.id,i+1,false,0))
            db.pages().putSearch(PageSearchRow(page,0,""))
        }}
        val batches=mutableListOf<Int>();LibraryTextIndex.build(db){batches+=it}
        assertEquals(listOf(64,1),batches);assertEquals(0,pending(db))
    }

    @Test fun oversizedFirstSourceStillProgressesWithoutAddingTheNextSource()=fixture{db->
        val note=WorkspaceRepository(db).create("Synthetic byte budget",false,PaperStyle.BLANK)
        db.withTransaction{
            db.pages().insert(NotebookPageRow("00-large",note.id,1,false,0))
            db.pages().insert(NotebookPageRow("01-small",note.id,2,false,0))
            db.pages().putSearch(PageSearchRow("00-large",0,"a".repeat(500_001)))
            db.pages().putSearch(PageSearchRow("01-small",0,"z"))
        }
        val batches=mutableListOf<Int>();LibraryTextIndex.build(db){batches+=it}
        assertEquals(listOf(1,1),batches);assertEquals(0,pending(db))
        assertEquals(LibrarySearchTerms.document("","a".repeat(3)),terms(db,0,"00-large"))
        assertEquals(LibrarySearchTerms.document("","z"),terms(db,0,"01-small"))
    }
}
