// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import leakcanary.AppWatcher

/** Watch only permanent release, after callbacks and jobs have been cleared. */
internal object LeakTracking {
    fun watchReleased(instance: Any, description: String) {
        AppWatcher.objectWatcher.expectWeaklyReachable(instance, description)
    }
}
