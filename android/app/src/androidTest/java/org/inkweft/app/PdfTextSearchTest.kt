// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.artifex.mupdf.fitz.Document as NativeDocument
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/** Native instrumentation: owned synthetic fixtures, isolated Room databases, no mocks. */
class PdfTextSearchTest {
    private val app get() = ApplicationProvider.getApplicationContext<InkWeftApplication>()
    private fun id() = UUID.randomUUID().toString()

    private fun fixture(block: suspend (NoteDatabase, DocumentRendering) -> Unit) = runBlocking {
        val name = "pdf-search-${id()}.db"
        val root = File(app.cacheDir, "pdf-search-${id()}")
        val db = NoteDatabase.open(app, name)
        try {
            block(db, DocumentRendering(app, DocumentRepository(db), root))
        } finally {
            db.close()
            app.deleteDatabase(name)
            // This directory belongs only to this fixture, never to the user's document cache.
            root.deleteRecursively()
        }
    }

    private data class Imported(
        val bookId: String,
        val pages: List<String>,
        val command: ImportNotebook,
        val prepared: ContentTransfer.Prepared
    )

    private suspend fun importPrepared(db: NoteDatabase, prepared: ContentTransfer.Prepared): Imported {
        val command = ImportNotebook(id(), id(), prepared.sha256)
        val note = LibraryContentRepository(db).import(command, prepared)
        return Imported(note.id, NotebookPages(db).activePages(note.id).map { it.id }, command, prepared)
    }

    private suspend fun importPdf(db: NoteDatabase, source: PdfDocumentSource) = importPrepared(
        db, ContentTransfer.document("PDF 查找合成测试", source, source.sha256)
    )

    private suspend fun importBook(db: NoteDatabase, pages: List<InkPageFile>) = importPrepared(
        db, ContentTransfer.decode(NotebookFile("PDF 查找合成测试", "", pages).encode())
    )

    @Test fun bilingualLiteralSearchIgnoresCaseAndLocatesTheFirstRepeatedWord() = fixture { db, renderer ->
        val source = bilingualPdf()
        val imported = importPdf(db, source)
        assertEquals("The bilingual fixture must fit on one page", 1, imported.pages.size)
        val pageId = imported.pages.single()

        val lower = checkNotNull(renderer.search(pageId, "token"))
        val upper = checkNotNull(renderer.search(pageId, "TOKEN"))
        assertTrue(lower.hasText)
        val first = checkNotNull(lower.hit)
        assertEquals(first.bounds, checkNotNull(upper.hit).bounds)
        assertEquals(pageId, first.pageId)
        assertEquals(source.sha256, first.documentSha256)
        assertEquals(0, first.sourcePage)
        assertTrue("Snippet must come from the first occurrence's line", first.text.contains("FIRST"))
        assertTrue(first.text.contains("Token", ignoreCase = true))
        assertTrue(first.text.length <= 220)
        val second = checkNotNull(checkNotNull(renderer.search(pageId, "SECOND")).hit)
        assertTrue("Repeated-word result must locate the first, not the last occurrence", first.bounds.top < second.bounds.top)

        val chinese = checkNotNull(checkNotNull(renderer.search(pageId, "中文原文")).hit)
        assertTrue(chinese.text.contains("中文原文"))
        assertNotNull(checkNotNull(renderer.search(pageId, "a.b")).hit)
        assertNull("Query syntax must be literal, not a regular expression", checkNotNull(renderer.search(pageId, "[Tt]oken")).hit)
    }

    @Test fun sharedSourcesKeepTheirOwnPageIdentityAndOnlyTheCurrentSourceIsCached() = fixture { db, renderer ->
        val a = asciiPdf(
            PageSpec(lines = listOf(Line("SOURCE A ZERO"))),
            PageSpec(lines = listOf(Line("SOURCE A ONE")))
        )
        val b = asciiPdf(PageSpec(lines = listOf(Line("SOURCE B ONLY"))))
        // Notebook positions deliberately differ from original PDF page indices.
        val imported = importBook(db, listOf(
            page(a, 1), page(b, 0), page(a, 0)
        ))
        val sources = mutableMapOf<String, PdfDocumentSource>()
        val aOne = checkNotNull(checkNotNull(renderer.search(imported.pages[0], "source", sources)).hit)
        assertEquals(imported.pages[0], aOne.pageId)
        assertEquals(1, aOne.sourcePage)
        assertEquals(a.sha256, aOne.documentSha256)
        assertEquals(1, sources.size)
        val retainedA = sources.values.single()

        val aZero = checkNotNull(checkNotNull(renderer.search(imported.pages[2], "source", sources)).hit)
        assertEquals(0, aZero.sourcePage)
        assertEquals(a.sha256, aZero.documentSha256)
        assertSame("Different pages of one PDF must reuse the loaded source", retainedA, sources.values.single())

        val bOnly = checkNotNull(checkNotNull(renderer.search(imported.pages[1], "source", sources)).hit)
        assertEquals(imported.pages[1], bOnly.pageId)
        assertEquals(0, bOnly.sourcePage)
        assertEquals(b.sha256, bOnly.documentSha256)
        assertEquals("Search must release the previous PDF when changing documents", 1, sources.size)
        assertEquals(b.sha256, sources.values.single().sha256)

        val aAgain = checkNotNull(checkNotNull(renderer.search(imported.pages[0], "SOURCE", sources)).hit)
        assertEquals(1, aAgain.sourcePage)
        assertEquals(a.sha256, aAgain.documentSha256)
        assertEquals(1, sources.size)
        assertEquals(a.sha256, sources.values.single().sha256)

        // Identical bytes in another notebook have a different document_sources.id.
        // A digest-only eviction rule must not retain both owners' full source objects.
        val identicalCopy = importPdf(db, a)
        val copiedHit = checkNotNull(checkNotNull(renderer.search(identicalCopy.pages[1], "source", sources)).hit)
        assertEquals(identicalCopy.pages[1], copiedHit.pageId)
        assertEquals(a.sha256, copiedHit.documentSha256)
        assertEquals(1, copiedHit.sourcePage)
        assertEquals("Identical bytes owned by a second notebook must replace the cached source", 1, sources.size)
    }

    @Test fun scannedAndOrdinaryPagesDoNotInventNativePdfText() = fixture { db, renderer ->
        val imported = importPdf(db, imageOnlyPdf())
        val scan = checkNotNull(renderer.search(imported.pages.single(), "SCAN_ONLY"))
        assertFalse("Rasterized visible words are not a native text layer", scan.hasText)
        assertNull(scan.hit)

        val note = WorkspaceRepository(db).create("普通合成纸张", false, PaperStyle.BLANK)
        val sources = mutableMapOf<String, PdfDocumentSource>()
        assertNull(renderer.search(note.id, "SCAN_ONLY", sources))
        assertTrue(sources.isEmpty())
    }

    @Test fun nativeMatchBeyondTwentyThousandCharactersIsNotLostToSnippetTruncation() = fixture { db, renderer ->
        val tail = "TAIL_SEARCH_SENTINEL_20261001"
        val lines = List(250) { index -> Line("a".repeat(96), 40, 800 - index * 2, 1) } +
            Line("END $tail", 40, 280, 1)
        val source = asciiPdf(PageSpec(lines = lines))
        val complete = nativeText(source, 0)
        assertTrue("Fixture must put its target after the old 20k cutoff", complete.indexOf(tail) > 20_000)
        val imported = importPdf(db, source)
        val result = checkNotNull(renderer.search(imported.pages.single(), tail.lowercase(Locale.ROOT)))
        assertTrue(result.hasText)
        val hit = checkNotNull(result.hit)
        assertTrue("Snippet must be taken near the native hit", hit.text.contains(tail))
        assertTrue(hit.text.length <= 220)
        assertTrue("Tail hit must focus the lower part of the page", hit.bounds.top > 800.0)
    }

    @Test fun landscapeNonzeroCropAndRotationBoundsCoverTheRenderedText() = fixture { db, renderer ->
        val cases = listOf(
            PageSpec(width = 842, height = 595, lines = listOf(Line("GeometryToken", 220, 280, 24))),
            PageSpec(width = 800, height = 600, crop = "80 40 720 560", lines = listOf(Line("GeometryToken", 220, 280, 24))),
            PageSpec(width = 800, height = 600, crop = "80 40 720 560", rotation = 90, lines = listOf(Line("GeometryToken", 220, 280, 24)))
        )
        for ((index, spec) in cases.withIndex()) {
            val source = asciiPdf(spec)
            val imported = importPdf(db, source)
            val hit = checkNotNull(checkNotNull(renderer.search(imported.pages.single(), "GeometryToken")).hit)
            // Independent Android renderer oracle: this PDF contains only the queried black text.
            val visible = renderedBlackBounds(source, 0)
            val padded = hit.bounds.padded(4.0)
            assertTrue("Case $index: quad must cover rendered left edge", padded.left <= visible.left)
            assertTrue("Case $index: quad must cover rendered top edge", padded.top <= visible.top)
            assertTrue("Case $index: quad must cover rendered right edge", padded.right >= visible.right)
            assertTrue("Case $index: quad must cover rendered bottom edge", padded.bottom >= visible.bottom)
            val quadArea = (hit.bounds.right - hit.bounds.left) * (hit.bounds.bottom - hit.bounds.top)
            val inkArea = (visible.right - visible.left) * (visible.bottom - visible.top)
            assertTrue("Case $index: result must focus glyphs, not the whole page", quadArea < inkArea * 4 + 64)
            assertTrue(hit.bounds.left >= 0 && hit.bounds.top >= 0)
            assertTrue(hit.bounds.right <= 1000 && hit.bounds.bottom <= 1414)
        }
    }

    @Test fun aZeroAdvanceCharacterFromAMulticharacterToUnicodeMappingStillLocatesItsGlyph() = fixture { db, renderer ->
        // One real Helvetica fi glyph, encoded as A, has two Unicode characters.
        // The native text extractor must emit f with the advance and i without it.
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /InkWeftFiFixture def
            /CMapType 2 def
            1 begincodespacerange
            <00> <FF>
            endcodespacerange
            1 beginbfchar
            <41> <00660069>
            endbfchar
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
        """.trimIndent() + "\n"
        val source = asciiPdf(PageSpec(lines = listOf(Line("A", 160, 600, 48))),
            toUnicode = cmap, fontEncoding = "<< /Type /Encoding /Differences [65 /fi] >>")
        val document = NativeDocument.openDocument(source.bytes(), "application/pdf")
        try {
            val page = document.loadPage(0)
            try {
                val structured = page.toStructuredText()
                try {
                    val chars = structured.blocks.flatMap { it.lines.toList() }.flatMap { it.chars.toList() }
                    assertEquals("The fixture must be a real multicharacter Unicode mapping", "fi",
                        buildString { chars.forEach { appendCodePoint(it.c) } })
                    val i = chars.single { it.c == 'i'.code }
                    val rect = i.quad.toRect()
                    assertTrue("The native i character must have a degenerate quad, not a fake test quad",
                        rect.x0 == rect.x1 || rect.y0 == rect.y1)
                } finally { structured.destroy() }
            } finally { page.destroy() }
        } finally { document.destroy() }

        val imported = importPdf(db, source)
        val pageId = imported.pages.single()
        val before = rows(db)
        val result = checkNotNull(renderer.search(pageId, "i"))
        assertTrue(result.hasText)
        val hit = checkNotNull(result.hit) { "A zero-advance native character is still a textual match" }
        assertEquals(pageId, hit.pageId)
        assertEquals(source.sha256, hit.documentSha256)
        assertEquals(0, hit.sourcePage)
        assertTrue(hit.text.contains("fi"))
        assertTrue("Line fallback must have an area", hit.bounds.right > hit.bounds.left && hit.bounds.bottom > hit.bounds.top)
        assertTrue("Fallback must locate the real glyph rendered by Android",
            hit.bounds.intersects(renderedBlackBounds(source, 0)))
        assertArrayEquals(source.bytes(), checkNotNull(DocumentRepository(db).read(pageId)).document.bytes())
        assertEquals("Glyph fallback must remain derived read-only work", before, rows(db))
    }

    @Test fun validationCancellationAndRasterPressureLeaveAuthorRowsAndReceiptsUntouched() = fixture { db, renderer ->
        val longTerm = "z".repeat(230)
        val source = asciiPdf(PageSpec(lines = listOf(
            Line("AuthorSearchMarker"), Line("BOUNDARY $longTerm TAIL", 48, 700, 2)
        )))
        val stroke = InkStroke(id(), InkPen.PEN, Color.BLUE, 3f, InkTool.STYLUS,
            listOf(InkSample(100f, 100f, 0), InkSample(160f, 140f, 20)))
        val objectText = PageObject(id(), PageObjectKind.TEXT, text = "作者文本保持")
        val imported = importBook(db, listOf(InkPageFile("有原迹的合成PDF", "", listOf(stroke),
            paper = PaperStyle.BLANK, objects = listOf(objectText), source = PdfPageSource(source, 0))))
        val pageId = imported.pages.single()
        val pages = NotebookPages(db)
        val inkRevision = InkRepository(db).read(pageId).revision
        val objectRevision = PageObjectRepository(db).read(pageId).revision
        assertTrue(pages.saveSearchText(pageId, inkRevision, "独立人工校对内容", objectRevision, "MANUAL"))
        val manual = pages.searchText(pageId)
        val receipt = db.libraryContent().receipt(imported.command.commandId)
        assertNotNull(receipt)
        val before = rows(db)

        val sources = mutableMapOf("untouched-seed" to source)
        assertNull("Blank query must return before source lookup", renderer.search(id(), " \t\n ", sources))
        assertEquals(setOf("untouched-seed"), sources.keys)
        try {
            renderer.search(id(), "x".repeat(257), sources)
            fail("Overlong query must be rejected before reading the source")
        } catch (_: IllegalArgumentException) { }
        val longest = checkNotNull(renderer.search(pageId, "x".repeat(256), sources))
        assertTrue(longest.hasText)
        assertNull(longest.hit)
        assertNotNull(checkNotNull(renderer.search(pageId, " ".repeat(300) + "AuthorSearchMarker" + " ".repeat(300), sources)).hit)
        val longHit = checkNotNull(checkNotNull(renderer.search(pageId, longTerm, sources)).hit)
        assertTrue("A valid long query must keep the complete matched term", longHit.text.contains(longTerm))
        assertTrue("A valid long query must still return a bounded result snippet", longHit.text.length <= 400)

        val cancelledSources = mutableMapOf<String, PdfDocumentSource>()
        coroutineScope {
            var entered = false
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                entered = true
                renderer.search(pageId, "AuthorSearchMarker", cancelledSources)
                fail("A cancelled search must not publish a result")
            }
            cancelled.join()
            assertTrue(entered)
            assertTrue(cancelled.isCancelled)
        }
        assertTrue("Cancelled work must not load a PDF into the caller cache", cancelledSources.isEmpty())

        val held = Any()
        val owner = "pdf-search-budget-${id()}"
        RenderResources.track(held, RenderResources.BUDGET, "fixture", owner, RenderResources.Role.ACTIVE)
        try {
            assertNotNull("Native text search must not fall back to a bitmap allocation",
                checkNotNull(renderer.search(pageId, "AuthorSearchMarker", sources)).hit)
        } finally {
            RenderResources.release(held, owner)
        }

        assertEquals(manual, pages.searchText(pageId))
        assertArrayEquals(source.bytes(), checkNotNull(DocumentRepository(db).read(pageId)).document.bytes())
        assertEquals(receipt, db.libraryContent().receipt(imported.command.commandId))
        assertEquals("Search, invalid queries and cancellation must not write any fixture table", before, rows(db))
        assertEquals(imported.bookId, LibraryContentRepository(db).import(imported.command, imported.prepared).id)
        assertEquals("Original import receipt must still replay without creating content", before, rows(db))
    }

    private fun page(source: PdfDocumentSource, sourcePage: Int) = InkPageFile(
        "合成源页", "", emptyList(), paper = PaperStyle.BLANK, source = PdfPageSource(source, sourcePage)
    )

    private suspend fun bilingualPdf(): PdfDocumentSource {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>PDF search fixture</dc:title><dc:identifier id="id">inkweft-pdf-search-owned-fixture</dc:identifier><dc:language>zh</dc:language></metadata><manifest><item id="p" href="page.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="p"/></spine></package>""")
            entry("page.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Search</title></head><body><h1>墨织查找</h1><p>FIRST Token a.b 中文原文</p><p>SECOND TOKEN axb 另一个词</p></body></html>""")
        }
        return PdfDocumentSource(DocumentImports.convert(app, out.toByteArray(), "epub"), 1)
    }

    private fun imageOnlyPdf(): PdfDocumentSource {
        val bitmap = Bitmap.createBitmap(360, 180, Bitmap.Config.ARGB_8888)
        val document = PdfDocument()
        val out = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("SCAN_ONLY", 12f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 42f
            })
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bitmap, 80f, 200f, Paint())
            document.finishPage(page)
            document.writeTo(out)
        } finally {
            document.close(); bitmap.recycle()
        }
        return PdfDocumentSource(out.toByteArray(), 1)
    }

    private data class Line(val text: String, val x: Int = 48, val y: Int = 740, val size: Int = 18)
    private data class PageSpec(
        val width: Int = 595, val height: Int = 842,
        val crop: String? = null, val rotation: Int = 0,
        val lines: List<Line>
    )

    /** Minimal valid PDF with actual text, standard Helvetica, byte-correct streams and xref. */
    private fun asciiPdf(vararg pages: PageSpec, toUnicode: String? = null, fontEncoding: String? = null): PdfDocumentSource {
        val objects = mutableListOf<ByteArray>()
        fun bytes(text: String) = text.toByteArray(Charsets.US_ASCII)
        objects += bytes("<< /Type /Catalog /Pages 2 0 R >>")
        val kids = pages.indices.joinToString(" ") { "${4 + it * 2} 0 R" }
        objects += bytes("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        val unicode = toUnicode?.let { "/ToUnicode ${4 + pages.size * 2} 0 R" }.orEmpty()
        val encoding = fontEncoding?.let { "/Encoding $it" }.orEmpty()
        objects += bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica $encoding $unicode >>")
        for ((index, spec) in pages.withIndex()) {
            val contents = bytes(buildString {
                append("BT\n")
                for (line in spec.lines) {
                    val escaped = line.text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                    append("/F1 ${line.size} Tf\n1 0 0 1 ${line.x} ${line.y} Tm\n($escaped) Tj\n")
                }
                append("ET\n")
            })
            val crop = spec.crop?.let { "/CropBox [$it]" }.orEmpty()
            objects += bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${spec.width} ${spec.height}] $crop /Rotate ${spec.rotation} /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + index * 2} 0 R >>")
            objects += bytes("<< /Length ${contents.size} >>\nstream\n") + contents + bytes("endstream")
        }
        toUnicode?.let { text ->
            val contents = bytes(text)
            objects += bytes("<< /Length ${contents.size} >>\nstream\n") + contents + bytes("endstream")
        }
        val out = ByteArrayOutputStream()
        out.write(bytes("%PDF-1.7\n"))
        val offsets = objects.mapIndexed { index, body ->
            val offset = out.size()
            out.write(bytes("${index + 1} 0 obj\n")); out.write(body); out.write(bytes("\nendobj\n"))
            offset
        }
        val xref = out.size()
        out.write(bytes("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
        for (offset in offsets) out.write(bytes(String.format(Locale.ROOT, "%010d 00000 n \n", offset)))
        out.write(bytes("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"))
        return PdfDocumentSource(out.toByteArray(), pages.size)
    }

    private fun nativeText(source: PdfDocumentSource, sourcePage: Int): String {
        val document = NativeDocument.openDocument(source.bytes(), "application/pdf")
        try {
            val page = document.loadPage(sourcePage)
            try {
                val text = page.toStructuredText()
                try { return text.asText() } finally { text.destroy() }
            } finally { page.destroy() }
        } finally { document.destroy() }
    }

    private fun renderedBlackBounds(source: PdfDocumentSource, sourcePage: Int): CanvasBounds {
        val file = File.createTempFile("pdf-search-geometry-", ".pdf", app.cacheDir)
        val bitmap = Bitmap.createBitmap(1000, 1414, Bitmap.Config.ARGB_8888)
        try {
            file.writeBytes(source.bytes()); bitmap.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer -> renderer.openPage(sourcePage).use { page ->
                    val scale = min(1000f / page.width, 1414f / page.height)
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((1000 - page.width * scale) / 2, (1414 - page.height * scale) / 2)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } }
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val color = pixels[y * bitmap.width + x]
                if (Color.red(color) < 96 && Color.green(color) < 96 && Color.blue(color) < 96) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The geometry oracle must render real black glyphs", right >= left && bottom >= top)
            return CanvasBounds(left.toDouble(), top.toDouble(), (right + 1).toDouble(), (bottom + 1).toDouble())
        } finally {
            bitmap.recycle(); file.delete()
        }
    }

    /** Snapshot every table only in this newly created synthetic database, including receipts. */
    private fun rows(db: NoteDatabase): Map<String, List<List<String>>> {
        val sql = db.openHelper.readableDatabase
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return names.associateWith { table ->
            sql.query("SELECT * FROM \"$table\"").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> "NULL"
                            Cursor.FIELD_TYPE_BLOB -> "BLOB:" + ContentTransfer.hash(cursor.getBlob(column))
                            Cursor.FIELD_TYPE_INTEGER -> "INT:" + cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> "FLOAT:" + cursor.getDouble(column)
                            else -> "TEXT:" + cursor.getString(column)
                        }
                    })
                }.sortedBy { it.joinToString("\u001f") }
            }
        }
    }
}
