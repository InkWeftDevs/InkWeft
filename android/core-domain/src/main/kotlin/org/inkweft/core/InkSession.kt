// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** Single UI actor; commands are immutable and have one bounded, ordered queue. */
class InkSession(initial: InkPage) {
    var page: InkPage = initial; private set
    var blocked: InkCommitResult? = null; private set
    var pending: CommitInk? = null; private set
    private val queue = ArrayDeque<InkMutation>()
    private val undo = ArrayDeque<InkMutation.Visibility>()
    private val redo = ArrayDeque<InkMutation.Visibility>()
    private var historyMove: Boolean? = null
    val queued: Int get() = queue.size + if (pending == null) 0 else 1
    val canStart: Boolean get() = blocked == null && queued < 16 &&
        page.strokes.size + queued < InkLimits.MAX_STROKES &&
        page.strokes.sumOf { it.stroke.samples.size } +
        (listOfNotNull(pending?.mutation) + queue).sumOf { if (it is InkMutation.Add) it.stroke.samples.size else 0 } +
        InkLimits.MAX_POINTS <= InkLimits.MAX_PAGE_POINTS
    val canUndo: Boolean get() = queued == 0 && blocked == null && undo.isNotEmpty()
    val canRedo: Boolean get() = queued == 0 && blocked == null && redo.isNotEmpty()

    /** Includes completed, queued author input; failures retain it, never roll it back silently. */
    fun visibleDraft(): List<InkStroke> {
        val rows = page.strokes.associateByTo(LinkedHashMap()) { it.stroke.id }
        val changes = listOfNotNull(pending?.mutation) + queue
        for (change in changes) when(change) {
            is InkMutation.Add -> rows[change.stroke.id] = StoredInk(change.stroke,true,page.revision + 1)
            is InkMutation.Visibility -> change.ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(visible=change.visible) } }
        }
        return rows.values.filter { it.visible }.map { it.stroke }
    }

    fun enqueue(change: InkMutation, finishInFlight: Boolean = false) {
        check(canStart || (finishInFlight && queued < 17)) { "Resolve pending storage before accepting more input" }
        if (change is InkMutation.Add) {
            val current = visibleDraft()
            require(page.strokes.size + queued < InkLimits.MAX_STROKES)
            require(page.strokes.sumOf { it.stroke.samples.size } + (listOfNotNull(pending?.mutation) + queue).sumOf { if(it is InkMutation.Add)it.stroke.samples.size else 0 } + change.stroke.samples.size <= InkLimits.MAX_PAGE_POINTS)
            require(page.strokes.none { it.stroke.id == change.stroke.id } && current.none { it.id == change.stroke.id })
        }
        queue.addLast(change)
    }

    fun nextCommand(id: () -> String = { UUID.randomUUID().toString() }): CommitInk? {
        if (blocked != null) return null
        if (pending == null && queue.isNotEmpty()) pending = CommitInk(id(),page.noteId,page.revision,queue.removeFirst())
        return pending
    }
    fun retry(): CommitInk? {
        if (blocked != InkCommitResult.Unknown) return null
        blocked = null
        return pending
    }
    fun complete(command: CommitInk, result: InkCommitResult) {
        check(command === pending) { "Receipt is not for the active immutable command" }
        if (result !is InkCommitResult.Committed) { blocked = result; return }
        require(result.revision == command.expectedRevision + 1)
        val rows=page.strokes.associateByTo(LinkedHashMap()) { it.stroke.id }
        val inverse = when(val change=command.mutation) {
            is InkMutation.Add -> {
                rows[change.stroke.id] = StoredInk(change.stroke,true,result.revision)
                InkMutation.Visibility(listOf(change.stroke.id),false)
            }
            is InkMutation.Visibility -> {
                require(change.ids.all { rows[it]?.visible == !change.visible })
                change.ids.forEach { rows[it] = checkNotNull(rows[it]).copy(visible=change.visible) }
                InkMutation.Visibility(change.ids,!change.visible)
            }
        }
        page = InkPage(page.noteId,result.revision,rows.values.toList())
        when(historyMove) {
            true -> { undo.removeLast(); redo.addLast(inverse) }
            false -> { redo.removeLast(); undo.addLast(inverse) }
            null -> { undo.addLast(inverse); redo.clear() }
        }
        while(undo.size > 50) undo.removeFirst()
        historyMove=null;pending=null;blocked=null
    }
    fun requestUndo() { check(canUndo); historyMove=true; queue.addLast(undo.last()) }
    fun requestRedo() { check(canRedo); historyMove=false; queue.addLast(redo.last()) }
}
