// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.inkweft.core.*

data class KnowledgeTextPreview(
    val target: TargetRef,
    val title: String,
    val body: String,
    val canOpen: Boolean,
    val pinnedRevision: Long?,
)

/** Projects confirmed relationships only. Every read is transactional and never creates a receipt. */
class KnowledgeTextRepository(private val db: NoteDatabase) {
    private fun changes() = db.invalidationTracker.createFlow(
        "knowledge_records", "study_cards", "study_card_revisions", "notes",
        "notebook_workspace", "notebook_pages", "ink_pages",
    )

    fun observe(source: TargetRef): Flow<List<KnowledgeTextTarget>> = changes().map {
        db.withTransaction {
            val reader = Reader()
            val book = reader.owner(source) ?: return@withTransaction emptyList<KnowledgeTextTarget>()
            db.knowledge().forBook(book).filter { !it.removed }.mapNotNull { row ->
                val link = row.data() as? KnowledgeData.Link ?: return@mapNotNull null
                if (link.source != source) return@mapNotNull null
                val content = reader.content(link.target, link.pinnedRevision)
                val aliases = if (link.target.kind == TargetKind.CARD && link.pinnedRevision == null && content.book != null)
                    reader.aliases(content.book)[link.target.id].orEmpty() else emptyList()
                val notebook = content.book?.let { reader.note(it)?.title } ?: "所属笔记不可用"
                KnowledgeTextTarget(row.id, row.revision, link.target, link.relation, link.pinnedRevision,
                    "${content.title} · $notebook", (content.terms + aliases).distinct(), content.canOpen)
            }
        }
    }.distinctUntilChanged()

    fun observePreview(source: TargetRef, linkId: String, linkRevision: Long, incoming: Boolean = false,
                       includeAllIncomingRelations: Boolean = false): Flow<KnowledgeTextPreview> =
        changes().map { preview(source, linkId, linkRevision, incoming, includeAllIncomingRelations) }.distinctUntilChanged()

    suspend fun preview(source: TargetRef, linkId: String, linkRevision: Long, incoming: Boolean = false,
                        includeAllIncomingRelations: Boolean = false): KnowledgeTextPreview = db.withTransaction {
        val row = db.knowledge().get(linkId)
        if (row == null || row.removed) throw KnowledgeRejected(KnowledgeRejection.UNAVAILABLE)
        val link = row.data() as? KnowledgeData.Link ?: throw KnowledgeRejected(KnowledgeRejection.INVALID)
        val reader = Reader()
        val matches = if (incoming) link.target == source &&
            (link.relation == RelationKind.REFERENCE || includeAllIncomingRelations) else link.source == source
        if (row.revision != linkRevision || !matches || reader.owner(link.source) != row.notebookId)
            throw KnowledgeRejected(KnowledgeRejection.CONFLICT)
        val target = if (incoming) link.source else link.target
        val pinned = if (incoming) null else link.pinnedRevision
        val content = reader.content(target, pinned)
        KnowledgeTextPreview(target, content.title, content.body, content.canOpen, pinned)
    }

    private data class Content(val title: String, val body: String, val book: String?, val terms: List<String>, val canOpen: Boolean)

    /** Caches stay within one transaction; aliases never alter a pinned historical title. */
    private inner class Reader {
        private val notes = mutableMapOf<String, NoteRow?>()
        private val activeBooks = mutableMapOf<String, Boolean>()
        private val aliasesByBook = mutableMapOf<String, Map<String, List<String>>>()
        private val contents = mutableMapOf<Pair<TargetRef, Long?>, Content>()

        suspend fun note(book: String): NoteRow? {
            if (!notes.containsKey(book)) notes[book] = db.notes().note(book)
            return notes[book]
        }

        private suspend fun active(book: String): Boolean {
            if (!activeBooks.containsKey(book)) {
                val workspace = db.workspace().get(book)
                activeBooks[book] = note(book) != null && workspace != null && workspace.trashedAt == null
            }
            return activeBooks.getValue(book)
        }

        suspend fun owner(ref: TargetRef): String? = when (ref.kind) {
            TargetKind.NOTE -> note(ref.id)?.id
            TargetKind.PAGE -> db.pages().get(ref.id)?.notebookId
            TargetKind.CARD -> db.study().card(ref.id)?.notebookId
            TargetKind.ANCHOR -> {
                val row = db.knowledge().get(ref.id)
                val anchor = row?.data() as? KnowledgeData.Anchor
                if (row != null && anchor != null && db.pages().get(anchor.pageId)?.notebookId == row.notebookId)
                    row.notebookId else null
            }
        }

        suspend fun aliases(book: String): Map<String, List<String>> {
            if (!aliasesByBook.containsKey(book)) aliasesByBook[book] = db.knowledge().forBook(book)
                .filter { !it.removed }.mapNotNull { it.data() as? KnowledgeData.Alias }
                .groupBy({ it.cardId }, { it.name })
            return aliasesByBook.getValue(book)
        }

        suspend fun content(ref: TargetRef, pinned: Long?): Content {
            val key = ref to pinned
            contents[key]?.let { return it }
            val result = when (ref.kind) {
                TargetKind.CARD -> {
                    val card = db.study().card(ref.id)
                    val book = card?.notebookId
                    val canOpen = card != null && card.trashedAt == null && active(card.notebookId)
                    if (pinned != null) {
                        val version = db.study().cardVersion(ref.id, pinned)
                        if (version == null) Content("固定版本不可用", "固定版本不可用（修订 $pinned），原关联仍保留。", book, emptyList(), false)
                        else Content(version.title, version.body, book, listOf(version.title), canOpen)
                    } else if (card == null) Content("知识卡不可用", "关联目标不可用，原关联仍保留。", null, emptyList(), false)
                    else Content(card.title, card.body, book, listOf(card.title), canOpen)
                }
                TargetKind.NOTE -> {
                    val notebook = note(ref.id)
                    if (notebook == null) Content("笔记不可用", "关联目标不可用，原关联仍保留。", null, emptyList(), false)
                    else Content(notebook.title, notebook.text, notebook.id, listOf(notebook.title), active(notebook.id))
                }
                TargetKind.PAGE -> {
                    val page = db.pages().get(ref.id)
                    if (page == null) Content("来源页不可用", "打开来源页查看；当前目标不可用，原关联仍保留。", null, emptyList(), false)
                    else Content("第 ${page.position + 1} 页", "打开来源页查看。", page.notebookId, emptyList(), page.trashedAt == null && active(page.notebookId))
                }
                TargetKind.ANCHOR -> {
                    val row = db.knowledge().get(ref.id)
                    val anchor = row?.data() as? KnowledgeData.Anchor
                    val page = anchor?.let { db.pages().get(it.pageId) }
                    val valid = row != null && anchor != null && page?.notebookId == row.notebookId
                    val changed = anchor != null && (db.ink().page(anchor.pageId)?.revision ?: 0) != anchor.inkRevision
                    Content("来源区域", if (changed) "来源已变化：仅可查看原区域位置，需重新核对关联。" else "打开来源区域查看。", row?.notebookId, emptyList(),
                        valid && row != null && !row.removed && page != null && page.trashedAt == null && active(row.notebookId))
                }
            }
            contents[key] = result
            return result
        }
    }
}
