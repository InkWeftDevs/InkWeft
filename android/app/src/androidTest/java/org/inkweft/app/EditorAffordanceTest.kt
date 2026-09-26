// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual emulator controls and preference files; not proof of the user's GPU or Pencil3. */
class EditorAffordanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun saved(count:Int){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true).assertTextContains("$count 笔",substring=true)}.isSuccess}}
    private fun create():String{
        ready();val title="AFFORDANCE-"+UUID.randomUUID().toString().take(8)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("cover-choice-content").performScrollTo().performClick();compose.onNodeWithTag("create-note").performClick();saved(0)
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false};compose.waitForIdle()
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
    }
    private fun draw(){compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.2f,height*.3f),Offset(width*.48f,height*.5f),200)}}
    private fun shot(name:String){compose.waitForIdle();val bmp=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}}
    private fun shell(command:String){InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
    private fun previews():Set<Int>{
        val found=mutableSetOf<Int>()
        fun collect(v:View){if(v is InkCanvasView && v.preview)found.add(System.identityHashCode(v));if(v is ViewGroup)for(i in 0 until v.childCount)collect(v.getChildAt(i))}
        collect(compose.activity.window.decorView);return found
    }
    @Test fun compactDrawerDoesNotReplaceThumbnailsOrDuplicateSearch(){
        try{
            // 1256px / 1.5 = 837.3dp, close to the reported portrait width.
            shell("wm size 1256x1920");Thread.sleep(600)
            create();compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1);compose.onNodeWithTag("back-library").performClick();ready()
            var initial=emptySet<Int>();compose.waitUntil(10_000){compose.runOnIdle{initial=previews()};initial.isNotEmpty()}
            repeat(5){
                compose.onNodeWithTag("open-library-drawer").performClick();compose.waitForIdle()
                compose.onNodeWithTag("library-drawer").assertIsDisplayed()
                compose.onAllNodesWithTag("library-search").assertCountEquals(1)
                compose.runOnIdle{assertEquals("opening drawer must not recreate native thumbnail views",initial,previews())}
                compose.onNodeWithTag("close-library-drawer").performClick();compose.waitForIdle();compose.onNodeWithTag("new-note").assertIsDisplayed()
            }
            compose.onNodeWithTag("open-library-drawer").performClick();shot("drawer-compact-emulator.png")
            compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle();compose.onNodeWithTag("new-note").assertIsDisplayed()
        }finally{shell("wm size 1920x1200");Thread.sleep(500)}
    }
    @Test fun visibleWidthPresetsAffectOnlyNewStrokesAndSurviveRecreate(){
        val id=create()
        fun choose(index:Int){
            compose.onNodeWithTag("pen-width-open").performScrollTo().performClick();compose.onNodeWithTag("pen-width-dialog").assertIsDisplayed()
            compose.onNodeWithTag("width-preset-$index").performScrollTo().performClick();compose.onNodeWithTag("apply-pen-width").performScrollTo().performClick();compose.waitForIdle()
        }
        choose(2)
        compose.waitUntil(10_000){PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).read()[0]==6f}
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        val first=runBlocking{app.inkRepository.read(id).strokes.single().stroke};assertEquals(6f,first.width,0f)
        choose(0);compose.waitUntil(10_000){PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).read()[0]==1.5f};draw();saved(2)
        val paths=runBlocking{app.inkRepository.read(id).strokes.map{it.stroke}}
        assertEquals(6f,paths[0].width,0f);assertEquals(1.5f,paths[1].width,0f);assertEquals(first.samples,paths[0].samples)
        compose.activityRule.scenario.recreate();saved(2)
        compose.onNodeWithTag("pen-width-open").performScrollTo().performClick();compose.onNodeWithTag("pen-width-value").assertTextEquals("线宽 1.5");shot("pen-width-emulator.png")
        compose.onNodeWithTag("apply-pen-width").performScrollTo().performClick()
    }
    @Test fun widthSettingsHaveIndependentSlotsAndRealFileRoundTrip(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-width-"+UUID.randomUUID()
        try{
            val store=PenWidthStore(context,name)
            runBlocking{assertTrue(store.save(0,6f));assertTrue(store.save(1,1.5f));assertTrue(store.save(2,34f))}
            assertEquals(listOf(6f,1.5f,34f),PenWidthStore(context,name).read())
        }finally{context.deleteSharedPreferences(name)}
    }
    @Test fun invalidWidthSettingsDoNotOverwriteOtherSlots(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="synthetic-width-"+UUID.randomUUID()
        try{
            val store=PenWidthStore(context,name);runBlocking{assertTrue(store.save(0,3f))}
            for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,100f)){
                try{runBlocking{store.save(0,value)};fail("invalid width was accepted")}catch(_:IllegalArgumentException){}
            }
            assertEquals(listOf(3f,3f,22f),PenWidthStore(context,name).read())
        }finally{context.deleteSharedPreferences(name)}
    }
}
