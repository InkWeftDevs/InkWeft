// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Read a content copy, never a database or arbitrary ZIP. Limits follow the
 * validated magic, not the filename, provider size metadata or available().
 * This implementation is deliberately bounded, not zero-copy streaming. */
object ContentTransfer {
    enum class Kind(val limit: Int, val extension: String) {
        PAGE(InkPageFile.MAX_BYTES, "iwpage"), BOOK(NotebookFile.MAX_BYTES, "iwbook")
    }
    sealed interface Content {
        val title: String
        class Page(val value: InkPageFile) : Content { override val title get() = value.title }
        class Book(val value: NotebookFile) : Content { override val title get() = value.title }
    }
    class Prepared internal constructor(val kind: Kind, val content: Content,
        val sha256: String, val byteCount: Int)

    fun kind(header: ByteArray): Kind {
        require(header.size >= 4) { "CONTENT_HEADER_MISSING" }
        val magic = header.take(4).fold(0) { n, b -> (n shl 8) or (b.toInt() and 255) }
        return when (magic) {
            0x49575031, 0x49575032, 0x49575033, 0x49575034, 0x49575035 -> Kind.PAGE
            0x49574231 -> Kind.BOOK
            else -> throw IllegalArgumentException("CONTENT_FORMAT_UNSUPPORTED")
        }
    }

    /** The caller owns/closes the stream. checkActive may throw CancellationException. */
    fun read(input: InputStream, checkActive: () -> Unit = {}): Prepared {
        val header = ByteArray(4)
        for (i in header.indices) {
            checkActive()
            val b = input.read()
            require(b >= 0) { "CONTENT_TRUNCATED" }
            header[i] = b.toByte()
        }
        val type = kind(header)
        val output = ByteArrayOutputStream(8192)
        output.write(header)
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            // Read only one extra byte at the bound, enough to reject overflow.
            val wanted = minOf(buffer.size, type.limit - output.size() + 1)
            var count = input.read(buffer, 0, wanted)
            if (count < 0) break
            if (count == 0) {
                val b = input.read()
                if (b < 0) break
                buffer[0] = b.toByte(); count = 1
            }
            require(output.size().toLong() + count <= type.limit) { "CONTENT_SIZE_LIMIT" }
            output.write(buffer, 0, count)
        }
        checkActive()
        return decode(output.toByteArray(), checkActive)
    }

    fun decode(bytes: ByteArray, checkActive: () -> Unit = {}): Prepared {
        val type = kind(bytes)
        require(bytes.size <= type.limit) { "CONTENT_SIZE_LIMIT" }
        checkActive()
        val content = when (type) {
            Kind.PAGE -> Content.Page(InkPageFile.decode(bytes))
            Kind.BOOK -> Content.Book(NotebookFile.decode(bytes))
        }
        checkActive()
        return Prepared(type, content, hash(bytes), bytes.size)
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun safeSuggestedName(title: String, extension: String): String {
        require(extension in listOf("iwpage", "iwbook"))
        val safe = title.filter { !it.isISOControl() && it !in "/\\:*?\"<>|" }
            .trim().take(72).trimEnd('.', ' ').ifBlank { "墨织内容副本" }
        return "$safe.$extension"
    }
}

/** COPY means the current committed snapshot at the first successful transaction.
 * Replaying the same identity returns that copy even if the source later changes. */
data class CopyNotebook(val commandId: String, val sourceId: String, val destinationId: String) {
    init { listOf(commandId, sourceId, destinationId).forEach(UUID::fromString); require(sourceId != destinationId) }
    fun digest() = ContentTransfer.hash("InkWeft.CopySaved/1\n$sourceId\n$destinationId".toByteArray())
}
data class ImportNotebook(val commandId: String, val destinationId: String, val contentSha256: String) {
    init { UUID.fromString(commandId); UUID.fromString(destinationId); require(contentSha256.matches(Regex("[0-9a-f]{64}"))) }
    fun digest() = ContentTransfer.hash("InkWeft.ImportCopy/1\n$destinationId\n$contentSha256".toByteArray())
}

/** Pure shelf ordering. Pinning is independent of the favorite filter. */
object ShelfOrder {
    fun <T> arrange(items: List<T>, pinned: (T) -> Boolean, title: (T) -> String, byTitle: Boolean): List<T> {
        val base = if (byTitle) items.sortedBy { title(it).lowercase(java.util.Locale.ROOT) } else items
        return base.filter(pinned) + base.filterNot(pinned)
    }
}
