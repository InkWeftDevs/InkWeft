// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real menus and writes use newly created synthetic notebooks only. Snapshot
 * byte boundaries below are isolated Compose inputs, never large Room blobs.
 */
class StudyCapacityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private val database get() = StudyRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(app.study) as NoteDatabase
    private val preferences = linkedMapOf<String, Map<String, Any?>>()
    private var ownedBook: String? = null
    private fun id() = UUID.randomUUID().toString()
    private fun notebook() = ViewModelProvider(compose.activity)[NotebookViewModel::class.java]
    private fun study(book: String) = ViewModelProvider(compose.activity)["study-$book", StudyViewModel::class.java]

    @Before fun capturePreferences() {
        listOf("inkweft-editor", "inkweft-reading", "inkweft-study-window", "inkweft-learning", "inkweft-open-tabs")
            .forEach { name ->
                preferences[name] = app.getSharedPreferences(name, 0).all.mapValues { (_, value) ->
                    if (value is Set<*>) value.toSet() else value
                }
            }
        assertTrue(app.getSharedPreferences("inkweft-editor", 0).edit().putBoolean("case-collapsed", true).commit())
    }

    @After fun restorePreferences() {
        try {
            compose.runOnIdle {
                notebook().back()
                ownedBook?.let { notebook().closeTab(it) }
            }
            waitFor("new-note")
        } finally {
            preferences.forEach { (name, values) ->
                // Restore only keys captured before this test. Newly created
                // notebook preferences belong to the retained synthetic fixture.
                val editor = app.getSharedPreferences(name, 0).edit()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        null -> editor.remove(key)
                        else -> error("Unexpected preference type ${value.javaClass.name}")
                    }
                }
                if (name == "inkweft-editor" && "case-collapsed" !in values) editor.remove("case-collapsed")
                assertTrue("Restore $name", editor.commit())
            }
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        waitFor(tag)
        val node = compose.onNodeWithTag(tag)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun mapAction(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            tap("study-management")
            tap("map-menu-group-1")
        }
        tap(tag)
    }

    private fun createBook(): Note {
        waitFor("new-note")
        return runBlocking {
            app.workspaceRepository.create("容量合成验证 ${id().take(8)}", false, PaperStyle.BLANK)
        }.also { note -> ownedBook = note.id }
    }

    private fun openBook(note: Note) {
        assertTrue(app.getSharedPreferences("inkweft-reading", 0).edit()
            .putBoolean("continuous-v20-${note.id}", false).commit())
        assertTrue(app.getSharedPreferences("inkweft-study-window", 0).edit()
            .putString("${note.id}-mode", "FOCUS").commit())
        compose.runOnIdle { notebook().select(note) }
        compose.singlePageEditor()
        compose.waitForSavedInk()
    }

    private fun settled(book: String, mapId: String? = null) {
        val vm = compose.runOnIdle { study(book) }
        compose.waitUntil(15_000) {
            val state = vm.ui.value
            vm.mapId.value == mapId && !state.loading && !state.busy && !state.unknown &&
                !state.readFailed && state.graph?.ref?.mapId == mapId
        }
        compose.waitForIdle()
    }

    private data class AuthorState(
        val note: NoteRow?,
        val cards: List<StudyCardRow>,
        val nodes: List<StudyNodeRow>,
        val knowledge: List<String>,
        val receipts: List<String>,
    )

    private fun author(book: String, source: NoteDatabase = database): AuthorState = runBlocking {
        source.withTransaction {
            val receipts = source.openHelper.readableDatabase.query(
                "SELECT id,digest,resultId FROM study_receipts WHERE notebookId=? ORDER BY id", arrayOf(book)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getString(1)}:${cursor.getString(2)}")
                }
            }
            AuthorState(source.notes().note(book), source.study().cards(book), source.study().nodes(book),
                source.knowledge().all().filter { it.notebookId == book }.sortedBy { it.id }
                    .map { "${it.id}:${it.revision}:${it.removed}:${ContentTransfer.hash(it.payload)}" }, receipts)
        }
    }

    private fun counts(active: Int, total: Int, cards: Int) {
        compose.onNodeWithTag("capacity-map-active").assertTextContains("$active / 128", substring = true)
        compose.onNodeWithTag("capacity-map-total").assertTextContains("$total / 256", substring = true)
        compose.onNodeWithTag("capacity-book-cards").assertTextContains("$cards / 200", substring = true)
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            ViewCompat.getWindowInsetsController(compose.activity.window.decorView)?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private fun visibleMap(): MindMapView {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is MindMapView && view.isShown) return view
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        error("Visible study map missing")
    }

    @Test fun realMenusCountRemovedNodesAndTrashedCardsAcrossMapSwitchAndCollapse() {
        val note = createBook()
        val card = id()
        val root = id()
        val removed = id()
        val namedMap = id()
        val namedRoot = id()
        val namedChild = id()
        runBlocking {
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = card, nodeId = root,
                title = "共享卡片", body = "多个位置共享这份内容"))
            repeat(2) { index ->
                app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = card, nodeId = id(),
                    parentId = root, x = 340.0, y = 80.0 + index * 180))
            }
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = id(), nodeId = removed,
                title = "已移除位置的卡片", body = "移除节点仍保留卡片"))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REMOVE_NODE, nodeId = removed, expectedRevision = 1))
            database.withTransaction {
                val trash = StudyCardRow(id(), note.id, 1, "已回收合成卡片", "仍占卡片记录", trashedAt = 1L)
                database.study().addCard(trash)
                database.study().revision(StudyCardRevisionRow(trash.id, trash.revision, trash.title, trash.body, trash.trashedAt))
            }
            app.knowledge.submit(KnowledgeCommand(id(), note.id, namedMap, 0,
                KnowledgeData.MapDefinition("容量命名图", structures = listOf(
                    MapStructure(namedRoot, null, "命名图根主题", 40.0, 80.0),
                    MapStructure(namedChild, namedRoot, "可收起的下级主题", 340.0, 80.0)))))
        }
        val before = author(note.id)
        openBook(note)
        tap("quick-readonly")
        compose.onNodeWithTag("exit-readonly").assertExists()
        tap("document-more")
        tap("document-capacity")
        waitFor("capacity-panel")
        settled(note.id)
        counts(active = 3, total = 4, cards = 3)
        compose.onNodeWithTag("capacity-new-map").assertIsNotEnabled()
        compose.onNodeWithTag("capacity-open-cards").assertIsEnabled()
        compose.onNode(hasText("含回收区 1 张", substring = true) and hasAnyAncestor(hasTestTag("capacity-panel")))
            .assertExists()
        tap("capacity-close")

        mapAction("study-capacity")
        counts(active = 3, total = 4, cards = 3)
        tap("capacity-close")
        tap("study-map-picker")
        tap("study-map-$namedMap")
        settled(note.id, namedMap)
        compose.runOnIdle { assertNotNull(visibleMap().nodeBounds(namedChild)) }
        mapAction("study-collapse-all")
        compose.runOnIdle { assertNull("The child is actually folded", visibleMap().nodeBounds(namedChild)) }
        mapAction("study-capacity")
        counts(active = 2, total = 2, cards = 3)
        tap("capacity-close")
        tap("study-map-picker")
        tap("study-map-main")
        settled(note.id)
        mapAction("study-capacity")
        counts(active = 3, total = 4, cards = 3)
        assertEquals("Reading capacity and folding must not author content", before, author(note.id))
        tap("capacity-close")
        tap("exit-readonly")
    }

    @Test fun cardBudgetRejectionKeepsTheRealEditorDraftAndAuthorRecords() {
        val note = createBook()
        val actualBefore = author(note.id)
        // MainActivity supplies the real navigation and editor. Only its fresh
        // study VM reads this in-memory capacity fixture; the app DB stays empty.
        val isolated = Room.inMemoryDatabaseBuilder(app, NoteDatabase::class.java).build()
        val isolatedRepository = StudyRepository(isolated)
        val key = "study-${note.id}"
        var injected: StudyViewModel? = null
        try {
            runBlocking {
                val isolatedNote = WorkspaceRepository(isolated).create(note.title, false, PaperStyle.BLANK, operationId = note.id)
                assertEquals("The isolated capacity fixture must share the shell identity", note.id, isolatedNote.id)
                isolated.withTransaction {
                    repeat(200) { index ->
                        val row = StudyCardRow(id(), note.id, 1, "合成已有卡片 $index", "容量拒绝检查")
                        isolated.study().addCard(row)
                        isolated.study().revision(StudyCardRevisionRow(row.id, row.revision, row.title, row.body, null))
                    }
                }
            }
            compose.runOnIdle {
                assertNull("The fresh shell must not already own a study VM", compose.activity.viewModelStore.get(key))
                injected = ViewModelProvider(compose.activity, StudyViewModel.Factory(note.id, isolatedRepository))[
                    key, StudyViewModel::class.java]
            }
            openBook(note)
            tap("quick-study")
            settled(note.id)
            compose.runOnIdle {
                assertSame(injected, study(note.id))
                assertSame(isolatedRepository, study(note.id).repo)
                assertEquals(200, study(note.id).ui.value.cards.size)
            }
            mapAction("study-add-card")
            waitFor("study-card-editor")
            val title = "  达到上限后的标题草稿  "
            val body = "第一行原样保留\n第二行含空格  与正文。"
            compose.onNodeWithTag("study-card-title").performTextReplacement(title)
            compose.onNodeWithTag("study-card-body").performTextReplacement(body)
            hideKeyboard()
            val before = author(note.id, isolated)
            val editorVm = compose.runOnIdle { study(note.id) }
            tap("study-save-card")
            compose.waitUntil(15_000) {
                val state = editorVm.ui.value
                !state.busy && !state.unknown && state.message != null
            }
            compose.onNodeWithTag("study-card-editor").assertExists()
            compose.onNodeWithTag("study-card-title").assertTextContains(title, substring = false)
            compose.onNodeWithTag("study-card-body").assertTextContains(body, substring = false)
            compose.onNode(hasText("未提交：本次操作会超过本笔记 200 张卡片上限（含回收区）。可编辑或复用已有卡片，或在另一笔记中新建。草稿已保留。") and
                hasAnyAncestor(hasTestTag("study-card-editor"))).assertExists()
            compose.onNodeWithTag("study-save-card").assertIsEnabled()
            compose.onNodeWithTag("study-cancel-card").assertIsEnabled()
            val after = author(note.id, isolated)
            assertEquals(200, after.cards.size)
            assertTrue(after.nodes.isEmpty())
            assertEquals("Rejected save must not change cards, graph, receipts or notebook revision", before, after)
            assertEquals("The actual app database must retain only its empty synthetic shell", actualBefore, author(note.id))
            tap("study-cancel-card")
        } finally {
            try {
                compose.runOnIdle { notebook().back() }
                waitFor("new-note")
            } finally {
                // Replacing this one owned key clears the old VM and its Room
                // observers without clearing any unrelated activity view model.
                try {
                    compose.activityRule.scenario.onActivity { activity ->
                        if (injected != null && activity.viewModelStore.get(key) === injected) {
                            activity.viewModelStore.put(key, StudyViewModel(note.id, app.study, SavedStateHandle()))
                        }
                    }
                } finally {
                    isolated.close()
                }
            }
        }
    }
}

