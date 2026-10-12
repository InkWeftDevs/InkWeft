package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.PaperStyle
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryPaginationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nextPreviousAndQueryResetShowBoundedPages(){
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val app=compose.activity.application as InkWeftApplication
        val term="分页验收-${UUID.randomUUID().toString().take(8)}"
        runBlocking{repeat(65){app.workspaceRepository.create("$term ${it.toString().padStart(3,'0')}",false,PaperStyle.BLANK)}}
        compose.onNodeWithTag("library-search").performTextInput(term)
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(30_000){runCatching{compose.onNodeWithTag("library-next-page").assertIsEnabled()}.isSuccess}
        compose.onNodeWithText("第 1 页").assertExists()
        compose.onNodeWithTag("library-next-page").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("library-previous-page").assertIsEnabled();compose.onNodeWithTag("library-next-page").assertIsNotEnabled()}.isSuccess}
        compose.onNodeWithText("第 2 页").assertExists();compose.onNodeWithText("本页 5 份").assertExists()
        val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(compose.activity.getExternalFilesDir(null),"v89-library-page2.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
        compose.onNodeWithTag("library-previous-page").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("library-next-page").assertIsEnabled()}.isSuccess}
        compose.onNodeWithText("第 1 页").assertExists()
        compose.onNodeWithTag("library-search").performTextReplacement("绝对无命中-$term")
        compose.waitUntil(10_000){compose.onAllNodesWithTag("library-next-page").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("第 2 页").assertDoesNotExist()
    }
}
