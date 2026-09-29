// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryBackupUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("library-backup-open").assertIsDisplayed()}.isSuccess}}
    private fun vm()=ViewModelProvider(compose.activity)[LibraryBackupViewModel::class.java]
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    @Test fun backupRequiresConsentThenSavesActualLibraryArchive(){
        ready();val n=runBlocking{app.workspaceRepository.create("备份界面-${UUID.randomUUID()}",false,PaperStyle.GRID)}
        compose.onNodeWithTag("library-backup-open").performClick();compose.onNodeWithTag("backup-create").performClick()
        compose.onNodeWithTag("backup-generate").assertIsNotEnabled();compose.onNodeWithTag("backup-consent").performClick()
        compose.onNodeWithTag("backup-generate").performClick()
        compose.waitUntil(30_000){compose.onAllNodesWithTag("backup-save").fetchSemanticsNodes().isNotEmpty()}
        shot("library-backup-export.png")
        val out=File(app.cacheDir,"test-backup-${UUID.randomUUID()}.iwbackup")
        try{
            // Real ContentResolver destination receiver; no claim about third-party picker/cloud.
            compose.runOnIdle{vm().save(Uri.fromFile(out))}
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("backup-message").assertTextContains("已完成备份写入",substring=true)}.isSuccess}
            var found=false
            out.inputStream().use{LibraryArchive.read(it,LibraryBackupRepository.SCHEMA,{table,row->if(table==0&&row[0]==n.id)found=true})}
            assertTrue(found);assertNotNull(runBlocking{app.repository.read(n.id)})
        }finally{out.delete()}
    }
    @Test fun badArchiveShowsFailureWithoutWritingLibrary(){
        ready();val before=runBlocking{app.repository.observeNotes().first()}.map{it.id}.toSet()
        val f=File(app.cacheDir,"test-invalid-${UUID.randomUUID()}.iwbackup").apply{writeText("not a backup")}
        try{compose.runOnIdle{vm().inspect(Uri.fromFile(f))}
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("backup-message").assertTextContains("校验失败",substring=true)}.isSuccess}
            assertEquals(before,runBlocking{app.repository.observeNotes().first()}.map{it.id}.toSet())
        }finally{f.delete()}
    }
    @Test fun isolatedPreviewRequiresConfirmationAndRestoresOnDevice(){
        ready();val name="backup-ui-source-${UUID.randomUUID()}.db";val source=NoteDatabase.open(app,name)
        var archive:LibraryBackupRepository.Snapshot?=null
        try{
            val n=runBlocking{WorkspaceRepository(source).create("恢复界面-${UUID.randomUUID()}",false,PaperStyle.RULED,NotebookCover.INK)}
            archive=runBlocking{LibraryBackupRepository(app,source).snapshot()}
            compose.runOnIdle{vm().inspect(Uri.fromFile(archive!!.file))}
            compose.waitUntil(30_000){compose.onAllNodesWithTag("restore-consent").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("backup-restore").assertIsNotEnabled();assertNull(runBlocking{app.repository.read(n.id)})
            shot("library-backup-restore.png")
            compose.onNodeWithTag("restore-consent").performClick();compose.onNodeWithTag("backup-restore").performClick()
            compose.waitUntil(30_000){runCatching{compose.onNodeWithTag("backup-message").assertTextContains("已恢复资料库",substring=true)}.isSuccess}
            assertEquals(n,runBlocking{app.repository.read(n.id)})
            compose.activityRule.scenario.recreate();assertEquals(n,runBlocking{app.repository.read(n.id)})
        }finally{archive?.close();source.close();app.deleteDatabase(name)}
    }
}
