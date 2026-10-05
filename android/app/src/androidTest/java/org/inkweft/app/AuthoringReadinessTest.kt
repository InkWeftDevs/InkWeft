// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.junit.Assert.*
import org.junit.Test

/** Existing AuthoringUi.ready truth table, not a simulation of journal, Room, or Compose loading. */
class AuthoringReadinessTest {
    @Test fun readinessRequiresLoadingBusyAndPendingToBeClear() {
        for (loading in listOf(false, true)) for (busy in listOf(false, true)) for (pending in listOf(false, true)) {
            val ui = AuthoringUi(loading = loading, busy = busy, pending = pending)
            assertEquals("loading=$loading busy=$busy pending=$pending", !loading && !busy && !pending, ui.ready)
        }
        val initial = AuthoringUi()
        assertFalse("An unread recovery journal is not ready", initial.ready)
        val recovering = initial.copy(loading = false, busy = true, pending = true)
        assertFalse(recovering.ready)
        val unconfirmed = recovering.copy(busy = false)
        assertFalse("Finishing IO alone must not clear a pending operation", unconfirmed.ready)
        assertTrue(unconfirmed.copy(pending = false).ready)
    }
}
