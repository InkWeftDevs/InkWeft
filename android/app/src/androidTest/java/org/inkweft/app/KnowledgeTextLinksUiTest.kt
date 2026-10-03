package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Exercises actual map/card routes and glyph hits on the author's plain summary text. */
class KnowledgeTextLinksUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as InkWeftApplication
    private var probeDatabase: NoteDatabase? = null
    private val probe get() = probeDatabase ?: NoteDatabase.open(app).also { probeDatabase = it }
    private fun id() = UUID.randomUUID().toString()

    @After fun closeProbe() {
        probeDatabase?.close()
        probeDatabase = null
    }

    private data class Fixture(
        val origin: Note,
        val books: List<Note>,
        val map: String,
        val node: String,
        val card: String,
        val body: String,
        val viewport: MapViewport,
        val liveCard: String,
        val liveLink: String,
        val replacementCard: String,
        val duplicateLinks: List<String>,
        val pinnedCard: String,
        val pinnedLink: String,
    )

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tap(tag: String, physical: Boolean = false) {
        compose.revealAction(tag)
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed()
        if (physical) node.performTouchInput { click() } else node.performClick()
        compose.waitForIdle()
    }

    private fun map(): MindMapView {
        val queue = java.util.ArrayDeque<View>()
        queue.add(compose.activity.window.decorView)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is MindMapView && view.isShown) return view
            if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
        error("Visible native map is missing")
    }

    private fun study(book: String) =
        ViewModelProvider(compose.activity)["study-" + book, StudyViewModel::class.java]

    private fun select(node: String) {
        compose.waitUntil(15_000) {
            var available = false
            compose.runOnIdle { available = runCatching { map().nodeBounds(node) != null }.getOrDefault(false) }
            available
        }
        var point = Offset.Zero
        compose.runOnIdle {
            map().focusNode(node)
            val bounds = checkNotNull(map().nodeBounds(node))
            point = Offset(bounds.centerX(), bounds.centerY())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("study-map").performTouchInput { click(point) }
        waitFor("node-actions")
    }

    private fun fixture(longBody: Boolean = false): Fixture {
        waitFor("new-note")
        val books = runBlocking {
            listOf("原摘要", "概率笔记", "同名甲笔记", "同名乙笔记").map {
                app.workspaceRepository.create(it + " " + id().take(6), false, PaperStyle.RULED)
            }
        }
        val origin = books[0]
        val mapId = id()
        val node = id()
        val card = id()
        val liveCard = id()
        val pinnedCard = id()
        val replacementCard = id()
        val duplicates = listOf(id(), id())
        val liveLink = id()
        val pinnedLink = id()
        val duplicateLinks = listOf(id(), id())
        val body = "条件概率与条件别名需要一起理解。\n共同名称需要自己选择目标；固定概念保留历史。\n未确认名称只是普通文字。" +
            if (longBody) "\n" + (1..42).joinToString("\n") {
                "摘要段 " + it + "：先确定条件与样本空间，区分当前内容和已保存的固定摘录，再逐步检查每一个推导。"
            } else ""
        runBlocking {
            app.knowledge.submit(KnowledgeCommand(id(), origin.id, mapId, 0,
                KnowledgeData.MapDefinition("标题链接验收图")))
            app.study.submit(StudyCommand(id(), origin.id, StudyAction.CREATE, cardId = card,
                nodeId = node, title = "保持原卡", body = body, x = 40.0, y = 80.0, mapId = mapId))
            app.study.submit(StudyCommand(id(), origin.id, StudyAction.CREATE, cardId = id(),
                nodeId = id(), parentId = node, title = "邻近主题", body = "不属于本次预览",
                x = 320.0, y = 180.0, mapId = mapId))
            app.study.submit(StudyCommand(id(), books[1].id, StudyAction.CREATE, cardId = liveCard,
                nodeId = id(), title = "条件概率", body = "实时原答案 LIVE-V1"))
            app.study.submit(StudyCommand(id(), books[1].id, StudyAction.CREATE, cardId = pinnedCard,
                nodeId = id(), title = "固定概念", body = "固定原答案 PIN-V1"))
            app.study.submit(StudyCommand(id(), books[1].id, StudyAction.CREATE, cardId = id(),
                nodeId = id(), title = "未确认名称", body = "没有正式关联，不能自动跳转"))
            app.study.submit(StudyCommand(id(), books[1].id, StudyAction.CREATE, cardId = replacementCard,
                nodeId = id(), title = "替换目标", body = "替换目标答案 NEW-TARGET"))
            duplicates.forEachIndexed { index, target ->
                app.study.submit(StudyCommand(id(), books[index + 2].id, StudyAction.CREATE, cardId = target,
                    nodeId = id(), title = "共同名称", body = if (index == 0) "同名甲答案 NAME-A" else "同名乙答案 NAME-B"))
            }
            app.knowledge.submit(KnowledgeCommand(id(), books[1].id, id(), 0,
                KnowledgeData.Alias(liveCard, "条件别名")))
            val source = TargetRef(TargetKind.CARD, card)
            app.knowledge.submit(KnowledgeCommand(id(), origin.id, liveLink, 0,
                KnowledgeData.Link(source, TargetRef(TargetKind.CARD, liveCard))))
            app.knowledge.submit(KnowledgeCommand(id(), origin.id, pinnedLink, 0,
                KnowledgeData.Link(source, TargetRef(TargetKind.CARD, pinnedCard), pinnedRevision = 1)))
            duplicateLinks.forEachIndexed { index, link ->
                app.knowledge.submit(KnowledgeCommand(id(), origin.id, link, 0,
                    KnowledgeData.Link(source, TargetRef(TargetKind.CARD, duplicates[index]))))
            }
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(origin) }
        compose.singlePageEditor()
        compose.waitForSavedInk()
        tap("quick-study")
        tap("study-map-picker")
        tap("study-map-" + mapId)
        select(node)
        lateinit var viewport: MapViewport
        compose.runOnIdle { viewport = map().snapshotViewport() }
        tap("node-more")
        tap("node-view-content")
        waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        waitFor("card-knowledge-links")
        return Fixture(origin, books, mapId, node, card, body, viewport, liveCard, liveLink,
            replacementCard, duplicateLinks, pinnedCard, pinnedLink)
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("card-full-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Real summary text must provide its text layout", it(results))
        }
        return results.single()
    }

    /** Reads real glyph bounds, then uses pointer input; it never invokes a synthetic link callback. */
    private fun clickInline(f: Fixture, term: String, linked: Boolean = true) {
        val start = f.body.indexOf(term)
        assertTrue("Fixture term is missing", start >= 0)
        var result: TextLayoutResult? = null
        compose.waitUntil(15_000) {
            runCatching {
                val current = layout()
                result = current
                current.layoutInput.text.getLinkAnnotations(start, start + term.length).isNotEmpty() == linked
            }.getOrDefault(false)
        }
        val text = compose.onNodeWithTag("card-full-body").assertTextEquals(f.body).assertIsDisplayed()
        val glyph = checkNotNull(result).getBoundingBox(start + term.length / 2)
        val semantics = text.fetchSemanticsNode()
        val global = semantics.positionInRoot + Offset(glyph.center.x, glyph.center.y)
        val visible = semantics.boundsInRoot
        assertTrue("The clicked glyph must be visibly inside the summary",
            global.x > visible.left && global.x < visible.right && global.y > visible.top && global.y < visible.bottom)
        val local = global - visible.topLeft
        val annotations = checkNotNull(result).layoutInput.text.getLinkAnnotations(start, start + term.length)
            .joinToString { range ->
                "[" + range.start + "," + range.end + ") tag=" +
                    ((range.item as? LinkAnnotation.Clickable)?.tag ?: range.item.toString())
            }
        File(app.getExternalFilesDir(null), "knowledge-text-links-clicks.txt").appendText(
            "stamp=" + android.os.SystemClock.elapsedRealtimeNanos() + " book=" + f.origin.id +
                " term=" + term + " start=" + start + " glyph=" + glyph +
                " position=" + semantics.positionInRoot + " global=" + global + " visible=" + visible +
                " local=" + local + " annotations=" + annotations + "\n")
        text.performTouchInput { click(local) }
        compose.waitForIdle()
    }

    private fun assertPreview(body: String) {
        try {
            waitFor("card-link-preview-body")
        } catch (error: ComposeTimeoutException) {
            val stamp = android.os.SystemClock.elapsedRealtimeNanos()
            runCatching { shot("preview-timeout-" + stamp) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                val count = roots.fetchSemanticsNodes().size
                val trees = (0 until count).joinToString("\n\n") { index ->
                    "UNMERGED ROOT " + index + "\n" + roots[index].printToString(maxDepth = Int.MAX_VALUE)
                }
                val file = File(app.getExternalFilesDir(null), "knowledge-text-links-failure-" + stamp + ".txt")
                file.writeText("Expected preview body: " + body + "\n\n" + trees +
                    "\n\nClick evidence: knowledge-text-links-clicks.txt\n")
                println("KNOWLEDGE_TEXT_LINK_FAILURE_EVIDENCE=" + file.absolutePath)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        compose.onNodeWithTag("card-link-preview-body").assertTextEquals(body)
    }

    private fun assertOrigin(f: Fixture) {
        waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body)
        compose.runOnIdle {
            assertEquals(f.origin.id, ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
            assertEquals(f.map, study(f.origin.id).mapId.value)
            assertEquals(f.node, study(f.origin.id).selectedByMap[f.map])
            assertEquals(f.viewport, map().snapshotViewport())
        }
    }

    private fun returnToCard(f: Fixture, physical: Boolean = false, pickerExpected: Boolean = false) {
        tap("card-link-close-preview", physical)
        if (pickerExpected) {
            waitFor("card-link-picker")
            tap("card-link-close-picker", physical)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("card-link-preview").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("card-link-picker").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("card-link-preview").assertDoesNotExist()
        compose.onNodeWithTag("card-link-picker").assertDoesNotExist()
        assertOrigin(f)
    }

    private val choices get() = SemanticsMatcher("confirmed card-link choices") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("card-link-choice-") == true
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** Author records and audit counts across source and target notebooks; view preferences are excluded. */
    private fun authorStamp(f: Fixture): List<String> = runBlocking {
        probe.withTransaction {
            buildList {
                f.books.forEach { book ->
                    add("book:" + book.id)
                    probe.study().cards(book.id).forEach { add("card:" + it) }
                    probe.study().nodes(book.id).forEach { add("node:" + it) }
                    probe.knowledge().forBook(book.id).forEach {
                        add("knowledge:" + it.id + ":" + it.revision + ":" + it.removed + ":" + sha(it.payload))
                    }
                    add("ink-page:" + probe.ink().page(book.id))
                    probe.ink().strokes(book.id).forEach {
                        add("ink:" + it.id + ":" + it.visible + ":" + it.createdRevision + ":" + sha(it.payload))
                    }
                    listOf(
                        "SELECT COUNT(*) FROM knowledge_revisions WHERE notebookId=?",
                        "SELECT COUNT(*) FROM knowledge_receipts WHERE notebookId=?",
                        "SELECT COUNT(*) FROM study_receipts WHERE notebookId=?",
                        "SELECT COUNT(*) FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId WHERE c.notebookId=?",
                        "SELECT COUNT(*) FROM ink_receipts WHERE noteId=?",
                    ).forEachIndexed { index, sql ->
                        probe.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book.id))).use {
                            check(it.moveToFirst())
                            add("audit-" + index + ":" + it.getLong(0))
                        }
                    }
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(app.getExternalFilesDir(null), "knowledge-text-links-" + name + ".png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice() {
        val f = fixture()
        val before = authorStamp(f)
        clickInline(f, "条件概率")
        assertPreview("实时原答案 LIVE-V1")
        compose.onNodeWithTag("card-link-picker").assertDoesNotExist()
        returnToCard(f)
        clickInline(f, "条件别名")
        assertPreview("实时原答案 LIVE-V1")
        returnToCard(f)
        clickInline(f, "共同名称")
        waitFor("card-link-picker")
        compose.onAllNodes(choices).assertCountEquals(2)
        compose.onNodeWithTag("card-link-preview").assertDoesNotExist()
        f.duplicateLinks.forEachIndexed { index, link ->
            compose.onNodeWithTag("card-link-choice-" + link).assertTextContains(f.books[index + 2].title, substring = true)
        }
        shot("same-name-choice")
        tap("card-link-choice-" + f.duplicateLinks[1])
        assertPreview("同名乙答案 NAME-B")
        returnToCard(f, pickerExpected = true)
        clickInline(f, "未确认名称", linked = false)
        compose.onNodeWithTag("card-link-picker").assertDoesNotExist()
        compose.onNodeWithTag("card-link-preview").assertDoesNotExist()
        assertOrigin(f)
        assertEquals("Reading links must not create author commands or receipts", before, authorStamp(f))
        clickInline(f, "条件别名")
        assertPreview("实时原答案 LIVE-V1")
        tap("card-link-open-target")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("card-full-body").assertTextEquals("实时原答案 LIVE-V1") }.isSuccess
        }
        compose.onNode(hasText("条件概率") and hasAnyAncestor(hasTestTag("study-card-details")),
            useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals("Valid Open must resolve the captured target notebook", f.books[1].id,
                ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
        }
        assertEquals("Opening the existing target must not copy content or write receipts", before, authorStamp(f))
    }

    @Test fun reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory() {
        val f = fixture()
        clickInline(f, "条件概率")
        assertPreview("实时原答案 LIVE-V1")
        returnToCard(f)
        clickInline(f, "固定概念")
        assertPreview("固定原答案 PIN-V1")
        returnToCard(f)
        runBlocking {
            app.study.submit(StudyCommand(id(), f.books[1].id, StudyAction.EDIT, cardId = f.liveCard,
                expectedRevision = 1, title = "条件概率新版", body = "实时新答案 LIVE-V2"))
            app.study.submit(StudyCommand(id(), f.books[1].id, StudyAction.EDIT, cardId = f.pinnedCard,
                expectedRevision = 1, title = "固定概念新版", body = "固定卡的新答案 PIN-V2"))
        }
        val afterEdit = authorStamp(f)
        clickInline(f, "条件别名")
        assertPreview("实时新答案 LIVE-V2")
        returnToCard(f)
        tap("card-knowledge-links")
        tap("card-link-choice-" + f.pinnedLink)
        assertPreview("固定原答案 PIN-V1")
        compose.onNodeWithText("固定摘录 · 修订 1", useUnmergedTree = true).assertExists()
        shot("pinned-history")
        returnToCard(f, pickerExpected = true)
        runBlocking {
            assertEquals("固定卡的新答案 PIN-V2", checkNotNull(probe.study().card(f.pinnedCard)).body)
            assertEquals("固定原答案 PIN-V1", checkNotNull(probe.study().cardVersion(f.pinnedCard, 1)).body)
        }
        assertEquals(afterEdit, authorStamp(f))
    }

    @Test fun pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting() {
        val f = fixture()
        val before = authorStamp(f)
        clickInline(f, "共同名称")
        waitFor("card-link-picker")
        compose.activityRule.scenario.recreate()
        waitFor("card-link-picker")
        compose.onAllNodes(choices).assertCountEquals(2)
        assertOrigin(f)
        tap("card-link-choice-" + f.duplicateLinks[0])
        assertPreview("同名甲答案 NAME-A")
        compose.activityRule.scenario.recreate()
        assertPreview("同名甲答案 NAME-A")
        assertOrigin(f)
        shot("recreated-preview")
        returnToCard(f, pickerExpected = true)
        tap("card-back")
        compose.onNodeWithTag("study-card-details").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(f.node, study(f.origin.id).selectedByMap[f.map])
            assertEquals(f.map, study(f.origin.id).mapId.value)
            assertEquals(f.viewport, map().snapshotViewport())
        }
        assertEquals(before, authorStamp(f))
    }

    /** Observe may disable Open first, or the click may reach its own read-only revalidation first. */
    private fun assertCapturedLinkCannotOpen(f: Fixture) {
        val open = compose.onNodeWithTag("card-link-open-target")
        if (runCatching { open.assertIsEnabled() }.isSuccess) {
            runCatching { open.performScrollTo() }
            open.performClick()
        }
        waitFor("card-link-preview-error")
        compose.onNodeWithTag("card-link-preview-error").assertTextEquals("关联已变化，请返回列表重新选择。")
        open.assertIsNotEnabled()
        compose.onNodeWithTag("card-link-preview-body").assertDoesNotExist()
        assertOrigin(f)
        assertNull("Invalid link must not queue navigation to a different target", app.openKnowledgeTarget.value)
    }

    @Test fun replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget() {
        val f = fixture()
        clickInline(f, "条件概率")
        assertPreview("实时原答案 LIVE-V1")
        runBlocking {
            val old = checkNotNull(probe.knowledge().get(f.liveLink))
            val changed = (old.data() as KnowledgeData.Link).copy(target = TargetRef(TargetKind.CARD, f.replacementCard))
            app.knowledge.submit(KnowledgeCommand(id(), f.origin.id, old.id, old.revision, changed))
        }
        val afterReplacement = authorStamp(f)
        assertCapturedLinkCannotOpen(f)
        assertEquals(afterReplacement, authorStamp(f))
        returnToCard(f)
        tap("card-knowledge-links")
        tap("card-link-choice-" + f.liveLink)
        assertPreview("替换目标答案 NEW-TARGET")
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(f.liveLink))
            app.knowledge.submit(KnowledgeCommand(id(), f.origin.id, row.id, row.revision, row.data(), true))
        }
        val afterRemoval = authorStamp(f)
        assertCapturedLinkCannotOpen(f)
        assertEquals(afterRemoval, authorStamp(f))
        shot("removed-link")
        returnToCard(f, pickerExpected = true)
        runBlocking {
            val row = checkNotNull(probe.knowledge().get(f.liveLink))
            assertEquals(3L, row.revision)
            assertTrue(row.removed)
        }
    }

    private fun connectionItem(tag: String) {
        waitFor("knowledge-links-list")
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertExists()
        }.isSuccess }
        tap(tag)
    }

    private fun closeConnections() {
        compose.onNodeWithTag("knowledge-close").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("knowledge-workspace").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites() {
        val f = fixture()
        val incoming = id()
        val contrast = id()
        val localSource = id()
        val localLink = id()
        runBlocking {
            app.study.submit(StudyCommand(id(), f.origin.id, StudyAction.CREATE,
                cardId = localSource, nodeId = id(), title = "同册引用来源", body = "同册来源正文 LOCAL-SOURCE"))
            app.knowledge.submit(KnowledgeCommand(id(), f.origin.id, localLink, 0,
                KnowledgeData.Link(TargetRef(TargetKind.CARD, localSource), TargetRef(TargetKind.CARD, f.card))))
            app.knowledge.submit(KnowledgeCommand(id(), f.books[1].id, incoming, 0,
                KnowledgeData.Link(TargetRef(TargetKind.CARD, f.liveCard), TargetRef(TargetKind.CARD, f.card), pinnedRevision = 1)))
            app.knowledge.submit(KnowledgeCommand(id(), f.books[1].id, contrast, 0,
                KnowledgeData.Link(TargetRef(TargetKind.CARD, f.pinnedCard), TargetRef(TargetKind.CARD, f.card), RelationKind.CONTRAST)))
        }
        var before = authorStamp(f)
        tap("card-backlinks")
        waitFor("knowledge-links-incoming")
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("引用我的 · 2") }.isSuccess }
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected()
        compose.onNodeWithTag("knowledge-close").assertTextContains("返回摘要")
        compose.onNodeWithTag("knowledge-link-focus").assertTextEquals("保持原卡")
        compose.onNodeWithTag("knowledge-incoming-" + contrast).assertDoesNotExist()
        connectionItem("knowledge-incoming-" + incoming)
        assertPreview("实时原答案 LIVE-V1")
        assertEquals(before, authorStamp(f))
        runBlocking { app.study.submit(StudyCommand(id(), f.books[1].id, StudyAction.EDIT,
            cardId = f.liveCard, expectedRevision = 1, title = "条件概率", body = "来源正文实时更新 LIVE-V2")) }
        before = authorStamp(f)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("card-link-preview-body").assertTextEquals("来源正文实时更新 LIVE-V2") }.isSuccess }
        compose.onNodeWithTag("card-link-open-target").assertTextContains("打开引用来源")
        compose.activityRule.scenario.recreate()
        assertPreview("来源正文实时更新 LIVE-V2")
        tap("card-link-close-preview")
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag("knowledge-links-incoming"))
        compose.onNodeWithTag("knowledge-links-incoming").assertIsSelected()
        closeConnections()
        assertOrigin(f)
        assertEquals(before, authorStamp(f))
        tap("card-backlinks")
        waitFor("knowledge-links-incoming")
        compose.onNodeWithTag("card-link-preview").assertDoesNotExist()
        tap("knowledge-links-outgoing")
        connectionItem("knowledge-outgoing-" + f.pinnedLink)
        assertPreview("固定原答案 PIN-V1")
        tap("card-link-close-preview")
        closeConnections()
        assertOrigin(f)
        assertEquals(before, authorStamp(f))
        tap("card-backlinks")
        connectionItem("knowledge-incoming-" + localLink)
        assertPreview("同册来源正文 LOCAL-SOURCE")
        tap("card-link-open-target")
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("card-full-body").assertTextEquals("同册来源正文 LOCAL-SOURCE") }.isSuccess }
        compose.onNodeWithTag("card-node-actions").assertDoesNotExist()
        compose.onNodeWithTag("card-back").assertTextContains("关闭")
        compose.runOnIdle { assertEquals(f.origin.id, ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId) }
        assertEquals(before, authorStamp(f))
    }

    @Test fun pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity() {
        val f = fixture()
        val pageLink = id()
        val noteLink = id()
        runBlocking {
            val source = TargetRef(TargetKind.CARD, f.liveCard)
            app.knowledge.submit(KnowledgeCommand(id(), f.books[1].id, pageLink, 0,
                KnowledgeData.Link(source, TargetRef(TargetKind.PAGE, f.origin.id))))
            app.knowledge.submit(KnowledgeCommand(id(), f.books[1].id, noteLink, 0,
                KnowledgeData.Link(source, TargetRef(TargetKind.NOTE, f.origin.id))))
        }
        tap("card-back"); tap("study-close")
        tap("quick-settings"); tap("settings-readonly")
        waitFor("reading-toolbar")
        val before = authorStamp(f)
        tap("quick-settings"); tap("settings-knowledge")
        waitFor("knowledge-links-incoming"); tap("knowledge-links-incoming")
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag("add-knowledge-link"))
        compose.onNodeWithTag("add-knowledge-link").assertIsNotEnabled()
        connectionItem("knowledge-incoming-" + pageLink)
        assertPreview("实时原答案 LIVE-V1")
        tap("card-link-close-preview")
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag("knowledge-scope-note"))
        tap("knowledge-scope-note")
        compose.onNodeWithTag("knowledge-links-incoming").assertTextContains("引用我的 · 1")
        connectionItem("knowledge-incoming-" + noteLink)
        assertPreview("实时原答案 LIVE-V1")
        assertEquals(before, authorStamp(f))
        val selectedLink = runBlocking { checkNotNull(probe.knowledge().get(noteLink)) }
        runBlocking { app.knowledge.submit(KnowledgeCommand(id(), f.books[1].id, noteLink,
            selectedLink.revision, selectedLink.data(), true)) }
        val afterRemoval = authorStamp(f)
        waitFor("card-link-preview-error")
        compose.onNodeWithTag("card-link-open-target").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        waitFor("card-link-preview-error")
        compose.onNodeWithTag("card-link-open-target").assertIsNotEnabled()
        tap("card-link-close-preview")
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag("knowledge-links-empty"))
        compose.onNodeWithTag("knowledge-links-empty").assertIsDisplayed()
        compose.onNodeWithTag("knowledge-links-list").performScrollToNode(hasTestTag("knowledge-link-focus"))
        compose.onNodeWithTag("knowledge-link-focus").assertTextEquals(f.origin.title)
        tap("knowledge-scope-initial")
        connectionItem("knowledge-incoming-" + pageLink)
        assertPreview("实时原答案 LIVE-V1")
        tap("card-link-close-preview")
        closeConnections()
        compose.runOnIdle {
            assertEquals(f.origin.id, ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
        }
        compose.runOnIdle { assertEquals(f.origin.id, ViewModelProvider(compose.activity)["book-" + f.origin.id, BookPagesViewModel::class.java].ui.value.selectedId) }
        assertEquals(afterRemoval, authorStamp(f))
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }

    private fun fullyVisible(tag: String, container: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        val semantics = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        val control = semantics.boundsInRoot
        val window = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        assertTrue(tag + " must not be clipped by a scrolling parent",
            control.width >= semantics.size.width - 1 && control.height >= semantics.size.height - 1)
        assertTrue(tag + " must be fully visible inside its window", control.left >= window.left &&
            control.right <= window.right && control.top >= window.top && control.bottom <= window.bottom)
    }

    @Test fun narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable() {
        val oldSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont = shell("settings get system font_scale")
        try {
            shell("wm size 750x1600")
            shell("wm density 320")
            shell("settings put system font_scale 1.6")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                val config = compose.activity.resources.configuration
                kotlin.math.abs(config.screenWidthDp - 375) <= 4 && kotlin.math.abs(config.fontScale - 1.6f) < .02f
            }
            val f = fixture(longBody = true)
            var before = authorStamp(f)
            clickInline(f, "条件概率")
            assertPreview("实时原答案 LIVE-V1")
            fullyVisible("card-link-open-target", "card-link-preview")
            fullyVisible("card-link-close-preview", "card-link-preview")
            returnToCard(f, physical = true)
            fullyVisible("card-knowledge-links", "study-card-details")
            tap("card-knowledge-links", physical = true)
            waitFor("card-link-picker")
            f.duplicateLinks.forEach { fullyVisible("card-link-choice-" + it, "card-link-picker") }
            fullyVisible("card-link-close-picker", "card-link-picker")
            shot("375-font1.6-picker")
            tap("card-link-choice-" + f.duplicateLinks[0], physical = true)
            assertPreview("同名甲答案 NAME-A")
            fullyVisible("card-link-open-target", "card-link-preview")
            fullyVisible("card-link-close-preview", "card-link-preview")
            returnToCard(f, physical = true, pickerExpected = true)
            assertEquals(before, authorStamp(f))
            runBlocking { app.study.submit(StudyCommand(id(), f.books[1].id, StudyAction.EDIT,
                cardId = f.liveCard, expectedRevision = 1, title = "完整长标题用于核对大字窄窗预览的滚动与操作可达性".repeat(6).take(120), body = "实时原答案 LIVE-V1")) }
            before = authorStamp(f)
            tap("card-backlinks", physical = true)
            waitFor("knowledge-links-incoming")
            fullyVisible("knowledge-links-incoming", "knowledge-workspace")
            fullyVisible("knowledge-links-outgoing", "knowledge-workspace")
            tap("knowledge-links-outgoing", physical = true)
            connectionItem("knowledge-outgoing-" + f.liveLink)
            assertPreview("实时原答案 LIVE-V1")
            fullyVisible("card-link-open-target", "card-link-preview")
            fullyVisible("card-link-close-preview", "card-link-preview")
            shot("375-font1.6-connection-preview")
            tap("card-link-close-preview", physical = true)
            closeConnections()
            assertOrigin(f)
            fullyVisible("card-back", "study-card-details")
            tap("card-back", physical = true)
            compose.onNodeWithTag("study-card-details").assertDoesNotExist()
            compose.runOnIdle { assertEquals(f.viewport, map().snapshotViewport()) }
            assertEquals(before, authorStamp(f))
        } finally {
            shell(if (oldSize == null) "wm size reset" else "wm size " + oldSize)
            shell(if (oldDensity == null) "wm density reset" else "wm density " + oldDensity)
            shell(if (oldFont.matches(Regex("""[0-9.]+"""))) "settings put system font_scale " + oldFont
                else "settings delete system font_scale")
        }
    }
}
