// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

/** Keeps normal tests and performance measurements free of heap watchers. */
internal object LeakTracking {
    @Suppress("UNUSED_PARAMETER")
    fun watchReleased(instance: Any, description: String) = Unit
}
