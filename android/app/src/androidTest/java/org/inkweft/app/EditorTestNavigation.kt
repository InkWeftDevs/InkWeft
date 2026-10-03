package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Tests of single-sheet tools explicitly choose that mode; production defaults to continuous. */
internal fun ComposeTestRule.singlePageEditor(){
    waitUntil(15_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()||onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
    if(onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()){
        if(onAllNodesWithTag("quick-settings").fetchSemanticsNodes().isEmpty()){
            waitUntil(15_000){runCatching{onNodeWithTag("document-more").assertIsDisplayed().assertIsEnabled()}.isSuccess}
            onNodeWithTag("document-more").performClick()
        }
        waitUntil(15_000){runCatching{onNodeWithTag("quick-settings").assertIsEnabled()}.isSuccess}
        onNodeWithTag("quick-settings").performClick()
        onNodeWithTag("continuous-setting").performScrollTo().performClick()
        onNodeWithTag("document-settings-dialog-close").performClick()
        waitUntil(10_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()}
    }
}
internal fun ComposeTestRule.openCurrentPen(){
    if(onAllNodesWithTag("pen-kind-pen").fetchSemanticsNodes().isEmpty())onNodeWithTag("case-collapse").performClick()
    val active=org.inkweft.core.InkPen.entries.firstOrNull{runCatching{onNodeWithTag("pen-kind-${it.name.lowercase()}").assertIsOn()}.isSuccess}
    val node=onNodeWithTag("pen-kind-${(active?:org.inkweft.core.InkPen.PEN).name.lowercase()}")
    if(active==null)node.performScrollTo().performClick()
    node.performScrollTo().performClick()
}

internal fun ComposeTestRule.selectPen(kind:String){
    if(onAllNodesWithTag("close-pen-settings").fetchSemanticsNodes().isNotEmpty())closePenSettings()
    if(onAllNodesWithTag("pen-kind-$kind").fetchSemanticsNodes().isEmpty())onNodeWithTag("case-collapse").performClick()
    val node=onNodeWithTag("pen-kind-$kind");if(runCatching{node.assertIsOff()}.isSuccess)node.performScrollTo().performClick()
}
/** Observe the actual canvas state; stroke totals are no longer user-facing UI. */
internal fun SemanticsNodeInteraction.assertInkCount(expected:Int):SemanticsNodeInteraction {
    var count:Int?=null
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        fun find(v:android.view.View):InkCanvasView?{
            if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
        count=a?.window?.decorView?.let(::find)?.displayedStrokeCount
    }
    org.junit.Assert.assertEquals(expected,count);return this
}

internal fun ComposeTestRule.closePenSettings(){onNodeWithTag("close-pen-settings").performScrollTo().performClick()}
internal fun ComposeTestRule.openBeautySettings(){onNodeWithTag("auto-beauty-toggle").performScrollTo().performClick()}

internal fun ComposeTestRule.openEditorAction(tag:String){
    revealAction("toolbar-more")
    if(onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())onNodeWithTag("toolbar-more").performClick()
    val actual=if(tag=="add-page")"quick-add-page"else tag
    val actions=mapOf("add-page" to "add-page","object-shape" to "shape","page-objects" to "objects","object-sticker" to "sticker","top-area-erase" to "area","object-camera" to "camera","quick-finger" to "finger")
    if(onAllNodesWithTag(actual).fetchSemanticsNodes().isEmpty()){
        val action=actions[tag]?:error("Unknown hidden action $tag")
        onNodeWithTag("toolbar-customize").performClick()
        onNodeWithTag("toolbar-visible-$action").performScrollTo().performClick()
        onNodeWithTag("toolbar-done").performClick()
    }
    if(onAllNodesWithTag(actual).fetchSemanticsNodes().isEmpty())onNodeWithTag("toolbar-more").performClick()
    val node=onNodeWithTag(actual)
    if(runCatching{node.assertIsDisplayed()}.isFailure)node.performScrollTo()
    node.assertIsDisplayed().performClick()
}

internal fun ComposeTestRule.waitForSavedInk(){waitUntil(15000){
    val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    app.navigationReady.value&&onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()
}}
internal fun ComposeTestRule.openOverviewGrid(){
    onNodeWithTag("quick-overview").performClick()
    if(onAllNodesWithTag("page-grid").fetchSemanticsNodes().isEmpty())onNodeWithTag("overview-layout").performClick()
}
/** Fixture framing for pixel assertions; the editor no longer has a permanent fit button. */
internal fun ComposeTestRule.frameCanvasFixture(content:Boolean=false){
    waitForSavedInk()
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{
        val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
        fun find(v:android.view.View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        checkNotNull(find(a.window.decorView)).let{if(content)it.fitContent()else it.fitPage()}
    }
    waitForIdle()
}
internal fun ComposeTestRule.pinchCanvasOut(){
    onNodeWithTag("ink-surface").performTouchInput{
        val y=height*.55f;val x=width*.5f
        down(0,androidx.compose.ui.geometry.Offset(x-width*.12f,y));down(1,androidx.compose.ui.geometry.Offset(x+width*.12f,y))
        for(i in 1..8){val d=width*(.12f+i*.008f);moveTo(0,androidx.compose.ui.geometry.Offset(x-d,y),16);moveTo(1,androidx.compose.ui.geometry.Offset(x+d,y),16)}
        up(0);up(1)
    };waitForIdle()
}

internal fun SemanticsNodeInteraction.assertSavedInkCount(expected:Int):SemanticsNodeInteraction {
    val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    org.junit.Assert.assertTrue("Ink is not yet durably settled",app.navigationReady.value)
    return assertInkCount(expected)
}
internal fun ComposeTestRule.assertCurrentPage(text:String){
    var actual=""
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{
        val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
        val provider=androidx.lifecycle.ViewModelProvider(a);val book=checkNotNull(provider[NotebookViewModel::class.java].ui.value.selectedId)
        val pages=provider["book-$book",BookPagesViewModel::class.java].ui.value
        actual="第 ${pages.pages.first{it.id==pages.selectedId}.position+1} / ${pages.pages.size} 页"
    }
    org.junit.Assert.assertEquals(text,actual)
}

internal fun ComposeTestRule.revealAction(tag:String){
    if(onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())return
    when {
        (tag.startsWith("study-card-")||tag.startsWith("outline-"))&&onAllNodesWithTag("study-list").fetchSemanticsNodes().isNotEmpty()->onNodeWithTag("study-list").performScrollToNode(hasTestTag(tag))
        tag=="toolbar-customize"->onNodeWithTag("toolbar-more").performClick()
        tag.startsWith("study-tab-")||tag.startsWith("study-fit-")||tag in setOf("study-new-map","study-insert-map","study-save-template","study-add-card","study-expand-all","study-focus-all","study-collapse-all","study-arrange")->{
            if(onAllNodesWithTag("map-menu").fetchSemanticsNodes().isEmpty()){
                // Selecting a map starts a repository load that is not covered by Compose idle.
                waitUntil("study-management is visible and enabled before revealing $tag",15_000){
                    runCatching{onNodeWithTag("study-management").assertIsDisplayed().assertIsEnabled()}.isSuccess
                }
                onNodeWithTag("study-management").assertIsDisplayed().assertIsEnabled().performTouchInput{click()}
            }
            waitUntil("map-menu is visible before revealing $tag",15_000){
                runCatching{onNodeWithTag("map-menu").assertIsDisplayed()}.isSuccess
            }
            if(onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())return
            val group=if(tag.startsWith("study-tab-")||tag.startsWith("study-fit-"))0 else if(tag in setOf("study-insert-map","study-save-template"))2 else 1
            val groupTag="map-menu-group-$group"
            waitUntil("$groupTag exists before revealing $tag",15_000){
                onAllNodesWithTag(groupTag).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(groupTag).performScrollTo().assertIsDisplayed().assertIsEnabled().performTouchInput{click()}
            waitUntil("$tag exists after choosing $groupTag",15_000){onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        }
        tag.startsWith("width-preset-")||tag.startsWith("pencil-hardness-")->onNodeWithTag("pen-advanced").performScrollTo().performClick()
        tag.startsWith("pen-color-")->onNodeWithTag("pen-basic").performScrollTo().performClick()
        tag in setOf("quick-readonly","quick-fullscreen","quick-timer","quick-add-page","quick-export","quick-beauty","quick-finger","object-image","object-text","top-tags")->onNodeWithTag("toolbar-more").performClick()
    }
}

internal fun ComposeTestRule.selectInboxCapture(){
    onNodeWithTag("top-excerpt").performClick();onNodeWithTag("top-excerpt").performClick()
    onNodeWithTag("capture-destination-inbox").performClick();onNodeWithContentDescription("关闭摘要笔").performClick()
}
