// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.ByteArrayInputStream

class LibraryContentRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend (NoteDatabase,String)->Unit)=runBlocking{
        val name="transfer-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("原笔记",false,PaperStyle.RULED,NotebookCover.FOREST).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private fun stroke(world:Boolean=false)=InkStroke(id(),InkPen.PEN,0xff234567.toInt(),4f,InkTool.STYLUS,
        listOf(InkSample(if(world)-70f else 10f,80f,0,world=world),InkSample(180f,80f,100,world=world)),world)
    @Test fun copyRetainsPagesMasksCoverTagsAndValidManualIndexWithNewIdentity()=fixture{db,source->
        val pages=NotebookPages(db);val second=pages.addAfter(source,source,id());val ink=InkRepository(db);val s=stroke()
        ink.save(CommitInk(id(),second.id,0,InkMutation.Add(s)))
        val cut=InkCut(id(),12f,listOf(EraserPoint(90f,70f),EraserPoint(90f,90f)))
        ink.save(CommitInk(id(),second.id,1,InkMutation.Cut(EraseSelection(cut,listOf(s.id)))))
        pages.saveSearchText(second.id,2,"保留的人工索引")
        WorkspaceRepository(db).organize(source,0,"数学","复习",true,false)
        val original=db.ink().strokes(second.id).single().payload
        val n=LibraryContentRepository(db).duplicate(CopyNotebook(id(),source,id()))
        val copied=db.pages().list(n.id);assertEquals(2,copied.size);assertEquals(listOf(0,1),copied.map{it.position})
        assertTrue(copied.none{it.id in listOf(source,second.id)})
        val next=InkSession(ink.read(copied[1].id)).visibleDraft().single()
        assertEquals(s.samples,next.samples);assertNotEquals(s.id,next.id)
        assertEquals(cut.points,next.cuts.single().points);assertNotEquals(cut.id,next.cuts.single().id)
        assertEquals("数学",db.workspace().get(n.id)?.folder);assertEquals("复习",db.workspace().get(n.id)?.tags)
        assertEquals("forest",db.workspace().get(n.id)?.coverKey);assertFalse(db.workspace().get(n.id)!!.favorite)
        assertFalse(db.workspace().get(n.id)!!.pinned);assertEquals("保留的人工索引",db.pages().search(copied[1].id)?.text)
        assertArrayEquals(original,db.ink().strokes(second.id).single().payload)
        ink.save(CommitInk(id(),copied[1].id,1,InkMutation.Visibility(listOf(next.id),false)))
        assertEquals(1,InkSession(ink.read(second.id)).visibleDraft().size)
    }
    @Test fun copyBeforeReceiptFaultLeavesNoPartialBook()=fixture{db,source->
        val c=CopyNotebook(id(),source,id());val before=db.notes().observeNotes().first().size
        try{LibraryContentRepository(db){if(it==LibraryContentFault.BEFORE_RECEIPT)error("synthetic")}.duplicate(c);fail("no fault")}catch(_:IllegalStateException){}
        assertEquals(before,db.notes().observeNotes().first().size);assertNull(db.notes().note(c.destinationId))
        assertNull(db.libraryContent().receipt(c.commandId));assertTrue(db.pages().list(c.destinationId).isEmpty())
    }
    @Test fun copyUnknownThenSourceChangesReplaysOriginalCopy()=fixture{db,source->
        val c=CopyNotebook(id(),source,id())
        try{LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_TRANSACTION)error("synthetic")}.duplicate(c);fail("no fault")}catch(_:IllegalStateException){}
        NotebookPages(db).addAfter(source,source,id())
        val repo=LibraryContentRepository(db);val n=repo.duplicate(c)
        assertEquals(c.destinationId,n.id);assertEquals(1,db.pages().list(n.id).size)
        assertEquals(2,db.notes().observeNotes().first().size)
        assertEquals(n,repo.lookup(c.commandId,"COPY",c.digest()))
    }
    @Test fun copySameIdentityDifferentPayloadIsRejected()=fixture{db,source->
        val c=CopyNotebook(id(),source,id());val repo=LibraryContentRepository(db);repo.duplicate(c)
        try{repo.duplicate(c.copy(destinationId=id()));fail("reused identity accepted")}catch(_:IllegalArgumentException){}
        assertEquals(2,db.notes().observeNotes().first().size)
    }
    @Test fun importUnknownRetryPreservesSameNewIds()=fixture{db,_->
        val page=InkPageFile("导入", "正文", listOf(stroke()))
        val parsed=ContentTransfer.read(ByteArrayInputStream(page.encode()))
        val c=ImportNotebook(id(),id(),parsed.sha256)
        try{LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_TRANSACTION)error("synthetic")}.import(c,parsed);fail("no fault")}catch(_:IllegalStateException){}
        val ids=db.ink().strokes(c.destinationId).map{it.id}
        val n=LibraryContentRepository(db).import(c,parsed)
        assertEquals(c.destinationId,n.id);assertEquals(ids,db.ink().strokes(n.id).map{it.id});assertEquals("正文",n.text)
    }
    @Test fun importBeforeReceiptRollsBackEveryPage()=fixture{db,_->
        val book=NotebookFile("三页","",List(3){InkPageFile("页","",listOf(stroke()))})
        val parsed=ContentTransfer.decode(book.encode());val c=ImportNotebook(id(),id(),parsed.sha256)
        try{LibraryContentRepository(db){if(it==LibraryContentFault.BEFORE_RECEIPT)error("synthetic")}.import(c,parsed);fail("no fault")}catch(_:IllegalStateException){}
        assertNull(db.notes().note(c.destinationId));assertNull(db.libraryContent().receipt(c.commandId))
        assertTrue(db.pages().list(c.destinationId).isEmpty());assertEquals(1,db.notes().observeNotes().first().size)
    }
    @Test fun validBookAboveFourMegabytesImportsThroughCommonReader()=fixture{db,_->
        val paths=List(10){InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,
            List(8192){i->InkSample(20f+i%800,30f+i%1200,i.toLong())})}
        val page=InkPageFile("长页","",paths);val bytes=NotebookFile("大副本","",List(3){page}).encode()
        assertTrue(bytes.size>4_000_000)
        val parsed=ContentTransfer.read(ByteArrayInputStream(bytes))
        val n=LibraryContentRepository(db).import(ImportNotebook(id(),id(),parsed.sha256),parsed)
        assertEquals(3,db.pages().list(n.id).size)
        val restored=InkRepository(db).read(db.pages().list(n.id)[2].id)
        assertEquals(paths[0].samples,restored.strokes[0].stroke.samples)
    }
    @Test fun pinIsIndependentOfFavoriteAndIdempotent()=fixture{db,source->
        val repo=WorkspaceRepository(db)
        assertTrue(repo.setPinned(source,0,true));assertTrue(repo.setPinned(source,0,true))
        val row=repo.get(source);assertTrue(row.pinned);assertFalse(row.favorite);assertEquals(1L,row.revision)
        assertFalse(repo.setPinned(source,0,false));assertTrue(repo.get(source).pinned)
        assertTrue(repo.organize(source,row.revision,"课本","标签",true,false))
        val fresh=repo.get(source);assertTrue(fresh.pinned&&fresh.favorite)
        assertTrue(repo.setPinned(source,fresh.revision,false));assertTrue(repo.get(source).favorite)
    }
    @Test fun trashedSourceCannotBeNewlyCopiedOrPinned()=fixture{db,source->
        val r=WorkspaceRepository(db);r.organize(source,0,"","",false,true)
        try{LibraryContentRepository(db).duplicate(CopyNotebook(id(),source,id()));fail("trash copied")}catch(_:IllegalArgumentException){}
        assertFalse(r.setPinned(source,r.get(source).revision,true));assertEquals(1,db.notes().observeNotes().first().size)
    }
    @Test fun boardExportAndCopyRemainWorldWithEffectiveMasks()=fixture{db,_->
        val n=WorkspaceRepository(db).create("无界",true,PaperStyle.DOTS);val s=stroke(true)
        InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(s)))
        val repo=LibraryContentRepository(db);val e=repo.export(n.id)
        assertEquals("iwpage",e.extension);val decoded=ContentTransfer.decode(e.bytes).content as ContentTransfer.Content.Page
        assertTrue(decoded.value.world);assertEquals(-70f,decoded.value.strokes.single().samples[0].x,0f)
        val copy=repo.duplicate(CopyNotebook(id(),n.id,id()))
        assertTrue(db.pages().list(copy.id).single().world)
    }
    @Test fun exportIsSavedSnapshotUnaffectedByLaterEdits()=fixture{db,source->
        val repo=LibraryContentRepository(db);val before=repo.export(source);val hash=ContentTransfer.hash(before.bytes)
        InkRepository(db).save(CommitInk(id(),source,0,InkMutation.Add(stroke())))
        assertEquals(hash,ContentTransfer.hash(before.bytes))
        val content=ContentTransfer.decode(before.bytes).content as ContentTransfer.Content.Book
        assertTrue(content.value.pages.single().strokes.isEmpty())
        assertEquals(1,(ContentTransfer.decode(repo.export(source).bytes).content as ContentTransfer.Content.Book).value.pages.single().strokes.size)
    }
    @Test fun receiptAndPinSurviveActualDatabaseReopen()=runBlocking{
        val name="library-reopen-${id()}.db";var db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("重开",false,PaperStyle.BLANK);WorkspaceRepository(db).setPinned(n.id,0,true)
            val c=CopyNotebook(id(),n.id,id());val copy=LibraryContentRepository(db).duplicate(c)
            db.close();db=NoteDatabase.open(context,name)
            assertTrue(db.workspace().get(n.id)!!.pinned);assertEquals(copy.id,LibraryContentRepository(db).duplicate(c).id)
            assertEquals(2,db.notes().observeNotes().first().size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun schemaFiveMigratesWithoutChangingOldBytesOrIndex()=runBlocking{
        val name="library-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val text=InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/5.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(text).getJSONObject("database");val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        val book=id();val s=stroke();val payload=InkStrokeCodec.encode(s)
        try{
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val e=entities.getJSONObject(i);sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val ix=e.optJSONArray("indices");for(j in 0 until (ix?.length()?:0))sql.execSQL(checkNotNull(ix).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'旧标题','旧正文',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace VALUES(?,0,1,'课本','数学',1,NULL,500,707,1,3,'forest',?)",arrayOf(book,book))
            sql.execSQL("INSERT INTO notebook_pages VALUES(?,?,0,0,1,500,707,1,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book))
            sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,2,1,1)",arrayOf(s.id,book,payload))
            sql.execSQL("INSERT INTO page_search_text VALUES(?,1,'原索引','MANUAL')",arrayOf(book));sql.version=5
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name)
        try{
            assertEquals("旧正文",db.notes().note(book)?.text);assertFalse(db.workspace().get(book)!!.pinned)
            assertTrue(db.workspace().get(book)!!.favorite);assertArrayEquals(payload,db.ink().strokes(book).single().payload)
            assertEquals("原索引",db.pages().search(book)?.text)
            assertTrue(WorkspaceRepository(db).setPinned(book,3,true))
            assertNotNull(LibraryContentRepository(db).duplicate(CopyNotebook(id(),book,id())))
        }finally{db.close();context.deleteDatabase(name)}
    }
}
