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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LibrarySearchRepositoryTest {
    @Test fun classificationUsesTheReadSnapshotAfterCancellableInitialIndexing()=fixture{db->coroutineScope{
        val workspace=WorkspaceRepository(db);val note=workspace.create("scope 原词",false,PaperStyle.BLANK)
        assertTrue(NotebookPages(db).saveSearchText(note.id,0,"scope 原词"))
        val repo=LibrarySearchRepository(db)
        repo.indexObserver={_,_->error("Foreground query must not drain the index")}
            workspace.organize(note.id,0,"","",false,true)
            assertNotNull(workspace.get(note.id).trashedAt)
            val result=withTimeout(5_000){repo.read(LibrarySearchRequest(1,listOf(note),emptyMap(),"scope"))}
            assertTrue(result.items.isEmpty());assertTrue(result.active.isEmpty());assertEquals(1,result.trashed)
            assertEquals(1,result.pendingIndex)
    }}
    @Test fun unicodeShortQueriesAndBoundedPagesKeepOrderAndNavigation()=fixture{db->
        val workspace=WorkspaceRepository(db);val pages=NotebookPages(db);val notes=List(125){workspace.create("笔记 ${it.toString().padStart(3,'0')}",false,PaperStyle.BLANK)}
        for(note in notes)assertTrue(pages.saveSearchText(note.id,0,"中文搜索 Ångström 🚀 %_ OR \"引号\""))
        val repo=LibrarySearchRepository(db)
        for(query in listOf("中","文搜","中文搜索","ångSTRÖm","🚀","%_","OR \"")){
            val seen=mutableListOf<String>();var offset=0;var rounds=0
            do{
                val result=repo.read(LibrarySearchRequest(1,notes,emptyMap(),query,byTitle=true,offset=offset,pageSize=17))
                assertTrue(result.items.size<=17);assertTrue(result.items.all{it.pageId==it.note.id})
                seen+=result.items.map{it.note.id};offset=result.nextOffset?:-1;rounds++
            }while(offset>=0)
            assertEquals(notes.map{it.id},seen);assertEquals(8,rounds)
        }
        assertTrue(repo.read(LibrarySearchRequest(1,notes,emptyMap(),"不存在")).items.isEmpty())
    }
    private fun id()=UUID.randomUUID().toString()
    private fun evidence(value:String){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
        putString("stream","LIBRARY_SEARCH_STAGE $value\n")
    })}
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

    @Test fun pausedSourceReadCancelsOnRapidInputThenClearAndCategoryStayFresh()=fixture{db->coroutineScope{
        val workspace=WorkspaceRepository(db);val pages=NotebookPages(db)
        val first=workspace.create("合成一",false,PaperStyle.BLANK)
        val second=workspace.create("合成二",false,PaperStyle.BLANK)
        assertTrue(workspace.organize(second.id,0,"","目标分类",false,false))
        assertTrue(pages.saveSearchText(first.id,0,"old source"))
        assertTrue(pages.saveSearchText(second.id,0,"target-only"))
        val repo=LibrarySearchRepository(db);val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val paused=AtomicBoolean(false)
        repo.scanObserver={query,_->
            check(Thread.currentThread()!=Looper.getMainLooper().thread)
            if(query=="old"&&paused.compareAndSet(false,true)){
                entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))
            }
        }
        val requests=MutableStateFlow(LibrarySearchRequest(1,listOf(first,second),emptyMap(),"old"))
        val results=Channel<LibrarySearchResult>(Channel.UNLIMITED);val published=mutableListOf<Long>()
        val observer=launch(Dispatchers.Main){repo.observe(requests).collect{published+=it.generation;results.send(it)}}
        try{
            withTimeout(5_000){entered.await()}
            withTimeout(1_000){withContext(Dispatchers.Main){
                for((i,term) in listOf("t","tar","target-only").withIndex())requests.value=requests.value.copy(generation=i+2L,query=term)
                yield();yield()
            }}
            release.countDown()
            suspend fun result(generation:Long)=withTimeout(5_000){var value=results.receive();while(value.generation!=generation)value=results.receive();value}
            val latest=result(4);assertFalse(latest.failed);assertEquals(second.id,latest.items.single().pageId)
            requests.value=requests.value.copy(generation=5,query="",type="board")
            val cleared=result(5);assertFalse(cleared.failed);assertTrue(cleared.items.isEmpty())
            requests.value=requests.value.copy(generation=6,type="all",filter="tag:目标分类")
            val category=result(6);assertFalse(category.failed);assertEquals(second.id,category.items.single().note.id)
            withContext(Dispatchers.Main){assertFalse("The paused old query must never publish",1L in published)}
        }finally{release.countDown();observer.cancelAndJoin();results.close()}
    }}

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
        evidence("fixtureReady books=500 pages=20000")
        val repo=LibrarySearchRepository(db);val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val scanned=ConcurrentHashMap<String,Int>()
        val indexed=AtomicInteger(0)
        val indexingStarted=System.nanoTime()
        val firstStart=System.nanoTime();val first=repo.read(LibrarySearchRequest(0,notes,emptyMap(),"target-only"))
        val firstMs=(System.nanoTime()-firstStart)/1_000_000.0
        assertEquals(expectedPage,first.items.single().pageId);assertEquals(20_000,first.pendingIndex)
        evidence("foregroundComplete elapsedMs=$firstMs pending=${first.pendingIndex}")
        val observeStart=System.nanoTime()
        val indexStep=AtomicReference("not-started")
        repo.indexFirstBatchStepObserver={step->
            indexStep.set(step)
            evidence("indexFirstBatch step=$step observeElapsedMs=${(System.nanoTime()-observeStart)/1_000_000}")
        }
        repo.indexObserver={query,count->
            check(Thread.currentThread()!=Looper.getMainLooper().thread){"Search scanned text on the main thread"}
            indexed.set(count)
            if(count<=128)evidence("indexBatch rows=$count observeElapsedMs=${(System.nanoTime()-observeStart)/1_000_000}")
            if(count==128){entered.complete(Unit);check(release.await(30,TimeUnit.SECONDS))}
            if(query=="target-only"&&count%4096==0)androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","FTS_PROGRESS rows=$count elapsedMs=${(System.nanoTime()-indexingStarted)/1_000_000}\n")
            })
        }
        repo.scanObserver={query,count->scanned.merge(query,count){a,b->maxOf(a,b)}}
        val requests=MutableStateFlow(LibrarySearchRequest(1,notes,emptyMap(),"slow"))
        val results=Channel<LibrarySearchResult>(Channel.UNLIMITED)
        val observer=launch(Dispatchers.Main){repo.observe(requests).collect{results.send(it)}}
        try{
            try{withTimeout(10_000){entered.await()}}catch(failure:Throwable){
                runCatching{evidence("indexGateFailed requiredRows=128 completedRows=${indexed.get()} lastIndexStep=${indexStep.get()} observeElapsedMs=${(System.nanoTime()-observeStart)/1_000_000} slowScanRows=${scanned["slow"]?:0} observerActive=${observer.isActive} failure=${failure.javaClass.simpleName}")}
                throw failure
            }
            // Background indexing is paused after 128 rows; request reads remain on IO.
            withTimeout(1_000){withContext(Dispatchers.Main){
                for((i,term) in listOf("t","tar","target-only").withIndex())requests.value=requests.value.copy(generation=i+2L,query=term)
            }}
            withContext(Dispatchers.Main){yield();yield()}
            release.countDown()
            suspend fun generation(value:Long)=withTimeout(120_000){var result=results.receive();while(result.generation!=value)result=results.receive();result}
            val latest=generation(4)
            assertEquals(4L,latest.generation);assertFalse(latest.failed)
            assertEquals(expectedPage,latest.items.single().pageId)
            withTimeout(120_000){var ready=latest;while(ready.pendingIndex>0){ready=results.receive();if(ready.generation==4L)assertEquals(expectedPage,ready.items.single().pageId)}}
            val warm=mutableListOf<Double>();repeat(5){val start=System.nanoTime();val result=repo.read(requests.value);warm+=(System.nanoTime()-start)/1_000_000.0;assertEquals(0,result.pendingIndex);assertEquals(expectedPage,result.items.single().pageId)}
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","FTS_EVIDENCE pages=20000 firstCompleteMs=$firstMs firstPendingRows=20000 warmMedianMs=${warm.sorted()[2]} elapsedMs=${(System.nanoTime()-indexingStarted)/1_000_000} pendingFallbackComplete=true\n")
            })
            requests.value=requests.value.copy(generation=5,query="",type="board")
            val cleared=generation(5);assertEquals(5L,cleared.generation);assertTrue(cleared.items.isEmpty())
            requests.value=requests.value.copy(generation=6,type="all",filter="tag:偶数",byTitle=true,pageSize=500)
            val category=generation(6);assertEquals(6L,category.generation);assertEquals(250,category.items.size)
            assertTrue(category.items.all{"偶数" in it.workspace.tags});assertEquals(500,category.active.size)
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
