// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/**
 * A read started by an explicit discard action may only replace that exact draft.
 * Referential identity is intentional: editing and then reverting text still
 * invalidates the old request (structural equality would miss that ABA case).
 * Call inside the UI state's atomic update, after the asynchronous read returns.
 */
fun NoteDraft.acceptExplicitReload(requested: NoteDraft, loaded: Note): NoteDraft {
    require(requested.base.id == loaded.id) { "Reload belongs to another note" }
    if (this !== requested || phase == SavePhase.SAVING || phase == SavePhase.UNKNOWN) {
        return this
    }
    return NoteDraft(loaded)
}
