// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Request ownership checks; production UI tests still cover page/viewport/return-stack integration. */
class StudyNavigationStateTest {
    private fun card(id:String)=StudyCardRow(id,"book",1,id,"")
    private fun node()=StudyNodeRow("node","book","card",null,0.0,0.0)
    private fun owner()=StudyNavigationState.NodeOwner(null,"node",2,null,null,null,null,null)
    private fun sources(vararg pages:String,complete:Boolean=true):FrozenStudySources {
        val rows=pages.mapIndexed{i,page->StudySourceRevisionRow(UUID(0L,i.toLong()+1).toString(),1,"book",page,1,0.0,0.0,10.0,10.0,"",byteArrayOf(i.toByte()))}
        return FrozenStudySources(rows.map{it.ref()},rows,complete)
    }

    @Test fun supersededSourceReadCannotPublishLateSuccessOrFailure()=runBlocking {
        withTimeout(5_000){
            for(fail in listOf(false,true)){
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                val state=StudyNavigationState(this){id,_->
                    if(id=="old"){entered.complete(Unit);release.await();if(fail)error("late read failure")}
                    sources(id)
                }
                val old=launch{state.loadCardSources(card("old"),false)}
                entered.await();state.loadCardSources(card("new"),false)
                release.complete(Unit);old.join()
                assertEquals("new",state.sourceVersions?.first?.id)
                assertEquals("new",state.source?.pageId)
                assertFalse(state.sourceLoadFailed)
            }
        }
    }

    @Test fun canceledCardCompletionCannotClearReplacementOrAcceptDuplicate()=runBlocking {
        withTimeout(5_000){
            val state=StudyNavigationState(this){_,_->sources("page")}
            val before=StudyNavigationState.CardOwner(null,"card",null)
            val after=StudyNavigationState.CardOwner("other-map","card","other-node")
            var current=before;var opened=0;val messages=mutableListOf<String>()
            val entered=CompletableDeferred<Unit>();val oldRelease=CompletableDeferred<Unit>()
            val old=checkNotNull(state.openCardSource(before,checkNotNull(sources("page").singleLegacy("card")),{current},
                {entered.complete(Unit);withContext(NonCancellable){oldRelease.await()};true},{opened++},messages::add))
            entered.await();state.cancelCardSource(before);current=after
            val newRelease=CompletableDeferred<Unit>()
            val next=checkNotNull(state.openCardSource(after,checkNotNull(sources("page").singleLegacy("card")),{current},
                {newRelease.await();true},{opened++},messages::add))
            assertNull(state.openCardSource(after,checkNotNull(sources("page").singleLegacy("card")),{current},{true},{opened++},messages::add))
            state.cancelCardSource(before) // Disposal of the previous inspector cannot cancel its successor.
            oldRelease.complete(Unit);old.join()
            assertTrue(state.cardSourceOpening);assertEquals(0,opened);assertTrue(messages.isEmpty())
            newRelease.complete(Unit);next.join()
            assertFalse(state.cardSourceOpening);assertEquals(1,opened)
        }
    }

    @Test fun changedMapRejectsCardResultEvenBeforeInspectorDisposal()=runBlocking {
        withTimeout(5_000){
            val state=StudyNavigationState(this){_,_->sources("page")}
            val origin=StudyNavigationState.CardOwner(null,"card","node")
            var current=origin;var opened=false;val messages=mutableListOf<String>()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val request=checkNotNull(state.openCardSource(origin,checkNotNull(sources("page").singleLegacy("card")),{current},
                {entered.complete(Unit);release.await();false},{opened=true},messages::add))
            entered.await();current=origin.copy(mapId="different-map");release.complete(Unit);request.join()
            assertFalse(opened);assertTrue(messages.isEmpty());assertFalse(state.cardSourceOpening)
            var pageRequests=0
            assertNull(state.openCardSource(origin,checkNotNull(sources("page").singleLegacy("card")),{current},
                {pageRequests++;true},{opened=true},messages::add))
            assertEquals(0,pageRequests)
        }
    }

    @Test fun unavailableSourceKeepsSnapshotAndCanRetryExactPage()=runBlocking {
        withTimeout(5_000){
            val frozen=sources("original-page")
            val state=StudyNavigationState(this){_,_->frozen}
            state.loadCardSources(card("card"),false)
            val source=checkNotNull(state.source);val owner=StudyNavigationState.CardOwner(null,"card","node")
            var opened=0;val messages=mutableListOf<String>()
            checkNotNull(state.openCardSource(owner,source,{owner},{false},{opened++},messages::add)).join()
            assertEquals(0,opened);assertEquals(listOf(StudyNavigationState.SOURCE_UNAVAILABLE),messages)
            assertSame(source,state.source);assertSame(frozen,state.sourceVersions?.second)
            checkNotNull(state.openCardSource(owner,source,{owner},{assertEquals("original-page",it.pageId);true},{opened++},messages::add)).join()
            assertEquals(1,opened);assertFalse(state.cardSourceOpening)
        }
    }

    @Test fun nodeRequiresExplicitChoiceForMultipleOrIncompleteSources()=runBlocking {
        withTimeout(5_000){
            for(frozen in listOf(sources("first","second"),sources("first",complete=false))){
                val state=StudyNavigationState(this){_,_->frozen}
                var choices=0;var opened=0;val messages=mutableListOf<String>()
                checkNotNull(state.openNodeSource(node(),owner(),::owner,{true},{true},{opened++;true},{choices++},messages::add)).join()
                assertEquals(1,choices);assertEquals(0,opened);assertTrue(messages.isEmpty())
                state.loadCardSources(card("card"),false)
                assertNull(state.source);assertSame(frozen,state.sourceVersions?.second)
            }
        }
    }

    @Test fun nodeLateReadNeedsCurrentOwnerRevisionAndDocument()=runBlocking {
        withTimeout(5_000){
            for(change in listOf("owner","revision","document","cancel")){
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                val state=StudyNavigationState(this){_,_->entered.complete(Unit);withContext(NonCancellable){release.await()};sources("page")}
                val origin=owner();var current=origin;var revisionCurrent=true;var documentReady=true;var opened=0
                val messages=mutableListOf<String>()
                val request=checkNotNull(state.openNodeSource(node(),origin,{current},{revisionCurrent},{documentReady},{opened++;true},{error("Unexpected chooser")},messages::add))
                entered.await()
                when(change){
                    "owner"->current=origin.copy(selectedNodeId="other-node")
                    "revision"->revisionCurrent=false
                    "document"->documentReady=false
                    "cancel"->state.cancelSourceNavigation()
                }
                release.complete(Unit);request.join()
                assertEquals(change,0,opened);assertTrue(change,messages.isEmpty());assertFalse(change,state.nodeSourceOpening)
            }
        }
    }
}
