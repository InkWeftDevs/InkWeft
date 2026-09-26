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
import java.io.*
import java.util.UUID

class PageEditingRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String,List<String>)->Unit)=runBlocking{
        val name="page-edit-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("页整理",false,PaperStyle.RULED)
            val b=NotebookPages(db).addAfter(n.id,n.id,id());val c=NotebookPages(db).addAfter(n.id,b.id,id())
            block(db,n.id,listOf(n.id,b.id,c.id))
        }finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun cmd(db:NoteDatabase,book:String,page:String,kind:PageEditKind,where:PageInsertLocation=PageInsertLocation.END,anchor:String?=null,stay:String?=null)=
        EditPage(id(),book,page,kind,InsertPages.orderHash(db.pages().list(book).map{it.id}),db.ink().page(page)?.revision?:0,
            where,anchor,if(kind==PageEditKind.COPY)id()else null,db.pages().get(page)?.trashedAt,stay?:db.pages().list(book).first().id)
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,listOf(InkSample(10f,80f,0),InkSample(180f,80f,100)))
    private suspend fun seed(db:NoteDatabase,page:String):InkStroke {
        val s=stroke();val r=InkRepository(db);r.save(CommitInk(id(),page,0,InkMutation.Add(s)))
        r.save(CommitInk(id(),page,1,InkMutation.Cut(EraseSelection(InkCut(id(),12f,listOf(EraserPoint(90f,70f),EraserPoint(90f,90f))),listOf(s.id)))))
        NotebookPages(db).saveSearchText(page,2,"条件概率");return s
    }
    @Test fun movePreservesInkBytesMasksIdentityAndSearchPage()=fixture{db,b,ids->
        seed(db,ids[1]);val before=InkRepository(db).read(ids[1]);val raw=db.ink().strokes(ids[1]).single().payload
        val r=PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.MOVE,PageInsertLocation.END,stay=ids[1])) as EditPageResult.Applied
        assertEquals(listOf(ids[0],ids[2],ids[1]),db.pages().list(b).map{it.id});assertEquals(ids[1],r.selectedPageId)
        assertArrayEquals(raw,db.ink().strokes(ids[1]).single().payload);assertEquals(before.revision,InkRepository(db).read(ids[1]).revision)
        val hit=db.pages().observeSearch().first().single();assertEquals(ids[1],hit.pageId);assertEquals(2,hit.position)
    }
    @Test fun recycleCurrentPageUsesNextAndKeepsOriginalRecord()=fixture{db,b,ids->
        seed(db,ids[1]);val raw=db.ink().strokes(ids[1]).single().payload
        val r=PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.TRASH,stay=ids[1])) as EditPageResult.Applied
        assertEquals(ids[2],r.selectedPageId);assertEquals(listOf(ids[0],ids[2]),db.pages().list(b).map{it.id})
        assertNotNull(db.pages().get(ids[1])!!.trashedAt);assertArrayEquals(raw,db.ink().strokes(ids[1]).single().payload)
        assertTrue(db.pages().observeSearch().first().none{it.pageId==ids[1]})
    }
    @Test fun restoringToChosenPositionBringsBackSamePageAndValidIndex()=fixture{db,b,ids->
        seed(db,ids[1]);val repo=PageEditingRepository(db);repo.apply(cmd(db,b,ids[1],PageEditKind.TRASH))
        repo.apply(cmd(db,b,ids[1],PageEditKind.RESTORE,PageInsertLocation.START))
        assertEquals(listOf(ids[1],ids[0],ids[2]),db.pages().list(b).map{it.id});assertNull(db.pages().get(ids[1])!!.trashedAt)
        assertEquals(ids[1],db.pages().observeSearch().first().single().pageId)
    }
    @Test fun lastAvailablePageCannotBeRecycled()=fixture{db,b,ids->
        val r=PageEditingRepository(db);r.apply(cmd(db,b,ids[1],PageEditKind.TRASH));r.apply(cmd(db,b,ids[2],PageEditKind.TRASH))
        assertEquals(EditPageResult.LastPage,r.apply(cmd(db,b,ids[0],PageEditKind.TRASH)));assertEquals(1,db.pages().list(b).size)
    }
    @Test fun copyHasIndependentIdsAndPreservesEffectiveCuts()=fixture{db,b,ids->
        val original=seed(db,ids[1]);val r=PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.COPY,PageInsertLocation.AFTER,ids[1])) as EditPageResult.Applied
        val copied=InkSession(InkRepository(db).read(r.resultPageId)).visibleDraft().single()
        assertNotEquals(original.id,copied.id);assertEquals(original.samples,copied.samples);assertEquals(1,copied.cuts.size)
        assertNotEquals(db.ink().cuts(ids[1]).single().id,copied.cuts.single().id)
        assertEquals("条件概率",db.pages().search(r.resultPageId)?.text)
        InkRepository(db).save(CommitInk(id(),r.resultPageId,1,InkMutation.Visibility(listOf(copied.id),false)))
        assertEquals(1,InkSession(InkRepository(db).read(ids[1])).visibleDraft().size)
    }
    @Test fun copyDoesNotReviveHiddenStrokesOrStaleIndex()=fixture{db,b,ids->
        val s=seed(db,ids[1]);InkRepository(db).save(CommitInk(id(),ids[1],2,InkMutation.Visibility(listOf(s.id),false)))
        val out=PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.COPY)) as EditPageResult.Applied
        assertTrue(InkRepository(db).read(out.resultPageId).strokes.isEmpty());assertNull(db.pages().search(out.resultPageId))
    }
    @Test fun staleOrderAndSourceHeadRefuseWithoutChanges()=fixture{db,b,ids->
        val r=PageEditingRepository(db);val c=cmd(db,b,ids[1],PageEditKind.TRASH)
        r.apply(cmd(db,b,ids[2],PageEditKind.MOVE,PageInsertLocation.START))
        assertEquals(EditPageResult.OrderChanged,r.apply(c));assertNull(db.pages().get(ids[1])!!.trashedAt)
        val next=cmd(db,b,ids[1],PageEditKind.TRASH);seed(db,ids[1])
        assertEquals(EditPageResult.SourceChanged,r.apply(next));assertNull(db.pageEdits().receipt(next.commandId))
    }
    @Test fun mutationsAgainstRecycledPageAreRejectedButReadRemainsPossible()=fixture{db,b,ids->
        seed(db,ids[1]);PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.TRASH))
        assertEquals(InkCommitResult.Conflict,InkRepository(db).save(CommitInk(id(),ids[1],2,InkMutation.Add(stroke()))))
        assertEquals(2L,InkRepository(db).read(ids[1]).revision)
        try{NotebookPages(db).saveSearchText(ids[1],2,"ghost");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun beforeReceiptExceptionRollsBackReorderAndCopy()=fixture{db,b,ids->
        val c=cmd(db,b,ids[1],PageEditKind.COPY)
        try{PageEditingRepository(db){if(it==PageEditFault.BEFORE_RECEIPT)error("synthetic")}.apply(c);fail()}catch(_:IllegalStateException){}
        assertEquals(ids,db.pages().list(b).map{it.id});assertNull(db.pages().get(c.newPageId!!));assertNull(db.pageEdits().receipt(c.commandId))
    }
    @Test fun lostReplyReplaysWithoutRepeatingPageCopyOrChangingNewSelection()=fixture{db,b,ids->
        val c=cmd(db,b,ids[1],PageEditKind.COPY)
        try{PageEditingRepository(db){if(it==PageEditFault.AFTER_TRANSACTION)error("synthetic")}.apply(c);fail()}catch(_:IllegalStateException){}
        NotebookPages(db).select(b,ids[2]);val r=PageEditingRepository(db).apply(c) as EditPageResult.Applied
        assertTrue(r.replayed);assertEquals(4,db.pages().list(b).size);assertEquals(ids[2],r.selectedPageId)
    }
    @Test fun sameCommandDifferentPayloadIsNotAccepted()=fixture{db,b,ids->
        val c=cmd(db,b,ids[1],PageEditKind.MOVE);val r=PageEditingRepository(db);r.apply(c)
        assertEquals(EditPageResult.CommandReused,r.apply(c.copy(location=PageInsertLocation.START)))
    }
    @Test fun contentExportAndNotebookCopyExcludeRecycledRootPage()=fixture{db,b,ids->
        seed(db,ids[0]);PageEditingRepository(db).apply(cmd(db,b,ids[0],PageEditKind.TRASH,stay=ids[0]))
        assertEquals(2,NotebookPages(db).exportBook(b).pages.size)
        val copied=LibraryContentRepository(db).duplicate(CopyNotebook(id(),b,id()))
        assertEquals(2,db.pages().list(copied.id).size);assertTrue(db.pages().list(copied.id).all{InkRepository(db).read(it.id).strokes.isEmpty()})
    }
    @Test fun insertionIgnoresRecycledPositionsButRetainsTombstones()=fixture{db,b,ids->
        val repo=PageEditingRepository(db);repo.apply(cmd(db,b,ids[1],PageEditKind.TRASH))
        val new=id();val active=db.pages().list(b)
        val c=InsertPages(id(),b,InsertPages.orderHash(active.map{it.id}),PageInsertLocation.START,null,PaperStyle.GRID,listOf(new),false,ids[0])
        assertTrue(NotebookPages(db).insert(c) is InsertPagesResult.Applied)
        assertEquals(listOf(new,ids[0],ids[2]),db.pages().list(b).map{it.id});assertEquals(1,db.pages().get(ids[1])!!.position)
    }
    @Test fun backupRestoresRecycledPagesAndActionReceipts()=fixture{db,b,ids->
        seed(db,ids[0]);val c=cmd(db,b,ids[0],PageEditKind.TRASH);PageEditingRepository(db).apply(c)
        val name="page-backup-${id()}.db";val target=NoteDatabase.open(context,name)
        try{LibraryBackupRepository(context,db).snapshot().use{f->val r=LibraryBackupRepository(context,target)
            f.file.inputStream().use{r.inspect(it)}.use{p->assertEquals(1,p.recycledPages);r.restore(p)}
            assertNotNull(target.pages().get(ids[0])!!.trashedAt);assertEquals(2,target.pages().list(b).size)
            assertTrue((PageEditingRepository(target).apply(c) as EditPageResult.Applied).replayed)
        }}finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun actionsAndTombstonesSurviveActualDatabaseReopen()=runBlocking{
        val name="page-reopen-${id()}.db";var db=NoteDatabase.open(context,name)
        try{val b=WorkspaceRepository(db).create("reopen",false,PaperStyle.RULED).id;val p=NotebookPages(db).addAfter(b,b,id()).id
            val c=cmd(db,b,p,PageEditKind.TRASH);PageEditingRepository(db).apply(c);db.close();db=NoteDatabase.open(context,name)
            assertNotNull(db.pages().get(p)!!.trashedAt);assertTrue((PageEditingRepository(db).apply(c) as EditPageResult.Applied).replayed)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun worldNotebookAndForeignAnchorsCannotBeEdited()=fixture{db,b,ids->
        val w=WorkspaceRepository(db).create("无界",true,PaperStyle.DOTS).id
        assertEquals(EditPageResult.Unavailable,PageEditingRepository(db).apply(cmd(db,w,w,PageEditKind.MOVE)))
        assertEquals(EditPageResult.Unavailable,PageEditingRepository(db).apply(cmd(db,b,ids[1],PageEditKind.MOVE,PageInsertLocation.AFTER,w)))
    }
    @Test fun schemaSixMigrationPreservesOriginalInkAndPages()=runBlocking{
        val name="page-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val text=InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/6.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(text).getJSONObject("database");val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        val book=id();val s=stroke();val payload=InkStrokeCodec.encode(s)
        try{val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){
            val e=entities.getJSONObject(i);sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            val ix=e.optJSONArray("indices");for(j in 0 until (ix?.length()?:0))sql.execSQL(checkNotNull(ix).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
        }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'原名','原文',1)",arrayOf(book))
            sql.execSQL("INSERT INTO note_revisions VALUES(?,1,'原名','原文',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace VALUES(?,0,1,'','',0,NULL,500,707,0,0,'auto','',0)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_pages VALUES(?,?,0,0,1,500,707,0,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,2,1,1)",arrayOf(s.id,book,payload));sql.version=6
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name)
        try{assertNull(db.pages().get(book)!!.trashedAt);assertArrayEquals(payload,db.ink().strokes(book).single().payload)
            val p=NotebookPages(db).addAfter(book,book,id());assertTrue(PageEditingRepository(db).apply(cmd(db,book,p.id,PageEditKind.TRASH)) is EditPageResult.Applied)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun schemaSixArchiveIsPromotedWithoutChangingOriginalIdentity()=fixture{db,b,ids->
        seed(db,ids[0]);val schema=LibraryBackupRepository.SCHEMA_V6;val sql=db.openHelper.writableDatabase
        val values=schema.map{t->sql.query("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{c->buildList<List<Any?>>{while(c.moveToNext())add(t.columns.mapIndexed{i,col->if(c.isNull(i))null else when(col.kind){'I'->c.getLong(i);'F'->c.getDouble(i);'B'->c.getBlob(i);else->c.getString(i)}})}}}
        val rows=object:LibraryArchive.Rows{override fun count(table:Int)=values[table].size.toLong();override fun visit(table:Int,consume:(List<Any?>)->Unit){values[table].forEach(consume)}}
        val bytes=ByteArrayOutputStream();LibraryArchive.write(bytes,schema,rows,0)
        val name="old-backup-${id()}.db";val target=NoteDatabase.open(context,name)
        try{val repo=LibraryBackupRepository(context,target);repo.inspect(ByteArrayInputStream(bytes.toByteArray())).use{repo.restore(it)}
            assertEquals(ids,target.pages().list(b).map{it.id});assertArrayEquals(db.ink().strokes(b).single().payload,target.ink().strokes(b).single().payload)
        }finally{target.close();context.deleteDatabase(name)}
    }
}
