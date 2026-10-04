package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyOrganizationRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(block: suspend (NoteDatabase, String) -> Unit) = runBlocking {
        val name = "study-organization-${id()}.db"
        val db = NoteDatabase.open(context, name)
        try { block(db, WorkspaceRepository(db).create("组织操作测试", false, PaperStyle.BLANK).id) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun create(db: NoteDatabase, book: String, title: String, map: String? = null,
        parent: String? = null, after: String? = null, x: Double = 40.0, y: Double = 80.0): StudyCommand {
        val repo = StudyRepository(db)
        val command = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(),
            title = title, body = "$title 的正文", parentId = parent, mapId = map, x = x, y = y,
            expectedGraph = repo.readGraph(book, map).graphFingerprint, afterNodeId = after)
        repo.submit(command)
        return command
    }
    private suspend fun map(db: NoteDatabase, book: String, structures: List<MapStructure>, layout: String = "right"): String {
        val map = id()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, map, 0, KnowledgeData.MapDefinition("混合图", layout, structures)))
        return map
    }
    private fun patch(state: StudyGraphState): StudyGraphPatch {
        val byId = state.nodes.associateBy { it.id }
        return StudyGraphPatch(state.orderedNodeIds, state.orderedNodeIds.map { id ->
            byId.getValue(id).let { StudyNodePlacement(it.id, it.parentId, it.x, it.y) }
        })
    }

    @Test fun legacyOrderMaterializesBeforeMoveAndExplicitInsertionKeepsSubtrees() = fixture { db, book ->
        val a = UUID(0, 1).toString(); val b = UUID(0, 9).toString(); val child = UUID(0, 4).toString()
        suspend fun seed(node: String, parent: String?, y: Double) {
            val card = StudyCardRow(id(), book, 1, node, "旧图正文")
            db.study().addCard(card); db.study().revision(StudyCardRevisionRow(card.id, 1, card.title, card.body, null))
            db.study().addNode(StudyNodeRow(node, book, card.id, parent, 40.0, y))
        }
        seed(a, null, 300.0); seed(b, null, 100.0); seed(child, a, 500.0)
        val repo = StudyRepository(db); val before = repo.readGraph(book)
        assertEquals(listOf(b, a, child), before.orderedNodeIds)
        assertNull(before.order); assertTrue(db.knowledge().forBook(book).isEmpty())
        repo.submit(StudyCommand(id(), book, StudyAction.MOVE, nodeId = b, expectedRevision = 1, x = 900.0, y = 900.0))
        val moved = repo.readGraph(book)
        assertEquals(before.orderedNodeIds, moved.orderedNodeIds); assertEquals(1L, moved.order!!.revision)
        val sibling = create(db, book, "紧随同级", after = b)
        val addedChild = create(db, book, "末尾子级", parent = b)
        val result = repo.readGraph(book)
        assertEquals(listOf(b, addedChild.nodeId, sibling.nodeId, a, child), result.orderedNodeIds)
        assertEquals(900.0, result.nodes.first { it.id == b }.y, 0.0)
        val invalid = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(), title = "错误锚点",
            parentId = a, afterNodeId = b, expectedGraph = result.graphFingerprint)
        assertTrue(repo.outcome(invalid) is StudyOutcome.Rejected)
        assertNull(db.study().card(invalid.cardId!!)); assertEquals(result.graphFingerprint, repo.readGraph(book).graphFingerprint)
    }

    @Test fun organizationRollbackReplayAndUndoKeepOneAtomicReceiptAndRejectAba() = fixture { db, book ->
        val a = create(db, book, "甲"); create(db, book, "乙"); val c = create(db, book, "丙")
        val repo = StudyRepository(db); val before = repo.readGraph(book)
        val plan = StudyOrganization.plan(before.state, c.nodeId!!, StudyOrganizationAction.UP)
        val undo = StudyOrganization.undo(StudyOrganization.apply(before.state, plan), plan).command(id())
        val command = plan.command(id())
        assertEquals(StudyOutcome.Unknown, StudyRepository(db) { if (it == StudyFault.BEFORE_RECEIPT) error("rollback") }.outcome(command))
        assertEquals(before.graphFingerprint, repo.readGraph(book).graphFingerprint); assertNull(db.study().receipt(command.id))
        assertTrue(StudyRepository(db) { if (it == StudyFault.AFTER_COMMIT) error("lost response") }.outcome(command) is StudyOutcome.Success)
        val applied = repo.readGraph(book)
        assertEquals(plan.expectedAfterGraph, applied.graphFingerprint)
        assertEquals(applied.graphFingerprint, repo.readGraph(book).graphFingerprint)
        repo.submit(undo)
        val restored = repo.readGraph(book)
        assertEquals(before.orderedNodeIds, restored.orderedNodeIds)
        assertTrue(restored.order!!.revision > before.order!!.revision)
        assertNotEquals(before.graphFingerprint, restored.graphFingerprint)
        // A committed operation replays before its now-stale CAS, without reapplying the change.
        assertEquals(book, repo.submit(command)); assertEquals(restored.graphFingerprint, repo.readGraph(book).graphFingerprint)
        assertTrue(repo.outcome(plan.command(id())) is StudyOutcome.Rejected)
        assertTrue(repo.outcome(StudyOrganization.undo(StudyOrganization.apply(before.state, plan), plan).command(id())) is StudyOutcome.Rejected)
        assertEquals("甲 的正文", db.study().card(a.cardId!!)!!.body)
    }

    @Test fun mixedParentSwapEmitsOnlyCoherentGraphsAndBumpsDefinitionOnce() = fixture { db, book ->
        val structure = id(); val otherStructure = id()
        val named = map(db, book, listOf(MapStructure(structure, null, "结构根", 40.0, 80.0),
            MapStructure(otherStructure, structure, "结构子级", 400.0, 220.0)))
        val card = create(db, book, "卡片主题", named, structure)
        val repo = StudyRepository(db); val before = repo.readGraph(book, named)
        val nodes = before.state.nodes.map { n -> when (n.id) {
            structure -> n.copy(parentId = card.nodeId, revision = before.state.definitionRevision + 1)
            otherStructure -> n.copy(revision = before.state.definitionRevision + 1)
            card.nodeId -> n.copy(parentId = null, revision = n.revision + 1)
            else -> n
        } }
        val order = StudyOrganization.canonicalOrder(nodes, before.orderedNodeIds)
        val after = before.state.copy(nodes = nodes, orderedNodeIds = order,
            definitionRevision = before.state.definitionRevision + 1, orderRevision = before.state.orderRevision + 1)
        val plan = StudyOrganizationPlan(before.ref, StudyOrganizationKind.RESTORE, before.graphFingerprint,
            StudyOrganization.fingerprint(after), patch(before.state), patch(after))
        coroutineScope {
            val events = Channel<StudyGraphSnapshot>(Channel.UNLIMITED)
            val observer = launch { repo.observeGraph(book, named).collect { events.send(it) } }
            try {
                assertEquals(before.graphFingerprint, withTimeout(10_000) { events.receive() }.graphFingerprint)
                repo.submit(plan.command(id()))
                withTimeout(10_000) {
                    do {
                        val snapshot = events.receive()
                        assertTrue(snapshot.graphFingerprint in setOf(before.graphFingerprint, plan.expectedAfterGraph))
                        assertEquals(snapshot.orderedNodeIds, snapshot.nodes.filterNot { it.removed }.map { it.id })
                        assertTrue(snapshot.nodes.filter { it.id in snapshot.state.structuralNodeIds }.all { it.revision == snapshot.definition!!.revision })
                    } while (snapshot.graphFingerprint != plan.expectedAfterGraph)
                }
            } finally { observer.cancelAndJoin(); events.close() }
        }
        val applied = repo.readGraph(book, named)
        assertEquals(before.definition!!.revision + 1, applied.definition!!.revision)
        assertEquals(plan.expectedAfterGraph, applied.graphFingerprint)
        repo.submit(StudyOrganization.undo(StudyOrganization.apply(before.state, plan), plan).command(id()))
        val restored = repo.readGraph(book, named)
        assertEquals(before.orderedNodeIds, restored.orderedNodeIds)
        assertEquals(before.definition!!.revision + 2, restored.definition!!.revision)
        assertEquals(structure, restored.nodes.first { it.id == card.nodeId }.parentId)
        assertEquals("卡片主题 的正文", db.study().card(card.cardId!!)!!.body)
    }

    @Test fun measuredLayoutUsesFrozenCoordinatesAndRelevantContentFingerprint() = fixture { db, book ->
        val a = create(db, book, "长主题", x = 123.0, y = 777.0)
        create(db, book, "短主题", x = 999.0, y = 333.0)
        val repo = StudyRepository(db); val before = repo.readGraph(book)
        val sizes = before.orderedNodeIds.associateWith { StudyNodeSize(232.0, if (it == a.nodeId) 480.0 else 80.0) }
        val stalePreview = StudyOrganization.arrange(before.state, sizes)
        val unused = StudyCardRow(id(), book, 1, "未放入图的卡", "原文")
        db.study().addCard(unused); db.study().revision(StudyCardRevisionRow(unused.id, 1, unused.title, unused.body, null))
        repo.submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = unused.id, expectedRevision = 1, title = "无关卡改名"))
        assertEquals(before.graphFingerprint, repo.readGraph(book).graphFingerprint)
        repo.submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = a.cardId, expectedRevision = 1, title = "相关标题变化"))
        assertTrue(repo.outcome(stalePreview.command(id())) is StudyOutcome.Rejected)
        assertEquals(123.0, db.study().node(a.nodeId!!)!!.x, 0.0)
        val current = repo.readGraph(book); val plan = StudyOrganization.arrange(current.state, sizes)
        repo.submit(plan.command(id()))
        val applied = repo.readGraph(book)
        assertEquals(plan.expectedAfterGraph, applied.graphFingerprint)
        assertEquals(current.order!!.revision, applied.order!!.revision)
        plan.after.placements.forEach { p ->
            val row = applied.nodes.first { it.id == p.nodeId }
            assertEquals(p.x, row.x, 0.0); assertEquals(p.y, row.y, 0.0)
        }
        val undo = StudyOrganization.undo(StudyOrganization.apply(current.state, plan), plan).command(id())
        repo.submit(undo)
        assertEquals(123.0, db.study().node(a.nodeId!!)!!.x, 0.0)
        assertEquals(StudyOutcome.Rejected("LAYOUT_PREVIEW_REQUIRED"), repo.outcome(StudyCommand(id(), book, StudyAction.ARRANGE,
            expectedGraph = repo.readGraph(book).graphFingerprint)))
    }

    @Test fun removedStructureKeepsHistoricalOrderOwnershipThroughFullBackup() = fixture { db, book ->
        val structure = id(); val named = map(db, book, listOf(MapStructure(structure, null, "待移除结构", 40.0, 80.0)))
        val card = create(db, book, "旧子级", named, structure); val repo = StudyRepository(db)
        repo.submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = card.nodeId, expectedRevision = 1, mapId = named))
        repo.submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = structure, expectedRevision = 1, mapId = named))
        val current = repo.readGraph(book, named)
        assertTrue(current.orderedNodeIds.isEmpty()); assertTrue((current.definition!!.data() as KnowledgeData.MapDefinition).structures.isEmpty())
        val tombstone = current.nodes.single()
        assertTrue(tombstone.removed); assertNull(tombstone.parentId); assertEquals(3L, tombstone.revision)
        val historicalOrders = db.knowledge().revisions(current.order!!.id).map { KnowledgeCodec.decode(it.payload) as KnowledgeData.MapOrder }
        assertTrue(historicalOrders.any { structure in it.orderedNodeIds && card.nodeId in it.orderedNodeIds })
        KnowledgeRepository(db).validateArchive()
        val name = "organization-restore-${id()}.db"; val restored = NoteDatabase.open(context, name)
        try {
            LibraryBackupRepository(context, db).snapshot().use { snapshot ->
                val backup = LibraryBackupRepository(context, restored)
                snapshot.file.inputStream().use { backup.inspect(it) }.use { prepared ->
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED, backup.restore(prepared))
                }
            }
            assertEquals(current.graphFingerprint, StudyRepository(restored).readGraph(book, named).graphFingerprint)
            KnowledgeRepository(restored).validateArchive()
        } finally { restored.close(); context.deleteDatabase(name) }
    }

    @Test fun templatesAndIndependentCopiesKeepCanonicalOrderAndMixedParents() = fixture { db, book ->
        val template = KnowledgeData.MapTemplate("模板顺序", layout = "bilateral", nodes = listOf(
            TemplateNode("根", null, 40.0, 100.0), TemplateNode("第一主题", 0, -300.0, 900.0), TemplateNode("第二主题", 0, 300.0, 100.0)))
        val named = id(); val definition = MapTemplates.instantiate(template, "模板实例")
        ResourceTemplates(db).map("ab".repeat(32), KnowledgeCommand(id(), book, named, 0, definition))
        val root = definition.structures[0].id; val first = definition.structures[1].id
        val card = create(db, book, "中间卡片", named, root, first)
        val repo = StudyRepository(db); val before = repo.readGraph(book, named)
        assertEquals(listOf(root, first, card.nodeId, definition.structures[2].id), before.orderedNodeIds)
        repo.submit(StudyOrganization.reparent(before.state, first, card.nodeId).command(id()))
        val source = MapGraphAccess(db).read(book).first { it.ref.mapId == named }
        val operation = id(); val copy = MapEmbedRepository(db).duplicate(source.ref, source.signature(), operation)
        assertEquals(copy, MapEmbedRepository(db).duplicate(source.ref, source.signature(), operation))
        val copied = MapGraphAccess(db).read(book).first { it.ref == copy }
        assertEquals(source.nodes.map { it.title }, copied.nodes.map { it.title })
        assertTrue(source.nodes.map { it.id }.toSet().intersect(copied.nodes.map { it.id }.toSet()).isEmpty())
        assertEquals("bilateral", (repo.readGraph(book, copy.mapId).definition!!.data() as KnowledgeData.MapDefinition).layout)
        val copiedParent = copied.nodes.first { it.title == "中间卡片" }.id
        assertEquals(copiedParent, copied.nodes.first { it.title == "第一主题" }.parentId)
        assertNotEquals(card.cardId, copied.nodes.first { it.title == "中间卡片" }.cardId)
        val newBook = ResourceTemplates(db).instantiate("cd".repeat(32), "带模板的笔记", PaperStyle.BLANK, null, template, id())
        val newMap = MapGraphAccess(db).read(newBook.id).first { it.ref.mapId != null }
        assertEquals(listOf("根", "第一主题", "第二主题"), newMap.nodes.map { it.title })
        KnowledgeRepository(db).validateArchive()
    }

    @Test fun orderUniquenessOwnershipAndNodeLimitRejectWithoutPartialWrites() = fixture { db, book ->
        val structures = (0 until StudyGraph.MAX_NODES).map { MapStructure(id(), null, "主题 $it", 40.0, (1000 - it).toDouble()) }
        val named = map(db, book, structures); val repo = StudyRepository(db); val before = repo.readGraph(book, named)
        assertEquals(structures.map { it.id }, before.orderedNodeIds)
        val extra = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(), title = "第129个",
            mapId = named, expectedGraph = before.graphFingerprint)
        assertEquals(StudyOutcome.Rejected("STUDY_NODE_BUDGET"),repo.outcome(extra)); assertNull(db.study().card(extra.cardId!!)); assertNull(db.study().receipt(extra.id))
        val knowledge = KnowledgeRepository(db); val order = before.order!!
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.INVALID), knowledge.outcome(KnowledgeCommand(id(), book, order.id,
            order.revision, KnowledgeData.MapOrder(named, before.orderedNodeIds.dropLast(1)))))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.DUPLICATE), knowledge.outcome(KnowledgeCommand(id(), book, id(), 0,
            KnowledgeData.MapOrder(named, before.orderedNodeIds))))
        val other = WorkspaceRepository(db).create("其他笔记", false, PaperStyle.BLANK).id
        val otherNode = create(db, other, "其他主题")
        try { knowledge.validateData(book, KnowledgeData.MapOrder(null, listOf(otherNode.nodeId!!)), false); fail("foreign order accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before.graphFingerprint, repo.readGraph(book, named).graphFingerprint)
        assertEquals(1, db.knowledge().forBook(book).count { !it.removed && it.data() is KnowledgeData.MapOrder })
    }

    @Test fun namedRecordMovesAndMapRestorePreserveOrderAndRejectLegacyHash() = fixture { db, book ->
        val a = map(db, book, emptyList()); val b = map(db, book, emptyList())
        val card = create(db, book, "移动展示位置", a); val repo = StudyRepository(db); val knowledge = KnowledgeRepository(db)
        val old = db.knowledge().get(card.nodeId!!)!!
        knowledge.submit(KnowledgeCommand(id(), book, old.id, old.revision, (old.data() as KnowledgeData.MapOccurrence).copy(mapId = b)))
        assertTrue(repo.readGraph(book, a).orderedNodeIds.isEmpty())
        assertEquals(listOf(card.nodeId), repo.readGraph(book, b).orderedNodeIds)
        knowledge.validateArchive()
        val current = repo.readGraph(book, b)
        assertEquals(current.graphFingerprint, MapGraphAccess(db).read(book).first { it.ref.mapId == b }.graphHash)
        val legacy = StudyGraph.orderHash(current.nodes.map { it.model() })
        val stale = StudyCommand(id(), book, StudyAction.MOVE, nodeId = card.nodeId, expectedRevision = 2, mapId = b,
            expectedGraph = legacy, x = 500.0, y = 400.0)
        assertEquals(StudyOutcome.Rejected("MAP_VERSION_CHANGED"), repo.outcome(stale))
        assertEquals(current.graphFingerprint, repo.readGraph(book, b).graphFingerprint)
        val first = id(); val second = id()
        val restoredMap = map(db, book, listOf(MapStructure(first, null, "先创建", 40.0, 80.0), MapStructure(second, null, "后创建", 40.0, 500.0)))
        val graph = repo.readGraph(book, restoredMap)
        repo.submit(StudyOrganization.plan(graph.state, second, StudyOrganizationAction.UP).command(id()))
        val reordered = repo.readGraph(book, restoredMap)
        val definition = reordered.definition!!
        knowledge.submit(KnowledgeCommand(id(), book, definition.id, definition.revision, definition.data(), true))
        val removed = db.knowledge().get(restoredMap)!!
        knowledge.submit(KnowledgeCommand(id(), book, removed.id, removed.revision, removed.data()))
        val restored = repo.readGraph(book, restoredMap)
        assertEquals(listOf(second, first), restored.orderedNodeIds)
        assertTrue(restored.order!!.revision > reordered.order!!.revision)
        knowledge.validateArchive()
    }
}
