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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Rebuilds the pending VM state, not an OS-process-death claim. */
class StudySourceScopeRestorationTest {
    private fun id()=UUID.randomUUID().toString()
    private fun <T> main(block:()->T):T {var result:T?=null;InstrumentationRegistry.getInstrumentation().runOnMainSync{result=block()};@Suppress("UNCHECKED_CAST")return result as T}
    @Test fun restoredPendingSourceRetainsAuthoringScopeAndOriginalDigest()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="source-scope-${id()}.db";val db=NoteDatabase.open(context,name)
        val fail=AtomicBoolean(true);val repo=StudyRepository(db){if(it==StudyFault.BEFORE_RECEIPT&&fail.get())throw IOException("synthetic unknown")}
        var first:StudyViewModel?=null;var second:StudyViewModel?=null
        try{
            val page=WorkspaceRepository(db).create("合成来源恢复",false,PaperStyle.BLANK).id
            val stroke=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(30f,40f,0),InkSample(60f,90f,10)))
            InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
            val meta=PageAuthoringRepository(db);val before=meta.readPage(page)
            meta.save(AuthoringScope.page(page,page),before,id(),before.state.withBlanks(listOf(DocumentWhitespace(id(),300.0))))
            val command=StudyCommand(id(),page,StudyAction.CREATE_EXCERPT,cardId=id(),title="同一来源",source=StudySourceDraft(page,1,stroke.bounds(),listOf(stroke.id),authoringRevision=1))
            val saved=SavedStateHandle();val a=main{StudyViewModel(page,repo,saved)};first=a
            withTimeout(15000){a.ui.first{!it.loading}}
            main{a.submit(command)};withTimeout(15000){a.ui.first{it.unknown&&!it.busy}}
            val copied=main{SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}).also{a.viewModelScope.cancel()}}
            assertEquals(1L,copied.get<Long>("study.authoringRevision"))
            val b=main{StudyViewModel(page,repo,copied)};second=b
            assertTrue(b.ui.value.unknown);assertNull(repo.lookup(command))
            fail.set(false);main{b.retry()};withTimeout(15000){b.ui.first{!it.unknown&&!it.busy&&it.completed==command.cardId}}
            assertEquals(command.cardId,repo.lookup(command));assertEquals(1,db.study().cards(page).size)
        }finally{main{first?.viewModelScope?.cancel();second?.viewModelScope?.cancel()};db.close();context.deleteDatabase(name)}
    }
}
