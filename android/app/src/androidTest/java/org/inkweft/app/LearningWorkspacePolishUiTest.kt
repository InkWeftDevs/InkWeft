// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.CardReuseKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real MainActivity/Room checks; screenshots still require a separate pixel review. */
class LearningWorkspacePolishUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var h:SelectAwaitTestSupport
    private var savedCase:Map<String,Any?> = emptyMap()
    @Before fun prepare(){
        h=SelectAwaitTestSupport(compose);h.captureSettings()
        val prefs=h.app.getSharedPreferences("inkweft-editor",0)
        savedCase=listOf("case-x","case-y","case-collapsed").associateWith{prefs.all[it]}
        check(prefs.edit().remove("case-collapsed").commit())
    }
    @After fun restore(){
        try{if(::h.isInitialized)h.closeAndRestoreSettings()}finally{
            if(::h.isInitialized){
                val editor=h.app.getSharedPreferences("inkweft-editor",0).edit()
                savedCase.forEach{(key,value)->when(value){is Float->editor.putFloat(key,value);is Boolean->editor.putBoolean(key,value);else->editor.remove(key)}}
                check(editor.commit())
            }
        }
    }
    private fun screenshot(name:String){
        compose.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(h.app.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun y(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test fun detailReadsTitleBodyAnnotationAndSourceBeforeEditingControls(){
        val f=h.seed();val annotation="独立个人注释，保持原有正文与来源。"
        runBlocking{h.app.knowledge.submit(KnowledgeCommand(UUID.randomUUID().toString(),f.note.id,UUID.randomUUID().toString(),0,
            KnowledgeData.CardPresentation(f.card,annotation,CardTint.GREEN,CardTint.ROSE)))}
        val before=runBlocking{h.app.study.cards(f.note.id).first()};val snapshot=h.source(f.card).snapshot.copyOf()
        h.openBody(f);h.waitFor("card-source-summary-0")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)}.isSuccess}
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)
        compose.onNodeWithTag("card-source-summary-0").assertTextContains("第2页",substring=true)
        val tags=listOf("card-full-title","card-full-body","card-full-annotation","card-source-heading","study-edit-card")
        tags.zipWithNext().forEach{(beforeTag,afterTag)->assertTrue("$beforeTag precedes $afterTag",y(beforeTag)<y(afterTag))}
        screenshot("polish-card-reading-order.png")
        compose.onNodeWithTag("study-edit-card").performScrollTo().assertIsNotEnabled()
        h.tapFooter("card-back")
        assertEquals(before,runBlocking{h.app.study.cards(f.note.id).first()});assertArrayEquals(snapshot,h.source(f.card).snapshot)
    }

    @Test fun longCardShortcutsReachAnnotationAndSourcesWithFixedReturnActions(){
        val original=h.seed()
        val body=buildString{repeat(400){append("正文第${it+1}段：先确定样本空间，再计算交集；完整正文保持可阅读，不折叠也不缩小字号。\n")}}.take(12_000)
        val annotation=buildString{repeat(200){append("注释第${it+1}段：这是独立个人理解，不能替换摘要正文或摘录时快照。\n")}}.take(4_000)
        val f=original.copy(body=body)
        runBlocking{
            val card=h.app.study.cards(f.note.id).first().single{it.id==f.card}
            h.app.study.submit(StudyCommand(UUID.randomUUID().toString(),f.note.id,StudyAction.EDIT,mapId=f.mapId,
                cardId=f.card,expectedRevision=card.revision,title=card.title,body=body))
            h.app.knowledge.submit(KnowledgeCommand(UUID.randomUUID().toString(),f.note.id,UUID.randomUUID().toString(),0,
                KnowledgeData.CardPresentation(f.card,annotation,CardTint.GREEN,CardTint.ROSE)))
        }
        val authors=h.authorStamp(f.note.id);val unrelated=h.authorStamp(f.unrelated.id);val source=h.source(f.card)
        h.openBody(f)
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)}.isSuccess}
        compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        compose.onNodeWithTag("card-annotation-heading").assertIsNotDisplayed()
        compose.onNodeWithTag("card-source-heading").assertIsNotDisplayed()
        val footer=compose.onNodeWithTag("card-back").getUnclippedBoundsInRoot()
        val graph=h.graph(f)
        listOf("body" to "正文","annotation" to "个人注释","source" to "来源").forEach{(section,label)->
            compose.onNodeWithTag("card-jump-$section").assertIsDisplayed().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertContentDescriptionEquals("定位到$label")
        }
        screenshot("polish-long-card-section-shortcuts.png")
        h.tapFooter("card-jump-annotation")
        compose.onNodeWithTag("card-annotation-heading").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("card-full-annotation").assertIsDisplayed().assertTextEquals(annotation)
        val readingTop=compose.onNodeWithTag("card-reading-content").getUnclippedBoundsInRoot().top
        val annotationTop=compose.onNodeWithTag("card-annotation-heading").getUnclippedBoundsInRoot().top
        assertTrue("Jump reveals the beginning of the long annotation",annotationTop>=readingTop&&annotationTop<=readingTop+2.dp)
        screenshot("polish-long-card-annotation-anchor.png")
        h.tapFooter("card-jump-source")
        compose.onNodeWithTag("card-source-heading").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("card-source-summary-0").assertIsDisplayed().assertTextContains("摘录时快照",substring=true)
        compose.onNodeWithTag("card-source-actions").assertIsDisplayed()
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().assertIsEnabled()
        assertEquals("Section jumps never move the fixed return action",footer,compose.onNodeWithTag("card-back").getUnclippedBoundsInRoot())
        screenshot("polish-long-card-source-anchor.png")
        // Exercise semantic activation and a superseded scroll, not only settled touch taps.
        compose.mainClock.autoAdvance=false
        try{
            compose.onNodeWithTag("card-jump-annotation").performClick()
            compose.onNodeWithTag("card-jump-source").performClick()
            compose.onNodeWithTag("card-jump-body").performClick()
            compose.mainClock.advanceTimeBy(2_000)
        }finally{compose.mainClock.autoAdvance=true}
        compose.waitForIdle()
        compose.onNodeWithTag("card-full-title").assertIsDisplayed()
        compose.onNodeWithTag("card-body-heading").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        h.assertReadOnly(f);h.assertNoRevealOrError(f,f.note.id)
        h.tapFooter("card-back")
        assertEquals(graph,h.graph(f));h.assertAuthors(f,authors,unrelated,source)
        h.openBody(f,second=true)
        compose.onNodeWithTag("card-full-title").assertIsDisplayed()
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
    }

    @Test fun compactSourceActionsKeepFrozenExcerptAndOriginalPageSeparate(){
        val f=h.seed();val source=h.source(f.card)
        val authors=h.authorStamp(f.note.id);val unrelated=h.authorStamp(f.unrelated.id)
        h.openBody(f);h.tapFooter("card-jump-source")
        val group=compose.onNodeWithTag("card-source-actions").getUnclippedBoundsInRoot()
        val preview=compose.onNodeWithTag("card-source-section")
        val snapshot=compose.onNodeWithTag("study-view-snapshot")
        for(action in listOf(preview,snapshot))action.assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assert(hasAnyAncestor(hasTestTag("card-source-actions")))
        preview.assertTextEquals("查看来源");snapshot.assertTextEquals("查看完整摘录 · 摘录时快照")
        val previewBounds=preview.getUnclippedBoundsInRoot();val snapshotBounds=snapshot.getUnclippedBoundsInRoot()
        assertTrue("Source actions have separate touch targets",previewBounds.right<=snapshotBounds.left||previewBounds.bottom<=snapshotBounds.top)
        if(group.right-group.left>=(previewBounds.right-previewBounds.left)+(snapshotBounds.right-snapshotBounds.left)+4.dp)
            assertEquals("Source actions share one row when they fit",previewBounds.top,snapshotBounds.top)
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().assertTextEquals("回原文")
            .assert(hasAnyAncestor(hasTestTag("card-source-actions")).not())
        screenshot("polish-card-compact-source-actions.png")
        h.tapFooter("card-source-section");h.waitFor("card-source-content")
        h.tapFooter("study-view-snapshot");h.waitFor("study-snapshot-canvas")
        compose.onNodeWithText("摘录时快照 · 只读").assertIsDisplayed()
        h.assertCurrent(f,f.note.id)
        h.tapFooter("study-snapshot-close")
        compose.onNodeWithTag("study-card-details").assertIsDisplayed()
        h.assertNoRevealOrError(f,f.note.id);h.assertAuthors(f,authors,unrelated,source)
        h.tapFooter("study-open-source");h.assertFocusedSource(f,source)
        h.assertAuthors(f,authors,unrelated,source)
    }

    @Test fun independentCopyUsesOriginalNotebookAndPageMetadata(){
        val f=h.seed()
        val copy=runBlocking{h.app.study.reuse.submit(h.app.study.reuse.prepare(f.card,1,f.unrelated.id,CardReuseKind.INDEPENDENT_COPY))}
        h.tap("study-close");h.tap("back-library")
        compose.runOnIdle{h.notebook().select(f.unrelated)}
        compose.singlePageEditor();compose.waitForSavedInk()
        h.tap("quick-study");h.tap("study-tab-0");h.tap("study-card-$copy")
        h.waitFor("card-source-summary-0")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-source-summary-0").assertTextContains(f.note.title,substring=true)}.isSuccess}
        compose.onNodeWithTag("card-source-summary-0").assertTextContains("第2页",substring=true)
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
    }

    @Test fun outlineExposesOneContextGroupAndKeepsFullDetailsAndFoldTargets(){
        val f=h.seed();h.tap("study-direct-outline")
        h.tap("outline-actions-${f.node}")
        compose.onAllNodesWithTag("outline-context-actions").assertCountEquals(1)
        compose.onNodeWithTag("outline-row-${f.node}").assertIsSelected()
        compose.onNodeWithTag("outline-context-actions").assert(hasAnyAncestor(hasTestTag("outline-row-${f.node}")))
        compose.onNodeWithTag("outline-rename-${f.node}").assertIsNotEnabled()
        compose.onNodeWithTag("outline-rename-${f.secondNode}").assertDoesNotExist()
        compose.onNodeWithTag("outline-fold-${f.node}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        h.tap("outline-actions-${f.secondNode}")
        compose.onAllNodesWithTag("outline-context-actions").assertCountEquals(1)
        compose.onNodeWithTag("outline-rename-${f.node}").assertDoesNotExist()
        compose.onNodeWithTag("outline-row-${f.node}").assertIsNotSelected()
        compose.onNodeWithTag("outline-row-${f.secondNode}").assertIsSelected()
        compose.onNodeWithTag("outline-context-actions").assert(hasAnyAncestor(hasTestTag("outline-row-${f.secondNode}")))
        compose.onNodeWithTag("outline-rename-${f.secondNode}").assertIsNotEnabled()
        compose.onNodeWithTag("outline-actions-${f.secondNode}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        screenshot("polish-outline-one-action-group.png")
        h.tap("outline-node-${f.secondNode}")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.secondBody)
    }

    @Test fun oneRowToolsKeepNamedPageMenuPenPreferenceAndHeaderUncovered(){
        h.seed();h.tap("exit-readonly");h.tap("study-close")
        val toolbarBounds=compose.onNodeWithTag("editor-toolbar").getUnclippedBoundsInRoot()
        assertTrue("Writing tools occupy one row",toolbarBounds.bottom-toolbarBounds.top<=64.dp)
        compose.onNodeWithTag("floating-pen-case").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        compose.onNodeWithTag("page-layers-open").assertDoesNotExist()
        compose.onNodeWithTag("quick-study").assertIsDisplayed().assertIsEnabled()
        h.tap("page-layers-open");h.waitFor("page-layers")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000){compose.onAllNodesWithTag("page-layers").fetchSemanticsNodes().isEmpty()}
        h.tap("case-collapse")
        compose.onNodeWithTag("pen-kind-pencil").assertExists()
        compose.activityRule.scenario.recreate();h.waitFor("pen-kind-pencil")
        assertFalse(h.app.getSharedPreferences("inkweft-editor",0).getBoolean("case-collapsed",true))
        h.tap("case-collapse");screenshot("polish-default-tools.png")
        h.tap("read-excerpts");h.waitFor("excerpt-panel")
        val header=compose.onNodeWithTag("document-toolbar").fetchSemanticsNode().boundsInRoot
        val panel=compose.onNodeWithTag("excerpt-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Excerpt panel starts below document navigation",panel.top>=header.bottom-1f)
        compose.onNodeWithTag("document-associations").assertIsDisplayed()
        screenshot("polish-excerpt-header-clear.png")
    }
}
