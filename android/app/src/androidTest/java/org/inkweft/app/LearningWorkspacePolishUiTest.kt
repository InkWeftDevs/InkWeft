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
