// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Requires a device: real Room and Android main-thread ViewModel/SavedState behavior. */
class ExcerptHistoryStateTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun id()=UUID.randomUUID().toString()
    private val app get()=ApplicationProvider.getApplicationContext<InkWeftApplication>()
    private fun fixture(block:(NoteDatabase,String)->Unit){
        val name="excerpt-history-${id()}.db";val db=NoteDatabase.open(app,name)
        try{val book=runBlocking{WorkspaceRepository(db).create("history",false,PaperStyle.BLANK).id};block(db,book)}
        finally{compose.runOnIdle{compose.activity.viewModelStore.clear()};db.close();app.deleteDatabase(name)}
    }
    private fun restored(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith{key->when(val value=saved.get<Any?>(key)){
        is ByteArray->value.copyOf();is ArrayList<*>->ArrayList(value);else->value
    }})
    @Test fun personalCommentUndoRedoSurvivesWriterRecreationAndRespectsReadLock()=fixture{db,book->
        val card=id();runBlocking{StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE_EXCERPT,cardId=card,title="excerpt",source=StudySourceDraft(book,0,CanvasBounds(1.0,1.0,20.0,20.0),emptyList(),jpeg(),0)))}
        var saved=SavedStateHandle();lateinit var writer:KnowledgeViewModel
        compose.runOnIdle{writer=KnowledgeViewModel(KnowledgeRepository(db),saved,app.resourcePacks);compose.activity.viewModelStore.put("history",writer)}
        compose.waitUntil(15_000){!writer.ui.value.loading}
        compose.runOnIdle{writer.submit(book,KnowledgeData.CardPresentation(card,"original comment"))}
        compose.waitUntil(15_000){writer.canUndoPresentation(card)}
        compose.runOnIdle{saved=restored(saved);writer=KnowledgeViewModel(KnowledgeRepository(db),saved,app.resourcePacks);compose.activity.viewModelStore.put("history",writer)}
        compose.waitUntil(15_000){writer.canUndoPresentation(card)}
        compose.runOnIdle{writer.authorAllowed={false};writer.undoProperties()}
        assertEquals(1L,runBlocking{db.knowledge().all().single().revision})
        compose.runOnIdle{writer.authorAllowed={true};writer.undoProperties()}
        compose.waitUntil(15_000){writer.canRedoPresentation(card)}
        assertTrue(runBlocking{db.knowledge().all().single().removed})
        compose.runOnIdle{writer.redoProperties()}
        compose.waitUntil(15_000){writer.canUndoPresentation(card)}
        val row=runBlocking{db.knowledge().all().single()};assertFalse(row.removed);assertEquals(3L,row.revision)
        assertEquals("original comment",(row.data() as KnowledgeData.CardPresentation).annotation)
        assertEquals(1L,runBlocking{db.study().card(card)!!.revision})
    }
    @Test fun pendingCommentUndoRetainsSameReceiptAndInverseUntilConfirmed()=fixture{db,book->
        val card=id();runBlocking{StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=id(),title="card"))}
        val fail=AtomicBoolean(false);val repository=KnowledgeRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT&&fail.get())throw IOException("synthetic")}
        var saved=SavedStateHandle();lateinit var writer:KnowledgeViewModel
        compose.runOnIdle{writer=KnowledgeViewModel(repository,saved,app.resourcePacks);compose.activity.viewModelStore.put("history",writer)}
        compose.waitUntil(15_000){!writer.ui.value.loading}
        compose.runOnIdle{writer.submit(book,KnowledgeData.CardPresentation(card,"keep"))}
        compose.waitUntil(15_000){writer.canUndoPresentation(card)}
        fail.set(true);compose.runOnIdle{writer.undoProperties()}
        compose.waitUntil(15_000){writer.ui.value.unknown&&!writer.ui.value.busy}
        val operation=compose.runOnIdle{writer.pendingOperationId}
        compose.runOnIdle{saved=restored(saved);writer=KnowledgeViewModel(repository,saved,app.resourcePacks);compose.activity.viewModelStore.put("history",writer)}
        assertEquals(operation,compose.runOnIdle{writer.pendingOperationId});assertEquals(1L,runBlocking{db.knowledge().all().single().revision})
        fail.set(false);compose.runOnIdle{writer.retry()}
        compose.waitUntil(15_000){writer.canRedoPresentation(card)}
        val row=runBlocking{db.knowledge().all().single()};assertTrue(row.removed);assertEquals(2L,row.revision)
        compose.runOnIdle{writer.redoProperties()};compose.waitUntil(15_000){writer.canUndoPresentation(card)}
        assertEquals(3L,runBlocking{db.knowledge().all().single().revision})
    }
    @Test fun excerptRangeInverseSurvivesSavedStateAndRejectsNewerCardRevision()=fixture{db,book->
        val card=id();val repo=StudyRepository(db)
        runBlocking{repo.submit(StudyCommand(id(),book,StudyAction.CREATE_EXCERPT,cardId=card,title="excerpt",source=StudySourceDraft(book,0,CanvasBounds(1.0,1.0,20.0,20.0),emptyList(),jpeg(),0)))}
        var saved=SavedStateHandle();lateinit var writer:StudyViewModel
        compose.runOnIdle{writer=StudyViewModel(book,repo,saved);compose.activity.viewModelStore.put("history",writer)}
        compose.waitUntil(15_000){!writer.ui.value.loading}
        compose.runOnIdle{writer.recropExcerpt(StudyCommand(id(),book,StudyAction.RECROP_EXCERPT,cardId=card,expectedRevision=1,source=StudySourceDraft(book,0,CanvasBounds(5.0,5.0,40.0,40.0),emptyList(),jpeg(),0)))}
        compose.waitUntil(15_000){writer.canUndoExcerpt(card)}
        compose.runOnIdle{saved=restored(saved);writer=StudyViewModel(book,repo,saved);compose.activity.viewModelStore.put("history",writer)}
        compose.waitUntil(15_000){writer.canUndoExcerpt(card)}
        compose.runOnIdle{writer.undoExcerpt(card)};compose.waitUntil(15_000){writer.canRedoExcerpt(card)}
        assertEquals(1.0,runBlocking{repo.source(card)!!.left},0.0)
        compose.runOnIdle{writer.redoExcerpt(card)};compose.waitUntil(15_000){writer.canUndoExcerpt(card)}
        assertEquals(5.0,runBlocking{repo.source(card)!!.left},0.0)
        runBlocking{repo.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=card,expectedRevision=4,title="newer content"))}
        compose.waitUntil(15_000){writer.ui.value.cards.single().revision==5L}
        compose.runOnIdle{assertFalse(writer.canUndoExcerpt(card));writer.undoExcerpt(card)}
        assertEquals(5L,runBlocking{db.study().card(card)!!.revision})
    }
    private fun jpeg():ByteArray{
        val bitmap=android.graphics.Bitmap.createBitmap(20,20,android.graphics.Bitmap.Config.ARGB_8888)
        return try{java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it)}.toByteArray()}finally{bitmap.recycle()}
    }
}
