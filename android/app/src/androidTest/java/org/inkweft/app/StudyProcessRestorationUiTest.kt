// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native SavedStateRegistry + new VM stores, not an OS-death claim or a force-stop contract. */
class StudyProcessRestorationUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as InkWeftApplication
    private var originalTabs: String? = null
    private var hadTabs = false
    private val owners = mutableListOf<RestoredOwner>()

    @Before fun rememberTabs() {
        val prefs = app.getSharedPreferences("inkweft-open-tabs", 0)
        hadTabs = prefs.contains("ids"); originalTabs = prefs.getString("ids", null)
    }

    @After fun cleanup() {
        main { owners.forEach { it.close() } }
        val edit = app.getSharedPreferences("inkweft-open-tabs", 0).edit()
        if (hadTabs) edit.putString("ids", originalTabs) else edit.remove("ids")
        assertTrue(edit.commit())
    }

    private fun id() = UUID.randomUUID().toString()
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun owner(saved: Bundle? = null) = RestoredOwner(app, saved).also(owners::add)
    private fun notebook(owner: RestoredOwner) = ViewModelProvider(owner)[NotebookViewModel::class.java]
    private fun panel(owner: RestoredOwner, book: String) = ViewModelProvider(owner)["study-panel-$book", StudyPanelSession::class.java]
    private fun study(owner: RestoredOwner, book: String, repo: StudyRepository = app.study) =
        ViewModelProvider(owner, StudyViewModel.Factory(book, repo))["study-$book", StudyViewModel::class.java]
    private fun loaded(vm: NotebookViewModel) = runBlocking { withTimeout(15_000) { vm.ui.first { !it.loading && !it.readFailed } } }
    private fun settled(vm: StudyViewModel) = runBlocking { withTimeout(15_000) { vm.ui.first { !it.loading && !it.busy } } }
    private fun book() = runBlocking { app.workspaceRepository.create("任务恢复 · ${id().take(6)}", false, PaperStyle.RULED) }
    private fun tap(tag: String) {
        compose.revealAction(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag); runCatching { node.performScrollTo() }; node.performClick()
    }

    @Test fun savedRegistryRestoresSelectedBookPanelAndEveryStudyTab() {
        val note = book()
        val original = main { owner() }
        val model = main { notebook(original) }; loaded(model)
        main { model.select(note); panel(original, note.id).opened.value = true }
        val session = main { study(original, note.id) }; settled(session)
        for (tab in 0..2) {
            main { session.selectTab(tab) }
            val state = main { original.save() }
            val rebuilt = main { owner(state) }
            val restoredModel = main { notebook(rebuilt) }
            val ui = loaded(restoredModel)
            assertNotSame(model, restoredModel)
            assertEquals(note.id, ui.selectedId); assertEquals(note, ui.current?.base)
            assertTrue(note.id in ui.openIds)
            main {
                assertTrue(panel(rebuilt, note.id).opened.value)
                val restoredStudy = study(rebuilt, note.id)
                assertNotSame(session, restoredStudy)
                assertTrue(restoredStudy.compactInitialized); assertEquals(tab, restoredStudy.lastTab)
                assertFalse("Another book must not inherit an open panel", panel(rebuilt, id()).opened.value)
                rebuilt.close()
            }
        }
        main { panel(original, note.id).opened.value = false; model.back() }
        val closed = main { owner(original.save()) }
        assertNull(loaded(main { notebook(closed) }).selectedId)
        main { assertFalse(panel(closed, note.id).opened.value) }
        assertEquals(note, runBlocking { app.repository.read(note.id) })
    }

    @Test fun remountedNotebookAndStudyContainersRestoreInlineTitleWithoutCreatingNode() {
        val note = book(); val root = id(); val card = id()
        runBlocking { app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE,
            cardId = card, nodeId = root, title = "条件概率", body = "确定条件后再看样本空间")) }
        val beforeCards = runBlocking { app.study.cards(note.id).first() }
        val beforeNodes = runBlocking { app.study.nodes(note.id).first() }
        var restoreState: Bundle? = null
        var mounted: RestoredOwner? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val current = remember { owner(restoreState).also { mounted = it } }
            DisposableEffect(current) { onDispose { current.close() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides current, LocalLifecycleOwner provides current,
                LocalSavedStateRegistryOwner provides current) { WorkspaceApp() }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        val previous = main { checkNotNull(mounted) }
        val previousModel = main { notebook(previous).also { it.select(note) } }
        compose.waitUntil(15_000) { app.navigationReady.value }
        tap("quick-study"); tap("study-window-maximize"); tap("study-window-mode-FOCUS")
        tap("study-tab-1"); tap("outline-child-$root")
        val text = "中文草稿 x² + y² = 1"
        compose.onNodeWithTag("node-title-input").performTextInput(text)
        compose.onNodeWithTag("node-title-input").assertTextContains(text)
        main { restoreState = previous.save() }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("node-title-input").fetchSemanticsNodes().isNotEmpty() }
        main {
            val current = checkNotNull(mounted)
            assertNotSame(previous, current); assertNotSame(previousModel, notebook(current))
            assertEquals(note.id, notebook(current).ui.value.selectedId)
            assertTrue(panel(current, note.id).opened.value); assertEquals(1, study(current, note.id).lastTab)
        }
        compose.onNodeWithTag("node-title-input").assertTextContains(text)
            .assert(hasAnyAncestor(hasTestTag("outline-title-$root")))
        compose.onAllNodesWithTag("node-title-editor").assertCountEquals(1)
        assertEquals(beforeCards, runBlocking { app.study.cards(note.id).first() })
        assertEquals(beforeNodes, runBlocking { app.study.nodes(note.id).first() })
        tap("node-title-cancel")
        compose.onNodeWithTag("node-title-editor").assertDoesNotExist()
        assertEquals(beforeCards, runBlocking { app.study.cards(note.id).first() })
        assertEquals(beforeNodes, runBlocking { app.study.nodes(note.id).first() })
    }

    @Test fun restoredUnknownStudyCommandWaitsForExplicitRetryOfTheSameOperation() {
        val note = book(); val database = NoteDatabase.open(app)
        val fail = AtomicBoolean(true); val writes = AtomicInteger()
        val repo = StudyRepository(database) { if (it == StudyFault.BEFORE_RECEIPT) {
            writes.incrementAndGet(); if (fail.get()) throw IOException("Synthetic task-state receipt rollback")
        } }
        val command = StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = id(), title = "待核对的唯一主题")
        var rebuilt: RestoredOwner? = null
        val original = main { owner() }
        try {
            val first = main { notebook(original).also { it.select(note) }; panel(original, note.id).opened.value = true; study(original, note.id, repo) }
            settled(first); main { first.selectTab(1); first.submit(command) }
            runBlocking { withTimeout(15_000) { first.ui.first { it.unknown && !it.busy } } }
            val saved = main { original.save().also { original.close() } }
            val second = main { owner(saved).also { rebuilt = it }.let { study(it, note.id, repo) } }
            settled(second)
            // Initialization has had time to dispatch; there must be no automatic author retry.
            SystemClock.sleep(200)
            assertTrue(second.ui.value.unknown); assertFalse(second.ui.value.busy); assertEquals(1, writes.get())
            assertNull(runBlocking { repo.lookup(command) }); assertTrue(runBlocking { repo.nodes(note.id).first() }.isEmpty())
            fail.set(false); main { second.retry() }
            runBlocking { withTimeout(15_000) { second.ui.first { !it.unknown && !it.busy && it.completed == command.cardId } } }
            assertEquals(2, writes.get()); assertEquals(command.cardId, runBlocking { repo.lookup(command) })
            assertEquals(command.nodeId, runBlocking { repo.nodes(note.id).first() }.single().id)
            assertEquals(1, runBlocking { repo.cards(note.id).first() }.size)
        } finally { main { original.close(); rebuilt?.close() }; database.close() }
    }

    private class RestoredOwner(application: Application, restored: Bundle?) : SavedStateRegistryOwner,
        ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val lifecycle = LifecycleRegistry(this)
        override val viewModelStore = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        override val defaultViewModelProviderFactory = SavedStateViewModelFactory(application, this)
        override val defaultViewModelCreationExtras: CreationExtras = MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
            set(SAVED_STATE_REGISTRY_OWNER_KEY, this@RestoredOwner)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@RestoredOwner)
        }
        init {
            controller.performAttach(); enableSavedStateHandles(); controller.performRestore(restored)
            lifecycle.currentState = Lifecycle.State.RESUMED
        }
        fun save(): Bundle {
            val bundle = Bundle(); controller.performSave(bundle)
            val parcel = Parcel.obtain()
            return try {
                parcel.writeBundle(bundle); parcel.setDataPosition(0)
                checkNotNull(parcel.readBundle(javaClass.classLoader))
            } finally { parcel.recycle() }
        }
        fun close() {
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                lifecycle.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear()
            }
        }
    }
}
