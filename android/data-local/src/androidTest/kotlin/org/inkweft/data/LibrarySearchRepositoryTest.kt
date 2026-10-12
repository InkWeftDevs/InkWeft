// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Looper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LibrarySearchRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="library-search-${id()}.db"
        val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun card(db:NoteDatabase,book:String,title:String,body:String,trashed:Boolean=false):StudyCardRow {
        val row=StudyCardRow(id(),book,1,title,body,if(trashed)1L else null)
        db.withTransaction{db.study().addCard(row);db.study().revision(StudyCardRevisionRow(row.id,1,title,body,row.trashedAt))}
        return row
    }

    @Test fun draftUnicodeFiltersPinsAndPageBeforeCardNavigationStayIntact()=fixture{db->
        val workspace=WorkspaceRepository(db);val pages=NotebookPages(db);val repo=LibrarySearchRepository(db)
        val draft=workspace.create("已保存标题",false,PaperStyle.BLANK)
        val page=workspace.create("页面命中",false,PaperStyle.BLANK)
        val summary=workspace.create("卡片命中",true,PaperStyle.BLANK)
        val trashed=workspace.create("回收笔记",false,PaperStyle.BLANK)
        workspace.organize(page.id,0,"课程","复习",true,false);workspace.setPinned(page.id,1,true)
        workspace.organize(trashed.id,0,"","",false,true)
        val later=pages.addAfter(page.id,page.id,id())
        assertTrue(pages.saveSearchText(later.id,0,"åNGSTRÖM 后页"))
        assertTrue(pages.saveSearchText(page.id,0,"Ångström 首页"))
        card(db,page.id,"Ångström 同时命中卡片","")
        card(db,summary.id,"摘要","Ångström 正文")
        card(db,summary.id,"已回收独有词","",true)
        val fresh=Note(id(),0,"未保存草稿","Ångström 新正文")
        val drafts=mapOf(draft.id to NoteDraft(draft,"草稿标题","Ångström 草稿正文"),fresh.id to NoteDraft(fresh))
        val request=LibrarySearchRequest(1,listOf(draft,page,summary,trashed),drafts,"ångSTRÖm",byTitle=true)
        val found=repo.read(request)
        assertEquals(4,found.items.size);assertEquals(page.id,found.items.first().note.id)
        assertEquals(page.id,found.items.first().pageId);assertTrue(found.items.first().cardMatch)
        val cardHit=found.items.single{it.note.id==summary.id};assertNull(cardHit.pageId);assertTrue(cardHit.cardMatch)
        assertEquals("草稿标题",found.items.single{it.note.id==draft.id}.note.title)
        assertEquals(1,found.favorites);assertEquals(1,found.trashed);assertEquals(mapOf("课程" to 1),found.folders)
        assertEquals(listOf("复习"),found.tags)
        assertTrue(repo.read(request.copy(query="已保存标题")).items.isEmpty())
        assertTrue(repo.read(request.copy(query="已回收独有词")).items.isEmpty())
        assertEquals(listOf(summary.id),repo.read(request.copy(type="board")).items.map{it.note.id})
        for(filter in listOf("favorite","folder:课程","tag:复习"))
            assertEquals(listOf(page.id),repo.read(request.copy(filter=filter)).items.map{it.note.id})
        assertEquals(listOf(trashed.id),repo.read(request.copy(query="",filter="trash")).items.map{it.note.id})
        assertTrue(repo.read(request.copy(query="",filter="unfiled")).items.none{it.note.id==page.id})
    }

    @Test fun twentyThousandPageRapidInputClearAndCategoryCancelOldGenerationOffMain()=fixture{db->coroutineScope{
        val notes=ArrayList<Note>();var expectedPage=""
        db.withTransaction{
            val sql=db.openHelper.writableDatabase
            val body="合成页面索引 "+"a".repeat(1024)
            repeat(500){bookIndex->
                val book=id();notes+=Note(book,1,"合成笔记 ${bookIndex.toString().padStart(3,'0')}","")
                db.notes().insertNote(NoteRow(book,1,notes.last().title,"",bookIndex.toLong()))
                db.notes().insertRevision(NoteRevisionRow(book,1,notes.last().title,"",bookIndex.toLong()))
                db.workspace().insert(WorkspaceRow(book,tags=if(bookIndex%2==0)"偶数"else"奇数"))
                repeat(40){position->
                    val page=if(position==0)book else id()
                    db.pages().insert(NotebookPageRow(page,book,position,false,0))
                    val target=bookIndex==499&&position==39;if(target)expectedPage=page
                    sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only"else body))
                }
            }
        }
        val repo=LibrarySearchRepository(db);val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val scanned=ConcurrentHashMap<String,Int>()
        repo.scanObserver={query,count->
            check(Thread.currentThread()!=Looper.getMainLooper().thread){"Search scanned text on the main thread"}
            scanned.merge(query,count){a,b->maxOf(a,b)}
            if(query=="slow"&&count==100){entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))}
        }
        val requests=MutableStateFlow(LibrarySearchRequest(1,notes,emptyMap(),"slow"))
        val results=Channel<LibrarySearchResult>(Channel.UNLIMITED)
        val observer=launch(Dispatchers.Main){repo.observe(requests).collect{results.send(it)}}
        try{
            withTimeout(10_000){entered.await()}
            // The actual scan is paused on IO. Main remains runnable and newer requests cancel it.
            withTimeout(1_000){withContext(Dispatchers.Main){
                for((i,term) in listOf("t","tar","target-only").withIndex())requests.value=requests.value.copy(generation=i+2L,query=term)
            }}
            withContext(Dispatchers.Main){yield();yield()}
            release.countDown()
            val latest=withTimeout(15_000){results.receive()}
            assertEquals(4L,latest.generation);assertFalse(latest.failed)
            assertEquals(expectedPage,latest.items.single().pageId);assertEquals(20_000,scanned["target-only"])
            requests.value=requests.value.copy(generation=5,query="",type="board")
            val cleared=withTimeout(5_000){results.receive()};assertEquals(5L,cleared.generation);assertTrue(cleared.items.isEmpty())
            requests.value=requests.value.copy(generation=6,type="all",filter="tag:偶数",byTitle=true)
            val category=withTimeout(5_000){results.receive()};assertEquals(6L,category.generation);assertEquals(250,category.items.size)
            assertTrue(category.items.all{"偶数" in it.workspace.tags});assertEquals(500,category.active.size)
            assertEquals(100,scanned["slow"])
            println("LIBRARY_SEARCH books=500 pages=20000 cancelledScanRows=${scanned["slow"]} completedScanRows=${scanned["target-only"]} oldGenerationPublished=false mainResponsive=true clearAndCategory=true")
        }finally{release.countDown();observer.cancelAndJoin();results.close()}
    }}

    @Test fun deletedOrStalePageAndTrashedCardCannotReviveSearchHits()=fixture{db->coroutineScope{
        val workspace=WorkspaceRepository(db);val pages=NotebookPages(db);val repo=LibrarySearchRepository(db)
        val note=workspace.create("检索来源",false,PaperStyle.BLANK)
        val page=pages.addAfter(note.id,note.id,id())
        assertTrue(pages.saveSearchText(page.id,0,"source-only"))
        val request=MutableStateFlow(LibrarySearchRequest(1,listOf(note),emptyMap(),"source-only"))
        val results=Channel<LibrarySearchResult>(Channel.UNLIMITED)
        val observer=launch{repo.observe(request).collect{results.send(it)}}
        try{
            assertEquals(page.id,withTimeout(5_000){results.receive()}.items.single().pageId)
            val command=EditPage(id(),note.id,page.id,PageEditKind.TRASH,InsertPages.orderHash(pages.activePages(note.id).map{it.id}),0,
                PageInsertLocation.END,null,null,null,note.id)
            assertTrue(pages.edit(command) is EditPageResult.Applied)
            withTimeout(5_000){while(results.receive().items.isNotEmpty())Unit}
            assertTrue(repo.read(request.value).items.isEmpty())
            // Rewriting an old index cannot make a recycled page searchable again.
            db.pages().putSearch(PageSearchRow(page.id,0,"source-only"))
            assertTrue(repo.read(request.value).items.isEmpty())
            assertTrue(pages.saveSearchText(note.id,0,"source-only"))
            assertEquals(note.id,repo.read(request.value).items.single().pageId)
            db.openHelper.writableDatabase.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(note.id))
            assertTrue(repo.read(request.value).items.isEmpty())
            val card=card(db,note.id,"source-only","")
            assertTrue(repo.read(request.value).items.single().cardMatch)
            db.study().updateCard(card.copy(trashedAt=1L))
            assertTrue(repo.read(request.value).items.isEmpty())
        }finally{observer.cancelAndJoin();results.close()}
    }}
}
