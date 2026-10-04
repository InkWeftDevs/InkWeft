// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.ContentTransfer
import org.junit.*
import org.junit.Assert.*

/** Production outline → transform → original identity → explicit frozen source → real page. */
class StudyTransformNavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var h:SelectAwaitTestSupport
    @Before fun prepare(){h=SelectAwaitTestSupport(compose);h.captureSettings()}
    @After fun restore(){if(::h.isInitialized)h.closeAndRestoreSettings()}

    @Test fun mergeFromSelectedOutlineKeepsOriginalsAndRequiresAnExplicitSource(){
        val f=h.seed();h.tap("exit-readonly")
        val cards=runBlocking{h.app.study.cards(f.note.id).first()}
        val nodes=runBlocking{h.app.study.nodes(f.note.id).first()}
        val snapshots=listOf(f.card,f.secondCard).associateWith{ContentTransfer.hash(h.source(it).snapshot)}
        h.tap("study-direct-outline");h.tap("study-select-many")
        h.tap("outline-select-${f.node}");h.tap("outline-select-${f.secondNode}")
        h.tap("study-merge-selection");h.waitFor("transform-title")
        compose.onNodeWithTag("transform-title").performTextReplacement("明确保留两个来源的新卡")
        h.tap("transform-preview");h.waitFor("transform-retention-policy");h.tap("transform-cancel")
        assertEquals(cards,runBlocking{h.app.study.cards(f.note.id).first()})
        assertEquals(nodes,runBlocking{h.app.study.nodes(f.note.id).first()})
        h.tap("study-merge-selection");h.waitFor("transform-title")
        compose.onNodeWithTag("transform-title").performTextReplacement("明确保留两个来源的新卡")
        h.tap("transform-preview");h.tap("transform-confirm");h.waitFor("transform-done");h.tap("transform-done")
        val added=runBlocking{h.app.study.cards(f.note.id).first()}.single{it.id !in cards.map{card->card.id}}
        compose.onNodeWithTag("study-transform-result-${added.id}").assertExists()
        h.tap("study-card-${f.card}")
        compose.onNode(hasText(added.title) and hasAnyAncestor(hasTestTag("study-card-details"))).performScrollTo().performClick()
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-full-title").assertTextEquals(added.title)}.isSuccess}
        h.waitFor("frozen-card-sources")
        compose.onNodeWithTag("study-open-source").assertDoesNotExist()
        val sources=runBlocking{h.app.study.sources(added.id,added.revision)}
        assertTrue(sources.complete);assertEquals(2,sources.sources.size)
        assertEquals(snapshots.values.toSet(),sources.sources.map{ContentTransfer.hash(it.snapshot)}.toSet())
        val index=sources.sources.indexOfFirst{it.pageId==f.secondPage};assertTrue(index>=0)
        h.tap("frozen-source-$index");h.tap("study-open-source")
        compose.waitUntil(15_000){compose.runOnIdle{
            ViewModelProvider(compose.activity)["book-${f.note.id}",BookPagesViewModel::class.java].ui.value.selectedId==f.secondPage
        }}
        assertEquals(nodes,runBlocking{h.app.study.nodes(f.note.id).first()})
        assertEquals(cards,runBlocking{h.app.study.cards(f.note.id).first()}.filter{it.id!=added.id})
        snapshots.forEach{(id,hash)->assertEquals(hash,ContentTransfer.hash(h.source(id).snapshot))}
    }
}
