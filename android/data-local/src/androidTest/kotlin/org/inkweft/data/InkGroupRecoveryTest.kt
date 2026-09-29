package org.inkweft.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class InkGroupRecoveryTest{
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PENCIL,0xff345678.toInt(),4f,InkTool.STYLUS,List(300){InkSample(300f+it%50,1300f+it*6,it*10L,.6f,.3f,world=true)},true,appearance=StrokeAppearance(BrushRecipe(),45,0f,0f))
    private fun fixture(block:suspend(NoteDatabase,InkRepository,String,List<String>)->Unit)=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val root=File(context.cacheDir,"recovery-${id()}").apply{mkdirs()};val db=NoteDatabase.open(context,File(root,"test.db").absolutePath);db.checkpointRoot=File(root,"checkpoints")
        try{val book=WorkspaceRepository(db).create("跨页恢复合成资料",false,PaperStyle.BLANK).id;val second=NotebookPages(db).addAfter(book,book,id()).id;val third=NotebookPages(db).addAfter(book,second,id()).id;block(db,InkRepository(db),book,listOf(book,second,third))}
        finally{db.close();require(root.canonicalFile.parentFile==context.cacheDir.canonicalFile);root.deleteRecursively()}
    }
    @Test fun cancelledBeforePrefixAndSealedBeforeLatePrefixNeverRevive()=fixture{_,repo,book,pages->
        val raw=stroke();val group=repo.captureGroup(book,pages,0,raw)
        repo.cancelGroup(raw.id);assertFalse(repo.checkpointGroup(group));assertTrue(repo.pendingGroups(book).isEmpty());assertFalse(repo.hasUnsealedInput())
        val other=repo.captureGroup(book,pages,0,stroke());repo.checkpointGroup(other);assertTrue(repo.hasUnsealedInput())
        val result=repo.sealGroup(other);assertEquals(3,result.results.size);assertFalse(repo.hasUnsealedInput());repo.acknowledgeGroup(other.stroke.id)
        assertFalse(repo.checkpointGroup(other));assertTrue(repo.pendingGroups(book).isEmpty())
    }
    @Test fun frozenPagesSurviveReorderingAndConflictsDoNotOverwrite()=fixture{db,repo,book,pages->
        val group=repo.captureGroup(book,pages,0,stroke());repo.checkpointGroup(group)
        db.pages().arrange(pages[1],2,null);db.pages().arrange(pages[2],1,null)
        val result=repo.sealGroup(group);assertEquals(pages,result.group.pages);assertEquals(3,result.results.size)
        val sessions=group.commands().map{c->InkSession(repo.read(c.noteId)).apply{rememberRecoveredAddition(c.strokeIds)}}
        val undo=sessions.map{it.requestUndo();it.nextCommand()!!};val outcomes=repo.saveGroup(undo);sessions.zip(undo.zip(outcomes)).forEach{(s,p)->s.complete(p.first,p.second)}
        assertTrue(pages.all{InkSession(repo.read(it)).visibleDraft().isEmpty()})
        val pending=repo.captureGroup(book,pages,0,stroke());repo.checkpointGroup(pending)
        db.pages().arrange(pages[1],2,1000L)
        try{repo.sealGroup(pending);fail("Recycled page revived")}catch(e:IllegalArgumentException){assertEquals("GROUP_PAGE_UNAVAILABLE",e.message)}
        assertEquals(1000L,db.pages().get(pages[1])!!.trashedAt);assertEquals(1,repo.read(book).strokes.size)
        assertTrue(repo.pendingGroups(book).any{it.stroke.id==pending.stroke.id});repo.cancelGroup(pending.stroke.id)
        db.pages().arrange(pages[1],2,null)
        val changed=repo.captureGroup(book,pages,0,stroke());repo.checkpointGroup(changed)
        val page=repo.read(book);repo.save(CommitInk(id(),book,page.revision,InkMutation.Add(InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(10f,10f,0))))))
        try{repo.sealGroup(changed);fail("New author change overwritten")}catch(e:IllegalArgumentException){assertEquals("GROUP_REVISION_CONFLICT",e.message)}
        assertEquals(2,repo.read(book).strokes.size);assertEquals(1,repo.read(pages[1]).strokes.size)
    }
    @Test fun transactionFaultsAndLostMarkerKeepOneLogicalReceipt()=fixture{db,repo,book,pages->
        for(cut in listOf("before-first-fragment","after-fragments","before-group-commit","after-group-commit","after-group-marker")){
            val group=repo.captureGroup(book,pages,0,stroke());repo.checkpointGroup(group);val before=repo.read(book).revision
            repo.groupFaultForTest={if(it==cut)error("injected")}
            try{repo.sealGroup(group);fail("Cut not reached")}catch(_:IllegalStateException){}
            repo.groupFaultForTest={};val committed=cut in listOf("after-group-commit","after-group-marker")
            assertEquals(before+if(committed)1 else 0,repo.read(book).revision)
            val retry=repo.sealGroup(repo.pendingGroups(book).single{it.stroke.id==group.stroke.id});assertEquals(committed,retry.replayed)
            assertTrue(repo.sealGroup(group).replayed);assertEquals(before+1,repo.read(book).revision);repo.acknowledgeGroup(group.stroke.id)
        }
        LibraryBackupRepository(InstrumentationRegistry.getInstrumentation().targetContext,db).snapshot().use{snapshot->
            snapshot.file.inputStream().use{LibraryBackupRepository(InstrumentationRegistry.getInstrumentation().targetContext,db).inspect(it)}.use{assertEquals(1,it.notes)}
        }
    }
}
