package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NoteFirstRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{
        val name="note-first-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("浮窗验收",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun templateCaptureAtomicReplayUndoAndArchive()=fixture{db,book->
        val repo=StudyRepository(db);val knowledge=KnowledgeRepository(db);val map=id()
        val t=MapTemplates.builtins[3];val definition=MapTemplates.instantiate(t,"章节")
        knowledge.submit(KnowledgeCommand(id(),book,map,0,definition))
        knowledge.submit(KnowledgeCommand(id(),book,id(),0,t))
        assertTrue(db.study().cards(book).isEmpty())
        val parent=definition.structures.first().id
        val stroke=InkStroke(id(),InkPen.PEN,0xff3366aa.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(150f,130f,50)))
        InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke)))
        val source=StudySourceDraft(book,1,stroke.bounds(),listOf(stroke.id))
        val c=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="来源",parentId=parent,source=source,mapId=map,expectedGraph=repo.readGraph(book,map).graphFingerprint)
        try{StudyRepository(db){if(it==StudyFault.BEFORE_RECEIPT)error("fault")}.submit(c);fail()}catch(_:IllegalStateException){}
        assertTrue(db.study().cards(book).isEmpty());assertNull(db.study().source(c.cardId!!));assertNull(db.study().receipt(c.id))
        assertTrue(StudyRepository(db){if(it==StudyFault.AFTER_COMMIT)error("lost response")}.outcome(c) is StudyOutcome.Success)
        assertEquals(c.cardId,repo.submit(c));assertEquals(1,db.study().cards(book).size)
        assertEquals(parent,repo.nodes(book,map).first().first{it.id==c.nodeId}.parentId)
        val restoreName="note-first-restore-${id()}.db";val target=NoteDatabase.open(context,restoreName)
        try{LibraryBackupRepository(context,db).snapshot().use{snap->snap.file.inputStream().use{LibraryBackupRepository(context,target).inspect(it)}.use{prepared->
            assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,LibraryBackupRepository(context,target).restore(prepared))}}
            assertEquals(definition,target.knowledge().get(map)!!.data());assertEquals(1,target.study().cards(book).size)
            assertNotNull(target.study().source(c.cardId!!));assertEquals(1,target.knowledge().all().count{it.data() is KnowledgeData.MapTemplate})
        }finally{target.close();context.deleteDatabase(restoreName)}
        val undo=StudyCommand(id(),book,StudyAction.UNDO_CAPTURE,cardId=c.cardId,nodeId=c.nodeId,expectedRevision=1,mapId=map)
        repo.submit(undo);repo.submit(undo)
        assertNotNull(db.study().card(c.cardId!!)!!.trashedAt);assertFalse(repo.nodes(book,map).first().any{!it.removed&&it.id==c.nodeId})
        assertEquals(1L,InkRepository(db).read(book).revision)
    }
    @Test fun staleTargetIsRejectedAndUndoKeepsReusedOrEditedKnowledge()=fixture{db,book->
        val repo=StudyRepository(db);val empty=repo.readGraph(book).graphFingerprint
        val a=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="原卡")
        repo.submit(a)
        val stale=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="过期",expectedGraph=empty)
        assertTrue(repo.outcome(stale) is StudyOutcome.Rejected);assertEquals(1,db.study().cards(book).size)
        val other=id();repo.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=a.cardId,nodeId=other))
        repo.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=a.cardId,expectedRevision=1,title="后续修改"))
        repo.submit(StudyCommand(id(),book,StudyAction.UNDO_CAPTURE,cardId=a.cardId,nodeId=a.nodeId,expectedRevision=1))
        assertNull(db.study().card(a.cardId!!)!!.trashedAt);assertEquals("后续修改",db.study().card(a.cardId!!)!!.title)
        assertTrue(db.study().nodes(book).any{it.id==other&&!it.removed})
    }
}
