// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Captures real production surfaces on the persisted full fixture. Pixels require separate review. */
class SixBatchVisualTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val nativeEvidence by lazy{SixBatchNativeEvidence(compose)}
    private val app get()=compose.activity.application as InkWeftApplication
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(tag:String){compose.waitUntil(60_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun tap(tag:String){compose.revealAction(tag);waitFor(tag);val node=compose.onNodeWithTag(tag)
        runCatching{node.performScrollTo()};compose.waitUntil(60_000){runCatching{node.assertIsEnabled()}.isSuccess}
        node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()}
    private fun fullyVisible(tag:String,container:String){
        val node=compose.onNodeWithTag(tag);node.assertIsDisplayed()
        val child=node.getUnclippedBoundsInRoot();val bounds=compose.onNodeWithTag(container).getUnclippedBoundsInRoot()
        assertTrue("$tag must be fully inside $container before capture",child.left>=bounds.left-1.dp&&child.right<=bounds.right+1.dp&&child.top>=bounds.top-1.dp&&child.bottom<=bounds.bottom+1.dp)
    }
    private fun back(tag:String){instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitUntil(30_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()}}
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
    private fun atDisplay(narrow:Boolean,action:()->Unit){
        val oldSize=Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity=Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont=shell("settings get system font_scale")
        val width=if(narrow)375 else 1440;val font=if(narrow)1.6f else 1f
        try{
            shell("wm size "+if(narrow)"750x1600"else"1440x2200");shell("wm density "+if(narrow)"320"else"160")
            shell("settings put system font_scale $font");compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000){val c=compose.activity.resources.configuration;abs(c.screenWidthDp-width)<=4&&abs(c.fontScale-font)<.02f}
            action()
        }finally{
            shell(if(oldSize==null)"wm size reset"else"wm size $oldSize")
            shell(if(oldDensity==null)"wm density reset"else"wm density $oldDensity")
            shell(if(oldFont=="null")"settings delete system font_scale"else"settings put system font_scale $oldFont")
            compose.activityRule.scenario.recreate()
        }
    }
    private fun shot(f:SixBatchFixture,layout:String,stage:String){
        if(stage=="10-whitespace-expanded"||stage=="11-whitespace-collapsed")nativeEvidence.awaitWhitespace(f,3)
        compose.waitForIdle();val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());val name="visual-$layout-$stage.png"
        try{File(f.root,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        val c=compose.activity.resources.configuration
        f.manifest.getJSONArray("visualCaptures").put(JSONObject().put("file",name).put("screenWidthDp",c.screenWidthDp).put("fontScale",c.fontScale.toDouble())
            .put("sha256",SixBatchFixture.sha(File(f.root,name))).put("review","PENDING_VISUAL_REVIEW"));f.save()
    }
    private fun map():MindMapView{
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){val v=queue.removeFirst();if(v is MindMapView&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)queue.add(v.getChildAt(i))}
        error("No actual map view is visible")
    }
    private fun select(node:String){
        compose.waitUntil(60_000){compose.runOnIdle{runCatching{map().nodeBounds(node)!=null}.getOrDefault(false)}}
        val point=compose.runOnIdle{map().focusNode(node);val b=checkNotNull(map().nodeBounds(node));Offset(b.centerX(),b.centerY())}
        compose.onNodeWithTag("study-map").performTouchInput{click(point)};compose.waitForIdle()
        compose.runOnIdle{assertEquals(node,map().selectedNodeId)}
    }
    @Test fun captureSameFullFixtureWideAndNarrowNativeWorkspace(){
        val f=SixBatchFixture.load(app);runBlocking{f.verify()}
        f.manifest.put("visualCaptures",JSONArray());f.save()
        val reading=app.getSharedPreferences("inkweft-reading",0);val readingKey="continuous-v20-${f.books[0]}"
        val hadReading=reading.contains(readingKey);val oldReading=reading.getBoolean(readingKey,true)
        try{for(narrow in listOf(false,true))atDisplay(narrow){
            val layout=if(narrow)"narrow"else"wide"
            runBlocking{f.step("capture-$layout-production-workspace-pending-pixel-review"){
                val note=checkNotNull(app.repository.read(f.books[0]))
                compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
                compose.singlePageEditor();compose.waitUntil(60_000){app.navigationReady.value}
                compose.openOverviewGrid();compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-1"))
                tap("jump-page-1");tap("pages-directory-dialog-close")
                nativeEvidence.awaitPage(f,0,continuous=false)
                if(compose.onAllNodesWithTag("exit-readonly").fetchSemanticsNodes().isNotEmpty())tap("exit-readonly")
                shot(f,layout,"01-default-tools")
                tap("top-draw");tap("pen-advanced");shot(f,layout,"02-advanced-pen");tap("close-pen-settings")
                tap("quick-study");tap("study-tab-2");waitFor("study-map")
                val node=f.manifest.getJSONObject("authoring").getString("boundNodeId");select(node)
                shot(f,layout,"03-map-selected");tap("study-direct-outline")
                compose.onNodeWithTag("study-list").performScrollToNode(hasTestTag("outline-row-$node"))
                fullyVisible("outline-organize-$node","study-list");shot(f,layout,"04-outline-selected")
                tap("outline-node-$node");waitFor("card-full-title");tap("card-jump-body")
                fullyVisible("card-full-title","card-reading-content");shot(f,layout,"05-long-card-title")
                tap("card-jump-body");compose.onNodeWithTag("card-reading-content").performTouchInput{swipeUp()}
                compose.onNodeWithTag("card-full-body").assertIsDisplayed().assertTextContains("正文末尾定位标记",substring=true)
                shot(f,layout,"06-long-card-body")
                tap("card-jump-annotation");fullyVisible("card-annotation-heading","card-reading-content")
                compose.onNodeWithTag("card-full-annotation").assertIsDisplayed().assertTextContains("注释末尾定位标记",substring=true)
                shot(f,layout,"07-long-card-annotation")
                waitFor("card-source-summary-0");tap("card-jump-source")
                compose.onNodeWithTag("card-source-summary-0").assertTextContains(note.title,substring=true).assertTextContains("第3页",substring=true)
                for(tag in listOf("card-source-heading","card-source-summary-0","card-source-section","study-view-snapshot"))fullyVisible(tag,"card-reading-content")
                compose.onNodeWithTag("study-open-source").assertIsDisplayed();shot(f,layout,"08-long-card-source")
                tap("card-back");tap("study-close")
                compose.openOverviewGrid();compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-4"));tap("jump-page-4");tap("pages-directory-dialog-close")
                nativeEvidence.awaitPage(f,3,continuous=false);tap("page-layers-open");waitFor("page-layers");fullyVisible("layer-heading-${UserLayers.DEFAULT_ID}","page-layers");shot(f,layout,"09-page-layers");back("page-layers")
                tap("page-whitespace-open");waitFor("document-whitespace-panel");compose.onNodeWithTag("floating-pen-case").assertDoesNotExist();fullyVisible("whitespace-title","document-whitespace-panel");shot(f,layout,"10-whitespace-expanded")
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("document-whitespace-panel")))
                    .performScrollToNode(hasTestTag("whitespace-collapse-${f.id("blank-collapsed")}"))
                compose.onNodeWithTag("whitespace-collapse-${f.id("blank-collapsed")}").assertTextEquals("展开")
                shot(f,layout,"11-whitespace-collapsed")
                tap("page-whitespace-open");tap("quick-study");tap("study-tab-2");waitFor("study-map");select(node)
                tap("node-more");tap("node-annotation-open");waitFor("bound-annotation")
                tap("annotation-region-collapse")
                compose.waitUntil(60_000){runBlocking{app.authoring.read(AuthoringScope.map(MapRef(f.books[0]))).state.regions.single().collapsed}}
                shot(f,layout,"12-bound-region-collapsed")
                tap("annotation-region-collapse")
                compose.waitUntil(60_000){runBlocking{!app.authoring.read(AuthoringScope.map(MapRef(f.books[0]))).state.regions.single().collapsed}}
                back("bound-annotation");tap("study-close");tap("back-library")
            }}
        }}finally{val e=reading.edit();if(hadReading)e.putBoolean(readingKey,oldReading)else e.remove(readingKey);check(e.commit())}
        runBlocking{f.verify()}
        f.manifest.put("nativeWorkspaceCapture","CAPTURED_PENDING_VISUAL_REVIEW").put("nativeRecallCapture","PENDING_B5_UI")
            .put("narrowLayoutMeaning","375dp available-window simulation; not a real tablet OS split-screen result");f.save()
    }
}
