// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.inkweft.core.InkPen
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PenPresetUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun saved(n:Int){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true).assertTextContains("$n 笔",substring=true)}.isSuccess}}
    private fun create():String {
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val title="预设测试-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick();compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("create-note").performClick();saved(0)
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
    }
    private fun draw(){compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.25f,height*.3f),Offset(width*.5f,height*.5f),200)}}
    @Test fun panelSavesColorAndWidthWithoutChangingEarlierStroke(){
        val id=create();val previous=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).readColors()[0]
        compose.onNodeWithTag("ink-more").performScrollTo().performClick();compose.onNodeWithTag("ink-finger").performScrollTo().performClick();draw();saved(1)
        val original=runBlocking{app.inkRepository.read(id).strokes.single().stroke}
        assertEquals(previous,original.color)
        compose.onNodeWithTag("pen-width-open").performScrollTo().performClick()
        compose.onNodeWithTag("pen-kind-brush").performScrollTo().performClick();compose.onNodeWithTag("width-preset-2").performScrollTo().performClick();compose.onNodeWithTag("pen-color-2").performScrollTo().performClick()
        compose.onNodeWithTag("pen-width-dialog").assertIsDisplayed()
        val bmp=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(compose.activity.getExternalFilesDir(null),"pen-presets-popover.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}
        compose.onNodeWithTag("apply-pen-width").performScrollTo().performClick()
        compose.waitUntil(10_000){val s=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id);s.read()[0]==6f&&s.readColors()[0]==PenWidthStore.colors(0)[2]}
        draw();saved(2)
        val paths=runBlocking{app.inkRepository.read(id).strokes.map{it.stroke}}
        assertEquals(original.samples,paths[0].samples);assertEquals(original.color,paths[0].color)
        assertEquals(InkPen.BRUSH,paths[1].pen);assertEquals(InkPen.BALLPOINT,original.pen);assertEquals(6f,paths[1].width,0f);assertEquals(PenWidthStore.colors(0)[2],paths[1].color)
        compose.activityRule.scenario.recreate();saved(2)
        compose.onNodeWithTag("pen-width-open").performScrollTo().performClick();compose.onNodeWithTag("pen-width-value").assertTextEquals("线宽 6.0")
        compose.onNodeWithTag("cancel-pen-preset").performScrollTo().performClick()
    }
    @Test fun cancelDoesNotPersistOrMakeAnInkStroke(){
        val id=create();val before=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).read();val colors=PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).readColors()
        compose.onNodeWithTag("pen-width-open").performScrollTo().performClick();compose.onNodeWithTag("pen-kind-marker").performScrollTo().performClick();compose.onNodeWithTag("width-preset-0").performScrollTo().performClick();compose.onNodeWithTag("pen-color-3").performScrollTo().performClick()
        compose.onNodeWithTag("cancel-pen-preset").performScrollTo().performClick();saved(0)
        assertEquals(before,PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).read());assertEquals(colors,PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).readColors())
        assertEquals(InkPen.BALLPOINT,PenWidthStore(compose.activity,"inkweft-pen-widths-book-"+id).readKinds()[0]);assertTrue(runBlocking{app.inkRepository.read(id).strokes}.isEmpty())
    }
    @Test fun settingsRoundTripKeepsSlotsIndependentAndRejectsTransparentPen(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="synthetic-preset-"+UUID.randomUUID()
        try{
            val store=PenWidthStore(context,name);val old=store.readColors()
            runBlocking{assertTrue(store.savePreset(0,6f,PenWidthStore.colors(0)[3]));assertTrue(store.savePreset(2,34f,PenWidthStore.colors(2)[2]))}
            val loaded=PenWidthStore(context,name)
            assertEquals(listOf(6f,3f,34f),loaded.read());assertEquals(old[1],loaded.readColors()[1]);assertEquals(PenWidthStore.colors(2)[2],loaded.readColors()[2])
            try{runBlocking{store.savePreset(0,3f,0)};fail("transparent ordinary pen accepted")}catch(_:IllegalArgumentException){}
        }finally{context.deleteSharedPreferences(name)}
    }
}
