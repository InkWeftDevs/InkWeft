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
    const val MAX_TARGETS = 512
    const val MAX_TERMS = 2048
    const val MAX_SPANS = 1024
    const val MAX_TEXT = 20_000
    const val MAX_DICTIONARY_CHARS = 256_000

    private data class Term(val value: String, val links: MutableSet<String>)
    private class Trie { val children=mutableMapOf<Int,Trie>();var term:Term?=null }
    private fun folded(codePoint:Int)=Character.toLowerCase(Character.toUpperCase(codePoint))
    private fun key(value:String)=buildString{
        var offset=0
        while(offset<value.length){val codePoint=value.codePointAt(offset);appendCodePoint(folded(codePoint));offset+=Character.charCount(codePoint)}
    }

    /** First matching position wins; the longest term there retains every equal-name Link. */
    fun spans(text: String, targets: List<KnowledgeTextTarget>): List<KnowledgeTextSpan> {
        if (text.length > MAX_TEXT || targets.size > MAX_TARGETS ||
            targets.sumOf { it.terms.size.toLong() } > MAX_TERMS ||
            targets.sumOf { it.terms.sumOf{term->term.length.toLong()} } > MAX_DICTIONARY_CHARS) return emptyList()
        val terms = linkedMapOf<String,Term>()
        targets.forEach { target ->
            target.terms.filter { it.isNotBlank()&&it.length<=text.length }.forEach { value ->
                val key=key(value)
                terms.getOrPut(key){Term(value,mutableSetOf())}.links.add(target.linkId)
            }
        }
        if (terms.isEmpty()) return emptyList()
        val root=Trie()
        terms.forEach{(key,term)->var node=root;key.codePoints().forEach{node=node.children.getOrPut(it){Trie()}};node.term=term}
        val result = mutableListOf<KnowledgeTextSpan>()
        var start = 0
        while (start < text.length) {
            var node=root;var end=start;var term:Term?=null
            while(end<text.length){
                val codePoint=text.codePointAt(end)
                node=node.children[folded(codePoint)]?:break;end+=Character.charCount(codePoint)
                val candidate=node.term?:continue
                if(codePointBoundary(text,end)&&
                    (!asciiWord(candidate.value.first())||start==0||!asciiWord(text[start-1]))&&
                    (!asciiWord(candidate.value.last())||end==text.length||!asciiWord(text[end])))term=candidate
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
