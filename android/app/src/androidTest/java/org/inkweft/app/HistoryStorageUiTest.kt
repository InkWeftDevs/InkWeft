// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.PaperStyle
import org.junit.Rule
import org.junit.Test
import java.io.File

class HistoryStorageUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun auditDisplaysCountsWithoutOfferingDeletionOfAuthorHistory(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        runBlocking{app.workspaceRepository.create("历史整理检查",false,PaperStyle.BLANK)}
        compose.setContent{MaterialTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){OriginalStorageSummary()}}}
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("audit-history-storage").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("audit-history-storage").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("历史与引用载荷",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("操作回执",substring=true).assertExists()
        compose.onNodeWithTag("collect-detached-history").assertIsNotEnabled()
        compose.onNodeWithTag("audit-history-storage").performScrollTo()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(app.getExternalFilesDir(null),"v90-history-storage.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
}
