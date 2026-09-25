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
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Compose controls + Room readback on an emulator, not Pencil3 evidence. */
class PageInsertionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun saved(){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}}
    private fun create():String{
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val title="分页回归-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("create-note").performClick();saved()
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
    }
    private fun rows(id:String)=runBlocking{app.pages.observe(id).first()}
    private fun shot(name:String){compose.waitForIdle();val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}}
    private fun insert(){compose.onNodeWithTag("confirm-insert-pages").performClick();compose.waitUntil(10_000){compose.onAllNodesWithTag("insert-pages-dialog").fetchSemanticsNodes().isEmpty()};saved()}
    @Test fun beginningBatchKeepsOriginalInkAndPageIdentity(){
        val book=create();compose.onNodeWithTag("ink-finger").performScrollTo().performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.3f,height*.3f),Offset(width*.5f,height*.5f),200)}
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(book).strokes.size}==1};saved()
        val stroke=runBlocking{app.inkRepository.read(book).strokes.single().stroke}
        compose.onNodeWithTag("add-page").performScrollTo().performClick()
        compose.onNodeWithTag("insert-start").performScrollTo().performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick()
        compose.onNodeWithTag("insert-paper-grid").performScrollTo().performClick();compose.onNodeWithTag("insert-preview").performScrollTo().assertTextContains("插入 2 页",substring=true)
        shot("insert-pages-options.png");insert()
        compose.waitUntil(10_000){rows(book).size==3}
        assertEquals(book,rows(book).last().id);assertTrue(rows(book).take(2).all{it.paper==PaperStyle.GRID.ordinal})
        assertEquals(stroke.samples,runBlocking{app.inkRepository.read(book).strokes.single().stroke.samples})
        compose.onNodeWithTag("page-counter").assertTextEquals("第 1 / 3 页")
        compose.onNodeWithTag("page-directory").performClick();shot("page-directory-insertion.png")
        compose.onNodeWithTag("jump-page-3").performClick();saved();compose.onNodeWithTag("ink-status").assertTextContains("1 笔",substring=true)
    }
    @Test fun thumbnailBeforeMenuDoesNotRequireNavigatingToTarget(){
        val book=create();compose.onNodeWithTag("add-page").performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==3};val previous=rows(book)
        compose.onNodeWithTag("page-directory").performClick();compose.onNodeWithTag("page-menu-1").performClick()
        compose.onNodeWithText("在此页之前插入").performClick()
        compose.onNodeWithTag("insert-open-new").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==4};val actual=rows(book)
        assertEquals(previous.map{it.id},actual.drop(1).map{it.id})
        compose.onNodeWithTag("page-counter").assertTextEquals("第 3 / 4 页")
        compose.activityRule.scenario.recreate();saved();compose.onNodeWithTag("page-counter").assertTextEquals("第 3 / 4 页")
    }
    @Test fun cancelledDialogDoesNotAddPagesAndEndKeepsOrder(){
        val book=create();val first=rows(book)
        compose.onNodeWithTag("add-page").performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithText("取消").performClick()
        assertEquals(first,rows(book));compose.onNodeWithTag("add-page").performClick();compose.onNodeWithTag("insert-end").performScrollTo().performClick();insert()
        compose.waitUntil(10_000){rows(book).size==2};assertEquals(book,rows(book).first().id)
        compose.onNodeWithTag("page-counter").assertTextEquals("第 2 / 2 页")
    }
}
