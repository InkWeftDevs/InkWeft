// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

class LibraryBackupRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,NoteDatabase)->Unit)=runBlocking{
        val a="backup-source-${id()}.db";val b="backup-target-${id()}.db"
        val source=NoteDatabase.open(context,a);val target=NoteDatabase.open(context,b)
        try{block(source,target)}finally{source.close();target.close();context.deleteDatabase(a);context.deleteDatabase(b)}
    }
    private suspend fun seed(db:NoteDatabase):String {
        val repo=WorkspaceRepository(db);val n=repo.create("备份原笔记",false,PaperStyle.GRID,NotebookCover.WAVE)
        NoteRepository(db).save(SaveNote(id(),n.id,1,n.title,"已保存正文"))
        val page=NotebookPages(db).addAfter(n.id,n.id,id())
        val s=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(10f,80f,0),InkSample(180f,80f,100)))
        val ink=InkRepository(db)
        ink.save(CommitInk(id(),page.id,0,InkMutation.Add(s)))
        ink.save(CommitInk(id(),page.id,1,InkMutation.Cut(EraseSelection(InkCut(id(),12f,listOf(EraserPoint(90f,70f),EraserPoint(90f,90f))),listOf(s.id)))))
        val hidden=InkStroke(id(),InkPen.PEN,0xff223344.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
        ink.save(CommitInk(id(),page.id,2,InkMutation.Add(hidden)))
        ink.save(CommitInk(id(),page.id,3,InkMutation.Visibility(listOf(hidden.id),false)))
        NotebookPages(db).saveSearchText(page.id,4,"有效人工索引")
        NotebookPages(db).select(n.id,page.id)
        repo.organize(n.id,0,"数学","备份,复习",true,false);repo.setPinned(n.id,1,true)
        repo.saveViewport(page.id,CanvasViewport(300.0,500.0,.8))
        return n.id
    }
    private suspend fun inspect(repo:LibraryBackupRepository,snapshot:LibraryBackupRepository.Snapshot)=snapshot.file.inputStream().use{repo.inspect(it)}
    @Test fun roundTripPreservesOriginalIdsHiddenInkMasksHistoryAndMetadata()=fixture{s,t->
        val id=seed(s);val repo=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{file->inspect(repo,file).use{p->
            assertEquals(1,p.notes);assertEquals(2,p.pages);assertEquals(0,t.notes().observeNotes().first().size)
            assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,repo.restore(p))
            assertEquals(s.notes().note(id),t.notes().note(id));assertEquals(s.workspace().get(id),t.workspace().get(id))
            assertEquals(s.pages().list(id),t.pages().list(id));assertEquals(s.notes().revision(id,1),t.notes().revision(id,1))
            val page=s.pages().list(id)[1].id
            val a=s.ink().strokes(page);val b=t.ink().strokes(page);assertEquals(a.map{it.id},b.map{it.id})
            a.zip(b).forEach{(x,y)->assertArrayEquals(x.payload,y.payload);assertEquals(x.visible,y.visible)}
            assertEquals(1,b.count{!it.visible});assertArrayEquals(s.ink().cuts(page).single().payload,t.ink().cuts(page).single().payload)
            assertEquals(s.pages().search(page),t.pages().search(page))
        }}
    }
    @Test fun restoredRecordsRemainAfterRealCloseAndReopen()=runBlocking{
        val a="backup-a-${id()}.db";val b="backup-b-${id()}.db";val s=NoteDatabase.open(context,a);var t=NoteDatabase.open(context,b)
        try{val note=seed(s);val r=LibraryBackupRepository(context,t)
            LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{r.restore(it)}}
            t.close();t=NoteDatabase.open(context,b)
            assertEquals(s.pages().list(note),t.pages().list(note));assertTrue(t.workspace().get(note)!!.pinned)
            assertEquals("已保存正文",t.notes().note(note)?.text)
        }finally{s.close();t.close();context.deleteDatabase(a);context.deleteDatabase(b)}
    }
    @Test fun disjointExistingLibraryIsRetainedAndRepeatedRestoreIsNoOp()=fixture{s,t->
        val note=seed(s);val existing=WorkspaceRepository(t).create("原有独立笔记",true,PaperStyle.DOTS)
        val r=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{p->
            assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,r.restore(p))
            assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,r.restore(p))
            assertEquals(2,t.notes().observeNotes().first().size);assertEquals(existing,NoteRepository(t).read(existing.id));assertNotNull(t.notes().note(note))
        }}
    }
    @Test fun changedIdentityStopsEntireRestoreWithoutOverwritingNewWork()=fixture{s,t->
        val note=seed(s);val r=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{p->r.restore(p)
            NoteRepository(t).save(SaveNote(id(),note,2,"本机新标题","新正文"))
            assertEquals(LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT,r.restore(p))
            assertEquals("新正文",t.notes().note(note)?.text)
        }}
    }
    @Test fun preCommitFailureRollsBackAllLiveTables()=fixture{s,t->
        val note=seed(s);val r=LibraryBackupRepository(context,t){if(it==LibraryBackupRepository.BackupFault.BEFORE_RESTORE_COMMIT)error("synthetic")}
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{p->
            try{r.restore(p);fail()}catch(_:IllegalStateException){}
            assertNull(t.notes().note(note));assertTrue(t.pages().list(note).isEmpty());assertNull(t.notes().revision(note,1))
            assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,LibraryBackupRepository(context,t).restore(p))
        }}
    }
    @Test fun lostAcknowledgementReusesOriginalIdentities()=fixture{s,t->
        seed(s);val r=LibraryBackupRepository(context,t){if(it==LibraryBackupRepository.BackupFault.AFTER_RESTORE_COMMIT)error("synthetic")}
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{p->
            try{r.restore(p);fail()}catch(_:IllegalStateException){}
            assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,LibraryBackupRepository(context,t).restore(p))
            assertEquals(1,t.notes().observeNotes().first().size)
        }}
    }
    @Test fun damagedFooterNeverTouchesLiveLibrary()=fixture{s,t->
        seed(s);LibraryBackupRepository(context,s).snapshot().use{f->val bytes=f.file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt()xor 1).toByte()
            try{LibraryBackupRepository(context,t).inspect(ByteArrayInputStream(bytes));fail()}catch(_:IllegalArgumentException){}
            assertTrue(t.notes().observeNotes().first().isEmpty())
        }
    }
    @Test fun unknownTablesCannotBeSilentlyOmitted()=fixture{s,_->
        seed(s);s.openHelper.writableDatabase.execSQL("CREATE TABLE future_author_data(id TEXT)")
        try{LibraryBackupRepository(context,s).snapshot();fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun orphanHistoricalReferencesPreventExport()=fixture{s,_->
        seed(s);s.openHelper.writableDatabase.execSQL("INSERT INTO note_revisions VALUES(?,1,'孤儿','不能遗漏',1)",arrayOf(id()))
        try{LibraryBackupRepository(context,s).snapshot();fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun trashAndStaleIndexArePreservedButStaleTextIsNotSearchable()=fixture{s,t->
        val note=seed(s);val page=s.pages().list(note)[1].id
        val ink=InkRepository(s);ink.save(CommitInk(id(),page,4,InkMutation.Add(InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.TOUCH,listOf(InkSample(30f,30f,0))))))
        val w=WorkspaceRepository(s).get(note);WorkspaceRepository(s).organize(note,w.revision,w.folder,w.tags,w.favorite,true)
        val r=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{p->assertEquals(1,p.trashed);r.restore(p)}}
        assertNotNull(t.workspace().get(note)?.trashedAt);assertEquals("有效人工索引",t.pages().search(page)?.text)
        assertTrue(t.pages().observeSearch().first().none{it.pageId==page})
    }
    @Test fun streamReadFailureOnlyAffectsIsolatedStage()=fixture{s,t->
        seed(s);LibraryBackupRepository(context,s).snapshot().use{f->val b=f.file.readBytes()
            val input=object:ByteArrayInputStream(b){var n=0;override fun read(bytes:ByteArray,o:Int,l:Int):Int{if(++n>5)throw IOException("synthetic");return super.read(bytes,o,minOf(l,32))}}
            try{LibraryBackupRepository(context,t).inspect(input);fail()}catch(_:IOException){}
            assertTrue(t.notes().observeNotes().first().isEmpty())
        }
    }
    @Test fun emptyBackupDoesNotEraseExistingData()=fixture{s,t->
        val note=seed(t);val r=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{f->inspect(r,f).use{assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,r.restore(it))}}
        assertNotNull(t.notes().note(note))
    }
}
