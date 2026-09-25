// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

enum class PageInsertLocation { BEFORE, AFTER, START, END }

/** A frozen operation: retries reuse IDs AND the original position/template.
 * Ordering is presentation, never the identity used by ink/search/source links. */
class InsertPages(
    val commandId: String,
    val notebookId: String,
    val expectedOrder: String,
    val location: PageInsertLocation,
    val anchorPageId: String?,
    val paper: PaperStyle,
    pageIds: List<String>,
    val openInserted: Boolean,
) {
    val pageIds: List<String> = Collections.unmodifiableList(ArrayList(pageIds))
    init {
        canonicalId(commandId); canonicalId(notebookId)
        require(expectedOrder.matches(Regex("[a-f0-9]{64}")))
        require(this.pageIds.size in 1..MAX_BATCH && this.pageIds.distinct().size == this.pageIds.size)
        this.pageIds.forEach(::canonicalId)
        require(notebookId !in this.pageIds && commandId !in this.pageIds)
        if (location == PageInsertLocation.BEFORE || location == PageInsertLocation.AFTER) {
            canonicalId(requireNotNull(anchorPageId)); require(anchorPageId !in this.pageIds)
        } else require(anchorPageId == null)
    }
    fun insertionIndex(orderedIds: List<String>): Int {
        require(orderedIds.isNotEmpty() && orderedIds.distinct().size == orderedIds.size)
        return when (location) {
            PageInsertLocation.START -> 0
            PageInsertLocation.END -> orderedIds.size
            PageInsertLocation.BEFORE, PageInsertLocation.AFTER -> {
                val index = orderedIds.indexOf(anchorPageId)
                require(index >= 0) { "Insertion anchor is no longer in this notebook" }
                index + if (location == PageInsertLocation.AFTER) 1 else 0
            }
        }
    }
    fun digest(): String = hashFields(listOf("inkweft.insert-pages.v1", commandId, notebookId,
        expectedOrder, location.name, anchorPageId.orEmpty(), paper.name, openInserted.toString()) + pageIds)

    companion object {
        const val MAX_BATCH = 20
        const val MAX_PAGES = 500
        fun orderHash(orderedIds: List<String>): String = hashFields(listOf("inkweft.page-order.v1") + orderedIds)
        private fun canonicalId(id: String) { require(UUID.fromString(id).toString() == id) }
        private fun hashFields(fields: List<String>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(fields.size)
                fields.forEach { text -> val value = text.toByteArray(Charsets.UTF_8); out.writeInt(value.size); out.write(value) }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

sealed interface InsertPagesResult {
    data class Applied(val pageIds: List<String>, val replayed: Boolean = false) : InsertPagesResult
    data object OrderChanged : InsertPagesResult
    data object Unavailable : InsertPagesResult
    data object CapacityReached : InsertPagesResult
    data object CommandReused : InsertPagesResult
}
