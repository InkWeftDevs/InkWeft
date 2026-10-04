// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

/** True accepts an asynchronous request, not permission to expose content now.
 * Only invoke afterPersisted after the same attempt's ORIGINAL fact is durable. */
internal typealias RecallOriginalGate = (afterPersisted: () -> Unit) -> Boolean
