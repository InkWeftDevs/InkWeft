// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Exact branch identities, historical ownership and complete restore on synthetic databases. */
class MapPortalBranchRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(block: suspend (NoteDatabase, String) -> Unit) = runBlocking {
        val name = "map-portal-branch-${id()}.db"
        val db = NoteDatabase.open(context, name)
        try { block(db, WorkspaceRepository(db).create("指定分支入口测试", false, PaperStyle.DOTS).id) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun map(db: NoteDatabase, book: String, title: String,
                            structures: List<MapStructure> = emptyList()): String {
        val map = id()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, map, 0,
            KnowledgeData.MapDefinition(title, structures = structures)))
        return map
    }
    private suspend fun card(db: NoteDatabase, book: String, mapId: String? = null,
                             title: String = "知识卡"): StudyCommand {
        val command = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(),
            title = title, body = "独立正文", mapId = mapId)
        StudyRepository(db).submit(command)
        return command
    }
    private suspend fun reuse(db: NoteDatabase, book: String, cardId: String,
                              mapId: String? = null): StudyCommand {
        val command = StudyCommand(id(), book, StudyAction.REUSE, cardId = cardId, nodeId = id(), mapId = mapId)
        StudyRepository(db).submit(command)
        return command
    }
    private suspend fun portal(db: NoteDatabase, book: String, sourceMap: String?, sourceNode: String,
                               targetMap: String?, targetBranch: String? = null): KnowledgeCommand {
        val command = KnowledgeCommand(id(), book, id(), 0,
            KnowledgeData.MapPortal(sourceMap, sourceNode, targetMap, targetBranch))
        KnowledgeRepository(db).submit(command)
        return command
    }
    private suspend fun rejected(block: suspend () -> Unit): KnowledgeRejection {
        try { block(); fail("Expected KnowledgeRejected") }
        catch (e: KnowledgeRejected) { return e.reason }
        error("Unreachable")
    }
    private fun authorCounts(db: NoteDatabase) = listOf(
        "notes", "note_revisions", "command_receipts", "study_cards", "study_card_revisions",
        "study_nodes", "study_sources", "study_receipts", "knowledge_records", "knowledge_revisions",
        "knowledge_receipts", "ink_strokes", "ink_receipts"
    ).map { table ->
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst(); it.getLong(0)
        }
    }
    private suspend fun rejectedWithoutWrites(db: NoteDatabase, command: KnowledgeCommand,
                                             expected: KnowledgeRejection? = null) {
        val counts = authorCounts(db)
        val note = db.notes().note(command.notebookId)
        val reason = rejected { KnowledgeRepository(db).submit(command) }
        if (expected != null) assertEquals(expected, reason)
        assertEquals(counts, authorCounts(db))
        assertEquals(note, db.notes().note(command.notebookId))
        assertNull(db.knowledge().get(command.id))
        assertNull(db.knowledge().revision(command.id, 1))
        assertNull(db.knowledge().receipt(command.operationId))
    }

    @Test fun savedBranchEndpointsSurviveReopenAcrossMainAndNamedMaps() = runBlocking {
        val name = "map-portal-branch-reopen-${id()}.db"
        var db = NoteDatabase.open(context, name)
        try {
            val book = WorkspaceRepository(db).create("重开指定分支", false, PaperStyle.DOTS).id
            val sourceStructure = id()
            val targetStructure = id()
            val sourceMap = map(db, book, "源图", listOf(MapStructure(sourceStructure, null, "源结构", 40.0, 80.0)))
            val targetMap = map(db, book, "目标图", listOf(MapStructure(targetStructure, null, "目标结构", 40.0, 80.0)))
            val main = card(db, book, title = "共享知识")
            val sourcePosition = reuse(db, book, main.cardId!!, sourceMap)
            val targetPosition = reuse(db, book, main.cardId!!, targetMap)
            val savedCard = db.study().card(main.cardId!!)!!
            val savedMainNodes = db.study().nodes(book)
            val entries = listOf(
                portal(db, book, null, main.nodeId!!, targetMap, targetStructure),
                portal(db, book, sourceMap, sourceStructure, null, main.nodeId),
                portal(db, book, sourceMap, sourcePosition.nodeId!!, targetMap, targetPosition.nodeId)
            )
            val counts = authorCounts(db)
            db.close(); db = NoteDatabase.open(context, name)
            val note = db.notes().note(book)
            repeat(2) {
                for (entry in entries) {
                    val data = entry.data as KnowledgeData.MapPortal
                    val preview = MapPortalRepository(db).preview(book, entry.id, 1)
                    assertTrue(preview.canOpen)
                    assertEquals(MapRef(book, data.sourceMapId), preview.source)
                    assertEquals(MapRef(book, data.targetMapId), preview.target)
                    assertEquals(data.sourceNodeId, preview.nodeId)
                    assertEquals(data.targetBranchId, preview.targetBranchId)
                    assertEquals(data, db.knowledge().get(entry.id)!!.data())
                }
            }
            assertEquals("目标结构", MapPortalRepository(db).preview(book, entries[0].id, 1).targetBranchTitle)
            assertEquals("共享知识", MapPortalRepository(db).preview(book, entries[1].id, 1).targetBranchTitle)
            assertEquals("共享知识", MapPortalRepository(db).preview(book, entries[2].id, 1).targetBranchTitle)
            assertEquals(counts, authorCounts(db)); assertEquals(note, db.notes().note(book))
            assertEquals(savedCard, db.study().card(main.cardId!!)); assertEquals(savedMainNodes, db.study().nodes(book))
            assertEquals(1, db.study().cards(book).size)
            KnowledgeRepository(db).validateArchive()
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun previewUsesRenamedStructureAndCardTitlesWithoutChangingBranchIdentity() = fixture { db, book ->
        val root = id()
        val target = map(db, book, "目标旧图名", listOf(MapStructure(root, null, "结构旧名", 40.0, 80.0)))
        val source = card(db, book)
        val targetCard = card(db, book, target, "卡片旧名")
        val structureEntry = portal(db, book, null, source.nodeId!!, target, root)
        val cardEntry = portal(db, book, null, source.nodeId!!, target, targetCard.nodeId)
        val definition = db.knowledge().get(target)!!.data() as KnowledgeData.MapDefinition
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, target, 1,
            definition.copy(title = "目标新图名", structures = definition.structures.map { it.copy(title = "结构新名") })))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = targetCard.cardId,
            expectedRevision = 1, title = "卡片新名", body = "新正文", mapId = target))
        val counts = authorCounts(db)
        val note = db.notes().note(book)
        repeat(3) {
            for ((entry, branch, title) in listOf(
                Triple(structureEntry, root, "结构新名"), Triple(cardEntry, targetCard.nodeId!!, "卡片新名")
            )) {
                val preview = MapPortalRepository(db).preview(book, entry.id, 1)
                assertTrue(preview.canOpen); assertEquals(MapRef(book, target), preview.target)
                assertEquals("目标新图名", preview.targetTitle)
                assertEquals(branch, preview.targetBranchId); assertEquals(title, preview.targetBranchTitle)
                assertArrayEquals(entry.payload, db.knowledge().get(entry.id)!!.payload)
                assertEquals(1L, db.knowledge().get(entry.id)!!.revision)
            }
        }
        assertEquals(counts, authorCounts(db)); assertEquals(note, db.notes().note(book))
    }

    @Test fun removedStructureNeverRedirectsToASameNamedReplacementAndCanBeRemoved() = fixture { db, book ->
        val root = id()
        val replacement = id()
        val target = map(db, book, "目标图", listOf(MapStructure(root, null, "同名分支", 40.0, 80.0)))
        val source = card(db, book)
        val entry = portal(db, book, null, source.nodeId!!, target, root)
        val repo = KnowledgeRepository(db)
        repo.submit(KnowledgeCommand(id(), book, target, 1,
            KnowledgeData.MapDefinition("目标图", structures = listOf(MapStructure(replacement, null, "同名分支", 40.0, 80.0)))))
        val preview = MapPortalRepository(db).preview(book, entry.id, 1)
        assertFalse(preview.canOpen); assertEquals(MapRef(book, target), preview.target)
        assertEquals(root, preview.targetBranchId); assertNotEquals(replacement, preview.targetBranchId)
        repo.validateArchive()
        val counts = authorCounts(db)
        rejectedWithoutWrites(db, KnowledgeCommand(id(), book, id(), 0, entry.data))
        assertEquals(counts, authorCounts(db))
        repo.submit(KnowledgeCommand(id(), book, entry.id, 1, entry.data, true))
        assertTrue(db.knowledge().get(entry.id)!!.removed)
        val removedCounts = authorCounts(db)
        assertEquals(KnowledgeRejection.UNAVAILABLE, rejected {
            repo.submit(KnowledgeCommand(id(), book, entry.id, 2, entry.data))
        })
        assertEquals(removedCounts, authorCounts(db)); assertEquals(2L, db.knowledge().get(entry.id)!!.revision)
        assertTrue(MapPortalRepository(db).preview(book,
            portal(db, book, null, source.nodeId!!, target, replacement).id, 1).canOpen)
        repo.validateArchive()
    }

    @Test fun removedMainAndNamedPositionsNeverRedirectToTheSameSharedCard() = fixture { db, book ->
        val sourceMap = map(db, book, "源图")
        val targetMap = map(db, book, "目标图")
        val source = card(db, book, sourceMap)
        for (target in listOf(null, targetMap)) {
            val position = card(db, book, target, "同名共享卡")
            val entry = portal(db, book, sourceMap, source.nodeId!!, target, position.nodeId)
            StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE,
                nodeId = position.nodeId, expectedRevision = 1, mapId = target))
            val replacement = reuse(db, book, position.cardId!!, target)
            val preview = MapPortalRepository(db).preview(book, entry.id, 1)
            assertFalse(preview.canOpen); assertEquals(MapRef(book, target), preview.target)
            assertEquals(position.nodeId, preview.targetBranchId); assertNotEquals(replacement.nodeId, preview.targetBranchId)
            assertTrue(MapGraphAccess(db).read(book).first { it.ref == MapRef(book, target) }.nodes.any { it.id == replacement.nodeId })
            KnowledgeRepository(db).validateArchive()
            KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, entry.id, 1, entry.data, true))
            assertTrue(db.knowledge().get(entry.id)!!.removed)
        }
        KnowledgeRepository(db).validateArchive()
    }

    @Test fun movedOccurrenceKeepsItsOriginalMapAndHistoricalOwnership() = fixture { db, book ->
        val target = map(db, book, "原目标图")
        val destination = map(db, book, "移入图")
        val source = card(db, book)
        val position = card(db, book, target, "共享卡")
        val entry = portal(db, book, null, source.nodeId!!, target, position.nodeId)
        val row = db.knowledge().get(position.nodeId!!)!!
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, row.id, row.revision,
            (row.data() as KnowledgeData.MapOccurrence).copy(mapId = destination)))
        val replacement = reuse(db, book, position.cardId!!, target)
        val preview = MapPortalRepository(db).preview(book, entry.id, 1)
        assertFalse(preview.canOpen); assertEquals(MapRef(book, target), preview.target)
        assertEquals(position.nodeId, preview.targetBranchId); assertNotEquals(replacement.nodeId, preview.targetBranchId)
        assertTrue(MapGraphAccess(db).read(book).first { it.ref == MapRef(book, destination) }.nodes.any { it.id == position.nodeId })
        assertArrayEquals(entry.payload, db.knowledge().get(entry.id)!!.payload)
        KnowledgeRepository(db).validateArchive()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, entry.id, 1, entry.data, true))
        assertTrue(db.knowledge().get(entry.id)!!.removed)
        assertTrue(MapPortalRepository(db).preview(book,
            portal(db, book, null, source.nodeId!!, destination, position.nodeId).id, 1).canOpen)
    }

    @Test fun movedStructureCannotBeFoundGloballyInAnotherMap() = fixture { db, book ->
        val root = MapStructure(id(), null, "原结构", 40.0, 80.0)
        val target = map(db, book, "原目标图", listOf(root))
        val destination = map(db, book, "移入图")
        val source = card(db, book)
        val entry = portal(db, book, null, source.nodeId!!, target, root.id)
        val repo = KnowledgeRepository(db)
        repo.submit(KnowledgeCommand(id(), book, target, 1, KnowledgeData.MapDefinition("原目标图")))
        repo.submit(KnowledgeCommand(id(), book, destination, 1, KnowledgeData.MapDefinition("移入图", structures = listOf(root))))
        val preview = MapPortalRepository(db).preview(book, entry.id, 1)
        assertFalse(preview.canOpen); assertEquals(MapRef(book, target), preview.target); assertEquals(root.id, preview.targetBranchId)
        repo.validateArchive()
        repo.submit(KnowledgeCommand(id(), book, entry.id, 1, entry.data, true))
        assertTrue(db.knowledge().get(entry.id)!!.removed)
    }

    @Test fun invalidForeignAndWrongMapBranchesLeaveNoAuthorRecordsOrReceipts() = fixture { db, book ->
        val sourceMap = map(db, book, "源图")
        val source = card(db, book, sourceMap)
        val main = card(db, book)
        val root = id()
        val target = map(db, book, "正确目标图", listOf(MapStructure(root, null, "正确结构", 40.0, 80.0)))
        val occurrence = card(db, book, target)
        val otherRoot = id()
        val otherMap = map(db, book, "另一图", listOf(MapStructure(otherRoot, null, "另一结构", 40.0, 80.0)))
        val otherPosition = reuse(db, book, occurrence.cardId!!, otherMap)
        val foreign = WorkspaceRepository(db).create("另一本", false, PaperStyle.BLANK).id
        val foreignRoot = id()
        val foreignMap = map(db, foreign, "外图", listOf(MapStructure(foreignRoot, null, "外结构", 40.0, 80.0)))
        val foreignMain = card(db, foreign)
        rejectedWithoutWrites(db, KnowledgeCommand(id(), book, id(), 0,
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, foreignMap, foreignRoot)), KnowledgeRejection.INVALID)
        val invalid = listOf(
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, foreignRoot),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, otherRoot),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, otherPosition.nodeId),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, main.nodeId),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, occurrence.cardId),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, target, id()),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, null, occurrence.nodeId),
            KnowledgeData.MapPortal(sourceMap, source.nodeId!!, null, foreignMain.nodeId)
        )
        for (data in invalid) rejectedWithoutWrites(db, KnowledgeCommand(id(), book, id(), 0, data))
        KnowledgeRepository(db).validateArchive()
    }

    @Test fun capturedRevisionRejectsRetargetedBranchesAndStaleRemovalOrUndo() = fixture { db, book ->
        val first = id(); val second = id()
        val target = map(db, book, "目标图", listOf(
            MapStructure(first, null, "第一分支", 40.0, 80.0), MapStructure(second, null, "第二分支", 40.0, 208.0)))
        val source = card(db, book)
        val entry = portal(db, book, null, source.nodeId!!, target, first)
        val changed = (entry.data as KnowledgeData.MapPortal).copy(targetBranchId = second)
        val repo = KnowledgeRepository(db)
        repo.submit(KnowledgeCommand(id(), book, entry.id, 1, changed))
        assertEquals(KnowledgeRejection.CONFLICT, rejected { MapPortalRepository(db).preview(book, entry.id, 1) })
        val current = MapPortalRepository(db).preview(book, entry.id, 2)
        assertTrue(current.canOpen); assertEquals(second, current.targetBranchId)
        val before = authorCounts(db)
        val staleRemove = KnowledgeCommand(id(), book, entry.id, 1, entry.data, true)
        assertEquals(KnowledgeRejection.CONFLICT, rejected { repo.submit(staleRemove) })
        assertEquals(before, authorCounts(db)); assertNull(db.knowledge().receipt(staleRemove.operationId))
        repo.submit(KnowledgeCommand(id(), book, entry.id, 2, changed, true))
        assertEquals(KnowledgeRejection.CONFLICT, rejected { MapPortalRepository(db).preview(book, entry.id, 2) })
        assertEquals(KnowledgeRejection.UNAVAILABLE, rejected { MapPortalRepository(db).preview(book, entry.id, 3) })
        val removedCounts = authorCounts(db)
        val staleUndo = KnowledgeCommand(id(), book, entry.id, 2, entry.data)
        assertEquals(KnowledgeRejection.CONFLICT, rejected { repo.submit(staleUndo) })
        assertEquals(removedCounts, authorCounts(db)); assertNull(db.knowledge().receipt(staleUndo.operationId))
        assertTrue(db.knowledge().get(entry.id)!!.removed)
        repo.submit(KnowledgeCommand(id(), book, entry.id, 3, changed))
        assertEquals(second, MapPortalRepository(db).preview(book, entry.id, 4).targetBranchId)
    }

    @Test fun faultsRetriesAndDuplicatesUseTheCompleteBranchEndpointIdentity() = fixture { db, book ->
        val first = id(); val second = id()
        val target = map(db, book, "目标图", listOf(
            MapStructure(first, null, "第一分支", 40.0, 80.0), MapStructure(second, null, "第二分支", 40.0, 208.0)))
        val sourceMap = map(db, book, "另一个源图")
        val source = card(db, book)
        val otherSource = reuse(db, book, source.cardId!!, sourceMap)
        val command = KnowledgeCommand(id(), book, id(), 0,
            KnowledgeData.MapPortal(null, source.nodeId!!, target, first))
        val before = authorCounts(db)
        assertEquals(KnowledgeOutcome.Unknown,
            KnowledgeRepository(db) { if (it == KnowledgeFault.BEFORE_RECEIPT) error("rollback") }.outcome(command))
        assertEquals(before, authorCounts(db)); assertNull(db.knowledge().get(command.id))
        assertNull(db.knowledge().revision(command.id, 1)); assertNull(db.knowledge().receipt(command.operationId))
        assertEquals(KnowledgeOutcome.Success(command.id),
            KnowledgeRepository(db) { if (it == KnowledgeFault.AFTER_COMMIT) error("lost response") }.outcome(command))
        val repo = KnowledgeRepository(db)
        val committed = authorCounts(db)
        assertEquals(command.id, repo.submit(command)); assertEquals(committed, authorCounts(db))
        rejectedWithoutWrites(db, KnowledgeCommand(id(), book, id(), 0, command.data), KnowledgeRejection.DUPLICATE)
        portal(db, book, null, source.nodeId!!, target, second)
        val wholeMap = portal(db, book, null, source.nodeId!!, target)
        assertNull(MapPortalRepository(db).preview(book, wholeMap.id, 1).targetBranchId)
        assertNull(MapPortalRepository(db).preview(book, wholeMap.id, 1).targetBranchTitle)
        portal(db, book, sourceMap, otherSource.nodeId!!, target, first)
        assertEquals(4, db.knowledge().forBook(book).count { it.data() is KnowledgeData.MapPortal })
        val operationCounts = authorCounts(db)
        try {
            repo.submit(KnowledgeCommand(command.operationId, book, command.id, 0,
                (command.data as KnowledgeData.MapPortal).copy(targetBranchId = second)))
            fail("An operation cannot replay with a different branch payload")
        } catch (_: IllegalArgumentException) {}
        assertEquals(operationCounts, authorCounts(db))
        val row = db.knowledge().get(target)!!
        val definition = row.data() as KnowledgeData.MapDefinition
        repo.submit(KnowledgeCommand(id(), book, target, row.revision,
            definition.copy(structures = definition.structures.filterNot { it.id == first })))
        assertFalse(MapPortalRepository(db).preview(book, command.id, 1).canOpen)
        val unavailableCounts = authorCounts(db)
        assertEquals(command.id, repo.submit(command)); assertEquals(unavailableCounts, authorCounts(db))
        assertEquals(1, db.knowledge().revisions(command.id).size)
        assertEquals(command.digest(), db.knowledge().receipt(command.operationId)!!.digest)
        assertEquals(command.data, db.knowledge().get(command.id)!!.data())
        KnowledgeRepository(db).validateArchive()
    }

    @Test fun archiveRejectsTargetBranchesWithoutHistoricalOwnershipInTheCapturedMap() = fixture { db, book ->
        val source = card(db, book)
        val target = map(db, book, "声明的目标图")
        val other = map(db, book, "真实所属图")
        val position = card(db, book, other)
        val invalid = KnowledgeRow(id(), book, 1,
            KnowledgeCodec.encode(KnowledgeData.MapPortal(null, source.nodeId!!, target, position.nodeId)))
        db.knowledge().insert(invalid)
        db.knowledge().revision(KnowledgeRevisionRow(invalid.id, 1, book, invalid.payload, false))
        try { KnowledgeRepository(db).validateArchive(); fail("Unproven target ownership must reject the archive") }
        catch (_: IllegalArgumentException) {}
        try { LibraryBackupRepository(context, db).snapshot().use { fail("Unproven target ownership must not export") } }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun archiveAllowsDifferentBranchesButRejectsAnExactActiveDuplicate() = fixture { db, book ->
        val first = id(); val second = id()
        val target = map(db, book, "目标图", listOf(
            MapStructure(first, null, "第一分支", 40.0, 80.0), MapStructure(second, null, "第二分支", 40.0, 208.0)))
        val source = card(db, book)
        val entry = portal(db, book, null, source.nodeId!!, target, first)
        portal(db, book, null, source.nodeId!!, target, second)
        portal(db, book, null, source.nodeId!!, target)
        KnowledgeRepository(db).validateArchive()
        val duplicate = KnowledgeRow(id(), book, 1, entry.payload)
        db.knowledge().insert(duplicate)
        db.knowledge().revision(KnowledgeRevisionRow(duplicate.id, 1, book, duplicate.payload, false))
        try { KnowledgeRepository(db).validateArchive(); fail("An exact active branch duplicate must reject the archive") }
        catch (e: IllegalArgumentException) { assertEquals("MAP_PORTAL_EXISTS", e.message) }
    }

    @Test fun fullBackupRestoresExactBranchHistoryReceiptsAndUnavailableTargetsWithoutDuplicating() = fixture { db, book ->
        val sourceRoot = id(); val removedRoot = id(); val liveRoot = id()
        val sourceMap = map(db, book, "源图", listOf(MapStructure(sourceRoot, null, "源结构", 40.0, 80.0)))
        val targetMap = map(db, book, "目标图", listOf(
            MapStructure(removedRoot, null, "将移除结构", 40.0, 80.0), MapStructure(liveRoot, null, "保留结构", 40.0, 208.0)))
        val destination = map(db, book, "移入图")
        val source = card(db, book)
        val namedSource = reuse(db, book, source.cardId!!, sourceMap)
        val mainTarget = card(db, book, title = "将移除主图位置")
        val movedTarget = card(db, book, targetMap, "共享目标卡")
        val livePosition = reuse(db, book, movedTarget.cardId!!, targetMap)
        val entries = listOf(
            portal(db, book, null, source.nodeId!!, targetMap, removedRoot),
            portal(db, book, sourceMap, sourceRoot, targetMap, movedTarget.nodeId),
            portal(db, book, sourceMap, namedSource.nodeId!!, null, mainTarget.nodeId),
            portal(db, book, null, source.nodeId!!, targetMap, liveRoot)
        )
        val repo = KnowledgeRepository(db)
        val retarget = KnowledgeCommand(id(), book, entries[3].id, 1,
            (entries[3].data as KnowledgeData.MapPortal).copy(targetBranchId = livePosition.nodeId))
        repo.submit(retarget)
        val removeStructure = KnowledgeCommand(id(), book, targetMap, 1,
            (db.knowledge().get(targetMap)!!.data() as KnowledgeData.MapDefinition).let { definition ->
                definition.copy(structures = definition.structures.filterNot { it.id == removedRoot })
            })
        repo.submit(removeStructure)
        val moveOccurrence = KnowledgeCommand(id(), book, movedTarget.nodeId!!, 1,
            (db.knowledge().get(movedTarget.nodeId!!)!!.data() as KnowledgeData.MapOccurrence).copy(mapId = destination))
        repo.submit(moveOccurrence)
        val removeMain = StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = mainTarget.nodeId, expectedRevision = 1)
        StudyRepository(db).submit(removeMain)
        val removeEntry = KnowledgeCommand(id(), book, entries[2].id, 1, entries[2].data, true)
        repo.submit(removeEntry); repo.validateArchive()
        val name = "map-portal-branch-restore-${id()}.db"
        var target = NoteDatabase.open(context, name)
        try {
            val retained = WorkspaceRepository(target).create("原有笔记", false, PaperStyle.BLANK)
            LibraryBackupRepository(context, db).snapshot().use { snapshot ->
                val backup = LibraryBackupRepository(context, target)
                snapshot.file.inputStream().use { backup.inspect(it) }.use { preview ->
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED, backup.restore(preview))
                    val restoredCounts = authorCounts(target)
                    assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT, backup.restore(preview))
                    assertEquals(restoredCounts, authorCounts(target))
                    assertEquals(retained, NoteRepository(target).read(retained.id))
                }
            }
            target.close(); target = NoteDatabase.open(context, name)
            assertEquals(db.study().nodes(book), target.study().nodes(book))
            assertEquals(db.study().cards(book), target.study().cards(book))
            for (original in db.knowledge().forBook(book)) {
                val restored = target.knowledge().get(original.id)!!
                assertEquals(original.notebookId, restored.notebookId); assertEquals(original.revision, restored.revision)
                assertEquals(original.removed, restored.removed); assertArrayEquals(original.payload, restored.payload)
                val history = db.knowledge().revisions(original.id)
                val restoredHistory = target.knowledge().revisions(original.id)
                assertEquals(history.size, restoredHistory.size)
                for ((before, after) in history.zip(restoredHistory)) {
                    assertEquals(before.id, after.id); assertEquals(before.notebookId, after.notebookId)
                    assertEquals(before.revision, after.revision); assertEquals(before.removed, after.removed)
                    assertArrayEquals(before.payload, after.payload)
                }
            }
            for (command in entries + listOf(retarget, removeStructure, moveOccurrence, removeEntry)) {
                assertEquals(db.knowledge().receipt(command.operationId), target.knowledge().receipt(command.operationId))
                assertNotNull(target.knowledge().receipt(command.operationId))
            }
            assertEquals(db.study().receipt(removeMain.id), target.study().receipt(removeMain.id))
            for (entry in entries) {
                assertEquals(entry.data, KnowledgeCodec.decode(target.knowledge().revision(entry.id, 1)!!.payload))
            }
            val reads = MapPortalRepository(target)
            val lostStructure = reads.preview(book, entries[0].id, 1)
            assertFalse(lostStructure.canOpen); assertEquals(removedRoot, lostStructure.targetBranchId)
            val movedOccurrence = reads.preview(book, entries[1].id, 1)
            assertFalse(movedOccurrence.canOpen); assertEquals(MapRef(book, targetMap), movedOccurrence.target)
            assertEquals(movedTarget.nodeId, movedOccurrence.targetBranchId)
            assertEquals(KnowledgeRejection.UNAVAILABLE, rejected { reads.preview(book, entries[2].id, 2) })
            val live = reads.preview(book, entries[3].id, 2)
            assertTrue(live.canOpen); assertEquals(livePosition.nodeId, live.targetBranchId)
            val beforeRetry = authorCounts(target)
            for (entry in entries) assertEquals(entry.id, KnowledgeRepository(target).submit(entry))
            assertEquals(beforeRetry, authorCounts(target))
            assertEquals(retarget.data, target.knowledge().get(entries[3].id)!!.data())
            assertTrue(target.knowledge().get(entries[2].id)!!.removed)
            KnowledgeRepository(target).validateArchive()
        } finally { target.close(); context.deleteDatabase(name) }
    }
}
