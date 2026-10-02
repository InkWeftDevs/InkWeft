package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class KnowledgeTextRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(block: suspend (NoteDatabase, String) -> Unit) = runBlocking {
        val name = "knowledge-text-" + id() + ".db"
        val db = NoteDatabase.open(context, name)
        try { block(db, WorkspaceRepository(db).create("正文链接验收", false, PaperStyle.DOTS).id) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun card(db: NoteDatabase, book: String, title: String, body: String = "目标正文"): StudyCommand {
        val command = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(), title = title, body = body)
        StudyRepository(db).submit(command)
        return command
    }
    private suspend fun link(db: NoteDatabase, book: String, target: TargetRef, pinned: Long? = null,
                             source: TargetRef = TargetRef(TargetKind.NOTE, book)): KnowledgeCommand {
        val command = KnowledgeCommand(id(), book, id(), 0, KnowledgeData.Link(source, target, pinnedRevision = pinned))
        KnowledgeRepository(db).submit(command)
        return command
    }
    private suspend fun reject(reason: KnowledgeRejection, action: suspend () -> Unit) {
        try { action(); fail("Expected " + reason) } catch (e: KnowledgeRejected) { assertEquals(reason, e.reason) }
    }
    private fun authorState(db: NoteDatabase): List<Long> = db.openHelper.readableDatabase.query(
        "SELECT (SELECT COALESCE(SUM(revision),0) FROM notes)," +
            "(SELECT COALESCE(SUM(revision),0) FROM study_cards)," +
            "(SELECT COALESCE(SUM(revision),0) FROM knowledge_records)," +
            "(SELECT COUNT(*) FROM command_receipts),(SELECT COUNT(*) FROM study_receipts)," +
            "(SELECT COUNT(*) FROM knowledge_receipts),(SELECT COUNT(*) FROM note_revisions)," +
            "(SELECT COUNT(*) FROM study_card_revisions),(SELECT COUNT(*) FROM knowledge_revisions)"
    ).use { cursor -> cursor.moveToFirst(); List(cursor.columnCount) { cursor.getLong(it) } }

    @Test fun onlyConfirmedLinksForExactSourceAndOwnerAppearWithoutAuthorWrites() = fixture { db, book ->
        val source = TargetRef(TargetKind.NOTE, book)
        val target = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "提及标题").cardId))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, id(), 0, KnowledgeData.Alias(target.id, "未确认提及")))
        link(db, book, target, source = TargetRef(TargetKind.PAGE, book))
        val repository = KnowledgeTextRepository(db)
        assertTrue(repository.observe(source).first().isEmpty())
        val confirmed = link(db, book, target)
        val foreignBook = WorkspaceRepository(db).create("另一作者本", false, PaperStyle.DOTS).id
        // A foreign author row cannot enter the source's dictionary even if its payload claims that source.
        val foreign = KnowledgeRow(id(), foreignBook, 1, KnowledgeCodec.encode(KnowledgeData.Link(source, target)))
        db.knowledge().insert(foreign)
        val before = authorState(db)
        assertEquals(listOf(confirmed.id), repository.observe(source).first().map { it.linkId })
        assertEquals(target, repository.preview(source, confirmed.id, 1).target)
        reject(KnowledgeRejection.CONFLICT) { repository.preview(source, foreign.id, 1) }
        assertEquals(before, authorState(db))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, confirmed.id, 1, confirmed.data, true))
        val afterRemoval = authorState(db)
        assertTrue(repository.observe(source).first().isEmpty())
        reject(KnowledgeRejection.UNAVAILABLE) { repository.preview(source, confirmed.id, 1) }
        assertEquals(afterRemoval, authorState(db))
    }

    @Test fun crossNotebookSameNamesAndAliasesKeepEveryConfirmedTargetIdentity() = fixture { db, book ->
        val source = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "源卡").cardId))
        val firstBook = WorkspaceRepository(db).create("甲册", false, PaperStyle.DOTS).id
        val secondBook = WorkspaceRepository(db).create("乙册", false, PaperStyle.DOTS).id
        val firstTarget = TargetRef(TargetKind.CARD, checkNotNull(card(db, firstBook, "同名知识", "甲册答案").cardId))
        val secondTarget = TargetRef(TargetKind.CARD, checkNotNull(card(db, secondBook, "同名知识", "乙册答案").cardId))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), firstBook, id(), 0, KnowledgeData.Alias(firstTarget.id, "甲别名")))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), secondBook, id(), 0, KnowledgeData.Alias(secondTarget.id, "乙别名")))
        val firstLink = link(db, book, firstTarget, source = source)
        val secondLink = link(db, book, secondTarget, source = source)
        val repository = KnowledgeTextRepository(db)
        val before = authorState(db)
        val targets = repository.observe(source).first()
        assertEquals(2, targets.size)
        assertTrue(targets.single { it.linkId == firstLink.id }.label.contains("同名知识 · 甲册"))
        assertTrue(targets.single { it.linkId == secondLink.id }.label.contains("同名知识 · 乙册"))
        assertTrue(targets.single { it.linkId == firstLink.id }.terms.contains("甲别名"))
        assertEquals(setOf(firstLink.id, secondLink.id), KnowledgeTextLinks.spans("同名知识", targets).single().linkIds.toSet())
        assertEquals(firstTarget, repository.preview(source, firstLink.id, 1).target)
        assertEquals("甲册答案", repository.preview(source, firstLink.id, 1).body)
        assertEquals(before, authorState(db))
    }

    @Test fun liveRenameAndAliasChangesNeverReplacePinnedTitleOrBody() = fixture { db, book ->
        val source = TargetRef(TargetKind.NOTE, book)
        val target = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "旧标题", "旧正文").cardId))
        val alias = KnowledgeCommand(id(), book, id(), 0, KnowledgeData.Alias(target.id, "旧别名"))
        KnowledgeRepository(db).submit(alias)
        val live = link(db, book, target)
        val pinned = link(db, book, target, 1)
        val note = WorkspaceRepository(db).create("笔记旧名", false, PaperStyle.DOTS)
        NoteRepository(db).save(SaveNote(id(), note.id, 1, note.title, "笔记正文"))
        val noteLink = link(db, book, TargetRef(TargetKind.NOTE, note.id))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = target.id,
            expectedRevision = 1, title = "新标题", body = "新正文"))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, alias.id, 1, KnowledgeData.Alias(target.id, "新别名")))
        NoteRepository(db).rename(RenameNote(id(), note.id, 2, "笔记新名"))
        val repository = KnowledgeTextRepository(db)
        val before = authorState(db)
        val targets = repository.observe(source).first()
        assertEquals(setOf("新标题", "新别名"), targets.single { it.linkId == live.id }.terms.toSet())
        assertEquals(listOf("旧标题"), targets.single { it.linkId == pinned.id }.terms)
        assertEquals(listOf("笔记新名"), targets.single { it.linkId == noteLink.id }.terms)
        assertEquals("新正文", repository.preview(source, live.id, 1).body)
        val fixed = repository.preview(source, pinned.id, 1)
        assertEquals("旧标题", fixed.title)
        assertEquals("旧正文", fixed.body)
        assertEquals(1L, fixed.pinnedRevision)
        assertEquals("笔记正文", repository.preview(source, noteLink.id, 1).body)
        assertEquals(before, authorState(db))
        val row = db.knowledge().get(alias.id)!!
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, row.id, row.revision, row.data(), true))
        assertEquals(listOf("新标题"), repository.observe(source).first().single { it.linkId == live.id }.terms)
    }

    @Test fun missingPinnedRevisionRetainsAssociationWithoutCurrentTitleOrBodyFallback() = fixture { db, book ->
        val source = TargetRef(TargetKind.NOTE, book)
        val target = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "固定旧标题", "固定旧正文").cardId))
        val pinned = link(db, book, target, 1)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = target.id,
            expectedRevision = 1, title = "当前新标题", body = "当前新正文"))
        // Deliberately remove the selected historical row only in this disposable fault fixture.
        db.openHelper.writableDatabase.execSQL("DELETE FROM study_card_revisions WHERE cardId=? AND revision=1", arrayOf(target.id))
        val repository = KnowledgeTextRepository(db)
        val before = authorState(db)
        val dictionary = repository.observe(source).first()
        assertEquals(1, dictionary.size)
        assertEquals(pinned.id, dictionary.single().linkId)
        assertTrue(dictionary.single().terms.isEmpty())
        assertFalse(dictionary.single().available)
        assertTrue(KnowledgeTextLinks.spans("固定旧标题 当前新标题", dictionary).isEmpty())
        val preview = repository.preview(source, pinned.id, 1)
        assertEquals(target, preview.target)
        assertFalse(preview.canOpen)
        assertEquals(1L, preview.pinnedRevision)
        assertTrue(preview.body.contains("固定版本不可用"))
        assertFalse(preview.body.contains("当前新正文"))
        assertEquals(before, authorState(db))
    }

    @Test fun recyclingNotebookEmitsUnavailableButKeepsExactPinnedExcerptAndTarget() = fixture { db, book -> coroutineScope {
        val source = TargetRef(TargetKind.NOTE, book)
        val targetBook = WorkspaceRepository(db).create("目标册", false, PaperStyle.DOTS).id
        val target = TargetRef(TargetKind.CARD, checkNotNull(card(db, targetBook, "同名旧标题", "保留的固定摘录").cardId))
        val pinned = link(db, book, target, 1)
        StudyRepository(db).submit(StudyCommand(id(), targetBook, StudyAction.EDIT, cardId = target.id,
            expectedRevision = 1, title = "目标当前标题", body = "目标当前正文"))
        card(db, book, "同名旧标题", "不得误跳的同名卡")
        val repository = KnowledgeTextRepository(db)
        val events = Channel<List<KnowledgeTextTarget>>(Channel.UNLIMITED)
        val job = launch { repository.observe(source).collect { events.send(it) } }
        suspend fun await(available: Boolean): List<KnowledgeTextTarget> = withTimeout(10_000) {
            var next = events.receive()
            while (next.single().available != available) next = events.receive()
            next
        }
        try {
            assertTrue(await(true).single().available)
            val workspace = db.workspace().get(targetBook)!!
            assertTrue(WorkspaceRepository(db).organize(targetBook, workspace.revision, workspace.folder, workspace.tags, workspace.favorite, true))
            val before = authorState(db)
            val unavailable = await(false).single()
            assertEquals(target, unavailable.target)
            assertEquals(listOf("同名旧标题"), unavailable.terms)
            val preview = repository.preview(source, pinned.id, 1)
            assertEquals(target, preview.target)
            assertEquals("保留的固定摘录", preview.body)
            assertFalse(preview.canOpen)
            assertEquals(before, authorState(db))
        } finally { job.cancelAndJoin(); events.close() }
    } }

    @Test fun capturedRevisionRejectsRetargetingRemovalAndWrongSourceWhilePageTargetsStayExplicit() = fixture { db, book ->
        val source = TargetRef(TargetKind.NOTE, book)
        val original = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "原目标").cardId))
        val replacement = TargetRef(TargetKind.CARD, checkNotNull(card(db, book, "后来目标").cardId))
        val captured = link(db, book, original)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, captured.id, 1, KnowledgeData.Link(source, replacement)))
        val repository = KnowledgeTextRepository(db)
        val before = authorState(db)
        reject(KnowledgeRejection.CONFLICT) { repository.preview(source, captured.id, 1) }
        reject(KnowledgeRejection.CONFLICT) { repository.preview(TargetRef(TargetKind.PAGE, book), captured.id, 2) }
        assertEquals(replacement, repository.preview(source, captured.id, 2).target)
        assertEquals(before, authorState(db))
        val changed = db.knowledge().get(captured.id)!!
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, changed.id, changed.revision, changed.data(), true))
        reject(KnowledgeRejection.UNAVAILABLE) { repository.preview(source, captured.id, 2) }
        val stroke = InkStroke(id(), InkPen.PEN, 0xff000000.toInt(), 3f, InkTool.STYLUS,
            listOf(InkSample(100f, 100f, 0), InkSample(200f, 100f, 10)))
        InkRepository(db).save(CommitInk(id(), book, 0, InkMutation.Add(stroke)))
        val anchor = KnowledgeCommand(id(), book, id(), 0,
            KnowledgeData.Anchor(book, 1, CanvasBounds(95.0, 95.0, 205.0, 105.0), listOf(stroke.id)))
        KnowledgeRepository(db).submit(anchor)
        val pageLink = link(db, book, TargetRef(TargetKind.PAGE, book))
        val anchorLink = link(db, book, TargetRef(TargetKind.ANCHOR, anchor.id))
        val afterAuthors = authorState(db)
        val dictionary = repository.observe(source).first()
        assertEquals(setOf(pageLink.id, anchorLink.id), dictionary.map { it.linkId }.toSet())
        assertTrue(dictionary.all { it.terms.isEmpty() && it.available })
        assertTrue(repository.preview(source, pageLink.id, 1).body.contains("打开来源页"))
        assertTrue(repository.preview(source, anchorLink.id, 1).body.contains("打开来源区域"))
        assertEquals(afterAuthors, authorState(db))
    }
}
