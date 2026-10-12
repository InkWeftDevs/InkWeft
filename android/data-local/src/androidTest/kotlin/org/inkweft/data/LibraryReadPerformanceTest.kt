// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Also compiles unchanged at a128bb07. The optional new projection observer is discovered
 * reflectively; timing and correctness use the same fixture/API on both revisions. */
class LibraryReadPerformanceTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun coldSearchConcurrentSavesAndShelfReadScale()=runBlocking{coroutineScope{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="library-read-probe-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val body="Synthetic note body "+"b".repeat(20_000)
            val pages="合成页面索引 "+"a".repeat(1024)
            var targetPage="";var targetBook=""
            db.withTransaction{
                val sql=db.openHelper.writableDatabase
                repeat(500){bookIndex->
                    val book=id();val title="合成笔记 ${bookIndex.toString().padStart(3,'0')}"
                    db.notes().insertNote(NoteRow(book,1,title,body,bookIndex.toLong()))
                    db.notes().insertRevision(NoteRevisionRow(book,1,title,body,bookIndex.toLong()))
                    db.workspace().insert(WorkspaceRow(book))
                    repeat(40){position->
                        val page=if(position==0)book else id()
                        db.pages().insert(NotebookPageRow(page,book,position,false,0))
                        val target=bookIndex==499&&position==39
                        if(target){targetPage=page;targetBook=book}
                        sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only"else pages))
                    }
                }
            }
            val noteRepo=NoteRepository(db)
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK)
            val headerMethod=NoteRepository::class.java.methods.firstOrNull{it.name=="observeNoteHeaders"}
            val headerStart=System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val notes=if(headerMethod==null)noteRepo.observeNotes().first() else (headerMethod.invoke(noteRepo) as Flow<List<Note>>).first()
            val headerMs=(System.nanoTime()-headerStart)/1e6
            val repo=LibrarySearchRepository(db)
            val request=LibrarySearchRequest(1,notes,emptyMap(),"target-only")
            val scanned=AtomicInteger();val entered=CompletableDeferred<Unit>()
            repo.scanObserver={_,count->scanned.set(count);if(count==1)entered.complete(Unit)}
            val coldStart=System.nanoTime()
            val cold=async(Dispatchers.IO){repo.read(request)}
            withTimeout(30_000){entered.await()}
            val saves=async(Dispatchers.IO){buildList{repeat(30){i->
                val start=System.nanoTime()
                val result=noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Synthetic concurrent edit $i"))
                assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
                add((System.nanoTime()-start)/1e6)
            }}}
            val first=cold.await();val coldMs=(System.nanoTime()-coldStart)/1e6;val latencies=saves.await().sorted()
            assertEquals(targetBook,first.items.single().note.id);assertEquals(targetPage,first.items.single().pageId)
            assertEquals(20_000,first.pendingIndex)
            val coldScanned=scanned.get()
            // Deterministic contention probe: pause inside actual source scanning. A writer
            // must be able to commit while an optimized reader pins its old WAL snapshot.
            val pauseEntered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            repo.scanObserver={_,count->if(count==1){pauseEntered.complete(Unit);check(release.await(30,TimeUnit.SECONDS))}}
            val paused=async(Dispatchers.IO){repo.read(request)}
            val completedWhilePaused:Boolean
            try{
                withTimeout(30_000){pauseEntered.await()}
                val saving=async(Dispatchers.IO){noteRepo.save(SaveNote(id(),writer.id,writer.revision,writer.title,"Paused reader edit"))}
                completedWhilePaused=withTimeoutOrNull(750){saving.await();true}?:false
                release.countDown();assertTrue(saving.await() is SaveResult.Committed)
                assertEquals(targetPage,paused.await().items.single().pageId)
            }finally{release.countDown();paused.cancelAndJoin()}
            if(headerMethod!=null)assertTrue("Independent WAL reader must not reserve the Room writer",completedWhilePaused)
            repo.scanObserver=null
            LibraryTextIndex.build(db)
            val warm=buildList{repeat(5){val start=System.nanoTime();val result=repo.read(request)
                add((System.nanoTime()-start)/1e6);assertEquals(targetPage,result.items.single().pageId);assertEquals(0,result.pendingIndex)}}.sorted()
            val observed=HashMap<String,Pair<Int,Int>>()
            val projection: (String,Int,Int)->Unit={kind,rows,chars->observed[kind]=rows to chars}
            LibrarySearchRepository::class.java.methods.firstOrNull{it.name.startsWith("setReadObserver")}?.invoke(repo,projection)
            val shelf=repo.read(request.copy(query="",pageSize=60))
            assertEquals(60,shelf.items.size);assertNotNull(shelf.nextOffset)
            if(headerMethod!=null){
                assertEquals(0,notes.sumOf{it.text.length});assertEquals(0,shelf.active.sumOf{it.text.length})
                assertEquals(61,observed.getValue("previews").first)
                assertTrue(observed.getValue("previews").second<=61*600)
                assertEquals(body,noteRepo.read(targetBook)!!.text)
            }
            val evidence=JSONObject().put("books",500).put("pages",20000).put("noteBodyChars",body.length)
                .put("headerReadMs",headerMs).put("headerReturnedBodyChars",notes.sumOf{it.text.length})
                .put("coldMs",coldMs).put("coldSourceChecks",coldScanned).put("warmMedianMs",warm[2])
                .put("saveCount",latencies.size).put("saveP50Ms",latencies[latencies.size/2])
                .put("saveP95Ms",latencies[(latencies.size*95+99)/100-1]).put("saveMaxMs",latencies.last())
                .put("writerCompletedDuringPausedSearch",completedWhilePaused)
                .put("shelfReturnedPreviewChars",shelf.items.sumOf{it.note.text.length})
                .put("shelfDirectoryBodyChars",shelf.active.sumOf{it.text.length})
                .put("projectionRows",observed["previews"]?.first?:JSONObject.NULL)
                .put("projectionChars",observed["previews"]?.second?:JSONObject.NULL)
                .put("fullBodyOnDemandMatches",noteRepo.read(targetBook)!!.text==body)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","LIBRARY_READ_EVIDENCE $evidence\n")})
        }finally{db.close();context.deleteDatabase(name)}
    }}
}
