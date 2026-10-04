// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardPresentationRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun command(book:String,value:KnowledgeData.CardPresentation,row:String=id(),revision:Long=0)=KnowledgeCommand(id(),book,row,revision,value)
    private fun fixture(block:suspend(NoteDatabase,String,String)->Unit)=runBlocking{
        val name="presentation-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val book=WorkspaceRepository(db).create("个人注释",false,PaperStyle.BLANK).id;val card=id()
            StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=id(),title="原标题",body="旧摘录\n旧备注逐字保留"))
            block(db,book,card)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun casUniquenessAndOwnershipPreserveBodyAndIndependentLayout()=fixture{db,book,card->
        val repo=KnowledgeRepository(db);val nodes=db.study().nodes(book);val before=db.study().card(card)!!
        val c=command(book,KnowledgeData.CardPresentation(card,"新注释",CardTint.GREEN,CardTint.BLUE));repo.submit(c)
        assertEquals(KnowledgeRejection.DUPLICATE,(repo.outcome(command(book,c.data as KnowledgeData.CardPresentation)) as KnowledgeOutcome.Rejected).reason)
        val updated=(c.data as KnowledgeData.CardPresentation).copy(annotation="已确认新注释")
        repo.submit(command(book,updated,c.id,1))
        assertEquals(KnowledgeRejection.CONFLICT,(repo.outcome(command(book,updated.copy(annotation="过期"),c.id,1)) as KnowledgeOutcome.Rejected).reason)
        val other=WorkspaceRepository(db).create("其他",false,PaperStyle.BLANK).id
        assertEquals(KnowledgeRejection.INVALID,(repo.outcome(command(other,updated)) as KnowledgeOutcome.Rejected).reason)
        assertEquals(before,db.study().card(card));assertEquals(nodes,db.study().nodes(book));assertEquals(updated,db.knowledge().get(c.id)!!.data())
        assertEquals("新注释",(KnowledgeCodec.decode(db.knowledge().revision(c.id,1)!!.payload) as KnowledgeData.CardPresentation).annotation)
        val another=id();StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=another,nodeId=id(),title="另一卡"))
        assertEquals(KnowledgeRejection.INVALID,(repo.outcome(command(book,updated.copy(cardId=another),c.id,2)) as KnowledgeOutcome.Rejected).reason)
        repo.validateArchive()
    }
    @Test fun failuresRollbackAndReceiptReplayDoesNotDuplicateAnnotation()=fixture{db,book,card->
        val c=command(book,KnowledgeData.CardPresentation(card,"只提交一次"))
        assertEquals(KnowledgeOutcome.Unknown,KnowledgeRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT)error("rollback")}.outcome(c))
        assertNull(db.knowledge().get(c.id));assertNull(db.knowledge().revision(c.id,1));assertNull(db.knowledge().receipt(c.operationId))
        assertTrue(KnowledgeRepository(db){if(it==KnowledgeFault.AFTER_COMMIT)error("lost response")}.outcome(c) is KnowledgeOutcome.Success)
        val repo=KnowledgeRepository(db);repo.submit(c);assertEquals(1,db.knowledge().revisions(c.id).size)
        assertEquals(c.id,repo.lookup(c))
    }
    @Test fun backupReopenRetainsCurrentHistoryDefaultsAndLegacyText()=fixture{db,book,card->
        val repo=KnowledgeRepository(db);val initial=KnowledgeData.CardPresentation(card,"共享注释",CardTint.CREAM,CardTint.ROSE)
        val c=command(book,initial);repo.submit(c);repo.submit(command(book,initial.copy(cardColor=CardTint.DEFAULT,titleBarColor=CardTint.DEFAULT),c.id,1))
        val name="presentation-restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{LibraryBackupRepository(context,db).snapshot().use{snapshot->
            val restore=LibraryBackupRepository(context,target)
            snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}
        };target.close();target=NoteDatabase.open(context,name)
            assertEquals(initial.copy(cardColor=CardTint.DEFAULT,titleBarColor=CardTint.DEFAULT),target.knowledge().get(c.id)!!.data())
            assertEquals(initial,KnowledgeCodec.decode(target.knowledge().revision(c.id,1)!!.payload));assertNotNull(target.knowledge().receipt(c.operationId))
            assertEquals("旧摘录\n旧备注逐字保留",target.study().card(card)!!.body);KnowledgeRepository(target).validateArchive()
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun livePreviewIncludesAnnotationWhilePinnedAnswerRemainsHistorical()=fixture{db,book,card->
        val repo=KnowledgeRepository(db);repo.submit(command(book,KnowledgeData.CardPresentation(card,"不能泄入固定版本的注释")))
        val source=TargetRef(TargetKind.PAGE,book);val target=TargetRef(TargetKind.CARD,card)
        val live=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Link(source,target));repo.submit(live)
        val pinned=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Link(source,target,pinnedRevision=1));repo.submit(pinned)
        val reader=KnowledgeTextRepository(db)
        assertEquals("不能泄入固定版本的注释",reader.preview(source,live.id,1).annotation)
        val old=reader.preview(source,pinned.id,1);assertEquals("",old.annotation);assertEquals("旧摘录\n旧备注逐字保留",old.body)
    }
}
