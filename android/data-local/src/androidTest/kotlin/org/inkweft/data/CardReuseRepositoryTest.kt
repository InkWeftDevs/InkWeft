// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardReuseRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String,String,String)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="reuse-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val a=WorkspaceRepository(db).create("原本",false,PaperStyle.RULED).id;val b=WorkspaceRepository(db).create("目标本",false,PaperStyle.BLANK).id
            val card=id();StudyRepository(db).submit(StudyCommand(id(),a,StudyAction.CREATE,cardId=card,nodeId=id(),title="同一知识",body="完整正文"))
            block(db,a,b,card)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun sharedReferenceKeepsOneCardWhileIndependentCopyStartsWithoutQuestions()=fixture{db,a,b,card->
        val knowledge=KnowledgeRepository(db);val presentation=KnowledgeData.CardPresentation(card,"个人注释",CardTint.GREEN,CardTint.ROSE)
        knowledge.submit(KnowledgeCommand(id(),a,id(),0,presentation));knowledge.submit(KnowledgeCommand(id(),a,id(),0,KnowledgeData.Question(card,"解释")))
        val repo=CardReuseRepository(db);val ref=repo.prepare(card,1,b,CardReuseKind.REFERENCE);repo.submit(ref)
        val copy=repo.prepare(card,1,b,CardReuseKind.INDEPENDENT_COPY)
        assertTrue(CardReuseRepository(db){if(it==KnowledgeFault.AFTER_COMMIT)error("lost")}.outcome(copy) is KnowledgeOutcome.Success)
        repo.submit(copy);val copied=db.study().card(copy.resultId())!!
        assertEquals(b,copied.notebookId);assertEquals(1L,copied.revision);assertEquals("完整正文",copied.body)
        assertEquals(2,(db.study().cards(a)+db.study().cards(b)).size)
        assertTrue(db.knowledge().forBook(b).none{it.data() is KnowledgeData.Question})
        assertEquals(presentation.copy(cardId=copied.id),db.knowledge().forBook(b).map{it.data()}.filterIsInstance<KnowledgeData.CardPresentation>().single())
        StudyRepository(db).submit(StudyCommand(id(),a,StudyAction.EDIT,cardId=card,expectedRevision=1,title="改变",body="更新原内容"))
        val link=db.knowledge().get(ref.resultId())!!
        assertEquals("更新原内容",KnowledgeTextRepository(db).preview(TargetRef(TargetKind.NOTE,b),link.id,link.revision).body)
        assertEquals("完整正文",db.study().card(copied.id)!!.body)
        knowledge.validateArchive();StudySourceVersions(db).validateArchive()
    }
    @Test fun frozenVersionConflictAndPreReceiptFaultDoNotLeaveHalfCopy()=fixture{db,a,b,card->
        val repo=CardReuseRepository(db);val request=repo.prepare(card,1,b,CardReuseKind.INDEPENDENT_COPY)
        assertEquals(KnowledgeOutcome.Unknown,CardReuseRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT)error("abort")}.outcome(request))
        assertTrue(db.study().cards(b).isEmpty());assertTrue(db.study().nodes(b).isEmpty())
        StudyRepository(db).submit(StudyCommand(id(),a,StudyAction.EDIT,cardId=card,expectedRevision=1,title="新版",body="不可静默抄新版"))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.CONFLICT),repo.outcome(request));assertTrue(db.study().cards(b).isEmpty())
    }
    @Test fun removingReferenceDoesNotDeleteEitherCardAndRecycledTargetReportsUnavailable()=fixture{db,a,b,card->
        val repo=CardReuseRepository(db);val ref=repo.prepare(card,1,b,CardReuseKind.REFERENCE);repo.submit(ref)
        val row=db.knowledge().get(ref.resultId())!!;KnowledgeRepository(db).submit(KnowledgeCommand(id(),b,row.id,row.revision,row.data(),true))
        assertNull(db.study().card(card)!!.trashedAt);assertTrue(db.study().cards(b).isEmpty())
        val another=repo.prepare(card,1,b,CardReuseKind.REFERENCE);repo.submit(another)
        val node=db.study().nodes(a).single()
        StudyRepository(db).submit(StudyCommand(id(),a,StudyAction.REMOVE_NODE,nodeId=node.id,expectedRevision=node.revision))
        StudyRepository(db).submit(StudyCommand(id(),a,StudyAction.TRASH_CARD,cardId=card,expectedRevision=1,expectedTrashImpact=StudyRepository(db).previewTrash(a,requireNotNull(card)).fingerprint))
        val preview=KnowledgeTextRepository(db).preview(TargetRef(TargetKind.NOTE,b),another.resultId(),1)
        assertFalse(preview.canOpen);assertEquals("完整正文",preview.body)
    }
}
