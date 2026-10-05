// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardTrashRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private data class Fixture(val db:NoteDatabase,val book:String,val other:String,val card:String,val question:String,val incoming:String,val outgoing:String)
    private fun fixture(block:suspend(Fixture)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="trash-preview-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val book=WorkspaceRepository(db).create("合成原笔记",false,PaperStyle.BLANK).id
            val other=WorkspaceRepository(db).create("合成其他本",false,PaperStyle.BLANK).id
            val ink=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(30f,40f,0),InkSample(60f,90f,10)))
            InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(ink)))
            val card=id();val repo=StudyRepository(db)
            repo.submit(StudyCommand(id(),book,StudyAction.CREATE_EXCERPT,cardId=card,title="保留原卡",body="正文",source=StudySourceDraft(book,1,ink.bounds(),listOf(ink.id))))
            val knowledge=KnowledgeRepository(db);val question=id();val incoming=id();val outgoing=id()
            knowledge.submit(KnowledgeCommand(id(),book,question,0,KnowledgeData.Question(card,"说明原理")))
            knowledge.submit(KnowledgeCommand(id(),other,incoming,0,KnowledgeData.Link(TargetRef(TargetKind.NOTE,other),TargetRef(TargetKind.CARD,card),pinnedRevision=1)))
            knowledge.submit(KnowledgeCommand(id(),book,outgoing,0,KnowledgeData.Link(TargetRef(TargetKind.CARD,card),TargetRef(TargetKind.NOTE,other))))
            block(Fixture(db,book,other,card,question,incoming,outgoing))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun previewIsReadOnlyListsCrossNotebookReferencesAndRestoreKeepsAllContent()=fixture{f->
        val repo=StudyRepository(f.db);val card=f.db.study().card(f.card);val source=repo.source(f.card)!!.snapshot
        val rows=f.db.knowledge().all().associateBy{it.id};val ink=InkRepository(f.db).read(f.book)
        val preview=repo.previewTrash(f.book,f.card)
        assertEquals(setOf(f.incoming,f.outgoing),preview.references.map{it.id}.toSet())
        assertEquals(listOf(f.question),preview.questions.map{it.id});assertTrue(preview.occurrences.isEmpty())
        assertTrue(preview.references.single{it.id==f.incoming}.label.contains("合成其他本"))
        assertEquals(f.other,preview.references.single{it.id==f.incoming}.notebookId)
        assertEquals(card,f.db.study().card(f.card));assertArrayEquals(source,repo.source(f.card)!!.snapshot)
        val command=preview.command();assertNull(repo.lookup(command)) // opening and cancelling have no receipt
        repo.submit(command);assertEquals(f.card,repo.submit(command));assertEquals(2L,f.db.study().card(f.card)!!.revision)
        repo.submit(StudyCommand(id(),f.book,StudyAction.RESTORE_CARD,cardId=f.card,expectedRevision=2))
        assertNull(f.db.study().card(f.card)!!.trashedAt);assertEquals(3L,f.db.study().card(f.card)!!.revision)
        assertArrayEquals(source,repo.source(f.card)!!.snapshot);assertEquals(ink.revision,InkRepository(f.db).read(f.book).revision)
        assertArrayEquals(InkStrokeCodec.encode(ink.strokes.single().stroke),InkStrokeCodec.encode(InkRepository(f.db).read(f.book).strokes.single().stroke))
        rows.forEach{(id,row)->val after=f.db.knowledge().get(id)!!;assertEquals(row.revision,after.revision);assertEquals(row.removed,after.removed);assertArrayEquals(row.payload,after.payload)}
        assertTrue(KnowledgeTextRepository(f.db).preview(TargetRef(TargetKind.NOTE,f.other),f.incoming,1).canOpen)
    }
    @Test fun newCrossNotebookLinkQuestionEditAndCardEditRejectEachFrozenPreview()=fixture{f->
        val repo=StudyRepository(f.db);val knowledge=KnowledgeRepository(f.db)
        suspend fun rejectAfter(change:suspend()->Unit,reason:String){
            val command=repo.previewTrash(f.book,f.card).command();change()
            assertEquals(StudyOutcome.Rejected(reason),repo.outcome(command));assertNull(repo.lookup(command));assertNull(f.db.study().card(f.card)!!.trashedAt)
        }
        rejectAfter({knowledge.submit(KnowledgeCommand(id(),f.other,id(),0,KnowledgeData.Link(TargetRef(TargetKind.NOTE,f.other),TargetRef(TargetKind.CARD,f.card),RelationKind.CONTRAST)))},"TRASH_IMPACT_CHANGED")
        rejectAfter({knowledge.submit(KnowledgeCommand(id(),f.book,f.question,1,KnowledgeData.Question(f.card,"新题目",ManualState.UNDERSTOOD)))},"TRASH_IMPACT_CHANGED")
        rejectAfter({val row=f.db.knowledge().get(f.incoming)!!;knowledge.submit(KnowledgeCommand(id(),f.other,row.id,row.revision,row.data(),true))},"TRASH_IMPACT_CHANGED")
        rejectAfter({repo.submit(StudyCommand(id(),f.book,StudyAction.EDIT,cardId=f.card,expectedRevision=1,title="新版",body="仍保留"))},"CARD_VERSION_CHANGED")
        repo.submit(repo.previewTrash(f.book,f.card).command());assertNotNull(f.db.study().card(f.card)!!.trashedAt)
    }
    @Test fun existingOrNewOccurrenceCannotBeRecycledByConfirmingOldList()=fixture{f->
        val repo=StudyRepository(f.db);val old=repo.previewTrash(f.book,f.card).command();val node=id()
        repo.submit(StudyCommand(id(),f.book,StudyAction.REUSE,cardId=f.card,nodeId=node))
        assertEquals(StudyOutcome.Rejected("TRASH_IMPACT_CHANGED"),repo.outcome(old))
        val now=repo.previewTrash(f.book,f.card);assertEquals(node,now.occurrences.single().id)
        assertEquals(StudyOutcome.Rejected("REMOVE_OCCURRENCES_FIRST"),repo.outcome(now.command()))
        assertNull(f.db.study().card(f.card)!!.trashedAt)
    }
    @Test fun legacyPendingOnlyResolvesItsExistingReceiptNeverCreatesUnpreviewedWrite()=fixture{f->
        val repo=StudyRepository(f.db);val legacy=StudyCommand(id(),f.book,StudyAction.TRASH_CARD,cardId=f.card,expectedRevision=1)
        assertEquals(StudyOutcome.Rejected("TRASH_PREVIEW_REQUIRED"),repo.outcome(legacy));assertNull(repo.lookup(legacy))
        assertNull(f.db.study().card(f.card)!!.trashedAt)
        repo.submit(repo.previewTrash(f.book,f.card).command())
        // Synthetic old-version receipt fixture; its original digest must still resolve before version/preview checks.
        f.db.study().receipt(StudyReceiptRow(legacy.id,f.book,legacy.digest(),f.card))
        assertEquals(StudyOutcome.Success(f.card),repo.outcome(legacy));assertEquals(2L,f.db.study().card(f.card)!!.revision)
    }
    @Test fun failureRollsBackAndLostAcknowledgementReplaysSameConfirmedImpact()=fixture{f->
        val repo=StudyRepository(f.db);val command=repo.previewTrash(f.book,f.card).command();val snapshot=repo.source(f.card)!!.snapshot
        assertEquals(StudyOutcome.Unknown,StudyRepository(f.db){if(it==StudyFault.BEFORE_RECEIPT)error("synthetic rollback")}.outcome(command))
        assertNull(repo.lookup(command));assertNull(f.db.study().card(f.card)!!.trashedAt);assertArrayEquals(snapshot,repo.source(f.card)!!.snapshot)
        assertEquals(StudyOutcome.Success(f.card),StudyRepository(f.db){if(it==StudyFault.AFTER_COMMIT)error("synthetic lost acknowledgement")}.outcome(command))
        assertEquals(f.card,repo.submit(command));assertEquals(2L,f.db.study().card(f.card)!!.revision)
    }
}
