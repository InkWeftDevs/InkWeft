// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** A dictionary entry comes only from an existing, author-confirmed Link. */
data class KnowledgeTextTarget(
    val linkId: String,
    val linkRevision: Long,
    val target: TargetRef,
    val relation: RelationKind,
    val pinnedRevision: Long?,
    val label: String,
    val terms: List<String>,
    val available: Boolean,
) {
    init {
        UUID.fromString(linkId)
        require(linkRevision > 0)
        require(pinnedRevision == null || target.kind == TargetKind.CARD && pinnedRevision > 0)
    }
}

/** Offsets address the original UTF-16 string; no body text or author relation is changed. */
data class KnowledgeTextSpan(val start: Int, val end: Int, val linkIds: List<String>) {
    init {
        require(start >= 0 && end > start)
        require(linkIds.isNotEmpty() && linkIds.distinct().size == linkIds.size)
        linkIds.forEach(UUID::fromString)
    }
}

object KnowledgeTextLinks {
    const val MAX_TARGETS = 128
    const val MAX_TERMS = 512
    const val MAX_SPANS = 256
    const val MAX_TEXT = 20_000

    private data class Term(val value: String, val links: MutableSet<String>)

    /** First matching position wins; the longest term there retains every equal-name Link. */
    fun spans(text: String, targets: List<KnowledgeTextTarget>): List<KnowledgeTextSpan> {
        if (text.length > MAX_TEXT || targets.size > MAX_TARGETS ||
            targets.sumOf { it.terms.size.toLong() } > MAX_TERMS) return emptyList()
        val terms = mutableListOf<Term>()
        targets.forEach { target ->
            target.terms.filter { it.isNotBlank() }.forEach { value ->
                val existing = terms.find { it.value.equals(value, ignoreCase = true) }
                if (existing == null) terms.add(Term(value, mutableSetOf(target.linkId)))
                else existing.links.add(target.linkId)
            }
        }
        if (terms.isEmpty()) return emptyList()
        val ordered = terms.sortedWith(compareByDescending<Term> { it.value.length }.thenBy { it.value })
        val result = mutableListOf<KnowledgeTextSpan>()
        var start = 0
        while (start < text.length) {
            val term = ordered.find { term ->
                val end = start + term.value.length
                end <= text.length && codePointBoundary(text, end) &&
                    (!asciiWord(term.value.first()) || start == 0 || !asciiWord(text[start - 1])) &&
                    (!asciiWord(term.value.last()) || end == text.length || !asciiWord(text[end])) &&
                    text.regionMatches(start, term.value, 0, term.value.length, ignoreCase = true)
            }
            if (term == null) start += Character.charCount(text.codePointAt(start))
            else {
                val end = start + term.value.length
                result.add(KnowledgeTextSpan(start, end, term.links.sorted()))
                if (result.size > MAX_SPANS) return emptyList()
                start = end
            }
        }
        return result
    }

    private fun asciiWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    private fun codePointBoundary(text: String, offset: Int) = offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
}
