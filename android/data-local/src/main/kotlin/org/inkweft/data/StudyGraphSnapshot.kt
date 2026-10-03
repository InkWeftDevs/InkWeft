// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.inkweft.core.*

/** One database read owns every value used to display or author the selected graph. */
data class StudyGraphSnapshot(
    val ref: MapRef,
    val cards: List<StudyCardRow>,
    val nodes: List<StudyNodeRow>,
    val allMainNodes: List<StudyNodeRow>,
    val definition: KnowledgeRow?,
    val order: KnowledgeRow?,
    val orderedNodeIds: List<String>,
    val graphFingerprint: String,
    val state: StudyGraphState,
)

internal fun graphOrder(rows: List<KnowledgeRow>, mapId: String?): KnowledgeRow? {
    val matches = rows.filter { row -> !row.removed && (row.data() as? KnowledgeData.MapOrder)?.let { it.mapId == mapId } == true }
    require(matches.size <= 1) { "MAP_ORDER_EXISTS" }
    return matches.singleOrNull()
}

/** Missing IDs are new nodes; callers pass the previous order before changing any coordinates. */
internal fun canonicalGraphOrder(nodes: List<StudyNode>, preferred: List<String>): List<String> {
    val live = nodes.filterNot { it.removed }.map { it.id }.toSet()
    val kept = preferred.filter { it in live }.distinct()
    val complete = kept + StudyOrganization.legacyOrder(nodes).filter { it !in kept }
    return StudyOrganization.canonicalOrder(nodes, complete)
}

internal fun graphNodes(book: String, mapId: String?, main: List<StudyNodeRow>, rows: List<KnowledgeRow>): List<StudyNodeRow> {
    if (mapId == null) return main
    val definition = rows.find { it.id == mapId && it.notebookId == book }
    val structures = (definition?.data() as? KnowledgeData.MapDefinition)?.structures.orEmpty().map {
        val owner = checkNotNull(definition)
        StudyNodeRow(it.id, book, it.id, it.parentId, it.x, it.y, owner.revision, owner.removed)
    }
    return structures + rows.mapNotNull { row ->
        if (row.notebookId != book) return@mapNotNull null
        (row.data() as? KnowledgeData.MapOccurrence)?.takeIf { it.mapId == mapId }?.let {
            StudyNodeRow(row.id, book, it.cardId, it.parentId, it.x, it.y, row.revision, row.removed)
        }
    }
}

internal fun graphSnapshot(
    ref: MapRef,
    cards: List<StudyCardRow>,
    main: List<StudyNodeRow>,
    rows: List<KnowledgeRow>,
): StudyGraphSnapshot {
    val ownRows = rows.filter { it.notebookId == ref.notebookId }
    val definition = ref.mapId?.let { id -> ownRows.find { it.id == id && it.data() is KnowledgeData.MapDefinition } }
    val structural = (definition?.takeUnless { it.removed }?.data() as? KnowledgeData.MapDefinition)?.structures.orEmpty().map { it.id }.toSet()
    val rawNodes = graphNodes(ref.notebookId, ref.mapId, main, ownRows)
    val models = rawNodes.map { it.model() }
    val order = graphOrder(ownRows, ref.mapId)
    val savedOrder = (order?.data() as? KnowledgeData.MapOrder)?.orderedNodeIds
    val orderedIds = if (savedOrder == null) StudyOrganization.legacyOrder(models) else {
        require(savedOrder.toSet() == models.filterNot { it.removed }.map { it.id }.toSet()) { "MAP_ORDER_MEMBERSHIP" }
        require(StudyOrganization.canonicalOrder(models, savedOrder) == savedOrder) { "MAP_ORDER_HIERARCHY" }
        savedOrder
    }
    val byId = rawNodes.associateBy { it.id }
    val orderedNodes = orderedIds.map(byId::getValue) + rawNodes.filter { it.removed }.sortedBy { it.id }
    val cardIds = orderedNodes.filterNot { it.removed || it.id in structural }.map { it.cardId }.toSet()
    val versions = cards.filter { it.id in cardIds }.sortedBy { it.id }.map { StudyCardVersion(it.id, it.revision, it.trashedAt) }
    require(versions.map { it.cardId }.toSet() == cardIds) { "MAP_CARD_UNAVAILABLE" }
    val state = StudyGraphState(ref, orderedNodes.map { it.model() }, orderedIds,
        order?.revision ?: 0, definition?.revision ?: 0, structural, versions)
    return StudyGraphSnapshot(ref, cards, orderedNodes, main, definition, order, orderedIds,
        StudyOrganization.fingerprint(state), state)
}
