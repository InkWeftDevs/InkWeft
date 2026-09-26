// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.DiagnosticArchive
import org.inkweft.core.DiagnosticLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Real Android component checks; not an automated test of a user's share target. */
class DiagnosticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun entryAndRedactedZipFromActualApp() {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("new-note").assertIsEnabled() }.isSuccess }
        val privateTitle = "DO_NOT_EXPORT_PRIVATE_NOTE_3791"
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-title").performTextInput(privateTitle)
        compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("document-more").performClick();compose.onNodeWithTag("open-diagnostics").performClick()
        compose.onNodeWithTag("diagnostics-dialog").assertExists()
        compose.onNodeWithTag("diagnostics-mark").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-status").assertTextContains("已加时间标记", substring = true)
        val app = compose.activity.application as InkWeftApplication
        val bytes = runBlocking { app.diagnostics.bundle() }
        assertTrue(bytes.size <= DiagnosticArchive.MAX_ZIP_BYTES)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        assertEquals(setOf("README.txt", "report.json", "events.jsonl", "manifest.json"), files.keys)
        files.values.forEach { assertFalse(it.toString(Charsets.UTF_8).contains(privateTitle)) }
        val report = JSONObject(files.getValue("report.json").toString(Charsets.UTF_8))
        assertEquals("InkWeft.Diagnostics/1", report.getString("format"))
        assertEquals(BuildConfig.APPLICATION_ID, report.getJSONObject("app").getString("application_id"))
        assertEquals("NOT_RUN_BY_APP", report.getJSONObject("tests").getString("gradle"))
        assertTrue(files.getValue("events.jsonl").toString(Charsets.UTF_8).contains("USER_MARK"))
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8)).getJSONArray("files")
        for (i in 0 until manifest.length()) { val row = manifest.getJSONObject(i); assertEquals(row.getString("sha256"), DiagnosticLog.hash(files.getValue(row.getString("name")))) }
        File(compose.activity.getExternalFilesDir(null), "diagnostics-emulator.zip").writeBytes(bytes)
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "diagnostics-emulator.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
    @Test fun providerOnlyExposesDiagnosticCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowed = File(context.cacheDir, "diagnostics-export").apply { mkdirs() }
        val file = File(allowed, "report-instrumented.zip").apply { writeBytes(byteArrayOf(1,2,3)) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostics.files", file)
        context.contentResolver.openInputStream(uri).use { assertArrayEquals(byteArrayOf(1,2,3), checkNotNull(it).readBytes()) }
        try { FileProvider.getUriForFile(context, context.packageName + ".diagnostics.files", File(context.filesDir, "private-database")); fail("private files exposed") }
        catch (_: IllegalArgumentException) { }
        file.delete()
    }
}
