// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import org.inkweft.data.*
import org.inkweft.core.CloudServicePort
import org.inkweft.core.DisabledCloudServices

class InkWeftApplication:Application(){
    val diagnostics by lazy{AppDiagnostics(this)}
    private val database by lazy{NoteDatabase.open(this)}
    val repository by lazy{NoteRepository(database)}
    val inkRepository by lazy{InkRepository(database)}
    val workspaceRepository by lazy{WorkspaceRepository(database)}
    val pages by lazy{NotebookPages(database)}
    val libraryContent by lazy{LibraryContentRepository(database)}
    val libraryBackup by lazy{LibraryBackupRepository(this,database)}
    val study by lazy{StudyRepository(database)}
    val cloudServices:CloudServicePort=DisabledCloudServices
    override fun onCreate(){super.onCreate();diagnostics}
}
