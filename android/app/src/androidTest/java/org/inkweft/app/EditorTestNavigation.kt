package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
    if(onAllNodesWithTag("pen-width-dialog").fetchSemanticsNodes().isNotEmpty())return
    val highlighter=runCatching{onNodeWithTag("pen-kind-highlighter").assertIsOn()}.isSuccess
    val control=onNodeWithTag(if(highlighter)"pen-kind-highlighter"else"top-draw")
    if(!highlighter&&runCatching{control.assertIsOff()}.isSuccess)control.performClick()
    control.performClick()
    onNodeWithTag("pen-width-dialog").assertIsDisplayed()
}

internal fun ComposeTestRule.selectPen(kind:String){
    if(onAllNodesWithTag("close-pen-settings").fetchSemanticsNodes().isNotEmpty())closePenSettings()
    if(kind=="highlighter"){
        val node=onNodeWithTag("pen-kind-highlighter")
        if(runCatching{node.assertIsOff()}.isSuccess)node.performClick()
    }else{
        val pen=onNodeWithTag("top-draw")
        if(runCatching{pen.assertIsOff()}.isSuccess)pen.performClick()
        openCurrentPen();onNodeWithTag("pen-kind-menu").performClick()
        onNodeWithTag("pen-kind-$kind").performScrollTo().performClick();closePenSettings()
    }
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
internal fun ComposeTestRule.openBeautySettings(){openEditorAction("quick-beauty")}

internal fun ComposeTestRule.openEditorAction(tag:String){
    revealAction("toolbar-more")
    if(onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())onNodeWithTag("toolbar-more").performClick()
    val actual=if(tag=="add-page")"quick-add-page"else tag
    val actions=mapOf("add-page" to "add-page","object-shape" to "shape","page-objects" to "objects","object-sticker" to "sticker","top-area-erase" to "area","object-camera" to "camera","quick-finger" to "finger","quick-beauty" to "beauty")
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
    revealAction("quick-overview")
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
        tag in setOf("quick-overview","quick-settings","book-search","quick-fullscreen","quick-export","quick-timer","document-add-page","study-close","quick-readonly","exit-readonly","document-recall","document-associations","read-excerpts")&&onAllNodesWithTag("document-more").fetchSemanticsNodes().isNotEmpty()->{
            waitUntil(15_000){runCatching{onNodeWithTag("document-more").assertIsDisplayed().assertIsEnabled()}.isSuccess}
            onNodeWithTag("document-more").performClick()
        }
        tag in setOf("node-add-child","node-add-sibling","node-links")->onNodeWithTag("node-more").assertIsDisplayed().assertIsEnabled().performClick()
        tag in setOf("page-layers-open","page-whitespace-open","page-visible-share","page-annotation-open")->{
            onNodeWithTag("toolbar-more").assertIsDisplayed().assertIsEnabled().performClick()
            onNode(hasText("页面与批注") and hasAnyAncestor(isPopup())).assertIsDisplayed()
            onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        listOf("outline-rename-","outline-child-","outline-sibling-","outline-organize-","outline-focus-").any(tag::startsWith)&&onAllNodesWithTag("study-list").fetchSemanticsNodes().isNotEmpty()->{
            val prefix=listOf("outline-rename-","outline-child-","outline-sibling-","outline-organize-","outline-focus-").first(tag::startsWith)
            val selectTag="outline-actions-${tag.removePrefix(prefix)}"
            onNodeWithTag("study-list").performScrollToNode(hasTestTag(selectTag))
            onNodeWithTag(selectTag).assertIsDisplayed().assertIsEnabled().performClick()
            onNodeWithTag("study-list").performScrollToNode(hasTestTag(tag))
        }
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
        tag in setOf("quick-readonly","quick-fullscreen","quick-timer","quick-add-page","quick-export","quick-beauty","quick-finger","object-image","object-text","top-tags","top-excerpt","object-tape","favorite-pens-toggle")->onNodeWithTag("toolbar-more").performClick()
    }
}

internal fun ComposeTestRule.selectInboxCapture(){
    onNodeWithTag("top-excerpt").performClick();onNodeWithTag("top-excerpt").performClick()
    onNodeWithTag("capture-destination-inbox").performClick();onNodeWithContentDescription("关闭摘要笔").performClick()
}

/** Verified learning-directory input protocol shared by actual map-navigation tests. */
internal fun ComposeTestRule.openLearningMapTarget(mapId:String,query:String,beforeOpen:()->Unit={}){
    val application=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    fun awaitTag(tag:String){waitUntil(15_000){onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitForIdle()}
    awaitTag("learning-workbench")
    // Re-entry changes recent-row heights while the directory reloads. A lazy
    // descendant may exist before it is placed; position its owning item by
    // the actual widget key before reading/touching the descendant's bounds.
    val mapsKey = application.learningStore.read().widgets.single {
        it.definition == "org.inkweft/maps" && it.visible
    }.id
    val widgets = onNode(hasScrollToKeyAction() and hasAnyAncestor(hasTestTag("learning-workbench")))
    widgets.assertIsDisplayed().performScrollToKey(mapsKey)
    awaitTag("learning-map-search")
    onNodeWithTag("learning-map-search").assertIsDisplayed().performTextReplacement(query)
    val target = hasTestTag("learning-target-${mapId}") and hasAnyAncestor(hasTestTag("widget-maps"))
    val evidenceDirectory = application.getExternalFilesDir(null)
    var touchState = "No target placement yet"
    try {
        waitUntil(15_000) { onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        // Search replacement focuses the Dialog, whose OS keyboard/insets are not Compose idle.
        val window = (onNodeWithTag("learning-workbench").fetchSemanticsNode().root as ViewRootForTest).view.rootView
        fun windowReady(bounds: androidx.compose.ui.geometry.Rect? = null): Boolean {
            val insets = ViewCompat.getRootWindowInsets(window)
            val visible = android.graphics.Rect(); window.getWindowVisibleDisplayFrame(visible)
            touchState = "learningDialog=${System.identityHashCode(window)}, attached=${window.isAttachedToWindow}, focused=${window.hasWindowFocus()}, " +
                "laidOut=${window.isLaidOut}, layoutRequested=${window.isLayoutRequested}, ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
                "visible=$visible, row=$bounds, touch=${bounds?.center}"
            return window.isAttachedToWindow && window.hasWindowFocus() && window.isLaidOut && !window.isLayoutRequested &&
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == false && (bounds == null ||
                bounds.width > 0 && bounds.height > 0 && bounds.left >= visible.left && bounds.top >= visible.top &&
                bounds.right <= visible.right && bounds.bottom <= visible.bottom)
        }
        runOnIdle {
            window.findFocus()?.clearFocus()
            ViewCompat.getWindowInsetsController(window)?.hide(WindowInsetsCompat.Type.ime())
        }
        waitUntil("Learning Dialog keyboard is hidden and its window is laid out", 15_000) {
            runOnIdle { windowReady() }
        }
        widgets.performScrollToKey(mapsKey)
        onNode(target).performScrollTo()
        var previous: androidx.compose.ui.geometry.Rect? = null
        waitUntil("Learning map row has a stable, enabled physical touch target", 15_000) {
            runCatching {
                val node = onNode(target).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
                val bounds = androidx.compose.ui.geometry.Rect(node.positionOnScreen, node.boundsInRoot.size)
                val sameWindow = (node.root as? ViewRootForTest)?.view?.rootView === window
                val ready = runOnIdle { windowReady(bounds) } && sameWindow
                touchState += ", sameWindow=$sameWindow"
                (ready && bounds == previous).also { previous = bounds }
            }.getOrDefault(false)
        }
        beforeOpen()
        // One real touch only. A missing destination remains a failure, never a retry.
        onNode(target).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        awaitTag("study-map")
    } catch (error: Throwable) {
        // Save already observed bounds/window state before one direct OS capture. Do not call
        // Compose idle or fetch a fresh semantics tree from an exceptional navigation state.
        runCatching { java.io.File(evidenceDirectory, "ls58-learning-map-entry-failure.txt").writeText(touchState) }.onFailure(error::addSuppressed)
        runCatching {
            val bitmap = checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { java.io.File(evidenceDirectory, "ls58-learning-map-entry-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }.onFailure(error::addSuppressed)
        error.addSuppressed(AssertionError("Synthetic learning-map entry: $touchState")); throw error
    }
}
