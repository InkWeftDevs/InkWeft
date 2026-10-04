// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Independent real Compose/Room component coverage; integration entry points have separate tests. */
class CardTransformUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val name="transform-ui-${UUID.randomUUID()}.db"
    private val db by lazy{NoteDatabase.open(context,name)}
    private val books=mutableListOf<String>()
    private fun id()=UUID.randomUUID().toString()
    @After fun close(){db.close();context.deleteDatabase(name);books.forEach{java.io.File(context.filesDir,"card-transform-$it.pending").delete()}}
    private fun seed():Pair<String,List<String>> = runBlocking{
        val book=WorkspaceRepository(db).create("合成转换UI",false,PaperStyle.BLANK).id;books+=book
        val cards=List(2){i->val card=id();StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,card,id(),title="卡片$i",body="正文$i\n 保留空格 "));card}
        book to cards
    }
    @Test fun previewCancellationHasZeroWritesAndConfirmedMergeShowsResolutionChoices(){
        val (book,cards)=seed();val repo=CardTransformRepository(db);var visible by mutableStateOf(true);var targets by mutableStateOf<List<String>>(emptyList())
        val count=runBlocking{db.study().cards(book).size};val nodes=runBlocking{db.study().nodes(book)}
        compose.setContent{MaterialTheme{if(visible)CardTransformDialog(repo,book,cards,CardTransformKind.MERGE,{visible=false},{targets=it;visible=false})}}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-preview").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("transform-preview").performClick();compose.onNodeWithTag("transform-retention-policy").assertExists()
        compose.onNodeWithTag("transform-cancel").performClick()
        assertEquals(count,runBlocking{db.study().cards(book).size});assertTrue(runBlocking{db.cardTransforms().forBook(book).isEmpty()})
        assertFalse(java.io.File(context.filesDir,"card-transform-$book.pending").exists())
        compose.runOnIdle{visible=true}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-preview").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("transform-preview").performClick();compose.onNodeWithTag("transform-confirm").performClick()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-done").assertExists()}.isSuccess}
        compose.onNodeWithTag("transform-done").performClick();assertEquals(1,targets.size);assertEquals(nodes,runBlocking{db.study().nodes(book)})
        assertEquals(targets,runBlocking{repo.resolve(cards.first()).single().targets.map{it.id}})
    }
    @Test fun unknownSaveRestoresSameOperationAndNeverCreatesDuplicateTargets(){
        val (book,cards)=seed();var fail=true;var mounted by mutableStateOf(true)
        val repo=CardTransformRepository(db){if(fail&&it==CardTransformFault.BEFORE_RECEIPT)error("synthetic unknown")}
        compose.setContent{MaterialTheme{if(mounted)CardTransformDialog(repo,book,cards,CardTransformKind.MERGE,{}, {})}}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-preview").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("transform-preview").performClick();compose.onNodeWithTag("transform-confirm").performClick()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithText("核对并重试同一次").assertExists()}.isSuccess}
        compose.onNodeWithTag("transform-cancel").assertIsNotEnabled()
        val path=java.io.File(context.filesDir,"card-transform-$book.pending");val intent=CardTransformCodec.decode(path.readBytes())
        compose.runOnIdle{mounted=false};compose.waitForIdle();compose.runOnIdle{mounted=true}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithText("核对并重试同一次").assertExists()}.isSuccess}
        assertEquals(intent.operationId,CardTransformCodec.decode(path.readBytes()).operationId)
        fail=false;compose.onNodeWithTag("transform-confirm").performClick()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-done").assertExists()}.isSuccess}
        assertEquals(3,runBlocking{db.study().cards(book).size});assertEquals(intent.operationId,runBlocking{db.cardTransforms().forBook(book).single().operationId});assertFalse(path.exists())
    }
    @Test fun frozenMultiSourceRequiresChoiceAndUnavailableNeverFallsBack(){
        val book=id();val card=id();val sources=List(2){i->StudySourceRevisionRow(id(),i+1L,book,id(),0,0.0,0.0,20.0,20.0,"",byteArrayOf(i.toByte()))}
        var frozen by mutableStateOf(FrozenStudySources(sources.map{it.ref()},sources,true));var selected:StudySourceRow?=null
        compose.setContent{MaterialTheme{FrozenCardSources(frozen,card,{selected=it})}}
        compose.runOnIdle{assertNull(selected)}
        compose.onNodeWithTag("frozen-source-1").performClick();compose.runOnIdle{assertEquals(sources[1].pageId,selected!!.pageId)}
        compose.runOnIdle{frozen=frozen.copy(complete=false)}
        compose.onNodeWithTag("frozen-source-unavailable").assertExists();compose.runOnIdle{assertNull(selected)}
    }
    @Test fun restoredUnknownInverseKeepsOneGuardAndDisablesOtherResolutionActions(){
        val (book,cards)=seed();val plain=CardTransformRepository(db)
        fun merge(title:String)=runBlocking{val p=plain.preview(book,cards);val plan=p.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(p.cards,title)));plain.submit(plan);plan}
        val first=merge("第一次合并");val latest=merge("第二次合并")
        var fail=true;var blocked=false
        val repository=CardTransformRepository(db){if(fail&&it==CardTransformFault.BEFORE_RECEIPT)error("synthetic inverse unknown")}
        val restoration=StateRestorationTester(compose)
        restoration.setContent{MaterialTheme{CardTransformResolutionPanel(repository,cards.first(),{},onPendingChanged={blocked=it})}}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("transform-undo-${latest.operationId}").assertExists()}.isSuccess}
        compose.onNodeWithTag("transform-undo-${latest.operationId}").performClick()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithText("重试同一次撤销").assertExists()}.isSuccess}
        compose.runOnIdle{assertTrue(blocked)};compose.onNodeWithTag("transform-undo-${first.operationId}").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithText("重试同一次撤销").assertExists()}.isSuccess}
        compose.runOnIdle{assertTrue(blocked)};compose.onNodeWithTag("transform-undo-${first.operationId}").assertIsNotEnabled()
        fail=false;compose.onNodeWithTag("transform-undo-${latest.operationId}").performClick()
        compose.waitUntil(15_000){runBlocking{db.cardTransforms().get(latest.operationId)!!.undone}}
        compose.waitForIdle();compose.runOnIdle{assertFalse(blocked)}
        assertFalse(runBlocking{db.cardTransforms().get(first.operationId)!!.undone})
    }

}
