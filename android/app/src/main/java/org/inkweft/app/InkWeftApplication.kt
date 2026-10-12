// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import kotlinx.coroutines.launch
import org.inkweft.data.*
import org.inkweft.core.CloudServicePort
import org.inkweft.core.DisabledCloudServices

class InkWeftApplication:Application(){
    val openKnowledgeTarget=kotlinx.coroutines.flow.MutableStateFlow<org.inkweft.core.TargetRef?>(null)
    val knowledgeTargetOrigin=kotlinx.coroutines.flow.MutableStateFlow<org.inkweft.core.TargetRef?>(null)
    val navigationReady=kotlinx.coroutines.flow.MutableStateFlow(true)
    val diagnostics by lazy{AppDiagnostics(this)}
    internal val beautyDiagnostics by lazy{BeautyDiagnostics()}
    private val database by lazy{NoteDatabase.open(this)}
    internal val backupEngine by lazy{BackupEngine(this)}
    val repository by lazy{NoteRepository(database)}
    val documents by lazy{DocumentRepository(database)}
    val originalMaintenance by lazy{OriginalStorageMaintenance(database)}
    val pageObjects by lazy{PageObjectRepository(database)}
    val authoring by lazy{PageAuthoringRepository(database).also{it.readObserver=diagnostics::readTiming}}
    val inkRepository by lazy{InkRepository(database).also{it.readObserver=diagnostics::readTiming}}
    val workspaceRepository by lazy{WorkspaceRepository(database)}
    val pages by lazy{NotebookPages(database)}
    val resourceTemplates by lazy{ResourceTemplates(database)}
    internal val resourcePacks by lazy{ResourcePacks(this)}
    val libraryContent by lazy{LibraryContentRepository(database)}
    val libraryBackup by lazy{LibraryBackupRepository(this,database)}
    val knowledge by lazy{KnowledgeRepository(database)}
    val branchReview by lazy{BranchReviewRepository(database)}
    val knowledgeText by lazy{KnowledgeTextRepository(database)}
    val mapPortals by lazy{MapPortalRepository(database)}
    val mapEmbeds by lazy{MapEmbedRepository(database)}
    val mapGraphs by lazy{MapGraphAccess(database)}
    val learningDirectory by lazy{LearningDirectory(database)}
    internal val learningStore by lazy{LearningStore(this)}
    val study by lazy{StudyRepository(database)}
    val cloudServices:CloudServicePort=DisabledCloudServices
    internal val documentRendering by lazy{DocumentRendering(this,documents)}
    internal val handwriting by lazy{HandwritingRecognizer(this)}
    internal val formulas by lazy{FormulaRecognizer(this)}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)RenderResources.trim()}
    override fun onLowMemory(){super.onLowMemory();RenderResources.trim()}
    override fun onCreate(){super.onCreate();
        // These transfers cannot resume without reselecting their input after process death.
        // Capture only files from the previous process before any new transfer can start.
        val names=Regex("(?:content-(?:import-|export-)?|document-import-)[0-9a-f-]{36}(?:\\.part)?")
        val abandoned=cacheDir.listFiles().orEmpty().filter{names.matches(it.name)}+
            java.io.File(cacheDir,"visible-exports").listFiles().orEmpty().filter{it.name.matches(Regex("page-[0-9]+\\.(pdf|png)"))}
        if(abandoned.isNotEmpty())kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchCleanup(abandoned)
        TextStyles.initialize(this);diagnostics; if(backupEngine.mayResume())BackupScheduler.schedule(this)}
}

private fun kotlinx.coroutines.CoroutineScope.launchCleanup(files:List<java.io.File>) {
    launch{files.forEach{runCatching{it.deleteRecursively()}}}
}
