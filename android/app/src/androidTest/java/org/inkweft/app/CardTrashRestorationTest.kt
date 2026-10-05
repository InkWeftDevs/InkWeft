// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Recreates SavedStateHandle and VM identity, not an OS process-death claim. */
class CardTrashRestorationTest {
    private fun id()=UUID.randomUUID().toString()
    private fun <T> main(block:()->T):T{var value:T?=null;InstrumentationRegistry.getInstrumentation().runOnMainSync{value=block()};@Suppress("UNCHECKED_CAST")return value as T}
    private suspend fun settled(vm:StudyViewModel){withTimeout(15_000){vm.ui.first{!it.loading&&!it.busy}}}
    private fun fixture(block:suspend(NoteDatabase,String,String)->Unit)=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="trash-restoration-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val book=WorkspaceRepository(db).create("合成回收恢复",false,PaperStyle.BLANK).id;val card=id();val node=id();val repo=StudyRepository(db)
            repo.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=node,title="原卡",body="保留内容"))
            repo.submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=node,expectedRevision=1));block(db,book,card)
        }finally{db.close();context.deleteDatabase(name)}
    }
    private fun legacySaved(c:StudyCommand)=SavedStateHandle(mapOf("study.command" to arrayListOf(c.id,c.notebookId,c.action.name,c.cardId.orEmpty(),c.nodeId.orEmpty(),c.expectedRevision.toString(),c.parentId.orEmpty(),c.title,c.body,c.x.toString(),c.y.toString(),c.expectedGraph,c.mapId.orEmpty())))
    @Test fun unknownFrozenImpactSurvivesRestorationAndRejectsLaterReferences()=fixture{db,book,card->
        val fail=AtomicBoolean(true);val repo=StudyRepository(db){if(it==StudyFault.BEFORE_RECEIPT&&fail.get())error("synthetic rollback")}
        val preview=repo.previewTrash(book,card);val saved=SavedStateHandle();var second:StudyViewModel?=null
        val first=main{StudyViewModel(book,repo,saved)}
        try{
            settled(first);main{assertTrue(first.trash(preview));assertFalse(first.trash(preview))}
            withTimeout(15_000){first.ui.first{it.unknown&&!it.busy}}
            main{assertFalse(first.trash(preview))};assertNull(db.study().card(card)!!.trashedAt)
            val copied=main{SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}).also{first.viewModelScope.cancel()}}
            assertEquals(preview.fingerprint,copied.get<String>("study.trashImpact"))
            val restored=main{StudyViewModel(book,repo,copied)};second=restored;settled(restored)
            KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(card,"恢复前新增题目")))
            fail.set(false);main{restored.retry()};withTimeout(15_000){restored.ui.first{!it.busy&&!it.unknown}}
            assertTrue(restored.ui.value.message!!.contains("引用或题目已变化"));assertNull(db.study().card(card)!!.trashedAt)
            assertNull(copied.get<String>("study.trashImpact"))
        }finally{main{first.viewModelScope.cancel();second?.viewModelScope?.cancel()}}
    }
    @Test fun oldPendingIsNotReplayedAndReadOnlyCanOnlyResolveACommittedReceipt()=fixture{db,book,card->
        val repo=StudyRepository(db);val old=StudyCommand(id(),book,StudyAction.TRASH_CARD,cardId=card,expectedRevision=1)
        val legacy=main{StudyViewModel(book,repo,legacySaved(old))}
        try{settled(legacy);main{legacy.retry()};withTimeout(15_000){legacy.ui.first{!it.busy&&!it.unknown}}
            assertTrue(legacy.ui.value.message!!.contains("没有影响预览"));assertNull(db.study().card(card)!!.trashedAt)
        }finally{main{legacy.viewModelScope.cancel()}}
        val preview=repo.previewTrash(book,card);val command=preview.command()
        val saved=legacySaved(command);saved["study.trashImpact"]=command.expectedTrashImpact
        val locked=main{StudyViewModel(book,repo,saved).also{it.authorAllowed={false}}}
        try{settled(locked);main{locked.retry()};withTimeout(15_000){locked.ui.first{!it.busy&&!it.unknown}}
            assertNull(db.study().card(card)!!.trashedAt);assertNull(repo.lookup(command))
            main{assertFalse(locked.trash(preview)) }
        }finally{main{locked.viewModelScope.cancel()}}
        repo.submit(command)
        val receiptSaved=legacySaved(command);receiptSaved["study.trashImpact"]=command.expectedTrashImpact
        val receipt=main{StudyViewModel(book,repo,receiptSaved).also{it.authorAllowed={false}}}
        try{settled(receipt);main{receipt.retry()};withTimeout(15_000){receipt.ui.first{!it.busy&&!it.unknown&&it.completed==card}}
            assertEquals(2L,db.study().card(card)!!.revision)
        }finally{main{receipt.viewModelScope.cancel()}}
    }
}
