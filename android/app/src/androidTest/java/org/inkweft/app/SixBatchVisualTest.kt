// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
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
    private val app get()=compose.activity.application as InkWeftApplication
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(tag:String){compose.waitUntil(60_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun tap(tag:String){compose.revealAction(tag);waitFor(tag);val node=compose.onNodeWithTag(tag)
        runCatching{node.performScrollTo()};compose.waitUntil(60_000){runCatching{node.assertIsEnabled()}.isSuccess}
        node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()}
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
                if(compose.onAllNodesWithTag("exit-readonly").fetchSemanticsNodes().isNotEmpty())tap("exit-readonly")
                shot(f,layout,"01-default-tools")
                tap("top-draw");tap("pen-advanced");shot(f,layout,"02-advanced-pen");tap("close-pen-settings")
                tap("quick-study");tap("study-tab-2");waitFor("study-map")
                val node=f.manifest.getJSONObject("authoring").getString("boundNodeId");select(node)
                shot(f,layout,"03-map-selected");tap("study-direct-outline")
                compose.onNodeWithTag("study-list").performScrollToNode(hasTestTag("outline-row-$node"));shot(f,layout,"04-outline-selected")
                tap("outline-row-$node");waitFor("card-full-title");shot(f,layout,"05-long-card-title")
                waitFor("card-full-body");compose.onNodeWithTag("card-full-body").performScrollTo();compose.onNodeWithTag("card-full-body").assertTextContains("正文末尾定位标记",substring=true)
                shot(f,layout,"06-long-card-body")
                compose.onNodeWithTag("card-full-annotation").performScrollTo();compose.onNodeWithTag("card-full-annotation").assertTextContains("注释末尾定位标记",substring=true)
                shot(f,layout,"07-long-card-annotation")
                if(compose.onAllNodesWithTag("card-source-content").fetchSemanticsNodes().isEmpty())tap("card-source-section")
                waitFor("card-source-content");compose.onNodeWithTag("card-source-heading").performScrollTo();shot(f,layout,"08-long-card-source")
                tap("card-back");tap("study-close")
                compose.openOverviewGrid();compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-4"));tap("jump-page-4");tap("pages-directory-dialog-close")
                compose.waitUntil(60_000){app.navigationReady.value};tap("page-layers-open");waitFor("page-layers");shot(f,layout,"09-page-layers");back("page-layers")
                tap("page-whitespace-open");waitFor("document-whitespace-panel");shot(f,layout,"10-whitespace-expanded")
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
