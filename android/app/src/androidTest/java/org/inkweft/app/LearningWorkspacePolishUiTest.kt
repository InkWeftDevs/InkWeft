// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
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
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
    private fun atNarrow(action:()->Unit){
        val oldSize=Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity=Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont=shell("settings get system font_scale")
        try{
            shell("wm size 750x1600");shell("wm density 320");shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000){val c=compose.activity.resources.configuration;kotlin.math.abs(c.screenWidthDp-375)<=4&&kotlin.math.abs(c.fontScale-1.6f)<.02f}
            action()
        }finally{
            shell(if(oldSize==null)"wm size reset"else"wm size $oldSize")
            shell(if(oldDensity==null)"wm density reset"else"wm density $oldDensity")
            shell(if(oldFont=="null")"settings delete system font_scale"else"settings put system font_scale $oldFont")
            compose.activityRule.scenario.recreate()
        }
    }
    private fun fullyVisible(tag:String,container:String){
        val node=compose.onNodeWithTag(tag);node.assertIsDisplayed()
        val child=node.getUnclippedBoundsInRoot();val bounds=compose.onNodeWithTag(container).getUnclippedBoundsInRoot()
        assertTrue("$tag stays entirely inside $container",child.left>=bounds.left-1.dp&&child.right<=bounds.right+1.dp&&child.top>=bounds.top-1.dp&&child.bottom<=bounds.bottom+1.dp)
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
        h.openBody(f);h.tapFooter("card-jump-body")
        fullyVisible("card-full-title","card-reading-content")
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
        for(tag in listOf("card-source-heading","card-source-summary-0","card-source-section","study-view-snapshot"))fullyVisible(tag,"card-reading-content")
        compose.onNodeWithTag("card-source-summary-0").assertTextContains(f.note.title,substring=true).assertTextContains("第2页",substring=true)
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
        val f=h.seed();h.tap("exit-readonly");h.tap("study-close")
        val toolbarBounds=compose.onNodeWithTag("editor-toolbar").getUnclippedBoundsInRoot()
        assertTrue("Writing tools occupy one row",toolbarBounds.bottom-toolbarBounds.top<=64.dp)
        compose.onNodeWithTag("floating-pen-case").assertDoesNotExist()
        compose.onNodeWithTag("quick-study").assertIsDisplayed().assertIsEnabled()
        h.tap("page-layers-open");h.waitFor("page-layers")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000){compose.onAllNodesWithTag("page-layers").fetchSemanticsNodes().isEmpty()}
        compose.selectPen("pencil")
        val store=PenWidthStore(h.app,"inkweft-pen-widths-book-${f.note.id}")
        assertEquals(InkPen.PENCIL,store.readKinds()[0])
        compose.activityRule.scenario.recreate();h.waitFor("top-draw")
        assertEquals(InkPen.PENCIL,store.readKinds()[0]);screenshot("polish-default-tools.png")
        h.tap("read-excerpts");h.waitFor("excerpt-panel")
        val header=compose.onNodeWithTag("document-toolbar").fetchSemanticsNode().boundsInRoot
        val panel=compose.onNodeWithTag("excerpt-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Excerpt panel starts below document navigation",panel.top>=header.bottom-1f)
        screenshot("polish-excerpt-header-clear.png")
    }

    @Test fun narrowLargeTextCardSectionShortcutsRevealReadableContent()=atNarrow{
        longCardShortcutsReachAnnotationAndSourcesWithFixedReturnActions()
    }

    @Test fun narrowLargeTextOutlineActionsWrapWithoutHorizontalHunting()=atNarrow{
        val f=h.seed();h.tap("exit-readonly");h.tap("study-direct-outline");h.tap("outline-actions-${f.node}")
        compose.onNodeWithTag("study-list").performScrollToNode(hasTestTag("outline-row-${f.node}"))
        for(action in listOf("rename","child","sibling","organize","focus")){
            val tag="outline-$action-${f.node}";fullyVisible(tag,"study-list")
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        screenshot("polish-narrow-outline-actions.png")
        h.tap("outline-organize-${f.node}");compose.onNodeWithText("顺序与层级").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        h.waitFor("outline-context-actions")
    }

    @Test fun narrowLargeTextWhitespaceAndLayersKeepControlsClearAndPenPreference()=atNarrow{
        val f=h.seed();h.tap("exit-readonly");h.tap("study-close")
        compose.selectPen("pencil")
        val store=PenWidthStore(h.app,"inkweft-pen-widths-book-${f.note.id}")
        val penBefore=store.readPen(InkPen.PENCIL)
        val prefs=h.app.getSharedPreferences("inkweft-editor",0)
        val caseBefore=listOf("case-x","case-y","case-collapsed").associateWith{prefs.all[it]}
        val viewport=h.paperViewport()
        fullyVisible("ink-select","editor-tool-scroll")
        h.tap("page-layers-open")
        fullyVisible("layer-select-${UserLayers.DEFAULT_ID}","page-layers")
        compose.onNodeWithTag("current-writable-layer").assertIsDisplayed()
        h.tap("layer-help")
        compose.onNodeWithTag("layer-help-content").performScrollTo().assertIsDisplayed().assertTextContains("隐藏仅影响显示，锁定限制编辑",substring=true)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000){compose.onAllNodesWithTag("page-layers").fetchSemanticsNodes().isEmpty()}
        h.tap("page-whitespace-open");h.waitFor("document-whitespace-panel")
        compose.onNodeWithTag("floating-pen-case").assertDoesNotExist()
        fullyVisible("whitespace-title","document-whitespace-panel")
        fullyVisible("whitespace-original","document-whitespace-panel")
        compose.onNodeWithTag("whitespace-add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val toolbar=compose.onNodeWithTag("editor-toolbar").getUnclippedBoundsInRoot()
        val header=compose.onNodeWithTag("whitespace-header").getUnclippedBoundsInRoot()
        assertTrue("Whitespace begins immediately below the one toolbar inset",header.top>=toolbar.bottom&&header.top<=toolbar.bottom+2.dp)
        assertEquals(caseBefore,listOf("case-x","case-y","case-collapsed").associateWith{prefs.all[it]})
        screenshot("polish-narrow-whitespace-clear.png")
        h.tap("whitespace-original");h.waitFor("ink-surface")
        compose.onNodeWithTag("top-draw").assertIsOn()
        assertEquals(penBefore,store.readPen(InkPen.PENCIL))
        assertEquals(caseBefore,listOf("case-x","case-y","case-collapsed").associateWith{prefs.all[it]})
        assertEquals(viewport,h.paperViewport())
    }

    @Test fun compactMapDraftBlocksNativePageWritingAndSurvivesRecreation(){
        val f=h.seed();h.tap("exit-readonly")
        val own=h.authorStamp(f.note.id);val other=h.authorStamp(f.unrelated.id);val source=h.source(f.card)
        h.tap("node-rename")
        val draft="云端未保存主题：保持原文与来源"
        compose.onNodeWithTag("node-title-input").performTextReplacement(draft)
        compose.waitUntil(15_000){compose.runOnUiThread{h.lock(f.note.id).hasDraft.value}}
        compose.onNodeWithTag("top-draw").assertIsNotEnabled()
        compose.onNodeWithTag("study-close").assertIsNotEnabled()
        compose.runOnIdle{
            val canvas=h.native<InkCanvasView>();assertFalse(canvas.allowInput)
            val viewport=canvas.snapshotViewport();val down=android.os.SystemClock.uptimeMillis()
            val pointer=android.view.MotionEvent.PointerProperties().apply{id=0;toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS}
            listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP).forEachIndexed{index,action->
                val point=android.view.MotionEvent.PointerCoords().apply{x=canvas.width*.3f+index*20f;y=canvas.height*.5f;pressure=.5f}
                val event=android.view.MotionEvent.obtain(down,down+index*20L,action,1,arrayOf(pointer),arrayOf(point),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
                try{canvas.dispatchTouchEvent(event)}finally{event.recycle()}
            }
            assertEquals(viewport,canvas.snapshotViewport())
            assertFalse(h.lock(f.note.id).request(true))
        }
        h.assertAuthors(f,own,other,source)
        compose.activityRule.scenario.recreate();h.waitFor("node-title-input")
        compose.onNodeWithTag("node-title-input").assertTextContains(draft)
        compose.runOnIdle{
            assertEquals(f.mapId,h.study(f.note.id).mapId.value)
            assertEquals(f.node,h.study(f.note.id).selectedByMap[f.mapId])
            assertTrue(h.lock(f.note.id).hasDraft.value);assertFalse(h.native<InkCanvasView>().allowInput)
        }
        h.assertAuthors(f,own,other,source)
        h.tap("node-title-cancel")
        compose.waitUntil(15_000){compose.runOnUiThread{!h.lock(f.note.id).hasDraft.value&&h.app.navigationReady.value}}
        compose.runOnIdle{assertTrue(h.native<InkCanvasView>().allowInput)}
        h.assertAuthors(f,own,other,source)
    }

    @Test fun compactLayersKeepBrowsingSeparateFromWritingAndUndoDeletion()=atNarrow{
        val f=h.seed();h.tap("exit-readonly");h.tap("study-close")
        val scope=AuthoringScope.page(f.note.id,f.note.id)
        val reference=h.authorStamp(f.unrelated.id)
        val viewport=h.paperViewport()
        fun layers()=runBlocking{h.app.authoring.read(scope).state.layers}
        fun saved(predicate:(UserLayers)->Boolean){
            compose.waitUntil(15_000){predicate(layers())&&runCatching{compose.onNodeWithTag("layer-add").assertIsEnabled()}.isSuccess}
        }
        h.tap("page-layers-open");h.tap("layer-add")
        saved{it.layers.size==2}
        val added=checkNotNull(layers().currentId)
        assertNotEquals(UserLayers.DEFAULT_ID,added)
        for(tag in listOf("layer-visible-$added","layer-lock-$added","layer-more-$added")){
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            fullyVisible(tag,"page-layers")
        }
        h.tap("layer-visible-$added");saved{it.currentId==null&&!it.layers.last().visible}
        h.tap("layer-select-$added")
        assertNull("Inspecting hidden data must not resume writing",layers().currentId)
        h.tap("layer-visible-$added");saved{it.layers.last().visible}
        assertNull("Showing a layer alone must not resume writing",layers().currentId)
        h.tap("layer-select-$added");saved{it.currentId==added}
        h.tap("layer-lock-$added");saved{it.currentId==null&&it.layers.last().locked}
        h.tap("layer-select-${UserLayers.DEFAULT_ID}");saved{it.currentId==UserLayers.DEFAULT_ID}
        val locked=PageAuthoringCodec.fingerprint(runBlocking{h.app.authoring.read(scope).state})
        h.tap("layer-select-$added")
        assertEquals(UserLayers.DEFAULT_ID,layers().currentId)
        assertEquals(locked,PageAuthoringCodec.fingerprint(runBlocking{h.app.authoring.read(scope).state}))
        h.tap("layer-lock-$added");saved{!it.layers.last().locked}
        h.tap("layer-select-$added");saved{it.currentId==added}
        h.tap("layer-more-$added");h.tap("layer-delete-$added")
        compose.onNodeWithText("删除空层").performClick();saved{it.layers.size==1}
        h.tap("layer-undo");saved{it.layers.size==2&&it.currentId==added}
        screenshot("editor-ui-layers-narrow-undo.png")
        assertEquals(reference,h.authorStamp(f.unrelated.id))
        assertEquals(viewport,h.paperViewport())
    }
}
