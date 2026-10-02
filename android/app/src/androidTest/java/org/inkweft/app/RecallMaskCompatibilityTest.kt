// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Run a on the actual V52 main APK, b after replacing only main with V53, then c
 * after installing the actual V52 main APK again. The same runner stays installed.
 * This class deliberately references only APIs already present in V52. Fixtures
 * are synthetic author data, not handwriting or recovery evidence from a user.
 * It never clears the application DB. Only its private round-trip DBs are deleted.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecallMaskCompatibilityTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private val evidence get() = File(app.filesDir, "RM53-compat-evidence").apply { check(isDirectory || mkdirs()) }
    private fun id(name: String) = UUID.nameUUIDFromBytes(("RM53-compat:" + name).toByteArray(Charsets.UTF_8)).toString()
    private val book get() = id("book")
    private val page get() = id("page-2")
    private val mapA get() = id("map-a")
    private val mapB get() = id("map-b")
    private val card get() = id("shared-card")
    private val node get() = id("map-a-node")
    private val question get() = id("question")
    private val portal get() = id("portal")

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode

    private fun requireVersion(expected: Long) {
        assertEquals("Run this stage on the actual requested main APK, not the runner's BuildConfig", expected, installedVersion())
    }

    private suspend fun <T> withProbe(block: suspend (NoteDatabase) -> T): T {
        val db = NoteDatabase.open(app)
        return try { block(db) } finally { db.close() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    // Payloads, author timestamps, tombstones and receipt IDs are all retained.
    // Only centerX/centerY/zoom (view state) are omitted from page/workspace rows.
    // Device last-visit preferences are outside the author DB and this snapshot.
    private val queries = listOf(
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

    private suspend fun canonical(db: NoteDatabase): String = db.withTransaction {
        val result = JSONArray()
        queries.forEach { sql ->
            db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(book))).use { c ->
                val rows = JSONArray()
                while (c.moveToNext()) rows.put(JSONArray().apply {
                    repeat(c.columnCount) { column ->
                        put(when (c.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> "int:" + c.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> "float:" + c.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + c.getBlob(column).size + ":" + sha(c.getBlob(column))
                            else -> "text:" + c.getString(column)
                        })
                    }
                })
                result.put(JSONObject().put("query", sql).put("columns", JSONArray(c.columnNames.toList())).put("rows", rows))
            }
        }
        result.toString()
    }

    private suspend fun verifyFixture(db: NoteDatabase, revision: Long) {
        assertEquals(12, db.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) })
        val ink = InkRepository(db).read(page)
        assertEquals(revision, ink.revision)
        assertEquals(revision.toInt(), ink.strokes.size)
        assertTrue(ink.strokes.any { it.stroke.id == id("stroke-v52") })
        val pdf = checkNotNull(DocumentRepository(db).read(page))
        assertEquals(1, pdf.document.pages)
        assertEquals(id("pdf-source"), checkNotNull(db.documents().page(page)).documentId)
        val pdfMetadata = checkNotNull(db.documents().source(id("pdf-source")))
        assertEquals(pdfMetadata.sha256, sha(pdf.document.bytes()))
        assertEquals(pdfMetadata.byteCount, pdf.document.bytes().size)
        val objects = PageObjectRepository(db).read(page)
        assertEquals(revision, objects.revision)
        assertEquals(if (revision == 1L) 2 else 3, objects.objects.size)
        assertTrue(objects.objects.any { it.id == id("text") && it.kind == PageObjectKind.TEXT })
        assertTrue(objects.objects.any { it.id == id("tape") && it.kind == PageObjectKind.TAPE && !it.revealed })
        assertEquals(0x49574f39, DataInputStream(ByteArrayInputStream(PageObjectCodec.encode(objects.objects))).use { it.readInt() })
        assertEquals(revision, checkNotNull(db.study().card(card)).revision)
        assertEquals("共享卡原始正文：目标知识用于阅读。", checkNotNull(db.study().cardVersion(card, 1)).body)
        assertEquals(page, checkNotNull(db.study().source(card)).pageId)
        assertTrue(checkNotNull(db.study().source(card)).snapshot.isNotEmpty())
        assertEquals(revision, checkNotNull(db.knowledge().get(question)).revision)
        assertEquals(revision, checkNotNull(db.knowledge().get(portal)).revision)
        val link = checkNotNull(db.knowledge().get(id("link"))).data() as KnowledgeData.Link
        assertEquals(TargetRef(TargetKind.CARD, card), link.source)
        assertEquals(TargetRef(TargetKind.CARD, id("target-card")), link.target)
        assertEquals(1L, checkNotNull(link.pinnedRevision))
        val entry = MapPortalRepository(db).preview(book, portal, revision)
        assertTrue(entry.canOpen)
        assertEquals(MapRef(book, mapA), entry.source)
        assertEquals(MapRef(book, if (revision == 1L) mapB else null), entry.target)
        val scenes = MapGraphAccess(db).read(book)
        assertTrue(scenes.any { it.ref == MapRef(book, mapA) && it.nodes.any { n -> n.id == node && n.cardId == card } })
        assertTrue(scenes.any { it.ref == MapRef(book, mapB) && it.nodes.any { n -> n.id == id("map-b-card-node") && n.cardId == card } })
        assertTrue(scenes.any { it.ref == MapRef(book) && it.nodes.any { n -> n.id == id("main-node") && n.cardId == card } })
    }

    private suspend fun export(stage: String) {
        app.libraryBackup.snapshot().use { snapshot ->
            snapshot.file.copyTo(File(evidence, "$stage.iwbackup"), overwrite = true)
        }
    }

    private suspend fun roundTrip(stage: String, archive: File, expected: String) {
        // This name cannot collide with the application's author DB or other tests.
        val name = "RM53-compat-roundtrip-$stage-${UUID.randomUUID()}.db"
        val db = NoteDatabase.open(app, name)
        try {
            val backup = LibraryBackupRepository(app, db)
            archive.inputStream().use { input -> backup.inspect(input).use { preview ->
                assertEquals(LibraryBackupRepository.RestoreResult.RESTORED, backup.restore(preview))
                assertEquals("Full backup must retain exact author rows, revisions and receipt identities", expected, canonical(db))
                verifyFixture(db, if (stage.startsWith("v52")) 1 else 2)
                assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT, backup.restore(preview))
                assertEquals("A duplicate restore creates no revisions or receipts", expected, canonical(db))
            } }
        } finally {
            db.close()
            assertTrue("Only this private round-trip database is removed", app.deleteDatabase(name))
        }
    }

    private fun record(stage: String, archive: File, canonical: String) {
        File(evidence, "$stage-result.json").writeText(JSONObject()
            .put("stage", stage).put("installedMainVersion", installedVersion())
            .put("bookId", book).put("syntheticFixture", true)
            .put("canonicalSha256", sha(canonical.toByteArray(Charsets.UTF_8)))
            .put("backupSha256", sha(archive.readBytes()))
            .put("schema", 12).put("pageObjectFormat", "IWO9")
            .put("canonicalQueryCount", queries.size).put("ownedPdfBytesIncluded", true)
            .put("exactAuthorHistoryAndReceipts", true)
            .put("excluded", "notebook_workspace and notebook_pages centerX/centerY/zoom; device last-visit preferences")
            .toString(2))
    }

    @Test fun a_seedV52FixtureAndExport() = runBlocking {
        requireVersion(52)
        withProbe { db ->
            val baseline = File(evidence, "v52-author.json")
            if (baseline.exists()) {
                assertEquals("A rerun must not reset or overwrite the persisted fixture", baseline.readText(), canonical(db))
                verifyFixture(db, 1)
                return@withProbe
            }
            assertNull("Never erase an existing namespaced identity", db.notes().note(book))
            app.workspaceRepository.create("RM53-compat 合成兼容笔记", false, PaperStyle.RULED, operationId = book)
            app.pages.addAfter(book, book, page)
            app.pages.select(book, page)
            // A valid synthetic owned PDF is seeded through the V52 author DAO API.
            // It is not a user's file; canonical checks include its metadata, bytes
            // and page association during both APK replacement directions.
            val pdf = PdfDocument()
            val pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 1414, 1).create())
            pdfPage.canvas.drawColor(Color.WHITE)
            val pdfPaint = Paint().apply { color = 0xffbc006f.toInt(); textSize = 42f }
            pdfPage.canvas.drawText("RM53-compat synthetic owned PDF", 100f, 500f, pdfPaint)
            pdf.finishPage(pdfPage)
            val bytes = ByteArrayOutputStream().also(pdf::writeTo).toByteArray()
            pdf.close()
            db.withTransaction {
                db.documents().insertSource(DocumentSourceRow(id("pdf-source"), book, sha(bytes), 1, bytes.size))
                bytes.asList().chunked(DocumentRepository.CHUNK).forEachIndexed { position, chunk ->
                    db.documents().insertChunk(DocumentChunkRow(id("pdf-source"), position, chunk.toByteArray()))
                }
                db.documents().insertPage(DocumentPageRow(page, id("pdf-source"), 0))
            }
            val stroke = InkStroke(id("stroke-v52"), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(100f, 380f, 0), InkSample(440f, 390f, 100)))
            assertTrue(app.inkRepository.save(CommitInk(id("ink-op-v52"), page, 0, InkMutation.Add(stroke))) is InkCommitResult.Committed)
            app.pageObjects.save(page, 0, id("objects-op-v52"), listOf(
                PageObject(id("text"), PageObjectKind.TEXT, 90f, 90f, 760f, 140f, text = "RM53-compat 已有文本对象"),
                PageObject(id("tape"), PageObjectKind.TAPE, 100f, 270f, 760f, 56f)))
            app.knowledge.submit(KnowledgeCommand(id("map-a-op"), book, mapA, 0, KnowledgeData.MapDefinition("RM53-compat 原图")))
            app.knowledge.submit(KnowledgeCommand(id("map-b-op"), book, mapB, 0,
                KnowledgeData.MapDefinition("RM53-compat 目标图", structures = listOf(MapStructure(id("map-b-root"), null, "已有结构主题", 40.0, 80.0)))))
            app.study.submit(StudyCommand(id("card-create-op"), book, StudyAction.CREATE, cardId = card, nodeId = node,
                title = "共享卡", body = "共享卡原始正文：目标知识用于阅读。", mapId = mapA,
                source = StudySourceDraft(page, 1, stroke.bounds(), listOf(stroke.id))))
            app.study.submit(StudyCommand(id("card-reuse-main-op"), book, StudyAction.REUSE, cardId = card, nodeId = id("main-node")))
            app.study.submit(StudyCommand(id("card-reuse-map-b-op"), book, StudyAction.REUSE, cardId = card,
                nodeId = id("map-b-card-node"), parentId = id("map-b-root"), mapId = mapB, x = 320.0, y = 180.0))
            app.study.submit(StudyCommand(id("target-card-op"), book, StudyAction.CREATE, cardId = id("target-card"), nodeId = id("target-node"),
                title = "目标知识", body = "V52 固定摘录正文", x = 360.0, y = 420.0))
            app.knowledge.submit(KnowledgeCommand(id("link-op"), book, id("link"), 0,
                KnowledgeData.Link(TargetRef(TargetKind.CARD, card), TargetRef(TargetKind.CARD, id("target-card")), pinnedRevision = 1)))
            app.knowledge.submit(KnowledgeCommand(id("question-op-v52"), book, question, 0, KnowledgeData.Question(card, "V52 已有回忆题？")))
            app.knowledge.submit(KnowledgeCommand(id("portal-op-v52"), book, portal, 0, KnowledgeData.MapPortal(mapA, node, mapB)))
            verifyFixture(db, 1)
            val expected = canonical(db)
            baseline.writeText(expected)
            export("v52")
            File(evidence, "fixture.json").writeText(JSONObject().put("namespace", "RM53-compat")
                .put("book", book).put("page", page).put("mapA", mapA).put("mapB", mapB)
                .put("card", card).put("node", node).put("question", question).put("portal", portal)
                .put("syntheticFixture", true).toString(2))
            record("v52-seed", File(evidence, "v52.iwbackup"), expected)
        }
    }

    @Test fun b_verifyV53ReadsAndBackupRoundTrips() = runBlocking {
        requireVersion(53)
        withProbe { db ->
            val v52 = File(evidence, "v52-author.json").readText()
            assertEquals("V53 must read actual V52 data without author changes", v52, canonical(db))
            verifyFixture(db, 1)
            roundTrip("v52-to-v53", File(evidence, "v52.iwbackup"), v52)
            // Exercise unchanged V52 writers on V53 before testing the real downgrade.
            val stroke = InkStroke(id("stroke-v53"), InkPen.PEN, 0xff286aca.toInt(), 3f, InkTool.STYLUS,
                listOf(InkSample(140f, 640f, 0), InkSample(460f, 670f, 120)))
            assertTrue(app.inkRepository.save(CommitInk(id("ink-op-v53"), page, 1, InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val prior = app.pageObjects.read(page)
            app.pageObjects.save(page, prior.revision, id("objects-op-v53"), prior.objects +
                PageObject(id("text-v53"), PageObjectKind.TEXT, 90f, 760f, 760f, 140f, text = "V53 用既有 IWO9 保存"))
            app.study.submit(StudyCommand(id("card-edit-op-v53"), book, StudyAction.EDIT, cardId = card,
                expectedRevision = 1, title = "共享卡 V53", body = "V53 正文仍用既有作者模型与不可变历史。"))
            app.knowledge.submit(KnowledgeCommand(id("question-op-v53"), book, question, 1, KnowledgeData.Question(card, "V53 更新后的回忆题？")))
            app.knowledge.submit(KnowledgeCommand(id("portal-op-v53"), book, portal, 1, KnowledgeData.MapPortal(mapA, node, null)))
            verifyFixture(db, 2)
            assertNotNull(db.ink().receipt(id("ink-op-v53")))
            assertNotNull(db.study().receipt(id("card-edit-op-v53")))
            val expected = canonical(db)
            File(evidence, "v53-author.json").writeText(expected)
            export("v53")
            roundTrip("v53-current", File(evidence, "v53.iwbackup"), expected)
            record("v53-upgrade", File(evidence, "v53.iwbackup"), expected)
        }
    }

    @Test fun c_verifyV52ReadsV53Data() = runBlocking {
        requireVersion(52)
        withProbe { db ->
            val expected = File(evidence, "v53-author.json").readText()
            assertEquals("Actual V52 downgrade must retain V53 author values, revisions and original receipts", expected, canonical(db))
            verifyFixture(db, 2)
            roundTrip("v53-to-v52", File(evidence, "v53.iwbackup"), expected)
            export("v52-after-v53")
            roundTrip("downgraded-v52-export", File(evidence, "v52-after-v53.iwbackup"), expected)
            assertEquals("Reading and full backup on V52 create no author history", expected, canonical(db))
            record("v52-downgrade", File(evidence, "v52-after-v53.iwbackup"), expected)
        }
    }
}
