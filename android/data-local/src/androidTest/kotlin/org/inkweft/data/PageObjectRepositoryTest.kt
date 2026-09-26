package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageObjectRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun text()=PageObject(id(),PageObjectKind.TEXT,text="对象保存与备份")
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking {
        val name="objects-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun unknownCommitCanReplayAndStaleWriterCannotOverwrite()=fixture{db->
        val note=WorkspaceRepository(db).create("对象",false,PaperStyle.BLANK)
        val repo=PageObjectRepository(db);val command=id();val content=listOf(text())
        try{PageObjectRepository(db){error("after commit")}.save(note.id,0,command,content);fail()}catch(_:IllegalStateException){}
        assertEquals(1L,repo.save(note.id,0,command,content));assertEquals(content,repo.read(note.id).objects)
        try{repo.save(note.id,0,id(),emptyList());fail()}catch(_:IllegalArgumentException){}
        try{repo.save(note.id,0,command,emptyList());fail()}catch(_:IllegalArgumentException){}
        assertEquals(content,repo.read(note.id).objects)
    }
    @Test fun duplicateAndContentImportCarryIndependentObjects()=fixture{db->
        val note=WorkspaceRepository(db).create("源",false,PaperStyle.GRID)
        val repo=PageObjectRepository(db);val item=text();repo.save(note.id,0,id(),listOf(item))
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),note.id,id()))
        val copied=repo.read(copy.id).objects.single();assertNotEquals(item.id,copied.id);assertEquals(item.text,copied.text)
        repo.save(copy.id,1,id(),listOf(copied.copy(text="修改副本")))
        assertEquals(item,repo.read(note.id).objects.single())
        val exported=NotebookPages(db).exportBook(note.id);val imported=NotebookPages(db).importBook(NotebookFile.decode(exported.encode()))
        assertEquals(item.text,repo.read(imported.id).objects.single().text)
    }
    @Test fun backupRestoresObjectsAndReceipts()=fixture{db->
        val note=WorkspaceRepository(db).create("备份",false,PaperStyle.BLANK);val content=listOf(text(),PageObject(id(),PageObjectKind.TAPE,revealed=true))
        val command=id();PageObjectRepository(db).save(note.id,0,command,content)
        val name="restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{s->val r=LibraryBackupRepository(context,target);s.file.inputStream().use{r.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,r.restore(it))}}
            target.close();target=NoteDatabase.open(context,name)
            assertEquals(content,PageObjectRepository(target).read(note.id).objects)
            assertEquals(1L,PageObjectRepository(target).save(note.id,0,command,content))
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun invalidBoundsAndRecycledPageCannotBeWritten()=fixture{db->
        val note=WorkspaceRepository(db).create("边界",false,PaperStyle.BLANK);val repo=PageObjectRepository(db)
        try{repo.save(note.id,0,id(),listOf(text().copy(x=900f)));fail()}catch(_:IllegalArgumentException){}
        assertTrue(repo.read(note.id).objects.isEmpty())
        db.pages().arrange(note.id,0,123L)
        try{repo.save(note.id,0,id(),listOf(text()));fail()}catch(_:IllegalArgumentException){}
    }
}
