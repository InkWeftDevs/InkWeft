// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LibraryPagingTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="library-paging-${id()}.db"
        val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun sqlPagingMatchesUnicodePinnedOrderAndLoadsOnlyBoundedPreviews()=fixture{db->
        val body="x".repeat(90_000)+"tail-only-needle"
        val titles=listOf("İstanbul","Ångström","ångström","Σ","σ","ς","𐐀","𐐨","\uE000","中文","Same")
        val rows=List(145){i->NoteRow(id(),1,titles[i%titles.size],body,(i/4).toLong())}
        val metadata=rows.mapIndexed{i,n->WorkspaceRow(n.id,pinned=i%7==0,folder=if(i%3==0)"课程"else"",world=i%2==0)}.associateBy{it.noteId}
        db.withTransaction{rows.forEach{db.notes().insertNote(it);db.workspace().insert(metadata.getValue(it.id))}}
        val repository=NoteRepository(db);val headers=repository.observeNoteHeaders().first()
        assertEquals(rows.size,headers.size);assertTrue(headers.all{it.text.isEmpty()})
        val savedOrder=rows.sortedWith(compareByDescending<NoteRow>{it.updatedAt}.thenBy{it.id})
            .map{Note(it.id,it.revision,it.title,it.text)}
        val changed=savedOrder[2];val fresh=Note(id(),0,"åNGSTRÖM",body)
        val drafts=mapOf(changed.id to NoteDraft(changed,"A draft title",body),fresh.id to NoteDraft(fresh))
        val merged=savedOrder.map{if(it.id==changed.id)it.copy(title="A draft title")else it}+fresh
        val repo=LibrarySearchRepository(db);val projections=mutableListOf<Pair<Int,Int>>()
        repo.readObserver={kind,count,chars->if(kind=="previews")projections+=count to chars}
        var plan=emptyList<String>();repo.queryPlanObserver={plan=it}
        for(byTitle in listOf(false,true))for(filter in listOf("all","folder:课程")){
            val expected=ShelfOrder.arrange(merged.filter{filter=="all"||metadata[it.id]?.folder=="课程"},
                {metadata[it.id]?.pinned==true},{it.title},byTitle).map{it.id}
            val seen=mutableListOf<String>();var offset=0
            do{
                val result=repo.read(LibrarySearchRequest(1,headers,drafts,filter=filter,byTitle=byTitle,offset=offset,pageSize=17))
                assertTrue(result.items.size<=17);assertTrue(result.active.all{it.text.isEmpty()})
                seen+=result.items.map{it.note.id};offset=result.nextOffset?:-1
            }while(offset>=0)
            assertEquals(expected,seen);assertEquals(seen.size,seen.distinct().size)
        }
        assertTrue(projections.all{it.first<=18&&it.second<=18*600})
        assertTrue(plan.any{it.contains("MATERIALIZE page")})
        assertTrue(plan.any{it.contains("SEARCH n USING INDEX")&&it.contains("id=?")})
        val found=repo.read(LibrarySearchRequest(2,headers,emptyMap(),"tail-only-needle",pageSize=500))
        assertEquals(rows.size,found.items.size)
        assertEquals(body,repository.read(rows.first().id)!!.text)
    }
    @Test fun snapshotRetainsOldClassificationAndSourceWhileWriterCommits()=fixture{db->coroutineScope{
        val workspace=WorkspaceRepository(db);val note=workspace.create("Old title",false,PaperStyle.BLANK)
        val pages=NotebookPages(db);assertTrue(pages.saveSearchText(note.id,0,"old-needle"))
        val repo=LibrarySearchRepository(db);val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        repo.scanObserver={_,count->if(count==1){entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))}}
        val request=LibrarySearchRequest(1,listOf(note),emptyMap(),"old-needle")
        val reading=async(Dispatchers.IO){repo.read(request)}
        try{
            withTimeout(5_000){entered.await()}
            withTimeout(5_000){withContext(Dispatchers.IO){
                assertTrue(workspace.organize(note.id,0,"","",false,true))
                assertTrue(NoteRepository(db).save(SaveNote(id(),note.id,1,"New title","new body")) is SaveResult.Committed)
            }}
            release.countDown()
            val before=reading.await();assertEquals(note.id,before.items.single().note.id)
            assertEquals("Old title",before.items.single().note.title);assertEquals(0,before.trashed)
            repo.scanObserver=null
            val after=repo.read(request);assertTrue(after.items.isEmpty());assertEquals(1,after.trashed)
            assertEquals("New title",repo.read(request.copy(query="new body",filter="trash")).items.single().note.title)
        }finally{release.countDown();reading.cancelAndJoin()}
    }}
    @Test fun cancelledSourceReadSkipsDirectoryAssemblyAndNextReadKeepsClassification()=fixture{db->coroutineScope{
        val workspace=WorkspaceRepository(db)
        val note=workspace.create("合成目录标题",false,PaperStyle.BLANK)
        val trashed=workspace.create("合成回收标题",false,PaperStyle.BLANK)
        assertTrue(workspace.organize(note.id,0,"课程","复习\n项目",true,false))
        assertTrue(workspace.organize(trashed.id,0,"废弃","",false,true))
        assertTrue(NotebookPages(db).saveSearchText(note.id,0,"target-only"))
        val repo=LibrarySearchRepository(db);val metadataRows=AtomicInteger()
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        repo.readObserver={kind,rows,_->if(kind=="metadata")metadataRows.set(rows)}
        repo.scanObserver={_,count->if(count==1){entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))}}
        val request=LibrarySearchRequest(1,listOf(note,trashed),emptyMap(),"target-only")
        val reading=async(Dispatchers.IO){repo.read(request)}
        try{
            withTimeout(5_000){entered.await()}
            assertEquals("A source read must not eagerly assemble directory headers",0,metadataRows.get())
            reading.cancel();release.countDown();reading.join()
            assertTrue(reading.isCancelled);assertEquals(0,metadataRows.get())
        }finally{release.countDown();reading.cancelAndJoin();repo.scanObserver=null}
        val complete=repo.read(request)
        assertEquals(note.id,complete.items.single().pageId)
        assertEquals(listOf(note.copy(text="")),complete.active)
        assertEquals(mapOf("课程" to 1),complete.folders)
        assertEquals(listOf("复习","项目"),complete.tags)
        assertEquals(1,complete.favorites);assertEquals(0,complete.unfiled);assertEquals(1,complete.trashed)
        assertEquals(2,metadataRows.get())
    }}
    @Test fun refreshedNoteBodyAndUnicodeBlankFolderUseAuthorState()=fixture{db->
        val workspace=WorkspaceRepository(db);val note=workspace.create("Saved title",false,PaperStyle.BLANK)
        // A restored legacy row may contain Unicode whitespace even though current writes trim it.
        db.workspace().update(workspace.get(note.id).copy(folder="\u2003"))
        val request=LibrarySearchRequest(1,listOf(note),emptyMap(),"",filter="unfiled")
        val repo=LibrarySearchRepository(db)
        assertEquals(listOf(note.id),repo.read(request).items.map{it.note.id})
        val saved=NoteRepository(db).save(SaveNote(id(),note.id,1,"Changed title","far-tail-needle"))
        assertTrue(saved is SaveResult.Committed)
        // Caller still holds the old header. Both title and body must come from Room.
        val found=repo.read(request.copy(query="far-tail-needle"))
        assertEquals("Changed title",found.items.single().note.title)
    }
}
