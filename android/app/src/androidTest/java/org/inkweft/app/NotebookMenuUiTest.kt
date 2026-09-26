// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NotebookMenuUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actionsAreGroupedWithoutFakeSyncOrLockAndOpenTheSameNotebook(){
        val app=compose.activity.application as InkWeftApplication
        val n=runBlocking{app.workspaceRepository.create("资料整理-"+UUID.randomUUID().toString().take(6),false,PaperStyle.RULED,NotebookCover.INK)}
        compose.waitUntil(10_000){compose.onAllNodesWithTag("note-menu-${n.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("note-menu-${n.id}").performScrollTo().performClick()
        compose.onNodeWithTag("notebook-actions-menu").assertIsDisplayed()
        compose.onNodeWithTag("rename-note-${n.id}").assertExists();compose.onNodeWithTag("change-cover-${n.id}").assertExists()
        compose.onAllNodesWithText("云同步").assertCountEquals(0);compose.onAllNodesWithText("添加锁").assertCountEquals(0)
        val bmp=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(compose.activity.getExternalFilesDir(null),"shelf-note-menu.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bmp.recycle()}
        compose.onNodeWithText("打开笔记").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(n.id,runBlocking{app.repository.read(n.id)}?.id)
    }
}
