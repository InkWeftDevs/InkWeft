// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real Activity, author DB, PDF renderer, native maps and saved rendered excerpts.
 * All notes/files/strokes are synthetic fixtures; no physical handwriting or user
 * recovery is claimed. Test APIs may inspect covered Compose roots, so leakage is
 * judged using the actual Android window accessibility policy plus OS nodes and
 * current visible pixels, rather than the presence of retained private state.
 */
class RecallMaskUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private var oldAccessibilityFlags = 0
    private lateinit var oldEditorPreferences: Map<String, *>
    private lateinit var oldLearningPreferences: Map<String, *>
    private val evidence get() = File(app.filesDir, "RM53-ui-evidence").apply { check(isDirectory || mkdirs()) }
    private fun id() = UUID.randomUUID().toString()
    private fun provider() = ViewModelProvider(compose.activity)
    private fun notebook() = provider()[NotebookViewModel::class.java]
    private fun study(book: String) = provider()["study-$book", StudyViewModel::class.java]

    @Before fun prepareEvidenceAndAccessibility() {
        val info = automation.serviceInfo
        oldAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val editor = app.getSharedPreferences("inkweft-editor", 0)
        oldEditorPreferences = editor.all
        editor.edit().clear().commit()
        val learning = app.getSharedPreferences("inkweft-learning", 0)
        oldLearningPreferences = learning.all
        learning.edit().clear().commit()
        app.learningStore.configure(LearningWidgets.defaults())
    }

    @After fun restoreDevicePreferencesAndCloseProbe() {
        val info = automation.serviceInfo
        info.flags = oldAccessibilityFlags
        automation.serviceInfo = info
        if (::oldEditorPreferences.isInitialized) restorePreferences("inkweft-editor", oldEditorPreferences)
        if (::oldLearningPreferences.isInitialized) restorePreferences("inkweft-learning", oldLearningPreferences)
        probeDatabase?.close(); probeDatabase = null
    }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val editor = app.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
    }

    private data class Fixture(
        val note: Note, val other: Note, val map: String, val branch: String,
        val card: String, val child: String, val duplicate: String, val manualCard: String,
        val collection: String, val questions: List<String>, val prompts: List<String>,
        val answer: String, val title: String, val branchTitle: String, val mapTitle: String,
        val objectText: String, val pdfText: String, val embedText: String,
        val manualTitle: String, val manualAnswer: String,
    ) {
        val secrets get() = listOf(note.title, answer, title, branchTitle, mapTitle, objectText, pdfText, embedText, manualTitle, manualAnswer)
    }

    private fun fixture(longText: Boolean = false, longNames: Boolean = false): Fixture {
        waitFor("new-note")
        val suffix = id().take(8)
        val title = if (longNames) "光合作用的三个关键步骤图解" else "RM53-TITLE-$suffix"
        val mapTitle = "RM53-MAP-$suffix"
        val branchTitle = "RM53-BRANCH-$suffix"
        val objectText = "RM53-OBJECT-$suffix"
        val pdfText = "RM53-PDF-$suffix"
        val embedText = "RM53-EMBED-$suffix"
        val answer = "RM53-ANSWER-$suffix" + if (longText) "\n" + (1..35).joinToString("\n") { "合成答案段 $it：先核对条件、原始定义与每一步关系。" } else ""
        val prompts = listOf("RM53-Q1-$suffix：请凭记忆解释。", "RM53-Q2-$suffix：请换一种表述。", "RM53-Q3-$suffix：未入图的手工卡。")
            .map { it.trimEnd() + if (longText) "\n" + (1..22).joinToString("\n") { n -> "合成题目条件 $n，请先思考再决定是否查看提示。" } else "" }
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 1414, 1).create())
        page.canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = 0xff00a0ef.toInt() }
        page.canvas.drawRect(100f, 300f, 900f, 460f, paint)
        paint.color = Color.BLACK; paint.textSize = 40f
        page.canvas.drawText(pdfText, 120f, 410f, paint)
        pdf.finishPage(page)
        val pdfBytes = ByteArrayOutputStream().also(pdf::writeTo).toByteArray(); pdf.close()
        val commonName = "RM54-$suffix 经典文献阅读与写作课程专题整理同前缀笔记第二部分的资料与讨论记录"
        val note = runBlocking { app.resourceTemplates.instantiate("d".repeat(64), if (longNames) "$commonName · 甲卷原文" else "RM53-BOOK-$suffix", PaperStyle.BLANK,
            PdfPageSource(PdfDocumentSource(pdfBytes, 1), 0), null) }
        val other = runBlocking { app.workspaceRepository.create(if (longNames) "$commonName · 乙卷讨论" else "RM53-OTHER-$suffix", false, PaperStyle.BLANK) }
        val map = id(); val branch = id(); val card = id(); val child = id(); val duplicate = id()
        val manualCard = id(); val collection = id()
        val manualTitle = "RM53-MANUAL-$suffix"; val manualAnswer = "RM53-MANUAL-ANSWER-$suffix"
        val prefix = id().take(24)
        val questions = (1..3).map { prefix + it.toString().padStart(12, '0') }
        val stroke = InkStroke(id(), InkPen.PEN, 0xffcf00a4.toInt(), 22f, InkTool.STYLUS,
            listOf(InkSample(120f, 220f, 0), InkSample(870f, 220f, 100)))
        val imageBitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff9deb00.toInt()) }
        val imageBytes = ByteArrayOutputStream().also { imageBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        imageBitmap.recycle()
        val embedded = MapScene(MapRef(note.id, map), embedText,
            listOf(MapSceneNode(id(), null, null, embedText, answer, 30.0, 30.0, 1, 1)), "a".repeat(64))
        runBlocking {
            assertTrue(app.inkRepository.save(CommitInk(id(), note.id, 0, InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val otherStroke = InkStroke(id(), InkPen.PEN, 0xffcf00a4.toInt(), 22f, InkTool.STYLUS,
                listOf(InkSample(100f, 220f, 0), InkSample(900f, 220f, 100)))
            assertTrue(app.inkRepository.save(CommitInk(id(), other.id, 0, InkMutation.Add(otherStroke))) is InkCommitResult.Committed)
            app.knowledge.submit(KnowledgeCommand(id(), note.id, map, 0, KnowledgeData.MapDefinition(mapTitle,
                structures = listOf(MapStructure(branch, null, branchTitle, 40.0, 80.0)))))
            app.pageObjects.save(note.id, 0, id(), listOf(
                PageObject(id(), PageObjectKind.TEXT, 100f, 500f, 790f, 100f, text = objectText, color = 0xffcf00a4.toInt(), fontSize = 40f),
                PageObject(id(), PageObjectKind.IMAGE, 110f, 650f, 320f, 140f, image = java.util.Base64.getEncoder().encodeToString(imageBytes)),
                PageObject(id(), PageObjectKind.MAP, 480f, 650f, 420f, 190f,
                    mapEmbed = MapEmbed(MapRef(note.id, map), policy = MapEmbedPolicy.PINNED, snapshot = embedded)),
            ))
        }
        openPaper(note)
        settleInk()
        val bounds = CanvasBounds(70.0, 160.0, 930.0, 930.0)
        val snapshot = compose.runOnIdle { native<InkCanvasView>().excerptPreview(bounds) }
        assertTrue("The actual rendered snapshot contains owned PDF/ink/image clues", snapshot.size > 500)
        runBlocking {
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = card, nodeId = child,
                parentId = branch, x = 300.0, y = 80.0, mapId = map, title = title, body = answer,
                source = StudySourceDraft(note.id, 1, bounds, listOf(stroke.id), snapshot, 1)))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REUSE, cardId = card, nodeId = duplicate,
                parentId = branch, x = 300.0, y = 220.0, mapId = map))
            val manualNode=id()
            app.study.submit(StudyCommand(id(), note.id, StudyAction.CREATE, cardId = manualCard,nodeId=manualNode,
                title = manualTitle, body = manualAnswer))
            app.study.submit(StudyCommand(id(), note.id, StudyAction.REMOVE_NODE,nodeId=manualNode,expectedRevision=1))
            listOf(card, card, manualCard).forEachIndexed { index, owner ->
                app.knowledge.submit(KnowledgeCommand(id(), note.id, questions[index], 0, KnowledgeData.Question(owner, prompts[index])))
            }
            app.knowledge.submit(KnowledgeCommand(id(), note.id, collection, 0, KnowledgeData.Collection("RM53-COLLECTION-$suffix")))
        }
        val f = Fixture(note, other, map, branch, card, child, duplicate, manualCard, collection, questions, prompts,
            answer, title, branchTitle, mapTitle, objectText, pdfText, embedText, manualTitle, manualAnswer)
        File(evidence, "fixture-$suffix.json").writeText(JSONObject().put("syntheticFixture", true)
            .put("book", note.id).put("otherBook", other.id).put("map", map).put("branch", branch).put("card", card)
            .put("questions", JSONArray(questions)).put("ownedPdfSha256", sha(pdfBytes))
            .put("actualRenderedExcerptSha256", sha(snapshot)).toString(2))
        openMap(f)
        return f
    }

    private fun waitFor(tag: String, diagnoseOnFailure: Boolean = false) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            // Keep the original failure, but distinguish a lost round from a loading
            // or adaptive-layout failure in the disposable synthetic CI fixture.
            if (diagnoseOnFailure) runCatching {
                println("RECALL_WAIT_FAILURE: tag=$tag config=${compose.activity.resources.configuration}")
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                    runCatching { println(compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString()) }
                }
            }
            throw error
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        if (tag.startsWith("recall-context-") && tag != "recall-context") {
            runCatching { compose.onNodeWithTag("recall-context").performScrollTo() }
        }
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        if (tag.startsWith("quick-") || tag == "toolbar-more") compose.revealAction(tag)
        if (tag.startsWith("tabs-actions-")) compose.onNodeWithTag("tabs-scroll").performScrollToNode(hasTestTag(tag))
        waitFor(tag); scrollTo(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun roots(): List<View> = WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }

    private fun children(root: View): List<View> = buildList {
        val queue = java.util.ArrayDeque<View>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst(); add(view)
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
    }

    private fun visibleNativeViews(): List<View> {
        val permitted = roots().filter { it.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS && it.isShown }
        val focused = permitted.filter { it.hasWindowFocus() }
        return (focused.ifEmpty { permitted }).flatMap(::children).filter { it.isShown }
    }

    private inline fun <reified T : View> native(): T = visibleNativeViews().filterIsInstance<T>().firstOrNull()
        ?: error("Actual visible ${T::class.java.simpleName} is missing")

    private fun openPaper(note: Note) {
        app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-${note.id}", false).commit()
        compose.runOnIdle { notebook().select(note) }
        compose.singlePageEditor(); compose.waitForSavedInk()
        compose.runOnIdle { native<InkCanvasView>().fitPage() }
        compose.waitForIdle()
    }

    private fun settleInk() {
        compose.waitUntil(20_000) { var ready = false
            compose.runOnIdle { ready = runCatching { native<InkCanvasView>().let { !it.rasterPending && it.documentContentReady } }.getOrDefault(false) }
            ready
        }
        compose.waitForIdle()
    }

    private fun openMap(f: Fixture) {
        tap("quick-study"); tap("study-map-picker"); tap("study-map-${f.map}"); waitFor("study-map")
        compose.waitUntil(15_000) { var ready = false
            compose.runOnIdle { ready = study(f.note.id).mapId.value == f.map && !study(f.note.id).ui.value.loading && native<MindMapView>().nodeBounds(f.child) != null }
            ready
        }
    }

    private fun select(node: String) {
        val point = compose.runOnIdle { native<MindMapView>().let { view ->
            view.focusNode(node); val b = checkNotNull(view.nodeBounds(node)); androidx.compose.ui.geometry.Offset(b.centerX(), b.centerY())
        } }
        compose.waitForIdle(); compose.onNodeWithTag("study-map").performTouchInput { click(point) }; waitFor("node-actions")
    }

    private fun startGraph(f: Fixture, branch: Boolean = true, folded: Boolean = false) {
        if (branch) {
            select(f.branch)
            if (folded) { tap("node-more"); tap("node-menu-fold"); compose.runOnIdle { assertNull(native<MindMapView>().nodeBounds(f.child)) } }
            tap("node-more"); tap("node-review-branch")
        } else { tap("study-management"); tap("map-menu-group-1"); tap("study-review-map") }
        waitFor("branch-review-counts")
        assertPlatformCluesAbsent(f)
        tap("branch-review-start"); waitFor("review-question")
    }

    private fun startNotebook() { tap("knowledge-tab-4"); tap("manual-review-start"); waitFor("review-question") }
    private fun closeKnowledge() { compose.onNodeWithText("返回笔记", useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun backToLibrary() {
        // Closing library Knowledge returns its actual note-picker Dialog.
        // Dismiss that window before touching the library behind it.
        val pickerBack = hasContentDescription("返回资料库") and hasAnyAncestor(isDialog())
        if (compose.onAllNodes(pickerBack).fetchSemanticsNodes().isNotEmpty()) compose.onNode(pickerBack).performClick()
        compose.runOnIdle { notebook().back() }; waitFor("new-note")
    }

    private fun openLibraryMap(f: Fixture) {
        backToLibrary()
        screenshot("design-library")
        if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
        compose.onNodeWithText("学习", useUnmergedTree = true).performScrollTo().performClick()
        waitFor("learning-workbench")
        compose.onNodeWithTag("widget-maps").performScrollTo()
        compose.onNodeWithTag("learning-map-search").performTextReplacement(f.mapTitle)
        val target = hasTestTag("learning-target-${f.map}") and hasAnyAncestor(hasTestTag("widget-maps"))
        compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(target).performScrollTo().performClick(); waitFor("study-map")
    }

    private fun openLibraryNotebook(f: Fixture, collection: Boolean) {
        backToLibrary()
        if (collection) {
            app.learningStore.shortcut(StableTargetRef(LearningTargetKind.COLLECTION, f.note.id, f.collection), true)
            if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
            compose.onNodeWithText("学习", useUnmergedTree = true).performScrollTo().performClick()
            waitFor("learning-workbench")
            val target = hasTestTag("learning-target-${f.collection}") and hasAnyAncestor(hasTestTag("widget-shortcuts"))
            compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(target).performScrollTo().performClick()
        } else {
            if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
            compose.onNodeWithText("复习", useUnmergedTree = true).performScrollTo().performClick()
            val target = hasText(f.note.title) and hasAnyAncestor(isDialog())
            compose.waitUntil(15_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(target).performScrollTo().performClick()
        }
        waitFor("knowledge-tab-4")
    }

    private fun semanticText(node: SemanticsNode): List<String> = buildList {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let(::addAll)
        node.children.forEach { addAll(semanticText(it)) }
    }

    @Suppress("DEPRECATION")
    private fun platformText(): List<String> {
        val result = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == app.packageName && node.isVisibleToUser) {
                listOf(node.text, node.contentDescription, node.hintText, node.paneTitle, node.tooltipText, node.stateDescription)
                    .filterNotNull().forEach { result += it.toString() }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { child -> try { walk(child) } finally { child.recycle() } } }
        }
        automation.windows.forEach { window -> window.root?.let { root -> try { walk(root) } finally { root.recycle() } } }
        return result
    }

    private fun assertPlatformCluesAbsent(f: Fixture, prompt: String? = null) {
        compose.waitUntil(15_000) { var protected = false
            compose.runOnIdle { protected = roots().any { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } }
            protected
        }
        val texts = platformText()
        assertTrue("OS inspection must retrieve the actual app UI, not an empty tree", texts.any { it.contains("回忆") || it.contains("本题") })
        if (prompt != null) assertTrue("The actual current question remains accessible", texts.any { it.contains(prompt.lineSequence().first()) })
        f.secrets.forEach { secret -> assertFalse("Actual interactive-window accessibility leaked $secret", texts.any { it.contains(secret) }) }
        val top = semanticText(compose.onNodeWithTag("manual-review", useUnmergedTree = true).fetchSemanticsNode())
        f.secrets.forEach { secret -> assertFalse("Current review semantics leaked $secret", top.any { it.contains(secret) }) }
    }

    private fun assertHidden(f: Fixture, question: Int = 0) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("review-question") and hasText(f.prompts[question])).fetchSemanticsNodes().isNotEmpty()
        }
        scrollTo("review-question")
        compose.onNodeWithTag("review-question").assertTextEquals(f.prompts[question])
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.onNodeWithTag("review-card-title").assertDoesNotExist()
        compose.onNodeWithTag("review-show-hint").assertExists()
        assertPlatformCluesAbsent(f, prompt = f.prompts[question])
    }

    private val authorQueries = listOf(
        "SELECT * FROM notes WHERE id=?",
        "SELECT * FROM note_revisions WHERE noteId=? ORDER BY revision",
        "SELECT * FROM command_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT noteId,world,paper,folder,tags,favorite,trashedAt,revision,coverKey,selectedPageId,pinned FROM notebook_workspace WHERE noteId=?",
        "SELECT * FROM notebook_covers WHERE noteId=?",
        "SELECT id,notebookId,position,world,paper,createdAfterId,trashedAt FROM notebook_pages WHERE notebookId=? ORDER BY id",
        "SELECT * FROM page_insert_receipts WHERE notebookId=? ORDER BY commandId",
        "SELECT * FROM page_edit_receipts WHERE notebookId=? ORDER BY commandId",
        "SELECT * FROM library_content_receipts WHERE noteId=? ORDER BY commandId",
        "SELECT i.* FROM ink_pages i JOIN notebook_pages p ON p.id=i.noteId WHERE p.notebookId=? ORDER BY i.noteId",
        "SELECT s.* FROM ink_strokes s JOIN notebook_pages p ON p.id=s.noteId WHERE p.notebookId=? ORDER BY s.id",
        "SELECT c.* FROM ink_cuts c JOIN notebook_pages p ON p.id=c.noteId WHERE p.notebookId=? ORDER BY c.id",
        "SELECT r.* FROM ink_receipts r JOIN notebook_pages p ON p.id=r.noteId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT o.* FROM page_objects o JOIN notebook_pages p ON p.id=o.pageId WHERE p.notebookId=? ORDER BY o.pageId",
        "SELECT r.* FROM object_receipts r JOIN notebook_pages p ON p.id=r.pageId WHERE p.notebookId=? ORDER BY r.commandId",
        "SELECT s.* FROM page_search_text s JOIN notebook_pages p ON p.id=s.pageId WHERE p.notebookId=? ORDER BY s.pageId",
        "SELECT * FROM study_cards WHERE notebookId=? ORDER BY id",
        "SELECT r.* FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=? ORDER BY r.cardId,r.revision",
        "SELECT * FROM study_nodes WHERE notebookId=? ORDER BY id",
        "SELECT s.* FROM study_sources s JOIN study_cards c ON c.id=s.cardId WHERE c.notebookId=? ORDER BY s.cardId",
        "SELECT * FROM study_receipts WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_records WHERE notebookId=? ORDER BY id",
        "SELECT * FROM knowledge_revisions WHERE notebookId=? ORDER BY id,revision",
        "SELECT * FROM knowledge_receipts WHERE notebookId=? ORDER BY operationId",
        "SELECT * FROM document_sources WHERE notebookId=? ORDER BY id",
        "SELECT c.* FROM document_chunks c JOIN document_sources s ON s.id=c.documentId WHERE s.notebookId=? ORDER BY c.documentId,c.position",
        "SELECT p.* FROM document_pages p JOIN document_sources s ON s.id=p.documentId WHERE s.notebookId=? ORDER BY p.pageId",
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** No author timestamps, payloads, revisions or receipts are excluded. Only
     * viewport centerX/centerY/zoom and device last-visit preferences are omitted.
     * selectedPageId is checked after returning to the original page.
     */
    private fun authorStamp(book: String, queries: List<String> = authorQueries): String = runBlocking {
        probe.withTransaction { JSONArray().apply { queries.forEach { sql ->
            val rows = JSONArray()
            probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONArray().apply { repeat(cursor.columnCount) { column ->
                    put(when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> "int:" + cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> "float:" + cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).size + ":" + sha(cursor.getBlob(column))
                        else -> "text:" + cursor.getString(column)
                    })
                } })
            }
            put(JSONObject().put("query", sql).put("rows", rows))
        } }.toString() }
    }

    private fun assertNoWrites(f: Fixture, before: String, otherBefore: String) {
        assertEquals("Mask/hint/navigation must not change any author value, history, rating, original ink or receipt", before, authorStamp(f.note.id))
        assertEquals("Other documents must retain their exact author records", otherBefore, authorStamp(f.other.id))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun capture(tag: String): Bitmap {
        waitFor(tag); scrollTo(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        return compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    }

    private fun pixels(bitmap: Bitmap, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (predicate(bitmap.getPixel(x, y))) count++
        return count
    }

    private fun magenta(color: Int) = Color.red(color) > 135 && Color.green(color) < 105 && Color.blue(color) > 90
    private fun cyan(color: Int) = Color.red(color) < 60 && Color.green(color) > 95 && Color.blue(color) > 140
    private fun lime(color: Int) = Color.red(color) > 100 && Color.green(color) > 150 && Color.blue(color) < 100

    private fun assertRawPixels(tag: String, name: String, pdfExpected: Boolean = true): Bitmap {
        val bitmap = capture(tag)
        assertTrue("Positive control must show real saved magenta ink/text, not a blank/test-only view", pixels(bitmap, ::magenta) > 25)
        if (pdfExpected) assertTrue("Positive control must include the actual owned PDF cyan block", pixels(bitmap, ::cyan) > 25)
        assertTrue("Positive control must include the actual saved image object", pixels(bitmap, ::lime) > 25)
        File(evidence, "$name-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun assertPlaceholder(tag: String) {
        val bitmap = capture(tag)
        try {
            val expected = 0xfff4f7f5.toInt()
            assertTrue("Neutral background must occupy at least 70%; only neutral border/lock/label may differ", pixels(bitmap) { it == expected } >= bitmap.width * bitmap.height * .70)
            assertEquals(0, pixels(bitmap, ::magenta)); assertEquals(0, pixels(bitmap, ::cyan)); assertEquals(0, pixels(bitmap, ::lime))
            compose.runOnIdle {
                assertTrue("Concealed related source/excerpt must instantiate no raw InkCanvasView", visibleNativeViews().none { it is InkCanvasView })
                assertTrue("Concealed related map must instantiate no native topology", visibleNativeViews().none { it is MindMapView })
                val placeholder = visibleNativeViews().single { it.tag == tag }
                assertTrue("The visible placeholder is a real neutral TextView", placeholder is TextView)
                val label = when (tag) {
                    "recall-context-source-placeholder" -> "原页已隐藏"
                    "recall-context-map-placeholder" -> "导图已隐藏"
                    "recall-context-excerpt-placeholder" -> "摘录已隐藏"
                    else -> error("Unexpected related placeholder: $tag")
                }
                assertEquals(label, (placeholder as TextView).text.toString())
                assertEquals("$label；本题线索已遮住的中性占位", placeholder.contentDescription.toString())
            }
        } finally { bitmap.recycle() }
    }

    private fun record(name: String, f: Fixture, before: String, otherBefore: String, extra: JSONObject = JSONObject()) {
        assertNoWrites(f, before, otherBefore)
        File(evidence, "$name-result.json").writeText(extra.put("case", name).put("syntheticFixture", true)
            .put("book", f.note.id).put("otherBook", f.other.id).put("canonicalQueryCount", authorQueries.size)
            .put("beforeCanonicalSha256", sha(before.toByteArray())).put("afterCanonicalSha256", sha(authorStamp(f.note.id).toByteArray()))
            .put("otherCanonicalSha256", sha(otherBefore.toByteArray())).put("schema", 12).put("pageObjectFormat", "IWO9")
            .put("excluded", "only viewport centerX/centerY/zoom and device last-visit preferences")
            .put("authorWrites", 0).put("implicitRatings", 0).toString(2))
        File(evidence, "$name-author.json").writeText(before)
        screenshot(name)
    }

    private fun checkAncestor(f: Fixture, label: String, before: String, otherBefore: String, wholeNotebook: Boolean = false) {
        // Notebook scope includes the unplaced manual card and has its own author/title
        // order. Check every exact fixture question once, not just a graph-first Q1.
        val expected = if (wholeNotebook) f.prompts.indices.toSet() else setOf(0, 1)
        val remaining = expected.toMutableSet()
        repeat(expected.size) { position ->
            waitFor("review-question")
            val prompt = compose.onNodeWithTag("review-question").fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text
            val question = f.prompts.indexOf(prompt)
            assertTrue("$label must visit each scoped fixture question exactly once: $prompt", remaining.remove(question))
            compose.onNodeWithText("手动回忆 · ${position + 1} / ${expected.size}").assertExists()
            assertHidden(f, question)
            tap("recall-context-tab-source"); assertPlaceholder("recall-context-source-placeholder")
            tap("recall-context-tab-map"); assertPlaceholder("recall-context-map-placeholder")
            compose.onNodeWithTag("recall-context-map-outline").assertDoesNotExist()
            tap("recall-context-tab-excerpt"); assertPlaceholder("recall-context-excerpt-placeholder")
            assertHidden(f, question)
            assertNoWrites(f, before, otherBefore)
            screenshot("ancestor-$label-q${question + 1}")
            if (remaining.isNotEmpty()) tap("branch-review-skip")
        }
        assertTrue("$label must cover its complete question scope", remaining.isEmpty())
        tap("branch-review-close")
        assertNoWrites(f, before, otherBefore)
    }

    // The former allRecallAncestorsHideCluesFromInteractiveWindows (CI 37198766363,
    // app-026) is split across these three runner cases, not reduced in scope:
    // 4 notebook ancestors / 10 questions, 3 library-map / 7, 2 library-notebook / 6.
    // Each case keeps the same timeout and byte-exact author/OS/pixel checks.
    @Test fun notebookRecallAncestorsHideCluesFromInteractiveWindows() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        screenshot("design-reading-map")
        val visited = mutableListOf<String>()
        for (branch in listOf(true, false)) {
            startGraph(f, branch); val label = if (branch) "book-branch" else "book-map"
            checkAncestor(f, label, before, otherBefore); visited += label
        }
        tap("study-close"); screenshot("design-writing"); tap("quick-settings"); tap("settings-knowledge"); startNotebook()
        checkAncestor(f, "book-knowledge", before, otherBefore, wholeNotebook = true); visited += "book-knowledge"; closeKnowledge()
        openMap(f); select(f.child); tap("node-more"); tap("node-view-content"); tap("card-properties"); startNotebook()
        checkAncestor(f, "book-card-properties", before, otherBefore, wholeNotebook = true); visited += "book-card-properties"; closeKnowledge(); tap("study-close")
        assertEquals(listOf("book-branch", "book-map", "book-knowledge", "book-card-properties"), visited)
        record("notebook-ancestors", f, before, otherBefore, JSONObject().put("ancestors", JSONArray(visited))
            .put("questionCount", 10).put("splitFrom", "allRecallAncestorsHideCluesFromInteractiveWindows"))
    }

    @Test fun libraryMapRecallAncestorsHideCluesFromInteractiveWindows() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        val visited = mutableListOf<String>()
        tap("study-close"); openLibraryMap(f)
        for (branch in listOf(true, false)) {
            startGraph(f, branch); val label = if (branch) "library-branch" else "library-map"
            checkAncestor(f, label, before, otherBefore); visited += label
        }
        select(f.child); tap("node-more"); tap("node-view-content"); tap("card-properties"); startNotebook()
        checkAncestor(f, "library-card-properties", before, otherBefore, wholeNotebook = true); visited += "library-card-properties"; closeKnowledge(); tap("study-close")
        assertEquals(listOf("library-branch", "library-map", "library-card-properties"), visited)
        record("library-map-ancestors", f, before, otherBefore, JSONObject().put("ancestors", JSONArray(visited))
            .put("questionCount", 7).put("splitFrom", "allRecallAncestorsHideCluesFromInteractiveWindows"))
    }

    @Test fun libraryNotebookRecallAncestorsHideCluesFromInteractiveWindows() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        val visited = mutableListOf<String>()
        tap("study-close")
        for (collection in listOf(false, true)) {
            openLibraryNotebook(f, collection); startNotebook()
            val label = if (collection) "library-collection" else "library-review"
            checkAncestor(f, label, before, otherBefore, wholeNotebook = true); visited += label; closeKnowledge()
        }
        assertEquals(listOf("library-review", "library-collection"), visited)
        record("library-notebook-ancestors", f, before, otherBefore, JSONObject().put("ancestors", JSONArray(visited))
            .put("questionCount", 6).put("splitFrom", "allRecallAncestorsHideCluesFromInteractiveWindows"))
    }

    @Test fun sharedCardAnnotationIsShieldedDuringRecallAndRestoredWithoutWrites() {
        val f=fixture();val annotation="RM69-PRIVATE-ANNOTATION-${f.card}"
        runBlocking{app.knowledge.submit(KnowledgeCommand(id(),f.note.id,id(),0,KnowledgeData.CardPresentation(f.card,annotation,CardTint.CREAM,CardTint.BLUE)))}
        val before=authorStamp(f.note.id);val otherBefore=authorStamp(f.other.id)
        select(f.child);tap("node-more");tap("node-view-content")
        compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)
        tap("card-review");waitFor("branch-review-counts")
        assertFalse(platformText().any{it.contains(annotation)})
        tap("branch-review-start");assertHidden(f)
        assertFalse(platformText().any{it.contains(annotation)})
        tap("review-show-hint")
        assertFalse("An independent annotation is not silently substituted for the frozen answer",platformText().any{it.contains(annotation)})
        tap("review-hide-hint");assertHidden(f)
        tap("branch-review-close");waitFor("card-full-annotation")
        compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)
        assertNoWrites(f,before,otherBefore)
    }

    @Test fun pageHintMasksOwnedPdfInkObjectsAndWarmNativeCachesWithoutWrites() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        startGraph(f); assertHidden(f); tap("recall-context-tab-source"); assertPlaceholder("recall-context-source-placeholder")
        repeat(2) { ordinal ->
            tap("review-show-hint"); waitFor("recall-context-source-canvas"); scrollTo("recall-context-source-canvas"); settleInk()
            val old = compose.runOnIdle { native<InkCanvasView>().also { assertFalse(it.allowInput); assertTrue(it.displayedStrokeCount > 0) } }
            assertRawPixels("recall-context-source-canvas", "page-hint-$ordinal").recycle()
            compose.onNodeWithTag("review-answer").assertDoesNotExist()
            compose.onNodeWithTag("recall-context-source-title").assertTextContains(f.note.title, substring = true)
            val scale = compose.runOnIdle { old.snapshotViewport().zoom }
            tap("recall-context-source-zoom-in"); compose.runOnIdle { assertTrue(old.snapshotViewport().zoom > scale) }
            tap("recall-context-source-fit"); settleInk()
            tap("review-hide-hint"); assertPlaceholder("recall-context-source-placeholder")
            compose.runOnIdle { assertFalse("The actual formerly warm source view must be detached", old.isAttachedToWindow) }
            assertHidden(f); assertNoWrites(f, before, otherBefore)
        }
        record("page-warm-cache", f, before, otherBefore, JSONObject().put("warmPermissionCycles", 2).put("ownedPdfBytesIncluded", true))
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapStrings(view: MindMapView, field: String): Map<String, String> = MindMapView::class.java.getDeclaredField(field).let {
        it.isAccessible = true; it.get(view) as Map<String, String>
    }

    @Test fun mapHintGatesNativeTopologyTitlesBodiesAndFoldedDescendants() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        startGraph(f, folded = true); assertHidden(f)
        tap("recall-context-tab-map"); assertPlaceholder("recall-context-map-placeholder")
        compose.onNodeWithTag("recall-context-map-outline").assertDoesNotExist()
        listOf(f.branch, f.child, f.duplicate).forEach { compose.onNodeWithTag("recall-context-map-node-$it").assertDoesNotExist() }
        tap("review-show-hint"); waitFor("recall-context-map")
        val raw = compose.runOnIdle { native<MindMapView>().also { view ->
            assertFalse(view.authorEditing)
            listOf(f.branch, f.child, f.duplicate).forEach { assertNotNull("Authorized graph includes folded descendants", view.nodeBounds(it)) }
            assertTrue(mapStrings(view, "titles").values.contains(f.title)); assertTrue(mapStrings(view, "titles").values.contains(f.branchTitle))
            assertTrue(mapStrings(view, "bodies").values.contains(f.answer))
        } }
        val rawPixels = capture("recall-context-map")
        assertTrue("Actual native graph positive control must draw text/edges", pixels(rawPixels) { Color.red(it) < 100 && Color.green(it) < 130 && Color.blue(it) < 130 } > 30)
        File(evidence, "map-hint-native.png").outputStream().use { rawPixels.compress(Bitmap.CompressFormat.PNG, 100, it) }; rawPixels.recycle()
        compose.onNodeWithTag("recall-context-map-node-${f.child}").assertTextEquals(f.title)
        tap("recall-context-map-fold-${f.branch}")
        compose.runOnIdle { assertNull(native<MindMapView>().nodeBounds(f.child)) }
        tap("recall-context-map-fold-${f.branch}")
        compose.runOnIdle { assertNotNull(native<MindMapView>().nodeBounds(f.child)) }
        tap("review-hide-hint"); assertPlaceholder("recall-context-map-placeholder")
        compose.runOnIdle { assertFalse(raw.isAttachedToWindow) }
        assertHidden(f)
        record("map-topology-mask", f, before, otherBefore, JSONObject().put("hiddenTopologyNodeCount", 0).put("authorizedNodeCount", 3))
    }

    @Test fun excerptHintGatesActualRenderedSnapshotAndNeverRevealsFrozenBodyImplicitly() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        startGraph(f); tap("recall-context-tab-excerpt"); assertPlaceholder("recall-context-excerpt-placeholder"); assertHidden(f)
        tap("review-show-hint"); waitFor("recall-context-excerpt-canvas"); scrollTo("recall-context-excerpt-canvas"); settleInk()
        val old = compose.runOnIdle { native<InkCanvasView>().also { assertTrue(it.preview); assertFalse(it.allowInput) } }
        assertRawPixels("recall-context-excerpt-canvas", "excerpt-hint").recycle()
        compose.onNodeWithTag("recall-context-card-title").assertTextEquals(f.title)
        compose.onNodeWithTag("recall-context-card-body").assertTextEquals("答案在本题面板显示")
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        assertNoWrites(f, before, otherBefore)
        tap("review-hide-hint"); assertPlaceholder("recall-context-excerpt-placeholder")
        compose.runOnIdle { assertFalse(old.isAttachedToWindow) }
        assertHidden(f)
        tap("reveal-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
        waitFor("recall-context-excerpt-canvas"); scrollTo("recall-context-excerpt-canvas"); settleInk()
        assertRawPixels("recall-context-excerpt-canvas", "excerpt-explicit-reveal").recycle()
        tap("branch-review-skip"); assertHidden(f, 1)
        tap("recall-context-tab-excerpt"); assertPlaceholder("recall-context-excerpt-placeholder")
        record("excerpt-reveal-mask", f, before, otherBefore, JSONObject().put("explicitAnswerReveal", true).put("nextQuestionReconcealed", true))
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    @Test fun narrowRotationSourceReturnAndNextQuestionResetPermissions() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600"); shell("wm density 320"); shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { val config = compose.activity.resources.configuration; kotlin.math.abs(config.screenWidthDp - 375) <= 4 && kotlin.math.abs(config.fontScale - 1.6f) < .02f }
            val f = fixture(longText = true); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
            startGraph(f); assertHidden(f); compose.onNodeWithTag("review-question").assertIsDisplayed()
            tap("review-show-hint"); tap("recall-context-tab-excerpt"); waitFor("recall-context-excerpt-canvas")
            compose.activityRule.scenario.recreate(); waitFor("review-question")
            compose.onNodeWithTag("review-hide-hint").assertExists(); compose.onNodeWithTag("review-answer").assertDoesNotExist()
            waitFor("recall-context-excerpt-canvas"); scrollTo("recall-context-excerpt-canvas"); settleInk()
            assertRawPixels("recall-context-excerpt-canvas", "narrow-restored-hint").recycle()
            tap("review-open-source"); waitFor("review-source-canvas"); settleInk()
            compose.runOnIdle { assertFalse(native<InkCanvasView>().allowInput) }
            compose.activityRule.scenario.recreate(); waitFor("review-source-canvas"); settleInk()
            tap("return-to-review"); waitFor("review-question")
            compose.onNodeWithTag("review-question").assertTextEquals(f.prompts[0]); compose.onNodeWithTag("review-hide-hint").assertExists()
            compose.onNodeWithTag("review-answer").assertDoesNotExist(); waitFor("recall-context-excerpt-canvas")
            tap("review-hide-hint"); assertPlaceholder("recall-context-excerpt-placeholder"); assertHidden(f)
            compose.activityRule.scenario.recreate(); waitFor("review-question"); assertHidden(f)
            tap("reveal-answer"); scrollTo("review-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer).assertIsDisplayed()
            compose.activityRule.scenario.recreate(); waitFor("review-answer"); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            tap("branch-review-skip"); assertHidden(f, 1); compose.onNodeWithTag("review-question").assertIsDisplayed()
            tap("recall-context-tab-source"); assertPlaceholder("recall-context-source-placeholder")
            tap("recall-context-tab-map"); assertPlaceholder("recall-context-map-placeholder")
            tap("recall-context-tab-excerpt"); assertPlaceholder("recall-context-excerpt-placeholder")
            record("narrow-rotation-source-next", f, before, otherBefore, JSONObject().put("widthDp", 375).put("fontScale", 1.6)
                .put("restoredHint", true).put("restoredExplicitAnswer", true).put("nextQuestionReconcealed", true))
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun exitRestoresAncestorRenderingAccessibilityDraftAndOtherBook() {
        val f = fixture(); val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        select(f.child)
        val originalMap = compose.runOnIdle { native<MindMapView>() }
        val originalAccessibility = compose.runOnIdle { roots().associateWith { it.importantForAccessibility } }
        compose.runOnIdle { notebook().edit(f.note.title + " draft", "RM53 synthetic unsaved author draft") }
        val draft = compose.runOnIdle { checkNotNull(notebook().ui.value.current) }
        assertTrue("A real existing author draft is retained by its original VM", draft.dirty)
        startGraph(f); assertHidden(f)
        compose.runOnIdle {
            // Android ViewOverlay is outside the ordinary child tree. Render the
            // actual covered root composite, including its real warm native views
            // and overlay, instead of findViewWithTag or an isolated cover object.
            val covered = roots().filter { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS && it.width > 0 && it.height > 0 }
            assertTrue("The real native ancestor is protected by an opaque overlay", covered.isNotEmpty())
            covered.forEachIndexed { index, root ->
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                assertTrue("Ancestor cover is opaque neutral, including over warm native caches", pixels(bitmap) { it == 0xfff7f7f3.toInt() } > bitmap.width * bitmap.height * .95)
                assertEquals(0, pixels(bitmap, ::magenta)); assertEquals(0, pixels(bitmap, ::cyan)); assertEquals(0, pixels(bitmap, ::lime))
                File(evidence, "ancestor-cover-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        tap("branch-review-close")
        compose.runOnIdle {
            originalAccessibility.forEach { (root, value) -> if (root.isAttachedToWindow) assertEquals(value, root.importantForAccessibility) }
            val retained = checkNotNull(notebook().ui.value.current)
            assertEquals(draft.base.id, retained.base.id); assertEquals(draft.title, retained.title); assertEquals(draft.text, retained.text)
            assertEquals(draft.pending, retained.pending); assertTrue(retained.dirty)
            assertSame("Window shielding does not replace the live original graph", originalMap, native<MindMapView>())
            assertTrue(mapStrings(originalMap, "titles").values.contains(f.title))
        }
        select(f.child); tap("node-more"); tap("node-view-content")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.answer)
        compose.onNodeWithTag("study-card-details").assertExists()
        assertTrue("Restored real ancestor accessibility includes the original card title", platformText().any { it.contains(f.title) })
        tap("card-back"); tap("study-close")
        settleInk(); assertRawPixels("ink-surface", "exit-original-paper").recycle()
        openPaper(f.other); settleInk()
        compose.runOnIdle { assertTrue(native<InkCanvasView>().displayedStrokeCount > 0) }
        val otherPixels = capture("ink-surface")
        assertTrue("An unrelated book remains drawable outside the question session", pixels(otherPixels, ::magenta) > 25); otherPixels.recycle()
        openPaper(f.note)
        compose.runOnIdle { assertEquals(draft.text, notebook().ui.value.current?.text); assertTrue(notebook().ui.value.current?.dirty == true) }
        record("exit-restore-draft-other-book", f, before, otherBefore, JSONObject().put("realExistingDraftRetained", true).put("ancestorAccessibilityRestored", true))
    }

    private fun atDisplay(size: String, density: Int?, font: Float, action: () -> Unit) {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size $size"); density?.let { shell("wm density $it") }; shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val config = compose.activity.resources.configuration
                kotlin.math.abs(config.fontScale - font) < .02f &&
                    (if (density == null) config.screenWidthDp >= 600 else kotlin.math.abs(config.screenWidthDp - size.substringBefore('x').toInt() * 160 / density) <= 4)
            }
            action()
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldDensity == null) "wm density reset" else "wm density $oldDensity")
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            compose.activityRule.scenario.recreate()
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun fullName(tag: String, name: String, moreThanTwoLines: Boolean = false) {
        if (tag.startsWith("tabs-full-name-")) {
            val buttonTag = "tabs-list-" + tag.removePrefix("tabs-full-name-")
            compose.onNodeWithTag("tabs-scroll").performScrollToNode(hasTestTag(buttonTag))
        }
        runCatching {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }.onFailure {
            screenshot("v54-tabs-missing")
            fun bounds(actual: String) = runCatching { compose.onNodeWithTag(actual).fetchSemanticsNode().boundsInRoot.toString() }.getOrNull()
            val input = compose.onNodeWithTag("tabs-filter").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            val ui = compose.runOnIdle { notebook().ui.value }
            val diagnostic = JSONObject().put("expectedTag", tag).put("filter", input).put("popupBounds", bounds("tabs-popup"))
                .put("scrollBounds", bounds("tabs-scroll")).put("imeWindows", JSONArray(automation.windows.map { it.type }))
                .put("matchingIds", JSONArray(ui.openIds.filter { id -> (ui.drafts[id]?.title ?: ui.notes.firstOrNull { it.id == id }?.title.orEmpty()).contains(input.orEmpty()) }))
            File(evidence, "v54-tabs-missing-diagnostic.json").writeText(diagnostic.toString(2))
            println("V54 popup diagnostic: $diagnostic")
        }.getOrThrow()
        scrollTo(tag)
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertTextEquals(name).assertIsDisplayed()
        runCatching { node.performScrollTo() }; compose.waitForIdle()
        val layout = textLayout(node)
        assertEquals(name, layout.layoutInput.text.text)
        // A short Text can allocate a wider paragraph than its measured box. Check
        // actual line extents so this still rejects clipping, not unused layout space.
        assertFalse("Complete names must not overflow vertically", layout.didOverflowHeight)
        repeat(layout.lineCount) {
            assertFalse("Complete names must not be ellipsized", layout.isLineEllipsized(it))
            assertTrue("Name line must fit its real left/right bounds: $tag", layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f)
            assertTrue("Name line must fit its real vertical bounds: $tag", layout.getLineTop(it) >= -1f && layout.getLineBottom(it) <= layout.size.height + 1f)
        }
        assertEquals("All name characters must be laid out", name.length, layout.getLineEnd(layout.lineCount - 1))
        if (moreThanTwoLines) assertTrue("Long Chinese names must remain readable beyond two lines", layout.lineCount > 2)
        if (tag.startsWith("tabs-full-name-") && moreThanTwoLines) screenshot("v54-full-name-narrow")
    }

    private fun dismissPopup(tag: String) {
        repeat(3) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            runCatching { compose.waitUntil(1_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }
        }
        compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    private fun selectedTab(note: Note, compact: Boolean = true) {
        if (compact) compose.waitForSavedInk()
        compose.runOnIdle { assertEquals(note.id, notebook().ui.value.selectedId); assertEquals(note.id, notebook().ui.value.current?.base?.id) }
        val count = compose.runOnIdle { notebook().ui.value.openIds.size }
        val list = compose.onNodeWithTag("tabs-list").assertIsDisplayed().assertIsEnabled()
        assertTrue(list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("已打开 $count") })
        val titleParent = if (compact) {
            // Ink uses the B1 document-title switcher at every width; full tabs live
            // in the text editor. Its popup remains the real selected-note control.
            compose.onNodeWithTag("notebook-tab-${note.id}").assertDoesNotExist()
            list.assertTextContains(note.title)
            assertTrue(list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("当前笔记：${note.title}") })
            "tabs-list"
        } else {
            val tab = compose.onNodeWithTag("notebook-tab-${note.id}").assertIsSelected().assertIsDisplayed()
            assertTrue(tab.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(note.title) })
            list.assertTextContains("已打开 $count", substring = true)
            "notebook-tab-${note.id}"
        }
        val title = compose.onNode(hasText(note.title) and hasAnyAncestor(hasTestTag(titleParent)), useUnmergedTree = true)
        assertEquals(TextOverflow.MiddleEllipsis, textLayout(title).layoutInput.overflow)
        val d = compose.activity.resources.displayMetrics.density
        assertTrue("Opened-notes control must retain a 48dp hit height", list.fetchSemanticsNode().boundsInRoot.height >= 48 * d - 1)
        assertTrue("Opened-notes control must retain a 48dp hit width", list.fetchSemanticsNode().boundsInRoot.width >= 48 * d - 1)
        tap("tabs-list"); filterTabs("")
        compose.onNodeWithTag("tabs-scroll").performScrollToNode(hasTestTag("tabs-list-${note.id}"))
        compose.onNodeWithTag("tabs-list-${note.id}").assertIsSelected().assertIsDisplayed()
        fullName("tabs-full-name-${note.id}", note.title)
        dismissPopup("tabs-popup")
    }

    private fun filterTabs(query: String) {
        val input = compose.onNodeWithTag("tabs-filter")
        input.performTextReplacement(query)
        compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().forEach { it.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        }
        compose.waitUntil(5_000) { automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        compose.waitForIdle()
    }

    private fun libraryTitles(f: Fixture, query: String) {
        compose.onNodeWithTag("library-search").performTextReplacement(query)
        if (compose.onAllNodesWithTag("library-grid").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithTag("library-more").fetchSemanticsNodes().isNotEmpty()) tap("library-more")
            tap("library-layout")
        }
        waitFor("library-grid")
        val heights = listOf(f.note, f.other).map { note ->
            scrollTo("note-tile-${note.id}"); waitFor("note-title-${note.id}")
            val title = compose.onNodeWithTag("note-title-${note.id}", useUnmergedTree = true).assertTextEquals(note.title)
            val layout = textLayout(title)
            assertEquals("Shelf title keeps the declared readable font", 15f, layout.layoutInput.style.fontSize.value, .01f)
            assertEquals(22f, layout.layoutInput.style.lineHeight.value, .01f)
            title.fetchSemanticsNode().boundsInRoot.height
        }
        assertEquals("Grid titles occupy the same two-line height despite different names", heights[0], heights[1], 2f)
    }

    private fun nativeFitAndText(f: Fixture): JSONObject {
        val font = compose.activity.resources.configuration.fontScale
        val layout = MapScenePainter.titleLayout(f.title, font)
        assertEquals(f.title, layout.text.toString())
        assertEquals(16f * font, layout.paint.textSize, .01f)
        assertTrue(layout.lineCount in 1..2)
        val lines = (0 until layout.lineCount).map { layout.text.subSequence(layout.getLineStart(it), layout.getLineEnd(it)).toString().trim() }
        if (f.title == "光合作用的三个关键步骤图解") {
            assertEquals("The real Chinese card title wraps into two readable lines", 2, layout.lineCount)
            repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        }
        assertTrue("Balanced native title must not strand a single character on its last line", lines.last().codePointCount(0, lines.last().length) > 1)
        var scale = 0f
        compose.runOnIdle {
            val view = native<MindMapView>(); view.fit(); scale = view.snapshotViewport().scale
            assertTrue("Fit retains the native readable scale", scale >= .8f)
            val first = checkNotNull(view.nodeBounds(f.branch))
            assertTrue("Fit keeps the leading real node within the native window", first.left >= 0 && first.top >= 0 && first.right <= view.width && first.bottom <= view.height)
            if (compose.activity.resources.configuration.screenWidthDp < 600) {
                assertTrue(view.focusNode(f.child))
                val child = checkNotNull(view.nodeBounds(f.child))
                assertTrue("The same actual card remains readable by focus in a narrow window", child.left >= 0 && child.right <= view.width && child.top >= 0 && child.bottom <= view.height)
            }
        }
        compose.waitForIdle()
        val bitmap = capture("study-map")
        try { assertTrue("The actual native canvas draws real title glyphs", pixels(bitmap) { Color.red(it) < 90 && Color.green(it) < 90 && Color.blue(it) < 100 } > 25) }
        finally { bitmap.recycle() }
        return JSONObject().put("fontScale", font).put("nativeFitScale", scale).put("actualTitleLines", JSONArray(lines))
    }

    @Test fun longChineseTabsLibraryAndBackupCancellationKeepAuthorRows() = atDisplay("750x1600", 320, 1.6f) {
        val f = fixture(longNames = true); val mapResult = nativeFitAndText(f)
        tap("study-close"); openPaper(f.other); openPaper(f.note)
        val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        selectedTab(f.note)
        repeat(2) {
            for ((note, tail) in listOf(f.other to "乙卷讨论", f.note to "甲卷原文")) {
                tap("tabs-list"); filterTabs(tail)
                val excluded = if (note.id == f.note.id) f.other else f.note
                compose.onNodeWithTag("tabs-list-${excluded.id}").assertDoesNotExist()
                fullName("tabs-full-name-${note.id}", note.title, moreThanTwoLines = true)
                tap("tabs-list-${note.id}"); waitFor("ink-surface"); selectedTab(note)
                assertNoWrites(f, before, otherBefore)
            }
        }
        tap("tabs-list"); filterTabs("乙卷讨论")
        tap("tabs-actions-${f.other.id}")
        compose.onNodeWithTag("tab-split-horizontal").assertIsEnabled()
        compose.onNodeWithTag("tab-split-vertical").assertIsEnabled()
        compose.onNodeWithTag("tab-close-note").assertIsEnabled()
        dismissPopup("tab-split-horizontal"); dismissPopup("tabs-popup"); selectedTab(f.note)
        tap("tabs-list"); filterTabs("乙卷讨论")
        tap("tabs-actions-${f.other.id}"); tap("tab-close-note"); dismissPopup("tabs-popup")
        compose.runOnIdle { assertFalse(f.other.id in notebook().ui.value.openIds); assertTrue(f.note.id in notebook().ui.value.openIds) }
        selectedTab(f.note); assertNoWrites(f, before, otherBefore)
        backToLibrary(); libraryTitles(f, f.note.title.substringBefore(' '))
        tap("note-menu-${f.note.id}"); fullName("note-menu-title-${f.note.id}", f.note.title, moreThanTwoLines = true)
        dismissPopup("notebook-actions-menu")
        compose.onNodeWithTag("library-backup-open").assertDoesNotExist()
        if (compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty()) tap("open-library-drawer")
        val settings = compose.onNodeWithText("设置与数据", useUnmergedTree = true)
        runCatching { settings.performScrollTo() }
        settings.assertIsDisplayed().performClick()
        waitFor("workspace-settings"); tap("library-backup-open"); waitFor("library-backup-dialog")
        compose.runOnIdle { assertEquals(BackupMode.HOME, provider()[LibraryBackupViewModel::class.java].ui.value.mode) }
        tap("backup-create"); compose.onNodeWithTag("backup-generate").assertIsNotEnabled()
        tap("backup-consent"); compose.onNodeWithTag("backup-generate").assertIsEnabled()
        compose.onNode(hasText("取消") and hasAnyAncestor(hasTestTag("library-backup-dialog")), useUnmergedTree = true).performClick()
        compose.waitForIdle(); compose.onNodeWithTag("library-backup-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(BackupMode.CLOSED, provider()[LibraryBackupViewModel::class.java].ui.value.mode) }
        compose.onNodeWithContentDescription("关闭设置").performClick(); waitFor("new-note")
        compose.onNodeWithTag("library-search").performTextReplacement(f.note.title)
        tap("note-cover-${f.note.id}"); compose.singlePageEditor(); waitFor("ink-surface"); selectedTab(f.note)
        record("v54-long-tabs-backup-cancel", f, before, otherBefore, JSONObject().put("uiPolishVersion", 54).put("widthDp", 375)
            .put("fontScale", 1.6).put("longNamesBeyondTwoLines", true).put("repeatedRealTabSwitches", 4)
            .put("backupConfirmedThenCancelledBeforeGeneration", true).put("nativeMap", mapResult))
    }

    @Test fun tabletMapTextFitAndNeutralRecallPlaceholdersKeepAuthorRows() = atDisplay("1920x1200", null, 1f) {
        val f = fixture(); val mapResult = nativeFitAndText(f)
        val before = authorStamp(f.note.id); val otherBefore = authorStamp(f.other.id)
        screenshot("v54-after-map")
        tap("study-close")
        openPaper(f.other); openPaper(f.note); selectedTab(f.note)
        screenshot("v54-after-writing")
        tap("quick-settings"); tap("mode-text"); waitFor("note-body")
        selectedTab(f.note, compact = false)
        val d = compose.activity.resources.displayMetrics.density
        fun tabWidth(note: Note) = compose.onNodeWithTag("notebook-tab-${note.id}").fetchSemanticsNode().boundsInRoot.width +
            compose.onNodeWithTag("close-tab-${note.id}").fetchSemanticsNode().boundsInRoot.width
        assertEquals(220 * d, tabWidth(f.note), 2 * d); assertEquals(168 * d, tabWidth(f.other), 2 * d)
        compose.onNodeWithTag("notebook-tab-${f.other.id}").assertIsNotSelected()
        tap("tabs-list"); tap("tabs-hide")
        compose.onNodeWithTag("notebook-tab-${f.note.id}").assertDoesNotExist()
        compose.onNodeWithTag("notebook-tab-${f.other.id}").assertDoesNotExist()
        compose.onNodeWithTag("tabs-list").assertIsDisplayed().assertIsEnabled()
        tap("tabs-list"); tap("tabs-hide"); selectedTab(f.note, compact = false)
        tap("mode-ink"); compose.singlePageEditor(); selectedTab(f.note)
        assertNoWrites(f, before, otherBefore)
        backToLibrary(); libraryTitles(f, f.note.title.substringAfterLast('-'))
        tap("note-menu-${f.note.id}"); fullName("note-menu-title-${f.note.id}", f.note.title); dismissPopup("notebook-actions-menu")
        screenshot("v54-after-library")
        compose.onNodeWithTag("library-search").performTextReplacement(f.note.title)
        tap("note-cover-${f.note.id}"); compose.singlePageEditor(); waitFor("ink-surface"); openMap(f); startGraph(f)
        assertHidden(f); assertPlaceholder("recall-context-source-placeholder")
        tap("recall-context-tab-excerpt"); assertPlaceholder("recall-context-excerpt-placeholder")
        screenshot("v54-after-mask")
        shell("settings put system font_scale 1.6"); compose.activityRule.scenario.recreate(); waitFor("review-question", diagnoseOnFailure = true)
        compose.waitUntil(15_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - 1.6f) < .02f }
        for ((tab, placeholder) in listOf("source" to "source", "map" to "map", "excerpt" to "excerpt")) {
            tap("recall-context-tab-$tab"); assertPlaceholder("recall-context-$placeholder-placeholder"); assertHidden(f)
        }
        tap("recall-context-tab-map"); tap("review-show-hint"); waitFor("recall-context-map")
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.runOnIdle { assertFalse(native<MindMapView>().authorEditing) }
        val largeLayout = MapScenePainter.titleLayout(f.title, 1.6f)
        val last = largeLayout.text.subSequence(largeLayout.getLineStart(largeLayout.lineCount - 1), largeLayout.getLineEnd(largeLayout.lineCount - 1)).toString().trim()
        assertTrue(last.codePointCount(0, last.length) > 1); assertEquals(25.6f, largeLayout.paint.textSize, .01f)
        tap("review-hide-hint"); assertPlaceholder("recall-context-map-placeholder"); assertHidden(f)
        screenshot("v54-tablet-font16-mask")
        tap("branch-review-close"); tap("study-close"); waitFor("ink-surface")
        record("v54-tablet-fit-mask", f, before, otherBefore, JSONObject().put("uiPolishVersion", 54)
            .put("pairedScreenshotPixels", "1920x1200").put("pairedScreenshotFontScale", 1.0)
            .put("pairedScreenshotDensityDpi", compose.activity.resources.displayMetrics.densityDpi)
            .put("largeFontScale", 1.6).put("neutralNativeType", "android.widget.TextView")
            .put("nativeMap", mapResult).put("hintDoesNotRevealBody", true))
    }

    @Test fun explicitKnownAndUnknownRatingRetainOriginalCasAndReceiptIdentity() {
        val f = fixture(); val faultDatabase = NoteDatabase.open(app); val failBeforeReceipt = AtomicBoolean(false)
        val saved = SavedStateHandle(); val key = "branch-review-${f.note.id}"
        val store = compose.activity.viewModelStore; var previous: ViewModel? = null
        val otherBefore = authorStamp(f.other.id)
        val reviewIds = f.questions.take(2).joinToString(",") { "'$it'" }
        val originalReceiptIds = runBlocking {
            faultDatabase.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT operationId FROM knowledge_receipts WHERE notebookId=? AND resultId IN ($reviewIds)", arrayOf(f.note.id)
            )).use { cursor -> buildList { while (cursor.moveToNext()) add("'${cursor.getString(0)}'") }.joinToString(",") }
        }
        val noteBefore = runBlocking { checkNotNull(faultDatabase.notes().note(f.note.id)) }
        val unaffectedQueries = authorQueries.map { sql -> when {
            // Only the two explicit ratings may touch this NoteRow timestamp; every other field is checked below.
            sql == "SELECT * FROM notes WHERE id=?" -> "SELECT id,revision,title,text FROM notes WHERE id=?"
            sql.startsWith("SELECT * FROM knowledge_records") -> sql.replace("WHERE notebookId=?", "WHERE notebookId=? AND id NOT IN ($reviewIds)")
            sql.startsWith("SELECT * FROM knowledge_revisions") -> sql.replace("WHERE notebookId=?", "WHERE notebookId=? AND (id NOT IN ($reviewIds) OR revision<>2)")
            sql.startsWith("SELECT * FROM knowledge_receipts") -> sql.replace("WHERE notebookId=?", "WHERE notebookId=? AND (resultId NOT IN ($reviewIds) OR operationId IN ($originalReceiptIds))")
            else -> sql
        } }
        val unaffectedBefore = authorStamp(f.note.id, unaffectedQueries)
        val initialReceipts = runBlocking { faultDatabase.knowledge().forBook(f.note.id).associate { it.id to it.revision } }
        val ratingRepository = KnowledgeRepository(faultDatabase) { stage ->
            if (stage == KnowledgeFault.BEFORE_RECEIPT && failBeforeReceipt.get()) throw java.io.IOException("Synthetic failed transaction before rating receipt")
        }
        try {
            compose.runOnIdle {
                previous = store.get(key)
                store.put(key, KnowledgeViewModel(ratingRepository, saved, app.resourcePacks))
            }
            startGraph(f); assertHidden(f)
            val before = authorStamp(f.note.id)
            tap("review-show-hint"); tap("recall-context-tab-map"); waitFor("recall-context-map")
            compose.onNodeWithTag("review-answer").assertDoesNotExist(); assertEquals(before, authorStamp(f.note.id))
            tap("review-hide-hint"); assertHidden(f); assertEquals(before, authorStamp(f.note.id))
            tap("reveal-answer"); tap("branch-review-mark-UNDERSTOOD"); waitFor("review-question"); assertHidden(f, 1)
            runBlocking { assertEquals(2L, checkNotNull(faultDatabase.knowledge().get(f.questions[0])).revision) }
            val afterKnown = authorStamp(f.note.id)
            val noteAfterKnown = runBlocking { checkNotNull(faultDatabase.notes().note(f.note.id)) }
            assertEquals(noteBefore.copy(updatedAt = noteAfterKnown.updatedAt), noteAfterKnown)
            tap("review-show-hint"); tap("review-hide-hint"); assertEquals(afterKnown, authorStamp(f.note.id))
            failBeforeReceipt.set(true)
            tap("reveal-answer"); tap("branch-review-mark-REVIEW"); waitFor("branch-review-retry")
            val request = checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList()
            val payload = checkNotNull(saved.get<ByteArray>("knowledge.payload")).clone()
            val expectedCardRevision = checkNotNull(saved.get<Long>("knowledge.reviewCardRevision"))
            val operation = request[0]
            val capturedCommand = KnowledgeCommand(request[0], request[1], request[2], request[3].toLong(), KnowledgeCodec.decode(payload), request[4].toBooleanStrict())
            assertEquals(f.questions[1], request[2]); assertEquals(1L, expectedCardRevision)
            runBlocking {
                assertNull("The failed transaction has no committed receipt", faultDatabase.knowledge().receipt(operation))
                assertEquals(1L, checkNotNull(faultDatabase.knowledge().get(f.questions[1])).revision)
                assertEquals(noteAfterKnown, faultDatabase.notes().note(f.note.id))
            }
            assertEquals("Unknown before receipt must roll back all author rows", afterKnown, authorStamp(f.note.id))
            compose.onNodeWithTag("branch-review-close").assertIsNotEnabled()
            compose.onNodeWithTag("branch-review-skip").assertIsNotEnabled()
            compose.activityRule.scenario.recreate(); waitFor("branch-review-retry")
            compose.onNodeWithTag("review-question").assertTextEquals(f.prompts[1]); compose.onNodeWithTag("review-answer").assertTextEquals(f.answer)
            assertEquals(request, checkNotNull(saved.get<ArrayList<String>>("knowledge.request")).toList())
            assertArrayEquals(payload, checkNotNull(saved.get<ByteArray>("knowledge.payload")))
            assertEquals(expectedCardRevision, saved.get<Long>("knowledge.reviewCardRevision"))
            runBlocking { assertNull(faultDatabase.knowledge().receipt(operation)) }
            assertEquals(afterKnown, authorStamp(f.note.id))
            failBeforeReceipt.set(false); tap("branch-review-retry"); waitFor("branch-review-ended")
            assertNull(saved.get<ArrayList<String>>("knowledge.request"))
            val committedAfterRetry = authorStamp(f.note.id)
            val noteAfterRetry = runBlocking { checkNotNull(faultDatabase.notes().note(f.note.id)) }
            assertEquals(noteAfterKnown.copy(updatedAt = noteAfterRetry.updatedAt), noteAfterRetry)
            val receipt = runBlocking { checkNotNull(faultDatabase.knowledge().receipt(operation)) }
            assertEquals(f.questions[1], receipt.resultId); assertEquals(capturedCommand.digest(), receipt.digest)
            assertEquals(KnowledgeOutcome.Success(f.questions[1]), runBlocking { ratingRepository.reviewOutcome(capturedCommand, expectedCardRevision) })
            assertEquals("Repeating the captured original command reads its receipt without author writes", committedAfterRetry, authorStamp(f.note.id))
            runBlocking { assertEquals(receipt, faultDatabase.knowledge().receipt(operation)); assertEquals(noteAfterRetry, faultDatabase.notes().note(f.note.id)) }
            runBlocking {
                listOf(f.questions[0] to ManualState.UNDERSTOOD, f.questions[1] to ManualState.REVIEW).forEach { (question, state) ->
                    val row = checkNotNull(faultDatabase.knowledge().get(question)); assertEquals(2L, row.revision)
                    assertEquals(state, (row.data() as KnowledgeData.Question).state)
                    assertNotNull(faultDatabase.knowledge().revision(question, 1)); assertNull(faultDatabase.knowledge().revision(question, 3))
                    faultDatabase.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM knowledge_receipts WHERE resultId=?", arrayOf(question))).use { cursor ->
                        check(cursor.moveToFirst()); assertEquals("One create and exactly one explicit rating receipt", 2L, cursor.getLong(0))
                    }
                }
                assertEquals(initialReceipts.getValue(f.questions[2]), checkNotNull(faultDatabase.knowledge().get(f.questions[2])).revision)
            }
            assertEquals("Only the two explicit CAS Question mutations and their NoteRow timestamps are permitted; old history and receipts are exact", unaffectedBefore, authorStamp(f.note.id, unaffectedQueries))
            assertEquals(otherBefore, authorStamp(f.other.id))
            File(evidence, "known-unknown-rating-result.json").writeText(JSONObject().put("syntheticFixture", true)
                .put("case", "known-unknown-rating").put("capturedOriginalOperationId", operation).put("capturedQuestionId", request[2])
                .put("capturedQuestionRevision", request[3]).put("expectedCardRevision", expectedCardRevision).put("explicitRatingReceipts", 2)
                .put("implicitRatings", 0).put("unknownFault", "BEFORE_RECEIPT").put("pendingHasReceipt", false)
                .put("unknownFirstRetryCreatesOneReceipt", true).put("repeatedRetryAddsReceipt", false)
                .put("explicitMarksNoteUpdatedAtOnly", true).put("noteUpdatedAtBefore", noteBefore.updatedAt)
                .put("noteUpdatedAtAfterKnown", noteAfterKnown.updatedAt).put("noteUpdatedAtAfterRetry", noteAfterRetry.updatedAt)
                .put("unaffectedAuthorSha256", sha(unaffectedBefore.toByteArray())).put("pendingCanonicalSha256", sha(afterKnown.toByteArray()))
                .put("committedAfterRetryCanonicalSha256", sha(committedAfterRetry.toByteArray())).toString(2))
            File(evidence, "known-unknown-rating-author.json").writeText(committedAfterRetry)
            screenshot("known-unknown-rating")
            tap("branch-review-close")
        } finally {
            try { compose.runOnIdle { store.put(key, previous ?: KnowledgeViewModel(app.knowledge, SavedStateHandle(), app.resourcePacks)) } }
            finally { faultDatabase.close() }
        }
    }
}
